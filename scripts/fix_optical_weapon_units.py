"""只调整权威载具包中可越过竖直方向的飞机观瞄站类型，保留原 JSON 格式。"""

import argparse
import json
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="写入类型；省略时只检查")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    changed = 0
    for path in sorted((root / "limitless_vehicle/rvp/data/rvp/vehicles").glob("*.json")):
        source = path.read_bytes().decode("utf-8")
        config = json.loads(source)
        if config.get("type") != "ywzj_vehicle:fixed_wing_vehicle":
            continue
        header = re.search(r'"parts"\s*:\s*\[', source)
        if header is None:
            continue
        decoder = json.JSONDecoder()
        cursor = header.end()
        edits = []
        for part in config.get("parts", []):
            while source[cursor].isspace() or source[cursor] == ",":
                cursor += 1
            parsed, end = decoder.raw_decode(source, cursor)
            assert parsed == part
            rotation = part.get("rot_info", {})
            crosses_pole = rotation.get("x_rot_max", 90) > 90 or rotation.get("x_rot_min", -90) < -90
            if part.get("with_focus_locker") and crosses_pole:
                assert part["type"] in ("ywzj_vehicle:weapon", "ywzj_rvp:optical_weapon")
                print(f'{path.name}: {part["id"]} -> ywzj_rvp:optical_weapon')
                if part["type"] == "ywzj_vehicle:weapon":
                    fragment, count = re.subn(r'("type"\s*:\s*)"ywzj_vehicle:weapon"',
                                              r'\1"ywzj_rvp:optical_weapon"', source[cursor:end], count=1)
                    assert count == 1
                    edits.append((cursor, end, fragment))
            cursor = end
        if edits:
            changed += len(edits)
            if args.apply:
                backup = root / "build/optical-sight-pack-before" / path.name
                backup.parent.mkdir(parents=True, exist_ok=True)
                if not backup.exists():
                    backup.write_bytes(source.encode("utf-8"))
                for start, end, fragment in reversed(edits):
                    source = source[:start] + fragment + source[end:]
                path.write_bytes(source.encode("utf-8"))
    print(f'{"已更新" if args.apply else "待更新"} {changed} 个观瞄站；不改转角、稳定器、结构模型或其他部件。')


if __name__ == "__main__":
    main()
