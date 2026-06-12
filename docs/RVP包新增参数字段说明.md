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
  "fuse_data": {},
  "collision_data": { "direct_damage": 80 },
  "effects_data": {},
  "detonate_data": { "explosion_data": {} },
  "submunition_data": {},
  "guidance_data": { "stages": [] }
}
```

## 顶层 RVP 字段（武器级）

载具包武器 JSON **不要**在顶层写 `damage`、`inaccuracy`、`velocity`（分别用 `collision_data.direct_damage`、`fire_data.spread`、`projectile_data.velocity`）。除 `shoot_interval`、`max_capacity`、`reload` 等武器级字段外，弹道、引信、制导、落点/爆炸等一律写入 `*_data` 分组。

| 字段 | 说明 |
| --- | --- |
| `sub_type` | 可选子类型标记，仅配置可读性；**落点逻辑请用 `detonate_data`**。 |
| `require_lock` | 是否要求发射前已有锁定。GPS、ARM、TV、MCLOS 等通常可设为 `false`。 |

**破坏性变更（0.5.23+）：** 已删除顶层 `acceleration`、`delay_fuse`、`active_radiation_*`、`tv_missile_*`、`laser_range` 等旧键；爆炸配置在 `detonate_data.explosion_data` 内，不再支持顶层 `explosion` / `explosion_data`。

## `laser_data`（`rvp:laser`）

| 字段 | 说明 |
| --- | --- |
| `range` | 激光有效射程。 |
| `visual_data` | 客户端光束外观（见下表）。 |

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
| `fire_mode` | 开火模式枚举 {@link org.ywzj.rvp.weapon.data.RVP_EnumFireMode}（JSON 须写枚举名，如 `FULL_AUTO`，大小写不敏感；无法识别时默认为 `FULL_AUTO`）。 |
| `charge_time` | `CHARGE`/`RAILGUN`：蓄满所需 tick；`MINIGUN`：转速爬满 tick。 |
| `charge_power_scale` | 按蓄力/转速比例线性放大伤害或初速（`1` = 不放大）。 |
| `minigun_spin_decay_tick` | 仅 `MINIGUN`：松开后每 tick 转速衰减量；默认 `max(charge_time/4, 1)`。 |

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
  "charge_time": 10,
  "charge_power_scale": 2.0
}
```

| 字段 | 说明 |
| --- | --- |
| `canister_count` | 单次开火弹丸数量；未写或 ≤0 时视为 1。 |
| `spread` | **发射角度散布（度）**（勿在顶层写 `inaccuracy`）。单发：每枚弹丸随机偏移；霰弹（`canister_count > 1`）：每轮齐射采样一次**束心**偏移，各弹丸再按 `canister_diff` / 网格散布相对束心展开。 |
| `canister_type` | 多弹丸散布：`0` 位置，`1` 角度，`2` 角度 + 前向错位模拟时间散布（`canister_count > 1` 时生效）。 |
| `canister_distribution` | 圆盘散布（`circle`）时控制随机密度；矩形网格（`square`）时决定在网格上优先占用哪些格（`normal` 靠中心、`ring` 靠方环等）。 |
| `canister_shape` | `circle`（默认，随机圆盘）或 `square`（矩形网格排布：16 弹→4×4，12 弹→4×3；格心映射到 `canister_diff` 角度/位置偏移）。 |
| `canister_diff` | 散布强度；type=1/2 时为角度散布，推荐 &gt; 0.5。 |
| `canister_burst_delay_time` | type=2 时沿弹道前向弹丸间距（方块），推荐 &gt; 3。 |
| `canister_burst_count` | 单次 `shoot()` 内齐射轮数（每轮 `canister_count` 枚，同 tick）。 |
| `burst_count` | 仅 `fire_mode: BURST`：每轮点射弹数。 |
| `burst_delay` | 仅 `fire_mode: BURST`：两轮点射间隔（毫秒）。 |

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
| `has_rocket_engine` | 是否装备火箭发动机，默认 `false`。为 `false` 时不启用推力运动学。 |
| `mass` | 弹体质量（与 `thrust` 共同决定加速度）；仅在 `has_rocket_engine` 为 true 时生效。 |
| `thrust` | 发动机推力。 |
| `motor_burn_time` | 发动机燃烧时间（tick）。 |
| `ignition_delay_tick` | 点火延迟；延迟内继承载具弹射速度（与本体弹仓弹射一致）。 |
| `drag_coefficient` | 速度平方阻力系数。 |

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
- 加载时顶层 `explosion` / `explosion_data` 会迁入 `detonate_data`；`guidance_data` 为数组时，顶层 `rigidity_time` 等会迁入 `steering_data`。

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

## `guidance_data` 分段/复合制导

导引头参数写在各制导阶段的 `seeker` 内；激光/瞄准吊舱射程用 `laser_data.range`。人在回路（弹载视角）用 `human_in_the_loop`。

`guidance_data` 可为对象 `{ "phase_resolve_policy": "...", "stages": [...] }`，或直接写 **stages 数组**（加载器会包成对象）。兼容旧键 `phases` / `stage_policy`；加载时会把顶层 `steering_data` / `seeker_data` 下沉合并到各阶段（`seeker_data` 优先合并到首个含 IR/ARH/SARH/ARM 源的阶段）。

运行时：`RVP_GuidancePhaseSelector` 按 **activation**（AND）求值 → 多阶段重叠时按 `phase_resolve_policy` 消解 → 单阶段内按 `composite_mode` 合成 → 跨阶段按 `composite_weight` 混合 → `RVP_GuidanceMath` 转向。

### 顶层字段

| 字段 | 说明 |
| --- | --- |
| `phase_resolve_policy` | 多阶段**同时激活**时的消解策略（已实现）：`highest_specificity`（默认，按 activation 特异性 + 兼容矩阵保留可复合阶段）、`first_phase`（保留 `stages` 数组中第一个激活阶段）、`sticky`（首次消解后记住胜出阶段，直至其不再激活）。 |
| `human_in_the_loop` | 弹载视角 / HITL；`enabled: true` 才开启。参数见下文。 |
| `stages` | 制导阶段列表（每阶段见 `RVP_GuidanceStageData`）。 |

#### `human_in_the_loop`（人在回路）

| 字段 | 说明 |
| --- | --- |
| `enabled` | **必须显式 `true` 才开启**；省略或为 `false` 均关闭。 |
| `control_mode` | `MOUSE`（MCLOS 鼠标驾控）/ `DESIGNATE`（SACLOS 屏幕点选）/ `VIEW`（仅观察）。省略时按制导源自动推断。 |
| `signal_source` | 信号来源：`radio`（无线电，默认）或 `fiber`（光纤）。无线电模式下：每 tick 检测“导弹 ↔ 发射载机”连线是否被**方块**遮挡（空气/水不算）；遮挡时雪花屏且不可操控、导弹惯性飞行；遮挡消失立即恢复；若连续遮挡 ≥ 2 秒（40 tick）则永久断链并强制退出弹载视角。 |
| `control_range` | 玩家可保持弹载视角的最大距离（格）。 |
| `timeout_tick` | 人在回路会话超时（tick）。 |
| `max_turn_deg_per_tick` | MOUSE 模式每 tick 最大转向角（度）；用于限速更新弹体 `hitlSteeringYaw/Pitch`（方向机/高低机手感）。 |
| `max_look_offset_deg` | DESIGNATE 模式鼠标视角偏移上限（度）；省略时取 SACLOS 阶段 `seeker.fov` 的一半。 |
| `video_modes` | `COLOR` / `BW` / `THERMAL` 等可用画面列表。 |

### 阶段 `stages[]`（`RVP_GuidanceStageData`）

| 字段 | 说明 |
| --- | --- |
| `name` | 阶段名称（调试/HUD）。 |
| `activation` | 激活条件对象（各维度 AND，见下表）。 |
| `seeker` | **本阶段**导引头参数（`fov`/`range`/`scan_interval_tick`/`lock_min_height`/抗干扰等）。 |
| `steering_data` | 本阶段转向参数（见下表）。 |
| `composite_weight` | 跨阶段重叠复合时的默认权重，默认 1.0。 |
| `sources` | 本阶段制导源列表。 |

#### `steering_data` 转向参数

| 字段 | 说明 |
| --- | --- |
| `rigidity_time` | **本阶段进入后**的刚性段 tick（从阶段首次激活的 tick 起算）；此期间本阶段制导源不转向。HITL 鼠标驾控同样受此约束。 |
| `turning_factor` | 每 tick 速度向目标插值比例。 |
| `max_degree_of_missile` | 单 tick 最大转向角（度）。 |
| `predict_target_pos` | 是否按弹速预测目标位置。 |
| `tick_end_homing` | 寿命末 N tick 内加大转向力度；0 表示全程一致。 |
| `proportional_navigation_gain` | **预留**，当前版本未接入转向数学。 |
| `max_lateral_accel` | **预留**，当前版本未接入转向数学。 |

#### `seeker` 阶段导引头

| 字段 | 说明 |
| --- | --- |
| `fov` | 搜索/锁定视场角（度）。 |
| `range` | 搜索/锁定距离（格）。 |
| `scan_interval_tick` | IR/ARH/SARH 弹载搜索间隔 tick（**不是** ARM 的 `params.scan_interval_tick`）。 |
| `lock_min_height` | 雷达地杂波高度门限。 |
| `ignore_flares` / `ignore_chaff` | 是否忽略热焰/箔条。 |
| `jam_resistance` / `dircm_resistance` / `decoy_filter` | 抗干扰预留。 |
| `home_on_jam` | 雷达弹干扰源归向。 |

#### `activation` 激活条件（策略模式，AND 关系）

| 字段 | 策略 | 说明 |
| --- | --- | --- |
| `start_tick` / `end_tick` | tick 窗 | `end_tick < 0` 不限制结束；未写表示该维度不限制。 |
| `min_target_distance` / `max_target_distance` | 目标点距离 | 与制导目标点/记忆点的距离（格）。 |
| `min_entity_distance` / `max_entity_distance` | 实体距离 | 与锁定实体中心的距离（格）；无实体时不满足。 |
| `min_altitude_agl` / `max_altitude_agl` | 离地高度 | 弹体离地高度（格）。 |
| `require_target` | 目标存在 | 无目标点且无实体时不激活。 |
| `require_entity_target` | 实体目标 | 无锁定实体时不激活。 |
| `require_illumination` | 照射 | 半主动雷达需平台照射。 |
| `enter_once` | 粘性 | 一旦进入本阶段，后续 tick 保持激活（即使条件不再满足）。 |

**多阶段同时激活 = 跨阶段复合制导**。阶段内先按 `composite_mode` 合成一个方向，再按 `composite_weight` 与其他阶段混合。不兼容组合由 `RVP_GuidanceCompositeCompatibility` 与 `phase_resolve_policy` 消解。

### 易混 / 重复字段说明（含示例）

#### 1. `activation.require_illumination` vs `sources[].params.require_illumination`（SARH）

| 写法 | 作用 |
| --- | --- |
| 仅 `activation` | 无照射时**阶段不激活**（弹可能只靠 IOG 备份或惯性）。 |
| 仅 `params`（默认 `true`） | 阶段可激活，但 SARH 源每 tick 检查照射，掉照射则该源失败。 |
| **两处都写 `true`（推荐）** | 阶段与源行为一致。 |

```json
{
  "name": "midcourse_sarh",
  "activation": {
    "start_tick": 40,
    "require_illumination": true
  },
  "sources": [
    {
      "type": "SARH",
      "params": { "require_illumination": true }
    },
    { "type": "IOG", "priority": 10 }
  ]
}
```

#### 2. `seeker.scan_interval_tick` vs `params.scan_interval_tick`

| 字段位置 | 用于 |
| --- | --- |
| `stage.seeker.scan_interval_tick` | 红外/主动雷达/半主动雷达的**实体搜索**间隔。 |
| `sources[].params.scan_interval_tick` | **ARM 反辐射**扫描辐射源的间隔。 |

```json
{
  "name": "arm",
  "seeker": { "fov": 35, "range": 1024, "scan_interval_tick": 2 },
  "sources": [
    {
      "type": "ARM",
      "params": {
        "scan_interval_tick": 5,
        "memory_tick": 40
      }
    }
  ]
}
```

上例中 `seeker.scan_interval_tick: 2` 对 ARM **无效**；辐射扫描只看 `params.scan_interval_tick: 5`。几何（FOV/距离）仍用 `seeker`。

#### 3. `stage.seeker` vs `laser_data.range`

| 字段 | 谁读 |
| --- | --- |
| `laser_data.range` | 载具瞄准吊舱 / SACLOS 激光指示射线长度。 |
| `stage.seeker.range` | 弹载导引头（IR/ARH/SARH/ARM）锁定与搜索距离。 |

SACLOS 阶段写 `seeker.range` **通常无效**（驾束跟的是指示点，不是弹载雷达头）：

```json
{
  "name": "laser_saclos",
  "seeker": { "range": 4800 },
  "sources": [{ "type": "SACLOS" }]
}
```

应把 4800 写在载具侧 `laser_data.range` 或武器 `laser_data`，而非 SACLOS 阶段的 `seeker`。

#### 4. `priority` vs `weight` vs `composite_weight`

| 字段 | 层级 | 作用 |
| --- | --- | --- |
| `sources[].priority` | 阶段内 | `primary`/`overlay`/`race` 的尝试顺序；也用于判定阶段的「主制导类型」（兼容矩阵）。 |
| `sources[].weight` | 阶段内 | `blend`/`overlay` 时各源方向权重。 |
| `stage.composite_weight` | 跨阶段 | 多阶段同时激活时，本阶段合成方向的权重。 |

末段 IR+ARH 同阶段复合：

```json
"sources": [
  { "type": "ARH", "composite_mode": "blend", "weight": 0.55, "priority": 100 },
  { "type": "IR",  "composite_mode": "blend", "weight": 0.45, "priority": 90 }
]
```

此处 `priority` 不影响 blend 比例（由 `weight` 决定），但 ARH 仍被视作主类型用于跨阶段兼容判断。

#### 5. `require_target` vs `require_entity_target` vs 武器级 `require_lock`

| 字段 | 时机 | 含义 |
| --- | --- | --- |
| `require_lock`（武器顶层） | **发射前** UI | 无火控锁时不让发射。 |
| `activation.require_target` | **飞行中**阶段 | 无目标点且无实体时阶段不激活。 |
| `activation.require_entity_target` | **飞行中**阶段 | 无锁定实体时阶段不激活（更严）。 |
| `params.vehicle_only` | **飞行中**源 | IR 等只追车辆实体。 |

#### 6. `steering_data` 两级覆盖

阶段默认 + 源覆盖（后者优先）：

```json
{
  "steering_data": { "turning_factor": 0.3 },
  "sources": [
    {
      "type": "IR",
      "steering_data": { "turning_factor": 0.5 }
    }
  ]
}
```

IR 源激活时 `turning_factor` 为 0.5；IOG 备份仍为 0.3。`seeker` **没有** source 级覆盖，只在 `stage.seeker`。

#### 7. `rigidity_time` 按阶段进入时刻计算

```json
"stages": [
  {
    "name": "boost",
    "activation": { "start_tick": 0, "end_tick": 9 },
    "steering_data": { "rigidity_time": 8 }
  },
  {
    "name": "terminal",
    "activation": { "start_tick": 10, "enter_once": true },
    "steering_data": { "rigidity_time": 2 }
  }
]
```

- `boost`：tick 0–7 不转向（进入 tick 0 起 8 tick）。
- `terminal`：tick 10–11 不转向（进入 tick 10 起 2 tick），**不是**全局 tick 10–11 与 boost 的 8 混用。

#### 8. `enter_once` vs `phase_resolve_policy: sticky`

| 机制 | 作用 |
| --- | --- |
| `activation.enter_once` | 本阶段一旦激活，**即使**距离/照射条件失效也保持激活。 |
| `phase_resolve_policy: sticky` | 多阶段冲突时，**记住第一次胜出的阶段**，直到其不再激活。 |

二者独立，常组合用于末段制导「粘住」IR 窗口。

#### 复合制导兼容性（不可复合 ❌）

| 类型 A | 类型 B | 说明 |
| --- | --- | --- |
| TV / MCLOS | 任意自主导引 | 人在回路不能与弹载导引头并行 |
| ARM | IR / ARH / SARH | 反辐射与目标追踪导引头逻辑冲突 |
| SARH | ARH | 需照射与主动雷达并行无意义 |
| SARH | IR | 照射链路 vs 红外成像 |
| GPS | SACLOS | 坐标制导 vs 驾束 |

**可复合 ✅**：任意类型 + `IOG`（备份）；`ARH` + `IR`；`GPS` + `IR`；同类型多阶段权重混合等。

### 制导源 `sources[]`

| 字段 | 说明 |
| --- | --- |
| `type` | 制导源类型（见下表）。 |
| `priority` | `primary`/`overlay`/`race` 模式下的尝试顺序。 |
| `composite_mode` | `primary`（默认）/ `blend` / `overlay` / `race` / `disabled`。 |
| `weight` | `blend`/`overlay` 权重，默认 1.0。 |
| `fallback_on_jammed` | 被干扰时是否尝试下一 source。 |
| `take_over_motion` | 直接对齐速度方向（TV/MCLOS）。 |
| `steering_data` | source 级转向参数覆盖（导引头仅在 phase.seeker）。 |
| `params` | 类型专用参数（见 `RVP_GuidanceSourceParamsData`）；**ARM/TV 专用字段也写在此**，不再使用顶层 `arm_data` / `tv_missile_data`。 |

`params` 常用字段（按 source 类型取用）：

| 字段 | 适用 | 说明 |
| --- | --- | --- |
| `scan_interval_tick` | ARM | 辐射源扫描间隔（tick）。 |
| `memory_tick` | ARM / IOG 等 | 丢失目标后的记忆制导 tick。 |
| `radiation_pulse_memory_tick` | ARM | 雷达脉冲记忆窗口。 |
| `reacquire` | ARM / 雷达弹 | ARM：丢失后是否允许再捕获。 |
| `locked_bonus` | ARM | 正在锁定目标的辐射源评分加成。 |
| `require_illumination` | SARH | 源运行时是否检查平台照射（默认 `true`）；与 `activation.require_illumination` 配合使用，见上文易混说明。 |
| `vehicle_only` | IR 等 | 只追踪载具实体。 |
| `reacquire` | ARM / IR | 丢失目标后是否允许再搜索。 |
| `use_target_pos` / `use_last_guidance` | GPS / IOG | 坐标/记忆点制导开关。 |
| `use_weapon_unit_aim` / `use_owner_look` | MCLOS | 线导参考炮塔瞄准或玩家视角。 |

**预留（JSON 可写，运行时未接入）**：`terminal_dive_angle`、`break_on_smoke`、`use_launch_heading`。

| `composite_mode` | 行为 |
| --- | --- |
| `primary` | 按 priority 依次尝试，第一个成功即采用。 |
| `blend` | 所有可用 source 方向按 weight 加权平均。 |
| `overlay` | 最高 priority 为主航向，其余为修正。 |
| `race` | 选与当前朝向角差最小的 source。 |

### 支持的制导源

| 类型 | 说明 |
| --- | --- |
| `NONE` | 无制导。 |
| `IOG` | 惯性制导，飞向记忆点/目标点。 |
| `MCLOS` | 线导，跟随炮塔或玩家视线。 |
| `SACLOS` | 激光驾束/标记点。 |
| `GPS` | GPS 目标点。 |
| `IR` | 红外 seeker。 |
| `ARH` | 主动雷达（仅 `rvp:missile`）。 |
| `SARH` | 半主动雷达，需平台照射。 |
| `ARM` | 反辐射；`params` 写扫描/记忆/再捕获（见上表），探测几何用阶段 `seeker`。 |

弹载电视 / 人在回路：使用 `human_in_the_loop` + `MCLOS` 或 `SACLOS`，**无**独立 `TV` 制导源类型。

### GPS 滑翔 + 末端 IR 示例（替代 `terminal_ir_*`）

```json
"guidance_data": {
  "phase_resolve_policy": "highest_specificity",
  "stages": [
    {
      "name": "gps_midcourse",
      "activation": { "start_tick": 0 },
      "sources": [
        { "type": "GPS", "priority": 100 },
        { "type": "IOG", "priority": 10 }
      ]
    },
    {
      "name": "terminal_ir",
      "activation": { "max_target_distance": 100, "enter_once": true },
      "seeker": { "fov": 40, "range": 80, "scan_interval_tick": 2 },
      "sources": [
        {
          "type": "IR",
          "priority": 100,
          "params": { "vehicle_only": true, "reacquire": false },
          "fallback_on_jammed": true
        },
        { "type": "GPS", "priority": 50 },
        { "type": "IOG", "priority": 10 }
      ]
    }
  ]
}
```

AIM-120 分段示例：

```json
"guidance_data": {
  "stages": [
    {
      "name": "boost_iog",
      "activation": { "start_tick": 0, "end_tick": 9 },
      "steering_data": { "rigidity_time": 8, "turning_factor": 0.35 },
      "sources": [{ "type": "IOG", "priority": 100 }]
    },
    {
      "name": "terminal_arh",
      "activation": { "start_tick": 10, "enter_once": true },
      "steering_data": { "rigidity_time": 8, "turning_factor": 0.35 },
      "seeker": { "range": 256, "fov": 30, "scan_interval_tick": 15 },
      "sources": [
        { "type": "ARH", "priority": 100, "fallback_on_jammed": true },
        { "type": "IOG", "priority": 10 }
      ]
    }
  ]
}
```

## HITL / ARM 示例

人在回路（电视 + 线导）：

```json
{
  "type": "rvp:missile",
  "guidance_data": {
    "human_in_the_loop": {
      "enabled": true,
      "control_mode": "MOUSE",
      "control_range": 2000,
      "timeout_tick": 400,
      "video_modes": ["COLOR", "BW", "THERMAL"]
    },
    "stages": [
      {
        "name": "mclos",
        "sources": [
          {
            "type": "MCLOS",
            "priority": 100,
            "take_over_motion": true
          }
        ]
      }
    ]
  }
}
```

`video_modes`：`COLOR` 彩色；`BW` / `MONO` 黑白；`THERMAL` / `IR` 热成像。

反辐射（`isAntiRadiationMissile()` 由是否含 `ARM` source 判定）：

```json
{
  "type": "rvp:missile",
  "guidance_data": {
    "stages": [
      {
        "name": "arm",
        "seeker": { "range": 1024, "fov": 35, "scan_interval_tick": 2 },
        "sources": [
          {
            "type": "ARM",
            "priority": 100,
            "params": {
              "scan_interval_tick": 2,
              "memory_tick": 40,
              "radiation_pulse_memory_tick": 25,
              "reacquire": true,
              "locked_bonus": 0.5
            }
          },
          { "type": "IOG", "priority": 10 }
        ]
      }
    ]
  }
}
```

## ARM 与 PDW

ARM seeker 会把雷达观测抽象为 PDW：

| PDW 字段 | 说明 |
| --- | --- |
| `timeOfArrivalTick` | 脉冲到达 tick。 |
| `pulseWidthMicroseconds` | 脉冲宽度。 |
| `angleOfArrivalDegrees` | 到达角。 |
| `carrierFrequencyMhz` | 载频。 |
| `amplitude` | 脉冲幅度。 |
| `emitterVehicleId` / `emitterRadarIndex` | 辐射源身份。 |
| `emitterPosition` | 辐射源位置。 |
| `lockedEmission` | 该雷达是否正在锁定目标。 |

当前评分偏好：到达角更小、距离更近、幅度更强、正在锁定的辐射源。

## 旧类型迁移对照

| 旧类型 | 新写法 |
| --- | --- |
| `ywzj_rvp:gps_bomb` | `rvp:bomb` + `GPS`，可加 `IOG`/`IR` 分段。 |
| `ywzj_rvp:tv_missile` | `rvp:missile` + `human_in_the_loop` + `MCLOS`/`SACLOS`。 |
| `ywzj_rvp:anti_radiation_missile` | `rvp:missile` + `ARM` source（`params` 写反辐射参数）。 |
| `ywzj_rvp:active_radar_missile` | `rvp:missile` + `ARH`。 |
| `ywzj_rvp:semi_active_radar_missile` | `rvp:missile` + `SARH`。 |
| `ywzj_rvp:manual_guidance_missile` | `rvp:missile` + `MCLOS`；需弹载视角时加 `human_in_the_loop`。 |

当前运行时只注册七个 `rvp:*` 公开类型。新内容应只依赖 `RVP_` schema。
