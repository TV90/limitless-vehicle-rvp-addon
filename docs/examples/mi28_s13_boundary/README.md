# Mi-28 S-13 子母弹边界测试 — 示例 JSON

本目录为 **可纳入 Git** 的武器数据副本；开发时复制到载具包：

`limitless-vehicle-rvp-addon/run/client_1/limitless_vehicle/rvp/data/rvp/weapons/`

说明与预期结果见 [子母弹系统与Mi28边界测试.md](../../子母弹系统与Mi28边界测试.md)。

## 文件列表

| 文件 | 资源 ID |
| --- | --- |
| `mi28_s13_t01_cluster.json` … `mi28_s13_t10_ground_disp.json` | `rvp:mi28_s13_t01_cluster` … `t10` |
| `mi28_s13_bomblet_he.json` 等 | 子战斗部，勿单独挂载 |

## 载具挂载片段

在 `data/rvp/vehicles/mi28.json` → `sighting_system` → `weapons` 追加 `part_unit_id: "rocket"` 条目（见主文档第 3 节）。
