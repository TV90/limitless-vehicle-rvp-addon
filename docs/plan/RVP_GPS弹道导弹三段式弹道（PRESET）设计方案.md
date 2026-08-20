# RVP GPS 弹道导弹三段式弹道（PRESET）设计方案

> 状态：设计方案（调研已完成，未实施）
> 适用项目：`limitless-vehicle-rvp-addon`，Minecraft 1.20.1 Forge
> 约束：所有改动只落在 `ywzj_rvp`，不修改 `ywzj_vehicle` 本体源码

## 1. 背景与目标

本体 0.5.8.2 的 `MissileEntity` 支持 `guidance = PRESET` 的弹道导弹弹道：**上升段 → 巡航段 → 俯冲段**（东风-41 `df_41.json` 即为该模式）。RVP 目前 GPS 导弹（如 `m142_atacms.json`）只有两种飞行方式：

1. **低空巡航**：`guidance_data.cruise_start_tick` + `cruise_end_horizontal_dist` 启用后，`steerGpsCruise` 水平指向目标 + 垂直分量向 0 收敛；
2. **攻顶**：`guidance_data.top_attack_height` 配置后，`resolveTopAttackAimPoint` 飞向发射-目标中点上方的高点再俯冲。

两者都缺少弹道导弹特有的"巡航段高度保持平飞"（攻顶过了中点直接俯冲，没有平飞段），且 `top_attack_height` 会被虚拟中段资格检查整体拒绝（`INELIGIBLE_TOP_ATTACK`），ATACMS 这类远程弹道导弹无法走虚拟飞行。

**目标**：为 RVP GPS 导弹引入本体的三段式弹道，实体态与虚拟态弹道一致，可参数化，并让远程弹道导弹能够进入虚拟中段。

## 2. 现状调研

### 2.1 本体 PRESET 弹道原理（参照实现）

核心在 `MissileEntity`：

| 阶段 | 逻辑（`tickPresetGuidance`） |
| --- | --- |
| ASCENT | 目标点 `ascentPos = launch + forward × ascentLead`，y = `launch.y + presetCruiseAltitude`；`ascentLead = min(presetMaxAscentLead, 25% × 水平距离)`。距目标 ≤ `presetAscentRadius` 或 y 达标 → CRUISE |
| CRUISE | 飞向目标头顶正上方 `overheadPos`。垂直分量 = `G/推力 + 高度误差×gain − vy×damping`，钳制 ±`presetCruiseMaxVerticalComponent`；水平分量 = √(1−v²) |
| DIVE | 水平距离 ≤ `diveDist` 或越过目标（`remaining·route ≤ 0`）→ 直扑目标。`diveDist = max(presetDiveRadius, 高度差×presetDiveAltitudeFactor, 转弯半径×presetDiveLeadFactor)`，`转弯半径 = v²/可用向心加速度` |

运动：推力沿 lookAngle（`thrust/mass`）+ 二次阻力 + 固定重力 `-PhysicsEngine.G`；转向用 `maxG × 动压因子(v²/refSpeed²) × G` 限角速度。

### 2.2 RVP GPS 导弹现状

**物理**（`RVP_ProjectileMotion.tickMissileMove`，与本体差异在 2.3 表）：
- 推力沿 lookAngle，`rotate_to_motion=true` 时每 tick 先按速度对齐姿态再推力；
- 二次阻力 × 可选高度阻力因子（`altitude_drag_factor`）；
- 重力 = 配置值 `projectile_data.gravity`（**默认 0**）；
- `min_speed` / `max_speed` 钳制、点火延迟、冷发射、双脉冲。

**制导链**：`RVP_RuntimeGpsGuidanceSource`（`point(targetPos)`）→ `RVP_GuidanceRuntimeController` → `RVP_GuidanceRuntimeMath.applyIntent`：
- GPS 巡航：`isGpsCruiseActive` + `steerGpsCruise`（水平指向 + 垂直向 0 收敛，`cruise_leveling_factor` 控制收敛速度）；
- 攻顶：`resolveTopAttackAimPoint`（apex = 中点上方 `top_attack_height`，过半程/20 格圆柱内进入俯冲）；
- 转向：`projectile_data.rvp_maxg` 已配置时实体端调用 `applySteering`；否则调用共享 `turningFactor` 速度方向插值。

**虚拟中段**（超视距）：
- `RVP_VirtualMidcourseData` + `RVP_RvpTrajectoryIntegrator`（`rvp_current` / `VERSION=7`）：GPS 高度闭环（常量 `CRUISE_ALTITUDE_GAIN=0.015`、`CRUISE_VERTICAL_DAMPING=0.05`、`CRUISE_MAX_VERTICAL_COMPONENT=0.5`）+ `rvp_maxg` 优先/否则 `turningFactor` 的共享转向选择 + `canReachTarget` 最小转弯半径可达性检查；
- 资格检查 `RVP_VirtualMissileEligibility`：**`top_attack_height ≠ 0` 直接拒绝虚拟化**（`INELIGIBLE_TOP_ATTACK`），积分器尚无攻顶/高抛轨迹。

### 2.3 本体 vs RVP 物理差异（影响弹道设计的点）

| 维度 | 本体 `MissileEntity` | RVP `RVP_ProjectileMotion` |
| --- | --- | --- |
| 重力 | 固定 `-PhysicsEngine.G`（0.0245/tick²） | 配置 `gravity`，默认 0，可任意（含上抛） |
| 推力方向 | 始终沿 lookAngle | `rotate_to_motion` 时先对齐速度再推力 |
| 速度限制 | 无 min/max 钳制 | `min_speed`/`max_speed` 钳制 |
| 转向 | `maxG`×动压因子，角速度限幅 | 实体/虚拟统一按 `rvp_maxg` 优先，否则 `turning_factor` |
| 转弯半径概念 | 有（v²/a） | `rvp_maxg` 用 v²/a；`turning_factor` 用 speed/factor 一阶近似 |
| 阻力 | 固定系数 | 系数 × 可选高度阻力因子 |

## 3. 设计方案

### 3.1 总体思路

在 GPS 制导内新增一个**弹道导弹弹道**，三段式状态机只在 GPS 制导且 `preset_cruise_altitude > 0` 时生效（该字段平铺在 `RVP_GuidanceData`，与 `top_attack_height`/`cruise_start_tick` 同一层，不新增嵌套配置块）：

```text
实体端（RVP_GuidanceRuntimeMath / RVP_BaseBullet）
  每次制导：根据 presetPhase（ASCENT/CRUISE/DIVE）生成当前段 steeringTarget
    -> 复用现有 steerPursuit/applyIntent 转向
  物理（RVP_ProjectileMotion）不变

虚拟端（RVP_RvpTrajectoryIntegrator VERSION=7）
  同样的三段式决策 + rvp_maxg/turningFactor 选择器 + canReachTarget 复用
  资格检查：preset_ballistic 启用时允许虚拟化（替代 INELIGIBLE_TOP_ATTACK）
```

不新增制导类型（`RVP_EnumGuidanceType` 仍为 GPS），避免波及 `RVP_GuidanceModelResolver`、HUD 类型判断等既有代码；三段式是 GPS 固定目标弹道的一个"飞行剖面"选项。

### 3.2 三段式状态机（实体端）

状态枚举挂在 `RVP_BaseBullet`（与 `topAttackApexPos` 同级）：

```java
enum RVP_PresetPhase { ASCENT, CRUISE, DIVE }
private RVP_PresetPhase presetPhase = RVP_PresetPhase.ASCENT;
private Vec3 presetLaunchPos;   // 发射点（首次制导时快照）
private Vec3 presetAscentPos;   // 上升段终点
private Vec3 presetOverheadPos; // 巡航段水平目标（目标头顶正上方）
```

弹道初始化（首次进入制导时，仿本体 `initializePresetPath`）：

```java
Vec3 horiz = targetPos - launchPos（y 置 0）;
ascentLead = min(maxAscentLead, horizDist * 0.25);
cruiseY    = launchPos.y + cruiseAltitude;                 // 相对发射点Y（本体语义）
ascentPos    = launch + forward * ascentLead, y = cruiseY;
overheadPos  = (targetPos.x, cruiseY, targetPos.z);
```

制导决策（每次 `applyIntent`，仅 GPS 且 preset 启用时）：

```java
switch (presetPhase) {
  case ASCENT -> {
      target = presetAscentPos;
      if (distanceTo(ascentPos) <= ascentRadius || getY() >= ascentPos.y - ascentRadius)
          presetPhase = CRUISE;
  }
  case CRUISE -> {
      if (shouldBeginDive()) { presetPhase = DIVE; target = targetPos; }
      else target = presetOverheadPos;   // 交给高度闭环（见 3.3）
  }
  case DIVE -> target = targetPos;
}
```

俯冲判定（仿本体 `shouldBeginPresetDive`，RVP 适配）：

```java
horizDist² <= diveDist² 或 passedTarget(remaining·route ≤ 0)
diveDist = max(diveRadius,
               垂直高度差 × diveAltitudeFactor,
               approximateTurnRadius × diveLeadFactor)
approximateTurnRadius = speed² / (等效横向加速度)
  // 实体端：turning_factor 是 blend 比例，无 G；建议用 speed/turningFactor 做一阶近似，
  // 或直接省略该分量（俯冲启动主要由 diveRadius + 高度差×factor 决定），保留 dive_lead_factor 作标定余量
```

### 3.3 巡航段高度闭环

复用 RVP 已有闭环，把参数从常量/既有字段提出来：

```java
// 垂直分量 = 高度误差×gain − vy×damping，钳制 ±speed×maxVerticalComponent
// （RVP 重力是配置值，本体公式里的 G/推力 补偿项在 gravity 配置化下没有直接等价，
//   建议取消该项；gravity=0 时高度闭环天然成立）
verticalCmd = clamp((cruiseY − getY()) × cruiseAltitudeGain − vy × cruiseVerticalDamping,
                    −speed × cruiseMaxVerticalComponent, speed × cruiseMaxVerticalComponent);
desiredDir  = normalize(horizDir.x, verticalCmd/speed, horizDir.z);
```

与现有 `steerGpsCruise`（低空巡航）的差异：**巡航基准高度 = 弹道配置的 cruiseY**，而不是"当前高度/0"。因此建议把 `steerGpsCruise` 扩展为接收目标高度参数（或新增 `steerPresetCruise`），实体与虚拟共用同一套 PD 常数，避免双份算法漂移。

### 3.4 虚拟飞行同步（关键）

远程弹道导弹必然走虚拟中段，**实体段和虚拟段必须跑同一弹道**，否则恢复实体瞬间轨迹折线。

改动：

1. **`RVP_RvpTrajectoryIntegrator`**：`VERSION` 升为 5，`step()` 增加 preset 三段式分支：
   - 输入（`RVP_VirtualGuidanceInput`）增加 `presetPhase`、`launchPos`、`ascentPos`、`overheadPos`（或直接快照 `presetProfile`）；
   - 上升段：`steerToPosition(ascentPos)`；巡航段：`steerGpsCruise`（高度基准 = 巡航高度）；俯冲段：`applySteering(velocity, target−pos, maxGs)`；
   - 可达性：俯冲段直接复用现有 `canReachTarget`；巡航段在目标进入不可接入区时提前转 DIVE（与实体判定一致的俯冲距离公式）。
2. **`RVP_VirtualMissileEligibility`**：把 `INELIGIBLE_TOP_ATTACK` 的判定改为：
   - `preset_cruise_altitude > 0` → 允许（新积分器支持）；
   - 仍配置 `top_attack_height`（旧攻顶）→ 维持拒绝。
3. **快照/恢复**：`RVP_VirtualMissileSnapshot` + SavedData 增加 `presetPhase`、`presetLaunchPos/ascentPos/overheadPos`（虚拟期间 phase 推进后，恢复实体直接续用，不回退 ASCENT）。

### 3.5 姿态语义

按虚拟中段文档 R-01/P0 要求：恢复实体前按最终速度对齐 `xRot/yRot`；虚拟积分器在速度钳制后由最终 velocity 派生姿态（VERSION 5 顺带修复）。弹道导弹 `rotate_to_motion=true` 时实体端每 tick 对齐速度，天然自愈，但恢复瞬间仍需 P0 对齐。

## 4. 参数设计

### 4.1 新增字段（9 个，镜像本体 `VehicleMissileWeaponData`，平铺在 `RVP_GuidanceData`，与 `top_attack_height`/`cruise_start_tick` 同一层）

字段命名、默认值与本体完全一致；`preset_cruise_altitude = 0` 禁用三段式弹道（默认行为不变），其余字段仅在启用后生效：

| 字段 | 类型 | 默认 | 对照本体 | 说明 |
| --- | --- | --- | --- | --- |
| `preset_cruise_altitude` | float | 0 | 512 | 巡航高度，**相对发射点Y**；`> 0` 启用三段式弹道 |
| `preset_max_ascent_lead` | float | 64 | 64 | 上升段前伸量上限，实际取 `min(值, 25%×水平距离)` |
| `preset_ascent_radius` | float | 24 | 24 | 上升段完成判定半径 |
| `preset_dive_radius` | float | 24 | 24 | 俯冲段最小启动水平距离 |
| `preset_dive_altitude_factor` | float | 0.75 | 0.75 | 俯冲距离 = 高度差 × 因子 |
| `preset_dive_lead_factor` | float | 1.5 | 1.5 | 俯冲距离 = 近似转弯半径 × 因子 |
| `preset_cruise_altitude_gain` | float | 0.002 | 0.002 | 高度闭环 P 增益 |
| `preset_cruise_vertical_damping` | float | 0.05 | 0.05 | 高度闭环 D 阻尼 |
| `preset_cruise_max_vertical_component` | float | 0.5 | 0.5 | 垂直分量占速率比例上限 |

使用者通常只写 `preset_cruise_altitude` 一个字段（其余用默认），**零配置即等于本体 df_41 默认弹道**。

### 4.1.1 参数通俗解释（配 9M723 实例）

三段式弹道整体形态（侧视）：

```text
            巡航段（最高点平飞，高度闭环维持）
  上升段   ↗  ─────────────────────→  ↘  俯冲段
发射点 ────┘                             ↘
发射点                                        目标（地面）
```

| 参数 | 一句话含义 | 计算方式 / 生效位置 | 调大的效果 | 调小的效果 |
| --- | --- | --- | --- | --- |
| `preset_cruise_altitude` | **弹道最高点**（巡航高度），相对发射点 Y | 巡航段平飞高度 = `launchY + 值`；`> 0` 才启用三段式 | 飞得更高更远；但俯冲提前量同步变大（见 altitude_factor），巡航段可能被压缩 | 更低更近；俯冲更晚更陡 |
| `preset_max_ascent_lead` | **上升段终点的水平前伸量上限** | 实际前伸 = `min(值, 25%×发射水平距离)`；上升终点 = 发射点前伸该距离 + 爬升到巡航高度 | 爬升更倾斜、上升→巡航过渡平滑 | 近乎垂直爬升、到顶急转水平（**折角**） |
| `preset_ascent_radius` | **上升段完成判定半径** | 距上升终点 ≤ 值 **或** 高度 ≥ 巡航高度 − 值 → 上升结束 | 更早结束上升、提前切巡航 | 更晚结束上升 |
| `preset_dive_radius` | **俯冲最小启动水平距离（兜底）** | 水平距离 ≤ 值 → 无条件进入俯冲 | 近距离也强制早俯冲 | 俯冲更晚 |
| `preset_dive_altitude_factor` | **高空俯冲提前量：高度差 × 因子** | `俯冲距离(高度部分) = 高度差 × 值`；高空不提前下压末端拉不住 | 俯冲更早（弹道更平滑） | 俯冲更晚更陡（末端要能转过弯） |
| `preset_dive_lead_factor` | **转弯提前量：转弯半径 × 因子** | `俯冲距离(转弯部分) = 转弯半径 × 值`；给末端转弯留余量 | 更早开始转 | 更晚开始转 |
| `preset_cruise_altitude_gain` | 巡航高度闭环 **P 增益** | 垂直指令 `+= 高度误差 × 值` | 高度恢复更快，易过冲振荡 | 更平缓 |
| `preset_cruise_vertical_damping` | 巡航高度闭环 **D 阻尼** | 垂直指令 `−= 垂直速度 × 值` | 抑制高度振荡 | 易振荡 |
| `preset_cruise_max_vertical_component` | **垂直分量占速率比例上限** | 垂直指令钳制 `±速率 × 值` | 爬升/下压更快 | 更慢更平 |

**俯冲启动距离（三者取最大，进入俯冲段的判定）：**

```text
diveDistance = max( preset_dive_radius,
                    高度差 × preset_dive_altitude_factor,
                    转弯半径 × preset_dive_lead_factor )
距目标水平距离 ≤ diveDistance → 进入俯冲段
```

**实例**（9M723：巡航 400 / 前伸 140 / altitude_factor 0.4 / lead_factor 1.5，发射水平距离 800 格）：

| 阶段 | 过程 | 距目标水平距离 |
| --- | --- | --- |
| 上升 | 前伸 `min(140, 800×25%=200)` = 140 格，爬升到 400 高 | 800 → 660 |
| 巡航 | 400 高平飞（高度闭环） | 660 → 160 |
| 俯冲 | `diveDistance = max(24, 400×0.4=160, 转弯半径×1.5≈120)` ≈ 160，下压命中 | 160 → 0 |

**常见现象 → 调参速查：**

| 现象 | 原因 | 调法 |
| --- | --- | --- |
| 巡航段消失，上升完直接俯冲 | 俯冲距离 ≥ 剩余航程：巡航高度过高或 `dive_altitude_factor` 过大（如 600×0.75=450） | 降 `preset_cruise_altitude` / 减 `preset_dive_altitude_factor` |
| 上升→巡航折角 | `preset_max_ascent_lead` 太小，几乎垂直爬升 | 调大前伸量（如 64 → 140） |
| 巡航高度过冲振荡 | 高度闭环增益过大/阻尼不足 | 增 `preset_cruise_vertical_damping`、减 `preset_cruise_altitude_gain` |
| 末端拉不住画大弧 | 俯冲太晚或转向不足 | 增 `preset_dive_altitude_factor` / 增末端 `turning_factor` |

### 4.2 复用既有字段（不新增）

| 来源 | 字段 | 弹道导弹中的用途 |
| --- | --- | --- |
| `projectile_data` | `thrust`/`mass`/`motor_burn_time` | 上升段能量；建议 `motor_burn_time` ≥ 到达巡航高度的耗时 |
| `projectile_data` | `rotate_to_motion` | 建议 true：推力沿速度方向，高抛自然成形 |
| `projectile_data` | `gravity` | 俯冲驱动力；默认 0 时高度闭环会自行把导弹压向目标高度，但**建议配置负值**（如 −0.02）加速俯冲、贴近真实弹道 |
| `projectile_data` | `altitude_drag_factor` | 高空稀薄 → 阻力小 → 高速；弹道导弹必配 |
| `projectile_data` | `max_speed`/`min_speed` | 高空速度上限与末端速度下限 |
| `projectile_data` | `turning_factor` | 未配置 `rvp_maxg` 时，按飞行 Tick 约束实体与虚拟转向 |
| `projectile_data` | `rvp_maxg` | 可选最大法向过载（G）；配置后在实体与虚拟链中均优先于 `turning_factor` |
| `virtual_midcourse_data` | `entry_*`/`restore_*` | 虚拟中段进出条件（弹道导弹射程远，必配） |
| `guidance_data` | `max_guidance_angle` | 制导角限制（弹道导弹建议放宽到 ≥ 90，否则上升段被拒） |

## 5. 配置示例（M142 ATACMS 改造）

```json
{
  "type": "rvp:missile",
  "name": "MGM-140 ATACMS",
  "projectile_data": {
    "has_rocket_engine": true,
    "velocity": 2.5,
    "rotate_to_motion": true,
    "max_speed": 20.0,
    "min_speed": 4.0,
    "mass": 0.015,
    "thrust": 0.04,
    "motor_burn_time": 3000,
    "ignition_delay_tick": 20,
    "drag_coefficient": 0.0045,
    "gravity": -0.02,
    "altitude_drag_factor": {
      "[[0,80]]": 1.0,
      "[[80,200]]": 0.35,
      "[[200,inf]]": 0.12
    },
    "turning_factor": { "[[5,inf]]": 0.15 },
    "rvp_maxg": 10.0
  },
  "guidance_data": {
    "guidance_type": "GPS",
    "guidance_tick_range": "[[5,inf]]",
    "max_guidance_angle": 120,
    "predict_target_pos": true,
    "enable_inertial_guidance": true,
    "gps_spread_radius": 5,
    "preset_cruise_altitude": 300.0,
    "preset_max_ascent_lead": 160.0,
    "preset_dive_radius": 40.0,
    "preset_dive_altitude_factor": 0.8
  },
  "virtual_midcourse_data": {
    "enabled": true,
    "entry_min_flight_tick": 60,
    "entry_min_distance_from_launch": 800.0,
    "entry_min_target_distance": 1600.0,
    "restore_target_distance": 768.0,
    "restore_lead_tick": 60,
    "restore_ticket_radius": 1,
    "restore_wait_timeout_tick": 200,
    "max_virtual_flight_tick": 12000,
    "cruise_altitude": null,
    "target_update_mode": "FIXED_SNAPSHOT",
    "on_restore_timeout": "DISCARD"
  },
  "fuse_data": { "delay_tick": 0, "proximity_radius": 3 },
  "detonate_data": {
    "explosion_data": { "explode": true, "damage": 1200, "radius": 18, "destroy_block": true }
  }
}
```

注意：`guidance_data.top_attack_height` **不要与 `preset_*` 同配**（旧攻顶仍被虚拟中段拒绝；同配以 preset 弹道优先，加载日志提示）。

## 6. 调参指南（结合 RVP 物理差异）

| 现象 | 调法 |
| --- | --- |
| 上升段太直、缺少弹道弧度 | 增大 `preset_cruise_altitude`、`preset_max_ascent_lead`；确认 `rotate_to_motion=true`（推力沿速度，重力由 `gravity` 提供下坠弧） |
| 巡航段高度过冲振荡 | 增大 `preset_cruise_vertical_damping`（0.05→0.1），减小 `preset_cruise_altitude_gain` |
| 巡航段拉不起来/到不了高度 | 检查 `motor_burn_time` 是否足够、`max_speed` 是否过低、`thrust/mass` 是否小于 `gravity` 需要 |
| 俯冲太早/太平 | 减小 `preset_dive_altitude_factor`、`preset_dive_radius`；增大 `preset_dive_lead_factor` 相反 |
| 末端俯冲拉不住、画大弧 | 使用 `turning_factor` 时增大末端区间值；使用 `rvp_maxg` 时增大 G 值；`min_speed` 钳制末端速度 |
| 高空速度异常快 | 配 `altitude_drag_factor` 高空低值 + `max_speed` 兜底 |
| 恢复实体瞬间轨迹折线 | 检查虚拟段 phase 快照/恢复（3.4 节）、恢复前姿态对齐（3.5 节） |

## 7. 实施步骤与验证

1. 数据模型：`RVP_GuidanceData` 加 9 个 `preset_*` 字段（默认值同本体，`presetCruiseAltitude=0` 禁用）；`RVP_GuidanceActiveConfig` 透传；默认值不改变现有 GPS 行为。
2. 实体端：`RVP_BaseBullet` 加 phase 状态与初始化；`RVP_GuidanceRuntimeMath` 加弹道导弹分支（仅 GPS + `presetCruiseAltitude > 0`）。
3. 虚拟端：`RVP_RvpTrajectoryIntegrator` VERSION 7 三段式与共享转向参数；`RVP_VirtualMissileEligibility` 放开 preset；快照/恢复加 phase 字段。
4. 验证：
   - 单测：三段式 phase 推进、高度闭环收敛、俯冲判定、实体/虚拟同参轨迹数值一致（黄金输入对照）；
   - 游戏内：ATACMS 发射 → 上升 → 高空平飞 → 俯冲命中；开启虚拟中段后全程弹道连续；恢复点轨迹无折线；
   - 回归：`presetCruiseAltitude=0` 的既有 GPS 导弹（spice/storm_shadow）行为不变。

## 8. 风险与取舍

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 实体/虚拟弹道漂移 | 两态若解析到不同 Tick 或重复施加转向会产生交接折线 | 两态按有效飞行 Tick 读取同一 `rvp_maxg` / `turning_factor`，每 Tick 只执行一种钳制算法 |
| `gravity=0` 的既有导弹误开 preset | 无重力时俯冲全靠高度闭环，轨迹偏"巡航滑翔"而非"弹道下砸" | 文档与校验：preset 建议配负 gravity；不强制（闭环可兜底） |
| 旧攻顶导弹兼容 | `top_attack_height` 仍被虚拟中段拒绝 | preset 与 top_attack 互斥，显式校验并在加载日志提示 |
| 虚拟中段积分器版本 | VERSION 4 → 5 变更在途记录语义 | 按既有治理规则：只在 `lastKnownActiveCount == 0` 时替换；SavedData 保存 implementationId/version |
