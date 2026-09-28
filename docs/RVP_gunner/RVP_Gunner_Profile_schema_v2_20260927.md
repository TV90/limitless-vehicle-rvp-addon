# RVP Gunner Profile schema v2

> 当前版本：2  
> 资源目录：`data/<namespace>/gunner/*.json`  
> 旧平铺 schema：不支持；只可使用 `scripts/migrate_gunner_profiles_v2.py` 离线改写

11 个当前 Profile 盘点、全部 18 种行为字段、范围、生效条件和组合范本见
[RVP_Gunner_JSON配置现状与详细指南_20260928.md](./RVP_Gunner_JSON配置现状与详细指南_20260928.md)。

## 顶层

```json
{
  "schema_version": 2,
  "name": "static_gunner",
  "faction": "friendly",
  "behaviors": []
}
```

- `schema_version`：必须为整数 `2`。
- `name`：可选显示名；缺省为资源路径。
- `faction`：可选，只允许 `friendly`、`enemy`、`team`。
- `behaviors`：必需数组；数组顺序用于同优先级仲裁。
- 未知顶层字段会拒绝整个 reload。

## 行为项

```json
{
  "id": "main_combat",
  "type": "rvp:weapon_engagement",
  "priority": 500,
  "config": {}
}
```

- `id`：Profile 内唯一实例 ID；只允许小写字母、数字、`_`、`-`、`.`。
- `type`：注册行为类型 ID，不允许 Java 类名。
- `priority`：可选整数，当前允许 `0..1000`；高者先获得互斥通道。
- `config`：可选对象，只接受该行为注册的字段。
- 同一 `type` 可出现多次，但每个实例拥有独立运行时和配置。

## 已注册行为类型

| 类型 | 主要配置 |
|---|---|
| `rvp:ciws_targeting` | `scan_interval_tick`、`target_cooldown_tick` |
| `rvp:primary_targeting` | `target_types`、`gps_prefer_farthest`、`search_radius`、`scan_interval_tick`、`engagement_net_cooldown_tick` |
| `rvp:driver_supply` | 无 |
| `rvp:weapon_countermeasure` | `range`、`cooldown_tick` |
| `rvp:rvp_countermeasure` | `scan_interval_tick`、`cooldown_tick`、导弹/雷达威胁半径 |
| `rvp:active_ecm` | `threat_range` |
| `rvp:smoke_evasion` | `scan_interval_tick`、`hold_tick`、`look_radius` |
| `rvp:ownship_radar` | 无 |
| `rvp:external_radar` | 无 |
| `rvp:guided_weapon_support` | 无 |
| `rvp:sead_revenge` | 扫描、范围、各阶段时长、总超时和冷却 |
| `rvp:fixed_wing_combat_flight` | AGL、作战半径及空战阶段时长 |
| `rvp:rotary_wing_combat_flight` | AGL 及空战阶段时长 |
| `rvp:launcher_positioning` | `stop_distance` |
| `rvp:stuck_recovery` | 检查间隔、卡住距离、恢复时长 |
| `rvp:ground_engagement_move` | 停车距离、观察/侧移时长和侧移角 |
| `rvp:ground_patrol` | 大转弯间隔、角度和时长 |
| `rvp:weapon_engagement` | 瞄准窗口、提前量、burst、制导/CIWS 冷却 |

精确字段名与当前示例以 `limitless_vehicle/rvp/data/rvp/gunner/default.json` 为准；加载错误会输出
资源 ID、行为数组下标和字段路径。行为的“存在”即启用，删除即禁用；不要再添加 `allow_drive` 等旧总开关。
