#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 res/ 扫出一个 R.kt 桩，用于"没有 Android Studio / 没有 aapt"时的纯 Kotlin 类型检查。

    python tools/gen_r_stub.py            # 写到 build/typecheck/R.kt

为什么需要它：kotlinc 编译 app 源码时会遇到 R.id.xxx / R.layout.xxx，
而 R 类是 aapt 在构建时生成的。没跑过 aapt 就没有 R，只能先造一个"值不重要、名字要对"的桩。
"""
import os, re, glob, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app/src/main/res")
OUT = os.path.join(ROOT, "build/typecheck/R.kt")

KINDS = ["layout", "drawable", "string", "color", "raw", "anim", "menu", "xml", "mipmap", "dimen", "font"]

def main():
    ids, kinds = set(), {k: set() for k in KINDS}
    for f in glob.glob(RES + "/**/*.*", recursive=True):
        folder = os.path.basename(os.path.dirname(f)).split("-")[0]
        name = os.path.basename(f).rsplit(".", 1)[0]
        if folder == "values":
            txt = open(f, encoding="utf-8", errors="replace").read()
            for m in re.finditer(r'<(string|color|dimen)\s+name="([\w.]+)"', txt):
                kinds[m.group(1)].add(m.group(2))
        elif folder in kinds:
            kinds[folder].add(name)
        if f.endswith(".xml"):
            txt = open(f, encoding="utf-8", errors="replace").read()
            for m in re.finditer(r'@\+?id/(\w+)', txt):
                ids.add(m.group(1))

    lines = ["package com.dshpet.android", "", "// 自动生成，仅用于类型检查，不要提交到生产构建", "object R {"]
    for i, node in enumerate(["id"] + KINDS):
        pool = ids if node == "id" else kinds.get(node, set())
        if not pool:
            continue
        lines.append("    object %s {" % node)
        for j, n in enumerate(sorted(pool)):
            safe = n if re.match(r"^[A-Za-z_]\w*$", n) else "`" + n + "`"
            lines.append("        const val %s = %d" % (safe, j + 1))
        lines.append("    }")
    lines.append("}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w", encoding="utf-8").write("\n".join(lines))
    print("R 桩已生成: %s  (id %d 个)" % (os.path.relpath(OUT, ROOT), len(ids)))

if __name__ == "__main__":
    sys.exit(main())
