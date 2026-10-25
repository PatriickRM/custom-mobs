"""Los 7 mobs de la dungeon (ceniza e infierno). Unidades en píxeles (16 = 1 bloque), frente = +Z, +X = izquierda."""
from engine import S, B, Node, Mob

# ----------------------------------------------------------------------------- paletas
ASH = ['#1f1d23', '#2e2c33', '#413f48', '#5a5761', '#77737c']
ASH_PALE = ['#3a3840', '#54515a', '#6f6b75', '#8d8892', '#aaa5ad']
CLOTH = ['#251913', '#372519', '#4d3524', '#684933']
RAG_GREY = ['#2a2828', '#3c3a3a', '#524f4f', '#6a6666']
BONE = ['#1a1817', '#2e2b28', '#524c44', '#8a806e', '#b8ad96']
BONE_CLEAN = ['#6e665a', '#948b79', '#bdb39d', '#ddd4bf', '#f0e8d4']
BARK = ['#181210', '#27201b', '#3a2f27', '#53443a', '#6e5b4c']
OBS = ['#0f0b16', '#1a1425', '#271f38', '#3b3053', '#554a74']
GOLD = ['#4d3a0e', '#7c5d17', '#b08a26', '#d9b640', '#f3d97a']
CRIM = ['#220809', '#3d0e11', '#5b1519', '#7a1f24', '#9b2c31']
CLOAK = ['#1a1230', '#271a45', '#38265e', '#4d3680', '#6a4ba3']
LEATHER = ['#1d120c', '#2d1c12', '#432b1b', '#5c3d27']
FUR = ['#16131a', '#221e28', '#322c3b', '#463e52', '#5d5470']
WOOD = ['#1b120b', '#2b1c10', '#3f2a18', '#573a22']

G_EMBER = ['#b03008', '#ff6a1a', '#ffa535', '#ffe08a']
G_RED = ['#9a0e08', '#e02212', '#ff5a36', '#ffb090']
G_VIOLET = ['#5a1590', '#9f3df0', '#d58cff', '#f6e0ff']
G_SOUL = ['#106f92', '#27b3d6', '#7be6f4', '#e0fdff']
G_LAVA = ['#c01a00', '#ff4d00', '#ff9a1a', '#ffe070']


def glow(p):
    return S(p, glow=True)


def mirror(boxes):
    """Copia las cajas reflejadas en X (izquierda <-> derecha)."""
    out = []
    for b in boxes:
        out.append(B(-b.hi[0], -b.lo[0], b.lo[1], b.hi[1], b.lo[2], b.hi[2], b.style))
    return out


# ============================================================================= 1. CENIZO
def cenizo():
    skin = S(ASH, bias=1.4, spread=1.0, speck=(0.05, '#8d8892'))
    skin_cr = S(ASH, bias=1.2, spread=1.0, cracks=(1, 4, '#c23a0a'))
    pale = S(ASH_PALE, bias=1.5, spread=1.0, speck=(0.08, '#ffffff'))
    cloth = S(CLOTH, bias=1.4, spread=1.0, stripes=True)
    bone = S(BONE, bias=2.0, spread=0.8)
    leg_boxes = lambda sx: [B(0.5 * sx, 4.5 * sx, 2, 12, -2, 2, skin),
                            B(0 if sx > 0 else -5, 5 if sx > 0 else 0, 3, 8, -2.5, 2.5, cloth),
                            B(0.5 * sx, 4.5 * sx, 0, 2, -2, 4, S(ASH, bias=0.8))]
    arm = lambda sx: [B(4.5 * sx, 7.5 * sx, 9, 23, -1.5, 1.5, skin_cr),
                      B(4.2 * sx, 7.8 * sx, 6.5, 9.5, -2, 2, S(ASH, bias=0.9)),
                      B(4.4 * sx, 5.2 * sx, 3, 6.5, -0.5, 0.5, bone), B(5.5 * sx, 6.3 * sx, 2.5, 6.5, -0.5, 0.5, bone),
                      B(6.6 * sx, 7.4 * sx, 3, 6.5, -0.5, 0.5, bone),
                      B(4 * sx, 8.5 * sx, 22, 25, -2.5, 2.5, pale)]
    arm_r = lambda: mirror(arm(1))
    nodes = [
        Node('legL', (2.5, 12, 0), leg_boxes(1), walk_amp=34, walk_phase=0),
        Node('legR', (-2.5, 12, 0), mirror(leg_boxes(1)), walk_amp=34, walk_phase=180),
        Node('body', (0, 12, 0), [
            B(-4, 4, 12, 24, -2, 2, skin_cr), B(-4.6, 4.6, 12, 14.2, -2.6, 2.6, cloth),
            B(-3.5, 3.5, 6.5, 12, 2.3, 2.9, cloth), B(-3.5, 3.5, 7.5, 12, -2.9, -2.3, cloth),
            B(-4.4, 0.5, 17, 24.3, 2.1, 2.6, cloth),
            B(-2.2, -1.2, 15, 17, -4, -2, bone), B(0, 1, 18, 20.5, -4.6, -2, bone), B(1.8, 2.8, 21, 23.5, -4.2, -2, bone),
        ], rest=(14, 0, 0), idle_amp=2.0, idle_axis='x', idle_speed=1.4),
        Node('body_glow', (0, 12, 0), [
            B(-0.5, 0.5, 14, 22, 2.0, 2.15, glow(G_EMBER)), B(-3, -0.5, 19.5, 20.5, 2.0, 2.15, glow(G_EMBER)),
            B(0.5, 3, 16.5, 17.5, 2.0, 2.15, glow(G_EMBER)), B(4.0, 4.15, 15, 21, -0.5, 0.5, glow(G_EMBER)),
            B(-2.2, -1.2, 16.6, 17.2, -4.3, -3.9, glow(G_EMBER)), B(0, 1, 20.1, 20.7, -4.9, -4.5, glow(G_EMBER)),
            B(1.8, 2.8, 23.1, 23.7, -4.5, -4.1, glow(G_EMBER)),
        ], parent='body', glow=True),
        Node('head', (0, 24, 0), [
            B(-4, 4, 25, 32, -4, 4, skin_cr), B(-3, 3, 21, 24, -3.2, 3, S(ASH, bias=0.6)),
            B(-3.5, -1.5, 32, 34, -3, -1, S(ASH, bias=0.7)), B(0, 2, 32, 35.5, 0, 2, S(ASH, bias=0.7)),
            B(2.5, 4, 32, 34, -2, 0, S(ASH, bias=0.7)), B(-4.4, 4.4, 29.5, 30.5, 3, 4.6, skin),
        ], parent='body', look=True),
        Node('head_glow', (0, 24, 0), [
            B(-3, -1, 27, 28.8, 4.0, 4.15, glow(G_EMBER)), B(1, 3, 27, 28.8, 4.0, 4.15, glow(G_EMBER)),
            B(-2.5, 2.5, 24.2, 24.8, -2, 3.4, glow(G_EMBER)),
        ], parent='head', glow=True),
        Node('armL', (6, 23, 0), arm(1), parent='body', rest=(-82, 0, -4), walk_amp=9, walk_phase=180, atk=45),
        Node('armR', (-6, 23, 0), arm_r(), parent='body', rest=(-82, 0, 4), walk_amp=9, walk_phase=0, atk=45),
    ]
    return Mob('cenizo', 'Cenizo', nodes)


# ============================================================================= 2. ARQUERO CALCINADO
def calcinado():
    bone = S(BONE, bias=1.8, spread=1.0, speck=(0.06, '#b8ad96'))
    bone_dark = S(BONE, bias=1.0, spread=0.9)
    cloth = S(CRIM, bias=1.0, spread=1.0, stripes=True, speck=(0.05, '#ff6a1a'))
    leather = S(LEATHER, bias=1.4, spread=1.0, rows=3)
    wood = S(WOOD, bias=1.6, spread=1.0, stripes=True)
    leg = lambda sx: [B(1 * sx, 3 * sx, 1.5, 12, -1, 1, bone), B(0.8 * sx, 3.2 * sx, 0, 1.5, -1.2, 2.6, bone_dark),
                      B(0.7 * sx, 3.3 * sx, 5, 6.6, -1.3, 1.3, bone_dark)]
    ribs = [B(-3, 3, y, y + 1, -1.5, 1.5, bone) for y in (13, 15, 17, 19, 21)]
    bow = [B(3.4, 4.6, 9.5, 11.5, -2, 2, wood), B(3.4, 4.6, 10.2, 12.2, 2, 5.5, wood), B(3.4, 4.6, 11.4, 13.4, 5.5, 9, wood),
           B(3.4, 4.6, 10.2, 12.2, -5.5, -2, wood), B(3.4, 4.6, 11.4, 13.4, -9, -5.5, wood),
           B(3.85, 4.15, 12.9, 13.2, -8.8, 8.8, S(['#8d8474', '#b8ad96'], bias=0.5)), B(3.2, 4.8, 9.2, 11.8, -1.2, 1.2, leather)]
    nodes = [
        Node('legL', (2, 12, 0), leg(1), walk_amp=32, walk_phase=0),
        Node('legR', (-2, 12, 0), leg(-1), walk_amp=32, walk_phase=180),
        Node('body', (0, 12, 0), ribs + [
            B(-0.8, 0.8, 12, 24, -2.4, -1.5, bone), B(-3, 3, 12, 13.5, -1.7, 1.7, bone_dark),
            B(-4, 4, 22.2, 24, -1.7, 1.7, bone), B(-3, 3, 6.5, 12.5, 1.8, 2.3, cloth),
            B(-5, 5, 22.5, 25, -2.8, 2.8, leather)], rest=(6, 0, 0), idle_amp=1.5, idle_axis='x', idle_speed=1.2),
        Node('body_glow', (0, 12, 0), [B(-2, 2, 13.5, 22.5, -1.2, 1.2, glow(G_EMBER))], parent='body', glow=True),
        Node('cape', (0, 23, -2.6), [B(-3.5, 3.5, 9, 23, -3.1, -2.6, cloth)], parent='body', idle_amp=7, idle_axis='x', idle_speed=1.5),
        Node('head', (0, 24, 0), [
            B(-4, 4, 24, 32, -4, 4, bone), B(-0.8, 0.8, 32, 34, -3, 2, bone_dark), B(-0.8, 0.8, 34, 35.5, -2.4, 0.6, bone_dark),
            B(4, 5.2, 28.5, 31, -1, 1, bone_dark), B(5.2, 6.3, 30.5, 33.5, -1, 1, bone_dark),
            B(-5.2, -4, 28.5, 31, -1, 1, bone_dark), B(-6.3, -5.2, 30.5, 33.5, -1, 1, bone_dark),
        ], parent='body', look=True),
        Node('head_glow', (0, 24, 0), [
            B(-3.2, -1.2, 27, 29, 4, 4.15, glow(G_EMBER)), B(1.2, 3.2, 27, 29, 4, 4.15, glow(G_EMBER)),
            B(5.4, 6.2, 32.4, 33.5, -0.5, 0.5, glow(G_EMBER)), B(-6.2, -5.4, 32.4, 33.5, -0.5, 0.5, glow(G_EMBER)),
        ], parent='head', glow=True),
        Node('armL', (4, 23, 0), [B(3, 5, 11, 23, -1, 1, bone), B(2.8, 5.2, 9.5, 11, -1.2, 1.2, bone_dark)],
             parent='body', rest=(-85, 0, 0), walk_amp=4, walk_phase=180, atk=10),
        Node('bow', (4, 11, 0), bow, parent='armL'),
        Node('armR', (-4, 23, 0), mirror([B(3, 5, 11, 23, -1, 1, bone), B(2.8, 5.2, 9.5, 11, -1.2, 1.2, bone_dark)]),
             parent='body', rest=(-62, 0, 14), walk_amp=4, walk_phase=0, atk=10),
    ]
    return Mob('calcinado', 'Arquero Calcinado', nodes)


# ============================================================================= 3. MARCHITO
def marchito():
    bark = S(BARK, bias=1.6, spread=1.1, cracks=(1, 4, '#a01808'), rows=4)
    bark_l = S(BARK, bias=2.2, spread=1.0)
    ash_rag = S(RAG_GREY, bias=1.4, spread=1.0, stripes=True)
    twig = S(BARK, bias=2.6, spread=0.8)
    leg = lambda sx: [B(0.5 * sx, 3.5 * sx, 1, 12, -1.5, 1.5, bark), B(0 if sx > 0 else -4, 4 if sx > 0 else 0, 5, 7, -2, 2, bark_l),
                      B(0 if sx > 0 else -4.5, 4.5 if sx > 0 else 0, 0, 1.5, -2, 3.5, bark_l)]
    spikes = [B(-0.6, 0.6, 14 + 3.2 * i, 15.6 + 3.2 * i, -3.8 - 0.7 * i, -2, bark_l) for i in range(5)]
    arm = lambda sx: [B(3.5 * sx, 6 * sx, 8, 24, -1.25, 1.25, bark), B(3.2 * sx, 6.3 * sx, 14, 16.5, -1.6, 1.6, bark_l),
                      B(3.5 * sx, 4.3 * sx, 2, 8, -0.6, 0.6, twig), B(4.6 * sx, 5.4 * sx, 1, 8, -0.6, 0.6, twig),
                      B(5.6 * sx, 6.4 * sx, 2.5, 8, -0.6, 0.6, twig)]
    nodes = [
        Node('legL', (2, 12, 0), leg(1), walk_amp=30, walk_phase=0),
        Node('legR', (-2, 12, 0), mirror(leg(1)), walk_amp=30, walk_phase=180),
        Node('body', (0, 12, 0), [B(-3.5, 3.5, 12, 25, -2, 2, bark), B(-4, -1, 14, 18, -2.6, -1.9, bark_l),
                                  B(1, 4, 19, 23, -2.6, -1.9, bark_l)] + spikes,
             rest=(20, 0, 0), idle_amp=2.5, idle_axis='x', idle_speed=1.1),
        Node('body_glow', (0, 12, 0), [B(-0.4, 0.4, 15, 23, 2.0, 2.15, glow(G_RED)), B(-2.5, -0.4, 20, 20.8, 2.0, 2.15, glow(G_RED)),
                                       B(3.5, 3.65, 14, 22, -0.5, 0.5, glow(G_RED)), B(-3.65, -3.5, 16, 21, -0.5, 0.5, glow(G_RED))],
             parent='body', glow=True),
        Node('stripBack', (0, 24, -2), [B(-3, 3, 10, 24, -2.6, -2, ash_rag)], parent='body', idle_amp=9, idle_axis='x', idle_speed=1.7),
        Node('stripL', (3, 24, 1.5), [B(2.5, 4, 13, 24, 1, 1.6, ash_rag)], parent='body', idle_amp=10, idle_axis='z', idle_speed=1.5, idle_phase=40),
        Node('stripR', (-3, 24, 1.5), [B(-4, -2.5, 13, 24, 1, 1.6, ash_rag)], parent='body', idle_amp=10, idle_axis='z', idle_speed=1.5, idle_phase=200),
        Node('head', (0, 25, 0), [B(-3, 3, 25, 32, -3, 3, bark), B(-2, 2, 23, 25, -2, 2.5, bark_l),
                                  B(2, 3, 32, 35, -0.5, 0.5, twig), B(3, 4, 34, 37, -0.5, 0.5, twig), B(4, 5, 36, 39, -0.5, 0.5, twig),
                                  B(3.8, 5.8, 35.2, 36, -0.5, 0.5, twig),
                                  B(-3, -2, 32, 35, -0.5, 0.5, twig), B(-4, -3, 34, 37, -0.5, 0.5, twig), B(-5, -4, 36, 39, -0.5, 0.5, twig),
                                  B(-5.8, -3.8, 35.2, 36, -0.5, 0.5, twig)], parent='body', look=True),
        Node('head_glow', (0, 25, 0), [B(-2.4, -0.6, 28, 29.2, 3, 3.15, glow(G_RED)), B(0.6, 2.4, 28, 29.2, 3, 3.15, glow(G_RED))],
             parent='head', glow=True),
        Node('armL', (4.5, 24, 0), arm(1), parent='body', rest=(-68, 0, -6), walk_amp=8, walk_phase=180, atk=50),
        Node('armR', (-4.5, 24, 0), mirror(arm(1)), parent='body', rest=(-68, 0, 6), walk_amp=8, walk_phase=0, atk=50),
    ]
    return Mob('marchito', 'Marchito', nodes)


# ============================================================================= 4. BRUTO FORJADO
def bruto():
    plate = S(OBS, bias=1.6, spread=0.9, rivets=True)
    plate_g = S(OBS, bias=2.0, spread=0.9, rivets=True)
    gold = S(GOLD, bias=1.8, spread=0.8)
    leather = S(LEATHER, bias=1.4, spread=1.0, rows=3, trim='#b08a26')
    steel = S(['#2a2c33', '#40434d', '#5d616e', '#8a8f9f', '#b9bdc9'], bias=2.0, spread=0.9)
    wood = S(WOOD, bias=1.5, spread=1.0, stripes=True)
    spike = S(BONE, bias=2.6, spread=0.7)
    leg = lambda sx: [B(0.5 * sx, 6 * sx, 2.5, 12, -2.75, 2.75, plate), B(0 if sx > 0 else -6.5, 6.5 if sx > 0 else 0, 4, 8, -3.2, 3.2, plate_g),
                      B(0.2 * sx, 6.3 * sx, 0, 2.5, -3, 4.5, plate), B(1 if sx > 0 else -5.5, 5.5 if sx > 0 else -1, 5, 8, 3.2, 3.8, gold)]
    pauld = lambda sx: [B(5.8 * sx, 11.8 * sx, 21, 26, -3.8, 3.8, plate_g), B(5.6 * sx, 12 * sx, 23.6, 24.6, -4, 4, gold), B(8 * sx, 9.2 * sx, 26, 29.5, -0.6, 0.6, spike),
                        B(6.2 * sx, 7.2 * sx, 26, 28.5, 1.8, 2.8, spike), B(6.2 * sx, 7.2 * sx, 26, 28.5, -2.8, -1.8, spike),
                        B(10 * sx, 11 * sx, 26, 28.5, -0.6, 0.6, spike)]
    arm = lambda sx: [B(6.2 * sx, 10.7 * sx, 10, 22, -2.2, 2.2, plate), B(6 * sx, 11 * sx, 9, 13.5, -2.6, 2.6, plate_g)] + pauld(sx)
    horn = lambda sx: [B(4.2 * sx, 5.5 * sx, 29, 31.5, -1, 1, spike), B(5.5 * sx, 7 * sx, 31, 34, -1, 1, spike),
                       B(6.5 * sx, 7.8 * sx, 33.5, 37, -1, 1, spike)]
    nodes = [
        Node('legL', (3.5, 12, 0), leg(1), walk_amp=26, walk_phase=0),
        Node('legR', (-3.5, 12, 0), mirror(leg(1)), walk_amp=26, walk_phase=180),
        Node('body', (0, 12, 0), [
            B(-6, 6, 12, 25, -3.5, 3.5, plate), B(-5, 5, 15, 24, 3.5, 4.4, plate_g), B(-6.4, 6.4, 12, 15, -3.9, 3.9, leather),
            B(-4, 4, 13, 24, -5.5, -3.5, plate), B(-2.5, -0.5, 24, 28, -5, -3.8, plate), B(0.5, 2.5, 24, 27, -5, -3.8, plate),
            B(-1.2, 1.2, 11.5, 15, 3.9, 4.6, gold)], rest=(8, 0, 0), idle_amp=1.6, idle_axis='x', idle_speed=1.0),
        Node('body_glow', (0, 12, 0), [B(-2, 2, 16, 20, 4.4, 4.55, glow(G_LAVA)), B(-3, 3, 17, 22, -5.7, -5.55, glow(G_LAVA)),
                                       B(-2.2, -0.8, 28, 29, -4.7, -4.1, glow(G_LAVA)), B(0.8, 2.2, 27, 28, -4.7, -4.1, glow(G_LAVA)),
                                       B(-5.2, -5.05, 16, 23, 3.5, 3.6, glow(G_LAVA))],
             parent='body', glow=True),
        Node('head', (0, 25, 0), [B(-4.2, 4.2, 24.5, 33, -4.2, 4.2, plate_g), B(-3, 3, 25, 30, 4.2, 5, plate),
                                  B(-0.6, 0.6, 33, 35.5, -4, 3.5, plate), B(-4.4, 4.4, 30.6, 31.6, -4.4, 4.4, gold)] + horn(1) + horn(-1), parent='body', look=True),
        Node('head_glow', (0, 25, 0), [B(-3.2, 3.2, 27.3, 28.5, 5.0, 5.15, glow(G_LAVA))], parent='head', glow=True),
        Node('armL', (8.4, 23.5, 0), arm(1), parent='body', rest=(-12, 0, -6), walk_amp=14, walk_phase=180, atk=60),
        Node('armR', (-8.4, 23.5, 0), mirror(arm(1)), parent='body', rest=(-38, 0, 8), walk_amp=8, walk_phase=0, atk=100),
        Node('cleaver', (-8.4, 18, 0), [B(-8.9, -7.9, 7, 15, -0.5, 0.5, wood), B(-10.5, -6.3, 15, 16.2, -1.5, 1.5, gold),
                                         B(-9.2, -7.6, 16.2, 33, -3, 4, steel), B(-9.0, -7.8, 22, 25, -4.5, -3, steel),
                                         B(-9.0, -7.8, 28, 31, -4.5, -3, steel)], parent='armR'),
        Node('cleaver_glow', (-8.4, 18, 0), [B(-9.0, -7.8, 17, 32.5, 4, 4.4, glow(G_LAVA))], parent='cleaver', glow=True),
    ]
    return Mob('bruto', 'Bruto Forjado', nodes)


# ============================================================================= 5. ESPECTRO
def espectro():
    cloak = S(CLOAK, bias=1.5, spread=1.0, stripes=True)
    cloak_l = S(CLOAK, bias=2.4, spread=1.0, stripes=True, speck=(0.04, '#d58cff'))
    bone = S(BONE_CLEAN, bias=2.0, spread=0.9)
    claw = S(BONE_CLEAN, bias=3.0, spread=0.6)
    nodes = [
        Node('core', (0, 20, 0), [], hover_amp=2.2, hover_speed=1.2),
        Node('robe', (0, 26, 0), [B(-4.5, 4.5, 18, 28, -3, 3, cloak), B(-4.8, 4.8, 26, 28.5, -3.4, 3.4, cloak_l)], parent='core',
             idle_amp=1.5, idle_axis='z', idle_speed=1.0),
        Node('tail1', (0, 18, 0), [B(-4, 4, 10, 18, -2.8, 2.8, cloak)], parent='robe', idle_amp=6, idle_axis='x', idle_speed=1.1),
        Node('tail2', (0, 10, 0), [B(-3, 3, 4, 10, -2.2, 2.2, cloak_l)], parent='tail1', idle_amp=10, idle_axis='x', idle_speed=1.3, idle_phase=60),
        Node('rags', (0, 8, 0), [B(-3.6, -2, 2, 8, -1.8, -1.2, cloak_l), B(-0.8, 0.8, 1, 8, 1.4, 2, cloak_l), B(2, 3.6, 2.5, 8, -1.8, -1.2, cloak_l)],
             parent='tail2', idle_amp=14, idle_axis='z', idle_speed=1.6, idle_phase=120),
        Node('hood', (0, 28, 0), [B(3, 5.2, 27, 35, -4, 4.2, cloak), B(-5.2, -3, 27, 35, -4, 4.2, cloak),
                                  B(-5.2, 5.2, 33.5, 36, -4, 4.8, cloak_l), B(-5.2, 5.2, 27, 36, -5, -3, cloak),
                                  B(-3, 3, 31, 38, -7.2, -4.6, cloak)], parent='robe', look=True),
        Node('skull', (0, 28, 0), [B(-3, 3, 27.5, 33, -3, 3, bone)], parent='hood'),
        Node('skull_glow', (0, 28, 0), [B(-2.4, -0.8, 29.5, 31, 3.0, 3.15, glow(G_VIOLET)), B(0.8, 2.4, 29.5, 31, 3.0, 3.15, glow(G_VIOLET)),
                                        B(-1.5, 1.5, 27.8, 28.3, 3.0, 3.1, glow(G_VIOLET))], parent='skull', glow=True),
        Node('armL', (4, 24, 0), [B(3.5, 5.5, 14, 24, -1.5, 1.5, cloak), B(3.6, 5.4, 10, 14, -1, 1, bone),
                                  B(3.6, 4.2, 6, 10, -0.4, 0.4, claw), B(4.4, 5, 5.5, 10, -0.4, 0.4, claw), B(5.2, 5.8, 6, 10, -0.4, 0.4, claw)],
             parent='robe', rest=(-55, 0, -8), idle_amp=6, idle_axis='x', idle_speed=1.0, atk=40),
        Node('armR', (-4, 24, 0), mirror([B(3.5, 5.5, 14, 24, -1.5, 1.5, cloak), B(3.6, 5.4, 10, 14, -1, 1, bone),
                                          B(3.6, 4.2, 6, 10, -0.4, 0.4, claw), B(4.4, 5, 5.5, 10, -0.4, 0.4, claw), B(5.2, 5.8, 6, 10, -0.4, 0.4, claw)]),
             parent='robe', rest=(-55, 0, 8), idle_amp=6, idle_axis='x', idle_speed=1.0, idle_phase=180, atk=40),
    ]
    for i, ph in enumerate((0, 120, 240)):
        nodes.append(Node(f'wisp{i}', (0, 14 + i * 5, 0), [B(-1.2, 1.2, 12.8 + i * 5, 15.2 + i * 5, -1.2, 1.2, glow(G_VIOLET))], parent='core',
                          glow=True, orbit_r=10 + i, orbit_speed=70 + 20 * i, orbit_phase=ph, hover_amp=1.5, hover_speed=2.0 + i * 0.4))
    return Mob('espectro', 'Espectro', nodes)


# ============================================================================= 6. SACERDOTE DE ALMAS
def sacerdote():
    robe = S(CRIM, bias=1.5, spread=0.9, stripes=True)
    robe_t = S(CRIM, bias=1.4, spread=0.9, stripes=True, trim='#d9b640')
    gold = S(GOLD, bias=2.0, spread=0.8)
    bone = S(BONE_CLEAN, bias=2.2, spread=0.8)
    dark = S(['#0e0608', '#1a0a0d', '#2a1014'], bias=1.0, spread=0.8)
    wood = S(WOOD, bias=1.7, spread=1.0, stripes=True)
    sleeve = lambda sx: [B(4 * sx, 7.5 * sx, 11, 23, -2, 2, robe), B(3.8 * sx, 7.7 * sx, 10, 12.5, -2.3, 2.3, gold),
                         B(4.5 * sx, 6.8 * sx, 8, 10.5, -1, 1, bone)]
    nodes = [
        Node('skirt', (0, 14, 0), [B(-5.2, 5.2, 1.6, 14, -3.8, 3.8, robe), B(-5.4, 5.4, 0, 1.6, -4, 4, gold), B(-1, 1, 1.6, 10, 3.8, 3.95, dark)],
             idle_amp=2, idle_axis='z', idle_speed=1.1, walk_amp=3, walk_phase=0),
        Node('torso', (0, 14, 0), [B(-4, 4, 14, 24, -2.5, 2.5, robe), B(-4.2, 4.2, 14, 16, -2.8, 2.8, gold),
                                   B(-6, 6, 22, 25, -3.5, 3.5, robe_t)], idle_amp=1.2, idle_axis='x', idle_speed=1.2),
        Node('torso_glow', (0, 14, 0), [B(-1, 1, 17, 19.5, 2.5, 2.65, glow(G_SOUL))], parent='torso', glow=True),
        Node('head', (0, 24, 0), [B(-3, 3, 25, 31.5, -3, 3, bone), B(3, 5, 24, 32, -4, 3.5, robe), B(-5, -3, 24, 32, -4, 3.5, robe),
                                  B(-5, 5, 31.5, 34, -4, 4.2, robe_t), B(-5, 5, 17, 32, -5, -3.5, robe)], parent='torso', look=True),
        Node('head_glow', (0, 24, 0), [B(-2.2, -0.8, 28, 29.3, 3, 3.15, glow(G_SOUL)), B(0.8, 2.2, 28, 29.3, 3, 3.15, glow(G_SOUL)),
                                       B(-0.5, 0.5, 30, 31, 3, 3.15, glow(G_SOUL))], parent='head', glow=True),
        Node('halo', (0, 29, -6.5), [B(-8, 8, 36, 37, -0.3, 0.3, glow(G_EMBER)), B(-8, 8, 21, 22, -0.3, 0.3, glow(G_EMBER)),
                                     B(7, 8, 22, 36, -0.3, 0.3, glow(G_EMBER)), B(-8, -7, 22, 36, -0.3, 0.3, glow(G_EMBER)),
                                     B(-1, 1, 36, 39, -0.3, 0.3, glow(G_EMBER)), B(-1, 1, 19, 21, -0.3, 0.3, glow(G_EMBER)),
                                     B(-10, -8, 28, 30, -0.3, 0.3, glow(G_EMBER)), B(8, 10, 28, 30, -0.3, 0.3, glow(G_EMBER))],
             parent='torso', glow=True, spin_axis='z', spin_speed=25),
        Node('armL', (5, 23, 0), sleeve(1), parent='torso', rest=(-30, 0, -5), idle_amp=4, idle_axis='x', idle_speed=1.0, atk=40),
        Node('armR', (-5, 23, 0), mirror(sleeve(1)), parent='torso', rest=(-45, 0, 0), walk_amp=3, walk_phase=0, atk=25),
        Node('staff', (-6, 10, 0), [B(-6.6, -5.4, -2, 31, -0.6, 0.6, wood), B(-7.6, -4.4, 31, 32.2, -1.5, 1.5, gold),
                                    B(-7.6, -6.4, 32.2, 34, -1.5, 1.5, gold), B(-5.6, -4.4, 32.2, 34, -1.5, 1.5, gold)],
             parent='armR', rest=(45, 0, 0)),
        Node('orb', (-6, 34, 0), [B(-7.2, -4.8, 32.8, 35.2, -1.2, 1.2, glow(G_SOUL))], parent='staff', glow=True,
             hover_amp=1.2, hover_speed=2.0, spin_axis='y', spin_speed=90),
    ]
    return Mob('sacerdote', 'Sacerdote de Almas', nodes)


# ============================================================================= 7. BESTIA DE CENIZA
def bestia():
    fur = S(FUR, bias=1.7, spread=1.2, rows=3, speck=(0.06, '#8a8494'), cracks=(1, 4, '#ff4d00'))
    fur_d = S(FUR, bias=0.8, spread=1.0, rows=3)
    obs = S(OBS, bias=1.6, spread=0.9, rivets=False)
    horn = S(BONE, bias=2.4, spread=0.8)
    tooth = S(BONE_CLEAN, bias=3.0, spread=0.5)
    leg = lambda sx, z: [B(4 * sx if sx > 0 else -9, 9 if sx > 0 else -4, 2, 14, z, z + 5.5, fur),
                         B(3.5 if sx > 0 else -9.5, 9.5 if sx > 0 else -3.5, 0, 3, z - 0.5, z + 6.5, obs),
                         B(4.2 if sx > 0 else -8.8, 5 if sx > 0 else -4.2, 0, 1.5, z + 6.5, z + 7.8, horn),
                         B(6 if sx > 0 else -7, 6.8 if sx > 0 else -6, 0, 1.5, z + 6.5, z + 7.8, horn),
                         B(7.8 if sx > 0 else -8.8, 8.8 if sx > 0 else -7.8, 0, 1.5, z + 6.5, z + 7.8, horn)]
    hornb = lambda sx: [B(4.5 * sx, 6 * sx, 28, 31, 13, 16, horn), B(5.5 * sx, 7.5 * sx, 30.5, 34, 12.5, 15.5, horn),
                        B(7 * sx, 8.5 * sx, 33, 37, 11.5, 14, horn)]
    nodes = [
        Node('legFL', (6.5, 14, 7.75), leg(1, 5), walk_amp=30, walk_phase=0),
        Node('legFR', (-6.5, 14, 7.75), leg(-1, 5), walk_amp=30, walk_phase=180),
        Node('legBL', (6.5, 14, -7.75), leg(1, -10.5), walk_amp=30, walk_phase=180),
        Node('legBR', (-6.5, 14, -7.75), leg(-1, -10.5), walk_amp=30, walk_phase=0),
        Node('body', (0, 20, 0), [B(-9, 9, 14, 27, -12, 12, fur), B(-8, 8, 27, 30, 3, 12, fur_d)] +
             [B(-1.2, 1.2, 27, 27 + h, z - 1, z + 1, obs) for z, h in ((-10, 2), (-5, 3), (0, 4), (5, 3.5), (10, 2.5))],
             idle_amp=1.0, idle_axis='x', idle_speed=1.0),
        Node('body_glow', (0, 20, 0), [B(-0.5, 0.5, 15, 25, 12, 12.15, glow(G_LAVA)), B(-4, -0.5, 21, 22, 12, 12.15, glow(G_LAVA)), B(0.5, 4, 18, 19, 12, 12.15, glow(G_LAVA)),
                                       B(-2.5, -0.5, 17, 18, 12, 12.15, glow(G_LAVA)), B(0.5, 2.5, 23, 24, 12, 12.15, glow(G_LAVA)),
                                       B(9, 9.15, 16, 25, -5, -4, glow(G_LAVA)), B(9, 9.15, 21, 22, -7.5, -5, glow(G_LAVA)), B(9, 9.15, 18, 19, -4, -1.5, glow(G_LAVA)),
                                       B(-9.15, -9, 15, 24, 0, 1, glow(G_LAVA)), B(-9.15, -9, 20, 21, 1, 3.5, glow(G_LAVA)), B(-9.15, -9, 17, 18, -2.5, 0, glow(G_LAVA)),
                                       B(-3, 3, 27.4, 27.6, -11, 11, glow(G_LAVA))] +
             [B(-0.7, 0.7, 29 + 0, 29.8 + h, z - 0.7, z + 0.7, glow(G_LAVA)) for z, h in ((-10, 1.2), (-5, 2.2), (0, 3), (5, 2.5), (10, 1.5))],
             parent='body', glow=True),
        Node('mane', (0, 24, 10), [B(-7, 7, 17, 33, 9, 15, fur_d), B(-6, 6, 33, 35, 9.5, 14, fur_d)], parent='body', idle_amp=2, idle_axis='x', idle_speed=1.2),
        Node('mane_glow', (0, 24, 10), [B(-2, -1, 22, 31, 15.05, 15.2, glow(G_LAVA)), B(2.5, 3.5, 20, 29, 15.05, 15.2, glow(G_LAVA)),
                                        B(-5.5, -4.5, 24, 32, 15.05, 15.2, glow(G_LAVA))], parent='mane', glow=True),
        Node('head', (0, 25, 14), [B(-5, 5, 20, 30, 13, 20, fur), B(-3.5, 3.5, 20, 26.5, 20, 28, obs), B(-1.5, 1.5, 24, 26, 27.5, 28.5, S(OBS, bias=0.3)),
                                   B(-3.2, -2.4, 18.5, 21, 26.5, 27.3, tooth), B(2.4, 3.2, 18.5, 21, 26.5, 27.3, tooth),
                                   B(-4.3, -2.7, 30, 31.5, 15, 18, fur_d), B(2.7, 4.3, 30, 31.5, 15, 18, fur_d)] + hornb(1) + hornb(-1),
             parent='body', look=True),
        Node('head_glow', (0, 25, 14), [B(-4.2, -2.4, 25.5, 27, 20, 20.15, glow(G_LAVA)), B(2.4, 4.2, 25.5, 27, 20, 20.15, glow(G_LAVA)),
                                        B(-3.4, -2.2, 29.3, 30, 20, 20.15, glow(G_LAVA)), B(2.2, 3.4, 29.3, 30, 20, 20.15, glow(G_LAVA))],
             parent='head', glow=True),
        Node('jaw', (0, 22, 15), [B(-3, 3, 17.5, 21, 15, 26, S(OBS, bias=0.8)), B(-2.8, -2, 21, 22.8, 24, 25, tooth), B(2, 2.8, 21, 22.8, 24, 25, tooth),
                                  B(-0.4, 0.4, 21, 22.5, 25.2, 26, tooth)], parent='head', rest=(12, 0, 0), idle_amp=5, idle_axis='x', idle_speed=1.4),
        Node('jaw_glow', (0, 22, 15), [B(-2.5, 2.5, 21, 21.3, 16, 25, glow(G_LAVA))], parent='jaw', glow=True),
        Node('tail1', (0, 24, -11), [B(-2.5, 2.5, 21.5, 26.5, -18, -11, fur)], parent='body', idle_amp=10, idle_axis='y', idle_speed=1.3),
        Node('tail2', (0, 24, -18), [B(-2, 2, 22, 26, -25, -18, fur_d)], parent='tail1', idle_amp=14, idle_axis='y', idle_speed=1.3, idle_phase=50),
        Node('tail3', (0, 24, -25), [B(-1.5, 1.5, 22.5, 25.5, -30, -25, obs)], parent='tail2', idle_amp=16, idle_axis='y', idle_speed=1.3, idle_phase=100),
        Node('tail_glow', (0, 24, -25), [B(-1, 1, 23, 25, -33, -30, glow(G_LAVA))], parent='tail3', glow=True),
    ]
    return Mob('bestia', 'Bestia de Ceniza', nodes)


# ============================================================================= 8. VORTHAK (jefe final)
def vorthak():
    """Centinela de Ceniza: caballero colosal de obsidiana con capa carmesi, cuernos, halo ardiente y mandoble de lava.
    Se dibuja con SCALE 1.7 (el del jefe), asi que aqui mide ~40 px de alto sin cuernos."""
    plate = S(OBS, bias=1.7, spread=0.9, rivets=True)
    plate_g = S(OBS, bias=2.3, spread=0.8, rivets=True, trim='#b08a26')
    dark = S(OBS, bias=0.9, spread=0.7)
    gold = S(GOLD, bias=2.0, spread=0.8)
    skull = S(['#0d0b0e', '#1b181d', '#2c282f', '#413c46', '#5a5460'], bias=2.1, spread=0.8)
    bone = S(BONE, bias=2.6, spread=0.7)
    steel = S(['#1d1f26', '#30333d', '#4a4e5c', '#757a8c', '#a9aebf'], bias=2.2, spread=0.9)
    cape = S(CRIM, bias=1.5, spread=1.0, stripes=True)
    cape_e = S(CRIM, bias=2.6, spread=0.7, trim='#d9b640')
    chest_cr = S(OBS, bias=1.8, spread=0.9, rivets=True, cracks=(3, 5, '#ff5a1a'))
    grip = S(LEATHER, bias=1.5, spread=1.0, rows=2)

    leg = lambda sx: [B(1.5 * sx, 7.2 * sx, 3, 17, -3.2, 3.4, plate), B(1.2 * sx, 7.5 * sx, 8, 12, -3.6, 3.8, dark),
                      B(1.4 * sx, 7.3 * sx, 0, 3.2, -3.4, 6.5, plate), B(2.5 * sx, 6.2 * sx, 10, 13.5, 3.8, 5.2, gold),
                      B(3.8 * sx, 4.8 * sx, 12.5, 15, 4.6, 6.0, bone)]
    pauld = lambda sx: [B(7.5 * sx, 16.5 * sx, 30.5, 36, -4.8, 4.8, plate_g), B(7.3 * sx, 16.7 * sx, 33.2, 34.4, -5, 5, gold),
                        B(10 * sx, 11.6 * sx, 36, 41.5, -0.8, 0.8, bone), B(13 * sx, 14.4 * sx, 36, 39.5, -0.8, 0.8, bone),
                        B(8.4 * sx, 9.8 * sx, 36, 39, 2.2, 3.4, bone), B(8.4 * sx, 9.8 * sx, 36, 39, -3.4, -2.2, bone)]
    arm = lambda sx: [B(8 * sx, 13.8 * sx, 21, 32, -2.8, 2.8, plate), B(7.6 * sx, 14.2 * sx, 20, 23.5, -3.3, 3.3, plate_g)] + pauld(sx)
    fore = lambda sx: [B(8.2 * sx, 13.6 * sx, 12, 20, -3.2, 3.2, plate_g), B(7.8 * sx, 14 * sx, 11, 13.2, -3.6, 3.6, gold),
                       B(8.4 * sx, 13.4 * sx, 6.5, 11, -2.6, 2.6, dark), B(8.6 * sx, 9.6 * sx, 2.5, 6.5, -0.5, 0.9, bone),
                       B(10.6 * sx, 11.6 * sx, 2, 6.5, -0.5, 0.9, bone), B(12.6 * sx, 13.6 * sx, 2.5, 6.5, -0.5, 0.9, bone)]
    horn = lambda sx: [B(4.0 * sx, 6.2 * sx, 42, 46, -1.6, 1.6, bone), B(5.4 * sx, 7.6 * sx, 45, 49, -1.4, 1.4, bone),
                       B(6.8 * sx, 8.6 * sx, 48, 52, -1.1, 1.1, bone), B(7.9 * sx, 9.1 * sx, 51, 55, -0.7, 0.7, gold)]

    nodes = [
        Node('legL', (4.5, 17, 0), leg(1), walk_amp=24, walk_phase=0),
        Node('legR', (-4.5, 17, 0), mirror(leg(1)), walk_amp=24, walk_phase=180),
        Node('body', (0, 17, 0), [
            B(-6.5, 6.5, 17, 22.5, -4, 4, plate), B(-7, 7, 17, 19.5, -4.5, 4.5, gold), B(-1.6, 1.6, 17.2, 21.5, 4.4, 5.2, gold),
            B(-8, 8, 22.5, 34, -4.6, 4.6, chest_cr), B(-5.5, 5.5, 24, 33, 4.6, 5.4, plate_g), B(-8.4, 8.4, 33.2, 34.6, -5, 5, gold),
            B(-5.5, 5.5, 34, 37, -3.8, 3.8, plate_g), B(-8.2, 8.2, 22, 23, -4.9, 4.9, gold),
            B(-3.4, -2.2, 26, 36, -7.8, -4.6, bone), B(2.2, 3.4, 26, 36, -7.8, -4.6, bone), B(-0.6, 0.6, 28, 38, -8.6, -4.6, bone),
            B(-6.5, 6.5, 12, 17.5, -4.2, 4.4, dark), B(-5.4, -1.2, 9, 17, 4.2, 4.9, plate), B(1.2, 5.4, 9, 17, 4.2, 4.9, plate)],
             rest=(5, 0, 0), idle_amp=1.3, idle_axis='x', idle_speed=0.9),
        Node('body_glow', (0, 17, 0), [
            B(-2.4, 2.4, 25.5, 31.5, 5.4, 5.6, glow(G_LAVA)), B(-0.7, 0.7, 20.5, 25.5, 5.2, 5.4, glow(G_LAVA)),
            B(-7.2, -6.4, 24, 32, 4.7, 4.85, glow(G_EMBER)), B(6.4, 7.2, 24, 32, 4.7, 4.85, glow(G_EMBER)),
            B(-0.4, 0.4, 30, 36, -8.7, -8.55, glow(G_LAVA))], parent='body', glow=True),
        Node('cape_hi', (0, 34, -5), [B(-7, 7, 20, 34, -5.7, -5.05, cape), B(-7.4, 7.4, 33, 34.6, -5.9, -4.9, cape_e)],
             parent='body', rest=(4, 0, 0), idle_amp=2.5, idle_axis='x', idle_speed=1.1),
        Node('cape_lo', (0, 20, -5.4), [B(-7.6, 7.6, 7, 20, -6.0, -5.4, cape), B(-7.9, -5.6, 2, 7, -6.0, -5.4, cape_e),
                                        B(-4.6, -2.2, 3.5, 7, -6.0, -5.4, cape_e), B(-1.6, 1.0, 1, 7, -6.0, -5.4, cape_e),
                                        B(2.0, 4.6, 4, 7, -6.0, -5.4, cape_e), B(5.6, 7.9, 2.5, 7, -6.0, -5.4, cape_e)],
             parent='cape_hi', idle_amp=5.5, idle_axis='x', idle_speed=1.5, idle_phase=50),
        Node('head', (0, 36, 0), [
            B(-4, 4, 36.5, 44.5, -4, 4, skull), B(-3, 3, 34.5, 37, -3.2, 3.8, skull), B(-4.7, 4.7, 42, 44.8, -4.7, 4.7, steel),
            B(-4.9, 4.9, 43.6, 44.4, -4.9, 4.9, gold), B(-0.9, 0.9, 44.5, 51, -4.3, 2.6, gold), B(-0.7, 0.7, 40, 43, 4.6, 5.0, steel),
            B(-3.5, 3.5, 38, 41, -5.2, -4, dark), B(-2.5, 2.5, 41, 46, -5.2, -4, dark)] + horn(1) + horn(-1),
             parent='body', look=True),
        Node('head_glow', (0, 36, 0), [B(1, 3.2, 39.6, 41.8, 4.0, 4.18, glow(G_RED)), B(-3.2, -1, 39.6, 41.8, 4.0, 4.18, glow(G_RED)),
                                       B(-2, 2, 36.6, 37.6, 3.9, 4.0, glow(G_EMBER)), B(-0.5, 0.5, 51, 56, -0.5, 0.5, glow(G_LAVA))],
             parent='head', glow=True),
        Node('halo', (0, 58, 0), [
            B(-1, 1, 56, 59, 7, 9, glow(G_EMBER)), B(-1, 1, 56, 59, -9, -7, glow(G_EMBER)),
            B(7, 9, 56, 59, -1, 1, glow(G_EMBER)), B(-9, -7, 56, 59, -1, 1, glow(G_EMBER)),
            B(4.6, 6.6, 56, 59, 4.6, 6.6, glow(G_LAVA)), B(-6.6, -4.6, 56, 59, 4.6, 6.6, glow(G_LAVA)),
            B(4.6, 6.6, 56, 59, -6.6, -4.6, glow(G_LAVA)), B(-6.6, -4.6, 56, 59, -6.6, -4.6, glow(G_LAVA))],
             glow=True, hover_amp=1.2, hover_speed=1.0, spin_axis='y', spin_speed=40),
        Node('armL', (11, 32, 0), arm(1), parent='body', rest=(-10, 0, -9), walk_amp=12, walk_phase=180, atk=40),
        Node('foreL', (11, 20.5, 0), fore(1), parent='armL', rest=(-22, 0, 0), atk=25),
        Node('armR', (-11, 32, 0), mirror(arm(1)), parent='body', rest=(-30, 0, 9), walk_amp=7, walk_phase=0, atk=85),
        Node('foreR', (-11, 20.5, 0), mirror(fore(1)), parent='armR', rest=(-30, 0, 0), atk=40),
        Node('sword', (-11, 15, 0.5), [
            B(-11.9, -10.1, 9, 19, -1, 2, grip), B(-12.2, -9.8, 7.5, 9.5, -1.6, 2.6, gold),
            B(-15.5, -6.5, 19, 21.5, -1.6, 1.6, gold), B(-12.6, -9.4, 21.5, 38.5, -0.9, 0.9, steel),
            B(-14.2, -13, 21.5, 26, -1.2, 1.2, steel), B(-9, -7.8, 21.5, 26, -1.2, 1.2, steel)], parent='foreR'),
        Node('sword_glow', (-11, 15, 0.5), [B(-11.3, -10.7, 23, 38, 0.9, 1.05, glow(G_LAVA)), B(-11.3, -10.7, 23, 38, -1.05, -0.9, glow(G_LAVA)),
                                            B(-11.6, -10.4, 19.2, 21.2, 1.6, 1.75, glow(G_EMBER))], parent='sword', glow=True),
        Node('sword2', (-11, 38.5, 0.5), [B(-12.8, -9.2, 38.5, 52, -0.9, 0.9, steel), B(-12.2, -9.8, 52, 58, -0.8, 0.8, steel),
                                          B(-11.7, -10.3, 58, 62.3, -0.6, 0.6, steel), B(-13.6, -12.8, 38.5, 44, -1.1, 1.1, steel),
                                          B(-9.2, -8.4, 38.5, 44, -1.1, 1.1, steel)], parent='sword'),
        Node('sword2_glow', (-11, 38.5, 0.5), [B(-11.3, -10.7, 38.5, 55, 0.9, 1.05, glow(G_LAVA)), B(-11.3, -10.7, 38.5, 55, -1.05, -0.9, glow(G_LAVA)),
                                               B(-11.2, -10.8, 55, 61.5, 0.7, 0.85, glow(G_EMBER))], parent='sword2', glow=True),
    ]
    for i in range(3):
        nodes.append(Node('soul%d' % i, (0, 30, 0), [B(-1.6, 1.6, 28.4, 31.6, -1.6, 1.6, glow(G_SOUL))], parent='body', glow=True,
                          orbit_r=15, orbit_speed=75, orbit_phase=120 * i, hover_amp=1.5, hover_speed=1.2 + 0.2 * i,
                          spin_axis='y', spin_speed=120))
    return Mob('vorthak', 'Vorthak', nodes)


ALL = [cenizo, calcinado, marchito, bruto, espectro, sacerdote, bestia, vorthak]
