"""把本地运行包 enemy Gunner Profile 补齐步行玩家目标类型。"""

from __future__ import annotations

import json
import re
from pathlib import Path


# 三份开发运行包的 enemy Profile；载具包资产仍留在本地运行目录，不加入 Git 暂存区。
PROFILE_PATHS = (
    Path("run/server/limitless_vehicle/rvp/data/rvp/gunner/enemy.json"),
    Path("run/client_1/limitless_vehicle/rvp/data/rvp/gunner/enemy.json"),
    Path("run/client_2/limitless_vehicle/rvp/data/rvp/gunner/enemy.json"),
)


def main() -> None:
    """校验 Profile JSON 后，仅在 target_types 数组末尾补入 player。"""
    for profile_path in PROFILE_PATHS:
        with profile_path.open("r", encoding="utf-8", newline="") as source:
            raw_text = source.read()
        profile = json.loads(raw_text)
        target_types = profile.get("target_types")
        if profile.get("name") != "enemy" or not isinstance(target_types, list):
            raise ValueError(f"不符合预期的 enemy Gunner Profile: {profile_path}")
        if "player" in target_types:
            continue

        updated_target_types = [*target_types, "player"]
        replacement = json.dumps(updated_target_types, ensure_ascii=False, separators=(", ", ": "))
        updated_text, replacements = re.subn(
            r'("target_types"\s*:\s*)\[[^\]]*\]',
            lambda match: f"{match.group(1)}{replacement}",
            raw_text,
            count=1,
        )
        if replacements != 1:
            raise ValueError(f"无法唯一定位 target_types 数组: {profile_path}")
        # 原样保留每份运行包 JSON 的换行格式与其它配置。
        with profile_path.open("w", encoding="utf-8", newline="") as destination:
            destination.write(updated_text)


if __name__ == "__main__":
    main()
