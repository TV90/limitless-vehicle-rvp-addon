#!/usr/bin/env python3
"""临时工具：解析 Bedrock 模型 JSON 的骨骼树（编号连续链压缩显示），用于模型层级梳理文档。"""
import json
import re
import sys


def compress(names):
    out, i = [], 0
    while i < len(names):
        m = re.match(r"^(.*?)(\d+)$", names[i])
        if m:
            prefix, start = m.group(1), int(m.group(2))
            j = i
            while j < len(names):
                m2 = re.match(r"^(.*?)(\d+)$", names[j])
                if m2 and m2.group(1) == prefix and int(m2.group(2)) == start + (j - i):
                    j += 1
                else:
                    break
            if j - i >= 3:
                out.append((names[i], "%s%d ~ %s%d (%d个)" % (prefix, start, prefix, start + (j - i - 1), j - i)))
                i = j
                continue
        out.append((names[i], names[i]))
        i += 1
    return out


def main():
    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    geo = data.get("minecraft:geometry", data.get("geometry", []))
    bones = geo[0]["bones"] if isinstance(geo, list) else data["bones"]
    desc = geo[0].get("description", {}) if isinstance(geo, list) else {}
    byname = {b["name"]: b for b in bones}
    children = {}
    roots = []
    for b in bones:
        p = b.get("parent")
        if p and p in byname:
            children.setdefault(p, []).append(b["name"])
        else:
            roots.append(b["name"])

    def walk(name, depth):
        b = byname[name]
        cubes = len(b.get("cubes", []) or [])
        line = "  " * depth + name
        if cubes:
            line += "  [cube:%d pivot:%s]" % (cubes, b.get("pivot"))
        print(line)
        for first, label in compress(children.get(name, [])):
            if label != first:
                print("  " * (depth + 1) + label)
            else:
                walk(first, depth + 1)

    print("identifier:", desc.get("identifier"), "| 骨骼总数:", len(bones), "| 根:", roots)
    for r in roots:
        walk(r, 0)


if __name__ == "__main__":
    main()
