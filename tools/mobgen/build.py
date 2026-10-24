"""Genera el resource pack, mobmodels.yml y la vista previa.  Uso: python3 build.py [--preview-only] [mob ...]"""
import os, sys, shutil
sys.path.insert(0, os.path.dirname(__file__))
import engine, defs

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "tools", "mobgen", "out")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    mobs = [f() for f in defs.ALL]
    if args:
        mobs_sel = [m for m in mobs if m.id in args]
    else:
        mobs_sel = mobs
    if "--preview-only" not in sys.argv:
        shutil.rmtree(os.path.join(OUT, "resourcepack"), ignore_errors=True)
        engine.export(mobs, OUT)
        sha = engine.make_zip(OUT, os.path.join(ROOT, "resourcepack", "CustomMobsPack.zip"))
        shutil.copy(os.path.join(OUT, "mobmodels.yml"), os.path.join(ROOT, "CustomMobs", "src", "main", "resources", "mobmodels.yml"))
        print("sha1", sha)
        for m in mobs:
            print(m.id, "atlas", m.tex_size, "nodos", len(m.nodes), "cajas", sum(len(n.boxes) for n in m.nodes))
    engine.preview_sheet(mobs_sel, os.path.join(OUT, "preview.png"))
    print("preview ->", os.path.join(OUT, "preview.png"))


main()
