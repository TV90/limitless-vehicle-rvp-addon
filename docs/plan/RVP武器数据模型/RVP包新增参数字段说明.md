# RVP 包新增参数字段说明

本文档面向扩展包开发者，说明 RVP 新武器系统的全部 JSON / toml 可配置项，并按模块组织：

- **武器配置**：`data/rvp/weapons/<id>.json`
- **武器显示配置**：`assets/rvp/display/weapon/<id>.json`（模型/贴图，格式沿用本体武器显示）
- **载具显示配置**：`assets/<namespace>/display/vehicle/<id>.json`（RVP 独有扩展：LOD 模型、隐藏骨骼、透明模式等，见 §2.11）
- **载具配置**：`data/<namespace>/vehicles/<id>.json`（顶层扩展 + `parts[]` 内部件扩展）
- **Gunner 配置**：`data/<namespace>/gunner_profiles/<id>.json`
- **UI 预设**：`data/<namespace>/ui_presets/<name>.json`
- **Forge 配置**：`config/ywzj_rvp-server.toml` / `ywzj_rvp-common.toml` / `ywzj_rvp-client.toml`

JSON 文件本身不能写注释，字段解释以本文档和 `org.ywzj.rvp.weapon.data.RVP_*Data` 类中的中文注释为准。

**延伸阅读（更易读的分主题文档）**

| 文档 | 内容 |
| --- | --- |
| [README.md](./README.md) | 文档索引、路径约定、代码入口 |
| [弹体运动学开发与测试.md](./弹体运动学开发与测试.md) | `projectile_data` 三条弹道分支、本体对照、调试 |
| [RVP伤害倍率与爆炸.md](./RVP伤害倍率与爆炸.md) | `direct_damage_factor`、爆炸伤害 |
| [子母弹系统与Mi28边界测试.md](./子母弹系统与Mi28边界测试.md) | `submunition_data` 链式战斗部 |

运行时路径约定：

- 开发载具包：`limitless-vehicle-rvp-addon/run/client_1/limitless_vehicle/rvp/`
- 运行时载具包目录：`.minecraft/limitless_vehicle/rvp/`
- 武器 JSON：`data/rvp/weapons/<id>.json`（资源 ID 为 `rvp:<id>`）
- 显示配置：`assets/rvp/display/weapon/<id>.json`

---

## 0. 通用写法约定

- **`*_data` 分组**：弹道、引信、制导、落点/爆炸等一律写在 `*_data` 分组字段内（`fire_data` / `projectile_data` / `fuse_data` / `collision_data` / `effects_data` / `detonate_data` / `submunition_data` / `dispenser_data` / `guidance_data` / `misc_data` / `laser_data` / `targeting_pod_data`）。
- **顶层旧键尽量迁移**：`damage` 用 `collision_data.direct_damage` 取代；`velocity` 若写在顶层，加载期会自动补入 `projectile_data`。顶层 `inaccuracy` 仍被读取，作为 `fire_data.spread` 未设置时的回退散布（`fire_data.spread` 优先）。
- **区间写法（`RVP_Range`）**：形如 `"[[0,500]]"` 或 `[[0,500]]`，可含并集（`"[[0,100],[200,300]]"`）；边界可为数字、`null`（开区间/正负无穷）、`inf`。用于 `turning_factor`、`altitude_drag_factor`、`missile_name_on_hud`、`lock_angle_gate`、`guidance_*_range` 等 Map 键。
- **枚举大小写不敏感**：如 `fire_mode: "full_auto"`、`guidance_type: "arh"` 均可解析。

---

## 1. 武器配置（`data/rvp/weapons/<id>.json`）

### 1.1 公开武器类型

新内容应只使用以下 7 个公开类型：

| 类型 | 用途 |
| --- | --- |
| `rvp:missile` | 导弹类弹体。通过 `guidance_data.guidance_type` 组合 TV、ARH、ARM、GPS、IR、SARH、SACLOS、MCLOS、LH/SALH、NONE；TV/ARH/AIR/ARM 等末端自主导引写 `terminal_guidance`。 |
| `rvp:rocket` | 火箭弹类弹体。 |
| `rvp:machinegun` | 机枪、机炮、霰弹、鸭弹等弹丸。 |
| `rvp:bomb` | 重力炸弹、GPS 滑翔弹、集束弹。 |
| `rvp:laser` | 瞬时射线武器。 |
| `rvp:dispenser` | 投放器/布撒器载荷（配置了 `dispenser_data.item` 时仅布撒、不走路径爆炸链）。 |
| `rvp:targetingpod` | 目标指示吊舱，用于写入 GPS/SACLOS 目标点。 |

旧公开类型 `ywzj_rvp:gps_bomb`、`ywzj_rvp:tv_missile`、`ywzj_rvp:anti_radiation_missile`、`ywzj_rvp:active_radar_missile`、`ywzj_rvp:semi_active_radar_missile`、`ywzj_rvp:manual_guidance_missile` 已不再作为配置入口，写法见「7. 旧写法迁移对照」。

### 1.2 基本结构

```json
{
  "type": "rvp:missile",
  "name": "Example",
  "shoot_interval": 500,
  "reload": { "time": 80, "ammo": "ywzj_vehicle:ammo_missile" },
  "fire_data": { "spread": 0 },
  "projectile_data": { "velocity": 2.5 },
  "misc_data": {},
  "fuse_data": {},
  "collision_data": { "direct_damage": 80 },
  "effects_data": {},
  "detonate_data": { "explosion_data": {} },
  "submunition_data": {},
  "guidance_data": { "guidance_type": "NONE" }
}
```

### 1.3 顶层 RVP 字段（武器级）

| 字段 | 说明 |
| --- | --- |
| `show_msl_indicator` | 是否在 HUD 中显示导弹指示器（菱形框 + 距离）；默认 `false`。 |
| `tactical_map_icon` | 战术地图上该武器弹药显示的自定义图标名（如 `rvp:textures/...` 或短名）；空字符串表示使用默认。 |
| `sub_type` | 可选子类型标记（如 `incendiary`），仅配置可读性；**落点逻辑请用 `detonate_data`**。 |
| `rvp_fire_control_sensor_mode` | 武器自身的火控传感器模式标记（字符串）。可选值：空（默认，不覆盖）/ `eo_ccip`（强制按电光传感器做 CCIP 弹道求解，常用于对地机炮）。 |
| `fire_control_sensor_type_override` | 可选，按当前武器覆盖所属 `WeaponUnit` 的火控传感器类型。枚举值与本体 `WeaponUnitData.FireControlSensorType` 一致：`none` / `ir` / `rf` / `eo` / `loc` / `ccip`。适合“同一武器站切不同武器时，火控传感器模式也随武器变化”的场景。 |
| `crosshair_style_override` | 可选，按当前武器覆盖所属 `WeaponUnit` 的 HUD 准星样式（与部件级 `crosshair_style` 同枚举）。枚举值与本体 `WeaponUnitData.CrosshairStyle` 一致：`none`（隐藏准星）/ `circle` / `square` / `cross` / `big_cross`。适合“同一武器站内不同挂架武器需要不同准星”的场景；未配置沿用武器站 `crosshair_style`。仅影响 HUD 准星，不影响开镜（scope）分划。 |
| `seeker_color` | 可选，导引头圈 HUD 颜色覆盖（RGB 十六进制字符串，如 `"0x30FF30"` / `"#FFAA00"`）。配置后该武器被选中时导引头圈用此颜色绘制，锁定时统一红色指示；未配置走本体机型基色（直升机绿 / 固定翼白）。仅客户端渲染消费。 |
| `parent_weapon_unit_aim_override` | 可选布尔，覆盖所属武器站的 `parent_weapon_unit_aim`：`true`=弹着点预测与准心锚定到母武器站，`false`=使用自身挂架位置；未写=继承站级静态配置。仅客户端消费（弹着点预测与准心显示锚定方向）。 |
| `lock_tone_sound` | 可选，本武器锁定敌人时播放的导引头锁定音（`SoundEvent` 资源位置字符串，如 `"ywzj_rvp:ir_track_alarm"` / `"ywzj_vehicle:missile_launch"`）。不配置则消费方回退全局默认（`RVP_Sounds.IR_TRACK_ALARM`）。仅客户端消费（`RVP_ClientSeekerTone`）。 |

**破坏性变更（0.5.23+）：** 已删除顶层 `acceleration`、`delay_fuse`、`active_radiation_*`、`tv_missile_*`、`laser_range` 等旧键；爆炸配置在 `detonate_data.explosion_data` 内，不再支持顶层 `explosion` / `explosion_data`。

### 1.4 `fire_data` 开火参数

| 字段 | 说明 |
| --- | --- |
| `require_lock` | 是否要求发射前已有锁定。默认 `true`。GPS、ARM、TV、MCLOS 等通常会手动设为 `false`。 |
| `fire_mode` | 开火模式枚举 `RVP_EnumFireMode`。JSON 须写枚举名，如 `FULL_AUTO`，大小写不敏感；无法识别时默认为 `FULL_AUTO`。 |
| `spread` | 发射角度散布（度）。为空时回退使用武器顶层 `inaccuracy`（经 `RVP_WeaponData#getInaccuracy()` 读取）。 |
| `burst_count` | 点射模式每轮发射数量。默认 `1`。 |
| `burst_delay` | 点射模式轮间间隔（毫秒）。默认 `0`。 |
| `charge_tick` | `CHARGE`/`RAILGUN` 的蓄满时间，或 `MINIGUN` 的转速爬升时间（tick）。默认 `10`。 |
| `charge_decay_tick` | 从满蓄力/满转速衰减回零所需时间（tick）。默认 `2`。 |
| `charge_power_scale` | 按蓄力/转速比例线性放大伤害或初速（`1` = 不放大）。 |
| `heat_count` | 武器级过热：每次成功开火增加的热量。仅当 `max_heat_count > 0` 时生效，默认 `0`。 |
| `max_heat_count` | 武器级过热上限。大于 `0` 时启用过热；当前热量达到或超过该值后禁止继续开火，直到冷却到上限以下。默认 `0`，表示关闭武器级过热。 |
| `overheat_extra_heat` | 武器级过热惩罚热量。开火后达到或超过 `max_heat_count` 时额外追加，用于模拟过热锁死后需要更久冷却。默认 `30`。 |
| `max_off_axis_shoot_angle` | 最大离轴发射角（度）。为空时保持旧版“任意角度均可发射”的行为。 |
| `canister_count` | 单次开火的子弹丸数量。`<= 0` 时按 `1` 处理。 |
| `canister_type` | 多弹丸散布类型：`0` 位置散布，`1` 角度散布，`2` 角度散布并沿弹道前向错位以模拟时间散布。 |
| `canister_distribution` | 多弹丸分布方式：`uniform`（默认）/ `normal`（中心聚集）/ `cluster_center`（强中心聚集）/ `cluster_edge`（外缘优先）/ `ring`（环带）。 |
| `canister_shape` | 多弹丸散布形状：仅 `circle`（默认）/ `square` 有效。 |
| `canister_diff` | 多弹丸散布半径/角度强度。默认 `0.3`。 |
| `canister_burst_delay_time` | 子弹丸分批抛撒的延时 tick。默认 `0`。 |
| `canister_burst_count` | 子弹丸分几批抛撒。默认 `1`。 |

#### `fire_mode` 开火模式

| 值 | 行为 |
| --- | --- |
| `FULL_AUTO` | 按住开火键，按 `shoot_interval` 连射（机炮/导弹默认）。 |
| `SEMI_AUTO` | 每次按下开火键发射一轮；按住不连射。 |
| `BURST` | 按下后锁定连射；每轮点射发射 `burst_count` 发（间隔仍受 `shoot_interval` 约束），轮与轮之间间隔 `burst_delay`（毫秒）；松开停止。 |
| `CHARGE` | 长按蓄力，蓄满自动发射并立即重新蓄力，无需松开鼠标。 |
| `MINIGUN` | 长按提升转速，足够转速后连射；松开转速缓慢下降，不必每次满转速。 |
| `RAILGUN` | 单击开始蓄力，蓄力中再按无效；蓄满自动发射且不可打断。 |

蓄力激光示例：

```json
"fire_data": {
  "fire_mode": "CHARGE",
  "charge_tick": 10,
  "charge_decay_tick": 2,
  "charge_power_scale": 2.0
}
```

多弹丸散布示例：

```json
"fire_data": {
  "fire_mode": "FULL_AUTO",
  "canister_count": 12,
  "canister_type": 1,
  "canister_diff": 1.5,
  "canister_burst_delay_time": 0
}
```

### 1.5 `projectile_data` 弹体运动学

运行时按武器 `type` 与 `has_rocket_engine` 进入**一条**弹道分支（详见 [弹体运动学开发与测试.md §2](./弹体运动学开发与测试.md#2-运行时走哪条弹道)）：

| 分支 | 条件 | 本体参考 |
| --- | --- | --- |
| 机炮积分 | `rvp:machinegun` | `BulletEntity` |
| 推力积分 | `has_rocket_engine` 且 mass/thrust/燃烧时间有效 | `MissileEntity#tickMove` |
| 简化弹道 | 其余 | gravity + drag（+ 可选 constant_speed） |

#### 字段一览

| 字段 | 说明 |
| --- | --- |
| `velocity` | 弹体初速/飞行速度（覆盖武器顶层 `velocity`）；为空时使用顶层 `velocity`。 |
| `gravity` | 空中每 tick 垂直加速度，负数向下。 |
| `gravity_in_water` | 水中每 tick 垂直加速度。 |
| `drag` | 空中水平阻力（MCH `DragInAir`）：每 tick 从 `motionX`/`motionZ` 减去 `(分量/|v|)*drag`，不改 `motionY`。 |
| `drag_in_water` | 水中水平阻力，公式同 `drag`（MCH 水中默认无 `DragInAir`；RVP 用本字段可选开启）。 |
| `inherit_vehicle_velocity` | 发射时是否继承载具当前速度。 |
| `constant_speed` | 是否保持恒定速度，仅改变方向。适合导弹、火箭。 |
| `rotate_to_motion` | 是否让实体朝向跟随运动方向。默认 `true`。 |
| `max_speed` | 最大速度限制，0 表示不限制。 |
| `min_speed` | 最小速度限制，0 表示不限制。 |
| `turning_factor` | 旧版 MCHR 风格方向插值参数表。类型为 `Map<RVP_Range<Integer>, Float>`，key 为飞行 Tick 区间，value 为 0～1 的转向因子；仅未配置 `rvp_maxg` 时约束实体与虚拟制导，区间未命中时使用 0.5。 |
| `rvp_maxg` | 可选 RVP 最大法向过载，单位 G，默认不配置。显式配置后实体与虚拟制导均使用 `applySteering`，并覆盖同时存在的 `turning_factor`；负数和非有限值按 0 G 安全处理，0 表示不允许转向。 |
| `has_rocket_engine` | 是否装备火箭发动机，默认 `false`。为 `false` 时不启用推力运动学。 |
| `mass` | 弹体质量（与 `thrust` 共同决定加速度）；仅在 `has_rocket_engine` 为 true 时生效。 |
| `thrust` | 发动机推力。 |
| `motor_burn_time` | 发动机燃烧时间（tick）。 |
| `second_pulse` | 是否启用双脉冲推进（第二段推力）。 |
| `second_pulse_trigger_speed` | 第二段触发：导弹速度 ≤ 阈值时满足（0 表示不按速度触发）。 |
| `second_pulse_trigger_distance` | 第二段触发：距离锁定目标 ≤ 阈值时满足（0 表示不按距离触发；仅在存在锁定目标实体或锁定坐标时可判定）。 |
| `second_pulse_thrust` | 第二段推力（与 `mass` 决定加速度）。 |
| `second_pulse_burn_time` | 第二段燃烧时间（tick）。 |
| `ignition_delay_tick` | 点火延迟；延迟内继承载具弹射速度（与本体弹仓弹射一致）。 |
| `drag_coefficient` | 速度平方阻力系数。仅火箭发动机分支读取。 |
| `altitude_drag_factor` | 高空空气阻力倍率表。类型为 `Map<RVP_Range<Float>, Float>`，key 为 **世界 Y 坐标区间**，value 为水平阻力倍率；未命中区间或 value 非法时按 `1.0` 处理。 |
| `wind_data` | `RVP_WindData` 嵌套对象，默认创建一份禁用配置；JSON 为 `null` 时读取端同样回退为禁用对象。当前只用于 RVP 子弹药的服务器权威风漂，字段见下表。 |
| `deployment_horizontal_half_life_ticks` | 子弹药部署水平速度半衰期，单位 Tick，默认 `0`。正有限值启用分量化弹道；非正或非有限值按 0。仅由 `RVP_SubmunitionSpawner` 显式初始化的速度散布/父弹继承 X/Z 生效，包含分层圆锥径向与云心水平径向分量；Y、风偏和显式附加速度不参与该衰减。 |
| `deployment_vertical_half_life_ticks` | 子弹药部署纵向速度半衰期，单位 Tick，默认 `0`。正有限值启用分量化弹道；非正或非有限值按 0。仅由 `RVP_SubmunitionSpawner` 显式初始化的速度散布 Y 生效；重力、`payloads_velocity[1]`、风偏和其他外力不参与该衰减。 |

`altitude_drag_factor` 的运行规则：

- 为空或未命中任何区间时，回退倍率 `1.0`。
- `y` 采样的是**世界坐标**，不是离地高度。
- 推力弹道会把倍率乘到 `drag_coefficient`；简化弹道会把倍率乘到 `drag`。

子弹药分量化部署的速度关系为：

```text
deploymentXZ(t) = deploymentXZ(0) × 2^(-t / halfLifeTicks)
deploymentY(t) = deploymentY(0) × 2^(-t / verticalHalfLifeTicks)
totalVelocity = baseVelocity + deploymentXZ(t) + deploymentY(t) + windContribution
```

`deploymentXZ(0)` 由散布速度 X/Z 与可选母弹水平继承组成，`deploymentY(0)` 仅由散布速度 Y 组成；`gravity` 与 `payloads_velocity` 属于基础速度。碰撞、穿透等外部改速会通过上一合成速度差并入基础速度。`max_speed`/`min_speed` 钳制时四个分量同比缩放，保持下一 Tick 重组连续。基础 `drag` 只处理基础速度中的水平分量，不阻尼部署 X/Z、散布 Y 或独立风偏。

示例：

```json
"projectile_data": {
  "velocity": 3.2,
  "has_rocket_engine": true,
  "mass": 84,
  "thrust": 7.5,
  "motor_burn_time": 90,
  "second_pulse": true,
  "second_pulse_trigger_distance": 120,
  "second_pulse_thrust": 5.2,
  "second_pulse_burn_time": 24,
  "altitude_drag_factor": {
    "[[-64,300]]": 0.98,
    "[[300,500]]": 1.0,
    "[[500,1000]]": 1.02,
    "[[1000,inf]]": 1.05
  }
}
```

#### `wind_data` 弹体风漂

`parent_facing_reverse` 只在 RVP 子弹药生成时捕获风向：以**释放 Tick 母弹当前旋转朝向的反向**为基准并固化到子体，之后不会继续跟随母弹转向。`north:<角度>` 则在任意 RVP 弹体初始化时固化世界水平风向，因此首发弹体和子弹药均可使用。

默认未启用任何部署半衰期时，风漂保持既有总速度收敛语义。配置任一正数部署半衰期后，服务器改为维护独立风偏贡献：基础弹道、部署 X/Z、散布 Y 与风偏分别积分，风偏从零向目标速度收敛后相加，不会把初始散布速度直接拉向风速。客户端只显示同步后的权威弹道，不自行计算。

| 字段 | 类型 | 缺省值 | 归一化与生效条件 |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | 总开关；只有本字段为 `true`、方向模式有效、归一化后 `speed > 0` 且 `response > 0` 时，`RVP_WindData.isEnabled()` 才返回 true。 |
| `direction_mode` | string | `parent_facing_reverse` | 接受 `parent_facing_reverse`（忽略大小写），或 `north:<有限角度>` 固定世界水平风向。固定角以北方 `-Z` 为 0°、顺时针为正：`north:+90` 为东 `+X`，`north:-90` 为西 `-X`，±180 为南 `+Z`；角度可超出 ±360 并按周期等价。未知前缀、缺失角度、NaN/Infinity 均令风漂禁用，不迁移、不回退。 |
| `speed` | float（格/tick） | `0.1` | 目标风速；有限值取 `max(value, 0)`，NaN/Infinity 按 `0`。它是速度收敛目标，不是每 Tick 直接追加的加速度。 |
| `response` | float | `0.03` | 每 Tick 向目标风速收敛的比例，有限值钳制到 `0..1`，NaN/Infinity 按 `0`；`0` 禁用风漂，`1` 表示单 Tick 直接收敛。 |
| `vertical_factor` | float | `0` | `parent_facing_reverse` 捕获风向时保留母弹反向朝向 Y 分量的比例，有限值钳制到 `0..1`，NaN/Infinity 按 `0`。固定 `north:<角度>` 永远是水平风向，不使用本字段。 |
| `turbulence` | float（格/tick） | `0` | 每 Tick 追加到 X/Z 速度的平滑确定性扰动幅度；有限值取 `max(value, 0)`，NaN/Infinity 按 `0`。扰动由实体 UUID 与飞行 Tick 派生，同一实体可复现。 |
| `turbulence_frequency` | float（周期/tick） | `0.02` | 扰动方向的基础旋转频率；有限值钳制到 `0..0.5`，NaN/Infinity 回退 `0.02`，仅 `turbulence > 0` 时生效。实体种子会再施加 `0.85..1.15` 的稳定频率倍率，避免同批子体同步摆动。 |

扰动初始相位与频率倍率均由实体稳定种子派生。默认总速度模式仍在收敛后追加扰动；分量化部署模式则把扰动放入有界风偏目标：

```text
phase = seedPhase + 2π × turbulence_frequency × seedRateScale × flightTick
turbulenceVelocity = turbulence × (cos(phase), 0, sin(phase))
windTarget = capturedWind × speed + turbulenceVelocity
```

固定风向转换公式：

```text
angle = direction_mode 中 north: 后的顺时针角度
fixedWind = normalize(sin(angle), 0, -cos(angle))
```

典型配置：

```json
"projectile_data": {
  "gravity": -0.045,
  "drag": 0.008,
  "wind_data": {
    "enabled": true,
    "direction_mode": "parent_facing_reverse",
    "speed": 0.11,
    "response": 0.035,
    "vertical_factor": 0.0,
    "turbulence": 0.006,
    "turbulence_frequency": 0.02
  }
}
```

固定东方风向只需把模式改为：

```json
"direction_mode": "north:+90"
```

#### 火箭发动机与推进回退

**何时启用推力：** `has_rocket_engine: true`，且解析后 `mass > 0`、`thrust > 0`、`motor_burn_time > 0`。否则打日志并退回简化弹道。

**参数写在哪儿：**

1. 推荐全部写在 `projectile_data`。
2. 若弹体内**未写**某键，加载时 `resolvePropulsionFallback` 从武器 JSON **顶层**补全（字段名与本体 `VehicleMissileWeaponData` 相同）。
3. 弹体内**写了**的键一律以弹体为准（含写 `0` 的情况）。

`has_rocket_engine` 为 **false** 时，不跑推力积分；仍可用 `gravity` / `drag` / `constant_speed` 等简化弹道。

**推力有效时的每 tick 近似：** `Δv += lookDir * (thrust/mass)`（燃烧期内）→ 二次阻力 `-drag_coefficient * |v|²` → 重力（默认 `PhysicsEngine.G`，或 `projectile_data.gravity`）。`constant_speed` 不参与；`max_speed` / `min_speed` 仍可钳制速度。

**加载期归一化：** 顶层或弹体出现 `mass`/`thrust`/`motor_burn_time` 时会自动补 `has_rocket_engine: true`（若未显式配置）。

#### `rvp:machinegun` 与官方机炮弹速

`rvp:machinegun` **不走** 导弹/火箭那套推进逻辑，而是与官方 `ywzj_vehicle:cannon` 的 `BulletEntity` 一致：

- **RVP 包内请只写** `projectile_data.velocity` 作为炮口初速（与官方 `auto_cannon` 顶层 `velocity: 16` 同量级，常用 **16**）。
- 未写时，加载期会从顶层 `velocity` 自动补入 `projectile_data.velocity`；两者都未写时机枪默认 **16**，不做倍率换算。
- 每 tick：`位置 += 速度`；`速度 *= (1 - friction)`；`速度.y -= gravity`。
- `projectile_data.gravity`：取绝对值作为向下重力（与 `BulletEntity.gravity` 同号约定）。
- `projectile_data.drag`：映射为线性摩擦 `friction`（默认 **0.01**）。

`projectile_data.max_speed`、`min_speed`、`constant_speed` 对机枪**不生效**（机枪只用 `velocity` + `gravity` + `drag`）。

### 1.6 `fuse_data` 引信

| 字段 | 说明 |
| --- | --- |
| `delay_tick` | 定时引信：飞行 tick ≥ 该值时引爆；0 表示不启用。 |
| `programmable_airburst` | 可编程空爆（MCH）：按 **R（火控锁定键）** 对**弹道落点**（瞄准镜绿框处，非屏幕中心射线）测距，弹体沿弹道飞行 **测距 + `airburst_offset` 米** 时引爆；未测距或测距无效（≤`airburst_measure_min` 或 ≥`airburst_measure_max`）不触发。 |
| `airburst_offset` | 可编程空爆附加距离（米），默认 **3**（对齐 MCH「测距 + 3m」）。 |
| `airburst_measure_min` / `airburst_measure_max` | 有效测距范围（米），默认 **5** / **300**。 |
| `airburst_explosion_damage` / `airburst_explosion_radius` | 可编程空爆触发的爆炸参数；未写时使用 `detonate_data.explosion_data`。 |
| `proximity_radius` | 近炸引信检测半径（米），0 表示不启用。未写时可读 `detonate_data.explosion_data.proximity_radius`。 |
| `proximity_fuse_tick` | 近炸解保 tick：出生后至少经过该 tick 才启用；**-1** 表示不限制。 |
| `proximity_fuse_height` | 近炸目标最低高度（格，MCH `ProximityFuseHeight`）：目标 `onGround` 或脚下该深度内有实心方块时**不触发**；默认 **20**。 |
| `proximity_fuse_damage` | 近炸对触发目标实体的直接伤害（MCH `ProximityFuseDamage`）；0 表示仅爆炸。 |
| `proximity_fuse_explosion_damage` / `proximity_fuse_explosion_radius` | 近炸引信触发的爆炸参数；未写时使用 `detonate_data.explosion_data`。 |
| `ground_proximity_fuse_distance` | 近地引信高度（格），默认 `0` 表示禁用。沿世界系绝对 `-Y` 检测可碰撞方块并忽略流体；会扫掠本 Tick 完整运动段，使高速弹体在撞地前的准确高度触发。建筑和树叶等有碰撞体的方块同样算地面。 |
| `ground_proximity_fuse_arm_tick` | 近地引信独立解保 tick，默认 `0`；弹体计时达到该值后才进行近地检测，不要求弹体必须下降。 |
| `detonate_on_life_end` | 生命周期结束时是否爆炸；false 时只消失。 |
| `entity_collision_safe_tick` | 实体碰撞安全引信 tick；生效期间忽略实体碰撞与实体近炸，但仍会撞地。未写时 `rvp:missile` 默认 `3`、`rvp:bomb` 默认 `20`，其它弹种默认 `0`。 |

#### AHEAD 自动可编程空爆（`rvp:machinegun` 等）

AHEAD 由引信自动编程：母弹飞行中按“预瞄点 − `ahead_burst_offset_meters`”解出空爆距离，到点后母弹自毁并释放子弹药（开花细节由 `submunition_data` 与子弹药自身 JSON 决定）。**不在**顶层写 `ahead_data`，全部写在 `fuse_data`：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `ahead_enabled` | 是否启用 AHEAD 自动编程逻辑。 | `false` |
| `ahead_burst_offset_meters` | 相对预瞄点提前多少米开花；实际编程公式为 `programmedDistance = leadDistance - ahead_burst_offset_meters`。 | `3` |
| `ahead_require_lock` | 是否要求必须存在锁定目标且能解出预瞄圈；为 `false` 时，无法解出预瞄圈会回退到当前 `AimContext.position` 的瞄点距离。 | `true` |
| `ahead_min_ground_clearance` | 可编程空爆点的最低离地高度（米）；低于该值时取消 AHEAD 空爆，让母弹继续飞行/撞击，避免对地过强。`0` 表示不限制。 | `0` |

#### 攻顶引信（`top_attack_*`）

攻顶分两层，勿混淆：

- **弹道层** `guidance_data.top_attack_height`——决定导弹是否拉起攻顶、攻到多高（见 1.13 `guidance_data` 公用字段表）。
- **起爆层** `fuse_data.top_attack_*`（本小节）——决定**何时引爆**：向弹体正下方探测目标，探测到后起爆。

攻顶引信检测弹体正下方（世界系绝对 `-Y` 轴，**不随弹体姿态变化**）半锥角区域内的实体；命中后触发引信，复用近炸全额伤害与 `on_fuse` 子母弹链路。

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `top_attack_fuse_enabled` | 是否启用攻顶引信。 | `boolean` | `false` |
| `top_attack_fuse_distance` | 从弹体向正下方的最大检测距离（米）。 | `float` | `6` |
| `top_attack_fuse_fov` | 检测半锥角（度）：实体与正下方方向的偏移角上限。 | `float` | `25` |
| `top_attack_fuse_delay_tick` | 探测到目标后延时起爆的 tick 数；`0` = 立即触发。 | `int` | `0` |
| `top_attack_fuse_arm_tick` | 解保 tick：出生后至少经过该 tick 才启用；`0` = 不限制。 | `int` | `0` |
| `top_attack_smart_enabled` | **智能引信**（默认开启）：探测命中后不立即引爆，而是记录检测点（目标 AABB 中心），解除当前制导并改飞向「检测点正上方 ±`top_attack_smart_target_radius` 圆内、高度为触发时刻导弹高度」的目标点，到达后再引爆，缓解 `fov` 圈过大导致的偏爆。 | `boolean` | `true` |
| `top_attack_smart_target_radius` | 智能引信目标点水平随机半径（米）。 | `float` | `0.5` |
| `top_attack_smart_arrive_horizontal` | 智能引信到达判定：水平距离 ≤ 该值（米）**且**垂直高度差 ≤ `top_attack_smart_arrive_vertical` 时引爆。 | `float` | `0.5` |
| `top_attack_smart_arrive_vertical` | 智能引信到达判定：垂直高度差 ≤ 该值（米）。 | `float` | `1.0` |

> `top_attack_smart_*` 仅在 `top_attack_fuse_enabled: true` 时有意义。除两个布尔开关外，其余字段取负值时按 `0` 处理（getter 统一 `Math.max(x, 0)`）。

### 1.7 `collision_data` 直击、衰减与碰撞

| 字段 | 说明 |
| --- | --- |
| `direct_damage` | **直接命中伤害**；未写时使用武器顶层 `damage`。 |
| `direct_damage_factor` | 按目标类别缩放直击、激光与近炸直伤；见 [RVP伤害倍率与爆炸.md](./RVP伤害倍率与爆炸.md)。 |
| `damage_decay` | 伤害衰减规则数组（见下表）；`domain=distance` 相乘，`domain=angle` 分段互斥，再相乘。 |
| `living_penetration` | 可穿透的 **生物**（`LivingEntity`）数量：每穿过一只仍造成一次伤害；`0` 表示命中生物后立即引信/消失。`N` 表示除首次命中外还可再穿透 `N` 只生物（共可伤害 `N+1` 只）。 |
| `wall_penetration` | 飞行途中可 **摧毁并穿过** 的实心方块最大数量（参考本体航空炸弹逐格破块；装饰性方块如树叶/玻璃可穿过且不扣次数）；`0` 表示命中实心方块后立即结算。不可破坏方块（如基岩）会阻挡穿透。 |
| `penetration_damage_multiplier` | 每完成一次穿透后，后续命中伤害的连乘倍率（如 `0.9`：第 1 次命中满伤，穿透 1 次后第 2 次 ×0.9，再穿透 ×0.9²）。默认 `1` 不衰减。 |
| `penetration_speed_multiplier` | 每完成一次穿透后弹速的连乘倍率（如 `0.9` 表示该次穿透后速度变为原来的 0.9）。默认 `1`。 |
| `bounce` | 弹跳次数，0 表示不跳弹。 |
| `bounce_strength` | 每次弹跳后速度保留比例（如 `0.8` = 反射速度 ×0.8）。未写且 `bounce > 0` 时默认 `0.6`。 |
| `bounce_fuse_tick` | 第一次弹跳后多少 tick 自动引信（引爆或消失，取决于 `detonate_data.explosion_data.explode`）；`0` 不启用。 |
| `bounce_incidence_angle` | 入射角阈值（度）：速度方向与撞击面法线夹角 **≥** 该值时才跳弹（如 `50` = 掠射跳弹、近垂直击中不跳）；`0` 表示不限制角度。 |
| `bounce_on_vehicle` | 击中载具（`AbstractVehicle`）时是否跳弹；默认 `false`（仅对方块等地形跳弹）。 |
| `bounce_min_block_hardness` | 方块跳弹硬度下限：仅当方块 `getDestroySpeed` **严格大于** 该值时才跳弹；默认 `2.1`（如石头约 1.5 不跳、铁块约 5 可跳）。不影响载具跳弹。 |

#### `direct_damage_factor` 子字段

| 字段 | 说明 |
| --- | --- |
| `player` | 对玩家倍率，默认 `1` |
| `living` | 对非玩家生物倍率，默认 `1` |
| `vehicle_default` | 对未单独列出的 `AbstractVehicle` 倍率，默认 `1` |
| `vehicles` | 对象：键为实体类型 ID（如 `ywzj_vehicle:rotary_wing_vehicle`），值为倍率 |

#### `damage_decay` 规则

| `domain` | 说明 |
| --- | --- |
| `distance`（默认） | 已飞行距离（米）；多条**相乘**。 |
| `angle` | 入射角（度）；多条**分段互斥**。 |

| `type` | 参数 | 说明 |
| --- | --- | --- |
| `constant` | `start_distance`, `end_distance`, `start_factor` | 区间内固定系数。 |
| `segmented` | `segments`: `[[起点, 系数], ...]` | 取满足 `起点 <= 采样值` 的**最后一段**系数（常用于**距离**）。 |
| `linear` | `start_distance`, `end_distance`, `start_factor`, `end_factor` | 区间内线性插值；距离衰减未写 `start_factor` 时起点为 `1`。 |
| `exponential` / `exp` | `rate`, `min_factor` | `exp(-rate × 距离)`（**距离**）。 |
| `curve` / `polynomial` | `start_distance`, `end_distance`, `start_factor`, `end_factor`, `power` | 在 `[start,end]` 上按 `t^power` 插值（**入射角**常用）。 |

30mm 低速榴弹示例（距离 + 入射角 + 跳弹）：

```json
"collision_data": {
  "direct_damage": 30,
  "damage_decay": [
    { "domain": "distance", "type": "segmented", "segments": [[100, 0.9], [200, 0.8]] },
    { "domain": "angle", "type": "constant", "start_distance": 50, "end_distance": 60, "start_factor": 0.9 },
    { "domain": "angle", "type": "linear", "start_distance": 60, "end_distance": 70, "start_factor": 0.9, "end_factor": 0.8 },
    { "domain": "angle", "type": "curve", "start_distance": 70, "end_distance": 80, "start_factor": 0.8, "end_factor": 0.5, "power": 2 }
  ],
  "bounce": 2,
  "bounce_incidence_angle": 80
}
```

最终系数 = 距离衰减 × 入射角衰减 × 穿透衰减（若有）。爆炸参数写在 `detonate_data.explosion_data`（与 `direct_damage` **无关**）。

### 1.8 `effects_data` 特效

| 字段 | 说明 |
| --- | --- |
| `trajectory_particle` | 飞行轨迹粒子；默认 `minecraft:cloud`，写 `none` 可关闭。 |
| `impact_particle` | 命中粒子。空或 `minecraft:block` = MCH 默认：方块破碎粒子 + 白烟（`CLOUD`）；`none` 关闭。分布与 MCH `spawnBlockPar` 一致（破碎：`flak_particles_*`；白烟：命中点 ±1 格高斯偏移、速度 `gaussian/200`）。激光命中走 `MCH_WeaponLaser#spawnBlockPar`（无破碎，仅 cloud/smoke/flame）。 |
| `explosion_particle` | 爆炸粒子。空或 `minecraft:explosion` / `explosion_emitter` = 原版 `EXPLOSION_EMITTER` + `EXPLOSION`；`none` 仅关闭额外粒子（`VehicleExplosion` 音效/烟雾仍由本体处理）。 |
| `impact_trail_particles` | 命中瞬间补渲轨迹粒子开关，默认 `false`（字段缺省或 JSON 为 `null` 均按关闭）。弹体在命中 Tick 即死亡、飞行时间过短且从未广播过轨迹粒子时，为 true 才在命中点补一簇 `trajectory_particle`；不改变持续飞行轨迹、碰撞或伤害。 |
| `flak_particles_crack` | MCH `FlakParticlesCrack`：方块破碎粒子基数（实际 +0~2），默认 10。 |
| `num_particles_flak` | MCH `NumParticlesFlak`：白烟数量，默认 3。 |
| `flak_particles_diff` | MCH `FlakParticlesDiff`：破碎粒子速度散布（步枪约 0.1，反坦克约 0.6），默认 0.3。 |
| `caliber` | **仅 `rvp:machinegun`**：口径（毫米），曳光条宽度与弹孔粒子大小。默认 `7.62`。 |
| `tracer_r` / `tracer_g` / `tracer_b` | **仅机枪**：曳光 `energySwirl` RGB，0–1。默认 `1` / `0.85` / `0.2`。 |
| `wire_link_enabled` | **线导视觉线**（导弹类弹体）：导弹与发射武器站枢轴间绘制一根原版钓鱼线风格的黑色细线（客户端世界渲染，正常游戏视角可见，非实体碰撞）。默认 `false` 关闭。线缆**中段受重力下垂呈曲线**（二次贝塞尔，下垂量随线长自动增大，约线长的 8%，钳制 0.4~12 格），端点精确连接导弹与发射枢轴并实时更新。导弹失去制导时线缆**立即消失**（失制导情形包括：HITL 链路切断 `hitlLinkSevered`、RADIO 信号源链路被遮挡 `hitlLinkBlocked`、`hitlLife` 耗尽，以及引导段结束 `guidance_type` 回到 `NONE`）；导弹消失（爆炸/自毁/生命周期结束）后，残留线缆将在 **20 tick** 内渐隐消失。 |
| `particle_projectile_data` | `RVP_ParticleProjectileData` 嵌套对象，默认创建一份禁用配置；JSON 为 `null` 时读取端同样回退为禁用对象。启用后 RVP 类型化 Renderer 跳过弹体模型，由客户端实体 Tick 生成主体和路径尾迹，字段见下表。 |

#### `particle_projectile_data` 纯粒子弹体

该对象只控制客户端视觉，不参与服务器碰撞、直击、点火或爆炸判定。`enabled: true` 会使类型化 Renderer 跳过 Bedrock/fallback 模型；因此 `particle_type` 未被专用发射器识别时会出现“模型隐藏但无粒子”。当前实现仅识别稳定数据 ID `rvp:white_phosphorus`，其贴图资产位于 `assets/ywzj_rvp/textures/nuclear/particle_base.png`；数据 ID 不随资产目录改变。

| 字段 | 类型 | 缺省值 | 归一化与生效条件 |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | 纯粒子弹体总开关；为 true 时隐藏模型，并仅在 `particle_type` 被客户端专用发射器识别时生成粒子。 |
| `particle_type` | string | `""` | 粒子数据 ID；读取时去除首尾空白。当前必须写 `rvp:white_phosphorus`，空值或其他 ID 不生成主体和尾迹。 |
| `full_bright` | boolean | `true` | 主体与尾迹是否使用全亮光照；为 false 时使用环境光。 |
| `body_scale` | float | `0.4` | 主体尺寸倍率；有限值取 `max(value, 0)`，NaN/Infinity 回退 `0.4`。 |
| `body_start_scale` | float | `0` | 主体出生尺寸倍率；0 表示关闭尺寸生长并直接使用本次 `body_flicker` 目标尺寸。正有限值取 `max(value, 0)`，实际出生尺寸为 `min(body_start_scale, 目标尺寸)`，避免反向缩小；仅在 `body_lifetime_ticks >= 2` 时生效。 |
| `body_lifetime_ticks` | int（tick） | `3` | 单个主体粒子寿命，读取时至少为 `1`。寿命至少为 2 时，启用的 `body_start_scale` 会按平滑曲线增长并在最后一个可见 Tick 到达目标尺寸；主体生成频率由 `body_sample_interval_ticks` 决定，距离超过 512 格时有效采样间隔至少为 2。 |
| `body_color` | string（RGB） | `#FFC247` | 主体出生颜色；接受 `#RRGGBB` 或 `RRGGBB`，必须正好 6 位十六进制，不接受 8 位 ARGB，非法值回退 `#FFC247`。 |
| `body_end_color` | string（RGB） | `""` | 主体寿命末端颜色；空值或非法值沿用归一化后的 `body_color`，因此旧配置保持单色。有效时主体颜色按归一化年龄从 `body_color` 线性渐变至该值。 |
| `body_flicker` | float | `0.08` | 随机样本中的主体尺寸浮动比例，有限值钳制到 `0..1`，NaN/Infinity 回退 `0.08`；不改变亮度。实际主体尺寸会随位置状态保存，并成为下一 Tick 对应尾迹的出生尺寸。 |
| `body_horizontal_flicker` | float（格） | `0` | 随机样本分别为 X、Z 生成 `[-value, +value]` 的独立均匀目标偏移，主体从上一实际偏移沿平滑步进曲线移动到目标；有限值取 `max(value, 0)`，NaN/Infinity 回退 0。只改变客户端粒子坐标，Y、实体弹道、碰撞、点火和伤害不变。 |
| `body_flicker_interval_ticks` | int（tick） | `1` | 主体尺寸与 X/Z 随机目标点的刷新周期，读取时至少为 1；同时也是水平偏移从上一实际位置平滑到新目标的过渡时长。1 表示每 Tick 重抽并立即到达；增大后尺寸刷新更慢、位置移动更柔和。它不直接决定主体生成频率，颜色年龄渐变和尾迹出生率不受影响。 |
| `body_sample_interval_ticks` | int（tick） | `1` | 主体粒子的生成采样间隔，读取时至少为 1。1 表示近距离每 Tick 生成；2 表示约每秒 10 次。只降低主体出生率，不改变随机样本刷新、颜色年龄曲线或近距离尾迹每 Tick 沉积。超过 512 格时有效间隔至少为 2。 |
| `trail_enabled` | boolean | `true` | 是否在连续客户端 Tick 中把上一主体位置沉积为一个尾迹粒子；首 Tick、静止 Tick、追踪中断或距离超过 512 格时不生成。 |
| `trail_initial_extra_count` | int | `0` | 每个初段成功移动尾迹 Tick 在原始尾迹外追加的粒子数，限制为 `0..16`；0 关闭。只有连续追踪、距离不超过 512 格、弹体真实移动且尾迹启用时才生效。 |
| `trail_initial_extra_ticks` | int（有效尾迹 tick） | `0` | 初段增密覆盖的成功移动尾迹 Tick 数，限制为 `0..100`；0 关闭。首 Tick、静止、追踪中断、超距或尾迹关闭均不消耗计数。 |
| `trail_initial_spread` | float（格） | `0` | 附加点相对原尾迹点的球体积均匀散布半径，有限值限制为 `0..16`，NaN/Infinity 回退 0。0 关闭初段增密，保持单个原始尾迹且不消耗散布随机数。 |
| `trail_lifetime_ticks` | int（tick） | `24` | 单个尾迹粒子寿命，读取时至少为 `1`。 |
| `trail_lifetime_start_on_landing` | boolean | `false` | 是否把同一弹体所有尾迹的寿命起点推迟到对应主体落地。false 保持每个尾迹从出生 Tick 立即计时；true 时飞行期间冻结尾迹年龄，检测到弹体 `onGround`、碰撞结束、被移除或客户端追踪释放后统一从年龄 0 开始按 `trail_lifetime_ticks` 计时。只影响客户端粒子生命周期。 |
| `trail_hot_phase_ticks` | int（tick） | `0` | 尾迹出生后的高温火光阶段时长；读取时取 `max(value, 0)`，0 禁用并完全沿用旧颜色曲线。正值启用时按独立视觉年龄把 `trail_hot_color` 平滑混合回既有尾迹颜色，不增加粒子数。 |
| `trail_hot_color` | string（RGB） | `#FFC247` | 高温阶段出生颜色；格式规则同 `body_color`，非法值回退橙黄色 `#FFC247`。仅在 `trail_hot_phase_ticks > 0` 时可见。 |
| `trail_end_scale` | float | `0.02` | 尾迹消失尺寸倍率；有限值取 `max(value, 0)`，NaN/Infinity 回退 `0.02`。 |
| `trail_start_alpha` | float | `0.9` | 尾迹出生透明度，有限值钳制到 `0..1`，NaN/Infinity 回退 `0.9`。 |
| `trail_end_alpha` | float | `0` | 尾迹消失透明度，有限值钳制到 `0..1`，NaN/Infinity 回退 `0`。 |
| `trail_start_color` | string（RGB） | `#FFFFFF` | 尾迹出生颜色；格式规则同 `body_color`，非法值回退白色。 |
| `trail_end_color` | string（RGB） | `#FFFFFF` | 尾迹消失颜色；格式规则同 `body_color`，非法值回退白色。 |

主体尺寸与 X/Z 目标偏移组成一组随机样本。尺寸目标为 `body_scale × (1 + [-body_flicker, +body_flicker] 随机量)`；若 `body_start_scale > 0` 且寿命至少 2 Tick，单个主体从较小出生尺寸使用 `smoothstep(t)=t²(3-2t)` 长到该目标，否则直接以目标尺寸生成。X/Z 从上一实际偏移使用相同平滑曲线移动，并在 `body_flicker_interval_ticks` 周期末准确到达目标；下个周期从该实际位置继续移动，不发生目标刷新瞬间的坐标跳变。追踪 Tick 中断时从权威位置重新起步并重抽，避免复用过期位置。`body_sample_interval_ticks` 只决定哪些 Tick 真正创建主体粒子，不影响这组视觉状态的逐 Tick 更新。尾迹不再对“上一位置 → 当前位置”的线段插值补点：每个移动中的实体在上一视觉状态的实际平滑位置沉积一个原始尾迹粒子，超过 512 格不生成，因此主体降采样不会让尾迹同步断续。移动判定始终比较连续 Tick 的权威弹体位置，避免静止弹体只因 `body_horizontal_flicker` 改变而堆积假尾迹。若初段增密数量与 Tick 数均大于 0，前 `trail_initial_extra_ticks` 个成功沉积 Tick 还会在原始点周围追加 `trail_initial_extra_count` 个尾迹；半径使用 `cbrt(U) × trail_initial_spread`、方向按球面均匀采样，因此附加点在球体积内均匀分布而不堆积于球心。尾迹出生尺寸继承对应主体的随机目标尺寸，而非启用后的较小出生尺寸，再平滑过渡到 `trail_end_scale`；原始点和附加点完整复用尺寸、透明度、颜色、高温阶段、寿命、全亮和落地寿命门。启用高温阶段后，同一个尾迹粒子在出生时使用 `trail_hot_color`，并在 `trail_hot_phase_ticks` 内按 smoothstep 降低热色权重、平滑回到当时的基础颜色，因此形成贴近主体的短火光段而不增加第二层粒子。高温阶段使用不受寿命门冻结的独立视觉年龄；启用 `trail_lifetime_start_on_landing` 后，飞行期间尺寸、透明度和基础冷却曲线仍冻结，但火色会按真实客户端 Tick 正常结束。同一弹体的尾迹共享一个只弱引用弹体的客户端计时门；落地或实体结束后门永久打开并统一开始正常衰减，避免尾迹持有已移除实体。该模式不会增加每 Tick 出生率，但粒子峰值存量会额外包含全部可见飞行阶段尾迹。尺寸生长和高温阶段均不增加粒子数量。三个初段字段默认均为 0，未配置时不生成附加点且不额外消耗随机数。`trail_spacing` 与 `trail_start_scale` 均已从当前 schema 删除，旧 JSON 中的同名未知键只会被 Gson 忽略，不提供迁移或别名。

`trail_initial_extra_count`、`trail_initial_extra_ticks` 与 `trail_initial_spread` 必须同时大于 0 才启用初段增密；任一字段关闭时保持旧行为，不生成附加点也不额外消耗随机数。

```json
"effects_data": {
  "trajectory_particle": "none",
  "impact_particle": "none",
  "explosion_particle": "none",
  "particle_projectile_data": {
    "enabled": true,
    "particle_type": "rvp:white_phosphorus",
    "full_bright": true,
    "body_scale": 0.42,
    "body_start_scale": 0.08,
    "body_lifetime_ticks": 3,
    "body_color": "#FF8A1F",
    "body_end_color": "#FF2400",
    "body_flicker": 0.8,
    "body_horizontal_flicker": 0.15,
    "body_flicker_interval_ticks": 4,
    "body_sample_interval_ticks": 2,
    "trail_enabled": true,
    "trail_initial_extra_count": 4,
    "trail_initial_extra_ticks": 10,
    "trail_initial_spread": 0.8,
    "trail_lifetime_ticks": 26,
    "trail_lifetime_start_on_landing": true,
    "trail_hot_phase_ticks": 4,
    "trail_hot_color": "#FFC247",
    "trail_end_scale": 0.03,
    "trail_start_alpha": 0.90,
    "trail_end_alpha": 0.0,
    "trail_start_color": "#FFB52E",
    "trail_end_color": "#7A3512"
  }
}
```

#### 导弹原生尾焰（`missile_native_trail_*`）

导弹/火箭类弹体飞行时由本体粒子发射器生成的尾焰，可用以下字段覆盖（全部可选）：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `missile_native_trail_enabled` | 是否启用原生尾焰。 | `true` |
| `missile_native_trail_particle` | 覆盖尾焰粒子 ID（空 = 保持本体默认）。 | `""` |
| `missile_native_trail_step` | 尾焰沿弹道采样步长（格）。 | `0.5` |
| `missile_native_trail_spawn_interval_tick` | 粒子生成间隔（tick）。 | `1` |
| `missile_native_trail_density_scale` | 粒子密度倍率。 | `1` |
| `missile_native_trail_offset` | 粒子相对弹体尾部的偏移距离（格）。 | `3` |
| `missile_native_trail_extra_flame` | 是否追加火焰粒子。 | `true` |
| `missile_native_trail_extra_smoke` | 是否追加烟尘粒子。 | `true` |

机枪飞行曳光与本体相同：固定 `ywzj_vehicle:entity/basic_bullet` + `textures/entity/basic_bullet.png`（`effects_data` 仅控制口径与 `tracer_*` 颜色）。导弹/炸弹飞行模型见 `assets/rvp/display/weapon/<id>.json`。

机枪曳光示例：

```json
"effects_data": {
  "trajectory_particle": "none",
  "impact_particle": "minecraft:block",
  "caliber": 30,
  "tracer_r": 1.0,
  "tracer_g": 0.5,
  "tracer_b": 0.1
}
```

常用短名：`none`、`smoke`、`flame`、`cloud`、`block`、`explosion`、`explosion_emitter`。也可写完整粒子 ID。

### 1.9 `detonate_data` 落点效果与爆炸

弹体在**方块命中、实体命中、引信/近炸/空爆结束**时，于落点执行爆炸与下列自定义效果（服务端）。

| 字段 | 说明 |
| --- | --- |
| `effects_before_explosion` | 为 `true`（默认）时先执行下方自定义效果再爆炸；为 `false` 时先爆炸再自定义效果。 |
| `explosion_data` | 爆炸参数（`RVP_Explosion`，继承本体 `Explosion` POJO 字段）。与 `collision_data.direct_damage` 无关。 |
| `fire_data` | 点燃空气格。`radius`：水平扩散格数（0=仅命中面邻格）；`chance`：每格概率 0–1；`soul_fire`；`on_block` / `on_entity` 是否在方块/实体命中时触发。 |
| `potion_cloud_data` | 生成原版药水云。`effect`（如 `slowness` / `minecraft:poison`）、`amplifier`、`duration_ticks`（作用于实体）、`cloud_duration_ticks`、`radius`、`radius_per_tick`、`targets`。 |
| `potion_effect_data` | 对范围内实体**直接**上 buff，不生成云；字段同上（忽略 `cloud_duration_ticks`）。 |
| `place_block_data` | 放置方块。`block`、`radius`、`chance`、`replace_mode`：`air_only` / `replaceable` / `always`。 |
| `lightning_data` | 召唤闪电。`damage`：是否造成伤害（false 为纯特效）。 |
| `ignite_entity_data` | `radius`、`seconds`（着火秒数）、`targets`。 |
| `knockback_data` | `radius`、`strength`、`targets`。 |
| `clear_plants_data` | `radius`：清除草、花、树叶等可替换植物。 |
| `hbm_effect_data` | 大威力爆炸视觉/特效（见下）。 |
| `visual_effect_data` | 当前 schema 的通用爆炸视觉配置数组；默认空，不改变旧武器行为。已注册 `rvp:thermobaric` 最小视觉工厂，支持火球、压力波、尘环和烟云。 |

`targets`（范围类效果共用）：`living`（默认）、`players`、`hostile`、`non_allied`（排除 owner 与发射载具乘员）、`all`。

#### `detonate_data.explosion_data`

| 字段 | 说明 |
| --- | --- |
| `explode` | 是否产生爆炸效果。 |
| `damage` | 爆炸伤害。 |
| `radius` | 爆炸半径。 |
| `proximity_fuze` / `proximity_radius` | 近炸引信；`fuse_data.proximity_radius` 优先，未写时可读此处。 |
| `destroy_block` | 是否破坏方块。 |

燃烧弹示例（先点火再小爆炸）：

```json
"detonate_data": {
  "effects_before_explosion": true,
  "explosion_data": {
    "explode": true,
    "damage": 8,
    "radius": 0.8,
    "destroy_block": false
  },
  "fire_data": { "radius": 2, "chance": 0.85 },
  "ignite_entity_data": { "radius": 2.5, "seconds": 6, "targets": "living" }
}
```

#### `detonate_data.hbm_effect_data` 大威力爆炸特效

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `enabled` | 总开关。 | `false` |
| `real_explosion` | 真实爆炸预设：`none`（默认）/ `vnt`（HBM 标准爆炸，自带视觉特效）/ `nuclear`（核爆）。 | `none` |
| `visual_preset` | 视觉特效预设：`none`（默认）/ `nuclear`（或 `nuke`，蘑菇云）/ `shell`（小规模爆炸）/ `bomb`（大规模爆炸）。 | `none` |
| `visual_scale` | 视觉规模倍率（≥0.1）。 | `1.0` |
| `visual_density` | 粒子密度（0.1–1.0）。 | `1.0` |
| `visual_backend` | 视觉后端：`auto` / `hbm` / `rvp`。 | `auto` |
| `visual_sound` | 是否播放视觉特效音效。 | `true` |
| `suppress_native_explosion_effect` | 是否抑制原版爆炸特效。 | `true` |
| `nuclear_sound` / `nuclear_flash` / `nuclear_shake` | 核爆音效 / 闪光 / 屏幕震动。 | `true` |
| `effect_yield` | 当量（≥0）。 | `0.0` |
| `spawn_frag` | 是否抛撒破片。 | `false` |
| `white_phosphorus` | 白磷燃烧效果。 | `false` |
| `chlorine_yield` | 氯气当量（>0 启用）。 | `0.0` |
| `destroy_block` | 是否破坏方块。 | `true` |

##### `detonate_data.hbm_effect_data` 视觉数值推导公式（现网生效值，2026-09-03）

`visual_preset` 的几何/数量参数由 `visual_scale`（几何缩放）与 `visual_density`（数量密度，0.1–1.0）在桥接层（HBM 后端）与自研回退后端以**同一公式同步推导**（两后端铁律一致）。以下均为现网生效公式与钳制区间：

| 参数 | 公式 | 钳制区间 | 生效档位 |
| --- | --- | --- | --- |
| `shellCloudCount` 烟团数 | round(10 × density) | [4, 80] | shell |
| `shellCloudScale` 烟团尺寸 | 2.0 × scale | [0.4, 16] | shell |
| `shellCloudSpeed` 烟柱速度 | 0.5 × scale（线性） | [0.15, 4] | shell |
| `shellDebrisCount` 碎屑数 | round(15 × density) | [0, 120] | shell（保持原样，不随 scale 缩） |
| `bombCloudCount` 烟团数 | round(30 × density) | [8, 180] | bomb（仅受 density，不随 scale 缩） |
| `bombCloudScale` 烟团尺寸 | 6.5 × scale | [1, 32] | bomb |
| `bombCloudSpeed` 烟柱速度 | 2.0 × scale（线性） | [0.35, 6] | bomb |
| `bombWaveScale` 冲击波 | 97.5 × scale（=65×1.5，2026-09-03 放大 1.5 倍） | [12, 330]（同比例放大） | bomb |
| `bombDebrisCount` 碎块数 | round(25 × scale × density) | [2, 160] | bomb |
| `bombDebrisSize` 碎块边长 | round(16 × scale) | [4, 64] | bomb |
| `bombDebrisRetry` 簇填充重试 | 固定 50（不随 scale/density 缩） | — | bomb |
| `bombDebrisVelocity` 碎块抛速 | 1.25 × √scale（保留 sqrt） | [0.2, 4] | bomb |
| `bombDebrisHorizontalDeviation` 碎块水平散布 | 3.0 × scale | [0.5, 12] | bomb |
| `bombSoundRange` 音域 | 350 × scale（线性） | [80, 800] | bomb |
| `shellSoundRange` 音域 | 200 × scale（线性） | [60, 600] | shell |

公式说明：
- 烟柱速度与音域采用**线性** scale（原 √scale 会拉细烟柱，线性缩放才保持烟柱高宽比恒 ≈4.4）；
- 碎块抛速**保留 √scale**（碎块为固定重力无摩擦弹道，射程 ∝ v²/g，sqrt 缩放才能等比缩小抛物线）；
- 碎块采样重试固定 50（控制簇内部填充密度，属"簇质量"而非"数量/尺寸"，不随 scale 缩放）；
- **`bombDebrisCount` 新公式（2026-09-03 修订）**：由原 `round(25 × density)` 改为 `round(25 × scale × density)`，同时受 scale 钳制（几何越小碎块越少），下限 2 避免为 0；烟团数与 shell 碎屑数维持原公式不变。例：`visual_scale=0.3`、`visual_density=1.0` 时碎块数 = round(7.5) = 8。

生效条件：`visual_preset` 实际生效（经 `RVP_HbmEffectBridge.apply` / `RVP_HbmVisualService`）时，上述公式才参与推导；`real_explosion = vnt` 时其自带视觉，`visual_preset` 不生效。

#### `detonate_data.visual_effect_data[]` 通用爆炸视觉

该数组只选择视觉算法和表现参数，不改变 `explosion_data` 的伤害、最终半径或方块破坏。不得使用武器 ID 判断效果类型。单项 `preset_data` 规范化后的 UTF-8 载荷不得超过 8 KiB；非法或超限配置会放弃自定义视觉并保留本体普通爆炸视觉。

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `enabled` | 启用该项；还要求 `effect_type` 是合法资源 ID。 | `false` |
| `effect_type` | 客户端效果工厂类型，如 `rvp:thermobaric`。 | 空 |
| `preset` | 客户端预设资源 ID；工厂不识别时由工厂回退。 | `rvp:default` |
| `scale` | 视觉尺寸倍率；接受非负有限值，不设业务上限。 | `1.0` |
| `density` | 服务端视觉密度倍率；接受非负有限值，不设业务上限，具体温压粒子数量仍不会突破对应 `max_*` 配置。 | `1.0` |
| `duration_ticks` | 整个视觉实例的结束时间/总寿命覆盖（tick，从事件起点计）；`-1` 使用预设中最晚的阶段结束时间，非负整数直接覆盖，`0` 表示不创建可见实例。 | `-1` |
| `broadcast_range` | 同维度网络广播距离（格）；接受非负有限值，不设业务上限。 | `1536.0` |
| `sound` / `flash` / `shake` | 是否允许声音、闪光、镜头震动；客户端设置仍可进一步关闭。 | `true` |
| `suppress_native_explosion_effect` | 视觉事件成功发布后是否屏蔽本体普通爆炸视觉；不影响伤害与方块破坏。 | `true` |
| `suppress_rvp_default_explosion` | 屏蔽 RVP 内置默认爆炸视觉（`rvp:mchr_explosion`），该武器改走本体爆炸视觉。读取不受 `enabled` 门控——条目可只作屏蔽标记存在（不配 `effect_type`）。 | `false` |
| `experimental` | 当前视觉事件的类型化实验配置对象；缺失或为 `null` 时所有实验均关闭。它与 `preset_data` 同级，不属于预设 schema。 | `{}` |
| `preset_data` | 与 `preset` 相同 schema 的稀疏 JSON 覆盖；由对应客户端工厂类型化校验。 | `{}` |

`experimental` 当前接受以下字段：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `dynamic_particle_budget` | 实验性动态粒子预算。当前仅 `effect_type: "rvp:thermobaric"` 消费；为 `true` 时火球、粒子凝结云、贴地尘环和后燃烟云按各自几何覆盖面积与实际 billboard 有效面积求出饱满所需数量，再受 `density`、对应 `max_*` 和距离 LOD 钳制。其它视觉类型忽略该字段。 | `false` |

##### `rvp:thermobaric` / `rvp:thermobaric_standard` 温压预设字段

 使用内建固定标准预设；`preset_data` 可稀疏覆盖下列字段。当前默认以服务端开发副本 `bmpt_9m120_f.json` 的首条 `visual_effect_data` 为权威稀疏补丁，但 `enabled`、`effect_type`、`preset` 分别继续默认 `false`、空、`rvp:default`，不会因空对象自动启用温压视觉。未知字段或类型、范围错误的字段会单独回退，不影响同一对象内其他合法覆盖。顶层 `scale`、`density`、`duration_ticks` 仍拥有更高优先级。

| 字段 | 说明 | 内建默认值                                                                   |
| --- | --- |-------------------------------------------------------------------------|
| `core_color` / `flame_color` / `smoke_color` | 点火核心、主火球/早期云团、后期烟云的 `#RRGGBB` 颜色。 | `#FFE0B0` / `#FF6820` / `#332A27`                                       |
| `show_core` / `show_dust_ring` / `show_cloud` | 分别控制主火球、贴地尘环和后燃烟云是否显示；每项均独立生效。 | 均为 `true`                                                               |
| `show_pressure_wave` | 是否显示压力波光学球壳。 | `false`                                                                 |
| `show_condensation_cloud` | 是否显示使用 `WHITE_TEXTURE` 双层球壳实现的凝结云墙；与粒子凝结云独立。 | `false`                                                                 |
| `show_condensation_cloud_particles` | 是否显示仅使用 `PARTICLE_TEXTURE` billboard 实现的粒子凝结云；与球壳凝结云独立。 | `true`                                                                  |
| `condensation_cloud_particle_max_count` | 完整视觉密度下粒子凝结云允许显示的最大粒子数量；接受非负整数、不设业务上限，实际数量还会乘以顶层 `density` 并受 `thermobaric_lod` 下调。 | `768`                                                                  |
| `condensation_cloud_particle_scale` | 粒子凝结云的贴图尺寸缩放倍率；接受非负有限值，不设业务上限。 | `6.0`                                                                   |
| `condensation_cloud_particle_spawn_thickness_factor` | 粒子凝结云在 start tick 的墙体厚度倍率；接受非负有限值、不设业务上限。厚度按 `visualRadius × lerp(formationProgress, 1.25 × factor, 0.12)` 计算，只改变起始厚度，full tick 及以后恢复原有厚度；不影响数量、贴图尺寸、扩张、裁切、淡出、寿命或可选凝结云球壳。 | `1.0` |
| `thermobaric_lod` | 温压火球、粒子凝结云、贴地尘环、后燃基础云团和连接粒子共用的嵌套四档距离 LOD；结构见下表。压力波和可选白色凝结云球壳不受其影响。 | 见下表 |
| `condensation_cloud_cut_speed_factor` | 粒子凝结云与可选凝结云球壳在 full tick 后从当前球体 Y 轴最高点向下连续裁切的速度倍率；接受非负有限值、不设业务上限。`0` 关闭裁切，`1.0` 恰好在派生阶段结束时裁切至最低点；只改变裁切进度，不改变径向速度、淡出或寿命。 | `0.75` |
| `pressure_wave_fade_speed_factor` | 压力波光学球壳、粒子凝结云与可选凝结云球壳从 full tick 到派生结束 tick 的线性淡出速度倍率；接受非负有限值、不设业务上限。`0` 不启用线性淡出，`1.0` 恰在派生结束 tick 淡至透明，大于 `1.0` 提前淡完，`0..1` 只完成部分淡出；不改变径向速度、裁切或寿命。 | `0.0` |
| `max_clouds` | 完整视觉密度下三层基础后燃烟云允许生成的最大云团数量；接受非负整数，不设业务上限。为保证爆心覆盖层与中心上升层连续，客户端可基于固定锚点派生额外连接粒子，派生数量最多为基础烟云数的四分之一且绝不超过 64，不计入本字段。 | `1024`                                                                   |
| `max_fireball_clouds` | 完整视觉密度下温压火球允许生成的最大团状云数量；接受非负整数，不设业务上限。 | `150`                                                                   |
| `max_dust_segments` | 完整视觉密度下贴地尘环允许生成的最大环段数量；接受非负整数，不设业务上限。 | `425`                                                                   |
| `dust_ground_radial_samples` | 尘环沿扩散半径预采样的地表层数；接受非负整数，不设业务上限，`0` 关闭地表采样。 | `9`                                                                     |
| `pressure_rings` / `pressure_segments` | 压力波及凝结云球壳的纬向/经向细分数；接受非负整数，不设业务上限，任一为 `0` 时不提交球壳。 | `16` / `32`                                                             |
| `core_start_tick` / `core_full_tick` / `core_fade_duration_ticks` | 主火球的绝对开始时刻、完整成形时刻、完整成形后到消失的持续时间（tick）；均接受非负整数，不设业务上限。 | `0` / `20` / `40`                                                        |
| `pressure_wave_start_tick` / `pressure_wave_full_tick` | 球形压力波、粒子凝结云与可选凝结云球壳的绝对开始、完整成形时刻（tick）；均接受非负整数、不设业务上限。派生阶段时长自动等于成形时长，结束 tick 为 `full + (full - start)`。两种凝结云从开始到 full tick 匀速扩张，随后保持相同径向速度继续外扩，结束半径为 full tick 半径两倍；压力波光学球壳共用派生寿命并保留其独立扩张表现。是否线性变淡由 `pressure_wave_fade_speed_factor` 控制。 | `10` / `25` |
| `dust_ring_start_tick` / `dust_ring_full_tick` | 贴地尘环的绝对开始时刻，以及到达 `dust_radius_factor` 配置半径并立即开始消散的时刻（tick）；均接受非负整数、不设业务上限。尘环从开始到 full tick 匀速扩张，之后以相同径向速度继续外扩并线性变淡；消散时长自动等于成形时长，结束时半径为 full tick 半径的两倍。地面高度按各环段采样，空爆也会投影到可见地表。 | `10` / `25`                                                       |
| `cloud_start_tick` / `cloud_full_tick` / `cloud_fade_duration_ticks` | 后燃烟云的绝对开始时刻、开始消散时刻、开始消散后到消失的持续时间（tick）；大型云团从 `cloud_start_tick` 到 `cloud_full_tick + cloud_fade_duration_ticks` 共用一条连续动画时钟，持续上升、翻滚、卷吸和平流。`cloud_full_tick` 仅启动独立淡出进度：稳定径向层级最外侧的云团先向外扩散和变淡，随后逐层向内，淡出结束时全部消失。均接受非负整数、不设业务上限，顶层 `duration_ticks>=0` 可覆盖实例寿命。 | `0` / `90` / `140`                                                       |
| `cloud_color_change_start_tick` / `cloud_color_change_end_tick` | 后燃烟云从 `flame_color` 向 `smoke_color` 线性变色的绝对开始、结束时刻（tick）；开始前保持火焰色，结束后保持烟色。均接受非负整数、不设业务上限；结束早于开始时钳制为开始时刻并立即变色。 | `30` / `85` |
| `pressure_radius_factor` | 压力波与凝结云在 `pressure_wave_full_tick` 时相对最终爆炸半径的半径倍率；接受非负有限值，不设业务上限。凝结云派生结束时半径为该半径的两倍。 | `3.5`                                                                   |
| `dust_radius_factor` | 尘环最大半径相对最终爆炸半径的倍率；接受非负有限值，不设业务上限。 | `5.0`                                                                   |
| `cloud_radius_factor` | 烟云横向半径相对最终爆炸半径的倍率；接受非负有限值，不设业务上限。高倍率造成爆心覆盖层与中心上升层实际分离时，客户端按几何间隙自动生成粒子连接链，不按武器 ID 或固定倍率阈值分支。 | `1.7`                                                                   |
| `cloud_rise_factor` | 后燃烟云最终最高升起高度相对最终爆炸半径的直接倍率；接受非负有限值、不设业务上限，配置值本身不钳制到 `0..1`。实际高度为最终爆炸半径乘该字段，再乘独立且限制在 `0..1` 的内部完成度；例如 `5.0` 表示最高五倍半径。高倍率下中心上升层仍必须通过自动派生粒子与爆心覆盖层保持连续。 | `1.5`                                                                   |
| `cloud_rise_speed_factor` | 后燃烟云竖直基础升起速度的无量纲倍率；接受非负有限值、不设业务上限。大于 `1.0` 时更早到达高度上限，小于 `1.0` 时可能在消失前未到顶，`0` 停止基础升起；不改变淡出、变色或寿命。 | `12.0` |
| `cloud_roll_speed_factor` | 后燃烟云翻滚、卷吸、连续湍流及水平平流速度的无量纲倍率；接受非负有限值、不设业务上限。空间包络完整后周期相位仍继续推进，`0` 冻结对应运动；不改变基础升起、淡出、变色或寿命。 | `1.0` |
| `near_sound` / `far_sound` / `tail_sound` | 近音、远音、尾音资源 ID。24 格内主音立即播放，更远按 `ceil(distance / 17.15)` tick 延迟；`distance <= max(24, 4 × visualRadius)` 选择近音，否则选择远音。尾音从主音实际播放起按事件 seed 确定性延迟 3–6 tick。声音使用动态可变范围事件，因此资源包可直接覆盖或提供自定义 ID。 | `rvp:thermobaric_near` / `rvp:thermobaric_far` / `rvp:thermobaric_tail` |

`thermobaric_lod` 使用以下嵌套结构：

| 路径 | 说明 | 默认值 |
| --- | --- | --- |
| `near.max_distance` | 近档最大爆心距离（格），接受非负有限值；边界距离属于近档。 | `512.0` |
| `near.particle_ratio` | 近档粒子保留比例，范围 `0..1`。 | `1.0` |
| `medium.max_distance` | 中档最大爆心距离（格），接受非负有限值；边界距离属于中档。 | `756.0` |
| `medium.particle_ratio` | 中档粒子保留比例，范围 `0..1`。 | `0.7` |
| `far.max_distance` | 远档最大爆心距离（格），接受非负有限值；边界距离属于远档。 | `1024.0` |
| `far.particle_ratio` | 远档粒子保留比例，范围 `0..1`。 | `0.35` |
| `beyond.particle_ratio` | 超过 `far.max_distance` 后无上界档位的粒子保留比例，范围 `0..1`。 | `0.0` |

`preset_data` 可以只覆盖一个档位或一个子字段；其余值继续继承所选 preset。合并后距离自动规范化为 `near <= medium <= far`，粒子比例自动规范化为 `near >= medium >= far >= beyond`。未知档位、未知子字段、错误类型或非法范围只回退对应局部字段，不影响同对象内其它合法值。各粒子组按完整数量乘当前档比例计算目标数量，正比例且原数量非零时至少保留一个；LOD 在粒子姿态计算、透明排序和顶点提交前生效。

`experimental.dynamic_particle_budget` 默认为 `false`，关闭时严格使用上述原有完整数量与 LOD 逻辑，固定三层火球核心和尘环均匀选段也不变。显式开启后先按 `capacity = min(max_*, round(max_* × density))` 得到容量。客户端资源重载时从实际 `particle_base.png` 按 `Σ(alpha / 255) / pixelCount` 计算贴图有效覆盖率；当前内置贴图为 `116 / 256 = 0.453125`，读取失败回退该值。单个 billboard 的有效面积为 `(2 × halfSize)² × textureCoverage`；实现按稳定候选顺序累计实际有效面积，取满足 `geometryArea × overlapFactor` 的最短前缀，再依次受容量和距离 LOD 钳制。运行时透明度不进入覆盖面积，避免淡出期补生粒子；固定核心以 `3` 为容量且仅在实验开启时应用 `density`。

四类几何模型分别为：粒子凝结云使用未裁切可见球面 `4πr² × (1 - cutProgress)`，重叠系数 `2.50`，粒子实际尺寸包含 `condensation_cloud_particle_scale`；贴地尘环把三个径向带分别展开为 `2π × bandRadius × 2 × halfSize`，每个环段的覆盖量为三张 billboard 有效面积之和，重叠系数 `1.20`；火球以实际核心外包半径计算 `4πr²`，重叠系数 `1.50`；后燃云以最大水平包络半径 `a` 与垂直半包络 `b` 计算 `π × a × max(a,b)`，重叠系数 `3.00`。凝结云和尘环在 full tick 后继续随外扩、裁切和收窄几何重算；火球与后燃云冻结 full tick 的面积需求。尘环仅在实验开启时使用稳定渐进环段顺序；后燃云仍优先保留连接锚点，派生连接粒子不计入 `max_clouds`。

阶段 C 客户端资源与反馈规则：客户端资源包可在 `assets/<namespace>/visual_effects/*.json` 提供稀疏预设，例如 `rvp:thermobaric_standard` 对应 `assets/rvp/visual_effects/thermobaric_standard.json`。字段按“内建安全默认值 < 资源预设 < 武器 `preset_data` < 顶层 `sound`/`flash`/`shake` 开关 < 客户端性能与无障碍上限”合并；F3+T 重载后只影响新建实例，已有实例继续持有旧快照。未知、缺失或完整解析失败的预设回退内建标准值，单个非法字段只回退该字段。

`ywzj_rvp-client.toml` 的 `[thermobaric]` 配置如下。质量只影响创建实例时生成的粒子列表，压力波与球壳不受影响；活动实例不会因运行中改档而重建。声音与反馈倍率在触发或渲染时读取，三项互相独立，`0` 即关闭。

| 客户端配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `thermobaricQuality` | `LOW` / `MEDIUM` / `HIGH` 分别将作者粒子密度乘以 `0.50` / `0.75` / `1.00`；不会增强作者预算。 | `HIGH` |
| `thermobaricSoundVolume` | 温压声音音量倍率，范围 `0..1`。 | `1.0` |
| `thermobaricFlashIntensity` | 立即闪光强度倍率，范围 `0..1`。 | `1.0` |
| `thermobaricShakeIntensity` | 声波抵达后镜头震动强度倍率，范围 `0..1`。 | `1.0` |

主火球和后燃烟云的消失时刻等于 `*_full_tick + *_fade_duration_ticks`。压力波与两种凝结云的结束时刻为 `pressure_wave_full_tick + (pressure_wave_full_tick - pressure_wave_start_tick)`；贴地尘环同样使用 `dust_ring_full_tick + (dust_ring_full_tick - dust_ring_start_tick)`。对应 `full_tick <= start_tick` 时没有有效移动周期，不绘制该阶段。若其它阶段的 `*_full_tick` 早于对应 `*_start_tick`，客户端会把阶段切换时刻钳制到开始时刻；其中后燃烟云的 `cloud_full_tick` 仅表示开始消散。不接受旧版结束时间/总持续时间键。

主火球在 `core_full_tick` 后由外向内线性变灰、轻微内敛收缩并线性降低透明度；贴地尘环为白色，从生成到消失持续线性变细，并在 `dust_ring_full_tick` 后立即以原速度继续外扩和线性变淡，不使用随机径向加速。压力波光学球壳在 `pressure_wave_full_tick` 后继续使用服务端同步种子生成的稳定随机漂移参数。粒子凝结云与可选球壳在 full tick 前后保持同一径向速度；三者共同按 `pressure_wave_fade_speed_factor` 决定是否及多快线性淡出，默认 `0` 时保持透明度，并按 `condensation_cloud_cut_speed_factor` 共用自顶向下裁切边界。后燃烟云从开始到消失持续上升、翻滚、卷吸和平流，并分别使用 `cloud_rise_speed_factor` 与 `cloud_roll_speed_factor` 缩放两类运动时钟；`cloud_full_tick` 始终启动按稳定径向层级从外向内传播的淡出，实验性动态预算开启时还冻结该时刻按云团最大视向轮廓计算出的覆盖需求，但仍不切换运动曲线。凝结云先于其它温压子效果提交，避免近距离观察时外层透明云错误覆盖其它特效，同时仍遵守世界几何的深度遮挡。

爆心覆盖层与中心上升层会从基础云团中按角度、径向距离和原始索引确定一对跨帧固定锚点。若锚点当前 billboard 已重叠则不追加粒子；若存在实际三维间隙，则沿中心线派生连接粒子，并在达到内部数量上限时扩大粒子尺寸以维持连续覆盖。该行为没有新增 JSON 字段，不改变伤害、寿命、颜色或原三层运动曲线。

温压弹配置示例
```json
"detonate_data": {
        
  "visual_effect_data": [
  {
    "enabled": true,
    "effect_type": "rvp:thermobaric",
    "preset": "rvp:thermobaric_standard",
    "scale": 1.0,
    "density": 1.0,
    "duration_ticks": -1,
    "broadcast_range": 1536.0,
    "sound": true,
    "flash": true,
    "shake": true,
    "suppress_native_explosion_effect": true,
    "experimental": {
      "dynamic_particle_budget": false
    },
      "preset_data": {
        "core_color": "#FFE0B0",
        "flame_color": "#FF6820",
        "smoke_color": "#332A27",
        "max_fireball_clouds": 150,
        "max_clouds": 1024,
        "condensation_cloud_particle_max_count": 768,
        "condensation_cloud_particle_scale": 6.0,
        "condensation_cloud_particle_spawn_thickness_factor": 1.0,
        "thermobaric_lod": {
          "near": {
            "max_distance": 512.0,
            "particle_ratio": 1.0
          },
          "medium": {
            "max_distance": 756.0,
            "particle_ratio": 0.7
          },
          "far": {
            "max_distance": 1024.0,
            "particle_ratio": 0.35
          },
          "beyond": {
            "particle_ratio": 0.0
          }
        },
        "condensation_cloud_cut_speed_factor": 0.75,
        "core_start_tick": 0,
        "core_full_tick": 20,
        "core_fade_duration_ticks": 40,
        "cloud_color_change_start_tick": 30,
        "cloud_color_change_end_tick": 85,
        "pressure_wave_start_tick": 10,
        "pressure_wave_full_tick": 25,
        "pressure_wave_fade_speed_factor": 0.0,
        "dust_ring_start_tick": 10,
        "dust_ring_full_tick": 25,
        "max_dust_segments": 425,
        "cloud_start_tick": 0,
        "cloud_full_tick": 90,
        "cloud_fade_duration_ticks": 140,
        "pressure_radius_factor": 3.5,
        "dust_radius_factor": 5,
        "cloud_radius_factor": 1.7,
        "cloud_rise_factor": 1.5,
        "cloud_rise_speed_factor": 12.0,
        "cloud_roll_speed_factor": 1.0
      }
    }
  ]
}
```

##### `rvp:mchr_explosion` 内置默认爆炸（MCHR 风格）

RVP 武器（机枪/火箭/导弹/炸弹）爆炸的**默认视觉**，不需要任何 `visual_effect_data` 配置即生效。服务端 `RVP_DefaultExplosionVisualService` 在 `RVP_BaseBullet.triggerExplosion` 默认路径广播 `effectType = rvp:mchr_explosion`，客户端 `RVP_DefaultExplosionEffectFactory`（注册 `rvp:mchr_explosion`）消费。

粒子表现（复刻 MCHR `MCH_Explosion.effectExplosion`）：
- `rvp:mchr_smoke`（`RVP_MchrSmokeParticle`）：翻滚灰黄大烟（`big_smoke_0..11` 帧），逐帧放大、缓上浮、转白；
- `rvp:mchr_flare`（`RVP_MchrFlareParticle`）：曳光火星（`nuclear/flare.png` 光斑，拖烟）；
- 另含原版大十字闪光、方块碎屑（`BlockParticleOption`）、水中水花。

**数值全部按 `baseExplosionRadius` 内置自动计算，不开放任何 `preset_data` / 粒子参数**（尺寸、数量、颜色、寿命均不可配）。顶层 `scale` / `density` 忽略（内置按半径推导）。

**屏蔽矩阵**（任一成立即不播放默认视觉，改走本体/其它特效；`RVP_BaseBullet.java` 触发处）：
- `hbm_effect_data` 生效（HBM 全权接管）；
- `visual_effect_data` 其它特效发布成功（如温压，已完整替代爆炸视觉）；
- 任一 `visual_effect_data` 条目 `suppress_rvp_default_explosion: true`（显式走本体视觉）。

仅屏蔽默认视觉的写法（条目无 `effect_type`、不启用）：
```json
"detonate_data": {
  "visual_effect_data": [
    { "suppress_rvp_default_explosion": true }
  ]
}
```

### 1.10 `submunition_data` 子母弹 / 空中布撒

飞行中或撞击/引信时生成**弹体实体**或**任意注册实体**（对应 MCH `spawnBulletInAir` 等能力）。

与 `dispenser_data`（**落点**方块/物品布撒）不同：本分组在**飞行过程**或**撞击/引信**时生成载荷。

#### 顶层

| 字段 | 说明 |
| --- | --- |
| `releases` | 释放方案数组；见下表。为空则关闭子母弹。 |

#### `releases[]` 单条释放方案

| 字段 | 说明                                                                        |
| --- |---------------------------------------------------------------------------|
| `triggers` | 触发器列表，见下表。默认 `["in_flight"]`。                                             |
| `delay_tick` | `in_flight`：首波前倒计时 tick。                                                  |
| `interval_tick` | `in_flight`：波次间隔；0 = 剩余次数同一 tick 打完。                                      |
| `release_events` | 释放波次数（每波对每个 payload 各生成 `count` 枚）。为 0 时取各 payload `count` 之和。            |
| `per_tick` | 每个间隔 tick 触发几波（MCH `spawnBulletPerNum`），默认 1。                             |
| `payloads` | 本波要生成的弹药列表，见下表。                                                           |
| `parent_action` | 本方案完成后母弹行为：`continue`（默认）、`discard_after_release`、`discard_on_first_spawn` |
| `release_cloud_enabled` | 是否把本 release 生成的全部 payload 初始位置散布到三维椭球体积内；默认 `false`。释放云本身只改变服务端权威生成位置；若 payload 使用 `cloud_radial_horizontal`，该最终位置还会作为水平外散方向的输入。 |
| `release_cloud_radius` | 释放云轴半径对象；必须使用当前 schema 的 `{"horizontal": 4.0, "vertical": 4.0}` 格式。`horizontal` 为 X/Z 共用半径，`vertical` 为 Y 半径，单位格，默认均为 `4.0`；仅开关为 true 时生效。每轴负值按 0，NaN/Infinity 回退 4.0。两轴均为 0 时保持母弹位置。 |

释放云先使用均匀球面方向与 `cbrt(random)` 归一化半径在单位球体积采样，再令 X/Z 乘 `horizontal`、Y 乘 `vertical`，得到单位体积密度均匀的轴对齐椭球。生成顺序为“母弹当前位置 + release 云偏移 + payload 位置散布”，因此仍可与 `payloads[].spread` 的 `canister_type: 0` 叠加；释放云自身不增加速度，但 `cloud_radial_horizontal` 会读取叠加完成后的出生偏移并生成水平径向速度。关闭或两个轴半径都为 0 时不消耗额外的云位置随机数；单轴为 0 时只压扁该轴且仍采样。多波释放分别以当波发生时的母弹位置为中心，`rvp_weapon` 和 `entity` payload 行为一致。

##### `triggers` 取值

| 值 | 说明 |
| --- | --- |
| `in_flight` | 飞行中按 `delay_tick` / `interval_tick` 释放。 |
| `on_impact` | 致死撞击（方块或实体，穿透耗尽后）。 |
| `on_block_hit` | 仅方块撞击。 |
| `on_entity_hit` | 仅实体撞击。 |
| `on_fuse` | 定时/近炸/空爆等引信引爆前（在爆炸链之前）。 |

#### `releases[].payloads[]` 单种载荷

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `kind` | string | `rvp_weapon` | 载荷类型：`rvp_weapon` 生成 RVP 武器弹体，`entity` 生成已注册实体。空值或未知值按 `rvp_weapon` 处理。 |
| `weapon_id` | string | `""` | 仅 `kind: rvp_weapon` 生效。RVP 武器 ID；完整 ID 如 `rvp:xxx`，短名自动使用 `rvp` 命名空间，空值克隆母弹武器 ID。 |
| `entity_type` | string | `""` | 仅 `kind: entity` 生效。已注册实体类型 ID，如 `minecraft:arrow`；为空或无法解析时不生成。 |
| `entity_nbt` | SNBT string | `""` | 仅 `kind: entity` 生效。实体创建并定位后通过 `Entity#load` 载入；为空或 SNBT 解析失败时忽略。 |
| `count` | int | `1` | 每个释放事件为本载荷条目生成的实例数；小于 0 按 0 处理。 |
| `spread` | object | 新建默认散布对象 | 位置/速度散布配置，见下表；JSON 为 `null` 时同样回退为默认对象。 |
| `inherit_parent_velocity` | bool | `true` | 未启用发射角度逻辑时，是否把母弹当前速度作为基础速度。 |
| `inherit_parent_horizontal_velocity` | bool | `false` | 是否只继承母弹 X/Z。为 true 时覆盖 `inherit_parent_velocity` 的父弹继承方式，在散布完成后追加 `(parent.x,0,parent.z) × velocity_scale`；不继承 Y，也不缩放 `launch_speed`。发射角度模式同样可用。 |
| `inherit_vehicle_velocity` | bool | `false` | 未启用发射角度逻辑时，是否在基础速度上叠加发射载具当前速度；母弹无发射载具时无效。 |
| `velocity_scale` | float | `1` | 速度倍率，读取时最小为 `0.01`。普通模式会缩放已继承的合成速度；发射角度模式且 `launch_speed <= 0` 时，用它缩放母弹当前速率。 |
| `payloads_velocity` | double[3] | `[0,0,0]` | 世界系附加速度 `[x,y,z]`，单位格/tick；在基础速度、发射角和 `spread` 计算完成后最后叠加。数组长度不是 3 或任一分量不是有限数时按零向量处理。 |
| `payloads_velocity_factor` | float | `0` | `payloads_velocity` 的随机浮动比例；读取时限制到 `0..1`，非有限数按 0。每枚子体独立抽取 `[1-factor,1+factor]` 的共同倍率，因此不改变向量方向。 |
| `launch_yaw` | float（度） | `0` | 发射 yaw；`0=南/+Z`，顺时针为正，`-90=东`、`90=西`。`relative` 模式相对母弹参考姿态叠加，`absolute` 模式使用世界系固定角。 |
| `launch_pitch` | float（度） | `0` | 发射 pitch；`90=正下`、`-90=正上`。角度基准由 `launch_angle_mode` 决定。 |
| `launch_angle_mode` | string | `relative` | 发射角度基准；仅精确值 `absolute`（忽略大小写）使用世界系绝对角度，其余值按 `relative`，即相对母弹参考姿态叠加。 |
| `launch_speed` | float（格/tick） | `0` | 发射初速，读取时最小为 0。大于 0 时直接作为定向发射速率；为 0 时使用“母弹当前速率 × `velocity_scale`”。`cloud_radial_horizontal` 使用该基础速度的长度作为水平外散基速。 |
| `power_scale` | float | `1` | 数据字段读取时最小为 `0.01`；当前 `RVP_SubmunitionSpawner` 生成链未消费该值，现阶段不改变子体伤害或初速。 |
| `allow_submunition` | bool | `false` | 仅 RVP 弹体生效。为 `true` 时，子体可执行**其自身武器 JSON** 的 `submunition_data`；多级火箭/链式战斗部必须在父级载荷条目开启。默认关闭以阻止叶子弹继续开舱。详见 [子母弹系统与Mi28边界测试.md](../../子母弹系统与Mi28边界测试.md)。 |
| `damage_multiplier` | float / null | `null` | 仅 RVP 弹体生效。非 `null` 时乘算子体初始化后的直击伤害，最终伤害最小为 `0.01`。 |
| `suppress_explosion` | bool | `false` | 仅 RVP 弹体生效。为 `true` 且子体存在爆炸配置时，以禁用爆炸的配置替换它。 |

发射角度逻辑的启用条件是 `launch_yaw != 0`、`launch_pitch != 0` 或 `launch_speed > 0` 中至少一项成立。启用后：

- 子体方向取 `launch_yaw` / `launch_pitch` 解析结果，不再使用普通速度继承方向。
- `inherit_parent_velocity` 和 `inherit_vehicle_velocity` 均不参与定向基础速度合成；但显式 `inherit_parent_horizontal_velocity=true` 时，散布后仍追加母弹 X/Z × `velocity_scale`。
- `launch_speed > 0` 时直接使用该速率；否则使用母弹当前速度长度乘 `velocity_scale`。
- `spread` 以解析后的发射方向为中心应用，最后再叠加 `payloads_velocity`。

未启用发射角度逻辑时，先按两个继承开关合成速度，再乘 `velocity_scale`、应用 `spread`，最后叠加 `payloads_velocity`。如果合成并缩放后的速度接近零，则回退为沿母弹运动方向的 `0.5` 格/tick 基础速度。

近地开舱并向下抛撒的典型配置：

```json
{
  "fuse_data": {
    "ground_proximity_fuse_distance": 20.0,
    "ground_proximity_fuse_arm_tick": 10
  },
  "submunition_data": {
    "enabled": true,
    "releases": [{
      "triggers": ["on_fuse"],
      "release_events": 1,
      "parent_action": "discard_after_release",
      "payloads": [{
        "kind": "rvp_weapon",
        "weapon_id": "rvp:cluster_bomblet",
        "count": 12,
        "payloads_velocity": [0.0, -3.0, 0.0],
        "payloads_velocity_factor": 0.25
      }]
    }]
  }
}
```

#### `payloads[].spread` 散布

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `mode` | string | `box` | `box`、`canister`、`stratified_cone` 或 `cloud_radial_horizontal`。两个专用速度模式优先于 canister；其他值不会自动迁移。注意完整默认对象的 `canister_diff=0.3`，所以 `mode=box` 但未把 `canister_diff` 设为 0 时，运行时仍会进入 canister 判定。 |
| `box_spread` | float | `0` | 非 canister 模式下的轴向速度随机扰动幅度，读取时最小为 0；Y 轴扰动为 X/Z 的一半。 |
| `canister_type` | int | `1` | 读取时限制到 `0..2`：`0` 改变生成位置，`1` 和 `2` 在当前子弹药散布器中都执行角度散布。 |
| `canister_diff` | float | `0.3` | canister 散布强度，读取时最小为 0；`type: 0` 时用于位置偏移，`type: 1/2` 时作为角度散布量。大于 0 会启用 canister，即使 `mode` 仍为 `box`。 |
| `canister_distribution` | string | `uniform` | canister 采样分布；取值与 `fire_data` 的散布分布一致。 |
| `canister_shape` | string | `circle` | canister 形状；由 `RVP_EnumSpreadShape.forCanister` 解析，方形时使用网格分配。 |
| `cone_half_angle` | float | `60` | `stratified_cone` 圆锥半角（度），限制 0～180。 |
| `cone_radial_speed` | float/null（格/tick） | `null` | `stratified_cone` 的最大径向展开速度；null、NaN 或 Infinity 沿用 payload `launch_speed`，有限值取 `max(value, 0)`。配置后只缩放 X/Z 径向分量，向下分量仍由 `launch_speed` 控制，可实现“快速撒开、缓慢下落”。 |
| `cone_axis` | string | `world_down` | 圆锥轴；当前 getter 对任何输入都返回 `world_down`，即世界 Y 负方向。 |
| `radial_distribution` | string | `uniform_area` | 径向分布；当前 getter 对任何输入都返回 `uniform_area`，实现按圆锥内均匀立体角采样。 |
| `cone_azimuth_mode` | string | `golden_angle` | `stratified_cone` 的水平方位来源。`golden_angle` 保持按子体序号推进黄金角的既有行为；`spawn_radial` 使用最终出生位置相对当波释放中心的 X/Z 外向方向。未知值或 null 回退 `golden_angle`，不提供旧键别名。 |
| `azimuth_jitter` | float | `0` | 方位角分层内扰动比例；有限值钳制到 `0..1`，NaN/Infinity 按 `0`。扰动范围为单个方位角分层宽度乘该比例。 |
| `radial_jitter` | float | `0` | 径向分层内扰动比例；有限值钳制到 `0..1`，NaN/Infinity 按 `0`，实际径向扰动还会除以子体总数。 |
| `cloud_direction_jitter` | float（度） | `0` | `cloud_radial_horizontal` 的水平外散方向扰动角；每枚子体在基础云心外向方位上独立抽取 `[-value,+value]`，有限值限制为 `0..90`，NaN/Infinity 按 0，因而不会反向指回云心。 |
| `cloud_speed_jitter` | float | `0` | `cloud_radial_horizontal` 的速度随机比例；每枚子体把外散基速乘以 `[1-value,1+value]` 内的独立均匀随机倍率，有限值限制为 `0..1`，NaN/Infinity 按 0。 |

`cloud_radial_horizontal` 在位置散布全部完成后，使用“最终出生位置 − 当波母弹释放位置”的 X/Z 投影作为基础外向方向，再应用方向与速度扰动。若投影长度接近 0、只有 Y 偏移或包含非有限值，会随机选择一个有限水平单位方向。该模式输出 Y 恒为 0；散布与父弹水平继承形成的 X/Z 会进入部署分量并受 `deployment_horizontal_half_life_ticks` 衰减，纵向运动由 `payloads_velocity`、重力和其他基础弹道外力负责。

```json
"spread": {
  "mode": "cloud_radial_horizontal",
  "cloud_direction_jitter": 15.0,
  "cloud_speed_jitter": 0.25
}
```

`stratified_cone` 始终按 `pelletIndex/pelletCount` 计算圆锥纵向层级；默认 `cone_azimuth_mode=golden_angle` 时继续用黄金角推进水平方位。显式使用 `spawn_radial` 时，改用“最终出生位置 − 当波母弹释放位置”的 X/Z 投影作为水平外向方向，release 云偏移和 payload 位置偏移都会纳入计算；投影接近零或含非有限值时按子体序号的黄金角稳定兜底。`azimuth_jitter` 在两种方位模式下都会继续作用于基础方位，配置为 0 可确保 `spawn_radial` 严格沿云心向外。未配置 `cone_radial_speed` 时，径向和向下分量都使用输入基础速度，输出速度长度与旧行为一致；配置后按下式解耦：

```text
velocity = worldDown × cos(theta) × launch_speed
         + radialDirection × sin(theta) × cone_radial_speed
```

若要求权威弹道斜向下快速散开，应省略 `cone_radial_speed`，让径向与向下分量共同使用 `launch_speed`，保持圆锥速度模长一致。只有确实需要独立 X/Z、Y 尺度时才配置该字段；例如 `launch_speed=0.2`、`cone_radial_speed=1.5` 会产生近水平高速、向下低速的轨迹。若同时配置 `canister_type: 0` 且 `canister_diff > 0`，位置阶段仍会应用 canister 偏移；只需要纯分层圆锥时应保持 `canister_type: 1`（默认）或显式把 `canister_diff` 设为 `0`。

```json
"spread": {
  "mode": "stratified_cone",
  "cone_half_angle": 72.0,
  "cone_axis": "world_down",
  "radial_distribution": "uniform_area",
  "cone_azimuth_mode": "spawn_radial",
  "azimuth_jitter": 0.0,
  "radial_jitter": 0.08
}
```

#### 示例场景

| 场景 | 配置要点                                                                                              |
| --- |---------------------------------------------------------------------------------------------------|
| 子母火箭 / 集束炸弹 | `triggers: ["in_flight"]`，多 `payloads` 指向子战斗部 `weapon_id`，`parent_action: discard_after_release`。 |
| 释放并引爆母弹 | 使用`continue`配合`on_fuse`即可，会继续走弹体引信结算爆炸链                                                           |
| 多级火箭 | 多段 `releases`，不同 `delay_tick`，`parent_action: continue`。                                          |
| 星光导弹分弹头 | 一条 `in_flight`，`release_events: 3`，`payloads` 指向 `rvp:starstreak_dart`，`canister` 散布。             |
| APFSDS 弹托 | `in_flight` + `entity` 载荷（装饰实体）+ `rvp_weapon` 穿甲杆，`discard_on_first_spawn` 仅脱托。                   |
| 撞击抛洒 | `triggers: ["on_impact"]`，`release_events: 1`。                                                    |

```json
"submunition_data": {
  "releases": [
    {
      "triggers": ["in_flight"],
      "delay_tick": 20,
      "interval_tick": 0,
      "release_events": 8,
      "parent_action": "discard_after_release",
      "payloads": [
        {
          "kind": "rvp_weapon",
          "weapon_id": "rvp:cluster_bomblet",
          "count": 1,
          "spread": { "mode": "canister", "canister_diff": 2.5, "canister_distribution": "cluster_center" }
        }
      ]
    }
  ]
}
```

### 1.11 `dispenser_data` 落点布撒物品（任意武器类型）

对应 MCH `DispenseItem` / `DispenseRange`。**导弹、炸弹、火箭、机枪弹等**均可配置；弹体命中或引信引爆时在落点按**形状 + 密集度 + 分布**采样若干格，对有效方块尝试原版 `useOn` / `use`（骨粉、火把、TNT、萤石等），顺序与 `detonate_data.effects_before_explosion` 一致（相对爆炸先后）。`rvp:dispenser` 类型在配置了 `item` 时仅布撒、不走路径爆炸链。可与 `submunition` 组合：母弹飞行中抛洒多枚子弹药，每枚弹体各自执行一次布撒采样。

| 字段 | 说明 |
| --- | --- |
| `item` | 物品 ID，如 `minecraft:bone_meal`。 |
| `damage` | 物品损伤值（可选）。 |
| `place_radius` | 布撒半径（格），默认 1，最大 24。别名：`radius`、`spread_radius`。 |
| `y_radius` | 竖直半高（格）。未写时：`circle`/`square` 为 0（单层）；`cylinder` 为与 `place_radius` 相同；`sphere`/`cube`/`diamond` 使用 `place_radius` 作为竖直范围。 |
| `density` | 密集度 **1–100**。100 = 形状内每个候选格都尝试放置；1 = 仅尝试 **1** 格；中间值为 `round(候选格数 × density/100)`，至少 1 格。 |
| `shape` | 布撒范围形状（见下表）。 |
| `distribution` | 当 `density` &lt; 100 时，从候选格中**选哪些格**的分布（见下表）。`density` = 100 时忽略，全部候选格都会尝试。 |
| `place_on_impact` | 为 `true` 时启用布撒逻辑（默认 `true`）。 |
| `surface_only` | 为 `true` 时仅对**顶面**（上方为空气）的方块尝试放置，适合骨粉/火把/TNT；为 `false` 时对形状内任意非空气格尝试（适合圆柱全高度布灯等）。 |

#### `shape` 形状

| 值 | 说明 |
| --- | --- |
| `circle` | 水平圆盘：`x² + z² ≤ r²`，竖直范围 `|y| ≤ y_radius`。 |
| `square` | 水平正方形：`|x|,|z| ≤ r`，竖直 `|y| ≤ y_radius`。 |
| `sphere` | 球体：`x² + y² + z² ≤ r²`。 |
| `cube` | 轴对齐立方体：`|x|,|y|,|z| ≤ r`。 |
| `cylinder` | 水平圆盘 + 竖直柱：`x² + z² ≤ r²`，`|y| ≤ y_radius`（未写 `y_radius` 时等于 `r`）。 |
| `diamond` | 八面体（曼哈顿距离）：`|x|+|y|+|z| ≤ r`。 |

#### `distribution` 分布（`density` &lt; 100）

| 值 | 说明 |
| --- | --- |
| `uniform` | 在候选格中均匀随机抽取；圆/柱 footprint 为圆盘均匀；`square` footprint 为轴对齐矩形内均匀。 |
| `normal` | 优先靠近落点中心的格（按到原点距离升序取前 N 个）。 |
| `cluster_center` | 同 `normal`，更强中心聚集。 |
| `cluster_edge` | 优先形状外缘的格。 |
| `ring` | 偏好水平半径约 70% 处的环带（适合 `circle`/`cylinder`/`square`）。 |

#### 换弹与物品

`reload.ammo` 写对应物品 ID；载具储物舱需备弹。任意原版/模组物品均可，实际效果取决于该物品的 `useOn` 实现。

#### 示例

**满密度球形骨粉（MI-28 默认绿化）：**

```json
"dispenser_data": {
  "item": "minecraft:bone_meal",
  "place_radius": 4,
  "y_radius": 1,
  "density": 85,
  "shape": "sphere",
  "distribution": "uniform",
  "place_on_impact": true,
  "surface_only": true
}
```

**单点测试（密集度 1）：**

```json
"dispenser_data": {
  "item": "minecraft:glowstone",
  "place_radius": 6,
  "density": 1,
  "shape": "circle",
  "distribution": "uniform",
  "surface_only": true
}
```

**环带分布：**

```json
"dispenser_data": {
  "item": "minecraft:redstone_block",
  "place_radius": 5,
  "density": 35,
  "shape": "circle",
  "distribution": "ring",
  "surface_only": true
}
```

布撒武器请关闭爆炸：`"detonate_data": { "explosion_data": { "explode": false } }`。引信建议 `"detonate_on_life_end": true`，以便空中引信到期也能布撒。

**落点解析（0.5.21+）**：引信在空中到期时，会先向下射线/柱扫描找到地表锚点，再按水平偏移 + `surface_only` 逐列找可放置顶面；火把/打火石/方块类物品在 `useOn` 失败时会走直接放置回退。`surface_only: true` 时形状自动投影为水平 footprint（不再在弹体高度的一层空气上采样）。

### 1.12 `misc_data` 杂项

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `missile_name_on_hud` | 导弹在 HUD 上显示的名称，按距目标距离区间映射（距离单位：米）。 | `Map<RVP_Range<Float>, String>` | `{"[[0,500]]":"MSL","[[500,inf]]":null}` |
| `missile_name_on_radar` | 导弹在雷达上的显示名称，按距离区间映射。 | `Map<RVP_Range<Float>, String>` | `{"[[20,inf]]":"MSL"}` |
| `signal_intensity_factor_on_radar` | 导弹在雷达上的信号强度倍率，按距离区间映射；未命中区间或非法值按 `1.0`。 | `Map<RVP_Range<Float>, Float>` | `{"[[0,inf]]":1.0}` |
| `artillery_map` | 是否作为火炮地图弹药（在战术地图上绘制弹道/落点）。 | `boolean` | `false` |

```json
"misc_data": {
  "missile_name_on_hud": {
    "[[0,500]]": "MSL",
    "[[500,inf]]": null
  },
  "missile_name_on_radar": {
    "[[20,inf]]": "MSL"
  },
  "signal_intensity_factor_on_radar": {
    "[[0,inf]]": 1.0
  },
  "artillery_map": false
}
```

### 1.13 `guidance_data` 制导数据模型

`guidance_data` 采用**直接对应数据模型**的写法，不再使用旧的 `stages[]`、`sources[]`、`seeker_data`、`steering_data`、`human_in_the_loop` 那一整套分段/复合 schema（旧写法仅在加载期被归一化接受，见「7. 旧写法迁移对照」）。

本文档以下表格中的字段名使用 **JSON 写法**（全小写 + 下划线）。Java 类中的对应模型分别为：

- `RVP_GuidanceData`（主模型）
- `RVP_GuidanceDataSACLOS`（`guidance_type: SACLOS`）
- `RVP_GuidanceDataHITL`（TV / HITL_TV / HITL_CLOS_TV）
- `RVP_GuidanceDataGPS`（`guidance_type: GPS`）
- `RVP_GuidanceDataARM`（`guidance_type: ARM`）
- `RVP_TerminalGuidanceData`（`terminal_guidance`）

**角度约定：**

- `max_lock_angle` 是**完整 FOV**，运行时会自动除以二，转换为单侧半角。
- `max_guidance_angle` 和 `max_off_axis_lock_angle` 是相对轴线的**单侧角度**，运行时不再除以二。

#### `guidance_data` 公用字段

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `guidance_type` | 主制导类型。公开值见下方表格。 | `RVP_EnumGuidanceType` | `NONE` |
| `guidance_tick_range` | 制导时间范围；`null` 表示立即开始且永不结束。 | `RVP_Range<Integer>` | `null` |
| `guidance_target_distance_range` | 弹药跟踪时，与制导目标点/记忆点的距离范围（格）。 | `RVP_Range<Float>` | `null` |
| `guidance_altitude_range` | 弹药跟踪时，与制导目标点/记忆点的离地高度范围（格）。支持并集区间。 | `RVP_Range<Float>` | `null` |
| `lock_target_distance_range` | 载具火控锁定时，与制导目标点/记忆点的距离范围（格）。 | `RVP_Range<Float>` | `null` |
| `lock_altitude_range` | 载具火控锁定时，与制导目标点/记忆点的离地高度范围（格）。支持并集区间。 | `RVP_Range<Float>` | `null` |
| `enable_ir_hmd` | 是否启用红外弹头瞄。当前仅红外系弹药使用。 | `boolean` | `true` |
| `max_guidance_angle` | 发射后导引头最大跟踪角（单侧角度，度）。 | `int` | `60` |
| `scan_interval_tick` | 发射后导引头自主扫描间隔。主要用于 `ARH/AIR/ARM`。`null` 表示不主动扫描。 | `Integer` | `null` |
| `max_lock_angle` | 导引头搜索视场角（完整 FOV，度）。用于“开机但未锁定”的扫描阶段。 | `int` | `5` |
| `max_off_axis_lock_angle` | 锁定后允许保持的最大离轴角（单侧角度，度）。 | `int` | `60` |
| `off_axis_stacks_with_station_rotation` | 头瞄离轴角是否与**武器站旋转叠加**。`false`（默认）：离轴锥以武器站**中立安装轴** `worldVec(0,0)` 为基准，忽略武器站自身 `xRot/yRot` 伺服旋转，炮塔转动**不**扩大 IR 锁定覆盖。`true`：离轴锥以武器站**当前朝向** `worldVec()`（含 `xRot/yRot`）为基准，离轴范围与武器站已转过的角度**叠加**——炮塔转到哪，离轴锥中心跟到哪。用于地对空红外导弹。注意 HUD 限位圈始终按 `true` 的基准绘制，故旋转炮塔车上建议开启以保持显示与判定一致。 | `boolean` | `false` |
| `predict_target_pos` | 是否启用比例制导/预测拦截。 | `boolean` | `false` |
| `predict_target_pos_gain` | 比例制导增益系数（收敛速度）。 | `float` | `3.0` |
| `predict_target_pos_start_tick` | 预测制导生效的起始 tick（发射后多久才开始预测）。 | `int` | `10` |
| `max_lateral_accel` | 最大横向加速度限制（度/秒² 量级）；`0` 表示不限制。 | `float` | `0` |
| `top_attack_height` | 攻顶最大高度；`null` 为不启用，可填负数（如潜射武器）。 | `Float` | `null` |
| `cruise_start_tick` | 多少 tick 后进入巡航段。激光架束、人在回路、指令线类通常不使用。 | `Integer` | `null` |
| `cruise_end_horizontal_dist` | 距目标水平距离小于该值后退出巡航，进入末端。 | `float` | `10` |
| `cruise_gravity_scale` | 巡航段重力系数。 | `float` | `1.0` |
| `cruise_leveling_factor` | 巡航段自动改平强度。 | `float` | `0.15` |
| `lock_angle_gate` | 火控锁定角度门。key 为载机距目标距离区间，value 为允许的目标运动方向夹角区间。 | `Map<RVP_Range<Float>, RVP_Range<Float>>` | `null` |
| `guidance_angle_gate` | 弹药跟踪角度门。key 为弹药距目标距离区间，value 为允许的目标运动方向夹角区间。 | `Map<RVP_Range<Float>, RVP_Range<Float>>` | `null` |
| `angle_gate_lock_out_tick` | 超出角度门后，经过多少 tick 才真正脱锁。 | `int` | `20` |
| `active_radar_activation_range` | 主动类导引头开机距离。适用于 `ARH/AIR/ARM`。 | `int` | `256` |
| `enable_inertial_guidance` | 是否启用惯性制导。脱锁后仍朝最后记忆点前进。 | `boolean` | `false` |
| `terminal_guidance` | 末端制导配置。`null` 表示不启用。 | `RVP_TerminalGuidanceData` | `null` |

**子类型字段约束**（由 `RVP_GuidanceDataAdapter` 校验）：

- `gps_spread_radius` 仅 `GPS` 可用。
- `radiation_pulse_memory_tick` / `arm_memory_tick` / `arm_locked_emitter_bonus` 仅 `ARM` 可用。
- `hitl_*` / `signal_source` 仅 TV / HITL_TV / HITL_CLOS_TV 可用。
- `semi_correction_*` 仅 `SACLOS` 可用。
- 写了以上子类型字段但未写 `guidance_type`（或类型不匹配）会在加载时报错。

#### 高度范围与头瞄 HUD 的约定

- `guidance_altitude_range` / `lock_altitude_range` 使用 `RVP_Range<Float>`，支持并集区间。
- 旧版“正值代表对空、负值代表对地”的 `lock_min_height` 思路，已由范围表达式接管。
- 当前 HUD 判定依然保留“低空/地面目标”与“空中目标”的区分习惯：
  - 类似 `[[30,inf]]` 的范围可视为空对空导引头逻辑。
  - 类似 `[[inf,10]]` 或低空区间可视为空对地/近地导引头逻辑。

#### `guidance_type` 公开值

| 枚举值 | 说明 |
| --- | --- |
| `NONE` | 无制导。 |
| `MCLOS` | 人工指令线制导。 |
| `SALH` | 半主动激光制导（照射点跟踪）。 |
| `SACLOS` | 视线指令制导。启用 `semi_correction_*` 后为“半自动修正”模型（模拟射手遥测 + 弹性修正），见下方 SACLOS 小节。 |
| `LBR` | 预留。 |
| `LH` | 激光点制导（载具照射点）。 |
| `TV` | 电视寻的（人在回路）。 |
| `HITL_TV` | 人在回路电视制导。 |
| `HITL_CLOS_TV` | 人在回路指令线电视制导。 |
| `ATV` | 主动电视制导（末端）。 |
| `IR` | 红外制导。 |
| `AIR` | 主动红外制导（末端）。 |
| `SARH` | 半主动雷达制导。 |
| `ARH` | 主动雷达制导。 |
| `GPS` | GPS/坐标制导。 |
| `ARM` | 反辐射制导。 |

> `IOG` 是历史枚举值，**新 schema 不公开**，`guidance_type: IOG` 会在加载时报错。

#### `RVP_GuidanceDataSACLOS`（`SACLOS`）

SACLOS 采用“射手瞄准线 + 半自动修正”模型，可选启用弹性修正：

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `semi_correction_enabled` | 是否启用半自动修正（模拟射手遥测修正的平滑追踪）。 | `boolean` | `false` |
| `semi_correction_stiffness` | 修正刚度：越大弹体越快地压向瞄准线。 | `float` | `0.05` |
| `semi_correction_damping` | 修正阻尼：抑制震荡。 | `float` | `0.05` |
| `semi_correction_wobble` | 弹体抖动幅度（模拟指令噪声/摆动）。 | `float` | `0.5` |

#### `RVP_GuidanceDataHITL`（TV / HITL_TV / HITL_CLOS_TV）

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `hitl_max_turn_deg_per_tick` | 导引头每 tick 最大转动角度，类似方向机速度。 | `int` | `2` |
| `signal_source` | 制导信号源：`FIBER` / `RADIO`。仅这两个值有效，非 `FIBER` 一律按 `RADIO` 处理。无线电可被方块遮挡，光纤不可。 | `String` | `RADIO` |
| `hitl_max_control_dist` | 最大控制距离（格）。 | `int` | `600` |
| `hitl_max_control_tick` | 最大控制时长（tick）。 | `int` | `200` |
| `hitl_max_look_offset` | HITL 视角最大偏转角度。 | `int` | `30` |
| `hitl_video_modes` | 可用画面模式（可写多个，玩家可循环切换）：`COLOR` / `MONO`（或 `BW`、`BLACK_WHITE`、`BLACKWHITE`、`MONOCHROME`）/ `THERMAL`（或 `IR`）。列表第一个有效模式为初始画面；无法识别的值被忽略，全部无效时回退为彩色画面。 | `List<String>` | `["MONO"]` |
| `hitl_right_click_detonate` | 开启后人在回路视角下鼠标右键从“退出视角”变为“**提前引爆导弹**”。 | `boolean` | `false` |

#### `RVP_GuidanceDataGPS`（`GPS`）

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `gps_spread_radius` | GPS 打击散布半径（格），使用正态分布。 | `float` | `0` |

#### `RVP_GuidanceDataARM`（`ARM`）

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `radiation_pulse_memory_tick` | 对雷达辐射脉冲的短时记忆时长。 | `int` | `30` |
| `arm_memory_tick` | 完全失去辐射源后，对最后有效辐射源的持续记忆时长。 | `int` | `60` |
| `arm_locked_emitter_bonus` | 对已被火控锁定/预选辐射源的优先级加权。值越高，越不容易被视场内其他辐射源抢走目标。 | `float` | `1.0` |

#### `terminal_guidance`（`RVP_TerminalGuidanceData`）

末端制导数据写在 `guidance_data.terminal_guidance` 中。

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `guidance_type` | 末端制导类型。只允许 `NONE/ATV/AIR/ARH/ARM/IR`。 | `RVP_EnumGuidanceType` | `NONE` |
| `active_radar_activation_range` | 末端主动类导引头开机距离。 | `int` | `256` |
| `max_lock_angle` | 末端导引头搜索视场角（完整 FOV）。 | `int` | `5` |
| `guidance_target_distance_range` | 末端制导目标距离范围。 | `RVP_Range<Float>` | `null` |
| `guidance_start_tick` | 多少 tick 后切入末端制导。 | `Integer` | `null` |
| `guidance_start_dist` | 距目标多少格后切入末端制导。 | `Float` | `null` |
| `guidance_start_horizontal_dist` | 距目标水平距离多少格后切入末端制导。 | `Float` | `null` |
| `guidance_altitude_range` | 末端制导目标离地高度范围。 | `RVP_Range<Float>` | `null` |
| `max_guidance_angle` | 末端导引头最大跟踪角（单侧角度）。 | `int` | `60` |
| `scan_interval_tick` | 末端导引头扫描间隔。 | `Integer` | `null` |
| `predict_target_pos` | 末端是否启用比例制导/预测拦截。 | `boolean` | `false` |
| `top_attack_height` | 末端攻顶高度。 | `Float` | `null` |
| `guidance_angle_gate` | 末端跟踪角度门。 | `Map<RVP_Range<Float>, RVP_Range<Float>>` | `null` |
| `angle_gate_lock_out_tick` | 末端超角度门后延迟脱锁 tick。 | `int` | `20` |
| `enable_inertial_guidance` | 末端是否允许惯性制导。 | `boolean` | `false` |

#### `interference_data`（`RVP_InterferenceData`，仅对 IR/AIR/SARH/ARH 生效）

干扰物干扰数据写在 `guidance_data.interference_data` 中。

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `seeker_jam_limit` | 导引头视场内极限干扰物数量，超过后导弹脱锁并飞向最近干扰物。 | `int` | `8` |
| `seeker_fov_shrink_factor` | 跟踪时导引头 fov 倍率，检测 fov = `max_lock_angle × 此值`（完整 FOV）。 | `float` | `1.0` |
| `seeker_shut_off_time` | 失去制导后导引头关闭时长（tick），关闭结束后重启主动搜索复锁；`null` 表示失锁后立即恢复搜索。 | `Integer` | `null` |
| `chaff_resistance` | 导引头对箔条目标的锁定抗性（0~1）：ARH/AIR 开启导引头后可锁箔条，但按此值施加评分罚分（越大优先级越低，非完全不可锁）。默认 `0.5`，具备相当的抗箔条能力。 | `float` | `0.5` |

#### PRESET 三段式弹道（弹道导弹巡航）

弹道导弹（通常搭配 `guidance_type: GPS`）可启用**上升 → 巡航 → 俯冲**三段式弹道。**总开关为 `preset_cruise_altitude`**：`> 0` 即启用，`0`（默认）禁用。以下字段均写在 `guidance_data` 顶层。

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `preset_cruise_altitude` | 巡航高度（相对发射点 Y，格）。**`> 0` 启用 PRESET 三段式弹道**，`0` 禁用。 | `float` | `0` |
| `preset_max_ascent_lead` | 上升段前伸量上限（格），实际取 `min(值, 25% × 水平距离)`。 | `float` | `64` |
| `preset_ascent_radius` | 上升段完成判定半径（格）。 | `float` | `24` |
| `preset_dive_radius` | 俯冲段最小启动水平距离（格）。 | `float` | `24` |
| `preset_dive_altitude_factor` | 俯冲距离 = 高度差 × 该因子。 | `float` | `0.75` |
| `preset_dive_lead_factor` | 俯冲距离 = 近似转弯半径 × 该因子。 | `float` | `1.5` |
| `preset_cruise_altitude_gain` | 巡航段高度闭环 P 增益（越大收敛越快，过大易震荡）。 | `float` | `0.002` |
| `preset_cruise_vertical_damping` | 巡航段高度闭环 D 阻尼（抑制高度震荡）。 | `float` | `0.05` |
| `preset_cruise_max_vertical_component` | 垂直分量占速率的比例上限（0~1），限制爬升/俯冲的陡峭程度。 | `float` | `0.5` |
| `preset_tactical_maneuver_amplitude` | 弹道中段战术机动（横向蛇形规避摆动）幅度（格）；`0` = 关闭。 | `float` | `0` |

> 除 `preset_cruise_altitude` 外，以上参数**仅在 PRESET 启用**（巡航高度 > 0）时生效。全部字段取负值时按 `0` 处理（getter 统一 `Math.max(x, 0)`）。

#### 示例

GPS 滑翔炸弹 + 末端红外：

```json
"guidance_data": {
  "guidance_type": "GPS",
  "guidance_tick_range": "[[0,inf]]",
  "gps_spread_radius": 1.0,
  "cruise_start_tick": 20,
  "cruise_end_horizontal_dist": 80,
  "cruise_gravity_scale": 0.5,
  "cruise_leveling_factor": 0.15,
  "terminal_guidance": {
    "guidance_type": "IR",
    "guidance_start_dist": 100,
    "max_lock_angle": 40,
    "max_guidance_angle": 60,
    "scan_interval_tick": 2
  }
}
```

SACLOS 反坦克导弹（半自动修正）：

```json
"guidance_data": {
  "guidance_type": "SACLOS",
  "semi_correction_enabled": true,
  "semi_correction_stiffness": 0.06,
  "semi_correction_damping": 0.04,
  "semi_correction_wobble": 0.3,
  "max_guidance_angle": 30,
  "max_off_axis_lock_angle": 45
}
```

人在回路 TV 导弹：

```json
"guidance_data": {
  "guidance_type": "HITL_CLOS_TV",
  "hitl_max_turn_deg_per_tick": 2,
  "signal_source": "RADIO",
  "hitl_max_control_dist": 1200,
  "hitl_max_control_tick": 400,
  "hitl_max_look_offset": 30,
  "hitl_video_modes": ["MONO", "THERMAL"]
}
```

反辐射导弹：

```json
"guidance_data": {
  "guidance_type": "ARM",
  "max_lock_angle": 35,
  "max_guidance_angle": 60,
  "scan_interval_tick": 2,
  "active_radar_activation_range": 256,
  "radiation_pulse_memory_tick": 30,
  "arm_memory_tick": 60,
  "arm_locked_emitter_bonus": 1.0
}
```

### 1.14 `laser_data`（`rvp:laser`）

| 字段 | 说明 |
| --- | --- |
| `range` | 激光有效射程（格），默认 `512`；亦用作目标指示吊舱的方块标记射线长度。 |
| `visual_data` | 客户端光束外观（见下表）。 |

#### `laser_data.visual_data` 激光光束（客户端）

| 字段 | 说明 |
| --- | --- |
| `color` | RGBA 0–255。 |
| `width` | 光束宽度（格），默认 `0.2`。 |
| `duration_tick` | 停火后残留 tick；连发时每次 pulse 刷新。默认 `20`。 |
| `pulsate` | 是否脉动宽度。默认 `true`。 |
| `render_start_distance` | 炮口沿瞄准方向外推最小距离（与几何裁剪配合，避免穿机体）。 |
| `segment_length` | 分段渲染段长（格），最长合并为 64 段。默认 `0.4`。 |

光束终点由射线检测决定，不穿墙；观察者视角通过深度测试遮挡。

### 1.15 `targeting_pod_data`（`rvp:targetingpod`）

目标指示吊舱：对实体/方块打标记，可写入 GPS 目标点供 GPS/SACLOS 类武器使用。

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `mode` | 标记模式：`entity`（仅实体）、`block`（仅方块）、`both`。 | `entity` |
| `spot_range` | 实体标记扫描距离（格）。 | `200` |
| `spot_angle` | 实体标记锥形半角（度）。 | `15` |
| `mark_duration` | 标记持续时间（tick）。 | `600` |
| `target_filter` | 实体标记的目标类型过滤，可含 `vehicle` / `player` / `living`。 | `["vehicle"]` |
| `block_range` | 方块标记射线最大距离（格）；`null` 时使用 `laser_data.range`。 | `null` |
| `write_gps_target` | 方块标记命中时是否同时写入 GPS 目标点（供 GPS/SACLOS 武器接引）。 | `true` |
| `team_share` | 标记是否共享给同队玩家。 | `true` |

实体标记沿瞄准方向扫描锥形区域（`spot_range` × `spot_angle` 半角）；方块标记走直线射线，命中后生成标记点并（可选）写入 GPS 目标。

#### `rvp:targetingpod` 示例

```json
{
  "type": "rvp:targetingpod",
  "laser_data": { "range": 512 },
  "targeting_pod_data": {
    "mode": "both",
    "spot_range": 200,
    "spot_angle": 15,
    "mark_duration": 600,
    "target_filter": ["vehicle", "player"],
    "block_range": 400,
    "write_gps_target": true,
    "team_share": true
  }
}
```

### 1.16 `virtual_midcourse_data` 虚拟中段弹道

```json
{
  "virtual_midcourse_data": {
    "enabled": true,
    "entry_min_flight_tick": 100,
    "entry_min_distance_from_launch": 800.0,
    "entry_min_target_distance": 1600.0,
    "restore_target_distance": 768.0,
    "restore_lead_tick": 60,
    "restore_ticket_radius": 1,
    "restore_wait_timeout_tick": 200,
    "virtual_update_interval_tick": 1,
    "max_virtual_flight_tick": 12000,
    "cruise_altitude": 320.0,
    "target_update_mode": "FIXED_SNAPSHOT",
    "on_restore_timeout": "DISCARD"
  }
}
```

字段如下。每个 `@SerializedName` 字段按项目约束编写与 `RVP_WeaponData` 同级的 JavaDoc，说明单位、默认值和生效条件。

| 字段 | 类型 | 单位/默认值 | 说明 |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | 是否允许该武器进入虚拟中段；必须显式开启 |
| `entry_min_flight_tick` | int | tick，`0` | 达到该有效飞行 Tick 后才允许虚拟化；区块等待 Tick 不计入 |
| `entry_min_distance_from_launch` | float | 格，`800` | 离发射点足够远后才进入虚拟态，保留可观察的真实助推段 |
| `entry_min_target_distance` | float | 格，`1600` | 距目标仍足够远时才值得虚拟化，防止短程导弹频繁切换 |
| `restore_target_distance` | float | 格，`768` | 距末段目标小于该值时开始恢复准备 |
| `restore_lead_tick` | int | tick，`2` | 根据当前速度提前计算恢复点的时间余量 |
| `restore_ticket_radius` | int | 区块半径，`1` | 恢复点周围 Ticket 半径，建议限制为 0～2 |
| `restore_wait_timeout_tick` | int | tick，`200` | 恢复区块长期无法 ready 时的最大等待时间 |
| `virtual_update_interval_tick` | int | tick，`1` | 虚拟积分间隔；第一版固定钳制为 1，后续才允许批量积分 |
| `max_virtual_flight_tick` | int | tick，`12000` | 单次虚拟态最长持续时间，不得超过弹体剩余 `life` |
| `cruise_altitude` | double/null | 世界 Y，`null` | 可选虚拟巡航高度；配置后在目标仍可达的前提下由高度闭环跟踪，不可达时取沿命中路线能够接近的高度，为空时以当前虚拟高度为闭环基准 |
| `target_update_mode` | enum | `FIXED_SNAPSHOT` | `FIXED_SNAPSHOT` 或第二阶段的 `DATALINK` |
| `on_restore_timeout` | enum | `DISCARD` | 第一版只建议 `DISCARD`；不得在未加载目标处直接爆炸 |

校验规则：

- `restore_target_distance` 必须小于 `entry_min_target_distance`，建议至少留出 256 格迟滞区间。
- `restore_lead_tick × 当前水平速度` 与 `restore_target_distance` 取较大值作为实际恢复触发距离。
- `restore_ticket_radius` 最大为 2，避免单枚导弹恢复时请求过多区块。
- `max_virtual_flight_tick <= weapon life`；运行时最终使用两者较小值。
- 虚拟积分从 `projectile_data` 读取 `rvp_maxg` 与当前飞行 Tick 对应的 `turning_factor`；`rvp_maxg` 已配置时优先，未配置时使用 `turning_factor`，区间未命中回退 0.5。
- 虚拟积分不读取 `guidance_data.cruise_leveling_factor` 或 `max_turn_degree_per_tick`。
- 不添加旧键别名、`legacy*` 或迁移逻辑；历史 JSON 由 `scripts/` 批量修改。

`rvp_maxg` 与 `turning_factor` 是实体态、虚拟态共用的弹体机动契约，不读取本体 `max_g`。两种状态共用相同优先级与数学实现，避免虚拟化或恢复时出现转向能力跳变。以后替换积分方法时，新实现必须显式声明参数和状态版本，不静默改变在途记录语义。

---

## 2 载具 JSON 扩展字段

以下字段直接写在**载具 JSON 顶层**（与 `parts`、`weapons` 等同级），由 RVP 的 `VehicleDataManagerMixin` 等读取。多数为客户端/服务端独立读取，未在列表特别说明时均属于可选字段。

### 2.1 显示与命名

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `ui_preset` | UI 预设 ID（字符串），指向 UI 预设文件里的预设名；决定载具 HUD 布局与样式。详见 §4。 | 空 |
| `nctr_name` | NCTR（敌我识别）显示名称字符串。 | `?` |
| `show_skeleton` | 观瞄时是否显示骨骼俯视图。 | `true` |
| `hide_passenger` | 是否隐藏乘客渲染（由 `RVP_VehicleHitboxFactorManager` 读取）。 | `false` |

### 2.2 武器槽位过热（写在武器条目内）

过热字段**不是**写在载具顶层，而是写在 `parts[].weapons[]` 的**武器对象条目**内（与 `id`、`secondary` 等同级），按「部件 id + 主/副通道 + 槽位索引」匹配（解析见 `RVP_VehicleWeaponHeatConfigCache`）：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `vehicle_max_heat_count` | 该武器槽位的过热上限；`> 0` 时启用该槽位过热，热量达到上限后禁止继续开火，直到冷却到上限以下。 | `0` |
| `vehicle_heat_count` | 初始热量值。 | `0` |
| `vehicle_overheat_extra_heat` | 过热惩罚热量：开火后达到或超过上限时额外追加，模拟过热锁死后需要更久冷却。 | `30` |

```json
"weapons": [
  {
    "id": "rvp:machinegun",
    "vehicle_max_heat_count": 100,
    "vehicle_heat_count": 0,
    "vehicle_overheat_extra_heat": 30
  }
]
```

> 注意：这是**武器槽位级**过热，与武器 JSON 内 `fire_data.heat_count` / `max_heat_count`（武器自身过热）相互独立；武器射速由本体字段 `shoot_interval`（武器 JSON 顶层）控制。

### 2.3 碰撞箱受击倍率与 ERA（`hitbox_*`）

由 `RVP_VehicleHitboxFactorManager` 读取。基于 `structure_model` 的骨骼 OBB 做射线命中判定，对命中骨骼应用伤害倍率或 ERA 规则：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `structure_model` | 结构模型资源 id（与 §2.8 物理/结构共用同一键）。未配置时退化为仅用默认倍率。 | `null` |
| `hitbox_damage_factor_default` | 未命中任何已配置骨骼时的默认受击倍率。 | `1.0` |
| `hitbox_damage_factor` | 对象：`骨骼名 → 倍率`。命中该骨骼时使用对应倍率。 | 空对象 |
| `hitbox_era` | 对象：`骨骼名 → 数字或对象`。数字为伤害倍率（简化）；对象见下表。 | 空对象 |
| `hitbox_display_name` | 对象：`骨骼名 → 显示名`，用于命中调试消息（HBX）。 | 空对象 |
| `core_distance_scale_multiplier` | 核心距离缩放倍率。 | `1.0` |

#### `hitbox_era` 值对象

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `damage_factor` | 命中该 ERA 骨骼时的伤害倍率。 | `1.0` |
| `min_trigger_damage` | 单次伤害需**大于**该值才触发 ERA 阻挡（消耗该骨骼并播放特效）。 | `inf`（不触发） |
| `explosion` | 触发时的爆炸特效规模系数（影响粒子/音效强度）。 | `0` |

```json
"structure_model": "ywzj_rvp:models/vehicle/apc.obj",
"hitbox_damage_factor_default": 1.0,
"hitbox_damage_factor": {
  "turret": 1.2,
  "engine": 1.5
},
"hitbox_era": {
  "hull_front": 0.25,
  "turret_side": { "damage_factor": 0.4, "min_trigger_damage": 20, "explosion": 1.2 }
},
"hitbox_display_name": {
  "hull_front": "首上装甲"
}
```

> 机制说明：直击命中 ERA 骨骼且伤害超过 `min_trigger_damage` 时消耗该骨骼（同骨骼冷却期内不再触发）并播放松散特效；附近爆炸（非直击）按爆炸半径阈值表按百分比破坏 ERA（≤5 格不破坏、5–8 格 10%、8–12 格 25%、12–18 格 50%、>18 格全毁，直击至少 1 块保底）。配置过 `physics_info.physics_only_bone(s)` 时，若命中只落在“仅物理”碰撞体上则本次伤害判定被禁用。

### 2.4 主动防护系统（`rvp_aps`）

载具顶层 `rvp_aps` 对象，启用后自动探测并拦截来袭射弹。**启用条件**：`enabled: true` 且 `ammo_max > 0` 且 `spawn_part_id` 非空。

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `enabled` | 总开关。 | `false` |
| `ammo_max` | 拦截弹药上限（消耗车体弹药计数）。 | `0` |
| `reload_one_tick` | 每发拦截弹药补充耗时（tick）。 | `600` |
| `cooldown_tick` | 连续拦截之间的冷却（tick）。 | `20` |
| `intercept_delay_tick` | 探测到目标到实际拦截的延迟（tick）。 | `10` |
| `scan_interval_tick` | 探测扫描间隔（tick）。 | `1` |
| `detect_radius` | 探测半径（格）。 | `32.0` |
| `intercept_radius` | 拦截生效半径（格）。 | `8.0` |
| `projectile_speed_min` | 可拦截射弹最小速度。 | `1.0` |
| `projectile_speed_max` | 可拦截射弹最大速度。 | `80.0` |
| `animation_part_ids` | 拦截动画部件 id 列表（旋转/开盖动画）。 | `[]` |
| `spawn_part_id` | 拦截弹生成部件 id（必填；缺省时取 `animation_part_ids[0]`）。 | `""` |
| `exclude_owner_projectile` | 是否不拦截本车自己发射的射弹。 | `true` |

```json
"rvp_aps": {
  "enabled": true,
  "ammo_max": 8,
  "reload_one_tick": 600,
  "cooldown_tick": 20,
  "intercept_delay_tick": 10,
  "scan_interval_tick": 1,
  "detect_radius": 32.0,
  "intercept_radius": 8.0,
  "projectile_speed_min": 1.0,
  "projectile_speed_max": 80.0,
  "animation_part_ids": ["aps_turret"],
  "spawn_part_id": "aps_muzzle",
  "exclude_owner_projectile": true
}
```

### 2.5 自动盘旋（`rvp_loiter_*`）

通用载具自动盘旋配置（任何飞行器可用，独立于可部署 UAV 系统）。`rvp_loiter_enabled: true` 即启用，其余全部可选：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `rvp_loiter_enabled` | 总开关。 | `false` |
| `rvp_loiter_radius` | 盘旋半径（格）。 | `120.0` |
| `rvp_loiter_altitude_offset` | 盘旋高度相对初始高度偏移（格）。 | `40.0` |
| `rvp_loiter_terrain_clearance` | 距地形最低安全高度（格）。 | `30.0` |
| `rvp_loiter_min_safe_altitude` | 全局最低安全高度（格）。 | `80.0` |
| `rvp_loiter_fixed_wing_min_bank` | 固定翼最小坡度（度），决定固定翼最小盘旋半径。 | `30.0` |
| `rvp_loiter_auto_on_takeoff` | 起飞后自动进入盘旋（固定翼同时自动启动引擎并满油门）。 | `false` |
| `rvp_loiter_bank` | 盘旋坡度（度）。 | `25.0` |
| `rvp_loiter_direction` | 盘旋方向：`right`（默认）或 `left`。 | `right` |

> 爬升/转场/进场的阶段超时、地形采样间隔与范围、震荡半径扩张等**内部算法参数**已固化为常量（见源码 `RVP_UavLoiterTickService`），不再暴露为 JSON 可配置项。

### 2.6 可部署 UAV（`deployable_uav_*`）

把指定载具作为“可部署 UAV”从本车释放（需要 `deployable_uav_vehicle_id` 指向实际载具 id）。`deployable_uav_enabled: true` 且 `deployable_uav_vehicle_id` 有效才启用：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `deployable_uav_enabled` | 总开关。 | `false` |
| `deployable_uav_vehicle_id` | 被部署的 UAV 载具资源 id（必填）。 | 空 |
| `deployable_uav_role` | UAV 角色标记：任意字符串（无固定枚举），仅作标记/调试显示，默认 `uav`。 | `uav` |
| `deployable_uav_spawn_offset` | 生成位置偏移：对象 `{x,y,z}` 或 `[x,y,z]`。 | `[0,0,0]` |
| `deployable_uav_spawn_yaw_mode` | 生成朝向模式：`parent`（默认，朝母机当前朝向）/ `operator_look`（朝操作员视线方向）。 | `parent` |
| `deployable_uav_single_instance` | 是否同时只允许一架。 | `true` |
| `deployable_uav_allow_control_switch` | 是否允许切换到 UAV 控制视角。 | `true` |
| `deployable_uav_auto_link_datalink` | 自动建立数据链。 | `true` |
| `deployable_uav_redeploy_cooldown_tick` | 回收后再次部署的冷却（tick）。 | `0` |
| `deployable_uav_auto_loiter_on_switch_back` | 切回本车时 UAV 自动进入盘旋。 | `true` |
| `deployable_uav_initial_speed` | 部署初始速度。 | `0.0` |

### 2.7 起落架自动收放（`rvp_auto_landing_gear*`）

顶层 `rvp_auto_landing_gear: true` 即启用：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `rvp_auto_landing_gear` | 总开关。 | `false` |
| `rvp_auto_landing_gear_retract_speed` | 收起速度阈值（km/h）：速度超限且离地高于 `retract_height` 时自动收起。 | `100` |
| `rvp_auto_landing_gear_deploy_speed` | 放下速度阈值（km/h）。 | `50` |
| `rvp_auto_landing_gear_deploy_height` | 放下判定高度（格）：速度低于 `deploy_speed` 且离地低于该高度时自动放下。 | `25` |
| `rvp_auto_landing_gear_retract_height` | 收起判定高度（格）：离地高于该高度才允许自动收起，防止低空误收。 | `50` |

**手动收放覆盖**：玩家一旦手动操作起落架（收放按键），该载具即进入**手动覆盖模式**，自动收放逻辑完全停用（覆盖式，最高优先级），直到该载具销毁。

### 2.8 物理与结构扩展

由 `RVP_VehicleExtendedConfigManager` 读取：

```json
"physics_info": {
  "ground_contact_part_ids": ["wheel_l", "wheel_r"],
  "physics_only_bone": "main_body",
  "physics_only_bones": ["b1", "b2"]
},
"structure_model": "yourmod:models/vehicle/fighter.obj"
```

| 字段 | 说明 |
| --- | --- |
| `physics_info.ground_contact_part_ids` | 视为“地面接触”的部件 id 列表（用于离地判定）。 |
| `physics_info.physics_only_bone` | 单根“仅物理”骨骼名（与 `physics_only_bones` 合并读取）。 |
| `physics_info.physics_only_bones` | 多根“仅物理”骨骼列表。配置后命中只落在这些骨骼上时，受击伤害判定被禁用（见 §2.3）。这些骨骼的碰撞盒会被移出 `vehicleCubeOBBs`，因此不显示在碰撞箱俯视图 UI 中；F3+B 调试渲染中会以**灰色线框**单独标出。 |
| `structure_model` | 结构模型资源 id，同时被物理系统与 hitbox/ERA（§2.3）使用。 |

#### 武器槽位分组（写在武器条目内）

以下两个字段写在 `parts[].weapons[]` 的武器对象条目内，用于把多个槽位组合成一组（同一挂点多武器）：

| 字段 | 说明 |
| --- | --- |
| `modding_only_multi` | `true` 时该槽位为“仅 mod 使用”的多联挂架槽；要求条目内 `ids` 数组至少 2 个武器。 |
| `merge_into_previous_slot` | `true` 时该槽位合并到上一槽位（不占独立循环位）。 |

```json
"weapons": [
  {
    "ids": ["rvp:missile", "rvp:missile"],
    "modding_only_multi": true
  },
  {
    "ids": ["rvp:rocket"],
    "merge_into_previous_slot": true
  }
]
```

> 联动规则：若槽位 A 为 `modding_only_multi` 且槽位 B（A 的下一个索引）为 `merge_into_previous_slot`，则 A、B 构成“分组槽位”（共享武器循环）；仅 A 为 modding 而 B 未合并时，A 在运行时武器循环中被跳过。

### 2.9 自定义挂架（`rvp_custom_mounts`）

客户端渲染自定义挂架/导弹模型，数组形式，见源码 `RVP_CustomMountConfig`：

```json
"rvp_custom_mounts": [
  {
    "part_unit_id": "wing_hardpoint_1",
    "attach_bone": "hardpoint_1",
    "weapon_id": "rvp:missile",
    "model": "yourmod:models/ammo/aim120.obj",
    "texture": "yourmod:textures/models/aim120.png",
    "rack_bones": ["rack_1"],
    "missile_bones": ["msl_1"],
    "replace_weapon_display": true,
    "ammo_slot": 0,
    "offset": [0, -0.2, 0],
    "rotation_deg": [0, 0, 0],
    "scale": [1, 1, 1]
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `part_unit_id` | 挂点所在部件 id（必填）。 |
| `attach_bone` | 挂点骨骼名；与 `attach_part_unit_id` 二选一（必填其一）。 |
| `attach_part_unit_id` | 直接挂在某部件 id 上（替代骨骼）。 |
| `weapon_id` | 绑定的武器类型 id（必填）。 |
| `model` / `texture` | 自定义显示模型/贴图资源 id（必填）。 |
| `rack_bones` / `missile_bones` | 挂架骨骼列表 / 导弹骨骼列表。 |
| `replace_weapon_display` | 是否替换原武器显示模型。默认 `true`。 |
| `ammo_slot` | 弹药槽位索引。默认 `0`。 |
| `offset` / `rotation_deg` / `scale` | 位置偏移 / 旋转（度）/ 缩放，均为 3 元数组。 |

### 2.10 发射器部署（`rvp_launcher_deploy`）

把武器站定义为“发射器部署”（可展开/收纳并带动俯仰）结构，支持单个对象或数组（多条配置），完整字段见源码 `RVP_LauncherDeployConfig`：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `enabled` | 本条配置开关（`false` 时忽略）。 | `true` |
| `id` | 配置 id；缺省时用 `part_unit_id` 或 `launcher_N`。 | — |
| `part_unit_id` | 所属部件 id（与 `weapon_unit_ids` 至少填一个）。 | 空 |
| `weapon_unit_ids` | 关联武器站 id 列表（字符串或数组）。 | `[]` |
| `pitch_part_unit_id` | 俯仰部件 id；缺省取 `weapon_unit_ids[0]` 或 `part_unit_id`。 | — |
| `pitch_group` | 俯仰分组：结构骨骼名（如 `launcher`）或 `<骨骼名>_barrel`（指向该部件的炮管 xTurn 分组）；不匹配任何骨骼时回退 xTurn 分组。缺省自动取部件 xTurn 分组。 | 空 |
| `deploy_speed_max` | 展开最大速度。 | `2.0` |
| `retract_speed_min` | 收纳最小速度（默认 `deploy_speed_max + 0.5`）。 | — |
| `deploy_time_tick` | 展开耗时（tick）。 | `60` |
| `retract_time_tick` | 收纳耗时（tick）。 | `40` |
| `require_player_present` | 需要乘员在位才允许展开。 | `true` |
| `auto_deploy` | 开火时自动展开。 | `true` |
| `auto_retract` | 停止开火/闲置自动收纳。 | `true` |
| `block_fire_when_closed` | 未展开时禁止开火。 | `true` |
| `block_fire_when_deploying` | 展开过程中禁止开火。 | `true` |
| `block_fire_when_retracting` | 收纳过程中禁止开火。 | `true` |
| `block_fire_when_speeding` | 高速移动时禁止开火。 | `true` |
| `apply_pitch_after_weapon_tick` | 在武器 tick 之后应用俯仰。 | `false` |
| `stowed_pitch` | 收纳俯仰角（度）。 | `0.0` |
| `deployed_pitch` | 展开俯仰角（度）。 | `88.0` |

```json
"rvp_launcher_deploy": {
  "part_unit_id": "launcher_unit",
  "weapon_unit_ids": ["launcher_unit"],
  "deploy_time_tick": 60,
  "retract_time_tick": 40,
  "auto_deploy": true,
  "auto_retract": true,
  "block_fire_when_closed": true,
  "stowed_pitch": 0,
  "deployed_pitch": 88
}
```

### 2.11 载具显示配置（`display/vehicle`）

载具模型/贴图与渲染行为配置文件：`assets/<namespace>/display/vehicle/<id>.json`。本体 `BaseDisplayPojo` 的字段（`model`、`texture`、`animations`、`special_bone_effects` 等）全部可用；RVP 通过 `RVP_BaseDisplayPojo` 扩展以下独有字段：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `bedrock_backend` | Bedrock 模型渲染后端：`vehicle`（默认，本体后端）/ `rvp`（RVP 增强后端）。 | `vehicle` |
| `no_cull_bones` | 骨骼名列表：渲染时对这些骨骼**不做面剔除**（半透明/镂空模型防误剔）。 | `[]` |
| `state_hidden_bones` | 状态机隐藏骨骼规则数组（见下）。 | `[]` |
| `distance_hidden_bones` | 距离 LOD 隐藏骨骼规则数组（见下）。 | `[]` |
| `lod_models` | 整模型 LOD 规则数组：距离/离地达到阈值时用低面数模型整体替换渲染（见下）。 | `[]` |

#### `state_hidden_bones[]`（`RVP_StateHiddenBone`）

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `state` | 触发状态。目前支持 `landing_gear_up`（起落架收起）。 | — |
| `bones` | 该状态下隐藏（不渲染）的骨骼名列表。 | `[]` |
| `delay_ticks` | 进入状态后延时多少 tick 才隐藏（保证收起动作动画完整播放）；状态退出立即恢复渲染。 | `0` |

#### `distance_hidden_bones[]`（`RVP_DistanceHiddenBone`）

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `distance` | 玩家与载具距离超过该值（方块）时隐藏对应骨骼。 | `50` |
| `bones` | 隐藏的骨骼名列表。 | `[]` |

#### `lod_models[]` 整模型 LOD（`RVP_LodModel`）

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `model` | LOD 低模资源 ID（**必填**，如 `yourmod:models/bedrock/fighter_lod2.json`）。 | — |
| `model_air` | 离地（飞行）状态使用的 LOD 模型 ID；缺省与地面共用 `model`。 | — |
| `texture` | LOD 贴图资源 ID；缺省沿用原贴图。 | — |
| `distance` | 地面状态下的进入阈值（方块）。 | `32` |
| `air_distance` | 离地状态下的进入阈值（方块），通常小于 `distance`——飞行时 LOD 更早生效。 | `16` |
| `air_height` | 离地判定高度（方块）：载具相对地面高于该值视为离地。 | `10` |
| `zoom_distance` / `zoom_air_distance` | 缩放中（载具镜/RVP 武器缩放/原版望远镜）的显式进入阈值（方块）；缺省沿用 `distance × 全局缩放系数`（系数见 §6.3 `lod.lodZoom*`）。`-1` 表示未配置。 | `-1` |

> LOD 切换带滞后防抖：进入某级使用配置阈值，退回使用 `0.85 × 阈值`，避免边界抖动。

#### `special_bone_effects[]` 内 RVP 透明模式扩展

`special_bone_effects` 为本体字段，RVP 在其条目中扩展透明渲染模式：

| 字段 | 说明 |
| --- | --- |
| `ywzj_rvp_transparent_mode` | 透明渲染模式：`cockpit_depth_fix`（配合同条目的 `bone` 字段，修复半透明座舱盖等特殊骨骼的深度渲染问题）。 |

```json
{
  "model": "yourmod:models/vehicle/fighter.json",
  "texture": "yourmod:textures/vehicle/fighter.png",
  "bedrock_backend": "rvp",
  "no_cull_bones": ["canopy_glass"],
  "state_hidden_bones": [
    { "state": "landing_gear_up", "bones": ["gear_front", "gear_rear"], "delay_ticks": 20 }
  ],
  "distance_hidden_bones": [
    { "distance": 80, "bones": ["antenna"] }
  ],
  "lod_models": [
    { "model": "yourmod:models/bedrock/fighter_lod2.json", "distance": 120, "air_distance": 60 }
  ],
  "special_bone_effects": [
    { "bone": "canopy_glass", "ywzj_rvp_transparent_mode": "cockpit_depth_fix" }
  ]
}
```

### 2.12 骨骼模块定向红外对抗（DIRCM，`bone_modules.dircm`）

写在 `bone_modules.<骨块名>` 条目的 `dircm` 子对象（`modules` 数组需含 `"dircm"`）。每个配置了 `dircm` 的骨骼拥有**一个火力通道**：同时只能照射一个目标；配多个骨骼即多通道（如左右各一，配合 `facing_yaw ±90` 分侧覆盖）。照射骨骼被击毁后该通道失效。

**触发与干扰语义**：
- 扇区+距离+来袭角内的所有 RVP 导弹/火箭/炸弹（`RVP_BaseBullet`，机炮弹除外）都会**触发**（占用通道并建立激光光束）；
- 光束建立瞬间完成干扰判定——仅 **IR / AIR / HITL_TV / HITL_CLOS_TV** 制导的弹体被干扰（丢制导 + 清目标 + 强制偏转远离载具）；其余弹体不干扰但同样占通道、消耗充能；
- 干扰时强制把截获目标改写为弹体正下方地面点，且 **6 秒内禁止重新指定目标**（HITL 弹对地直飞无法重新截获）；
- HITL 弹为**临时干扰**：电视（HITL_TV）3 秒、指令线（HITL_CLOS_TV）6 秒后恢复制导能力（需操作员重新指定才重锁），期间其操作员视角叠加「中心→四周」白闪滤镜；
- ARH/SARH 雷达导引头不受影响（DIRCM 是红外对抗，对雷达导引头无效）；不干扰同阵营与自己发射的弹药。

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `laser_part` | 激光照射武器部件 id（`ywzj_vehicle:weapon` 型，兼作光束起点、失效锚点与动画联动锚点）。必填；缺省视为未启用。 | `null` |
| `facing_part` | 探测扇区朝向跟随的部件 id（随该部件旋转）；缺省跟随车体朝向。直升机建议不填（不随观瞄站转动）。 | `null` |
| `facing_yaw` | 扇区朝向水平偏置角（度）：正值朝车头右侧（-X）、负值朝左侧（+X）。左右双通道用 `±90`。 | `0.0` |
| `scan_fov` | 探测扇区全角（度），半角 = 值/2。 | `120.0` |
| `detect_radius` | 探测/照射距离（格），同时是光束断开阈值。 | `2000.0` |
| `approach_angle` | 来袭角判定（度，半角）：弹体飞行方向与「弹→车」连线夹角超过该值不触发（只照朝本车飞来的威胁）。 | `60.0` |
| `beam_tick` | 激光光束美术特效持续时长（tick）。干扰在光束建立瞬间即生效，本字段只控制特效跟踪时长。 | `20` |
| `charge_tick` | 一次照射后的充能时长（tick），期间通道不可用。 | `300` |
| `scan_interval_tick` | 扫描节流间隔（tick）。 | `5` |
| `exclude_owner_projectile` | 是否忽略本车自己发射的弹体。 | `true` |

```json
"bone_modules": {
  "dircm_l": {
    "modules": ["dircm"],
    "dircm": {
      "laser_part": "dircm_l",
      "facing_yaw": -90.0,
      "scan_fov": 120,
      "detect_radius": 192.0,
      "approach_angle": 60,
      "beam_tick": 20,
      "charge_tick": 300,
      "scan_interval_tick": 5,
      "exclude_owner_projectile": true
    }
  },
  "dircm_r": { "...": "同上，facing_yaw: 90.0" }
}
```

> 配套：`laser_part` 引用的部件须为 `parts` 内的 `ywzj_vehicle:weapon` 型部件（`structure_bone` 指向结构模型对应骨骼）；动画脚本可用 `getPartXRot/getPartYRot(<laserPart>)` 让发射器随动。调试：`/rvpdebug scanviz` 会以白色粒子（END_ROD）勾勒 DIRCM 干扰锥。

### 2.13 快速维修（`bone_modules.__vehicle__.maintenance`，模块 `MAINTENANCE`）

写在 `bone_modules.<骨块名>` 条目的 `maintenance` 子对象（`modules` 数组需含 `"MAINTENANCE"`）。**规范写法挂虚拟骨骼 `__vehicle__`**（载具级能力、永不可被击毁，与无骨骼 ECM 同构）；也可绑定实体骨（如发动机）——骨块被直击打掉则维修模块失效，快修无法触发（模块失效后无法用快修复自身，该能力即告失去）。顶层 `maintenance` 块保留为别名（自动映射到 `__vehicle__`，等价）。

按键默认 **G**（`key.ywzj_rvp.use_maintenance.desc`，可在按键设置改）。触发校验：未被摧毁 / 冷却就绪 / 触发者乘坐本车 / 离地高度限制。HUD 在干扰物信息组（热诱/箔条/ECM/烟雾）末尾按缺省递补显示"维修"行。冷却与生效剩余经载具实体 NBT 持久化（随存档）。

触发瞬间执行**骨骼模块渐进恢复**（方案详见 `docs/plan/快速维修移植方案_20260902.md`）：

- **设备类**（APS/JAMMER/DIRCM/COUNTERMEASURE/ECM_PASSIVE/ECM_ACTIVE）：每台已毁模块**独立按概率**恢复——部分恢复有明确语义（每根 APS 雷达骨 = 一个扫描扇区，修 1 根恢复 1 个方位）；
- **ERA**：按数量比例恢复（对已毁数向上取整、至少 `era_recover_min` 块，随机洗牌），对齐 MCHR 消耗品手感；
- `TRACK` 无功能消费点，**不在缺省白名单**且配置加入也不会被维修；
- `MAINTENANCE` 自身不在设备恢复掷骰内。

| 字段（`maintenance` 子对象） | 说明 | 默认值 |
| --- | --- | --- |
| `use_time_ticks` | 生效时长（tick），每 tick 回 `heal_per_tick_percent`% 最大血量。 | `20` |
| `wait_time_ticks` | 冷却时长（tick）。 | `300` |
| `heal_per_tick_percent` | 每 tick 回复量占最大血量百分比。 | `1.0` |
| `heal_parts` | 生效期是否同步回部件血量（每部件 +10% 上限，对齐扳手）。 | `false` |
| `require_max_altitude` | 允许触发的离地高度上限（方块）；`< 0` 不限。 | `-1` |
| `module_repair` | 模块渐进恢复子对象；缺省即下表默认值。 | `null` |

| 字段（`module_repair` 子对象） | 说明 | 默认值 |
| --- | --- | --- |
| `repairable_types` | 允许维修的模块类型白名单；空 = 除 `TRACK` 外全部（含 `ERA/APS/JAMMER/DIRCM/COUNTERMEASURE/ECM_PASSIVE/ECM_ACTIVE/MAINTENANCE`）。 | 空（全类型） |
| `era_recover_fraction` | ERA 单次维修恢复比例（对已毁块数向上取整）。 | `0.25` |
| `era_recover_min` | ERA 单次维修至少恢复块数。 | `1` |
| `device_recover_chance` | 设备类每台已毁模块每次维修的独立恢复概率（0~1）。 | `0.25` |

```json
"bone_modules": {
  "__vehicle__": {
    "modules": ["MAINTENANCE"],
    "maintenance": {
      "use_time_ticks": 20,
      "wait_time_ticks": 300,
      "heal_per_tick_percent": 1.0,
      "heal_parts": false,
      "require_max_altitude": -1,
      "module_repair": {
        "repairable_types": [],
        "era_recover_fraction": 0.25,
        "era_recover_min": 1,
        "device_recover_chance": 0.25
      }
    }
  }
}
```

> 骨骼模块失效状态经 `S2CBoneModuleState` 同步客户端（JS 动画 `rvp_isEraActive`/`isModuleActive` 据此显隐渲染骨），快修恢复后同通道自动恢复显示。

---

### 2.14 干扰物（`countermeasure`，热焰/箔条/烟雾）

载具 JSON 顶层 `countermeasure` 块，键为子系统类型：`flare`（热焰弹）/ `chaff`（箔条）/ `smoke`（烟雾弹）。每个键是一个**系统对象**（`RVP_CountermeasureSystemData`）；键名大小写不敏感，`type` 字段缺省由所属键决定。系统启用条件：`total > 0` 且至少一个 `launcher_parts`。触发键位：热焰 H、箔条/ECM 左 Alt、烟雾 H（客户端按各自系统配置过滤）。

#### 系统对象字段（`flare` / `chaff` / `smoke`）

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `type` | 干扰物类型 `FLARE` / `CHAFF` / `SMOKE`，大小写不敏感；缺省由所属键决定。 | 空（随键） |
| `launcher_parts` | 发射装置部件 id 列表（本体 `WeaponUnit` 部件），作为出膛点与发射动画锚点。 | `[]` |
| `total` | 干扰物总数（弹舱容量）；`0` = 禁用该系统。 | `32` |
| `per_round` | 一轮发射数 m。 | `4` |
| `burst_rounds` | 总发射轮数 n（一次按键最多发射的轮数）。 | `8` |
| `launch_interval_tick` | 轮间发射间隔（tick）。 | `4` |
| `reload_tick` | 装填时间（tick），从 0 装填到 total。 | `200` |
| `decoy` | 干扰物实体属性（见下）。 | 见下 |
| `smoke` | 烟雾云属性；**仅 `SMOKE` 系统生效**（见下）。 | 见下 |
| `radar_jam_radius` | 箔条对雷达锁定的干扰判定半径（格）；**仅 `CHAFF` 生效**。 | `8.0` |
| `radar_jam_count` | 被锁定目标周围箔条数 ≥ 该值时雷达脱锁；**仅 `CHAFF` 生效**。 | `3` |
| `radar_jam_cooldown_tick` | 脱锁后目标短时间内不能被雷达选中/锁定（仍可被扫描）的时长（tick）；**仅 `CHAFF` 生效**。 | `60` |
| `bone_modules` | 关联的 `bone_modules` 骨块名列表：非空时对应骨块 `COUNTERMEASURE` 模块全部被击毁则本系统失去抛洒功能；为空不联动骨块。 | `[]` |

#### `decoy` 子对象（`RVP_CountermeasureDecoyData`）

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `lifetime_tick` | 单发干扰物存活 tick（有效干扰窗口）。 | `160` |
| `speed` | 出膛初速（m/tick，沿发射装置瞄准方向，叠加载具速度）。 | `0.5` |
| `gravity` | 下落加速度（热焰弹建议 `0.02` 漂浮更久；箔条建议 `0` 悬浮）。 | `0.02` |
| `drag` | 空气阻力系数，速度按 `velocity *= (1 - drag)` 衰减。 | `0.05` |
| `spread` | 发射散布半径（格，发射时随机偏移）。 | `1.0` |
| `glow_color` | 发光颜色（`0xRRGGBB`），作用于 billboard 贴图颜色倍乘。 | `0xFFFFFF` |
| `halo_scale` | 光晕/billboard 尺寸倍率（光圈大小）。 | `1.0` |

#### `smoke` 子对象（`RVP_CountermeasureSmokeData`，仅 `SMOKE` 系统）

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `lifetime_tick` | 云团存活 tick（含弹道飞行段），到点消散。 | `100` |
| `radius` | 云团最终半径（格），AABB 从 0 随时间膨胀到该值；需显著大于导弹近炸半径。 | `10.0` |
| `speed` | 出膛初速（格/tick，沿发射装置瞄准方向，叠加载具速度）。 | `1.0` |
| `gravity` | 下落加速度（格/tick²），用于出膛弹道飞行段。 | `0.05` |
| `explode_tick` | 出膛后延迟该 tick 爆炸生成烟雾云。 | `10` |
| `rise_speed` | 烟雾上升速度（格/tick，即负重力语义）。 | `0.03` |
| `opacity` | 遮蔽强度 0~1（预留：后续分级遮蔽/视线衰减，当前仅作数据保留）。 | `1.0` |
| `drift` | 风/随机飘移幅度（格/tick，预留）。 | `0.0` |

配置示例（热焰 + 箔条 + 烟雾）：

```json
"countermeasure": {
  "flare": {
    "launcher_parts": ["decoy_flare_barrel"],
    "total": 32, "per_round": 4, "burst_rounds": 8,
    "launch_interval_tick": 4, "reload_tick": 200,
    "decoy": { "lifetime_tick": 160, "speed": 1.0, "gravity": 0.052, "drag": 0.02, "spread": 0.6, "glow_color": 16711680, "halo_scale": 1.6 }
  },
  "chaff": {
    "launcher_parts": ["decoy_flare_barrel"],
    "total": 32, "per_round": 4, "burst_rounds": 8,
    "launch_interval_tick": 4, "reload_tick": 200,
    "radar_jam_radius": 8, "radar_jam_count": 2, "radar_jam_cooldown_tick": 60,
    "decoy": { "lifetime_tick": 100, "speed": 1.0, "gravity": 0.052, "drag": 0.02, "spread": 2.5, "glow_color": 16777215, "halo_scale": 0.7 }
  },
  "smoke": {
    "launcher_parts": ["turret_smoke_grenade_l", "turret_smoke_grenade_r"],
    "total": 4, "per_round": 2, "burst_rounds": 2,
    "launch_interval_tick": 4, "reload_tick": 250,
    "smoke": { "lifetime_tick": 100, "radius": 10.0, "speed": 1.0, "gravity": 0.01, "explode_tick": 10, "rise_speed": 0.0, "opacity": 1.0, "drift": 0.0 },
    "decoy": { "glow_color": 16777215, "halo_scale": 4.0 }
  }
}
```

> 干扰物系统完整数据模型见 `docs/plan/RVP干扰物重构数据模型/RVP 干扰物重构数据模型文档.md`。

---

## 3 部件 JSON 扩展

### 3.1 武器站部件扩展

以下字段写在武器站**部件 JSON**内（`parts` 数组某部件的同级字段，由 `WeaponUnitPojoMixin` 注入）：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `rvp_fire_control_mode` | 火控模式标记：空（默认，不覆盖本体默认）或 `rvp_rf`（启用 RF 软火控/瞄准辅助，配合 `rvp_rf_off_axis_deg` 限制离轴角度）。 | `""` |
| `rvp_rf_off_axis_deg` | 雷达（RF）制导离轴限制（度）。 | `10.0` |
| `rvp_disable_crt_effect` | 是否禁用 CRT 显示器特效。 | `false` |
| `rvp_follow_parent_only_part_unit_ids` | 仅跟随父级部件旋转的部件 id 列表。 | `[]` |
| `rvp_structure_bolt_bones` | 多挂点武器的**挂点骨骼名列表**。本体 `initStructureModel` 只为 `xTurnBone`（`structure_bone + "_barrel"`）构建**一个** Bolt，左右两侧挂架（如 `variable_agm_1_barrel` / `variable_agm_2_barrel`）只有第一个挂点有 Bolt，导致挂架渲染偏移到单侧。列出全部挂点骨骼后，`WeaponUnitDataMixin` 会自动跳过本体已处理的 `xTurnBone`，为其余骨骼计算偏移并**补充 Bolt**。 | `[]` |
| `rvp_optical_sight_pivot` | 观瞄基准枢轴（`[x, y, z]`，**渲染模型骨块 pivot 像素值**，内部 `/16` 转方块单位）。默认观瞄位置 = `结构骨枢轴 + opticalSightOffset`；配置本字段后改为 `渲染骨枢轴/16 + opticalSightOffset`，用于“观瞄点相对某个渲染骨骼（如机枪观瞄镜）而非武器站结构骨枢轴”的场景（T84BM 机枪观瞄即以 `guanmiao` 骨骼为基准）。未配置时为 `null`（不生效）。 | `null` |

### 3.2 雷达部件扩展

以下字段写在雷达部件 JSON 内（由 `RadarUnitPojoMixin` 注入）：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `radar_role` | 雷达角色：`all`（默认，可搜索可锁定）/ `search`（仅搜索，不可锁定）/ `fire_control`（火控雷达，多雷达时锁定优先）。 | `all` |
| `scan_animation_mode` | 扫描动画模式：`mechanical`（默认，机械扫描线）/ `phase`（相位阵列扫描）。 | `mechanical` |
| `nctr_mode` | NCTR 非合作目标识别模式：`NONE`（默认，关闭）/ `EARLY`（早期简化识别）/ `MODERN`（现代识别，写任意非 `NONE`/`EARLY` 值均可）。 | `NONE` |
| `scan_period_tick` | 扫描周期（tick）。 | `0` |
| `scan_line_when_locked` | 锁定时是否显示扫描线。 | `false` |
| `contact_hold_tick` | 脱锁后目标保持显示的 tick。 | `0` |
| `enable_hms` | 头盔瞄准具（HMS）开关：`false` / `true` / `onlyACM`（仅格斗模式）。未写时视为 `FULL`（启用）。 | 未写 |
| `scan_min_height` | 扫描最小高度（格）。 | `25` |
| `scan_max_height` | 扫描最大高度（格）。 | `10000` |
| `chaff_resistance` | 雷达对箔条目标的锁定抗性（0~1）：箔条可作为雷达锁定目标，但按此值对箔条施加锁定候选评分罚分（越大优先级越低，非完全不可锁）。默认 `0.5`，具备相当的抗箔条能力。 | `0.5` |
| `scan_vehicle_only` | 仅扫描/跟踪载具：`true` 时扫描与锁定只保留载具目标，排除弹药、箔条等非载具实体（扫描与接触保活均过滤）。适合“对地补盲雷达”（如长弓桅顶雷达）。 | `false` |

> `enable_hms` 为 `JsonElement`：写布尔或字符串均可；`false`/`off`/`none` 表示关闭，`onlyACM`/`only_acm`/`acm` 表示仅空战模式启用，其余值视为完整启用。

### 3.3 本体火箭弹武器数据扩展（`ballistic_*`）

以下字段写在**本体火箭弹武器的 JSON**（`VehicleRocketWeaponData`，由 `VehicleRocketWeaponDataMixin` 注入），用于启用**火箭弹弹道预瞄**（按重力/阻力积分预测落点，供 CCIP 瞄准圈使用）。消费方为 `RVP_RocketBallistics`。

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `ballistic_enabled` | 是否启用该火箭弹的弹道预瞄。`false` 时 `RVP_RocketBallistics.resolve` 直接返回 `null`，不做落点预测。 | `false` |
| `ballistic_gravity` | 预瞄积分用的重力（格/tick²）。取负值按 `0` 处理。 | `0.03` |
| `ballistic_drag` | 预瞄积分用的阻力系数。取负值按 `0` 处理。 | `0.002` |
| `ballistic_prediction_tick` | 预瞄最长积分步数（tick）。解析时 `Math.max(1, 值)`，即写 `0` 或负数按 `1` 处理。 | `240` |

> 初速取本体 `VehicleRocketWeaponData.velocity`（下限 `0.01`），不在本组字段内配置。炮兵场景另有 `ARTILLERY_PREDICTION_TICK = 1200` 常量，非 JSON 字段。

---

## 4 UI 预设文件（`ui_presets`）

UI 预设决定载具 HUD 布局。加载来源（`UIPresetManager`）：

1. 数据包：`data/*/ui_presets/<name>.json`（数据包同名预设**优先**）；
2. 配置目录：`config/limitless_vehicle/ui_presets/*.json`（回退）；
3. 两者都为空时自动生成默认 `default.json`。

载具 JSON 顶层通过 `"ui_preset": "<name>"` 引用预设。

### 预设文件结构

| 字段 | 说明 |
| --- | --- |
| `name` | 预设名（缺省用文件名）。 |
| `radar` | 雷达窗口位置。 |
| `external_radar` | 外部雷达（地图屏）位置。 |
| `rwr` | 雷达告警接收机位置。 |
| `vehicle_bones` | 骨骼俯视图位置。 |
| `scope_envelope` | 瞄准镜包络位置。 |
| `radars` | 多雷达独立位置：对象，key 为雷达部件 id（如 `scan_radar`），value 为位置对象。 |
| `aps_hud` | APS（主动防护系统）状态 HUD 位置，类型为下方 `UIPosition`。 |
| `dircm_hud` | DIRCM（定向红外对抗）通道 HUD 位置，类型为下方 `UIPosition`。 |

### 位置对象（`UIPosition`）

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `anchor` | 锚点：`center` / `top_left` / `left` / `right` / `right_bottom`。 | `right` |
| `offset_x` | 水平偏移（px）。 | `0` |
| `offset_y` | 垂直偏移（px），按 1080p 参考高度等比缩放（`offset_y × 实际高度/1080`）。 | `0` |
| `scale` | 缩放倍率。 | `1.0` |

```json
{
  "name": "default",
  "radar": { "anchor": "right", "offset_x": 128, "offset_y": -80, "scale": 1.0 },
  "external_radar": { "anchor": "top_left", "offset_x": 80, "offset_y": 300, "scale": 1.0 },
  "rwr": { "anchor": "right", "offset_x": 128, "offset_y": -180, "scale": 1.0 },
  "vehicle_bones": { "anchor": "right_bottom", "offset_x": 116, "offset_y": 80, "scale": 1.0 },
  "radars": {
    "scan_radar": { "anchor": "top_left", "offset_x": 100, "offset_y": 200, "scale": 1.0 }
  }
}
```

> 热更新：`/ywzj_vehicle reload` 会重新加载数据包与 config 中的预设文件。

---

## 5 Gunner 配置文件（`gunner_profiles`）

Gunner（炮手 AI）配置文件，字段以源码 `GunnerProfile` 为准：

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `name` | 配置名（缺省用文件名）。 | `default` |
| `faction` | 阵营：`friendly` / `enemy`（或 `hostile`）/ `team`（或 `faction`）。 | `friendly` |
| `target_types` | 目标类型过滤：`vehicle` / `player` / `monster` / `living` / `rvp:missile`。 | `["rvp:missile","vehicle","monster","player"]` |
| `gps_prefer_farthest` | 是否优先用 GPS 武器打击索敌范围内**最远**的目标（GPS 属远程点打击武器，默认优先打最远目标）。 | `true` |
| `search_radius` | 索敌半径（格）。 | `96.0` |
| `scan_interval_tick` | 目标扫描间隔（tick）。 | `10` |
| `fire_window_deg` | 开火窗口角（度）：目标进入该角锥内才开火。 | `6.0` |
| `lead_scale` | 提前量补偿系数。 | `1.0` |
| `burst_fire_tick` | 单次点射持续（tick）。 | `6` |
| `burst_rest_tick` | 点射间隔（tick）。 | `10` |
| `countermeasure_range` | 应对来袭导弹的告警/规避半径（格）。 | `36.0` |
| `countermeasure_cooldown_tick` | 反制动作冷却（tick）。 | `80` |
| `allow_drive` | 是否允许 AI 驾驶本车。 | `true` |
| `drive_pursuit_distance` | 追击目标距离（格）。 | `64.0` |
| `drive_stop_distance` | 停车距离（格）。 | `12.0` |
| `drive_stuck_check_tick` | 卡住检测间隔（tick）。 | `20` |
| `drive_stuck_distance` | 卡住判定位移（格）。 | `1.0` |
| `drive_recovery_tick` | 脱困倒车时长（tick）。 | `20` |
| `rotary_cruise_altitude_min` | 旋翼机巡航高度下限（格）。 | `28.0` |
| `rotary_cruise_altitude_max` | 旋翼机巡航高度上限（格）。 | `60.0` |
| `fixedwing_cruise_altitude_min` | 固定翼巡航高度下限（格）。 | `150.0` |
| `fixedwing_cruise_altitude_max` | 固定翼巡航高度上限（格）。 | `500.0` |
| `fixedwing_combat_radius_min` | 固定翼作战半径下限（格）。 | `40.0` |
| `fixedwing_combat_radius_max` | 固定翼作战半径上限（格）。 | `550.0` |
| `ground_wander_enabled` | 地面 AI 是否允许漫游。 | `true` |
| `ground_big_turn_interval_tick_min` | 地面大转弯间隔下限（tick）。 | `300` |
| `ground_big_turn_interval_tick_max` | 地面大转弯间隔上限（tick）。 | `600` |
| `ground_big_turn_angle_deg_min` | 地面大转弯角度下限（度）。 | `120.0` |
| `ground_big_turn_angle_deg_max` | 地面大转弯角度上限（度）。 | `180.0` |
| `ground_big_turn_duration_tick` | 地面大转弯持续（tick）。 | `40` |
| `air_attack_phase_tick` | 空中攻击阶段持续（tick）。 | `200` |
| `air_disengage_phase_tick` | 空中脱离阶段持续（tick）。 | `200` |
| `air_initial_disengage_tick_min` | 首次脱离延迟下限（tick）。 | `300` |
| `air_initial_disengage_tick_max` | 首次脱离延迟上限（tick）。 | `400` |

---

## 6 Forge 配置（config 文件）

RVP 的 Forge 配置分三个文件：`ywzj_rvp-server.toml`、`ywzj_rvp-common.toml`、`ywzj_rvp-client.toml`。

### 6.1 `ywzj_rvp-server.toml`（服务端）

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `explosion.craterDepthRules` | 爆炸坑深度规则：每个条目格式 `"maxRadius:maxDepth"`（maxRadius = 爆炸半径阈值格数，maxDepth = 爆炸中心 Y 以下最多破坏层数，`0` = 仅地表）。加载时按 maxRadius 自动升序排序，半径超过最后一条时沿用最后一条深度。**空列表 = 无限制（原版行为）**。 | `["5:0","15:1","35:2","65:3","100:4","9999:5"]` |

### 6.2 `ywzj_rvp-common.toml`（通用）

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `spawning.spawnVehicleWithCreativeAmmo` | 用生成物品放置载具时，是否自动往载具库存塞一组创造弹药以便立即使用；同时**在载具生成的瞬间一次性补满所有武器的所有弹药**（跳过装填时间，避免换弹时间长的载具放置后还要等待）。该补满是仅生成时刻的一次性操作，不随后续消耗弹药重复触发。 | `false` |

### 6.3 `ywzj_rvp-client.toml`（客户端）

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `lod.lodZoomEnabled` | 缩放中（载具镜/RVP 武器缩放/原版望远镜）启用 LOD 增强：视场内载具少、渲染压力低，可将 LOD 距离阈值放大、更晚替换低模。 | `true` |
| `lod.lodZoomFovRatio` | “缩放中”判定阈值：当前渲染 FOV 低于基础 FOV 的该比例时视为缩放中。范围 `0.1–0.99`。 | `0.75` |
| `lod.lodZoomDistanceMultiplier` | 缩放中 LOD 距离阈值倍率（`1.0` = 不变，`>1` = 缩放中 LOD 更晚生效）。范围 `1.0–10.0`。 | `1.5` |

---

## 7 旧写法迁移对照

> 此表帮助从旧版本 JSON 迁移到当前 schema。**当前版本不再解析旧键**（仅大小写与少量字段补全，不做全量键迁移），旧 JSON 需手工改写成新写法。

### 7.1 武器类型迁移

| 旧写法 | 新写法 |
| --- | --- |
| `type: "ywzj_rvp:gps_bomb"` | `type: "rvp:bomb"` + `guidance_data.guidance_type: "GPS"` |
| `type: "ywzj_rvp:tv_missile"` | `type: "rvp:missile"` + `guidance_data.guidance_type: "HITL_TV"` 等 |
| `type: "ywzj_rvp:anti_radiation_missile"` | `type: "rvp:missile"` + `guidance_data.guidance_type: "ARM"` |
| `type: "ywzj_rvp:active_radar_missile"` | `type: "rvp:missile"` + `guidance_data.guidance_type: "ARH"` |
| `type: "ywzj_rvp:semi_active_radar_missile"` | `type: "rvp:missile"` + `guidance_data.guidance_type: "SARH"` |
| `type: "ywzj_rvp:manual_guidance_missile"` | `type: "rvp:missile"` + `guidance_data.guidance_type: "MCLOS"` |
| 顶层 `velocity` | 移入 `projectile_data.velocity`（顶层写法仍被自动归一化补入） |
| 无 `has_rocket_engine` 但有 `mass`/`thrust`/`motor_burn_time` | 归一化层自动补 `has_rocket_engine: true` |

### 7.2 旧键 → 新键

| 旧键 | 新键 |
| --- | --- |
| 顶层 `ahead_data{...}` | `fuse_data.ahead_enabled` / `ahead_burst_offset_meters` / `ahead_require_lock` / `ahead_min_ground_clearance` |
| `guidance_data.phases[]` | `guidance_data.stages[]`（归一化层自动重命名） |
| `guidance_data.stage_policy` | `guidance_data.phase_resolve_policy`（自动重命名） |
| `seeker_data`（顶层或阶段内） | 阶段内 `seeker{}`（自动合并） |
| 顶层 `steering_data` | 各阶段 `steering_data`（自动复制到缺省阶段） |
| `charge_time`（旧蓄力字段） | 由 `fire_data.fire_mode: "CHARGE"` + `fire_data` 蓄力参数取代 |
| `minigun_spin_decay_tick` | 由 `fire_data.fire_mode: "MINIGUN"` 相关参数取代 |
| `dual_pulse` | `projectile_data.second_pulse` / `second_pulse_trigger_speed` / `second_pulse_trigger_distance` / `second_pulse_thrust` / `second_pulse_burn_time` |
| `altitude_drag_*` | `projectile_data.altitude_drag_factor` |
| `gps_cep` | `guidance_data.gps_spread_radius` |
| `signature_size` | `misc_data.signal_intensity_factor_on_radar` |
| `guide_head_max_angle` | `guidance_data.max_off_axis_lock_angle` |
| `seek.fov` | `guidance_data.max_lock_angle`（注意后者为完整 FOV，运行时减半） |
| `terminal_ir_*` | 并入 `guidance_data.terminal_guidance`（仅 `NONE`/`ATV`/`AIR`/`ARH`/`ARM`/`IR`） |
| 顶层 `spread` | 由 `submunition_data` / `dispenser_data` 控制；弹道散布回退顶层 `inaccuracy` |

---

## 8 参考

- 弹体运动学：`docs/plan/RVP武器数据模型/弹体运动学与爆炸伤害.md`
- 伤害倍率与爆炸：`docs/plan/RVP武器数据模型/RVP伤害倍率与爆炸.md`
- 子母弹系统：`docs/plan/RVP武器数据模型/子母弹系统.md`
- 源码目录：`src/main/java/org/ywzj/rvp/`（数据模型在 `weapon/data/`，载具扩展在 `mixin/`，配置在 `config/`）

---

> 本文档为 RVP 包新增参数字段的完整说明。若源码出现新的可配置项，请同步更新本文档。
