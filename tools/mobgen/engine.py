"""Motor del generador de mobs: DSL de nodos/cajas, pintor de texturas, atlas, exportación al resource pack y vista previa.

Espacio del mob (píxeles; 16 = 1 bloque): origen en el suelo bajo el centro, +Y arriba, el FRENTE es +Z,
+X es la IZQUIERDA del mob. Rotaciones: R = Rx * Ry * Rz (igual que JOML rotateX().rotateY().rotateZ()).
Un nodo con rx>0 inclina su "arriba" hacia delante (+Z); un miembro que cuelga con rx<0 se levanta hacia delante.
"""
import json, math, os, random, zipfile, hashlib, zlib
import numpy as np
from PIL import Image

NS = "custommobs"

# --------------------------------------------------------------------------- estilos

def rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


class Style:
    def __init__(self, pal, bias=1.3, spread=1.1, speck=None, cracks=None, stripes=False, rivets=False,
                 trim=None, glow=False, rows=None, seed=0):
        self.pal = [rgb(c) if isinstance(c, str) else c for c in pal]
        self.bias, self.spread, self.speck, self.cracks = bias, spread, speck, cracks
        self.stripes, self.rivets, self.trim, self.glow, self.rows, self.seed = stripes, rivets, trim, glow, rows, seed


def S(pal, **kw):
    return Style(pal, **kw)


class Box:
    def __init__(self, x0, x1, y0, y1, z0, z1, style):
        self.lo = (min(x0, x1), min(y0, y1), min(z0, z1))
        self.hi = (max(x0, x1), max(y0, y1), max(z0, z1))
        self.style = style
        self.region = None  # (u, v) en el atlas

    @property
    def size(self):
        return tuple(self.hi[i] - self.lo[i] for i in range(3))

    def px(self):
        dx, dy, dz = self.size
        return max(1, round(dx)), max(1, round(dy)), max(1, round(dz))


def B(x0, x1, y0, y1, z0, z1, style):
    return Box(x0, x1, y0, y1, z0, z1, style)


class Node:
    def __init__(self, id, pivot, boxes=None, parent=None, rest=(0, 0, 0), glow=False, **anim):
        self.id, self.pivot, self.boxes, self.parent = id, tuple(pivot), boxes or [], parent
        self.rest, self.glow, self.anim = tuple(rest), glow, anim


class Mob:
    def __init__(self, id, name, nodes, scale=1.0, base=""):
        self.id, self.name, self.nodes, self.scale, self.base = id, name, nodes, scale, base
        self.tex_size = 64


# --------------------------------------------------------------------------- atlas

def pack(mob):
    """Coloca cada caja en un atlas cuadrado (potencia de 2) con un empaquetado por estantes."""
    items = []
    for n in mob.nodes:
        for b in n.boxes:
            dx, dy, dz = b.px()
            items.append((2 * dz + 2 * dx, dz + dy, b))
    items.sort(key=lambda t: -t[1])
    for T in (64, 128, 256, 512):
        x = y = shelf = 0
        ok = True
        for w, h, b in items:
            if w > T:
                ok = False
                break
            if x + w > T:
                x, y, shelf = 0, y + shelf, 0
            if y + h > T:
                ok = False
                break
            b.region = (x, y)
            x += w
            shelf = max(shelf, h)
        if ok:
            mob.tex_size = T
            return
    raise RuntimeError("atlas demasiado grande: " + mob.id)


def faces_of(b):
    """Regiones del atlas por cara: nombre -> (u, v, w, h)."""
    dx, dy, dz = b.px()
    u, v = b.region
    return {
        "up": (u + dz, v, dx, dz), "down": (u + dz + dx, v, dx, dz),
        "west": (u, v + dz, dz, dy), "south": (u + dz, v + dz, dx, dy),
        "east": (u + dz + dx, v + dz, dz, dy), "north": (u + 2 * dz + dx, v + dz, dx, dy),
    }


# --------------------------------------------------------------------------- pintor

def paint(mob):
    T = mob.tex_size
    img = np.zeros((T, T, 4), dtype=np.uint8)
    for n in mob.nodes:
        for bi, b in enumerate(n.boxes):
            st = b.style
            rng = random.Random(zlib.crc32(f"{mob.id}|{n.id}|{bi}|{st.seed}".encode()))  # determinista
            for face, (u, v, w, h) in faces_of(b).items():
                paint_face(img, u, v, w, h, face, st, rng)
    return Image.fromarray(img, "RGBA")


def clampi(i, n):
    return max(0, min(n - 1, int(round(i))))


def paint_face(img, u, v, w, h, face, st, rng):
    pal = st.pal
    n = len(pal)
    if st.glow:
        cx, cy = (w - 1) / 2, (h - 1) / 2
        for y in range(h):
            for x in range(w):
                d = max(abs(x - cx) / max(0.5, w / 2), abs(y - cy) / max(0.5, h / 2))
                idx = clampi((1 - d * 0.7) * (n - 1) + rng.uniform(-0.3, 0.3), n)
                img[v + y, u + x] = (*pal[idx], 255)
        return
    cols = [rng.uniform(-st.spread, st.spread) for _ in range(w)] if st.stripes else None
    for y in range(h):
        for x in range(w):
            base = st.bias + rng.uniform(-st.spread, st.spread)
            if face == "up":
                base += 0.9
            elif face == "down":
                base -= 1.1
            else:
                base += (0.5 - y / max(1, h - 1)) * 0.9  # más claro arriba, más oscuro abajo
            if st.stripes:
                base += cols[x] * 0.8
            if st.rows:
                base += 0.7 if (y % st.rows) == 0 else 0
            edge = (x == 0 or x == w - 1) and w > 2
            if edge:
                base -= 0.5
            idx = clampi(base, n)
            c = pal[idx]
            if st.speck and rng.random() < st.speck[0]:
                c = rgb(st.speck[1]) if isinstance(st.speck[1], str) else st.speck[1]
            img[v + y, u + x] = (*c, 255)
    if st.cracks:
        cnt, ln, col = st.cracks
        col = rgb(col) if isinstance(col, str) else col
        for _ in range(cnt):
            x, y = rng.randrange(w), rng.randrange(h)
            for _ in range(ln):
                img[v + y, u + x] = (*col, 255)
                x = clampi(x + rng.choice((-1, 0, 0, 1)), w)
                y = clampi(y + rng.choice((0, 1, 1, -1)), h)
    if st.rivets and w >= 3 and h >= 3:
        c = pal[-1]
        for (x, y) in ((1, 1), (w - 2, 1), (1, h - 2), (w - 2, h - 2)):
            img[v + y, u + x] = (*c, 255)
    if st.trim:
        tc = rgb(st.trim) if isinstance(st.trim, str) else st.trim
        for x in range(w):
            img[v, u + x] = (*tc, 255)
            img[v + h - 1, u + x] = (*tc, 255)


# --------------------------------------------------------------------------- matemáticas

def rx(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def rot(r):
    return rx(r[0]) @ ry(r[1]) @ rz(r[2])


# --------------------------------------------------------------------------- exportación

def model_json(mob, node):
    T = mob.tex_size
    elems = []
    for b in node.boxes:
        faces = {}
        for fname, (u, v, w, h) in faces_of(b).items():
            faces[fname] = {"uv": [round(u * 16 / T, 4), round(v * 16 / T, 4),
                                   round((u + w) * 16 / T, 4), round((v + h) * 16 / T, 4)],
                            "texture": "#0"}
        fr = [round(b.lo[i] - node.pivot[i] + 8, 4) for i in range(3)]
        to = [round(b.hi[i] - node.pivot[i] + 8, 4) for i in range(3)]
        for v in fr + to:
            if v < -16 or v > 32:
                raise ValueError(f"{mob.id}/{node.id}: caja fuera de rango (-16..32): {fr} {to}")
        elems.append({"from": fr, "to": to, "faces": faces})
    return {"textures": {"0": f"{NS}:item/mob/{mob.id}", "particle": f"{NS}:item/mob/{mob.id}"}, "elements": elems}


def export(mobs, out):
    rp = os.path.join(out, "resourcepack")
    for sub in ("assets/%s/textures/item/mob" % NS, "assets/%s/models/item" % NS, "assets/%s/items" % NS):
        os.makedirs(os.path.join(rp, sub), exist_ok=True)
    yml = ["# GENERADO por tools/mobgen/build.py: no editar a mano.", "mobs:"]
    for mob in mobs:
        pack(mob)
        paint(mob).save(os.path.join(rp, "assets/%s/textures/item/mob/%s.png" % (NS, mob.id)))
        yml += [f"  {mob.id}:", f"    scale: {mob.scale}", f"    name: \"{mob.name}\"", "    nodes:"]
        for n in mob.nodes:
            has = bool(n.boxes)
            key = f"{mob.id}_{n.id}".lower()  # Minecraft solo admite [a-z0-9/._-] en rutas
            if has:
                with open(os.path.join(rp, "assets/%s/models/item/%s.json" % (NS, key)), "w") as f:
                    json.dump(model_json(mob, n), f, separators=(",", ":"))
                with open(os.path.join(rp, "assets/%s/items/%s.json" % (NS, key)), "w") as f:
                    json.dump({"model": {"type": "minecraft:model", "model": f"{NS}:item/{key}"}}, f)
            yml.append(f"      - id: {n.id}")
            yml.append(f"        parent: {n.parent or 'null'}")
            yml.append("        pivot: [%s]" % ", ".join(str(round(v, 3)) for v in n.pivot))
            yml.append("        rest: [%s]" % ", ".join(str(v) for v in n.rest))
            yml.append(f"        model: {key if has else 'null'}")
            yml.append(f"        glow: {'true' if n.glow else 'false'}")
            for k, v in n.anim.items():
                yml.append(f"        {k}: {json.dumps(v)}")
    # recursos auxiliares: objeto sin modelo y armadura invisible (para ocultar el equipo del mob vanilla)
    with open(os.path.join(rp, "assets/%s/models/item/empty.json" % NS), "w") as f:
        json.dump({"elements": []}, f)
    with open(os.path.join(rp, "assets/%s/items/empty.json" % NS), "w") as f:
        json.dump({"model": {"type": "minecraft:model", "model": f"{NS}:item/empty"}}, f)
    os.makedirs(os.path.join(rp, "assets/%s/equipment" % NS), exist_ok=True)
    with open(os.path.join(rp, "assets/%s/equipment/invisible.json" % NS), "w") as f:
        json.dump({"layers": {"humanoid": [{"texture": f"{NS}:invisible"}],
                              "humanoid_leggings": [{"texture": f"{NS}:invisible"}]}}, f)
    for layer in ("humanoid", "humanoid_leggings"):
        d = os.path.join(rp, "assets/%s/textures/entity/equipment/%s" % (NS, layer))
        os.makedirs(d, exist_ok=True)
        Image.new("RGBA", (64, 32), (0, 0, 0, 0)).save(os.path.join(d, "invisible.png"))
    # franjas de cine: un único glifo gigante (negro arriba y abajo, transparente en medio) que se muestra como título.
    # Ajustado para escala de GUI 3-4: la franja ocupa ~1/4 de la altura a mitad de pantalla; mayor/menor escala varía el grosor.
    fd = os.path.join(rp, "assets/%s/font" % NS)
    td = os.path.join(rp, "assets/%s/textures/font" % NS)
    os.makedirs(fd, exist_ok=True)
    os.makedirs(td, exist_ok=True)
    bar = Image.new("RGBA", (256, 128), (0, 0, 0, 0))
    for y in range(128):
        if y < 39 or y >= 128 - 39:
            for x in range(256):
                bar.putpixel((x, y), (0, 0, 0, 255))
    bar.save(os.path.join(td, "letterbox.png"))
    with open(os.path.join(fd, "cinema.json"), "w") as f:
        json.dump({"providers": [{"type": "bitmap", "file": f"{NS}:font/letterbox.png", "ascent": 72, "height": 150,
                                  "chars": ["\ue000"]}]}, f)
    with open(os.path.join(rp, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "CustomMobs · Ceniza e Infierno", "min_format": [84, 0],
                            "max_format": [99, 0]}}, f, indent=2)
    icon = Image.new("RGBA", (64, 64), (20, 16, 18, 255))
    for i in range(64):
        for j in range(64):
            d = math.hypot(i - 32, j - 36)
            if d < 18:
                t = d / 18
                icon.putpixel((i, j), (int(255 - 120 * t), int(150 - 110 * t), int(30 - 10 * t), 255))
    icon.save(os.path.join(rp, "pack.png"))
    with open(os.path.join(out, "mobmodels.yml"), "w") as f:
        f.write("\n".join(yml) + "\n")


def make_zip(out, dest):
    rp = os.path.join(out, "resourcepack")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    files = []
    for root, _, fs in os.walk(rp):
        for fn in fs:
            p = os.path.join(root, fn)
            files.append((os.path.relpath(p, rp).replace(os.sep, "/"), p))
    files.sort()
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for arc, p in files:
            zi = zipfile.ZipInfo(arc, (2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, open(p, "rb").read())
    return hashlib.sha1(open(dest, "rb").read()).hexdigest()


# --------------------------------------------------------------------------- vista previa (rasterizador de prueba)

SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
NORMAL = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}


def face_corners(b, face):
    (x0, y0, z0), (x1, y1, z1) = b.lo, b.hi
    # esquinas en orden: arriba-izq, arriba-der, abajo-der, abajo-izq (según el espectador de la cara)
    if face == "south":
        return [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)]
    if face == "north":
        return [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)]
    if face == "east":
        return [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)]
    if face == "west":
        return [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)]
    if face == "up":
        return [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)]
    return [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)]


def pose(mob, walk=0.0, extra=None):
    """Devuelve {nodo: (Rw, Pw)} con la pose de reposo + paso de caminata (solo para la vista previa)."""
    by = {n.id: n for n in mob.nodes}
    res = {}
    for n in mob.nodes:
        r = list(n.rest)
        a = n.anim
        if walk and "walk_amp" in a:
            r[0] += math.sin(walk + math.radians(a.get("walk_phase", 0))) * a["walk_amp"]
        if extra and n.id in extra:
            for i in range(3):
                r[i] += extra[n.id][i]
        Rl = rot(r)
        if n.parent:
            Rp, Pp = res[n.parent]
            pp = np.array(by[n.parent].pivot)
            Rw = Rp @ Rl
            Pw = Pp + Rp @ (np.array(n.pivot) - pp)
        else:
            Rw, Pw = Rl, np.array(n.pivot, float)
        res[n.id] = (Rw, Pw)
    return res


def render_view(mob, tex, view_yaw, walk=0.0, size=(300, 360), scale=7.0, pitch=18):
    W, H = size
    col = np.zeros((H, W, 3), float)
    col[:] = (34, 32, 38)
    zbuf = np.full((H, W), -1e9)
    T = tex.shape[0]
    P = pose(mob, walk)
    cam = rx(-pitch) @ ry(-view_yaw)  # primero giro del mob, luego inclinación de cámara
    for n in mob.nodes:
        Rw, Pw = P[n.id]
        for b in n.boxes:
            for face, (u, v, w, h) in faces_of(b).items():
                nrm = Rw @ np.array(NORMAL[face])
                cn = cam @ nrm
                if cn[2] <= 0.0:
                    continue  # cara trasera (cámara mira hacia -Z de cámara)
                cs = face_corners(b, face)
                pts = []
                for c in cs:
                    wv = Pw + Rw @ (np.array(c) - np.array(n.pivot))
                    pts.append(cam @ wv)
                shade = 1.0 if (n.glow or b.style.glow) else (0.45 + 0.55 * SHADE[face] * (0.5 + 0.5 * max(0.0, np.dot(cn, (0.2, 0.7, 0.68)))))
                uvs = [(u, v), (u + w, v), (u + w, v + h), (u, v + h)]
                for tri in ((0, 1, 2), (0, 2, 3)):
                    raster(col, zbuf, [pts[i] for i in tri], [uvs[i] for i in tri], tex, shade, W, H, scale, n.glow or b.style.glow)
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), "RGB")


def raster(col, zbuf, p, uv, tex, shade, W, H, scale, glow):
    xs = [W / 2 + q[0] * scale for q in p]
    ys = [H * 0.82 - q[1] * scale for q in p]
    zs = [q[2] for q in p]
    x0, x1 = int(max(0, math.floor(min(xs)))), int(min(W - 1, math.ceil(max(xs))))
    y0, y1 = int(max(0, math.floor(min(ys))), ), int(min(H - 1, math.ceil(max(ys))))
    if x1 < x0 or y1 < y0:
        return
    gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
    d = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
    if abs(d) < 1e-9:
        return
    l1 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / d
    l2 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / d
    l3 = 1 - l1 - l2
    inside = (l1 >= -0.02) & (l2 >= -0.02) & (l3 >= -0.02)
    z = l1 * zs[0] + l2 * zs[1] + l3 * zs[2]
    uu = l1 * uv[0][0] + l2 * uv[1][0] + l3 * uv[2][0]
    vv = l1 * uv[0][1] + l2 * uv[1][1] + l3 * uv[2][1]
    tx = np.clip(uu.astype(int), 0, tex.shape[1] - 1)
    ty = np.clip(vv.astype(int), 0, tex.shape[0] - 1)
    texel = tex[ty, tx]
    ok = inside & (texel[..., 3] > 0)
    sub_z = zbuf[y0:y1 + 1, x0:x1 + 1]
    upd = ok & (z > sub_z - 1e-6 * 0 )
    # cámara mira hacia -Z: más cerca = z mayor
    sub_z[upd] = z[upd]
    c = texel[..., :3].astype(float) * shade
    if glow:
        c = np.minimum(255, c * 1.15 + 25)
    sub = col[y0:y1 + 1, x0:x1 + 1]
    sub[upd] = c[upd]


def preview_sheet(mobs, path):
    rows = []
    for mob in mobs:
        pack(mob)
        tex = np.array(paint(mob))
        views = [render_view(mob, tex, ya, walk=wk) for ya, wk in ((35, 0), (-35, 0), (90, 0), (180 + 30, 0), (35, 1.0))]
        rows.append(views)
    cw, ch = rows[0][0].size
    sheet = Image.new("RGB", (cw * 5, ch * len(rows)), (20, 20, 24))
    for r, views in enumerate(rows):
        for c, im in enumerate(views):
            sheet.paste(im, (c * cw, r * ch))
    sheet.save(path)
    return sheet
