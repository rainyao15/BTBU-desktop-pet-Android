#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
没有 Android SDK 也能做的"半个编译器"：
把 Kotlin 里所有 R.xxx.yyy 引用跟 res/ 里真实存在的资源对一遍，
再把 layout 里的 @drawable/@color/@string 引用也核对一遍。
能在上传 CI 之前抓掉 90% 的低级编译错误（R.id 写错、漏建 layout、引用了不存在的颜色）。
"""
import os, re, sys, glob

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app/src/main/res")
KT = glob.glob(os.path.join(ROOT, "app/src/main/java/**/*.kt"), recursive=True)

def defined_res():
    out = {"id": set(), "layout": set(), "drawable": set(), "string": set(),
           "color": set(), "anim": set(), "raw": set(), "xml": set(), "menu": set()}
    for f in glob.glob(os.path.join(RES, "**/*.*"), recursive=True):
        kind = os.path.basename(os.path.dirname(f)).split("-")[0]
        name = os.path.basename(f).rsplit(".", 1)[0]
        if kind == "values":
            # values/*.xml 里定义的是 string/color/dimen/style，不是"values 资源"
            txt = open(f, encoding="utf-8", errors="replace").read()
            for m in re.finditer(r'<(string|color|dimen|style|bool|integer)\s+name="([\w.]+)"', txt):
                out.setdefault(m.group(1), set()).add(m.group(2))
            continue
        out.setdefault(kind, set()).add(name)
        if f.endswith(".xml"):
            s = open(f, encoding="utf-8", errors="replace").read()
            for m in re.finditer(r'android:id="@\+id/(\w+)"', s):
                out["id"].add(m.group(1))
            for m in re.finditer(r'@\+?id/(\w+)', s):
                out["id"].add(m.group(1))
    return out

# 这些是上游 README 明说的"可选素材"（.gitignore 里，缺了也能构建）
OPTIONAL = {("raw", "tap_press"), ("raw", "tap_release"),
            ("drawable", "whale_cutout"), ("drawable", "whale_selfie")}

def main():
    d = defined_res()
    problems = []
    # 1) Kotlin 里的 R.x.y
    for f in KT:
        s = open(f, encoding="utf-8").read()
        code = "\n".join(
            l for l in s.splitlines()
            if not l.strip().startswith("//") and not l.strip().startswith("*")
        )
        for m in re.finditer(r'(?<!android\.)\bR\.(\w+)\.(\w+)', code):
            kind, name = m.group(1), m.group(2)
            if kind == "id" and name.startswith("acc") and name in ("accUser", "accPass", "accStatus"):
                pass
            if (kind, name) in OPTIONAL:
                continue
            pool = d.get(kind, set())
            if not pool:
                problems.append(f"{os.path.basename(f)}: R.{kind}.{name}  ← res/ 里没有这一类资源")
            elif name not in pool:
                problems.append(f"{os.path.basename(f)}: R.{kind}.{name}  ← 资源不存在")
    # 2) layout 里的 @drawable/@color/@string/@layout
    for f in glob.glob(os.path.join(RES, "layout/*.xml")):
        s = open(f, encoding="utf-8", errors="replace").read()
        for m in re.finditer(r'@(drawable|color|string|layout|anim|raw)/(\w+)', s):
            kind, name = m.group(1), m.group(2)
            if (kind, name) in OPTIONAL:
                continue
            if name not in d.get(kind, set()):
                problems.append(f"{os.path.basename(f)}: @{kind}/{name}  ← 不存在")
    # 3) include 的 layout 必须有根节点
    for f in glob.glob(os.path.join(RES, "layout/*.xml")):
        s = open(f, encoding="utf-8", errors="replace").read()
        body = re.sub(r"<!--.*?-->", "", s, flags=re.S)
        if not re.search(r"<\w[\w.]*\s", body):
            problems.append(f"{os.path.basename(f)}: 没有根节点")
    if problems:
        print("发现问题 %d 个：" % len(problems))
        for p in sorted(set(problems)):
            print("  -", p)
        return 1
    print("[OK] R 引用 / 资源引用 / include 全部对得上（%d 个 Kotlin 文件, %d 个资源）" % (len(KT), sum(len(v) for v in d.values())))
    return 0

if __name__ == "__main__":
    sys.exit(main())
