#!/usr/bin/env python3
"""统计 RVP 载具包内每台载具的模型面数：载具渲染模型 + 结构模型 + 挂载武器 / 挂架 display 模型。

用法（在仓库根目录执行）：
    python scripts/stat_vehicle_mesh.py                       # 打印 Markdown 表格到 stdout
    python scripts/stat_vehicle_mesh.py --out build/x.md      # 只写表格
    python scripts/stat_vehicle_mesh.py --doc docs/plan/agent层级梳理/整包载具与挂载武器模型统计_20261108.md
                                                              # 生成完整报告（含口径说明）

统计口径（与运行时一致）：
1. poly_mesh：按“同一 poly 内不同位置索引数 n”做扇形三角化，三角形 = max(n-2, 0)。
   SBM 的 TreeBedrockModelBaker.bakePolygon 对四边形发 (0,1,2)+(2,3,0)，
   但 bakeTriangle 会跳过任意两角点同位置的退化三角形，故 4 角点重复首点的 poly 只算 1 个三角形。
2. cube：面数 = uv 字典的键数（缺面不渲染），三角形 = 面数 × 2。
3. 顶点：唯一位置数（去重）；GPU 提交顶点数 = 三角形 × 3（SBM 的 Triangle 直接存顶点记录，无索引复用）。
4. 挂载武器 display：
   - 先按“武器站（part_unit_id / part id）”归组，同一站内多个武器是可选弹种，同一时刻只画一个 → 取组内最大值；
   - 有 rvp_custom_mounts 且 replace_weapon_display=true 时，武器自身 display 被挂架模型取代，
     按该站匹配该武器的挂点条目累计挂架模型面数；
   - 无挂点时，武器 display 按“每个 bolt 一份”渲染，bolt 数取 parts[].bolts 长度（缺省 1）。
   - 武器 display 可引用本体命名空间模型（如 ywzj_vehicle:entity/missile_pl_12），
     用 --base-assets 指向本体 default_vehicle/assets 才能统计；未提供时按 0 计并告警。
5. 不含：飞行中的弹体、子母弹子弹药、乘员、粒子/特效、远距 LOD 代理。
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import statistics
import sys
from collections import OrderedDict, defaultdict


# ---------------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------------

def load_json(path: str):
    """读取 UTF-8 JSON；文件不存在或解析失败返回 None（调用方自行记缺失）。"""
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def poly_position_indices(poly, npos: int, nuv: int, nnorm: int):
    """取一个 poly 的“位置索引”序列，并判断编码是否有歧义。

    本包导出器的 poly 是“角点三元组”：[[位置, 法线, UV], ...]，第 4 个角点常与第 1 个相同。
    Bedrock 官方写法是 [[位置...], [UV...], [法线...]]。两种都是“数组的数组”，
    故先用索引范围判定，两者都合法时按官方写法（并标记 ambiguous 供报告脚注）。
    """
    if not poly or not all(isinstance(e, list) for e in poly):
        return [], False

    def in_range(seq, limit):
        # 只接受非负整数索引，且必须落在对应数组范围内
        return all(isinstance(v, int) and 0 <= v < limit for v in seq)

    corner_ok = (
        all(len(e) == 3 for e in poly)
        and in_range([e[0] for e in poly], npos)
        and in_range([e[1] for e in poly], nnorm)
        and in_range([e[2] for e in poly], nuv)
    )
    standard_ok = (
        len(poly) == 3
        and in_range(poly[0], npos)
        and in_range(poly[1], nuv)
        and in_range(poly[2], nnorm)
    )

    if corner_ok and not standard_ok:
        return [e[0] for e in poly], False
    if standard_ok and not corner_ok:
        return list(poly[0]), False
    if corner_ok and standard_ok:
        # 两种读法都合法：官方写法优先，同时记录歧义
        return list(poly[0]), True
    # 都越界：按角点三元组兜底，避免整份数据丢失
    if all(len(e) == 3 for e in poly):
        return [e[0] for e in poly], True
    return [], True


def mesh_stats(path: str) -> dict:
    """统计一个 Bedrock geometry 文件的三角形 / 顶点。

    返回字段：
      tris      三角形数
      verts_uni 唯一位置数（poly positions 去重 + cube 8 角）
      verts_sub GPU 提交顶点数（三角形 × 3，cube 面按 4 角点）
      polys     poly_mesh 条目数
      cubes     cube 数
      bones     骨数
      ambiguous 是否存在 poly 编码歧义（标准索引式 / 角点三元组两种读法都合法）
      missing   文件缺失
    """
    data = load_json(path)
    if data is None:
        return {"tris": 0, "verts_uni": 0, "verts_sub": 0, "polys": 0,
                "cubes": 0, "bones": 0, "ambiguous": False, "missing": True}

    geos = data.get("minecraft:geometry") or []
    stats = {"tris": 0, "verts_uni": 0, "verts_sub": 0, "polys": 0,
             "cubes": 0, "bones": 0, "ambiguous": False, "missing": False}

    for geo in geos:
        bones = geo.get("bones") or []
        stats["bones"] += len(bones)
        for bone in bones:
            # --- poly_mesh：自由三角网格 ---
            pm = bone.get("poly_mesh")
            if pm:
                positions = pm.get("positions") or []
                uvs = pm.get("uvs") or []
                normals = pm.get("normals") or []
                stats["verts_uni"] += len(positions)
                for poly in pm.get("polys") or []:
                    stats["polys"] += 1
                    idx, ambiguous = poly_position_indices(poly, len(positions), len(uvs), len(normals))
                    stats["ambiguous"] = stats["ambiguous"] or ambiguous
                    distinct = len(set(idx))
                    tri = max(distinct - 2, 0)   # 扇形三角化，退化三角形不计
                    stats["tris"] += tri
                    stats["verts_sub"] += tri * 3
            # --- cube：Box UV / 逐面 UV ---
            for cube in bone.get("cubes") or []:
                stats["cubes"] += 1
                uv = cube.get("uv")
                if isinstance(uv, dict):
                    faces = len(uv)            # 逐面 UV：只统计实际存在的面
                elif isinstance(uv, list):
                    faces = 6                  # 整体 UV：六面全开
                else:
                    faces = 0
                stats["tris"] += faces * 2
                stats["verts_uni"] += 8        # 每个盒 8 个不同角点
                stats["verts_sub"] += faces * 4
    return stats


# ---------------------------------------------------------------------------
# 路径解析（rvp: / ywzj_vehicle: 命名空间 → 模型文件）
# ---------------------------------------------------------------------------

def split_ref(ref: str) -> tuple[str, str]:
    """拆资源引用为 (命名空间, 相对路径)；格式非法时返回 ('', '')。"""
    if not ref or not isinstance(ref, str):
        return "", ""
    ns, _, rel = ref.partition(":")
    return ns, rel


def model_path(pack: str, base_assets: str, ref: str) -> str | None:
    """把 display 的 model 引用解析成文件路径。

    rvp:entity/x              → <pack>/assets/rvp/models/bedrock/entity/x.json
    ywzj_vehicle:entity/x     → <base_assets>/ywzj_vehicle/models/bedrock/entity/x.json（本体回退模型）
    """
    ns, rel = split_ref(ref)
    if not ns or not rel:
        return None
    if ns == "rvp":
        return os.path.join(pack, "assets", "rvp", "models", "bedrock", rel + ".json")
    if base_assets:
        return os.path.join(base_assets, ns, "models", "bedrock", rel + ".json")
    return None


def structure_model_path(pack: str, ref: str) -> str | None:
    """结构模型：rvp:vehicle/xxx → data/rvp/models/bedrock/vehicle/xxx.structure.json。"""
    ns, rel = split_ref(ref)
    if ns != "rvp" or not rel:
        return None
    return os.path.join(pack, "data", "rvp", "models", "bedrock", rel + ".structure.json")


def weapon_display_path(pack: str, weapon_id: str) -> str:
    """武器显示：assets/rvp/display/weapon/<武器id>.json（id 去掉命名空间）。"""
    wid = weapon_id.split(":")[-1]
    return os.path.join(pack, "assets", "rvp", "display", "weapon", wid + ".json")


# ---------------------------------------------------------------------------
# 载具 JSON 解析
# ---------------------------------------------------------------------------

def extract_weapon_ids(entry) -> list[str]:
    """从 parts[].weapons[] 条目取出武器 id 列表。

    合法形态：
      "rvp:xxx"                                     直接写 id
      {"id": "rvp:xxx"}                             单武器
      {"ids": [...]}                                同槽多弹种（可选，同一时刻只画一个）
      ["rvp:a", "rvp:b"]                            数组（等价 ids）
    仅含 part_unit_id 的条目是“武器站链接 / 武器舱声明”，不含武器，返回空。
    """
    if isinstance(entry, str):
        return [entry]
    if isinstance(entry, list):
        return [e for e in entry if isinstance(e, str)]
    if isinstance(entry, dict):
        if isinstance(entry.get("ids"), list):
            return [e for e in entry["ids"] if isinstance(e, str)]
        if isinstance(entry.get("id"), str):
            return [entry["id"]]
    return []


def part_bolts(part: dict) -> int:
    """bolt 数：parts[].bolts 数组长度；未配置时本体默认 1 个 bolt（WeaponUnit 构造逻辑）。"""
    bolts = part.get("bolts")
    return len(bolts) if isinstance(bolts, list) and bolts else 1


# ---------------------------------------------------------------------------
# 单台载具统计
# ---------------------------------------------------------------------------

def analyze_vehicle(pack: str, base_assets: str, vid: str, memo: dict, warnings: list) -> dict:
    """统计一台载具：渲染模型、结构模型、挂载武器 / 挂架 display 模型。

    memo 为模型路径 → mesh_stats 的缓存，避免同一模型重复解析（整包复用率很高）。
    """
    result = {
        "id": vid,
        "render_tris": 0, "render_verts": 0,
        "struct_tris": 0, "struct_verts": 0,
        "weapon_tris": 0, "total_tris": 0,
        "weapon_models": [],   # (unit, 武器id, 模型引用, 三角形)
        "mount_models": [],    # (unit, 武器id, 模型引用, 三角形)
        "mount_entries": 0,
        "mount_replace_true": 0, "mount_replace_false": 0,
        "notes": [],
    }

    vpath = os.path.join(pack, "data", "rvp", "vehicles", vid + ".json")
    vdata = load_json(vpath) or {}

    # 1) 载具渲染模型：display.model
    dpath = os.path.join(pack, "assets", "rvp", "display", "vehicle", vid + ".json")
    ddata = load_json(dpath)
    if ddata is None:
        result["notes"].append("无载具 display")
        warnings.append(f"{vid}: 无 display/vehicle/{vid}.json")
    else:
        mref = ddata.get("model")
        mp = model_path(pack, base_assets, mref) if mref else None
        if mp is None:
            result["notes"].append("display 未配置 model")
        else:
            if mp not in memo:
                memo[mp] = mesh_stats(mp)
            st = memo[mp]
            result["render_tris"] = st["tris"]
            result["render_verts"] = st["verts_uni"]
            if st.get("missing"):
                result["notes"].append(f"渲染模型缺失 {mref}")
                warnings.append(f"{vid}: 渲染模型缺失 {mref}")

    # 2) 结构模型：structure_model
    sref = vdata.get("structure_model")
    sp = structure_model_path(pack, sref) if sref else None
    if sp is None:
        result["notes"].append("未配置 structure_model")
    else:
        if sp not in memo:
            memo[sp] = mesh_stats(sp)
        st = memo[sp]
        result["struct_tris"] = st["tris"]
        result["struct_verts"] = st["verts_uni"]
        if st.get("missing"):
            result["notes"].append(f"结构模型缺失 {sref}")
            warnings.append(f"{vid}: 结构模型缺失 {sref}")

    # 3) 武器站分组：unit_id → 可选武器 id 列表（同一站内互斥，同时只画一个）
    units: "OrderedDict[str, list[str]]" = OrderedDict()
    bolts_by_unit: dict[str, int] = {}
    for part in vdata.get("parts") or []:
        pid = part.get("id")
        if pid:
            bolts_by_unit[pid] = part_bolts(part)
        for entry in part.get("weapons") or []:
            ids = extract_weapon_ids(entry)
            if not ids:
                continue
            unit = (entry.get("part_unit_id") if isinstance(entry, dict) else None) or pid
            if not unit:
                continue
            bucket = units.setdefault(unit, [])
            for wid in ids:
                if wid not in bucket:
                    bucket.append(wid)

    # 4) 挂架条目：按 (unit, weapon_id) 归组，累计挂架模型面数
    mounts_by_key: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for mount in vdata.get("rvp_custom_mounts") or []:
        if not isinstance(mount, dict):
            continue
        unit = mount.get("part_unit_id")
        wid = mount.get("weapon_id")
        mref = mount.get("model")
        if not unit or not wid:
            continue
        if not mref:
            # 解析器要求 model/texture 齐全，缺一即整条丢弃（RVP_CustomMountConfig.parse）
            warnings.append(f"{vid}: 挂点缺 model（{unit}/{wid}），该条不生效")
            continue
        mp = model_path(pack, base_assets, mref)
        if mp and mp not in memo:
            memo[mp] = mesh_stats(mp)
        tris = memo[mp]["tris"] if mp else 0
        if mp and memo[mp].get("missing"):
            warnings.append(f"{vid}: 挂架模型缺失 {mref}")
        replace = mount.get("replace_weapon_display", True)
        if replace:
            result["mount_replace_true"] += 1
        else:
            result["mount_replace_false"] += 1
        mounts_by_key[(unit, wid)].append({"model": mref, "tris": tris, "replace": replace})
        result["mount_entries"] += 1
        result["mount_models"].append((unit, wid, mref, tris))

    # 5) 逐武器站计算：同站内取“最坏情况”一种弹种
    unit_total = 0
    for unit, wids in units.items():
        bolts = bolts_by_unit.get(unit, 1)
        best = 0
        for wid in wids:
            mounts = mounts_by_key.get((unit, wid)) or []
            replace_mounts = [m for m in mounts if m["replace"]]
            disp_tris = 0
            wpath = weapon_display_path(pack, wid)
            wd = load_json(wpath)
            if wd and wd.get("model"):
                mp = model_path(pack, base_assets, wd["model"])
                if mp and mp not in memo:
                    memo[mp] = mesh_stats(mp)
                disp_tris = memo[mp]["tris"] if mp else 0
                if mp is None:
                    warnings.append(f"{vid}: 武器 {wid} 的模型 {wd['model']} 无法解析（需要 --base-assets）")
                elif memo[mp].get("missing"):
                    warnings.append(f"{vid}: 武器模型缺失 {wd['model']}")
                result["weapon_models"].append((unit, wid, wd["model"], disp_tris))
            if replace_mounts:
                cand = sum(m["tris"] for m in replace_mounts)
            else:
                cand = disp_tris * bolts
            best = max(best, cand)
        unit_total += best

        # 记录“挂点但该站没有对应武器”的孤立条目（不会渲染，仅提示配置冗余）
        for (mu, mw), items in mounts_by_key.items():
            if mu == unit and mw not in wids:
                warnings.append(f"{vid}: 挂点 {mu}/{mw} 的武器不在该站可选列表内（{len(items)} 条不渲染）")

    # 挂点所属武器站完全不存在
    for (mu, mw), items in mounts_by_key.items():
        if mu not in units:
            warnings.append(f"{vid}: 挂点 {mu}/{mw} 的武器站不存在（{len(items)} 条不渲染）")

    result["weapon_tris"] = unit_total
    result["total_tris"] = result["render_tris"] + unit_total
    return result


# ---------------------------------------------------------------------------
# Markdown 输出
# ---------------------------------------------------------------------------

def fmt(n: int) -> str:
    """千分位，表格更易读。"""
    return f"{n:,}"


def collect(pack: str, base_assets: str):
    """扫描整包，返回 (按合计降序的载具结果, 告警列表, 模型缓存)。"""
    vdir = os.path.join(pack, "data", "rvp", "vehicles")
    vids = sorted(f[:-5] for f in os.listdir(vdir) if f.endswith(".json"))
    memo: dict = {}
    warnings: list[str] = []
    rows = [analyze_vehicle(pack, base_assets, vid, memo, warnings) for vid in vids]
    for path, st in memo.items():
        if st.get("ambiguous"):
            warnings.append(f"poly 编码存在歧义（已按标准索引式解释）：{os.path.relpath(path, pack)}")
    rows.sort(key=lambda r: -r["total_tris"])
    return rows, warnings, memo


def table_main(rows: list[dict]) -> str:
    """主表：每台载具一行。"""
    out = ["| # | 载具 | 渲染模型三角形 | 渲染顶点（唯一） | 挂载武器/挂架三角形 | 合计三角形 | 结构模型三角形 | 武器模型数 | 挂点条目 |",
           "|---:|---|---:|---:|---:|---:|---:|---:|---:|"]
    for i, r in enumerate(rows, 1):
        out.append(
            f"| {i} | `{r['id']}` | {fmt(r['render_tris'])} | {fmt(r['render_verts'])} | "
            f"{fmt(r['weapon_tris'])} | **{fmt(r['total_tris'])}** | {fmt(r['struct_tris'])} | "
            f"{len({m[2] for m in r['weapon_models']})} | {r['mount_entries']} |"
        )
    out.append(
        f"| — | **合计（{len(rows)} 台）** | **{fmt(sum(r['render_tris'] for r in rows))}** | — | "
        f"**{fmt(sum(r['weapon_tris'] for r in rows))}** | **{fmt(sum(r['total_tris'] for r in rows))}** | "
        f"**{fmt(sum(r['struct_tris'] for r in rows))}** | — | "
        f"**{sum(r['mount_entries'] for r in rows)}** |"
    )
    return "\n".join(out)


def table_weapon_models(rows: list[dict], pack: str, base_assets: str, memo: dict) -> str:
    """明细表：武器 display 模型（去重）。"""
    use: dict[str, set] = defaultdict(set)
    tris: dict[str, int] = {}
    verts: dict[str, int] = {}
    source: dict[str, str] = {}
    for r in rows:
        for unit, wid, mref, t in r["weapon_models"]:
            use[mref].add(r["id"])
            tris[mref] = t
            source[mref] = "本体" if not mref.startswith("rvp:") else "RVP"
            path = model_path(pack, base_assets, mref)
            if path:
                if path not in memo:
                    memo[path] = mesh_stats(path)
                verts[mref] = memo[path]["verts_uni"]

    out = ["| 武器 display 模型 | 来源 | 三角形 | 顶点（唯一） | 引用载具数 |",
           "|---|---|---:|---:|---:|"]
    for mref in sorted(tris, key=lambda m: (-tris[m], m)):
        out.append(f"| `{mref}` | {source[mref]} | {fmt(tris[mref])} | {fmt(verts.get(mref, 0))} | {len(use[mref])} |")
    return "\n".join(out)


def table_mount_models(rows: list[dict], pack: str, base_assets: str, memo: dict) -> str:
    """明细表：挂架（rvp_custom_mounts.model）模型（去重）。"""
    use: dict[str, set] = defaultdict(set)
    tris: dict[str, int] = {}
    verts: dict[str, int] = {}
    count: dict[str, int] = defaultdict(int)
    for r in rows:
        for unit, wid, mref, t in r["mount_models"]:
            use[mref].add(r["id"])
            tris[mref] = t
            count[mref] += 1
            path = model_path(pack, base_assets, mref)
            if path:
                if path not in memo:
                    memo[path] = mesh_stats(path)
                verts[mref] = memo[path]["verts_uni"]

    out = ["| 挂架模型 | 三角形 | 顶点（唯一） | 挂点条目数 | 引用载具数 |",
           "|---|---:|---:|---:|---:|"]
    for mref in sorted(tris, key=lambda m: (-tris[m], m)):
        out.append(f"| `{mref}` | {fmt(tris[mref])} | {fmt(verts.get(mref, 0))} | {count[mref]} | {len(use[mref])} |")
    return "\n".join(out)


def build_tables(pack: str, base_assets: str) -> str:
    """只输出表格（stdout / --out 用）。"""
    rows, warnings, memo = collect(pack, base_assets)
    parts = [
        "## 主表",
        "",
        table_main(rows),
        "",
        "## 武器 display 模型明细（去重）",
        "",
        table_weapon_models(rows, pack, base_assets, memo),
        "",
        "## 挂架模型明细（去重）",
        "",
        table_mount_models(rows, pack, base_assets, memo),
    ]
    if warnings:
        parts += ["", "## 数据告警", ""] + [f"- {w}" for w in warnings]
    return "\n".join(parts)


def build_doc(pack: str, base_assets: str, doc_title: str, date_str: str) -> str:
    """生成完整报告：口径说明 + 三张表 + 观察 + 复现命令。"""
    rows, warnings, memo = collect(pack, base_assets)
    total_render = sum(r["render_tris"] for r in rows)
    total_weapon = sum(r["weapon_tris"] for r in rows)
    total_struct = sum(r["struct_tris"] for r in rows)
    top = rows[:5]
    lowest = rows[-3:]
    with_weapon = sum(1 for r in rows if r["weapon_tris"] > 0)
    ratio = (total_struct / total_render * 100) if total_render else 0
    n = len(rows)

    # 结论用的派生数据（避免把数字写死在文案里，换包后重新生成仍准确）
    median_total = statistics.median([r["total_tris"] for r in rows]) if rows else 0
    mounts_total = sum(r["mount_entries"] for r in rows)
    mount_true = sum(r["mount_replace_true"] for r in rows)
    mount_false = sum(r["mount_replace_false"] for r in rows)
    wd_dir = os.path.join(pack, "assets", "rvp", "display", "weapon")
    wd_files = [f for f in os.listdir(wd_dir) if f.endswith(".json")] if os.path.isdir(wd_dir) else []
    wd_nomodel = sum(1 for f in wd_files if not (load_json(os.path.join(wd_dir, f)) or {}).get("model"))
    base_used = len({m[2] for r in rows for m in r["weapon_models"] if not m[2].startswith("rvp:")})
    base_files = 0
    for f in wd_files:
        wd = load_json(os.path.join(wd_dir, f)) or {}
        mref = wd.get("model") or ""
        if mref and not mref.startswith("rvp:"):
            base_files += 1
    all_weapon_models = {m[2]: m[3] for r in rows for m in r["weapon_models"]}
    heaviest_model, heaviest_model_tris = ("", 0)
    if all_weapon_models:
        heaviest_model, heaviest_model_tris = max(all_weapon_models.items(), key=lambda kv: kv[1])
    share = (total_weapon / (total_render + total_weapon) * 100) if (total_render + total_weapon) else 0

    lines = [
        f"# {doc_title}",
        "",
        f"> 生成时间：{date_str} ｜ 数据来源：`limitless_vehicle/rvp` ｜ 生成脚本：`scripts/stat_vehicle_mesh.py`",
        "> 本文所有数字为**静态几何**统计（含不可见/被动画缩放置 0 的骨），不是绘制调用数，也不是 LOD 代理。",
        "",
        "## 一、统计口径",
        "",
        "| 项 | 口径 |",
        "|---|---|",
        "| 三角形 | `poly_mesh` 按“同 poly 内不同位置索引数 n 做扇形三角化，取 max(n-2, 0)”；`cube` 按“实际存在的面 × 2” |",
        "| 顶点（唯一） | `poly_mesh.positions` 条数 + cube 8 角（每个盒子） |",
        "| 顶点（GPU 提交） | 三角形 × 3；SBM 的 `PolyMesh.Triangle` 直接存顶点记录，无索引复用 |",
        "| 载具渲染模型 | `display/vehicle/<id>.json` 的 `model` |",
        "| 结构模型 | 载具 JSON 的 `structure_model`，只用于物理/受击，不渲染 |",
        "| 挂载武器 display | `display/weapon/<武器>.json` 的 `model`，按武器站去重后取“最坏情况一种弹种” |",
        "| 挂架 | 载具 JSON `rvp_custom_mounts[].model`，每条挂点计一份 |",
        "| 不含 | 飞行中的弹体、子母弹子弹药、乘员、粒子/特效、远距 LOD 代理 |",
        "",
        "### 1.1 为什么 4 角点的 poly 只算 1 个三角形",
        "",
        "本包导出器的 `poly` 写的是 4 个角点，但**第 4 个角点与第 1 个完全相同**（位置索引相同）。",
        "SBM 的 `TreeBedrockModelBaker.bakePolygon` 对四边形发 `(0,1,2)` 与 `(2,3,0)` 两个三角形，",
        "而 `bakeTriangle` 开头用 `samePosition(a,b) || samePosition(b,c) || samePosition(c,a)` 过滤，",
        "含重复位置的三角形被丢弃 → 每条 poly 实际只有 1 个三角形。",
        "若机械按“quad × 2”统计会整体翻倍（例如 ebrcaa 会从 16,162 变成 32,324），这是本统计最容易错的一步。",
        "",
        "### 1.2 挂载武器怎么算",
        "",
        "- 同一武器站的多个武器是**可选弹种**（`ids` / 多个 `id` / `merge_into_previous_slot`），同一时刻只画一个 → 取组内最大值；",
        f"- `rvp_custom_mounts` 且 `replace_weapon_display=true`（本包 {mounts_total} 条挂点中 {mount_true} 条为 true、{mount_false} 条为 false）时，",
        "  武器自身 display 被挂架模型取代，按该站匹配该武器的挂点条目**累计**挂架模型（挂架模型内含 `missile_bones` 的导弹几何）；",
        "- 无挂点时，武器 display 按 `parts[].bolts` 的 bolt 数渲染（本包所有配置的 bolt 均为 1，缺省也是 1）；",
        "- 武器 display 允许引用本体模型（如 `ywzj_vehicle:entity/missile_pl_12`），",
        f"  本包有 {base_files} 个武器 display 声明了本体模型，其中 {base_used} 个不同模型实际被载具挂载并计入；",
        "  脚本默认探测仓库同级的 `ywzj_vehicle_fish/.../default_vehicle/assets`，找不到时按 0 计并告警。",
        "",
        "## 二、结果总览",
        "",
        f"- 载具渲染模型合计：**{fmt(total_render)}** 三角形；",
        f"- 挂载武器 / 挂架合计：**{fmt(total_weapon)}** 三角形；",
        f"- 两者相加：**{fmt(total_render + total_weapon)}** 三角形；",
        f"- 结构模型合计：**{fmt(total_struct)}** 三角形（约为渲染量的 {ratio:.1f}%）；",
        f"- {n} 台中 {with_weapon} 台有会渲染的挂载武器 display（其余为机炮/子弹类，display 无 `model`）。",
        "",
        "### 2.1 最重的五台",
        "",
        "| 载具 | 渲染模型三角形 | 挂载武器/挂架三角形 | 合计三角形 |",
        "|---|---:|---:|---:|",
    ]
    for r in top:
        lines.append(f"| `{r['id']}` | {fmt(r['render_tris'])} | {fmt(r['weapon_tris'])} | **{fmt(r['total_tris'])}** |")
    lines += [
        "",
        "### 2.2 最轻的三台（升序）",
        "",
        "| 载具 | 渲染模型三角形 | 挂载武器/挂架三角形 | 合计三角形 |",
        "|---|---:|---:|---:|",
    ]
    for r in reversed(lowest):
        lines.append(f"| `{r['id']}` | {fmt(r['render_tris'])} | {fmt(r['weapon_tris'])} | **{fmt(r['total_tris'])}** |")
    lines += [
        "",
        "## 三、主表（全部 {} 台，按合计降序）".format(n),
        "",
        table_main(rows),
        "",
        "> 列说明：`渲染顶点（唯一）` 为去重后的位置数；`挂载武器/挂架三角形` 为按武器站取最坏情况的合计；",
        "> `合计三角形` = 渲染模型 + 挂载武器/挂架；`结构模型三角形` 仅物理/受击，**不计入合计**；",
        "> `武器模型数` 为该车实际引用到的**不同** display 模型数；`挂点条目` 为 `rvp_custom_mounts` 条数。",
        "> 载具清单以 `data/rvp/vehicles` 为准（44 台）；`display/vehicle` 另有 1 个无数据文件的 `j10cp` 涂装变体，不计入。",
        "",
        "## 四、武器 display 模型明细",
        "",
        table_weapon_models(rows, pack, base_assets, memo),
        "",
        "## 五、挂架模型明细",
        "",
        table_mount_models(rows, pack, base_assets, memo),
        "",
        "## 六、结论与注意事项",
        "",
        f"1. **载具本体是主要成本**：{n} 台渲染模型合计约 {total_render / 10000:.0f} 万三角形，"
        f"挂载武器/挂架约 {total_weapon / 10000:.0f} 万（{share:.0f}%）。",
        f"2. **个别载具异常重**：`{top[0]['id']}`（{top[0]['total_tris'] / 10000:.1f} 万）、"
        f"`{top[1]['id']}`（{top[1]['total_tris'] / 10000:.1f} 万）明显高于整包中位数（约 {median_total / 10000:.1f} 万），",
        "   若要优化帧率，优先看这两台的大块网格（车体、雷达阵面等）。",
        "3. **挂载武器不是小头**："
        + "、".join(f"`{r['id']}`（{r['weapon_tris'] / 10000:.1f} 万）" for r in
                   sorted([r for r in rows if r["weapon_tris"] > 0], key=lambda r: -r["weapon_tris"])[:4])
        + "的挂载模型接近甚至超过车体；"
        f"`{heaviest_model}` 单个模型 {heaviest_model_tris:,} 三角形，是整包最重的武器模型。",
        f"4. **结构模型可以忽略**：合计 {fmt(total_struct)} 三角形，绝大多数是 box（每个盒 12 三角形），"
        "只有个别 APS 骨用了 poly 面。",
        "5. **武器 display 与载具内嵌弹药可能重叠**：部分载具（如 ebrcaa）渲染模型内嵌 `missile1/2`，",
        "   同时武器站还会渲染一份 display 模型；本表按各自定义分别计入，实际同屏是否叠加取决于模型摆放与隐藏脚本。",
        f"6. **机炮类 display 多为空**：{len(wd_files)} 个 `display/weapon` 里 {wd_nomodel} 个没有 `model`，它们在表中计 0——",
        "   这是正常设计（炮身在外观模型里，display 只给弹体/音效）。",
        "",
        "## 七、复现",
        "",
        "```powershell",
        "# 仅表格",
        "python scripts/stat_vehicle_mesh.py --pack limitless_vehicle/rvp --out build/mesh_tables.md",
        "",
        "# 重新生成本文档（自动带上本体回退模型）",
        "python scripts/stat_vehicle_mesh.py --doc docs/plan/agent层级梳理/整包载具与挂载武器模型统计_20261108.md",
        "```",
    ]
    if warnings:
        lines += ["", "## 八、数据告警", ""] + [f"- {w}" for w in warnings]
    return "\n".join(lines) + "\n"


def default_base_assets() -> str:
    """本体 default_vehicle/assets 的默认位置（与仓库同级的 ywzj_vehicle_fish 工作区）。"""
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    candidate = os.path.join(os.path.dirname(repo), "ywzj_vehicle_fish", "src", "main",
                             "resources", "default_vehicle", "assets")
    return candidate if os.path.isdir(candidate) else ""


def main() -> int:
    ap = argparse.ArgumentParser(description="统计 RVP 载具包模型面数")
    ap.add_argument("--pack", default=os.path.join("limitless_vehicle", "rvp"),
                    help="载具包根目录（含 data/ 与 assets/）")
    ap.add_argument("--base-assets", default="",
                    help="本体 default_vehicle/assets 路径；缺省自动探测 ../ywzj_vehicle_fish/...")
    ap.add_argument("--out", default="", help="把表格写入文件（UTF-8）")
    ap.add_argument("--doc", default="", help="生成完整报告到指定路径（UTF-8）")
    ap.add_argument("--date", default=datetime.date.today().isoformat(), help="报告日期（默认今天）")
    args = ap.parse_args()

    if not os.path.isdir(os.path.join(args.pack, "data", "rvp", "vehicles")):
        print(f"不是有效的载具包根目录：{args.pack}", file=sys.stderr)
        return 1

    base_assets = args.base_assets or default_base_assets()
    if not base_assets:
        print("警告：未找到本体 assets，非 rvp 命名空间的模型将按 0 计（可用 --base-assets 指定）",
              file=sys.stderr)

    if args.doc:
        title = "整包载具与挂载武器模型三角形统计"
        text = build_doc(args.pack, base_assets, title, args.date)
        os.makedirs(os.path.dirname(os.path.abspath(args.doc)), exist_ok=True)
        with open(args.doc, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print(f"written: {args.doc}")
        return 0

    text = build_tables(args.pack, base_assets)
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
        print(f"written: {args.out}")
    else:
        sys.stdout.reconfigure(encoding="utf-8")   # 避开 Windows 控制台 GBK 代码页
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
