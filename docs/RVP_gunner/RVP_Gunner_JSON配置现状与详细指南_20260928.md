# RVP Gunner JSON 配置现状与详细指南

> 文档日期：2026-09-28  
> 当前 schema：`2`  
> 权威配置目录：`limitless_vehicle/rvp/data/rvp/gunner/`  
> 适用代码：阶段 F“启用 JSON 行为组合”完成后的当前实现

本文档同时回答两个问题：

1. 当前载具包中 11 个 Gunner Profile 分别配了什么；
2. 如何用 schema v2 编写、组合和调试新的 Gunner Profile。

本文以当前 JSON、`RVP_GunnerProfileCompiler`、`RVP_GunnerBehaviorRegistry` 和内建行为实现为准。若后续修改字段、默认值或范围，应同步更新本文。

---

## 1. 快速入门

### 1.1 最小可用结构

```json
{
  "schema_version": 2,
  "name": "example",
  "faction": "friendly",
  "behaviors": [
    {
      "id": "main_target",
      "type": "rvp:primary_targeting",
      "priority": 500,
      "config": {
        "target_types": ["vehicle", "monster", "player"],
        "search_radius": 192.0,
        "scan_interval_tick": 10
      }
    },
    {
      "id": "main_combat",
      "type": "rvp:weapon_engagement",
      "priority": 500,
      "config": {}
    }
  ]
}
```

将文件保存为：

```text
limitless_vehicle/rvp/data/rvp/gunner/example.json
```

当前加载器会把文件名映射成 Profile ID：

```text
ywzj_rvp:example
```

服务端登录或 datapack reload 后会把 Profile ID 列表同步给客户端，客户端为每个 ID 生成一个带 `ProfileId` NBT 的通用 Gunner 生成器变体。新增 JSON 时无需新增 Java 物品注册。

### 1.2 时间与距离单位

- `tick`：服务端正常 20 tick = 1 秒。
- 距离：Minecraft 格/方块单位。
- `AGL`：相对当地地面的高度，不是世界绝对 Y 坐标。
- 角度：度。
- 优先级：无单位，数值越大越先参与同一资源通道的仲裁。

### 1.3 修改配置的基本原则

- 启用行为：在 `behaviors` 中增加该行为实例。
- 禁用行为：删除整个行为实例，不再使用 `allow_drive` 等旧总开关。
- 不适用当前载具的行为会静默跳过。例如完整 Profile 同时含固定翼和旋翼飞行行为，陆车上两者都不会执行。
- 任一 Profile 编译失败时，整批 reload 不发布，继续使用上一代完整快照。
- `ywzj_rvp:default` 必须存在。未知、空或非法 Profile ID 会回退到 `default`。
- Java 不支持旧平铺 schema。历史文件只能用 `scripts/migrate_gunner_profiles_v2.py` 离线改写。

---

## 2. 当前 11 个 Profile 现状

### 2.1 总览

| Profile | 阵营 | 行为数 | 当前用途与关键特征 |
| --- | --- | ---: | --- |
| `air` | `friendly` | 9 | 飞行载具的武器操作员组合；远距离索敌、高提前量、快短点射；**当前不含任何驾驶行为** |
| `ciws_only` | `friendly` | 3 | 仅 CIWS 来袭弹药索敌、本车雷达和开火；不选普通目标 |
| `default` | `friendly` | 18 | 通用完整组合；目标、驾驶、防御、雷达、制导和 SEAD 全部启用 |
| `enemy` | `enemy` | 18 | 完整组合；远距离、长 burst，目标为玩家、玩家载具、中立生物和 friendly Gunner 载具 |
| `friendly` | `friendly` | 18 | 完整组合；主要打怪物和 enemy Gunner 载具 |
| `ground` | `friendly` | 18 | 完整组合；更短停车距离、更敏感的脱困阈值和更长脱困时间 |
| `mixed` | `friendly` | 18 | 完整组合；宽目标类型，兼顾普通载具、怪物、玩家和其他生物 |
| `sead_pilot` | `friendly` | 18 | 完整组合；基础索敌半径1024 格，用于带 AntiRadiation 武器的驾驶员 |
| `static_gunner` | `friendly` | 6 | 固定武器座；普通索敌、雷达、制导和开火；不写载具移动 |
| `static_anti_air` | `friendly` | 6 | 静态防空炮手；CIWS 与普通索敌仅选 `rvp:missile`、`vehicle:aircraft`，不写载具移动 |
| `team` | `team` | 18 | 完整组合；以 Minecraft Team 联盟关系过滤，打玩家和非盟友 Gunner 载具 |

### 2.2 行为组合模式

当前 11 个 Profile 实际使用五种组合模式。

| 模式 | Profile | 行为组成 |
| --- | --- | --- |
| 完整 18 行为 | `default`、`enemy`、`friendly`、`ground`、`mixed`、`sead_pilot`、`team` | 全部注册行为；不适用载具类型的行为由能力门控静默跳过 |
| 飞行武器操作员 | `air` | CIWS、普通索敌、本体/RVP 反制、ECM、本车/外置雷达、制导维持、武器交战 |
| 静态炮手 | `static_gunner` | CIWS、普通索敌、本车/外置雷达、制导维持、武器交战 |
| 静态防空炮手 | `static_anti_air` | CIWS、仅导弹/飞机普通索敌、本车/外置雷达、制导维持、武器交战；不含移动行为 |
| 纯 CIWS | `ciws_only` | CIWS 索敌、本车雷达、武器交战 |

完整 18 行为 Profile 当前的声明顺序和显式优先级如下。所有值都与当前注册表缺省优先级相同：

| 顺序 | 实例 ID | 行为类型 | 优先级 |
| ---: | --- | --- | ---: |
| 1 | `incoming_ammo` | `rvp:ciws_targeting` | 850 |
| 2 | `main_target` | `rvp:primary_targeting` | 500 |
| 3 | `resupply` | `rvp:driver_supply` | 100 |
| 4 | `base_defense` | `rvp:weapon_countermeasure` | 800 |
| 5 | `rvp_defense` | `rvp:rvp_countermeasure` | 850 |
| 6 | `ecm` | `rvp:active_ecm` | 850 |
| 7 | `smoke_cover` | `rvp:smoke_evasion` | 820 |
| 8 | `own_radar` | `rvp:ownship_radar` | 500 |
| 9 | `external_radar` | `rvp:external_radar` | 510 |
| 10 | `guidance` | `rvp:guided_weapon_support` | 520 |
| 11 | `sead` | `rvp:sead_revenge` | 950 |
| 12 | `fixed_wing_flight` | `rvp:fixed_wing_combat_flight` | 500 |
| 13 | `rotary_wing_flight` | `rvp:rotary_wing_combat_flight` | 500 |
| 14 | `launcher_move` | `rvp:launcher_positioning` | 500 |
| 15 | `recover` | `rvp:stuck_recovery` | 800 |
| 16 | `ground_combat_move` | `rvp:ground_engagement_move` | 500 |
| 17 | `ground_idle_patrol` | `rvp:ground_patrol` | 200 |
| 18 | `main_combat` | `rvp:weapon_engagement` | 500 |

注意：`air` 这个名字不代表它会开飞机。当前它不含 `fixed_wing_combat_flight`、`rotary_wing_combat_flight` 或其他移动行为，因此只适合武器操作座。需要 AI 驾驶飞机时，应使用包含飞行行为的 Profile，或基于 `air` 新建组合。

### 2.3 当前索敌与开火参数差异

| Profile | `target_types` | 基础半径 | 扫描间隔 | 开火窗 | 提前量 | burst 开/停 | 本体反制 半径/冷却 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `air` | `vehicle`, `player` | 320 | 6 | 4° | 1.5 | 4/8 | 52/60 |
| `ciws_only` | 无普通索敌行为 | — | — | 6° | 1.0 | 6/10 | — |
| `default` | `vehicle`, `monster`, `player` | 192 | 10 | 6° | 1.0 | 6/10 | 36/80 |
| `enemy` | `vehicle:player`, `player`, `neutral`, `vehicle:friendly_gunner` | 1024 | 6 | 5° | 1.2 | 40/8 | 40/140 |
| `friendly` | `monster`, `vehicle:enemy_gunner` | 224 | 8 | 6° | 1.0 | 8/10 | 36/80 |
| `ground` | `vehicle`, `monster`, `player` | 192 | 8 | 5° | 0.8 | 10/14 | 40/70 |
| `mixed` | `vehicle`, `monster`, `player`, `living` | 224 | 8 | 5° | 1.2 | 6/8 | 40/70 |
| `sead_pilot` | `vehicle`, `player` | 1024 | 10 | 6° | 1.0 | 6/10 | 36/80 |
| `static_gunner` | `vehicle`, `monster`, `player` | 192 | 10 | 6° | 1.0 | 6/10 | — |
| `static_anti_air` | `rvp:missile`, `vehicle:aircraft` | 320 | 6 | 4° | 1.5 | 4/8 | — |
| `team` | `player`, `vehicle:non_allied_gunner` | 256 | 8 | 5° | 1.0 | 6/10 | 36/80 |

表中“基础半径”不一定等于最终索敌半径：

- 固定翼和旋翼载具会把 `search_radius` 乘以 6。
- 本车或外置中继雷达存在时，最终半径取“飞行倍率后基础半径”与“雷达最大扫描距离”中的较大值。
- 实际能否发射仍受武器射程、导引头、锁定、弹药、冷却和射界门控。

### 2.4 其他现有差异

- `ground`：`launcher_positioning.stop_distance` 和 `ground_engagement_move.stop_distance` 为 10，其他完整 Profile 为 12。
- `ground`：`stuck_distance=0.8`、`recovery_tick=24`。
- `mixed`：`stuck_distance=0.8`、`recovery_tick=22`。
- 其他完整 Profile：`stuck_distance=1.0`、`recovery_tick=20`。
- `ground`：`lead_scale=0.8`，但当前实现只对 `lead_scale > 1` 追加速度修正，因此 0.8 与 1.0 的实际瞄准点相同。
- 所有含 `active_ecm` 的现有 Profile 均使用 `threat_range=400`，而编译器缺省值是 200。
- 现有 RVP 反制、Smoke、SEAD、巡航和飞行参数除上述项外均使用当前缺省组合。

---

## 3. Profile 顶层字段

| 字段 | 类型 | 必需 | 缺省 | 说明 |
| --- | --- | --- | --- | --- |
| `schema_version` | 整数 | 是 | 无 | 必须等于 `2`；不支持旧 schema |
| `name` | 非空字符串 | 否 | 文件资源路径 | Profile 显示名；不决定资源 ID |
| `faction` | 字符串 | 否 | `friendly` | 只允许 `friendly`、`enemy`、`team` |
| `behaviors` | 数组 | 是 | 无 | 声明行为实例；可为空，空数组表示只保留管理器安全清理 |

顶层出现任何未知字段都会拒绝整批 reload。

### 3.1 `faction` 语义

| 值 | 当前语义 |
| --- | --- |
| `friendly` | 不攻击放置者/所有者，并应用载具与 Gunner Team 的盟友过滤 |
| `enemy` | 不应用 Team 盟友过滤，且可攻击放置者；仍必须命中 `target_types` |
| `team` | 按 Minecraft Team 盟友关系过滤；建议配合 `vehicle:non_allied_gunner` |

`faction` 不会自动生成目标列表。阵营过滤与 `target_types` 必须同时满足。

---

## 4. 行为项通用字段与仲裁

```json
{
  "id": "main_combat",
  "type": "rvp:weapon_engagement",
  "priority": 500,
  "config": {}
}
```

| 字段 | 类型 | 必需 | 规则 |
| --- | --- | --- | --- |
| `id` | 字符串 | 是 | Profile 内唯一；只能含小写字母、数字、`_`、`-`、`.` |
| `type` | 资源 ID 字符串 | 是 | 建议总是写完整 `rvp:<type>`；未知类型会拒绝 reload |
| `priority` | 整数 | 否 | 缺省值由行为类型提供；当前所有类型允许 `0..1000` |
| `config` | 对象 | 否 | 缺省 `{}`；只允许该行为声明的字段 |

同一 `type` 可以出现多次，但每个实例必须使用不同 `id`。运行时状态也按 `id` 隔离。

### 4.1 仲裁顺序

当多个行为争用同一通道和同一资源时，按以下顺序选出胜者：

1. `priority` 较大者优先；
2. 优先级相同时，`behaviors` 数组中靠前者优先；
3. 仍相同时，按行为实例 `id` 字典序。

优先级只在“同通道 + 同资源键”冲突时生效。例如本车雷达与外置雷达使用不同资源键，可以同 tick 维持；移动和常规开火则各有唯一胜者。

### 4.2 驾驶能力的判定

Profile 包含下列任一行为时，才被认为具有移动能力：

- `rvp:smoke_evasion`
- `rvp:sead_revenge`
- `rvp:fixed_wing_combat_flight`
- `rvp:rotary_wing_combat_flight`
- `rvp:launcher_positioning`
- `rvp:stuck_recovery`
- `rvp:ground_engagement_move`
- `rvp:ground_patrol`

即使 Profile 含移动行为，Gunner 不在驾驶位、载具类型不匹配或能力门控失败时，也不会写入载具控制。

---

## 5. `target_types` 可用值

| 值 | 匹配对象 |
| --- | --- |
| `rvp:missile` | RVP/本体的可拦截导弹、航空炸弹和火箭弹；不包括普通机枪弹 |
| `monster` | 怪物类生物 |
| `player` | 玩家实体 |
| `neutral` | 非玩家、非载具、非 Gunner、非怪物的其他生物 |
| `living` | 任意 `LivingEntity`，仍受自身、乘员、所有者和盟友过滤 |
| `vehicle` | 任意已有乘员的载具；空载具不匹配 |
| `vehicle:aircraft` | 有驾驶员的固定翼或旋翼载具；按载具类别判断，不检查当前离地/飞行状态 |
| `vehicle:player` | 由玩家驾驶的载具 |
| `vehicle:enemy_gunner` | 由 `enemy` Gunner 驾驶的载具 |
| `vehicle:friendly_gunner` | 由 `friendly` Gunner 驾驶的载具 |
| `vehicle:team_gunner` | 由 `team` Gunner 驾驶的载具 |
| `vehicle:non_allied_gunner` | 由非盟友 Gunner 驾驶的载具 |

额外过滤规则：

- 死亡实体、Gunner 自身、当前载具和同车乘员不会成为目标。
- `vehicle:aircraft` 匹配固定翼与旋翼载具类别；飞机停在地面时仍匹配，空载飞机不匹配。
- 步行的创造/旁观玩家在任何难度下受保护。
- 带创造/旁观乘员的载具在非困难难度下受保护；困难难度下可被攻击。
- 来袭弹药会过滤本机、同阵营 Gunner、同车乘员和盟友的弹药。
- 载具与弹药的分角度 RCS 会缩短实际感知距离。

当前编译器只验证 `target_types` 是非空字符串数组，不校验枚举值。拼错的字符串不会阻止 reload，但永远不会匹配目标，因此必须从上表选择。

---

## 6. 18 种行为详细配置

下文的“缺省优先级”只在行为项未写 `priority` 时使用。当前每种行为的允许优先级均为 `0..1000`。

### 6.1 `rvp:ciws_targeting`

来袭弹药优先索敌，提交 TARGET 意图。需要当前座位拥有武器站。缺省优先级：850。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `scan_interval_tick` | 整数 | 1 | 1..1200 | tick | CIWS 目标扫描间隔 |
| `target_cooldown_tick` | 整数 | 100 | 0..72000 | tick | 自导武器成功进入发射链后，对同一弹药目标的冷却；也可被 `weapon_engagement` 继承 |

### 6.2 `rvp:primary_targeting`

普通目标过滤、分层和评分，提交 TARGET 意图。缺省优先级：500。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `target_types` | 字符串数组 | `["rvp:missile","vehicle","monster","player"]` | 至少 1 项 | — | 允许目标类型，详见第 5 节 |
| `gps_prefer_farthest` | 布尔 | `true` | `true/false` | — | 有可用 GPS 武器时，在当前目标分层内优先选最远的 GPS 可打击目标，并优先选 GPS 武器 |
| `search_radius` | 数字 | 96.0 | 1..4096 | 格 | 基础索敌半径；飞行载具倍率和雷达范围会进一步放大 |
| `scan_interval_tick` | 整数 | 10 | 1..1200 | tick | 普通索敌扫描间隔 |
| `engagement_net_cooldown_tick` | 整数 | 100 | 0..1200 | tick | 同阵营 Gunner 对弹药目标的组网降权窗口；0 关闭该降权 |

### 6.3 `rvp:driver_supply`

司机 AI 首次补满并持续维护载具弹药补给。不是驾驶 AI 时提交清理。缺省优先级：100。

`config` 必须为空对象 `{}`。

### 6.4 `rvp:weapon_countermeasure`

使用载具武器站内的本体式反制武器拦截危险弹药。只有动作层接受发射后才进入冷却。缺省优先级：800。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `range` | 数字 | 36.0 | 0..4096 | 格 | 危险弹药搜索半径 |
| `cooldown_tick` | 整数 | 80 | 0..72000 | tick | 一次已接受发射后的行为冷却 |

### 6.5 `rvp:rvp_countermeasure`

根据导弹制导类型和雷达锁定自动选择 Flare 或 Chaff。需要 Gunner 处于驾驶位，且载具反制配置已启用。只有真实提交成功才记录冷却。缺省优先级：850。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `scan_interval_tick` | 整数 | 5 | 1..1200 | tick | 威胁扫描间隔 |
| `cooldown_tick` | 整数 | 100 | 0..72000 | tick | 同类反制的行为级释放冷却 |
| `missile_threat_range` | 数字 | 256.0 | 1..4096 | 格 | 锁定本车的 RVP/本体导弹威胁扫描半径 |
| `radar_lock_threat_range` | 数字 | 1024.0 | 1..4096 | 格 | 已锁定本车的雷达源扫描半径 |

### 6.6 `rvp:active_ecm`

当 RWR 报告雷达锁定/导弹发射，或半径内出现敌对危险弹药时，尝试触发存活的主动 ECM 模块。最终冷却由 ECM 服务端管理器判定。缺省优先级：850。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `threat_range` | 数字 | 200.0 | 1..4096 | 格 | 弹药威胁触发半径的下限；实际值还会取所有存活 ECM 设备的弹药/载具干扰半径最大值 |

### 6.7 `rvp:smoke_evasion`

地面司机 AI 检测红外导弹、敌对导弹和激光照射威胁，触发 Smoke，然后停留或驶入附近烟幕。只适用于地面载具驾驶位。缺省优先级：820。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `scan_interval_tick` | 整数 | 10 | 1..1200 | tick | Smoke 威胁扫描间隔 |
| `hold_tick` | 整数 | 260 | 1..72000 | tick | 触发后驶入/驻留烟幕的总时间 |
| `look_radius` | 数字 | 48.0 | 1..4096 | 格 | 可用烟幕云搜索半径 |

Smoke 的威胁触发范围当前不全部开放为 JSON：红外锁定导弹检测半径为 200 格，近距离敌对导弹告警半径为 100 格，激光点命中判定为载具包围盒向外 8 格，来袭角阈值为 60°。`look_radius` 只控制触发后去哪里找烟幕云。

### 6.8 `rvp:ownship_radar`

维持本车雷达开机、探测和锁定。缺省优先级：500。`config` 必须为 `{}`。

### 6.9 `rvp:external_radar`

维持外置雷达中继的部署、请求、搜索接触和外置锁定。缺省优先级：510。`config` 必须为 `{}`。

### 6.10 `rvp:guided_weapon_support`

维持 GPS、激光照射和 HITL 等在途武器控制源。缺省优先级：520。`config` 必须为 `{}`。

### 6.11 `rvp:sead_revenge`

固定翼/旋翼驾驶 AI 的 SEAD 复仇状态机：被敌方雷达锁定 → 反制 → 飞离 → 回旋 → 用 AntiRadiation 武器锁定发射 → 冷却。需要驾驶位、航空载具和可用 AntiRadiation 武器。缺省优先级：950。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `threat_scan_interval_tick` | 整数 | 10 | 1..1200 | tick | 非复仇状态下的雷达锁定源扫描间隔 |
| `radar_lock_range` | 数字 | 1024.0 | 1..4096 | 格 | 搜索锁定本机的敌方雷达载具半径 |
| `fly_away_tick` | 整数 | 100 | 1..72000 | tick | 触发后首段飞离时间 |
| `reversal_tick` | 整数 | 160 | 1..72000 | tick | 回旋阶段上限；门控成功可提前进入发射 |
| `lock_fire_tick` | 整数 | 40 | 1..72000 | tick | 锁定发射阶段上限 |
| `timeout_tick` | 整数 | 400 | 1..72000 | tick | 一轮复仇的总超时保险 |
| `cooldown_tick` | 整数 | 400 | 0..72000 | tick | 复仇退出后的再次触发冷却 |

### 6.12 `rvp:fixed_wing_combat_flight`

固定翼攻击、脱离、巡航、回航与高度保持。只适用于固定翼驾驶 AI。SEAD 复仇活动期间暂停自身阶段推进。非攻击阶段会占用 FIRE 通道阻止常规开火。缺省优先级：500。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `cruise_altitude_min` | 数字 | 150.0 | 0..4096 | AGL 格 | 巡航高度下限 |
| `cruise_altitude_max` | 数字 | 500.0 | 0..4096 | AGL 格 | 巡航高度上限，不得小于下限 |
| `combat_radius_min` | 数字 | 40.0 | 0..4096 | 格 | 相对 home 位置的作战半径下限 |
| `combat_radius_max` | 数字 | 550.0 | 0..4096 | 格 | 作战半径上限，不得小于下限 |
| `attack_phase_tick` | 整数 | 200 | 1..72000 | tick | 攻击阶段基础时长；运行时会结合固定翼节奏缩放与夹取 |
| `disengage_phase_tick` | 整数 | 200 | 1..72000 | tick | 脱离阶段基础时长；运行时会缩放与夹取 |
| `initial_disengage_tick_min` | 整数 | 300 | 0..72000 | tick | 初始脱离随机时长下限 |
| `initial_disengage_tick_max` | 整数 | 400 | 0..72000 | tick | 初始脱离随机时长上限，不得小于下限 |

固定翼的 JSON 时长是“基础值”而非最终直读 tick：初始脱离乘 0.45 后夹在 40..180，后续脱离乘 0.55 后夹在 40..140，攻击阶段乘 1.4 后夹在 140..420。固定翼进入攻击阶段的当前硬门为 AGL 至少 175 格，该值尚未开放到 JSON。

### 6.13 `rvp:rotary_wing_combat_flight`

旋翼起飞保护、攻击/脱离、悬停与高度保持。只适用于旋翼驾驶 AI。SEAD 活动期间暂停自身阶段推进，非攻击阶段占用 FIRE 通道。缺省优先级：500。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `cruise_altitude_min` | 数字 | 28.0 | 0..4096 | AGL 格 | 旋翼巡航高度下限，同时影响起飞/低空保护阈值 |
| `cruise_altitude_max` | 数字 | 60.0 | 0..4096 | AGL 格 | 巡航高度上限，不得小于下限 |
| `attack_phase_tick` | 整数 | 200 | 1..72000 | tick | 攻击阶段基础时长；运行时会结合旋翼节奏缩放与夹取 |
| `disengage_phase_tick` | 整数 | 200 | 1..72000 | tick | 脱离阶段基础时长；运行时会缩放与夹取 |
| `initial_disengage_tick_min` | 整数 | 300 | 0..72000 | tick | 初始脱离随机时长下限 |
| `initial_disengage_tick_max` | 整数 | 400 | 0..72000 | tick | 初始脱离随机时长上限，不得小于下限 |

旋翼的基础时长也会缩放：初始脱离乘 0.2 后夹在 20..90，后续脱离乘 0.35 后夹在 40..120，攻击阶段乘 1.15 后夹在 120..320。

### 6.14 `rvp:launcher_positioning`

发射架载具有弹时停车、装填期走位。只适用于驾驶位且具有发射架能力的载具。缺省优先级：500。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `stop_distance` | 数字 | 12.0 | 0..1024 | 格 | 对载具目标的停车距离 |

### 6.15 `rvp:stuck_recovery`

地面驾驶 AI 的位移检测与倒车脱困。只适用于地面载具驾驶位。缺省优先级：800，通常应高于常规接敌和巡逻移动。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `check_interval_tick` | 整数 | 20 | 5..1200 | tick | 位移采样间隔 |
| `stuck_distance` | 数字 | 1.0 | 0.05..128 | 格 | 有目标且采样期内位移不超过此值时判定卡住 |
| `recovery_tick` | 整数 | 20 | 5..1200 | tick | 倒车脱困持续时间；脱困后还会按实现进入额外冷却 |

### 6.16 `rvp:ground_engagement_move`

非发射架地面载具的有目标接近、停车观察和随机侧移。只适用于地面载具驾驶位，且有权威目标时才提交移动。缺省优先级：500。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `stop_distance` | 数字 | 12.0 | 0..1024 | 格 | 对载具目标停车的距离 |
| `hold_tick` | 整数 | 100 | 0..72000 | tick | 停车观察时长 |
| `evade_tick_min` | 整数 | 140 | 0..72000 | tick | 侧移持续时间下限 |
| `evade_tick_max` | 整数 | 280 | 0..72000 | tick | 侧移持续时间上限，不得小于下限 |
| `evade_yaw_deg` | 数字 | 55.0 | 0..180 | 度 | 侧移方向相对目标方向的随机左/右偏航角 |

### 6.17 `rvp:ground_patrol`

非发射架地面载具在无目标时的漫游和周期性大转弯。缺省优先级：200，应低于 Smoke、脱困和接敌移动。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `big_turn_interval_tick_min` | 整数 | 300 | 1..72000 | tick | 大转弯随机间隔下限 |
| `big_turn_interval_tick_max` | 整数 | 600 | 1..72000 | tick | 大转弯随机间隔上限，不得小于下限 |
| `big_turn_angle_deg_min` | 数字 | 120.0 | 0..360 | 度 | 大转弯随机角度下限 |
| `big_turn_angle_deg_max` | 数字 | 180.0 | 0..360 | 度 | 大转弯随机角度上限，不得小于下限 |
| `big_turn_duration_tick` | 整数 | 40 | 1..1200 | tick | 一次大转弯持续时间 |

### 6.18 `rvp:weapon_engagement`

对当前 TARGET 通道胜者执行武器选择、瞄准、锁定准备、制导准备、发射与冷却记账。需要当前座位拥有武器站。普通交战、CIWS 和 SEAD 共用 FIRE 仲裁通道，避免同 tick 多次常规开火。缺省优先级：500。

| 字段 | 类型 | 缺省 | 范围 | 单位 | 说明 |
| --- | --- | ---: | ---: | --- | --- |
| `fire_window_deg` | 数字 | 6.0 | 0.1..180 | 度 | 非 RVP 制导武器的水平/俯仰瞄准误差窗；RVP 制导武器和垂发不走此门 |
| `lead_scale` | 数字 | 1.0 | 0..10 | — | 瞄准提前量倍率；实现在基础预测点上追加 `max(0, lead_scale-1)` 倍目标速度 |
| `burst_fire_tick` | 整数 | 6 | 0..1200 | tick | 一轮 burst 的开火窗长度；垂发会强制单次记账 |
| `burst_rest_tick` | 整数 | 10 | 0..1200 | tick | burst 轮次之间的休息时间 |
| `guided_weapon_cooldown_tick` | 整数 | 100 | 0..72000 | tick | RVP 制导武器成功进入发射链后的 Gunner 统一发射冷却 |
| `ciws_target_cooldown_tick` | 整数 | 继承或 100 | 0..72000 | tick | 对 CIWS 自导武器目标的发射后冷却；未写时继承第一个 `ciws_targeting.target_cooldown_tick` |

`lead_scale` 的当前公式是“基础距离预测点 + `max(0, lead_scale - 1)` 倍目标速度”。因此 `0..1` 之间的任意值都不会减少基础预测量；只有大于 1 才会增加额外提前量。

`weapon_engagement` 还会继承第一个 `primary_targeting` 的 `gps_prefer_farthest` 和 `engagement_net_cooldown_tick`，使目标选择与发射记账使用同一策略。如果对同一类型配置多个实例，跨行为继承只取 JSON 顺序中第一个实例；建议显式写出 `weapon_engagement` 的两个冷却字段，避免歧义。

---

## 7. 推荐组合范本

### 7.1 固定炮手

固定炮手不应包含任何移动行为。建议以当前 `static_gunner.json` 为范本：

```json
{
  "schema_version": 2,
  "name": "static_gunner",
  "faction": "friendly",
  "behaviors": [
    {"id":"incoming_ammo","type":"rvp:ciws_targeting","priority":850,"config":{}},
    {"id":"main_target","type":"rvp:primary_targeting","priority":500,
     "config":{"target_types":["vehicle","monster","player"],"search_radius":192.0}},
    {"id":"own_radar","type":"rvp:ownship_radar","priority":500,"config":{}},
    {"id":"external_radar","type":"rvp:external_radar","priority":510,"config":{}},
    {"id":"guidance","type":"rvp:guided_weapon_support","priority":520,"config":{}},
    {"id":"main_combat","type":"rvp:weapon_engagement","priority":500,"config":{}}
  ]
}
```

### 7.2 静态防空炮手

使用 `static_anti_air.json`。Profile 不含任何移动行为；`primary_targeting` 只配置：

```json
"target_types": ["rvp:missile", "vehicle:aircraft"]
```

`vehicle:aircraft` 只匹配有驾驶员的固定翼或旋翼载具，不要求目标当前正在飞行。`rvp:missile` 沿用当前类型语义，包含可拦截的导弹、炸弹和火箭。CIWS 与普通索敌共用这组弹药类别，CIWS 负责高频来袭弹药目标抢占。

### 7.3 纯 CIWS

仅保留：

```text
ciws_targeting + ownship_radar + weapon_engagement
```

不要加 `primary_targeting`，否则它也会选普通目标。

### 7.4 地面突击车

建议最小移动组合：

```text
primary_targeting
stuck_recovery
ground_engagement_move
ground_patrol
weapon_engagement
```

按载具能力再加 `driver_supply`、`weapon_countermeasure`、`rvp_countermeasure`、`active_ecm`、`smoke_evasion`、雷达和制导支持。

### 7.5 固定翼/SEAD 驾驶员

建议至少包含：

```text
primary_targeting
ownship_radar
guided_weapon_support
sead_revenge
fixed_wing_combat_flight
weapon_engagement
```

`sead_revenge` 不会把普通武器自动变成 AntiRadiation；当前武器站必须实际存在类型化 AntiRadiation 武器且发射门控成功。

### 7.6 旋翼驾驶员

把固定翼范本中的 `fixed_wing_combat_flight` 换成 `rotary_wing_combat_flight`。如果 Profile 需要同时通用于固定翼和旋翼，可同时放入两种行为，运行时只有匹配载具类型的一个会生效。

---

## 8. 常见调参目标

| 目标 | 建议修改 | 风险 |
| --- | --- | --- |
| 降低服务端索敌频率 | 调大 `primary_targeting.scan_interval_tick`、CIWS/Smoke/RVP 反制的各自扫描间隔 | 反应速度变慢；CIWS 对高速弹药尤其敏感 |
| 扩大索敌距离 | 调大 `search_radius` | 飞行载具还会乘 6；雷达也可覆盖该值；不代表武器能打到 |
| 减少机炮乱射 | 减小 `fire_window_deg`，增大 `burst_rest_tick` | 炮塔转速不足时可能长时间不开火 |
| 增加连射时间 | 调大 `burst_fire_tick` | 更快耗弹；如 `enemy` 当前已是 40/8 |
| 提高移动目标提前量 | 调大 `lead_scale` | 当前只对基础预测点追加速度项；过大会显著超前 |
| 降低导弹发射频率 | 调大 `guided_weapon_cooldown_tick` | 不改变武器自身冷却；两者中任一未满足都不能再发 |
| 让地面车更靠近 | 减小 `ground_engagement_move.stop_distance` | 只对载具目标的停车距离有效 |
| 更积极脱困 | 减小 `stuck_distance`或增大 `recovery_tick` | 阈值太小可能把正常低速移动判为卡住 |
| 加强反制 | 增大威胁半径、减小扫描间隔或冷却 | 无设备、模块损毁、无弹药或动作层拒绝时，仅改 Profile 也不会成功释放 |
| 让 SEAD 更快复仇 | 减小飞离/回旋时间或扫描间隔 | 可能来不及脱离威胁、转向或完成 AntiRadiation 发射门控 |

---

## 9. 加载失败与排障

### 9.1 严格校验项

下列情况会拒绝整批 Profile 发布：

- `schema_version` 不是整数 2；
- 顶层、行为项或 `config` 存在未知字段；
- `id` 非法或在同一 Profile 内重复；
- `type` 未注册；
- 字段类型错误、数值越界；
- `min` 大于对应 `max`；
- 整批资源中缺少 `ywzj_rvp:default`。

错误日志会包含 Profile ID、`behaviors` 数组下标和字段路径，例如：

```text
ywzj_rvp:example.behaviors[2].config.cooldown_tick: 必须在 0..72000 范围内
```

### 9.2 常见的“加载成功但不动作”

- Profile 没有对应行为：有 `weapon_engagement` 但没有任何 TARGET 行为时，不会自动选普通目标。
- 行为能力不匹配：如旋翼行为挂在固定翼上，会静默跳过。
- Gunner 不在驾驶位：移动、司机补给、SEAD 驾驶等不生效。
- `target_types` 拼写错误或与场景不匹配。
- 目标被 Team、所有者、创造模式或 RCS 感知门过滤。
- 没有适用武器，或武器无弹、装填中、冷却中、射程/离轴/锁定门控失败。
- 反制、ECM、雷达或制导行为已启用，但载具没有对应模块或模块已损毁。

### 9.3 调试建议

1. 先用 `ciws_only` 或 `static_gunner` 验证座位、武器站和 Profile ID。
2. 确认服务端日志已输出“已原子加载 N 个 Gunner schema v2 Profile”。
3. 用 `/rvpdebug flags gunner on` 查看 Gunner 性能/扫描统计。
4. 结合 Gunner 锁定诊断查看 `PROFILE_TYPE`、`ALLIED`、`CREATIVE_PLAYER`、`WEAPON_UNUSABLE` 等首个拒绝原因。
5. 热重载后如任一 Profile 失败，先修正日志中的精确路径；不要假设其他已通过文件被部分发布。

---

## 10. 发布与副本同步

仓库内权威源为：

```text
limitless_vehicle/rvp/data/rvp/gunner/
```

`run/client_1`、`run/client_2`、`run/server` 下的载具包是运行副本，不会由 Gunner 加载器自动覆盖。更改权威源后，需按当前项目发布流程手动同步到实际客户端/服务端载具包。

请不要把旧平铺 Profile 与 schema v2 Profile 混用。只要任一旧文件仍在同一加载集合内，当次 reload 就会整体失败并保留上一代快照。

---

## 11. 相关文档与代码入口

- 简版 schema 速查：`docs/RVP_gunner/RVP_Gunner_Profile_schema_v2_20260927.md`
- 渐进式重构方案：`docs/RVP_gunner/RVP_Gunner行为组合渐进式重构实施方案_20260914.md`
- 当前重构交接：`docs/RVP_gunner/RVP_Gunner重构进度交接_20260914.md`
- 严格编译器：`RVP_GunnerProfileCompiler`
- 行为注册表：`RVP_GunnerBehaviorRegistry`
- 运行时计划与仲裁：`RVP_GunnerBehaviorManager`、`RVP_GunnerIntentArbiter`
- 内建行为：`RVP_BuiltinGunnerBehaviors`
- 目标过滤：`GunnerTargeting`
- 离线迁移脚本：`scripts/migrate_gunner_profiles_v2.py`
