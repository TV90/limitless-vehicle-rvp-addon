"""比较相同武器参数、相同场景下的 PIP/PPN 首次交会，不把近炸或绕圈后的接近算作命中。"""

import argparse
import json
import math
from pathlib import Path


def read_json(path):
    """读取 Gradle 回放与索引，接受 Windows UTF-8 文件头。"""
    return json.loads(path.read_text(encoding="utf-8-sig"))


def vector_subtract(first, second):
    """仅用于观测轨迹的相对位置，不实现弹道或制导。"""
    return [a - b for a, b in zip(first, second)]


def encounter(replay):
    """从生产实体回放独立测量首次相对运动段最近距离与最高高度。"""
    best = math.inf
    closest_tick = 0
    peak_y = -math.inf
    previous = None
    for frame in replay["frames"]:
        samples = {sample["id"]: sample for sample in frame["objects"]}
        missile = samples["projectile"]["position"]
        target = list(samples["target"]["position"])
        # 目标图形位置沿用导出器底部锚点；恢复真实目标中心后再测几何误差。
        target[1] += replay["scene"]["targetRadius"]
        relative = vector_subtract(missile, target)
        if previous is None:
            best = math.sqrt(sum(value * value for value in relative))
        else:
            delta = vector_subtract(relative, previous)
            length_squared = sum(value * value for value in delta)
            fraction = 0 if length_squared <= 1.0e-16 else max(
                0, min(1, -sum(a * b for a, b in zip(previous, delta)) / length_squared))
            distance = math.sqrt(sum((a + fraction * b) ** 2 for a, b in zip(previous, delta)))
            if distance > best + 1.0e-6 and closest_tick > 1:
                break
            if distance < best:
                best = distance
                closest_tick = frame["tick"]
        peak_y = max(peak_y, missile[1])
        previous = relative
    return {"firstMissBlocks": best, "closestTick": closest_tick, "peakY": peak_y}


def compare(before, after):
    """强制核对武器内容指纹和全部初态，禁止把调参结果当作算法对照。"""
    before_index = {entry["id"]: entry["file"] for entry in read_json(before / "index.json")["replays"]}
    after_index = {entry["id"]: entry["file"] for entry in read_json(after / "index.json")["replays"]}
    if before_index.keys() != after_index.keys():
        raise ValueError("对照场景集合不同")
    results = []
    for scene_id in sorted(before_index):
        pip = read_json(before / before_index[scene_id])
        ppn = read_json(after / after_index[scene_id])
        if pip["scene"] != ppn["scene"] or pip["weapon"] != ppn["weapon"]:
            raise ValueError(f"{scene_id} 的武器参数或场景初态不同")
        result = {"id": scene_id, "weapon": pip["scene"]["weapon"], "identicalInputs": True,
                  "pip": encounter(pip), "ppn": encounter(ppn)}
        results.append(result)
        print(f'{scene_id:30s} PIP={result["pip"]["firstMissBlocks"]:.6f}'
              f' PPN={result["ppn"]["firstMissBlocks"]:.6f}'
              f' peakY={result["pip"]["peakY"]:.2f}->{result["ppn"]["peakY"]:.2f}')
    return {"identicalInputs": True, "scenarios": results,
            "limitations": "理想实体观测；几何首次交会不等于真实碰撞、近炸、反制或命中率。"}


def main():
    """命令行只读取回放，并把本次对照写入显式指定的结果文件。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = compare(args.before, args.after)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
