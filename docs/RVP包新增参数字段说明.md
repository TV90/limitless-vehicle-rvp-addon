## 前提

本文件说明 **ywzj_rvp** 附属模组在载具包 JSON 中新增、扩展的可配置字段。

- 配置位置：载具包目录（如 `limitless_vehicle/rvp/...`）下的 `data/`、`assets/` 等。
- 雷达、火箭弹道等扩展字段需要安装 **ywzj_rvp** 后才会生效；未安装时写了也会被忽略。
- 修改载具包内容后，重启游戏或执行 `/ywzj_vehicle reload` 重新加载。

---

## 命名规范（2025 重构后）

制作或迁移载具包时请统一遵守以下约定，避免与旧版 `arm_*` / `tv_*`（无 `tv_missile_` 前缀）混用。

| 类别 | 规范 | 示例 |
|------|------|------|
| 武器 `type` | 命名空间 `ywzj_rvp:` + **snake_case** 功能名 | `ywzj_rvp:tv_missile`、`ywzj_rvp:anti_radiation_missile` |
| 专属 JSON 字段 | 与武器类型同前缀的 **snake_case** | `tv_missile_control_range`、`anti_radiation_seek_range` |
| 制导模式 `homing_mode` | 全大写 + 下划线 | `ACTIVE_RADAR`、`SEMI_ACTIVE_RADAR`、`ANTI_RADIATION` |
| 实体注册 ID | 与武器 `type` 路径名一致（无命名空间） | 实体 `ywzj_rvp:anti_radiation_missile` 对应武器 `ywzj_rvp:anti_radiation_missile` |
| Java 类名 | PascalCase，与功能对应 | `VehicleTVMissile`、`AntiRadiationMissileEntity` |

**已不再支持（请勿在新包中使用）：**

- 在 `ywzj_vehicle:missile` 上通过 `homing_mode: "ANTI_RADAR"` + `arm_*` 注入反辐射逻辑（已改为独立武器类型，见下文）。
- TV 旧字段名 `tv_control_range`、`tv_timeout_tick`、`tv_video_modes`（请改为 `tv_missile_*`）。

**读取兼容（仅反序列化时自动映射，新包请直接写新名）：**

| 旧字段 / 旧枚举 | 映射为 |
|-----------------|--------|
| `homing_mode: "ANTI_RADAR"` | `ANTI_RADIATION` |
| `anti_radar: true` | 内部标记（反辐射武器类型加载时仍会设 `homing_mode`） |
| `arm_*` | 对应 `anti_radiation_*`（见反辐射导弹一节） |

---

## RVP 武器类型一览

以下类型由 `ywzj_rvp` 注册；`type` 必须写全名（含 `ywzj_rvp:`）。

| `type` | 实现类 | 实体 | 说明 |
|--------|--------|------|------|
| `ywzj_rvp:gps_bomb` | `VehicleGPSBomb` | `GPSBombEntity` | GPS 制导炸弹 |
| `ywzj_rvp:tv_missile` | `VehicleTVMissile` | `TVMissileEntity` | 电视（手动）制导导弹 |
| `ywzj_rvp:anti_radiation_missile` | `VehicleAntiRadiationMissile` | `AntiRadiationMissileEntity` | 反辐射导弹 |
| `ywzj_rvp:active_radar_missile` | 本体 `VehicleMissile` | 本体 `MissileEntity` | 主动雷达制导（别名，默认 `ACTIVE_RADAR`） |
| `ywzj_rvp:semi_active_radar_missile` | 本体 `VehicleMissile` | 本体 `MissileEntity` | 半主动雷达制导（别名，默认 `SEMI_ACTIVE_RADAR`） |

另：本体 `ywzj_vehicle:rocket` 可通过 mixin 扩展弹道字段（见火箭弹一节）。

---

## 载具包参数（vehicles/*.json）

### 雷达（`type: "ywzj_vehicle:radar"`）

字段写在雷达部件节点内（如 `parts` 里 `id: "radar"` 的对象）。需安装 **ywzj_rvp**。

#### `scan_animation_mode`

- 作用：HUD 上扫描线动画模式。
- 可选值：
  - `mechanical`：扫线跟随雷达本体角度（默认，与未装附属时一致）。
  - `phase`：按固定周期扫掠；全圆连续绕圈，扇区往返。
- 默认：不写等价于 `mechanical`。

#### `scan_period_tick`

- 作用：`scan_animation_mode=phase` 时，扫描线完成一周期的 tick 数。
- 默认：不写或 `<=0` 时退回 `mechanical` 的视觉节奏。

#### `scan_line_when_locked`

- 作用：锁定目标后是否仍绘制扫描线。
- 默认：`false`。

#### `contact_hold_tick`

- 作用：雷达盘上“扫描到的目标点”滞留时间（tick）。
- 行为：`>0` 使用该值；不写或 `<=0` 时取扫描周期的两倍。
- 默认：`0`（自动推导）。

示例：

```json
{
  "id": "radar",
  "type": "ywzj_vehicle:radar",
  "scan_animation_mode": "phase",
  "scan_period_tick": 30,
  "scan_line_when_locked": true,
  "contact_hold_tick": 0
}
```

---

## 载具包参数（weapons/*.json）

### Gunner 配置（`data/ywzj_rvp/gunner_profiles/*.json`）

定义炮手（Gunner）行为模板。生成器可在 `default` / `ground` / `air` / `mixed` / `friendly` / `enemy` / `team` 等 profile 间切换，也可新增自定义 JSON。

#### `name`

- 作用：Profile 显示名，用于标识与调试。
- 默认：文件名。

#### `target_types`

- 作用：允许攻击的目标类型列表。
- 可选值：
  - `vehicle`（仅攻击有驾驶员的载具）
  - `vehicle:enemy_gunner` / `vehicle:friendly_gunner` / `vehicle:team_gunner` / `vehicle:non_allied_gunner`
  - `player`、`monster`、`neutral`、`living`
- 默认：`["vehicle", "monster", "player"]`

#### `faction`

- 作用：阵营语义（是否按队伍过滤目标等）。
- 可选值：`friendly`（默认）、`enemy`、`team`

通用规则：创造模式玩家在世界难度非“困难”时，Gunner 不会攻击创造模式玩家。

#### `search_radius`

- 作用：索敌半径（米）。
- 默认：`96.0`

#### `scan_interval_tick`

- 作用：重新扫描并重选目标的间隔（tick）。
- 默认：`10`

#### `fire_window_deg`

- 作用：炮口与目标方向误差小于该角度（度）时才开火。
- 默认：`6.0`

#### `lead_scale`

- 作用：移动目标提前量系数（`1.0` 默认预测，`0.0` 瞄当前点，`>1.0` 更大提前量）。
- 默认：`1.0`

#### `burst_fire_tick` / `burst_rest_tick`

- 作用：点射持续 / 停火 tick。
- 默认：`6` / `10`

#### `countermeasure_range` / `countermeasure_cooldown_tick`

- 作用：自动反制扫描半径（米）/ 两次反制最小间隔（tick）。
- 默认：`36.0` / `80`

#### `allow_drive`

- 作用：是否允许 Gunner 接管驾驶位移动。
- 默认：`true`

#### `drive_pursuit_distance` / `drive_stop_distance`

- 作用：地面追击开始距离 / 停止顶近距离（米）。
- 默认：`64.0` / `12.0`

#### `drive_stuck_check_tick` / `drive_stuck_distance` / `drive_recovery_tick`

- 作用：卡住检测周期 / 判定位移阈值 / 倒车脱困持续时间。
- 默认：`20` / `1.0` / `20`

#### `rotary_cruise_altitude_min` / `rotary_cruise_altitude_max`

- 作用：直升机巡航高度上下限（米，离地）。
- 默认：`50.0` / `100.0`

#### `fixedwing_cruise_altitude_min` / `fixedwing_cruise_altitude_max`

- 作用：固定翼巡航高度上下限（米）。
- 默认：`150.0` / `500.0`

#### `fixedwing_combat_radius_min` / `fixedwing_combat_radius_max`

- 作用：固定翼作战半径内环 / 外环（米）。
- 默认：`50.0` / `500.0`

#### `ground_wander_enabled`

- 作用：无目标时地面载具是否游荡。
- 默认：`true`

#### `ground_big_turn_interval_tick_min` / `ground_big_turn_interval_tick_max`

- 作用：游荡时大幅转向间隔上下限（tick）。
- 默认：`300` / `600`

#### `ground_big_turn_angle_deg_min` / `ground_big_turn_angle_deg_max`

- 作用：单次大幅转向角度范围（度）。
- 默认：`120.0` / `180.0`

#### `ground_big_turn_duration_tick`

- 作用：执行大幅转向的持续时间（tick）。
- 默认：`40`

#### `air_attack_phase_tick` / `air_disengage_phase_tick`

- 作用：固定翼/直升机攻击阶段 / 脱离阶段持续时间（tick）。
- 默认：`200` / `200`

#### `air_initial_disengage_tick_min` / `air_initial_disengage_tick_max`

- 作用：刚放置时首次爬升脱离阶段时长范围（tick）。
- 默认：`300` / `400`

---

### GPS 炸弹（`type: "ywzj_rvp:gps_bomb"`）

独立武器类型；除下表字段外，可继续写本体导弹/武器通用字段（伤害、爆炸、`reload` 等，数据类继承 `VehicleMissileWeaponData`）。

#### `gravity_scale`

- 作用：重力倍率（越大下坠越快）。
- 默认：`1.0`

#### `fuse_delay_tick`

- 作用：触地/命中后延迟引爆的 tick 数。
- 默认：`0`（立即引爆）

#### `terminal_ir_enabled`

- 作用：末段是否启用红外自导（接近目标点后切换为 IR 跟踪）。
- 默认：`false`

#### `terminal_ir_activation_distance`

- 作用：距预设 GPS 点小于该距离（米）时激活末段 IR；`0` 表示一进入末段逻辑即启用。
- 默认：`0`

#### `terminal_ir_seeker_fov`

- 作用：末段 IR 视场角（度）。
- 默认：`30`

#### `terminal_ir_seek_range`

- 作用：末段 IR 搜索半径（米）。
- 默认：`64`

#### `terminal_ir_scan_interval_tick`

- 作用：末段 IR 重新扫描间隔（tick）。
- 默认：`2`

#### `terminal_ir_vehicle_only`

- 作用：末段是否只跟踪载具目标。
- 默认：`true`

#### `terminal_ir_smoke_break_lock`

- 作用：烟雾等遮挡是否打断末段锁定。
- 默认：`true`

#### `terminal_ir_memory_tick`

- 作用：丢失目标后按最后已知位置飞行的记忆时间（tick）；`0` 为默认策略。
- 默认：`0`

#### `terminal_ir_allow_reacquire`

- 作用：丢失后是否允许重新捕获。
- 默认：`true`

示例：

```json
{
  "type": "ywzj_rvp:gps_bomb",
  "gravity_scale": 0.6,
  "fuse_delay_tick": 10,
  "terminal_ir_enabled": true,
  "terminal_ir_activation_distance": 80.0,
  "terminal_ir_seek_range": 128.0
}
```

---

### 火箭弹扩展（`type: "ywzj_vehicle:rocket"`）

写在火箭武器 JSON 内；安装 **ywzj_rvp** 后生效。不写则保持本体直线飞行与原准星。

#### `ballistic_enabled`

- 作用：是否启用弹道物理与连续预测落点准星。
- 默认：`false`

#### `ballistic_gravity`

- 作用：每 tick 下坠量（方块/tick²）。
- 默认：`0.03`

#### `ballistic_drag`

- 作用：每 tick 速度衰减（方块/tick）。
- 默认：`0.002`

#### `ballistic_prediction_tick`

- 作用：HUD 预测落点最多模拟 tick 数。
- 默认：`240`

示例：

```json
{
  "type": "ywzj_vehicle:rocket",
  "velocity": 10.0,
  "ballistic_enabled": true,
  "ballistic_gravity": 0.03,
  "ballistic_drag": 0.002,
  "ballistic_prediction_tick": 240
}
```

---

### 电视制导导弹（`type: "ywzj_rvp:tv_missile"`）

独立武器 + 独立实体；发射后由玩家手动操导。加载时强制 `guidance: "PRESET"`。

除下表外，可配置本体导弹通用字段（`mass`、`thrust`、`explosion`、`seeker_fov` 等）。

#### `tv_missile_control_range`

- 作用：电视制导最大控制距离（米）。
- 默认：`2000.0`

#### `tv_missile_timeout_tick`

- 作用：进入 TV 制导后最长控制时间（tick）。
- 默认：`200`

#### `tv_missile_video_modes`

- 作用：允许的视频模式列表；客户端按 `4` 在列表内循环。
- 可选字符串（大小写不敏感，部分别名可用）：
  - `COLOR`：彩色
  - `BW`（或 `BLACK_WHITE`、`MONO` 等）：黑白
  - `THERMAL`（或 `IR`）：红外
- 默认：不写等价于 `["COLOR", "BW", "THERMAL"]`；列表全无效时同样退回三者全开。
- 行为：进入 TV 视角时以列表中第一个有效模式为初始模式。

示例：

```json
{
  "type": "ywzj_rvp:tv_missile",
  "tv_missile_video_modes": ["BW", "THERMAL"],
  "tv_missile_control_range": 2000,
  "tv_missile_timeout_tick": 400
}
```

---

### 反辐射导弹（`type: "ywzj_rvp:anti_radiation_missile"`）

独立武器 + 独立实体；被动接收敌方雷达辐射并制导。加载时强制 `guidance: "PRESET"`，未写 `homing_mode` 时默认 `ANTI_RADIATION`。

客户端在导引头开启时可预选辐射源（快捷键见语言文件 `key.ywzj_rvp.anti_radiation_*`）。

除下表外，可配置本体导弹通用字段（`seeker_fov`、`mass`、`thrust` 等）。

#### `homing_mode`

- 作用：写入数据用；类型加载器会保证反辐射语义。
- 推荐值：`ANTI_RADIATION`（兼容读取 `ANTI_RADAR`，会自动改写为 `ANTI_RADIATION`）。

#### `anti_radiation_scan_interval_tick`

- 作用：被动接收机扫描间隔（tick）。
- 默认：`2`

#### `anti_radiation_seek_range`

- 作用：搜索距离（米）。
- 默认：`1024`

#### `anti_radiation_memory_tick`

- 作用：丢失辐射源后按最后已知点惯导的记忆时间（tick）。
- 行为：`>0` 使用该值；`0` 时按敌方雷达 `contact_hold_tick` / 扫描节奏推导默认值。
- 默认：`0`

#### `anti_radiation_allow_reacquire`

- 作用：丢失后是否允许再次捕获。
- 默认：`true`

#### `anti_radiation_allow_fire_without_seeker`

- 作用：是否允许未预选辐射源且未锁定实体时发射。
- 默认：`true`

#### `anti_radiation_radiation_pulse_memory_tick`

- 作用：扫描雷达“辐射脉冲”在接收机上的留存时间（tick）；锁定雷达（持续辐射）不受此限制。
- 默认：`25`

#### `anti_radiation_locked_bonus`

- 作用：目标排序时对“锁定辐射”的加权（相对扫描脉冲的优势）。
- 默认：`0.5`

#### `anti_radiation_preselect_enabled`

- 作用：是否启用客户端辐射源预选（HUD 框选 + 上报服务器）。
- 默认：`true`

**旧字段兼容（反序列化时映射，新包请写 `anti_radiation_*`）：**

`arm_scan_interval_tick`、`arm_seek_range`、`arm_memory_tick`、`arm_allow_reacquire`、`arm_allow_fire_without_seeker`、`arm_radiation_pulse_memory_tick`、`arm_locked_bonus`、`arm_preselect_enabled`

示例：

```json
{
  "type": "ywzj_rvp:anti_radiation_missile",
  "name": "YJ-91",
  "guidance": "PRESET",
  "seeker_fov": 35,
  "homing_mode": "ANTI_RADIATION",
  "anti_radiation_scan_interval_tick": 2,
  "anti_radiation_memory_tick": 40,
  "anti_radiation_seek_range": 1024.0,
  "anti_radiation_allow_reacquire": true,
  "anti_radiation_allow_fire_without_seeker": true,
  "anti_radiation_radiation_pulse_memory_tick": 25,
  "anti_radiation_locked_bonus": 0.5,
  "anti_radiation_preselect_enabled": true
}
```

---

### 雷达制导导弹别名（`active_radar_missile` / `semi_active_radar_missile`）

便于载具包区分制导类型而注册的 **类型别名**，底层仍为本体 `VehicleMissile` + `MissileEntity`。

| `type` | 默认 `homing_mode` |
|--------|-------------------|
| `ywzj_rvp:active_radar_missile` | `ACTIVE_RADAR` |
| `ywzj_rvp:semi_active_radar_missile` | `SEMI_ACTIVE_RADAR` |

- 仅自动补全 `homing_mode` 与旧字段 `arm_*` → `anti_radiation_*` 的迁移（若误写在雷达弹上）；**不会**启用反辐射逻辑。
- 反辐射请使用 `ywzj_rvp:anti_radiation_missile`，不要在本体 `ywzj_vehicle:missile` 上写 `ANTI_RADIATION`。

示例：

```json
{
  "type": "ywzj_rvp:active_radar_missile",
  "name": "Active Radar Missile",
  "guidance": "HOMING",
  "seeker_fov": 30
}
```

---

## 兼容性说明

| 场景 | 行为 |
|------|------|
| 老包不写任何 RVP 新字段 | 与未装附属时一致（雷达/火箭扩展除外） |
| 仍使用 `tv_control_range` 等旧 TV 字段 | **无效**，请改为 `tv_missile_*` |
| 在 `ywzj_vehicle:missile` 上写 `ANTI_RADAR` + `arm_*` | **不再注入反辐射**；请改用 `ywzj_rvp:anti_radiation_missile` |
| 写 `arm_*` 且 `type` 为 `anti_radiation_missile` | 自动映射到 `anti_radiation_*` |
| 雷达新增字段 | 仅影响 HUD 扫描线/_CONTACT 滞留，不改变目标筛选算法 |

---

## 迁移对照（旧文档 → 当前）

| 旧写法 | 当前写法 |
|--------|----------|
| `arm_*`（反辐射） | `anti_radiation_*` |
| `homing_mode: "ANTI_RADAR"` | `homing_mode: "ANTI_RADIATION"` |
| `tv_control_range` | `tv_missile_control_range` |
| `tv_timeout_tick` | `tv_missile_timeout_tick` |
| `tv_video_modes` | `tv_missile_video_modes` |
| 本体导弹 mixin 反辐射 | 独立类型 `anti_radiation_missile` |
