# RVP 包新增参数字段说明

本文档面向扩展包开发者，说明 RVP 新武器系统的 JSON schema。

JSON 文件本身不能写注释，因此字段解释以本文档和 `org.ywzj.rvp.weapon.data.RVP_*Data` 类中的中文注释为准。

**延伸阅读（更易读的分主题文档）**

| 文档 | 内容 |
| --- | --- |
| [README.md](./README.md) | 文档索引、路径约定、代码入口 |
| [弹体运动学开发与测试.md](./弹体运动学开发与测试.md) | `projectile_data` 三条弹道分支、本体对照、调试 |

RVP 扩展武器数据包路径：

- 开发载具包：`limitless-vehicle-rvp-addon/run/client_1/limitless_vehicle/rvp/`
- 运行时载具包目录：`.minecraft/limitless_vehicle/rvp/`
- 武器 JSON：`data/rvp/weapons/<id>.json`（资源 ID 为 `rvp:<id>`）
- 显示配置：`assets/rvp/display/weapon/<id>.json`

## 载具 JSON 扩展字段

除武器 JSON 外，RVP 也会从 `data/<namespace>/vehicles/<id>.json` 读取少量扩展字段。

### `rvp_custom_mounts` 自定义外挂挂载渲染（客户端）

用于“按当前选中的挂载类型，切换整套挂架 + 导弹外挂模型”，并在弹药打空后只隐藏导弹骨骼、保留挂架骨骼。

```json
"rvp_custom_mounts": [
  {
    "part_unit_id": "variable_weapon_1",
    "attach_bone": "hardpoint_1",
    "weapon_id": "rvp:yj_91_2",
    "model": "rvp:entity/weapon_mount_yj91",
    "texture": "rvp:textures/entity/weapon_mount/pylon_yj91.png",
    "rack_bones": ["rack_root", "rail_l", "rail_r"],
    "missile_bones": ["missile_root"],
    "replace_weapon_display": true,
    "offset": [0.0, 0.0, 0.0],
    "rotation_deg": [0.0, 0.0, 0.0],
    "scale": [1.0, 1.0, 1.0]
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `part_unit_id` | 绑定到哪个 `WeaponUnit`。RVP 会读取这个武器站“当前选中的武器”，只有当其 `weapon_id` 与本条匹配时才渲染本外挂模型。 |
| `attach_bone` | 可选。载具显示/结构模型中的挂点骨骼名。外挂模型整体会跟随这个骨骼的当前姿态渲染。 |
| `attach_part_unit_id` | 可选。绑定到另一个“只负责提供挂点”的 `WeaponUnit`。RVP 会取该武器站第一个 bolt 的位置作为外挂挂点，适合直接复用结构模型里的 `*_barrel` 挂载位。与 `attach_bone` 二选一，优先推荐给固定翼多挂点使用。 |
| `weapon_id` | 触发本外挂模型的武器 ID。通常对应 `rvp:yj_91_2`、`rvp:kd_88a`、`rvp:pl_12`、`rvp:pl_15` 这类具体挂载武器。 |
| `model` | 外挂整体模型 ID（Bedrock 模型）。建议把“挂架 + 导弹”做到同一个模型里。 |
| `texture` | 外挂整体贴图。 |
| `rack_bones` | 挂架骨骼列表。当前主要用于配置可读性与后续扩展，建议如实填写。 |
| `missile_bones` | 导弹/弹药骨骼列表。支持两种模式：（1）**单枚导弹**（列表长度 ≤ 1）：弹药耗尽时隐藏该骨骼，保留挂架；（2）**多枚导弹**（列表长度 > 1）：弹药按列表顺序逐枚消耗并逐枚隐藏，见下方"多枚导弹逐枚隐藏"小节。 |
| `replace_weapon_display` | 是否替代本体默认武器显示。默认 `true`；开启后，匹配到本条配置时会抑制 `WeaponUnit.render()` 的默认武器模型渲染，避免外挂模型与默认导弹模型重叠。 |
| `ammo_slot` | 可选的弹药槽位序号（从 1 开始）。当同一个 `part_unit_id + weapon_id` 配了多条外挂时，RVP 会按 `remainAmmo` 与 `ammo_slot` 比较来决定哪些挂点的导弹继续显示。未填写时按 `rvp_custom_mounts` 的书写顺序自动分配。 |
| `offset` | 外挂模型相对挂点骨骼的平移偏移 `[x, y, z]`，单位与载具渲染坐标一致（方块）。 |
| `rotation_deg` | 外挂模型相对挂点骨骼的附加旋转 `[x, y, z]`（度）。 |
| `scale` | 外挂模型的附加缩放 `[x, y, z]`。默认 `[1, 1, 1]`。 |

**运行规则：**

- 仅客户端渲染使用，不影响服务器武器逻辑、发射逻辑、命中逻辑。
- 触发条件是“当前武器站选中的具体武器 ID”匹配 `weapon_id`，因此适合给 J15/J16 这类“同一挂点可切换不同挂载”的飞机做可变外挂。
- 当 `replace_weapon_display=true` 且匹配成功时，默认武器显示会被抑制；未匹配到配置的武器仍按本体默认方式渲染。
- 当同一 `part_unit_id + weapon_id` 存在多条配置时，RVP 会按 `ammo_slot`（或列表顺序）与 `remainAmmo` 对比，超出剩余弹药数的挂点会隐藏 `missile_bones`，从而实现多挂点逐枚消失。

### 多枚导弹逐枚隐藏

当 `missile_bones` 列表包含多个骨骼时（如 `["missile1", "missile2", "missile3"]`），RVP 会按弹药消耗顺序逐枚隐藏，而非全显/全隐。

**适用场景**：单挂架挂载多枚同型弹药（如 AASM 三联挂架、火箭巢等）。

**运行规则：**

- `missile_bones` 列表中的骨骼按**索引顺序**对应弹药发射顺序：index 0 的导弹最先发射、最先隐藏。
- 每个挂架的可见导弹数 = `max(0, min(missileBones.size(), visibleAmmo - (ammoSlot - 1) × missileBones.size()))`。
- 挂架本身（`rack_bones`/`pylon`）在所有弹药打空前始终显示；弹药全部打空后挂架也隐藏。
- 单枚导弹模式（`missile_bones.size() <= 1`）不受影响，保持原有全显/全隐逻辑。

**配置示例 — AASM 三联挂架 × 2 = 6 发总弹药：**

```json
"rvp_custom_mounts": [
  {
    "part_unit_id": "variable_agm",
    "attach_part_unit_id": "variable_agm_mount_1",
    "weapon_id": "rvp:rafale_aasm_ir",
    "model": "rvp:entity/weapon_mount_aasm",
    "texture": "rvp:textures/entity/weapon_mount/pylon_aasm.png",
    "rack_bones": ["pylon"],
    "missile_bones": ["missile1", "missile2", "missile3"],
    "ammo_slot": 1
  },
  {
    "part_unit_id": "variable_agm",
    "attach_part_unit_id": "variable_agm_mount_2",
    "weapon_id": "rvp:rafale_aasm_ir",
    "model": "rvp:entity/weapon_mount_aasm",
    "texture": "rvp:textures/entity/weapon_mount/pylon_aasm.png",
    "rack_bones": ["pylon"],
    "missile_bones": ["missile1", "missile2", "missile3"],
    "ammo_slot": 2
  }
]
```

**弹药消耗效果：**

| 剩余弹药 | 挂架1（slot=1） | 挂架2（slot=2） |
| --- | --- | --- |
| 6 | ✅✅✅ | ✅✅✅ |
| 5 | ✅✅✅ | ✅✅❌ |
| 4 | ✅✅✅ | ✅❌❌ |
| 3 | ✅✅✅ | ❌❌❌ |
| 2 | ✅✅❌ | ❌❌❌ |
| 1 | ✅❌❌ | ❌❌❌ |
| 0 | ❌❌❌ | ❌❌❌ |

### `rvp_structure_bolt_bones` 多结构挂点聚合（WeaponUnit 扩展）

用于一个主武器站统一控制多个结构 `*_barrel` 挂载位，典型场景是“4 个 AAM 挂点共用一个可切换的主武器站”。

```json
{
  "id": "variable_aam",
  "type": "ywzj_vehicle:weapon",
  "structure_bone": "variable_aam_1",
  "rvp_structure_bolt_bones": [
    "variable_aam_1_barrel",
    "variable_aam_2_barrel",
    "variable_aam_3_barrel",
    "variable_aam_4_barrel"
  ],
  "ammo_capacity": 4,
  "weapons": [
    "rvp:pl_12",
    "rvp:pl_15"
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `rvp_structure_bolt_bones` | 结构模型中的 barrel 骨骼列表。RVP 会从这些骨骼自动构建多个 bolts，并覆盖本体默认“只从 `structure_bone + "_barrel"` 读取一个挂点”的行为。 |

**运行规则：**

- 仅对 `WeaponUnit` 生效。
- 这些 barrel 骨骼会被统一收束到同一个主武器站的 bolts 列表里，用于多发弹药的发射位/挂点位。
- 适合"4 个挂架只能统一选 `pl_12` 或 `pl_15`"这类场景；不适合每个挂点独立选型的场景。

### `rvp_auto_landing_gear` 自动收放起落架

用于为固定翼/旋翼载具启用自动收放起落架功能。启用后，RVP 会在服务端每 tick 检测速度与离地高度，自动切换起落架状态。

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `rvp_auto_landing_gear` | 是否启用自动收放起落架。`true` 启用，`false` 或不写则关闭。 | `false` |
| `rvp_auto_landing_gear_retract_speed` | 速度超过此值（km/h）→ 收起起落架。 | `100` |
| `rvp_auto_landing_gear_deploy_speed` | 速度低于此值（km/h）且离地低于 `deploy_height` → 放下起落架。 | `50` |
| `rvp_auto_landing_gear_deploy_height` | 离地高度低于此值（米）且速度低于 `deploy_speed` → 放下起落架。 | `25` |

**运行规则：**

- 仅服务端执行，客户端无感知。
- 玩家手动按 **G 键** 切换起落架后，**5 秒内**自动逻辑不干预（手动覆盖冷却），防止"刚放下又被自动收起"。
- 未写 `rvp_auto_landing_gear` 或写为 `false`：完全禁用自动逻辑，只能手动控制。
- 只写 `rvp_auto_landing_gear: true`：使用默认阈值（100/50/25）。
- 同时写其他参数：覆盖默认阈值。

**示例：**

```json
{
  "rvp_auto_landing_gear": true,
  "rvp_auto_landing_gear_retract_speed": 120,
  "rvp_auto_landing_gear_deploy_speed": 60,
  "rvp_auto_landing_gear_deploy_height": 30
}
```

## 公开武器类型

新内容应只使用以下 7 个公开类型：

| 类型 | 用途 |
| --- | --- |
| `rvp:missile` | 导弹类弹体。通过 `guidance` 组合 TV、ARH、ARM、GPS、IR、SARH、SACLOS、MCLOS、IOG、NONE；TV 和 ARH 只在该类型生效。 |
| `rvp:rocket` | 火箭弹类弹体。 |
| `rvp:machinegun` | 机枪、机炮、霰弹、鸭弹等弹丸。 |
| `rvp:bomb` | 重力炸弹、GPS 滑翔弹、集束弹。 |
| `rvp:laser` | 瞬时射线武器。 |
| `rvp:dispenser` | 投放器/布撒器载荷。 |
| `rvp:targetingpod` | 目标指示吊舱，用于写入 GPS/SACLOS 目标点。 |

旧公开类型 `ywzj_rvp:gps_bomb`、`ywzj_rvp:tv_missile`、`ywzj_rvp:anti_radiation_missile`、`ywzj_rvp:manual_guidance_missile`、`ywzj_rvp:active_radar_missile`、`ywzj_rvp:semi_active_radar_missile` 已不再作为配置入口。

## 基本结构

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

## 顶层 RVP 字段（武器级）

载具包武器 JSON **不要**在顶层写 `damage`、`inaccuracy`、`velocity`（分别用 `collision_data.direct_damage`、`fire_data.spread`、`projectile_data.velocity`）。除 `shoot_interval`、`max_capacity`、`reload` 等武器级字段外，弹道、引信、制导、落点/爆炸等一律写入 `*_data` 分组。

| 字段 | 说明 |
| --- | --- |
| `show_msl_indicator` | 是否在 HUD 中显示导弹指示器（菱形框 + 距离）；默认 `false`。仅对需要在 HUD 上额外标示的导弹有意义。 |
| `ahead_data` | `rvp:machinegun` 的 AHEAD 自动编程配置分组。要求同时启用 `fuse_data.programmable_airburst`；Java 侧只负责把空爆距离自动编到预瞄点附近，开花后的子弹药细节仍由 `submunition_data` 和子弹药自身 JSON 决定。旧顶层 `ahead_*` 仍兼容读取，但已不推荐继续使用。 |
| `sub_type` | 可选子类型标记，仅配置可读性；**落点逻辑请用 `detonate_data`**。 |
| `require_lock` | 旧顶层写法，已不推荐继续使用。请改写到 `fire_data.require_lock`。 |
| `fire_control_sensor_type_override` | 可选，按当前武器覆盖所属 `WeaponUnit` 的火控传感器类型。枚举值与本体 `WeaponUnitData.FireControlSensorType` 一致：`none` / `ir` / `rf` / `eo` / `ccip`。适合“同一武器站切不同武器时，火控传感器模式也随武器变化”的场景。 |

**破坏性变更（0.5.23+）：** 已删除顶层 `acceleration`、`delay_fuse`、`active_radiation_*`、`tv_missile_*`、`laser_range` 等旧键；爆炸配置在 `detonate_data.explosion_data` 内，不再支持顶层 `explosion` / `explosion_data`。

## `laser_data`（`rvp:laser`）

| 字段 | 说明 |
| --- | --- |
| `range` | 激光有效射程。 |
| `visual_data` | 客户端光束外观（见下表）。 |

## `ahead_data`（`rvp:machinegun`）

| 字段 | 说明 |
| --- | --- |
| `enabled` | 是否启用 AHEAD 自动编程逻辑。 |
| `burst_offset_meters` | AHEAD 相对预瞄点提前多少米开花；正值表示在飞到预瞄点前开花。实际编程公式为 `programmedDistance = leadDistance - burst_offset_meters`。 |
| `require_lock` | 是否要求必须存在锁定目标且能解出预瞄圈；为 `false` 时，无法解出预瞄圈会回退到当前 `AimContext.position` 的瞄点距离。 |
| `min_ground_clearance` | 可编程空爆点的最低离地高度，单位米；低于该值时取消 AHEAD 空爆，让母弹继续飞行/撞击，避免对地过强。默认 `0` 表示不限制。 |

### `laser_data.visual_data` 激光光束（客户端）

| 字段 | 说明 |
| --- | --- |
| `color` | RGBA 0–255。 |
| `width` | 光束宽度（格）。 |
| `duration_tick` | 停火后残留 tick；连发时每次 pulse 刷新。 |
| `pulsate` | 是否脉动宽度。 |
| `render_start_distance` | 炮口沿瞄准方向外推最小距离（与几何裁剪配合，避免穿机体）。 |
| `segment_length` | 分段渲染段长（格），最长合并为 64 段。 |

光束终点由射线检测决定，不穿墙；观察者视角通过深度测试遮挡。

## `fire_data` 开火参数

| 字段 | 说明 |
| --- | --- |
| `require_lock` | 是否要求发射前已有锁定。默认 `true`。GPS、ARM、TV、MCLOS 等通常会手动设为 `false`。 |
| `fire_mode` | 开火模式枚举 `RVP_EnumFireMode`。JSON 须写枚举名，如 `FULL_AUTO`，大小写不敏感；无法识别时默认为 `FULL_AUTO`。 |
| `spread` | 发射角度散布（度）。为空时回退使用武器顶层 `spread`。 |
| `burst_count` | 点射模式每轮发射数量。默认 `1`。 |
| `burst_delay` | 点射模式轮间间隔（毫秒）。默认 `0`。 |
| `charge_tick` | `CHARGE`/`RAILGUN` 的蓄满时间，或 `MINIGUN` 的转速爬升时间（tick）。默认 `10`。旧写法 `charge_time` 已废弃。 |
| `charge_decay_tick` | 从满蓄力/满转速衰减回零所需时间（tick）。默认 `2`。旧的 `minigun_spin_decay_tick` 已并入此字段。 |
| `charge_power_scale` | 按蓄力/转速比例线性放大伤害或初速（`1` = 不放大）。 |
| `max_off_axis_shoot_angle` | 最大离轴发射角（度）。为空时保持旧版“任意角度均可发射”的行为。 |
| `canister_count` | 单次开火的子弹丸数量。`<= 0` 时按 `1` 处理。 |
| `canister_type` | 多弹丸散布类型：`0` 位置散布，`1` 角度散布，`2` 角度散布并沿弹道前向错位以模拟时间散布。 |
| `canister_distribution` | 多弹丸分布方式，默认 `uniform`。 |
| `canister_shape` | 多弹丸散布形状，默认 `circle`。 |
| `canister_diff` | 多弹丸散布半径/角度强度。默认 `0.3`。 |
| `canister_burst_delay_time` | 子弹丸分批抛撒的延时 tick。默认 `0`。 |
| `canister_burst_count` | 子弹丸分几批抛撒。默认 `1`。 |

### `fire_mode` 开火模式

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

## `projectile_data` 弹体运动学

运行时按武器 `type` 与 `has_rocket_engine` 进入**一条**弹道分支（详见 [弹体运动学开发与测试.md §2](./弹体运动学开发与测试.md#2-运行时走哪条弹道)）：

| 分支 | 条件 | 本体参考 |
| --- | --- | --- |
| 机炮积分 | `rvp:machinegun` | `BulletEntity` |
| 推力积分 | `has_rocket_engine` 且 mass/thrust/燃烧时间有效 | `MissileEntity#tickMove` |
| 简化弹道 | 其余 | gravity + drag（+ 可选 constant_speed） |

### 字段一览

| 字段 | 说明 |
| --- | --- |
| `velocity` | 弹体初速/飞行速度（覆盖武器顶层 `velocity`）；为空时使用顶层 `velocity`。 |
| `gravity` | 空中每 tick 垂直加速度，负数向下。 |
| `gravity_in_water` | 水中每 tick 垂直加速度。 |
| `drag` | 空中水平阻力（MCH `DragInAir`）：每 tick 从 `motionX`/`motionZ` 减去 `(分量/|v|)*drag`，不改 `motionY`。 |
| `drag_in_water` | 水中水平阻力，公式同 `drag`（MCH 水中默认无 `DragInAir`；RVP 用本字段可选开启）。 |
| `inherit_vehicle_velocity` | 发射时是否继承载具当前速度。 |
| `constant_speed` | 是否保持恒定速度，仅改变方向。适合导弹、火箭。 |
| `rotate_to_motion` | 是否让实体朝向跟随运动方向。 |
| `max_speed` | 最大速度限制，0 表示不限制。 |
| `min_speed` | 最小速度限制，0 表示不限制。 |
| `turning_factor` | 旧版 MCHR 风格过载参数表。类型为 `Map<RVP_Range<Integer>, Float>`，key 为飞行 tick 区间，value 为该区间的转向因子。为空时不启用。 |
| `has_rocket_engine` | 是否装备火箭发动机，默认 `false`。为 `false` 时不启用推力运动学。 |
| `mass` | 弹体质量（与 `thrust` 共同决定加速度）；仅在 `has_rocket_engine` 为 true 时生效。 |
| `thrust` | 发动机推力。 |
| `motor_burn_time` | 发动机燃烧时间（tick）。 |
| `second_pulse` | 是否启用双脉冲推进（第二段推力）。旧写法 `dual_pulse` 已废弃。 |
| `second_pulse_trigger_speed` | 第二段触发：导弹速度 ≤ 阈值时满足（0 表示不按速度触发）。 |
| `second_pulse_trigger_distance` | 第二段触发：距离锁定目标 ≤ 阈值时满足（0 表示不按距离触发；仅在存在锁定目标实体或锁定坐标时可判定）。 |
| `second_pulse_thrust` | 第二段推力（与 `mass` 决定加速度）。 |
| `second_pulse_burn_time` | 第二段燃烧时间（tick）。 |
| `ignition_delay_tick` | 点火延迟；延迟内继承载具弹射速度（与本体弹仓弹射一致）。 |
| `drag_coefficient` | 速度平方阻力系数。仅火箭发动机分支读取。 |
| `altitude_drag_factor` | 高空空气阻力倍率表。类型为 `Map<RVP_Range<Float>, Float>`，key 为 **世界 Y 坐标区间**，value 为水平阻力倍率；未命中区间或 value 非法时按 `1.0` 处理。旧的 `altitude_drag_*` 分层字段已全部废弃。 |

`altitude_drag_factor` 的运行规则：

- 为空或未命中任何区间时，回退倍率 `1.0`。
- `y` 采样的是**世界坐标**，不是离地高度。
- 推力弹道会把倍率乘到 `drag_coefficient`；简化弹道会把倍率乘到 `drag`。

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

### 火箭发动机与推进回退

**何时启用推力：** `has_rocket_engine: true`，且解析后 `mass > 0`、`thrust > 0`、`motor_burn_time > 0`。否则打日志并退回简化弹道。

**参数写在哪儿：**

1. 推荐全部写在 `projectile_data`。
2. 若弹体内**未写**某键，加载时 `resolvePropulsionFallback` 从武器 JSON **顶层**补全（字段名与本体 `VehicleMissileWeaponData` 相同）。
3. 弹体内**写了**的键一律以弹体为准（含写 `0` 的情况）。

`has_rocket_engine` 为 **false** 时，不跑推力积分；仍可用 `gravity` / `drag` / `constant_speed` 等简化弹道。

**推力有效时的每 tick 近似：** `Δv += lookDir * (thrust/mass)`（燃烧期内）→ 二次阻力 `-drag_coefficient * |v|²` → 重力（默认 `PhysicsEngine.G`，或 `projectile_data.gravity`）。`constant_speed` 不参与；`max_speed` / `min_speed` 仍可钳制速度。

**加载期迁移：** 已废弃的 `speed_factor*` 会换算为 `mass` / `thrust` / `motor_burn_time` 并打开 `has_rocket_engine`；顶层或弹体出现 mass/thrust/motor_burn_time 时会自动补 `has_rocket_engine: true`（若未显式配置）。

### `rvp:machinegun` 与官方机炮弹速

`rvp:machinegun` **不走** 导弹/火箭那套 `accelFactor`、`max_speed` 推进逻辑，而是与官方 `ywzj_vehicle:cannon` 的 `BulletEntity` 一致：

- **RVP 包内请只写** `projectile.velocity` 作为炮口初速（与官方 `auto_cannon` 顶层 `velocity: 16` 同量级，常用 **16**）。
- 若写了顶层 `velocity` 且不为默认值 10，则**以顶层为准**（兼容外部覆盖）；否则用 `projectile.velocity`，**不做倍率换算**。
- 每 tick：`位置 += 速度`；`速度 *= (1 - friction)`；`速度.y -= gravity`。
- `projectile.gravity`：取绝对值作为向下重力（与 `BulletEntity.gravity` 同号约定）。
- `projectile.drag`：映射为线性摩擦 `friction`（默认 **0.01**）。

`projectile.max_speed`、`min_speed`、`constant_speed` 对机枪**不生效**（机枪只用 `projectile.velocity` + `gravity` + `drag`）。

## `misc_data` 杂项显示/信号

| 字段 | 说明 |
| --- | --- |
| `missile_name_on_hud` | 不同距离下在 HUD 上显示的字符串映射。类型为 `Map<RVP_Range<Float>, String>`；value 为 `null` 时表示该距离段不显示。默认近距显示 `MSL`。 |
| `missile_name_on_radar` | 不同距离下在雷达 HUD 上显示的字符串映射。默认 `20` 格外显示 `MSL`。 |
| `signal_intensity_factor_on_radar` | 不同距离下弹药雷达信号强度倍率。类型为 `Map<RVP_Range<Float>, Float>`，默认 `{"[[0,inf]]": 1.0}`。旧字段 `signature_size` 已废弃，请改用本字段表达雷达可探测性。 |

## `fuse_data` 引信

| 字段 | 说明 |
| --- | --- |
| `delay_tick` | 定时引信：飞行 tick ≥ 该值时引爆；0 表示不启用。 |
| `programmable_airburst` | 可编程空爆（MCH）：按 **R（火控锁定键）** 对**弹道落点**（瞄准镜绿框处，非屏幕中心射线）测距，弹体沿弹道飞行 **测距 + `airburst_offset` 米** 时引爆；未测距或测距无效（≤`airburst_measure_min` 或 ≥`airburst_measure_max`）不触发。 |
| `airburst_offset` | 可编程空爆附加距离（米），默认 **3**（对齐 MCH「测距 + 3m」）。 |
| `airburst_measure_min` / `airburst_measure_max` | 有效测距范围（米），默认 **5** / **300**。 |
| `proximity_radius` | 近炸引信检测半径（米），0 表示不启用。 |
| `proximity_fuse_tick` | 近炸解保 tick：出生后至少经过该 tick 才启用；**-1** 表示不限制。 |
| `proximity_fuse_height` | 近炸目标最低高度（格，MCH `ProximityFuseHeight`）：目标 `onGround` 或脚下该深度内有实心方块时**不触发**；默认 **20**。 |
| `proximity_fuse_damage` | 近炸对触发目标实体的直接伤害（MCH `ProximityFuseDamage`）；0 表示仅爆炸。 |
| `proximity_fuse_explosion_damage` / `proximity_fuse_explosion_radius` | 近炸引信触发的爆炸参数；未写时使用 `detonate_data.explosion_data`。 |
| `airburst_explosion_damage` / `airburst_explosion_radius` | 可编程空爆触发的爆炸参数；未写时使用 `detonate_data.explosion_data`。 |
| `detonate_on_life_end` | 生命周期结束时是否爆炸；false 时只消失。 |
| `entity_collision_safe_tick` | 实体碰撞安全引信 tick；生效期间忽略实体碰撞与实体近炸，但仍会撞地。未写时 `rvp:missile` 默认 `3`、`rvp:bomb` 默认 `20`，其它弹种默认 `0`。 |

## `collision_data` 直击、衰减与碰撞

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

### `direct_damage_factor` 子字段

| 字段 | 说明 |
| --- | --- |
| `player` | 对玩家倍率，默认 `1` |
| `living` | 对非玩家生物倍率，默认 `1` |
| `vehicle_default` | 对未单独列出的 `AbstractVehicle` 倍率，默认 `1` |
| `vehicles` | 对象：键为实体类型 ID（如 `ywzj_vehicle:rotary_wing_vehicle`），值为倍率 |

### `damage_decay` 规则

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

## 配置约定

- 载具包武器 JSON **只写** `*_data` 分组中的弹道/伤害/散布；勿在顶层写 `damage`、`inaccuracy`、`velocity`。
- 直接命中伤害：写在 `collision_data.direct_damage`。
- 发射散布：写在 `fire_data.spread`（经 `RVP_WeaponData#getInaccuracy()` 读取）；霰弹为每轮齐射束心偏移，单发为每弹偏移。
- 弹体初速：写在 `projectile_data.velocity`；见「机枪与官方机炮弹速」。
- 近炸半径：`fuse_data.proximity_radius` 优先，否则可读 `detonate_data.explosion_data.proximity_radius`。
- 顶层 `damage` 为默认直击数值；覆盖用 `collision_data.direct_damage`。
- 历史兼容加载仍可能识别少量旧键，但新配置请统一按本文档的 `*_data` 新写法编写，不要再依赖旧 schema 自动迁移。

## `effects_data` 特效

| 字段 | 说明 |
| --- | --- |
| `trajectory_particle` | 飞行轨迹粒子；写 `none` 可关闭。 |
| `impact_particle` | 命中粒子。空或 `minecraft:block` = MCH 默认：方块破碎粒子 + 白烟（`CLOUD`）；`none` 关闭。分布与 MCH `spawnBlockPar` 一致（破碎：`flak_particles_*`；白烟：命中点 ±1 格高斯偏移、速度 `gaussian/200`）。激光命中走 `MCH_WeaponLaser#spawnBlockPar`（无破碎，仅 cloud/smoke/flame）。 |
| `explosion_particle` | 爆炸粒子。空或 `minecraft:explosion` / `explosion_emitter` = 原版 `EXPLOSION_EMITTER` + `EXPLOSION`；`none` 仅关闭额外粒子（`VehicleExplosion` 音效/烟雾仍由本体处理）。 |
| `flak_particles_crack` | MCH `FlakParticlesCrack`：方块破碎粒子基数（实际 +0~2），默认 10。 |
| `num_particles_flak` | MCH `NumParticlesFlak`：白烟数量，默认 3。 |
| `flak_particles_diff` | MCH `FlakParticlesDiff`：破碎粒子速度散布（步枪约 0.1，反坦克约 0.6），默认 0.3。 |
| `caliber` | **仅 `rvp:machinegun`**：口径（毫米），曳光条宽度与弹孔粒子大小。默认 `7.62`。 |
| `tracer_r` / `tracer_g` / `tracer_b` | **仅机枪**：曳光 `energySwirl` RGB，0–1。默认 `1` / `0.85` / `0.2`。 |

机枪飞行曳光与本体相同：固定 `ywzj_vehicle:entity/basic_bullet` + `textures/entity/basic_bullet.png`（`effects_data` 仅控制口径与 `tracer_*` 颜色）。导弹/炸弹飞行模型见 `assets/rvp/display/weapon/<id>.json`。

机枪曳光示例（写在 `effects` 内）：

```json
"effects": {
  "trajectory_particle": "none",
  "impact_particle": "minecraft:block",
  "caliber": 30,
  "tracer_r": 1.0,
  "tracer_g": 0.5,
  "tracer_b": 0.1
}
```

常用短名：`none`、`smoke`、`flame`、`cloud`、`block`、`explosion`、`explosion_emitter`。也可写完整粒子 ID。

## `detonate_data` 落点效果与爆炸

弹体在**方块命中、实体命中、引信/近炸/空爆结束**时，于落点执行爆炸与下列自定义效果（服务端）。

| 字段 | 说明 |
| --- | --- |
| `effects_before_explosion` | 为 `true`（默认）时先执行下方自定义效果再爆炸；为 `false` 时先爆炸再自定义效果。 |
| `explosion_data` | 爆炸参数（`RVP_Explosion`，继承本体 `Explosion` POJO 字段）。与 `collision_data.direct_damage` 无关。 |

### `detonate_data.explosion_data`

| 字段 | 说明 |
| --- | --- |
| `explode` | 是否产生爆炸效果。 |
| `damage` | 爆炸伤害。 |
| `radius` | 爆炸半径。 |
| `proximity_fuze` / `proximity_radius` | 近炸引信；`fuse_data.proximity_radius` 优先，未写时可读此处。 |
| `destroy_block` | 是否破坏方块。 |

### 自定义落点子对象

| 子对象 | 说明 |
| --- | --- |
| `fire_data` | 点燃空气格。`radius`：水平扩散格数（0=仅命中面邻格）；`chance`：每格概率 0–1；`soul_fire`；`on_block` / `on_entity` 是否在方块/实体命中时触发。 |
| `potion_cloud_data` | 生成原版药水云。`effect`（如 `slowness` / `minecraft:poison`）、`amplifier`、`duration_ticks`（作用于实体）、`cloud_duration_ticks`、`radius`、`radius_per_tick`、`targets`。 |
| `potion_effect_data` | 对范围内实体**直接**上 buff，不生成云；字段同上（忽略 `cloud_duration_ticks`）。 |
| `place_block_data` | 放置方块。`block`、`radius`、`chance`、`replace_mode`：`air_only` / `replaceable` / `always`。 |
| `lightning_data` | 召唤闪电。`damage`：是否造成伤害（false 为纯特效）。 |
| `ignite_entity_data` | `radius`、`seconds`（着火秒数）、`targets`。 |
| `knockback_data` | `radius`、`strength`、`targets`。 |
| `clear_plants_data` | `radius`：清除草、花、树叶等可替换植物。 |

`targets`（范围类效果共用）：`living`（默认）、`players`、`hostile`、`non_allied`（排除 owner 与发射载具乘员）、`all`。

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

## `submunition_data` 子母弹 / 空中布撒

飞行中或撞击/引信时生成**弹体实体**或**任意注册实体**（对应 MCH `spawnBulletInAir` 等能力）。

与 `dispenser_data`（**落点**方块/物品布撒）不同：本分组在**飞行过程**或**撞击/引信**时生成载荷。

### 顶层

| 字段 | 说明 |
| --- | --- |
| `releases` | 释放方案数组；见下表。为空则关闭子母弹。 |

### `releases[]` 单条释放方案

| 字段 | 说明 |
| --- | --- |
| `triggers` | 触发器列表，见下表。默认 `["in_flight"]`。 |
| `delay_tick` | `in_flight`：首波前倒计时 tick。 |
| `interval_tick` | `in_flight`：波次间隔；0 = 剩余次数同一 tick 打完。 |
| `release_events` | 释放波次数（每波对每个 payload 各生成 `count` 枚）。为 0 时取各 payload `count` 之和。 |
| `per_tick` | 每个间隔 tick 触发几波（MCH `spawnBulletPerNum`），默认 1。 |
| `payloads` | 本波要生成的弹药列表，见下表。 |
| `parent_action` | 本方案完成后母弹行为：`continue`（默认）、`discard_after_release`、`discard_on_first_spawn`。 |

#### `triggers` 取值

| 值 | 说明 |
| --- | --- |
| `in_flight` | 飞行中按 `delay_tick` / `interval_tick` 释放。 |
| `on_impact` | 致死撞击（方块或实体，穿透耗尽后）。 |
| `on_block_hit` | 仅方块撞击。 |
| `on_entity_hit` | 仅实体撞击。 |
| `on_fuse` | 定时/近炸/空爆等引信引爆前（在爆炸链之前）。 |

### `releases[].payloads[]` 单种载荷

| 字段 | 说明 |
| --- | --- |
| `kind` | `rvp_weapon`（默认）或 `entity`。 |
| `weapon_id` | RVP 武器 id（`rvp:xxx` 或短名 `xxx`）；空 = 克隆母弹武器。 |
| `entity_type` | `kind: entity` 时实体类型，如 `minecraft:arrow`。 |
| `entity_nbt` | 可选 SNBT，生成后 `Entity#load`。 |
| `count` | 每波生成数量，默认 1。 |
| `spread` | 散布，见下表。 |
| `inherit_parent_velocity` | 是否叠加母弹速度，默认 true。 |
| `inherit_vehicle_velocity` | 是否叠加发射载具速度，默认 false。 |
| `velocity_scale` | 速度倍率，默认 1。 |
| `power_scale` | RVP 武器伤害/初速蓄力倍率，默认 1。 |
| `allow_submunition` | 写在**本层 `payloads` 条目**上：为 `true` 时，被生成的弹体可执行**其自身武器 JSON** 的 `submunition_data`（多级火箭、链式战斗部**必须**为 `true`）；默认 `false` 防止叶子弹继续开舱。详见 [子母弹系统与Mi28边界测试.md](./子母弹系统与Mi28边界测试.md)。 |
| `damage_multiplier` | 仅 RVP 弹体：直击伤害倍率（可选）。 |
| `suppress_explosion` | 仅 RVP 弹体：关闭爆炸。 |

### `payloads[].spread` 散布

| 字段 | 说明 |
| --- | --- |
| `mode` | `box`（默认，随机立方）或 `canister`（复用机枪霰弹逻辑）。 |
| `box_spread` | `box` 模式速度扰动幅度（MCH `BombletDiff`）。 |
| `canister_type` | `0` 位置、`1` 角度、`2` 角度+前向错位（同 `fire_data.canister_type`）。 |
| `canister_diff` | 散布强度（度或格）。 |
| `canister_distribution` / `canister_shape` | 同 `fire_data` / `dispenser_data` 的 `distribution`、`shape`。 |

### 示例场景

| 场景 | 配置要点 |
| --- | --- |
| 子母火箭 / 集束炸弹 | `triggers: ["in_flight"]`，多 `payloads` 指向子战斗部 `weapon_id`，`parent_action: discard_after_release`。 |
| 多级火箭 | 多段 `releases`，不同 `delay_tick`，`parent_action: continue`。 |
| 星光导弹分弹头 | 一条 `in_flight`，`release_events: 3`，`payloads` 指向 `rvp:starstreak_dart`，`canister` 散布。 |
| APFSDS 弹托 | `in_flight` + `entity` 载荷（装饰实体）+ `rvp_weapon` 穿甲杆，`discard_on_first_spawn` 仅脱托。 |
| 撞击抛洒 | `triggers: ["on_impact"]`，`release_events: 1`。 |

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

## `dispenser_data` 落点布撒物品（任意武器类型）

对应 MCH `DispenseItem` / `DispenseRange`。**导弹、炸弹、火箭、机枪弹等**均可配置；弹体命中或引信引爆时在落点按**形状 + 密集度 + 分布**采样若干格，对有效方块尝试原版 `useOn` / `use`（骨粉、火把、TNT、萤石等），顺序与 `detonate_data.effects_before_explosion` 一致（相对爆炸先后）。`rvp:dispenser` 类型在配置了 `item` 时仅布撒、不走路径爆炸链。可与 `submunition` 组合：母弹飞行中抛洒多枚子弹药，每枚弹体各自执行一次布撒采样。

### 字段

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

### `shape` 形状

| 值 | 说明 |
| --- | --- |
| `circle` | 水平圆盘：`x² + z² ≤ r²`，竖直范围 `|y| ≤ y_radius`。 |
| `square` | 水平正方形：`|x|,|z| ≤ r`，竖直 `|y| ≤ y_radius`。 |
| `sphere` | 球体：`x² + y² + z² ≤ r²`。 |
| `cube` | 轴对齐立方体：`|x|,|y|,|z| ≤ r`。 |
| `cylinder` | 水平圆盘 + 竖直柱：`x² + z² ≤ r²`，`|y| ≤ y_radius`（未写 `y_radius` 时等于 `r`）。 |
| `diamond` | 八面体（曼哈顿距离）：`|x|+|y|+|z| ≤ r`。 |

### `distribution` 分布（`density` &lt; 100）

| 值 | 说明 |
| --- | --- |
| `uniform` | 在候选格中均匀随机抽取；圆/柱 footprint 为圆盘均匀；`square` footprint 为轴对齐矩形内均匀。 |
| `normal` | 优先靠近落点中心的格（按到原点距离升序取前 N 个）。 |
| `cluster_center` | 同 `normal`，更强中心聚集。 |
| `cluster_edge` | 优先形状外缘的格。 |
| `ring` | 偏好水平半径约 70% 处的环带（适合 `circle`/`cylinder`/`square`）。 |

### 换弹与物品

`reload.ammo` 写对应物品 ID；载具储物舱需备弹。任意原版/模组物品均可，实际效果取决于该物品的 `useOn` 实现。

### 示例

**满密度球形骨粉（MI-28 默认绿化）：**

```json
"dispenser": {
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
"dispenser": {
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
"dispenser": {
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

### 布撒器武器一览（载具包 0.5.22+）

弹速约 `velocity` 2.4–3.2、`max_speed` 3.5–4.5，**无子母弹**（`submunition.count: 0`）。

**实用型**

| 武器 ID | 说明 |
| --- | --- |
| `disp_bonemeal` | 骨粉绿化，圆盘均匀 90% |
| `disp_torch` | 火把照明，圆盘正态 55% |
| `disp_fire` | 打火石燃烧，方形中心聚集 70% |
| `disp_mine` | TNT 布雷，菱形均匀 30% |
| `disp_cobweb` | 蛛网阻滞，方形均匀 60% |
| `disp_stone` | 圆石掩体，圆盘中心聚集 45% |
| `disp_lantern` | 灯笼营地灯，圆盘正态 50% |
| `disp_general` | 通用 TNT 布雷（AH-64 / F-16 等挂架） |

**测试型**（名称带 `[测试]`，MI-28 火箭挂架）

| 武器 ID | 验证点 |
| --- | --- |
| `test_disp_single` | 密度 1，萤石单点 |
| `test_disp_ring` | `ring` 分布，红石块环带 |
| `test_disp_full` | 密度 100，海晶灯满圆盘 |
| `test_disp_normal` | `normal` 分布，干草块 |
| `test_disp_edge` | `cluster_edge`，金块外缘 |
| `test_disp_cube` | `cube` 满密度，铁块（非 surface_only） |

## `guidance_data` 制导数据模型

`guidance_data` 现已改为**直接对应数据模型**的写法，不再使用旧的 `stages[]`、`sources[]`、`seeker_data`、`steering_data`、`human_in_the_loop` 那一整套分段/复合 schema。

本文档以下表格中的字段名使用 **JSON 写法**（全小写 + 下划线）。Java 类中的对应模型分别为：

- `RVP_GuidanceData`
- `RVP_GuidanceDataHITL`
- `RVP_GuidanceDataGPS`
- `RVP_GuidanceDataARM`
- `RVP_TerminalGuidanceData`

**角度约定：**

- `max_lock_angle` 是**完整 FOV**，运行时会自动除以二，转换为单侧半角。
- `max_guidance_angle` 和 `max_off_axis_lock_angle` 是相对轴线的**单侧角度**，运行时不再除以二。

### `RVP_GuidanceData` 公用字段

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `guidance_type` | 主制导类型。支持 `NONE/MCLOS/SALH/SACLOS/LBR/LOSBR/LH/TV/HITL_TV/HITL_CLOS_TV/ATV/IR/AIR/SARH/ARH/GPS/ARM`。 | `RVP_EnumGuidanceType` | `NONE` |
| `guidance_tick_range` | 制导时间范围；`null` 表示立即开始且永不结束。 | `RVP_Range<Integer>` | `null` |
| `guidance_target_distance_range` | 弹药跟踪时，与制导目标点/记忆点的距离范围（格）。 | `RVP_Range<Float>` | `null` |
| `guidance_altitude_range` | 弹药跟踪时，与制导目标点/记忆点的离地高度范围（格）。支持并集区间。 | `RVP_Range<Float>` | `null` |
| `lock_target_distance_range` | 载具火控锁定时，与制导目标点/记忆点的距离范围（格）。 | `RVP_Range<Float>` | `null` |
| `lock_altitude_range` | 载具火控锁定时，与制导目标点/记忆点的离地高度范围（格）。支持并集区间。 | `RVP_Range<Float>` | `null` |
| `enable_ir_hmd` | 是否启用红外弹头瞄。当前仅红外系弹药使用。 | `boolean` | `true` |
| `max_guidance_angle` | 发射后导引头最大跟踪角（单侧角度，度）。 | `int` | `60` |
| `scan_interval_tick` | 发射后导引头自主扫描间隔。主要用于 `ARH/AIR/ARM`。`null` 表示不主动扫描。 | `Integer` | `null` |
| `max_lock_angle` | 导引头搜索视场角（完整 FOV，度）。用于“开机但未锁定”的扫描阶段。 | `int` | `5` |
| `max_off_axis_lock_angle` | 锁定后允许保持的最大离轴角（单侧角度，度）。旧 `guide_head_max_angle` 的功能已并入此字段。 | `int` | `60` |
| `predict_target_pos` | 是否启用比例制导/预测拦截。 | `boolean` | `false` |
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

### 高度范围与头瞄 HUD 的约定

- `guidance_altitude_range` / `lock_altitude_range` 使用 `RVP_Range<Float>`，支持并集区间。
- 旧版“正值代表对空、负值代表对地”的 `lock_min_height` 思路，已由范围表达式接管。
- 当前 HUD 判定依然保留“低空/地面目标”与“空中目标”的区分习惯：
  - 类似 `[[30,inf]]` 的范围可视为空对空导引头逻辑。
  - 类似 `[[inf,10]]` 或低空区间可视为空对地/近地导引头逻辑。

### `RVP_GuidanceDataHITL`

用于 `TV`、`HITL_TV`、`HITL_CLOS_TV` 等人在回路弹药，对应 Java 类 `RVP_GuidanceDataHITL`。

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `hitl_max_turn_deg_per_tick` | 导引头每 tick 最大转动角度，类似方向机速度。 | `int` | `2` |
| `signal_source` | 制导信号源，支持 `FIBER` / `RADIO`。无线电可被方块遮挡。 | `String` | `RADIO` |
| `hitl_max_control_dist` | 最大控制距离（格）。 | `int` | `600` |
| `hitl_max_control_tick` | 最大控制时长（tick）。 | `int` | `200` |
| `hitl_max_look_offset` | HITL 视角最大偏转角度。 | `int` | `30` |
| `hitl_video_modes` | 可用画面模式，如 `COLOR`、`MONO`、`THERMAL`。 | `List<String>` | `["MONO"]` |

### `RVP_GuidanceDataGPS`

用于 `GPS` 类武器，对应 Java 类 `RVP_GuidanceDataGPS`。

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `gps_spread_radius` | GPS 打击散布半径（格），使用正态分布。旧字段 `gps_cep` 已改由本字段表达。 | `float` | `0` |

### `RVP_GuidanceDataARM`

用于 `ARM` 反辐射导弹，对应 Java 类 `RVP_GuidanceDataARM`。

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `radiation_pulse_memory_tick` | 对雷达辐射脉冲的短时记忆时长。 | `int` | `30` |
| `arm_memory_tick` | 完全失去辐射源后，对最后有效辐射源的持续记忆时长。 | `int` | `60` |
| `arm_locked_emitter_bonus` | 对已被火控锁定/预选辐射源的优先级加权。值越高，越不容易被视场内其他辐射源抢走目标。 | `float` | `1.0` |

### `terminal_guidance`（`RVP_TerminalGuidanceData`）

末端制导数据写在 `guidance_data.terminal_guidance` 中，对应 Java 类 `RVP_TerminalGuidanceData`。

| 字段 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `guidance_type` | 末端制导类型。通常用于 `ATV/AIR/ARH/ARM` 等末端自主导引。 | `RVP_EnumGuidanceType` | `NONE` |
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

### 当前支持的 `guidance_type`

| 枚举值 | 说明 |
| --- | --- |
| `NONE` | 无制导。 |
| `MCLOS` | 人工指令线制导。 |
| `SALH` | 半主动激光制导。 |
| `SACLOS` | 当前实际链路更接近激光/指令跟踪写法，后续若拆分真正 SACLOS / MLOS / 激光架束，会在本枚举体系内继续演进。 |
| `LBR` | 预留。 |
| `LOSBR` | 激光架束制导。 |
| `LH` | 激光点制导。 |
| `TV` | 电视寻的。 |
| `HITL_TV` | 人在回路电视制导。 |
| `HITL_CLOS_TV` | 人在回路指令线电视制导。 |
| `ATV` | 主动电视制导。 |
| `IR` | 红外制导。 |
| `AIR` | 主动红外制导。 |
| `SARH` | 半主动雷达制导。 |
| `ARH` | 主动雷达制导。 |
| `GPS` | GPS/坐标制导。 |
| `ARM` | 反辐射制导。 |

### 迁移说明

以下旧写法已不再作为当前推荐配置入口：

| 旧写法 | 新写法 |
| --- | --- |
| `stages[]` / `sources[]` / `phase_resolve_policy` | 直接写 `guidance_data` 主模型字段 |
| `human_in_the_loop` | 使用 `RVP_GuidanceDataHITL` 对应字段 |
| `guide_head_max_angle` | 改为 `max_off_axis_lock_angle` |
| `seek.fov` | 改为 `max_lock_angle` |
| `seek.range` / `lock_min_height` 等旧 seeker 写法 | 改为 `lock_*_range` / `guidance_*_range` 系列 |
| `gps_cep` | 改为 `gps_spread_radius` |
| `terminal_ir_*` / `terminal_*` 散字段 | 统一并入 `terminal_guidance` |

### 示例

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

## 旧类型迁移对照

| 旧类型 | 新写法 |
| --- | --- |
| `ywzj_rvp:gps_bomb` | `rvp:bomb` + `guidance_data.guidance_type = GPS` |
| `ywzj_rvp:tv_missile` | `rvp:missile` + `guidance_type = HITL_TV/HITL_CLOS_TV/TV` |
| `ywzj_rvp:anti_radiation_missile` | `rvp:missile` + `guidance_type = ARM` |
| `ywzj_rvp:active_radar_missile` | `rvp:missile` + `guidance_type = ARH` |
| `ywzj_rvp:semi_active_radar_missile` | `rvp:missile` + `guidance_type = SARH` |
| `ywzj_rvp:manual_guidance_missile` | `rvp:missile` + `guidance_type = MCLOS` |

---

## 载具 JSON 扩展字段（`data/rvp/vehicles/<id>.json`）

以下字段写在载具数据 JSON 的顶层（`ywzj_vehicle` 会忽略未知字段，无需修改本体）。

### UI 预设与观瞄附加字段

| 字段 | 说明 |
| --- | --- |
| `ui_preset` | 载具 UI 预设名。RVP 会优先从 `data/<namespace>/ui_presets/<name>.json` 读取；若未找到，再回退到 `config/limitless_vehicle/ui_presets/<name>.json`。 |
| `show_skeleton` | 是否在观瞄/UI 预设启用时显示载具骨骼俯视图，默认 `true`。 |
| `nctr_name` | 载具在 `MODERN` NCTR 模式下显示的识别名称，默认 `?`。例如 `F-16V`、`AH-64D`、`Z-10`。 |
| `hide_passenger` | `bool`，默认 `false`。`true` 时坐进该载具的玩家在第三人称/旁观模式下不显示（`RenderPlayerEvent.Pre` + `RenderLivingEvent.Pre` 取消渲染）。 |

### 碰撞箱受击倍率

| 字段 | 说明 |
| --- | --- |
| `structure_model` | 可选，结构模型资源 ID（如 `rvp:vehicle/abramsx.structure`）。写了 `hitbox_damage_factor` / `hitbox_era` 时，RVP 会优先按该结构模型的骨骼名匹配受击倍率与 ERA；不写时回退本体当前结构模型。 |
| `hitbox_damage_factor_default` | 未在 `hitbox_damage_factor` 中配置的骨骼的默认倍率；不写时 = 1。 |
| `hitbox_damage_factor` | `Map<String, Float>`：结构模型骨骼名 → 直击该骨骼时的伤害倍率。 |
| `core_distance_scale_multiplier` | 控制本体“命中点离核心越远伤害越低”的衰减强度（0 = 完全关闭衰减，按直击伤害计算；1 = 本体原值）。 |
| `hitbox_era` | 爆炸反应装甲（ERA）配置，见下表。 |

```json
"hitbox_damage_factor_default": 1.0,
"hitbox_damage_factor": {
  "Upper_front": 0.4,
  "Lower_front": 0.8,
  "turret": 0.6
},
"core_distance_scale_multiplier": 0.0,
"hitbox_era": {
  "ERA0": { "damage_factor": 0.35, "min_trigger_damage": 12.0, "explosion": 1.5 },
  "ERA1": { "damage_factor": 0.35, "min_trigger_damage": 12.0 }
}
```

#### `hitbox_era` 子字段

| 字段 | 说明 |
| --- | --- |
| `damage_factor` | 命中该 ERA 时的伤害系数（类似 MCH 的 `damageFactor`）。 |
| `min_trigger_damage` | 触发爆炸反应所需的最小基础伤害；低于此值不引爆、不消耗（防止机枪清空爆反）。 |
| `explosion` | 可选，触发时在该骨块附近播放小爆炸（视觉效果 + 声音），0 = 不播放。 |

ERA 以“特殊碰撞箱”思路实现：命中列表按距离排序，已失效的 ERA 碰撞箱跳过，继续检查后方普通碰撞箱。ERA 的 `damage_factor` 与普通 `hitbox_damage_factor` 互斥（ERA 命中时优先使用 ERA 的系数）。

### 客户端模型渲染联动

ERA 触发失效后，JS 脚本可通过 `ctx.getEntity().rvp_isEraActive("ERA0")` 查询状态并隐藏对应骨骼：

```js
function updateBones(context) {
  const pose = createPoseBuilder();
  const v = context.getEntity();
  if (!v.rvp_isEraActive("ERA0")) pose.hideBone("$ERA0");
  if (!v.rvp_isEraActive("ERA1")) pose.hideBone("$ERA1");
  return pose;
}
```

需要在 `animation_controllers/<vehicle>_controller.json` 的 `graph.base.inputs` 中加入 `{ "type": "script", "function": "updateBones" }`。

---

## 部件 JSON 扩展字段

以下字段写在载具 `parts[]` 内，对应 `type: "ywzj_vehicle:weapon"` 或 `type: "ywzj_vehicle:radar"` 的部件对象。

### 武器站部件扩展（`type: "ywzj_vehicle:weapon"`）

| 字段 | 说明 |
| --- | --- |
| `rvp_fire_control_mode` | RVP 扩展火控模式。当前公开值为 `rvp_rf`，表示在本体 `rf` 火控基础上启用 RVP 的软离轴/半自动跟踪逻辑。未写时走本体行为。 |
| `rvp_rf_off_axis_deg` | `rvp_fire_control_mode: "rvp_rf"` 时允许的离轴角（度），默认 `10`。 |
| `rvp_disable_crt_effect` | 关闭 CRT 扫描线/闪烁等后处理，但保留 CRT 观瞄框架；通常配合 `optical_sight_type: "crt_ui"` 由 RVP 自动写入，手动写 `true` 也可生效。 |
| `rvp_follow_parent_only_part_unit_ids` | 可选，子部件 `part_unit_id` 列表。每 tick 在父 `WeaponUnit.updateRot()` 后，把这些子部件的本地旋转重置回 `baseRotation`，从而实现“只跟随父级整体姿态、不再叠加自身局部旋转”。适合炮塔附属装甲块、装饰件、碰撞代理件等只应整体跟随的部件。 |

### 雷达部件扩展（`type: "ywzj_vehicle:radar"`）

| 字段 | 说明 |
| --- | --- |
| `scan_animation_mode` | 雷达扫描动画模式。当前默认 `mechanical`；主要影响客户端雷达 UI 的扫描线表现。 |
| `scan_period_tick` | 扫描周期（tick）。`0` 表示沿用本体默认/每 tick 检测逻辑；`>0` 时按周期刷新扫描结果。 |
| `scan_line_when_locked` | 锁定目标时是否仍显示扫描线动画，默认 `false`。 |
| `contact_hold_tick` | 雷达目标保持 tick。`0` 表示不额外保持；`>0` 时目标短暂消失后在 UI/ARM 侧保留一段时间。 |
| `enable_hms` | 是否为该雷达启用 HMS/HMD 相关显示与逻辑，默认 `true`。 |
| `scan_min_height` | 雷达允许扫描的最低离地高度（格），默认 `25`。 |
| `scan_max_height` | 雷达允许扫描的最高离地高度（格），默认 `10000`。 |
| `nctr_mode` | 雷达 NCTR 识别档位。`NONE` = 不显示型号；`EARLY` = 固定翼显示 `JET`、直升机显示 `HELI`、`rvp:missile` 显示 `MSL`、`rvp:bomb` 显示 `BOMB`；`MODERN` = 载具显示其 `nctr_name`，RVP 弹药显示具体武器名。默认 `NONE`。 |

补充说明：

- RVP 雷达现在会额外把 `rvp:missile` 与 `rvp:bomb` 纳入雷达扫描/显示目标。
- `nctr_mode` 只影响雷达 UI 上的目标标签显示，不改变本体雷达锁定、告警或命中判定逻辑。

### `rvp_aps` 被动拦截系统（载具 JSON 顶层）

写在载具 JSON 顶层；用于为该载具启用 RVP 的被动拦截系统（APS）。

| 字段 | 说明 |
| --- | --- |
| `enabled` | 是否启用 APS。默认 `false`。 |
| `ammo_max` | APS 最大备弹量。`0` 表示启用系统但没有可发射拦截弹。 |
| `reload_one_tick` | 自动补充 1 发拦截弹所需 tick，默认 `600`。 |
| `cooldown_tick` | 两次 APS 发射之间的最小间隔，默认 `20`。 |
| `intercept_delay_tick` | 检测到来袭弹药后，延时多少 tick 再发射拦截弹，默认 `10`。 |
| `scan_interval_tick` | 扫描来袭威胁的间隔 tick，默认 `1`。 |
| `detect_radius` | 威胁检测半径（格），默认 `32.0`。 |
| `intercept_radius` | 拦截弹生效半径（格），默认 `8.0`。 |
| `projectile_speed_min` | 允许拦截的来袭弹体最低速度，默认 `1.0`。 |
| `projectile_speed_max` | 允许拦截的来袭弹体最高速度，默认 `80.0`。 |
| `animation_part_ids` | 可选，触发 APS 发射动画时允许使用的部件 `part_unit_id` 列表；可用于左右发射器轮换。 |
| `spawn_part_id` | 可选，APS 拦截弹生成位置关联的部件 `part_unit_id`。未写时回退为 `animation_part_ids` 的第一个。 |
| `exclude_owner_projectile` | 是否忽略本载具及其乘员自己发射的 Projectile，默认 `true`。 |

示例：

```json
"rvp_aps": {
  "enabled": true,
  "ammo_max": 8,
  "reload_one_tick": 100,
  "cooldown_tick": 20,
  "intercept_delay_tick": 10,
  "scan_interval_tick": 1,
  "detect_radius": 32.0,
  "intercept_radius": 8.0,
  "projectile_speed_min": 1.0,
  "projectile_speed_max": 80.0,
  "animation_part_ids": ["aps_left", "aps_right"],
  "spawn_part_id": "aps_left",
  "exclude_owner_projectile": true
}
```

### 本体火箭 CCIP 扩展（本体 `VehicleRocket` / `VehicleRocketWeaponData`）

以下字段写在**本体火箭武器 JSON** 上，作用对象是 `ywzj_vehicle` 原生火箭，不是 `rvp:rocket`。

| 字段 | 说明 |
| --- | --- |
| `ballistic_enabled` | 是否启用 RVP 为本体火箭提供的弹道落点预测/CCIP 支持，默认 `false`。 |
| `ballistic_gravity` | CCIP 预测使用的重力系数，默认 `0.03`。仅影响预测，不改真实飞行。 |
| `ballistic_drag` | CCIP 预测使用的阻力系数，默认 `0.002`。仅影响预测，不改真实飞行。 |
| `ballistic_prediction_tick` | CCIP 最多向前模拟的 tick 数，默认 `240`。 |

### UI 预设文件（`data/<namespace>/ui_presets/<name>.json`）

RVP UI 预设现在支持两条加载路径：

- 优先：`data/<namespace>/ui_presets/*.json`
- 回退：`config/limitless_vehicle/ui_presets/*.json`

单个预设文件的结构如下：

| 字段 | 说明 |
| --- | --- |
| `name` | 预设名；省略时可用文件名/资源路径作为查找别名。 |
| `radar` | 主雷达 UI 位置。 |
| `rwr` | RWR UI 位置。 |
| `vehicle_bones` | 载具骨骼俯视图 UI 位置。 |
| `scope_envelope` | 观瞄包线/边框 UI 位置。 |
| `radars` | 多雷达位置表，键为 `part_unit_id`（如 `scan_radar`），值为位置对象；不存在时回退到 `radar`。 |

位置对象通用字段：

| 字段 | 说明 |
| --- | --- |
| `anchor` | 锚点：`center` / `right` / `right_bottom`。未写时默认按 `right` 处理。 |
| `offset_x` | 横向偏移（像素）。 |
| `offset_y` | 纵向偏移，按 1080p 参考高度等比缩放。 |
| `scale` | UI 缩放，默认 `1.0`。 |

示例：

```json
{
  "name": "ps1sm",
  "radar": { "anchor": "right", "offset_x": -220, "offset_y": -240, "scale": 1.0 },
  "rwr": { "anchor": "right_bottom", "offset_x": -180, "offset_y": -180, "scale": 0.9 },
  "vehicle_bones": { "anchor": "center", "offset_x": 0, "offset_y": 180, "scale": 0.8 },
  "radars": {
    "scan_radar": { "anchor": "right", "offset_x": -260, "offset_y": -260, "scale": 1.0 }
  }
}
```

### Gunner 配置文件（`data/<namespace>/gunner_profiles/<id>.json`）

Gunner AI 配置通过资源重载加载；默认扫描目录为 `gunner_profiles`。

| 字段 | 说明 |
| --- | --- |
| `name` | 配置名称；空时回退为文件名。 |
| `faction` | 阵营，默认 `friendly`。 |
| `target_types` | 目标类型列表；常用值：`vehicle`、`player`、`monster`、`living`，也可写实体类型 ID（如 `rvp:missile`）。 |
| `search_radius` | 搜敌半径，默认 `96`。 |
| `scan_interval_tick` | 搜敌间隔，默认 `10`。 |
| `fire_window_deg` | 开火窗口角（度），默认 `6`。 |
| `lead_scale` | 提前量倍率，默认 `1.0`。 |
| `burst_fire_tick` / `burst_rest_tick` | Gunner 扳机按住/松开节奏，默认 `6 / 10`。 |
| `countermeasure_range` | 释放干扰弹的威胁距离，默认 `36`。 |
| `countermeasure_cooldown_tick` | 干扰弹冷却，默认 `80`。 |
| `allow_drive` | 是否允许 Gunner 驾驶载具，默认 `true`。 |
| `drive_pursuit_distance` / `drive_stop_distance` | 地面追击/刹车距离，默认 `64 / 12`。 |
| `drive_stuck_check_tick` / `drive_stuck_distance` / `drive_recovery_tick` | 卡住检测与恢复参数，默认 `20 / 1.0 / 20`。 |
| `rotary_cruise_altitude_min` / `rotary_cruise_altitude_max` | 直升机巡航高度范围，默认 `28 / 60`。 |
| `fixedwing_cruise_altitude_min` / `fixedwing_cruise_altitude_max` | 固定翼巡航高度范围，默认 `150 / 500`。 |
| `fixedwing_combat_radius_min` / `fixedwing_combat_radius_max` | 固定翼攻击半径范围，默认 `40 / 350`。 |
| `ground_wander_enabled` | 地面载具是否启用闲逛式机动，默认 `true`。 |
| `ground_big_turn_interval_tick_min` / `ground_big_turn_interval_tick_max` | 地面大转向触发间隔范围，默认 `300 / 600`。 |
| `ground_big_turn_angle_deg_min` / `ground_big_turn_angle_deg_max` | 地面大转向角范围，默认 `120 / 180`。 |
| `ground_big_turn_duration_tick` | 地面大转向持续时间，默认 `40`。 |
| `air_attack_phase_tick` / `air_disengage_phase_tick` | 空中攻击/脱离阶段时长，默认 `200 / 200`。 |
| `air_initial_disengage_tick_min` / `air_initial_disengage_tick_max` | 起飞后首次脱离阶段随机范围，默认 `300 / 400`。 |

---

## 服务端配置（`config/ywzj_rvp-server.toml`）

RVP 的服务端配置文件，单人/联机均生效，支持 `/forge config reload ywzj_rvp server` 热重载。

### 爆炸坑深控制

| 配置项 | 类型 | 说明 |
| --- | --- | --- |
| `explosion.craterDepthRules` | `String[]` | 格式 `"maxRadius:maxDepth"`，按 `maxRadius` 从小到大排序。空数组 = 不限制。 |

默认值：
```toml
[explosion]
    craterDepthRules = ["5:0", "15:1", "35:2", "65:3", "100:4", "9999:5"]
```

| 爆炸半径 | 最大破坏层数（中心Y以下） |
|---------|------------------------|
| ≤5 | 0（不破坏地表以下） |
| ≤15 | 1 层 |
| ≤35 | 2 层 |
| ≤65 | 3 层 |
| ≤100 | 4 层 |
| >100 | 5 层封顶 |

- 该限制对所有走 `VehicleExplosion` 的爆炸生效（RVP 武器、ywzj_vehicle 本体武器、其他附属）
- 通过 mixin 注入 `VehicleExplosion$ExplosionCollectionTask.finish()` 实现，不修改本体代码
- `ObjectArrayList.removeIf` 在返回给调用方之前过滤掉低 Y 方块，即时破坏和分帧大爆炸两条路径都覆盖
