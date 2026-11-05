"""核对原参数 PIP/PPN 机动矩阵，输出首次交会误差；截断、未开始机动与完成交会分别统计。"""

import argparse
import csv
import hashlib
import json
import math
import statistics
from pathlib import Path


# 类别顺序固定，避免不同进程或图表排序掩盖同条件配对。
CATEGORIES = {
    "CROSSING": "横穿匀速",
    "CROSSING_ACCELERATING": "横穿加速",
    "CROSSING_DECELERATING": "横穿减速",
    "TURNING": "变向匀速",
    "TURNING_ACCELERATING": "变向加速",
    "TURNING_DECELERATING": "变向减速",
}
# 小于一毫米游戏距离量级的差异记为近似相等，原始数值仍全部保存。
TIE_TOLERANCE = 0.001


def read(path):
    """读取 Java UTF-8 输出，不依赖机器默认编码。"""
    return json.loads(path.read_text(encoding="utf-8-sig"))


def quantile(values, probability):
    """线性插值分位数；空集合明确返回 null，不伪装为零误差。"""
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * probability
    low = int(index)
    return ordered[low] + (ordered[min(low + 1, len(ordered) - 1)] - ordered[low]) * (index - low)


def aggregate(rows):
    """只在双方都完成首次接近且都实际经历机动的共同集合比较误差。"""
    completed = [row for row in rows if row["pipCompleted"] and row["ppnCompleted"]]
    paired = [row for row in completed if row["effectiveManeuver"]]
    result = {"total": len(rows), "bothCompleted": len(completed), "effectivePairs": len(paired),
              "endedBeforeManeuver": len(completed) - len(paired),
              "pipOnlyCompleted": sum(row["pipCompleted"] and not row["ppnCompleted"] for row in rows),
              "ppnOnlyCompleted": sum(row["ppnCompleted"] and not row["pipCompleted"] for row in rows),
              "neitherCompleted": sum(not row["pipCompleted"] and not row["ppnCompleted"] for row in rows)}
    for mode in ("pip", "ppn"):
        values = [row[mode + "Miss"] for row in paired]
        result[mode] = {"median": statistics.median(values) if values else None,
                        "mean": statistics.mean(values) if values else None,
                        "p90": quantile(values, 0.90), "p95": quantile(values, 0.95),
                        "maximum": max(values) if values else None,
                        "within2Blocks": sum(value <= 2 for value in values),
                        "within5Blocks": sum(value <= 5 for value in values)}
    result["pipSmaller"] = sum(row["ppnMiss"] - row["pipMiss"] > TIE_TOLERANCE for row in paired)
    result["ppnSmaller"] = sum(row["pipMiss"] - row["ppnMiss"] > TIE_TOLERANCE for row in paired)
    result["ties"] = len(paired) - result["pipSmaller"] - result["ppnSmaller"]
    return result


def checked_rows(root):
    """任何参数、场景、源码身份不一致都终止报告，不能把错误配对生成漂亮平均数。"""
    plan_file = root / "plan.json"
    plan = read(plan_file)
    pip, ppn = read(root / "pip.json"), read(root / "ppn.json")
    expected_hash = hashlib.sha256(plan_file.read_bytes()).hexdigest()
    if pip["planSHA256"] != expected_hash or ppn["planSHA256"] != expected_hash:
        raise ValueError("结果不属于同一份固定场景清单")
    if pip["runtime"]["mode"] != "PIP" or ppn["runtime"]["mode"] != "PPN":
        raise ValueError("实际制导运行时标签错误")
    if pip["runtime"]["runtimeSHA256"] == ppn["runtime"]["runtimeSHA256"]:
        raise ValueError("两个进程加载了相同制导类，拒绝假对照")
    if pip["weapons"] != ppn["weapons"]:
        raise ValueError("两个进程的原始武器内容不同")
    samples = {sample["id"]: sample for sample in plan["cases"]}
    before = {sample["id"]: sample for sample in pip["cases"]}
    after = {sample["id"]: sample for sample in ppn["cases"]}
    if len(samples) != len(plan["cases"]) or len(before) != len(pip["cases"]) or len(after) != len(ppn["cases"]):
        raise ValueError("存在重复场景，不能按字典去重后冒充足量样本")
    if samples.keys() != before.keys() or samples.keys() != after.keys():
        raise ValueError("双方场景集合不一致")
    rows = []
    for case_id, sample in samples.items():
        if before[case_id]["sceneSHA256"] != after[case_id]["sceneSHA256"]:
            raise ValueError(f"{case_id} 的输入初态不同")
        scores = [before[case_id]["score"], after[case_id]["score"]]
        for score in scores:
            if (score["termination"] == "FIRST_ENCOUNTER") != (score["firstMissBlocks"] is not None):
                raise ValueError(f"{case_id} 把截断结果伪装为交会")
            if not math.isfinite(score["minimumObservedBlocks"]):
                raise ValueError(f"{case_id} 的测量出现非有限距离")
            if score["guidanceAppliedTicks"] < 1 and score["termination"] != "NO_GUIDANCE":
                raise ValueError(f"{case_id} 全程没有实际制导输出，不能作为算法试验")
        left, right = scores
        effective = sample["category"] == "CROSSING" or (left["maneuverObserved"] and right["maneuverObserved"])
        row = {"id": case_id, "category": sample["category"], "weapon": sample["weapon"],
               "loft": sample["loft"], "profile": sample["profile"], "mirror": sample["mirror"],
               "distance": sample["scene"]["target"][2], "referenceTick": sample["referenceTick"],
               "maneuverStart": sample["maneuverStart"], "maneuverDuration": sample["maneuverDuration"],
               "effectiveManeuver": effective}
        for mode, score in zip(("pip", "ppn"), scores):
            row.update({mode + "Completed": score["firstMissBlocks"] is not None,
                        mode + "Miss": score["firstMissBlocks"], mode + "ObservedMinimum": score["minimumObservedBlocks"],
                        mode + "Termination": score["termination"], mode + "TimeTicks": score["closestTimeTicks"],
                        mode + "ManeuverCompleted": score["maneuverCompleted"],
                        mode + "GuidedTicks": score["guidanceAppliedTicks"], mode + "Steps": score["steps"],
                        mode + "FirstGuidanceTick": score["firstGuidanceTick"],
                        mode + "MaxNoseBudgetRatio": score["maxNoseBudgetRatio"], mode + "PeakY": score["peakY"]})
        row["deltaPPNMinusPIP"] = right["firstMissBlocks"] - left["firstMissBlocks"] if all(
            score["firstMissBlocks"] is not None for score in scores) else None
        rows.append(row)
    return rows, pip["runtime"], ppn["runtime"]


def number(value):
    """报告中的空集合和距离分别显示，避免 null 看起来像零误差。"""
    return "—" if value is None else f"{value:.3f}"


def table(groups):
    """同一统计口径用于控制台和 Markdown。"""
    lines = ["| 类别 | 总数 / 有效配对 | PIP 中位 | PPN 中位 | PIP P90 | PPN P90 | PIP较小 / PPN较小 / 近似 |",
             "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for label, item in groups.items():
        lines.append(f'| {label} | {item["total"]} / {item["effectivePairs"]} | {number(item["pip"]["median"])}'
                     f' | {number(item["ppn"]["median"])} | {number(item["pip"]["p90"])} | {number(item["ppn"]["p90"])}'
                     f' | {item["pipSmaller"]} / {item["ppnSmaller"]} / {item["ties"]} |')
    return lines


def create_plots(root, rows):
    """使用标准绘图库生成静态科研图；展示下限不改变任何原始数值或分类统计。"""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager

    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    if font_path.exists():
        font_manager.fontManager.addfont(str(font_path))
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(font_path)).get_name()
    plt.rcParams["axes.unicode_minus"] = False
    eligible = [row for row in rows if row["pipCompleted"] and row["ppnCompleted"] and row["effectiveManeuver"]]
    floor = 1.0e-6
    fig, axes = plt.subplots(2, 3, figsize=(14, 8.5), sharey=True, constrained_layout=True)
    for axis, (category, label) in zip(axes.flat, CATEGORIES.items()):
        selected = [row for row in eligible if row["category"] == category]
        values = [[max(floor, row[mode + "Miss"]) for row in selected] for mode in ("pip", "ppn")]
        box = axis.boxplot(values, tick_labels=["PIP", "PPN"], patch_artist=True, whis=(5, 95),
                           flierprops={"markersize": 2, "alpha": 0.35})
        for patch, color in zip(box["boxes"], ("#3584b9", "#e27a36")):
            patch.set_facecolor(color)
            patch.set_alpha(0.55)
        axis.set_yscale("log")
        axis.set_title(f"{label}  n={len(selected)}")
        axis.set_ylabel("首次交会误差（格，对数轴）")
        axis.grid(axis="y", alpha=0.2)
        axis.axhline(2, color="#8a8a8a", linestyle="--", linewidth=0.8)
    fig.suptitle("原参数 PIP / PPN · 连续目标机动对照\n仅比较双方完成首次接近且已经历机动的共同集合；虚线为 2 格几何距离", fontsize=14)
    fig.savefig(root / "error-distribution.png", dpi=170)
    fig.savefig(root / "error-distribution.svg")
    plt.close(fig)

    fig, axes = plt.subplots(1, 2, figsize=(12, 6), constrained_layout=True)
    palette = plt.get_cmap("tab10")
    for axis, loft in zip(axes, (False, True)):
        selected = [row for row in eligible if row["loft"] == loft]
        all_values = [max(floor, row[mode + "Miss"]) for row in selected for mode in ("pip", "ppn")]
        low = min(all_values) * 0.6 if all_values else floor
        high = max(all_values) * 1.5 if all_values else 1
        for index, (category, label) in enumerate(CATEGORIES.items()):
            subset = [row for row in selected if row["category"] == category]
            axis.scatter([max(floor, row["pipMiss"]) for row in subset],
                         [max(floor, row["ppnMiss"]) for row in subset], s=11, alpha=0.45,
                         color=palette(index), label=label)
        axis.plot([low, high], [low, high], color="#555", linestyle="--", linewidth=1)
        axis.set(xscale="log", yscale="log", xlim=(low, high), ylim=(low, high),
                 xlabel="PIP 首次交会误差（格）", ylabel="PPN 首次交会误差（格）",
                 title="原配高抛：路线与 PPN 衔接" if loft else "无高抛：实体 PPN")
        axis.grid(alpha=0.2)
    axes[1].legend(fontsize=8, loc="upper left")
    fig.suptitle("同条件配对：对角线下方为 PPN 误差较小，上方为 PIP 误差较小", fontsize=13)
    fig.savefig(root / "paired-errors.png", dpi=170)
    fig.savefig(root / "paired-errors.svg")
    plt.close(fig)


def main():
    """只读取结果并生成报告，不调参、不改写原始 PIP/PPN 数据。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--plot", action="store_true", help="额外使用 matplotlib 生成 PNG/SVG")
    args = parser.parse_args()
    rows, pip_runtime, ppn_runtime = checked_rows(args.root)
    categories = {label: aggregate([row for row in rows if row["category"] == category])
                  for category, label in CATEGORIES.items()}
    weapons = {weapon: aggregate([row for row in rows if row["weapon"] == weapon])
               for weapon in dict.fromkeys(row["weapon"] for row in rows)}
    scope = {label: aggregate([row for row in rows if row["loft"] == loft])
             for label, loft in (("无高抛", False), ("原配高抛", True))}
    summary = {"schemaVersion": 1, "identicalInputs": True, "totalPairs": len(rows),
               "pipRuntime": pip_runtime, "ppnRuntime": ppn_runtime, "overall": aggregate(rows),
               "categories": categories, "weapons": weapons, "scope": scope,
               "tieToleranceBlocks": TIE_TOLERANCE}
    (args.root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (args.root / "paired-results.csv").open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    lines = ["# PIP / PPN 连续目标机动对照", "", f"共 {len(rows)} 组同条件配对；距离单位均为格。", "",
             "P90 表示九成样本的误差不超过该值。近似相等阈值为 0.001 格。", "",
             "统计只使用双方完成首次接近且实际经历机动的共同集合。超时、寿终和机动前交会单列，不能当作普通脱靶或零误差。", ""]
    lines += table(categories)
    lines += ["", "## 按武器", ""] + table(weapons)
    lines += ["", "## 高抛与无高抛", ""] + table(scope)
    lines += ["", "## 截断与机动是否发生", "",
              "| 类别 | 机动前交会 | 仅PIP完成 | 仅PPN完成 | 双方均截断 |",
              "| --- | ---: | ---: | ---: | ---: |"]
    for label, item in categories.items():
        lines.append(f'| {label} | {item["endedBeforeManeuver"]} | {item["pipOnlyCompleted"]}'
                     f' | {item["ppnOnlyCompleted"]} | {item["neitherCompleted"]} |')
    effective = [row for row in rows if row["pipCompleted"] and row["ppnCompleted"] and row["effectiveManeuver"]]
    for title, reverse in (("PPN 相比 PIP 增大误差最多的配对", True), ("PPN 相比 PIP 减小误差最多的配对", False)):
        lines += ["", "## " + title, "", "| 场景 | PIP | PPN | PPN-PIP |", "| --- | ---: | ---: | ---: |"]
        for row in sorted(effective, key=lambda sample: sample["deltaPPNMinusPIP"], reverse=reverse)[:12]:
            lines.append(f'| {row["id"]} | {number(row["pipMiss"])} | {number(row["ppnMiss"])}'
                         f' | {number(row["deltaPPNMinusPIP"])} |')
    lines += ["", "## 证据与边界", "",
              "- `plan.json` 与 `scenarios/` 保存全部初态；`pip.json`、`ppn.json` 保存实际制导 class 指纹及武器原始快照。",
              "- 两算法共用真实弹体、气动、机头惯量、发动机和门控；旧 PIP 仅在独立测试进程覆盖制导类。",
              "- 从首次有效制导开始识别首次接近；点火等待期间的距离波动不算交会，全程无制导单列为 NO_GUIDANCE。",
              "- 目标位置由连续速度函数积分，速度和航向在段连接处连续；不是瞬间掉头或任意传送。",
              "- 时间标尺来自原 PIP 同条件匀速试验；不能完成时使用明确的距离/设计极速名义时间。",
              "- 理想实体观测和理想自主捕获事件，不验证实际搜索、照射、反制、网络、引信或命中率。",
              "- 转弯过载预算不作调整；高抛组统计的是现有路线/PPN混合链，不能当作全程纯PPN。",
              "- 数据保留原始精度；可选对数图只在绘图时把小于1e-6格的值画在下限，不改统计。", ""]
    if args.plot:
        create_plots(args.root, rows)
        lines += ["![六类误差分布](error-distribution.png)", "", "![同条件散点对照](paired-errors.png)", ""]
    (args.root / "report.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(table(categories)))
    print(f"已核对 {len(rows)} 组；有效共同集合 {summary['overall']['effectivePairs']} 组。")


if __name__ == "__main__":
    main()
