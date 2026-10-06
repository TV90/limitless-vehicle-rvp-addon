# RVP 制导转向气动化方案（最小 JSON 改动）

> 状态：阶段 S1、S2 已实施；当前气动链已接入实体态与虚拟态，但 `rvp_aero_steering` 默认仍为 `false`，尚未进入阶段 S3 代表弹种实弹验证。
> 适用项目：`limitless-vehicle-rvp-addon`，Minecraft 1.20.1 Forge，`ywzj_vehicle` 本体源码**不改**。
> 基准要求：**对现有 JSON 结构做最小改动**——不删除、不改名、不迁移任何现有键；只新增少量可选键，默认值使旧配置可继续工作。

> **2026-09-28 默认高度倍率更新**：下文关于“未配置 `altitude_drag_factor` 时 `ρ(h)≡1`、需要配置该键才有高度效应”的内容是本方案提出时的历史假设，现已不适用。当前缺省或 JSON `null` 使用 Y64→海平面、Y550→约 20 km 的默认大气密度插值表；显式 `{}` 保持 `ρ(h)≡1`。实际字段规则与锚点以 [RVP 包新增参数字段说明](../plan/RVP武器数据模型/RVP包新增参数字段说明.md) 为准。

---

## 0. 结论摘要

三个问题是同一个根因的三张面孔：**RVP 的制导把"转向"实现成了对速度向量的运动学赋值，而不是"气动过载预算 + 能量代价"的结算。**

| 问题 | 现状代码 | 本方案 |
| --- | --- | --- |
| P1 制导直接旋转速度向量 | `RVP_GuidanceRuntimeMath.applyIntent` / `RVP_WireGuidanceSteering.applyFromDirection` 各自算出新速度后 `setDeltaMovement` | 制导只提交「期望方向」，由统一气动求解器裁决本 Tick 实际转角 |
| P2 `rvp_maxg` 不看动压 | `RVP_BallisticTrajectoryMath.applySteering` 用常量 `maxGs` 换算固定可用转角 | 可用过载 `G_avail = rvp_maxg × qp(v, h)`，`qp` 为归一化动压因子 |
| P3 转弯不产生额外阻力 | `applySteering` / `applyTurningFactor` 严格 `保持输入速率` | 按本 Tick 载荷因子 `λ` 平方追加诱导阻力速度损失 `Δv = k_i·λ²·v` |

关键结论：

1. **`turning_factor` 必须一起纳入气动模型**。全载具包 74 个武器配置了 `turning_factor`，**0 个配置 `rvp_maxg`**（只有 `docs/examples/guidance/m12_atacms_preset.json` 示例）。只修 `rvp_maxg` 路径等于什么都没修。本方案把 `turning_factor` 在参考速度下的等效转角折算成「等效设计过载」，从而让两条路径共享同一动压与阻力模型。
2. **JSON 净新增最少 1 个键**（`rvp_induced_drag`），推荐新增 2 个：`rvp_aero_steering`（总开关，默认 `true`）+ `rvp_induced_drag`。另 2 个键（`rvp_ref_speed`、`rvp_turn_rate_limit`）为可选精调，不配置时全自动推导。
3. 现有 `max_speed`、`altitude_drag_factor`、`drag_coefficient`、`rvp_maxg`、`turning_factor` **全部复用**，其 JSON 结构不动。
4. **唯一语义变化**：`rvp_maxg` 从"任意速度下恒定的过载上限"变为"设计动压点的过载上限"（动压不足时按比例减载）。这正是 P2 要修的东西。

---

## 1. 问题定义与代码定位

### 1.1 P1：制导直接旋转速度向量

实体链每 Tick 的顺序（`RVP_BaseBullet.tick` → `tickGuidance()` → … → `tickMotion()`）：

```text
tickGuidance()
  └─ RVP_GuidanceController.tick(this)
       └─ RVP_GuidanceRuntimeMath.applyIntent(context, intent)
            ├─ steerPursuit / steerPredictiveIntercept / steerGpsCruise / steerPresetBallistic…
            ├─ applySteering(current, next, rvpMaxGs)   ← 直接产出「旋转后的速度向量」
            ├─ projectile.setDeltaMovement(next)        ← 运动学赋值
            └─ RVP_ProjectileMotion.applyGuidanceFacing(projectile, next)  ← 姿态瞬间对齐
tickMotion()
  └─ RVP_ProjectileMotion.tickMissileMove(projectile)   ← 之后才做推力 / 阻力 / 重力
```

问题表现：

- 转向是**对速度向量的直接改写**，与弹体姿态、攻角、气动面状态无关。只要有 `turning_factor` 或 `rvp_maxg`，本 Tick 就能拿到一个凭空出现的横向速度增量。
- `turning_factor` 路径更直观：`RVP_TrajectorySteeringMath.applyTurningFactor` 做的是
  `normalize((1-f)·u + f·d) · v`，即**转角由方向误差按比例给出**（P 控制器），不是物理角速率。离轴 90°、`f=0.15` 时单 Tick 转角 ≈ 10°，在 6 格/Tick（120 m/s）下等效角速率 200°/s、横向过载 ≈ 43 G——数值上恰好"像"真实导弹，但它是误差比例产物而非过载预算产物：**离轴角小的时候它几乎不转，离轴角大的时候它瞬间满舵**，与真实弹的"角速率上限"特性相反。
- 同一逻辑在 `RVP_WireGuidanceSteering.applyFromDirection`（HITL/线导直控）里复制了一份，并且还有一条绕过制导的写入：`RVP_RuntimeSaclosGuidanceSource.applyVelocityRotation`（干扰注入，直接水平旋转速度分量）。

### 1.2 P2：`rvp_maxg` 不看动压

`RVP_BallisticTrajectoryMath.applySteering` 当前实现：

```java
double chordRatio = Mth.clamp(maxGs * PhysicsEngine.G / (2.0 * speed), 0.0, 1.0);
double maxTurn = 2.0 * Math.asin(chordRatio);
```

`maxGs` 是常量，只与速度有关，**与空气密度、与"这颗弹此刻是否具备产生这个过载的动压"无关**。对比本体 `MissileEntity.calculateSteeredRotation`：

```java
double dynamicPressureFactor = min(1.0, missileSpeed² / referenceSpeed²);
double maxAccelLimit = this.maxG * dynamicPressureFactor * PhysicsEngine.G;
double maxOmega = maxAccelLimit / missileSpeed;
```

本体有动压因子、有 `referenceSpeed`，RVP 侧把它丢了。后果：高空稀薄空气、低速爬升、关机后能量衰减阶段，RVP 弹仍能按额定满过载急转。

### 1.3 P3：转弯不产生额外阻力

`applySteering` 与 `applyTurningFactor` 的契约都是**严格保持输入速率**（`Vec3.scale(speed)`），且有单元测试固定这一契约：

- `RVP_RvpTrajectoryIntegratorTest.applySteeringPreservesSpeedAndClampsVelocityDeltaByGs`（断言 `assertEquals(current.length(), steered.length())`）
- 同文件 `configuredSteeringFallsBackToTurningFactorWhenRvpMaxGIsAbsent`

物理上，产生升力/侧力的代价是诱导阻力，`C_Di ∝ C_N² ∝ (需用过载)²`。RVP 里转弯零代价，导致"飙车式贴脸急转"成为最优解，能量机动（能源空战）不成立。

### 1.4 三个问题的统一根因

```text
制导（战略层）   ——直接决定速度向量——> 速度
                                    ↑
                     气动/结构（物理层）缺位
```

正确分层应是：

```text
制导（战略层）：我这一 Tick 想让弹体指向 desired
求解器（物理层）：按当前动压能给出的最大转角 θ_aero，(结构上限 G × qp) 折算
                 → θ_applied = min(θ_cmd, θ_aero)，并结算诱导阻力
运动（执行层）：推力 / 阻力 / 重力 → 速度钳制 → 位移
```

### 1.5 现网配置现状（决定方案取舍）

| 键 | 配置该键的武器数 | 说明 |
| --- | --- | --- |
| `turning_factor` | 74 | 主要转向参数，取值 `0.08 / 0.1 / 0.12 / 0.15 / 0.2 / 0.24 / 0.28 / 1` |
| `rvp_maxg` | 0 | 仅文档示例 `docs/examples/guidance/m12_atacms_preset.json` |
| `max_speed` | 89+ | 几乎所有弹体都配（只有 `f16_gbu39` / `f16_spice1000` / `spice_1000` 为 `0`） |
| `drag_coefficient` | 多数导弹 | 与 `has_rocket_engine` 配合 |
| `altitude_drag_factor` | 少数（如 `9k720_9m723`） | 高度阻力倍率表 |

两条重要推论：

- **方案必须同时覆盖 `turning_factor` 路径**，否则对现网零效果。
- `turning_factor: 1`（`lav25_tow2b` / `lav25_tow2n` / `zbl08a_hj73e` / `ucav_switchblade`）语义是"无过载、瞬转"，是线导直控弹的手感依赖项，**必须保持瞬转**，不得被气动模型压住。

### 1.6 现状核验：RVP 弹体是 3DOF（点质量平动）

本方案的物理层定位依赖这个前提，故列出代码证据。

**状态量只有位置与速度**：`RVP_BaseBullet` 的位移积分是 `setPos(position().add(velocity))`，速度就是 `deltaMovement`；没有任何角速度、力矩、转动惯量、攻角或侧滑角字段。全项目弹体侧唯一带 "Rate" 的成员是 `jammingHeadingRate`（干扰配置常量，非状态）。

**姿态是派生量，不是状态量**：

| 场合 | 代码 | 行为 |
| --- | --- | --- |
| 制导改速后 | `RVP_ProjectileMotion.applyGuidanceFacing` | `setXRot/setYRot` **瞬间**对齐速度方向（无速率限制） |
| 无目标惯性段 | `applyMissileCoastFacing(..., MISSILE_COAST_LERP=0.2)` | 一阶 lerp 追随速度（视觉阻尼，非角动量） |
| 弹道/炮射 | `applyRotationFromVelocity` | 同样由速度即时派生 |
| 虚拟中段 | `RVP_VirtualTrajectoryState(position, velocity, xRot, yRot, …)` | 每步由速度重算 `xRot/yRot`，**证明虚拟链同为零转动状态** |

**没有滚转自由度**：弹体侧 0 处 `setZRot` / `getZRot`（该 API 只被载具与远程可见性代理使用），即无 `zRot` 状态、无滚转通道。

**力的来源（3DOF 的全部"3"）**：

| 力项 | 方向 | 位置 |
| --- | --- | --- |
| 推力 | 沿 `getLookAngle()`（= 姿态） | `RVP_ProjectileMotion.tickMissileMove` |
| 二次阻力 | 沿速度反方向 | 同上 |
| 重力 | Y 轴 | 同上 / `tickBallisticMotion` |
| 风漂 | 由风场模型 | `RVP_BaseBullet.applyWindDrift` |
| **制导"转向"** | **直接改写速度方向** | `RVP_GuidanceRuntimeMath` / `RVP_WireGuidanceSteering` |

**没有升力/侧力项**：气动力只抵抗运动（阻力），不产生横向力。因此"转弯"完全由制导直接改写速度方向实现——这正是 P1 的物理根源。

**与严格点质量的 5 处偏差（都是补丁，不是转动动力学）**：

1. `rotate_to_motion: false` 时姿态不跟随速度，推力方向 ≠ 速度方向，产生等效"攻角"观感——但姿态只能被外部显式设定，**没有恢复力矩**，不会自稳。
2. 惯性段 `MISSILE_COAST_LERP = 0.2` 的一阶姿态滞后。
3. 冷发射：`applyPreIgnitionVelocity` 沿载具 OBB 轴给初速，姿态与速度可长时间不一致。
4. HITL 鼠标指令有速率限制（`RVP_HitlSteeringMath.stepYawToward`）——这是**指令惯性**，发生在 `hitlSteeringYaw/Pitch` 上，不是弹体角动量。
5. `applyGuidanceFacing` 的**姿态瞬时跳变**：机头可以单 Tick 转到任意方向，与速度转角是否受限完全无关。

**对方案的直接影响**：

- 3DOF 下**不存在攻角 α**，所以诱导阻力不能用 `C_D(α)` 推导，只能用"转角使用率 `λ`"作代理——这就是 §4.5 采用 `Δv = k_i·λ²·v` 而不是 `C_D0 + k·α²` 的原因。
- 姿态瞬时跳变叠加"速度转向零代价"，共同造成"机头瞬间指向、能量毫发无损"的观感；方案只修速度侧的能量代价，**不修姿态侧的跳变**（属 §6.3 明确的非目标）。
- 若将来要做真 6DOF，需要新增：`xRot/yRot` 角速度状态、力矩模型、攻角→升力映射、网络同步字段、虚拟态状态版本升级。成本远超"最小 JSON 改动"，不在本方案范围。

### 1.7 现状核验：惯性（转动 / 质量 / 动量）如何运作

"惯性"在本项目有三种完全不同的含义，必须分开看，否则结论会互相矛盾：

| 含义 | 现状 |
| --- | --- |
| **转动惯性**（角动量、转动惯量） | **没有**（见 §1.6：无力矩、无角速度、姿态是派生量） |
| **平动质量惯性**（`F = ma` 的 `m`） | **仅推力通道有** |
| **动量惯性**（速度跨 Tick 保持） | **有**，积分器是半隐式欧拉；但被 4 条路径硬覆盖 |

#### 1.7.1 积分器形态

速度是唯一的运动状态（`deltaMovement`），**没有加速度状态**：

```text
v_{n+1} = f(v_n)                 ← 力在本步内一次性施加
x_{n+1} = x_n + v_{n+1}          ← 先算速度、后算位移（半隐式 / symplectic Euler）
```

无外力时保持匀速直线，即标准牛顿第一定律。

#### 1.7.2 质量只出现在推力一处

```java
thrustAccelerationPerTick(thrust, mass) = thrust / max(mass, 1e-6)
```

- **变质量**：`resolveMassAt` 在主燃烧期把质量从 `mass` 线性降到干质量 `mass - fuel_mass`，第二脉冲按干质量工作 → **加速度随燃料消耗递增**，这是全项目唯一体现"惯性随质量变化"的地方。
- **阻力不除质量**：`-drag_coefficient · v²` 直接是**速度增量**（`drag_coefficient` 已把质量吸收进去了）→ 轻重弹的阻力衰减完全相同。
- 重力本来与质量无关。

#### 1.7.3 导弹默认没有重力（关键事实）

`applyPropulsionGravity` 当前是 `return velocity.add(0, gravity, 0)`，而 `gravity` 默认 `0`；本体那行 `velocity.subtract(0, PhysicsEngine.G, 0)` 已被注释掉。全载具包**只有 `9k720_9m723` 配了 `gravity: -0.005`**，其余导弹全部为 0。

⇒ 一枚既没配 `drag_coefficient` 也没配 `gravity` 的导弹，关机后会**永远匀速直线飞下去**。这是"惯性最强"的表现，而不是"没有惯性"。

#### 1.7.4 破坏动量惯性的 4 条路径

| 路径 | 代码 | 后果 |
| --- | --- | --- |
| 制导直接改写速度 | `projectile.setDeltaMovement(next)`（速率严格保持） | 方向跳变不由任何力驱动；能量零代价（即 P3） |
| 速度硬钳制 | `clampSpeed`（`min_speed` / `max_speed` 等比缩放） | 速度墙，无过渡过程 |
| `constant_speed` | 导弹运动与制导使用 `peakFlightSpeed` 作为速率参考；普通弹体继续使用当前速率 | `constant_speed=true` 的导弹按已达到的历史峰值回填，仍受 `max_speed` 限制；未启用恒速的导弹不会因历史峰值自动加速 |
| 位置伺服类 | `tickSmartFuseGuidance`：`toTarget.normalize() × min(base, dist×0.9)`；`tickHitlTvMove`：`lookDir × speed` | 速度由位置/姿态直接决定，无横向惯性 |

另有：跳弹反射 `setDeltaMovement(reflected)`（瞬时，无冲量过程）、子弹药部署四分量独立指数衰减（部分惯性）、`applyWindDrift`（加性外力，有惯性）。

#### 1.7.5 三类弹体的运动链

| 弹体 | 路径 | 阻力 / 重力形态 |
| --- | --- | --- |
| 导弹（`usesPropulsion`） | `tickMissileMove` | 推力（含质量、变质量）/ 二次阻力 / **重力默认 0** |
| 无动力弹（火箭、炸弹、子弹药） | `tickBallisticMotion` → `RVP_UnguidedBallisticMath.stepProjectile` | MCH 语义**只减 X/Z** 的线性阻力 `−dir·drag` / 可配重力；`RVP_BombEntity` 未配重力时补 `−PhysicsEngine.G` |
| 机枪弹 | `RVP_BulletEntity.tickBullet` → `stepCannon` | `v ← v·(1−friction) − gravity`；`cannonFriction` 默认 `0.01`，`cannonGravity` 取自 `projectile_data.gravity`（机枪 JSON 普遍配 `−0.005 ~ −0.03`） |

#### 1.7.6 另一个"惯性"：惯性制导 IOG（勿混淆）

- `enable_inertial_guidance`（`guidance_data` 与 `terminal_guidance` 各一份）：制导源评估失败时改用记忆点 `getLastGuidancePos()` 继续指向（`RVP_GuidanceRuntimeController:53-58`）。
- `RVP_EnumGuidanceType.IOG` 在新 schema 中**禁止公开配置**（`RVP_GuidanceDataAdapter:75-76` 直接抛 `JsonParseException`）。
- 注意：这是"记忆目标点继续飞"，**速度方向仍被制导逐 Tick 修正**，不等于弹道惯性滑行。

#### 1.7.7 对方案的直接影响

- 惯性只存在于"力 → 速度"这一段；制导分支是运动学跳变，完全绕开惯性 → 这正是 P1 / P3 的机理。
- 对未启用恒速的导弹，因默认 `gravity = 0`，诱导阻力可成为**关机后除 `drag_coefficient` 之外的速度衰减源**；放在速度钳制之后，可避免 `max_speed` 钳制把这次减速补回。`constant_speed=true` 的导弹按既定豁免跳过诱导阻力，并在实体运动中按历史峰值回填，故该配置下看不到诱导阻力造成的持续掉速。

---

## 2. 设计目标与约束

**目标**

1. 制导不再"自产速度向量"：只提交期望方向；实际转角由气动求解器按动压裁决。
2. `rvp_maxg`（或 `turning_factor` 折算的等效值）乘动压因子 `qp`，构成**可用过载预算**。
3. 转弯按载荷因子 `λ²` 产生诱导阻力速度损失，使持续大过载机动付出能量代价。
4. 实体链与虚拟中段链（`RVP_RvpTrajectoryIntegrator`）使用**同一套纯数学求解器**，交接点不出现转向能力跳变。

**约束**

| 约束 | 落实方式 |
| --- | --- |
| 不改 `ywzj_vehicle` 本体 | 全部改动落在 `org.ywzj.rvp.*`；`PhysicsEngine.G` 只读复用 |
| 最小 JSON 改动 | 不删除/改名/迁移任何现有键；新增键全部可选且有行为等价默认 |
| 禁止硬编码武器 ID | 求解器只吃数据字段，不看 `weaponId` |
| 禁止旧版 JSON 迁移 | 无 `legacy*` / `migrate*` / 旧键别名 |
| `RVP_*Data` JavaDoc | 4 个新字段按 `RVP_FireData` 同级规范写单位/默认/生效条件 |
| 禁止新增 Mixin | 本方案 100% 在 RVP 自有类内完成，无需注入本体 |
| 默认可回滚 | `rvp_aero_steering: false` 单键回退到当前行为 |

---

## 3. 总体方案

### 3.1 改前 / 改后数据流

```text
【改前】
desired ──turningFactor blend──┐
                              ├──> 速度（速率被强制保持）
rvp_maxg ──2asin 弦长钳制─────┘

【改后】
desired ─────────────────────────────┐
v, h ──> qp(v,h) ─┐                   │
rvp_maxg ─────────┼─> G_avail ─> θ_aero ├─> θ_applied = min(θ_cmd, θ_aero)
turning_factor ───┘                      │        λ = θ_applied / θ_aero
                                         │
                                         ├─> 新速度 = slerp(u, d, θ_applied/θ_cmd)·v
                                         └─> λ（本 Tick 载荷因子）
                                                    │
tickMotion: 推力 → 二次阻力 → 重力 → min/max 速度钳制 → −k_i·λ²·v（诱导阻力）
```

### 3.2 与现有 Tick 顺序的配合（重要）

实体链顺序是 `tickGuidance()` → `tickHit()` → `tickMotion()`。制导阶段拿到的 `v` 是**上一 Tick 结算后的速度**，它写回的方向由本 Tick `tickMotion` 消耗（推力沿 `getLookAngle()`，而 `applyGuidanceFacing` 已把姿态对齐到新速度）。

因此：

- **转角裁决放在制导阶段**（需要 `current` 与 `desired`，且必须在 `applyGuidanceFacing` 之前）。
- **诱导阻力放在运动阶段**（`RVP_ProjectileMotion.tickMissileMove` 内、速度钳制之后），这样它不会被同 Tick 的推力/二次阻力覆盖，也不会被 `max_speed` 钳制吸收掉。制导阶段只把 `λ` 存到弹体的一个 transient 字段。

> 为什么诱导阻力必须放在速度钳制之后：RVP 的 `thrust/mass` 数值极大（如 `pl_15`：`0.045/0.012 = 3.75 格/Tick²`，2 Tick 内就顶到 `max_speed`），若把诱导阻力与气动阻力一起放在钳制之前，`clampSpeed` 会把它加回去，"转弯掉速"完全不可见。把诱导阻力定义为**独立于推力的能量损耗**（放钳制之后、仅受 `min_speed` 保护）是有意为之的游戏化抽象，需要在文档与 JavaDoc 中写明。

---

## 4. 数学模型

### 4.1 记号与单位

| 记号 | 含义 | 单位 |
| --- | --- | --- |
| `v` | 当前速率 | 格/Tick（1 格/Tick = 20 m/s） |
| `g` | `PhysicsEngine.G = 9.8/400` | 0.0245 格/Tick²，即 1 G |
| `v_ref` | 动压参考速度 | 格/Tick |
| `ρ(h)` | 高度密度比 | 无量纲 |
| `qp` | 归一化动压因子 | 0～1 |
| `θ_cmd` | 制导期望转角 = `angle(u, d)` | rad |
| `θ_aero` | 本 Tick 气动可用最大转角 | rad |
| `θ_applied` | 实际转角 = `min(θ_cmd, θ_aero)` | rad |
| `λ` | 载荷因子 = `θ_applied / θ_aero` | 0～1 |
| `k_i` | 诱导阻力系数 | 无量纲 |

### 4.2 动压因子 `qp`

```text
v_ref = rvp_ref_speed                       （显式配置且 > 0）
      → max_speed                            （> 0）
      → 武器初速 velocity                     （> 0）
      → 3.0                                  （兜底常量 = 60 m/s）

ρ(h)  = projectile_data.resolveAltitudeDragFactor(y)     ← 复用现有高度阻力倍率表
qp    = clamp(ρ(h) · (v / v_ref)², qp_min, 1.0)
qp_min = 0.05                                             ← 常量，防止发射瞬间零舵
```

设计要点：

- **上限钳到 1.0**：`rvp_maxg` 永远是硬上限，动压只能减载不能增载——结构与气动强度不会因为飞得快而"变强"。
- **密度复用 `altitude_drag_factor`**：该表本来就是"阻力倍率"，诱导阻力与阻力同源（都 ∝ ρ），复用它物理自洽、且零新增字段。未配置该表的弹 `ρ(h) = 1.0`，行为只受速度影响。
- **`qp_min` 存在的理由**：`pl_15` 的 `velocity = 0.3`、`max_speed = 6.0`，发射首 Tick `qp = 0.0025`，若不设下限会得到 ~0.1 G 的可用过载，等于发射瞬间完全锁舵。0.05 的下限保证极低速时仍有微弱舵效。
- **`v_ref` 取 `max_speed` 的风险与对策**：若某弹的 `max_speed` 是"永远达不到的名义值"（如 `9k720_9m723`：`max_speed = 9.0`，而 `thrust/drag` 平衡速度约 1.0～5.2 格/Tick），则该弹会长期处于 `qp ≪ 1` 的低权限状态。这是**自洽的**（飞不到设计速度就不该有设计过载），但需要在调参指南里明确指出：要么把 `max_speed` 调到实际速度，要么显式配 `rvp_ref_speed`。

### 4.3 可用过载与可用转角

```text
G_design = rvp_maxg                        （显式配置，0 表示禁止转向）
         | G_fromTurningFactor(turning_factor, v_ref)   （见 4.4）
         | +∞（turning_factor ≥ 1，语义 = 无过载瞬转，保持现状不参与气动限制）

G_avail  = G_design · qp                              [G]
a_max    = G_avail · PhysicsEngine.G                  [格/Tick²]

θ_aero_g = 2 · asin( clamp( a_max / (2v), 0, 1 ) )     ← 弦长法，与现有 applySteering 同源
θ_aero   = rvp_turn_rate_limit > 0
           ? min(θ_aero_g, toRadians(rvp_turn_rate_limit))
           : θ_aero_g

θ_applied = min(θ_cmd, θ_aero)
λ         = θ_aero > 0 ? θ_applied / θ_aero : 0
新速度     = slerp(u, d, θ_cmd > 0 ? θ_applied / θ_cmd : 0) · v
```

弦长法推导与现有实现一致：单位时间内速度变化量 `|Δv| = a_max`，而 `|Δv| = 2v·sin(θ/2)`，故 `θ = 2·asin(a_max/(2v))`。

### 4.4 `turning_factor` → 等效设计过载

现有 blend 在离轴角 `θ` 下产生的转角：

```text
θ_tf(θ) = atan2( f·sinθ , (1−f) + f·cosθ )        f = turning_factor（钳制到 0～1）
最坏情形 θ = 90° → θ_tf = atan( f / (1−f) )
```

把 `θ_tf` 视为"设计动压点的单 Tick 转角预算"，反解等效设计过载：

```text
G_fromTurningFactor = 2 · v_ref · sin(θ_tf / 2) / PhysicsEngine.G
```

映射表（`v_ref = max_speed`；表中 G 为设计点等效过载）：

| `turning_factor` | `θ_tf`（离轴 90°） | G @ `v_ref`=1 | G @ `v_ref`=3 | G @ `v_ref`=6 | 现网用例 |
| --- | --- | --- | --- | --- | --- |
| 0.08 | 4.97° | 3.5 | 10.6 | 21.2 | `f14a_iriaf_aim23b` |
| 0.10 | 6.34° | 4.5 | 13.5 | 27.1 | `f14d_aim54c`、`rafale_mica_em/ng` |
| 0.12 | 7.77° | 5.5 | 16.6 | 33.2 | `f16_aim260a`、`irist_sl`、`yj_91` |
| 0.15 | 10.01° | 7.1 | 21.4 | 42.7 | `pl_15`、`f16_aim120d`、`ea18g_agm88` |
| 0.20 | 14.04° | 10.0 | 29.9 | 59.8 | `f22a_aim9m`、`j20a_pl10`、`kd_88a` |
| 0.24 | 17.53° | 12.4 | 37.3 | 74.6 | `mi28_kh_39`、`vt4_gp125`、`t90m_9m119m1` |
| 0.28 | 21.25° | 15.1 | 45.2 | 90.3 | `ah64_agm179_*`、`su57_kh58` |
| 1.00 | 90° | +∞ | +∞ | +∞ | `lav25_tow2b/n`、`zbl08a_hj73e`、`ucav_switchblade` |

结论：现网配置的等效过载落在 **3～90 G** 区间，量级合理，**大多数武器不需要改 `turning_factor`**。若某弹希望更真实，可用 `rvp_maxg` 直接覆盖（显式 `rvp_maxg` 优先于折算值）。

### 4.5 诱导阻力

```text
Δv_induced = k_i · λ² · v            [格/Tick]
k_i = rvp_induced_drag                （显式配置，0 = 关闭）
    → drag_coefficient                （缺省复用，同量级近似）
```

- `λ` 是"舵面/攻角使用率"：平飞微调 `λ → 0` 几乎不掉速；满舵急转 `λ → 1` 掉速最快。对应 `C_Di ∝ C_N² ∝ λ²`。
- 结算位置：`RVP_ProjectileMotion.tickMissileMove` 末尾，`clampSpeed` 之后：

```java
double loss = k_i * lambda * lambda * velocity.length();
velocity = velocity.normalize().scale(Math.max(velocity.length() - loss, minSpeedFloor));
```

`minSpeedFloor` 由 `projectile_data.min_speed` 提供（未配置时不设下限，但保留 0.01 的数学下限防止零向量）。

- 对未启用恒速的弹，与 `max_speed` 的关系已在上文说明：诱导阻力放在钳制之后，才能表现为"顶速变慢/掉速"而不是被速度钳制补回。
- `constant_speed: true` 的导弹会按历史峰值回填速度（HITL 与常规运动均生效），这是显式恒速语义；`max_speed` 仍可限制回填速度。未启用恒速的导弹保留基础阻力、穿透和转向造成的掉速。

### 4.6 边界与退化

| 情形 | 处理 |
| --- | --- |
| `v ≤ 1e-8` | 返回原速度，`λ = 0` |
| `rvp_aero_steering = false` | 完全走旧路径（`applyTurningFactor` / 常量 `maxGs`），`λ = 0` |
| `rvp_maxg = 0` | `G_avail = 0` → `θ_aero = 0` → 禁止转向（保持现有 0 G 语义，并新增动压维度） |
| `turning_factor ≥ 1` | `G_design = +∞`，不参与气动限制，保持瞬转（线导直控弹手感） |
| `θ_cmd ≈ 0` | 直接返回原速度，避免 `λ` 除零 |
| 方向完全相反（`θ_cmd = π`） | 复用现有 `slerpDirection` 的 `dot < -0.999999` 正交轴分支，保证有限值 |
| `qp` 计算含 NaN/Inf | `qp = qp_min`（保守减载） |
| 干扰注入旋转（`applyVelocityRotation`） | **豁免**气动限制（干扰是外部强制力矩，必须强于弹体机动性），但**计入 `λ`**，使其同样付出能量代价 |

### 4.7 零配置推导的成立条件（以 AIM-120D 为实例）

问题：载具包里已存在的弹（如 `f16_aim120d` / `f22a_aim120d` / `ea18g_aim120d`）**不做任何 JSON 改动**，动压与转弯阻力能不能由代码推出来？——**能，但两个量的"可推导性"强度不同**：动压可以完整推导（速度维度），高度维度需要 `altitude_drag_factor` 才存在；转弯阻力可以推导但默认强度偏弱。

#### 4.7.1 AIM-120D 的推导来源

`f16_aim120d.json` 的 `projectile_data`：`velocity=0.5`、`mass=0.012`、`thrust=0.017`、`motor_burn_time=120`、`drag_coefficient=0.002`、`max_speed=6.0`、`turning_factor={"[[1,inf]]":0.15}`，**没有** `rvp_maxg` / `rvp_ref_speed` / `rvp_induced_drag` / `altitude_drag_factor` / `min_speed` / `gravity`。

| 需要的量 | 代码推导来源 | 该弹是否满足 | 推导结果 |
| --- | --- | --- | --- |
| `v_ref` | `max_speed`（2 级兜底） | ✅ `6.0` | `v_ref = 6.0` |
| `ρ(h)` | `altitude_drag_factor` | ❌ 未配置 | `ρ(h) ≡ 1.0`（只剩速度维度） |
| `G_design` | `turning_factor` 折算（§4.4） | ✅ `f=0.15` | `θ_tf=10.008°` → **42.7 G** |
| `k_i` | `drag_coefficient` | ✅ `0.002`（且 `has_rocket_engine=true`） | `k_i = 0.002` |
| `λ` | 由 `G_design` 与 `θ_cmd` 比较得出 | ✅（`f<1` 才可算） | 0～1 |
| 总开关 | `rvp_aero_steering` 默认 `true` | ✅ | 生效 |

派生运动学（`a = thrust/mass = 0.017/0.012 = 1.4167 格/Tick²`，`d = drag_coefficient = 0.002`）：

```text
无上限平衡速度 v_eq = sqrt(a/d) = sqrt(1.4167/0.002) = 26.6 格/Tick
→ 被 max_speed 钳制到 6.0，即 v_eq(有效) = 6.0 = max_speed        ← 这是推导成立的关键
加速段（0.5 → 6.0）耗时 t = (1/sqrt(ad))·[atanh(v√(d/a))] ≈ 4.0 Tick
```

**推论 1（动压）**：制导在 `guidance_start_tick = 20` 才启动，此时弹已到 `max_speed`，`qp = 1.0`。所以动压**在助推段几乎不产生限制**，它只在**关机后能量衰减**时才显现：

```text
关机（tick 120）后 dv/dt = −0.002v² → v(t) = 1/(0.002t + 1/6)
  v: 6 → 5   约 17 Tick      v: 5 → 4   约 25 Tick      v: 4 → 3   约 42 Tick
```

| 速率 `v` | 3.0 | 4.0 | 5.0 | 6.0（设计点） |
| --- | --- | --- | --- | --- |
| `qp = (v/6)²` | 0.250 | 0.444 | 0.694 | 1.000 |
| `G_avail = 42.7·qp` | 10.7 G | 19.0 G | 29.6 G | 42.7 G |
| `θ_aero` | 5.01° | 6.67° | 8.32° | 10.01° |

这组数字对 AIM-120D 恰好合理（真实 AIM-120 系列约 40 G 级），说明 **`turning_factor=0.15` → 42.7 G 的折算不是凑出来的，而是与现网调参自然吻合**。

**推论 2（转弯阻力）**：`Δv_ind = 0.002·λ²·v`，与同 Tick 的二次阻力 `Δv_drag = 0.002·v²` 之比为 `λ²/v`。满舵（`λ=1`）时在 `v=6` 只相当于零升阻力的 **1/6（≈17%）**，在 `v=3` 时相当于 **1/3**。关机后满舵机动的衰减率由 `0.072` 升到 `0.084 格/Tick`（+17%），**可见但偏弱**。

#### 4.7.2 成立条件（判定清单）

**A. 动压因子 `qp` 可推导的条件**

| # | 条件 | 不满足时 |
| --- | --- | --- |
| A1 | `rvp_aero_steering` 未配置或为 `true` | 走旧路径，无 `qp` |
| A2 | 存在有限正 `v_ref`：`rvp_ref_speed>0` → `max_speed>0` → 武器初速 `velocity>0` → 常量 `3.0`（链尾兜底保证总有值） | 不会失败，但落到 3.0 是无依据的默认 |
| A3 | **`max_speed` 必须是该弹实际能飞到的速度区间**，判据：`min(sqrt((thrust/mass)/drag_coefficient), max_speed) ≈ max_speed` | `v_ref` 虚高 → `qp` 长期 ≪ 1 → 机动被过度削弱（见反例） |
| A4 | 想获得高度维度：配置 `altitude_drag_factor` | `ρ≡1`，只剩速度维度（AIM-120D 就是这种情形，仍然"可推导"） |

> A4 说明：本方案刻意**不**在代码里内置标准大气模型。理由是 `altitude_drag_factor` 同时被二次阻力使用，若升力另用一套密度函数，阻力与诱导阻力会在同一高度上互相矛盾。要高度效应，就配同一张表。

**B. 诱导阻力 `k_i` 可推导的条件**

| # | 条件 | 不满足时 |
| --- | --- | --- |
| B1 | `rvp_induced_drag` 显式配置 → 用它（`0` = 关闭） | — |
| B2 | 否则 `drag_coefficient > 0` 且 `has_rocket_engine=true`（`getResolvedDragCoefficient()` 的门控） | `k_i=0`，无诱导阻力；只有线性 `drag` 的老弹需显式配 `rvp_induced_drag` |
| B3 | `turning_factor < 1`（`f≥1` 被豁免气动限制 → 无 `θ_aero` → 无 `λ`） | 线导直控弹（TOW / HJ-73E / Switchblade）无转弯阻力 |
| B4 | `rvp_aero_steering = true` | 无 `λ` |
| B5 | `constant_speed = false`（恒速导弹会按历史峰值回填） | 掉速被抹掉，不可观测 |

#### 4.7.3 反例：`9k720_9m723`（推导会失效）

| 量 | 值 |
| --- | --- |
| `thrust/mass` | `0.001/0.06 = 0.0167` |
| `drag_coefficient` | `0.0155` |
| 无上限平衡速度 | `sqrt(0.0167/0.0155) = 1.04 格/Tick`；第二脉冲（`0.025/0.06=0.417`）期间约 `5.2` |
| `max_speed` | `9.0` ← **永远飞不到** |

→ `qp` 长期落在 `0.01～0.33`，机动被过度削弱。这正是 A3 的失败形态，处理方式：改 `max_speed` 到实际速度，或配 `rvp_ref_speed: 5.2`。

#### 4.7.4 结论

| 量 | 零配置可推导 | 对 AIM-120D 的实际效果 | 建议 |
| --- | --- | --- | --- |
| 动压因子 `qp` | ✅ 速度维度可推导；高度维度需 `altitude_drag_factor` | 助推段无影响（4 Tick 即到 `max_speed`），关机后按 `v²` 衰减给出 42.7 → 10.7 G 的能量机动 | 无需配置；若要让高空发射也更"迟钝"，补 `altitude_drag_factor` |
| 转弯阻力 `k_i` | ✅ 缺省取 `drag_coefficient` | 满舵时约 +17% 衰减率，可见但偏弱 | 想有明显手感就显式配 `rvp_induced_drag`（建议 `2～4 × drag_coefficient`，即 `0.004～0.008`） |

**一句话**：AIM-120D **不需要改 JSON** 就能获得动压限过载与转弯掉速，前提是它的 `turning_factor=0.15` 能被折算出有限的等效过载（成立）、`max_speed=6.0` 与实际可达速度一致（成立）、`drag_coefficient=0.002` 与 `has_rocket_engine=true` 并存（成立）。唯一"打折"的是高度维度与诱导阻力强度——前者要 `altitude_drag_factor`，后者要 `rvp_induced_drag`。

---

## 5. JSON 改动（最小集）

### 5.1 新增键（全部位于 `projectile_data`）

| JSON 键 | Java 字段 | 类型 | 默认 | 生效条件与说明 |
| --- | --- | --- | --- | --- |
| `rvp_aero_steering` | `rvpAeroSteering` | Boolean | `true` | 气动转向总开关。`false` = 完全回退当前行为（`turning_factor` 比例混合 / `rvp_maxg` 常量钳制，且无诱导阻力）。唯一回滚开关 |
| `rvp_induced_drag` | `rvpInducedDrag` | Float（可空） | `null` → 复用 `drag_coefficient` | 诱导阻力系数 `k_i`。`0` = 显式关闭诱导阻力。仅在 `rvp_aero_steering=true` 时生效 |
| `rvp_ref_speed` | `rvpRefSpeed` | Float（可空） | `null` → `max_speed` → 初速 `velocity` → `3.0` | 动压参考速度，单位格/Tick。用于覆盖 `max_speed` 不适合当参考速度的弹（如弹道导弹）。`0` = 关闭动压减载（退化为常量 G） |
| `rvp_turn_rate_limit` | `rvpTurnRateLimit` | Float | `0` | 角速率硬上限，单位度/Tick。`0` = 不额外限制。用于限制"高动压下仍过于灵活"的弹 |

> 4 个键都是 `projectile_data` 顶层的扁平键，与既有 `rvp_maxg` / `turning_factor` / `drag_coefficient` 同级，**不引入任何嵌套结构变化**。

### 5.2 语义变化（唯一的现有键行为改变）

| 键 | 旧语义 | 新语义（`rvp_aero_steering=true`） |
| --- | --- | --- |
| `rvp_maxg` | 任意速度下恒定可用过载上限 | **设计动压点**的过载上限；`G_avail = rvp_maxg × qp`；`qp=1` 处与旧值等价 |
| `turning_factor` | 单 Tick 方向插值强度 | 折算为设计点等效过载后，由气动模型裁决；`f ≥ 1` 保持瞬转 |

其余键（`max_speed`、`min_speed`、`drag_coefficient`、`altitude_drag_factor`、`gravity`、`velocity`、`constant_speed`…）**语义完全不变**。

### 5.3 配置示例 diff

**空空弹（多数情况无需改动，零新增键）**

```jsonc
// pl_15.json — projectile_data 保持不变即可
{
  "velocity": 0.3,
  "max_speed": 6.0,
  "drag_coefficient": 0.003,
  "turning_factor": { "[[1,inf]]": 0.15 }
  // v_ref 自动 = max_speed = 6.0 → 设计过载 ≈ 42.7 G
  // k_i  自动 = drag_coefficient = 0.003
}
```

**希望"顶速也掉速"更明显的格斗弹**

```jsonc
// f22a_aim9m.json — projectile_data
{
  "max_speed": 6.5,
  "drag_coefficient": 0.004,
  "turning_factor": { "[[1,inf]]": 0.2 },
  "rvp_induced_drag": 0.012,        // 新增：诱导阻力加大到 3 倍零升阻力
  "rvp_turn_rate_limit": 18.0       // 新增：单 Tick 转角硬上限 18°，防止高动压下瞬转
}
```

**弹道导弹（显式参考速度，避免 `max_speed` 虚高）**

```jsonc
// 9k720_9m723.json — projectile_data
{
  "max_speed": 9.0,
  "drag_coefficient": 0.0155,
  "turning_factor": { "[[1,inf]]": 0.15 },
  "rvp_ref_speed": 5.2,             // 新增：按实际可达速度取参考点
  "rvp_induced_drag": 0.02          // 新增：弹道机动也要付能量代价
}
```

**线导直控弹（保持瞬转，显式豁免）**

```jsonc
// lav25_tow2b.json — projectile_data
{
  "max_speed": 3.5,
  "turning_factor": { "[[1,inf]]": 1 }
  // turning_factor ≥ 1 → 自动豁免气动限制，无需任何新增键
}
```

**紧急回滚（单键）**

```jsonc
{
  "rvp_aero_steering": false
}
```

### 5.4 为什么不再多增键

| 本可新增的键 | 复用方案 | 理由 |
| --- | --- | --- |
| `rvp_air_density` | `altitude_drag_factor` | 两项同源（都 ∝ ρ），分开配置只会互相矛盾 |
| `rvp_reference_g` | `turning_factor` 折算（§4.4） | 74 个弹已有等价信息，新增键等于要求重配 74 次 |
| `rvp_induced_drag_scale` | `rvp_induced_drag` | 同一个量，不需要第二把旋钮 |
| `rvp_steering_mode`（枚举） | `rvp_aero_steering` 布尔 | 只有"开/关"两种真实需求，枚举是过度设计 |

---

## 6. 代码改动清单

### 6.1 新增类（全部纯数学 / 无跨 Tick 状态）

| 新文件 | 职责 |
| --- | --- |
| `org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringLimits`（record） | 单 Tick 冻结输入：`rvpMaxGs`、`turningFactor`、`referenceSpeed`、`densityFactor`、`inducedDrag`、`turnRateLimitDegPerTick`、`enabled` |
| `org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringSolution`（record） | 单 Tick 输出：`velocity`、`turnAngleRadians`、`availableTurnAngleRadians`、`loadFactor`（λ）、`availableGs`、`inducedDragLoss`、`limitedBy`（枚举：`NONE`/`AERO_G`/`TURN_RATE`/`DISABLED`） |
| `org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringModel` | 核心纯数学：`resolveDynamicPressureFactor(v, limits)`、`availableGs(...)`、`equivalentGsFromTurningFactor(f, vRef)`、`solve(current, desiredDirection, limits)`、`inducedDragLoss(k, lambda, v)` |

> 放在 `trajectorymath.util` 与 `RVP_BallisticTrajectoryMath`、`RVP_TrajectorySteeringMath` 同级，使实体链、虚拟链、测试三方共用（沿用现有分层约定，见 `RVP导弹虚拟中段弹道具体实施方案.md` §3.1）。

### 6.2 修改文件逐条

| 文件 | 改动 | 备注 |
| --- | --- | --- |
| `weapon/data/RVP_ProjectileData.java` | 新增 4 个 `@SerializedName` 字段 + getter + JavaDoc；新增 `resolveAeroSteeringLimits(double speed, double altitude)` 便捷解析 | 字段注释按 `RVP_FireData` 同级规范写单位/默认/生效条件 |
| `guidance/trajectorymath/util/RVP_BallisticTrajectoryMath.java` | 新增 `applyAeroSteering(Vec3, Vec3, RVP_AeroSteeringLimits)`；`applySteering` / `applyConfiguredSteering` 保留签名为薄包装（`limits` 只填 `rvpMaxGs`/`turningFactor`，`enabled=false`）以保持旧测试与旧调用语义；`resolveTurnRadius` / `canReachTarget` 改用 `qp` 折算后的 `G_avail` | 不改任何现有方法签名，避免破坏调用点 |
| `guidance/RVP_GuidanceRuntimeMath.java` | `applyIntent` 的自动制导分支（原 111–148 行）与 `applyPresetBallistic` 分支出 `setDeltaMovement`，改调 `applyAeroSteering` 并把 `λ` 写入弹体；`steeringFactor = rvpMaxGs != null ? 1.0F : factor` 的"先取满期望方向"逻辑**保留**（因为转角裁决已下沉到求解器） | `applyIntent` 的 HITL `directMotion` 分支改为委托 `RVP_WireGuidanceSteering` |
| `guidance/RVP_WireGuidanceSteering.java` | `applyFromDirection` 改用 `applyAeroSteering`（HITL/线导同样吃动压与诱导阻力）；姿态写入逻辑不变 | 显式 `rvp_maxg` 优先规则保留 |
| `guidance/runtime/RVP_RuntimeSaclosGuidanceSource.java` | `applyVelocityRotation` 保持不受气动限制（干扰强度不能打折），但把旋转角折算成 `λ` 记入弹体 | 见 §4.6 豁免条款 |
| `entity/projectile/RVP_BaseBullet.java` | 新增 transient 字段 `aeroLoadFactor`（默认 0）；在 `tick()` 的 `guidanceWireDirectApplied = false;`（约 1742 行）旁一并复位；`tickSmartFuseGuidance` 等非气动路径保持 `λ=0` | 该字段不参与网络同步、不持久化 |
| `entity/projectile/RVP_ProjectileMotion.java` | `tickMissileMove` 末尾 `clampSpeed` 之后追加诱导阻力扣减；`tickHitlTvMove` 内同样处理（`constant_speed` 弹自动免疫） | 需要读取 `λ` 与 `k_i` |
| `guidance/trajectorymath/virtualguidance/RVP_VirtualTrajectoryParameters.java` | 新增 `rvpRefSpeed`、`inducedDrag`、`turnRateLimitDegPerTick`、`densityFactor`（或把 `altitudeDragFactor` 直接当密度） | record 组件新增属于源不兼容改动，需同步全部构造点 |
| `guidance/trajectorymath/virtualguidance/RVP_RvpTrajectoryIntegrator.java` | `step` 内改用 `applyAeroSteering`；在 `clampSpeed` 之后追加诱导阻力；`VERSION` **7 → 8**（转向语义变更，在途虚拟记录必须失效重建） | 按现有约定，实现语义变更必须提升 `VERSION` |
| `virtualflight/server/RVP_VirtualTrajectoryInputFactory.java` | 填新增的 4 个参数 | 与实体链同源解析 |
| 测试：`RVP_RvpTrajectoryIntegratorTest` | "严格保持速率"两条断言需改为"速率不增且变化量 ≤ G 上限"；新增 λ / 动压用例 | 契约变更必须显式改测试，不能悄悄改语义 |
| 测试：新增 `RVP_AeroSteeringModelTest` | 覆盖 `qp` 公式、`turning_factor` 折算表、`λ²` 诱导阻力、`f≥1` 豁免、`rvp_aero_steering=false` 回退 | 纯数学，无 MC 依赖 |
| 文档：`docs/plan/RVP武器数据模型文档.md`、`docs/plan/RVP武器文档.md`、`docs/plan/RVP武器数据模型/RVP 火炮导弹火箭武器数据模型文档.md`、`docs/plan/RVP武器数据模型/RVP包新增参数字段说明.md` | 补 4 个新键的字段表行 | 与代码同提交 |
| 文档：`docs/RVP弹体-fish/RVP导弹虚拟中段弹道具体实施方案.md` | 更新 §字段表（331/524/853/1000/1033 行附近）与 `VERSION` 说明 | 保持方案文档与实现一致 |

### 6.3 明确的非目标（本方案不做）

- **不引入弹体姿态动力学**（不做攻角/升力面/角动量积分）。速度方向仍由本 Tick 的转角裁决决定，姿态仍由 `applyGuidanceFacing` 对齐速度——这是"点质量模型"的边界，完整姿态动力学需要新增同步状态量，超出"最小改动"。
- **不改 `tickGuidance` → `tickMotion` 的执行顺序**，不新增 Mixin。
- **不改 `RVP_TrajectorySteeringMath.applyTurningFactor` 的公有签名与算法**（它是"旧路径"，被 `applyConfiguredSteering` 的 `enabled=false` 分支继续调用）。
- **不处理 HITL 直控的"指令锥角"逻辑**（`clampToAngle` / `max_guidance_angle`），与本方案正交。

---

## 7. 兼容性、回滚与分阶段上线

### 7.1 兼容性

- 所有新增键可选；未配置时行为由默认值决定（P1/P2/P3 修复生效）。
- 旧 JSON（含 `turning_factor` 的 74 个弹）**不需要修改任何文件**即可获得新模型——这是"最小改动"的核心。
- `rvp_aero_steering: false` 为单键完全回滚，行为与今天逐位等价（含 `applySteering` 严格保速契约）。

### 7.2 分阶段上线（建议）

| 阶段 | 内容 | 验证方式 |
| --- | --- | --- |
| S1（已完成） | 纯数学层 + 单测（`RVP_AeroSteeringModel` / `RVP_AeroSteeringSolution`），暂不接入实体链 | `./gradlew build`，单测通过 |
| S2（已完成） | 接入实体链与虚拟链，`rvp_aero_steering` 默认 **false**（全局静默，无行为变化） | 构建、单测及服务端冒烟通过；日志无新增错误 |
| S3 | 选取 3 个代表性弹种显式开启：`pl_15`（空空+双脉冲）、`9k720_9m723`（弹道+PRESET+虚拟中段）、`lav25_tow2b`（线导 `f=1` 豁免） | 手动实弹测试 + 虚拟中段 ETA 对比 |
| S4 | 默认值翻转为 `true`，全弹种生效 | 全弹种回归清单 |
| S5 | 按 §9 调参，把明显偏强的弹补 `rvp_maxg` 或调 `turning_factor` | 手感评审 |

### 7.3 回滚

- 单弹回滚：该弹 `projectile_data` 加 `"rvp_aero_steering": false`。
- 全局回滚：把 `RVP_ProjectileData.rvpAeroSteering` 的默认值改回 `false` 重新构建（一个字段）。
- 虚拟链回滚：`VERSION` 提升会令在途记录失效并按策略重建，不需要手工清理 `SavedData`。

---

## 8. 测试与验证

### 8.1 单元测试（`./gradlew build` 自动执行）

1. **动压因子**：`qp(0.5·v_ref) = 0.25`；`qp(2·v_ref) = 1.0`（上限钳制）；`ρ(h)=0.5` 时 `qp` 减半；NaN 输入回退 `qp_min`。
2. **`turning_factor` 折算**：`f=0.15, v_ref=6` → `G ≈ 42.74`；`f=1` → `+∞`（豁免）；`f=0` → 0（禁止转向）。
3. **可用转角**：`θ_aero = 2·asin(clamp(G_avail·G/(2v)))`，与 §4.3 公式逐点对齐。
4. **载荷因子与诱导阻力**：`λ=1` 时 `Δv = k·v`；`λ=0` 时 `Δv=0`；`θ_cmd < θ_aero` 时 `λ < 1` 且 `Δv ∝ λ²`。
5. **回退等价**：`rvp_aero_steering=false` 时，`solve(...)` 输出与今日 `applySteering` 逐位一致（用现有测试向量 `(10,0,0) → (0,0,1)`、`18 G` 验证）。
6. **边界**：`v→0`、方向完全相反、`rvp_maxg=0`、`rvp_turn_rate_limit` 生效时 `limitedBy=TURN_RATE`。
7. **契约变更显式化**：修改 `RVP_RvpTrajectoryIntegratorTest` 中两条"保持速率"断言为"速率不增 + 变化量 ≤ G_avail 上限"，并补一条断言确认诱导阻力使速率下降。

### 8.2 手动仿真

复用 `RVP_RvpTrajectoryIntegratorTest.manualFullVirtualFlightMaintainsCruiseAltitudeAndExpectedArrivalTime` 的 XChart 侧视窗口，对比开/关气动模型的：

- 到达 Tick 偏差（诱导阻力应使 ETA 略微增大，但不得超出容差）
- 最大稳定高度误差（高度闭环不应因权限下降而振荡）
- 末端是否仍能进入一个 Tick 航程

### 8.3 服务端冒烟

`./gradlew runServer` 后台启动，每 10 秒轮询日志，出现 `Done (Xs)!` 即通过；`grep` 加 `-a`；只看**新增**错误（历史噪音基线见 `docs/调试与修复规范.md` §5.1）。

> `pwsh` 在本会话中因沙箱写权限报错无法执行，编译与冒烟验证需在实施阶段以正常终端执行。

---

## 9. 调参与推荐值

### 9.1 推荐流程

```text
1. 先什么都不加，跑新模型 → 看手感是否可接受（多数弹应可直接用）
2. 转不动 / 末端拉不住 → 优先调 max_speed 到真实速度，或加 rvp_ref_speed
3. 转得太狠 → 加 rvp_maxg（显式覆盖折算值）或降 turning_factor
4. 急转不掉速 → 加 rvp_induced_drag（建议 1～5 倍 drag_coefficient）
5. 高动压下瞬转太假 → 加 rvp_turn_rate_limit（空空弹建议 20～30 °/Tick）
6. 手感崩了 → rvp_aero_steering: false
```

### 9.2 分类推荐值

| 弹种 | `rvp_maxg`（若显式配） | `rvp_induced_drag` | `rvp_turn_rate_limit` |
| --- | --- | --- | --- |
| 近程格斗弹（AIM-9M / PL-10） | 40～50 G | `2× drag_coefficient` | 25～30 °/Tick |
| 中程空空弹（PL-15 / AIM-120D） | 30～40 G | `1.5× drag_coefficient` | 20～25 °/Tick |
| 远程空空 / 截击（AIM-54C / R-27R） | 20～30 G | `1.5× drag_coefficient` | 15～20 °/Tick |
| 反辐射（AGM-88 / LD-10） | 10～15 G | `1× drag_coefficient` | 10～15 °/Tick |
| 反坦克（TOW / HJ-73E / 9M120） | 5～10 G（`turning_factor: 1` 者不配） | `0.5× drag_coefficient` | 0（保持瞬转） |
| 巡航 / 空地（Storm Shadow / KD-88） | 3～8 G | `1× drag_coefficient` | 8～12 °/Tick |
| 弹道导弹（9M723 / ATACMS） | 5～15 G | `1～2× drag_coefficient` | 6～10 °/Tick |

### 9.3 `qp` 相关常量

| 常量 | 建议值 | 位置 | 调整影响 |
| --- | --- | --- | --- |
| `qp_min` | `0.05` | `RVP_AeroSteeringModel` | 调大 → 低速舵效更强；调小 → 发射瞬间更"僵硬" |
| `v_ref` 兜底常量 | `3.0` 格/Tick | `RVP_AeroSteeringModel` | 仅在前三级来源都缺失时使用 |
| `λ` 除零保护 | `1e-8` | `RVP_AeroSteeringModel` | 纯数值保护，不调 |

---

## 10. 风险与已知边界

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `turning_factor` 语义被重新解释，74 个弹种手感一次性变化 | 高 | 分阶段上线（§7.2）；`rvp_aero_steering` 单键回滚；先开 3 个代表弹验证 |
| 小离轴角下新模型比 blend 转得更快（G 限幅 vs 误差比例） | 中 | 这是刻意修正（贴近真实角速率上限）；若不可接受可加 `rvp_turn_rate_limit` |
| `max_speed` 虚高的弹（弹道导弹）机动被过度削弱 | 中 | `rvp_ref_speed` 显式覆盖；调参指南明示 |
| 诱导阻力放在速度钳制之后，属于刻意的游戏化抽象 | 中 | 在 JavaDoc 与本文档写明"独立能量损耗，非气动物理" |
| `min_speed` 与诱导阻力冲突（掉到下限后被托住） | 低 | 文档注明；不建议同时配大 `k_i` 与非零 `min_speed` |
| `constant_speed: true` 导弹按历史峰值回填速度 | 低 | 保持显式恒速语义并受 `max_speed` 限制 |
| `VERSION` 7→8 使在途虚拟记录失效 | 低 | 现有虚拟中段机制已定义恢复/取消策略，按既定流程处理 |
| record 组件新增导致构造点编译错误 | 低 | 全库仅 3 处构造（1 生产 + 2 测试），一次性同步 |
| 干扰注入 `λ` 计入后掉速过强 | 低 | `λ` 只由旋转角折算，满舵旋转才接近 1；必要时给干扰旋转单独乘 0.5 |

---

## 11. 实施顺序（可独立合入的切片）

| 切片 | 内容 | 可独立验证 |
| --- | --- | --- |
| C1 | 新增 `RVP_AeroSteeringLimits` / `RVP_AeroSteeringSolution` / `RVP_AeroSteeringModel` + `RVP_AeroSteeringModelTest` | 单测 |
| C2 | `RVP_ProjectileData` 新增 4 键与解析器；4 份武器数据模型文档补行 | 编译 + 单测 |
| C3 | `RVP_BallisticTrajectoryMath` 新增 `applyAeroSteering`，旧方法改薄包装；修 `resolveTurnRadius` / `canReachTarget` 的可达性 | 单测（回退等价用例） |
| C4 | 实体链接入：`RVP_GuidanceRuntimeMath` / `RVP_WireGuidanceSteering` + `RVP_BaseBullet.aeroLoadFactor` + `RVP_ProjectileMotion` 诱导阻力 | 冒烟 + 实弹 |
| C5 | 虚拟链接入：`RVP_VirtualTrajectoryParameters` / `RVP_VirtualTrajectoryInputFactory` / `RVP_RvpTrajectoryIntegrator`（`VERSION=8`）；同步 `RVP导弹虚拟中段弹道具体实施方案.md` | 手动仿真 + ETA 对比 |
| C6 | 默认值翻转 `rvp_aero_steering=true`；SACLOS 干扰旋转的 `λ` 记账 | 全弹种回归 |

---

## 12. 附：核心伪代码

```java
// ===== RVP_AeroSteeringModel（纯数学，无 world/entity 访问）=====

/** 归一化动压因子：q/q_ref = ρ(h)·(v/v_ref)²，上限 1.0。 */
public static double dynamicPressureFactor(double speed, RVP_AeroSteeringLimits limits) {
    double vRef = limits.referenceSpeed();
    if (!(vRef > 1.0E-6) || !Double.isFinite(vRef)) {
        return 1.0;                                  // rvp_ref_speed=0 → 关闭动压减载
    }
    double ratio = speed / vRef;
    double qp = limits.densityFactor() * ratio * ratio;
    return Mth.clamp(qp, QP_MIN, 1.0);               // 只减载、不增载
}

/** turning_factor 在设计动压点的等效过载。f ≥ 1 表示无过载限制。 */
public static double equivalentGsFromTurningFactor(float factor, double vRef) {
    float f = Mth.clamp(factor, 0.0F, 1.0F);
    if (f >= 1.0F) {
        return Double.POSITIVE_INFINITY;             // 线导直控弹：保持瞬转
    }
    if (f <= 0.0F) {
        return 0.0;                                  // 禁止转向
    }
    double thetaTf = Math.atan2(f, 1.0 - f);         // 离轴 90° 的最坏转角
    double chord = 2.0 * vRef * Math.sin(thetaTf * 0.5);
    return chord / PhysicsEngine.G;
}

/** 单 Tick 转向求解：制导只给期望方向，转角与能量代价由本方法裁决。 */
public static RVP_AeroSteeringSolution solve(Vec3 current, Vec3 desiredDirection,
                                            RVP_AeroSteeringLimits limits) {
    double speed = current.length();
    if (!limits.enabled()) {
        return RVP_AeroSteeringSolution.disabled(legacySteering(current, desiredDirection, limits));
    }
    if (speed <= 1.0E-8 || desiredDirection == null
            || desiredDirection.lengthSqr() <= 1.0E-12) {
        return RVP_AeroSteeringSolution.noTurn(current);
    }

    double gDesign = limits.rvpMaxGs() != null
            ? limits.rvpMaxGs().doubleValue()
            : equivalentGsFromTurningFactor(limits.turningFactor(), limits.referenceSpeed());
    if (!Double.isFinite(gDesign)) {                 // f ≥ 1：豁免气动限制
        return RVP_AeroSteeringSolution.exempt(
                RVP_TrajectorySteeringMath.applyTurningFactor(
                        current, desiredDirection, speed, limits.turningFactor()));
    }

    double qp = dynamicPressureFactor(speed, limits);
    double gAvail = gDesign * qp;
    double aMax = gAvail * PhysicsEngine.G;

    double thetaCmd = RVP_BallisticTrajectoryMath.angleBetween(current, desiredDirection);
    double thetaAero = 2.0 * Math.asin(Mth.clamp(aMax / (2.0 * speed), 0.0, 1.0));
    LimitReason reason = LimitReason.AERO_G;
    if (limits.turnRateLimitRad() > 0.0 && limits.turnRateLimitRad() < thetaAero) {
        thetaAero = limits.turnRateLimitRad();
        reason = LimitReason.TURN_RATE;
    }

    double thetaApplied = Math.min(thetaCmd, thetaAero);
    double lambda = thetaAero > 1.0E-9 ? thetaApplied / thetaAero : 0.0;
    double fraction = thetaCmd > 1.0E-9 ? thetaApplied / thetaCmd : 0.0;
    Vec3 next = RVP_BallisticTrajectoryMath
            .slerpDirection(current.normalize(), desiredDirection.normalize(), fraction)
            .scale(speed);

    return new RVP_AeroSteeringSolution(
            next, thetaApplied, thetaAero, lambda, gAvail,
            reason == LimitReason.AERO_G && thetaApplied >= thetaCmd
                    ? LimitReason.NONE : reason);
}

// ===== RVP_ProjectileMotion.tickMissileMove 末尾（速度钳制之后）=====

// 目的：把本 Tick 的转向载荷折算为诱导阻力速度损失，使持续大过载机动付出能量代价。
// 位置刻意放在 clampSpeed 之后，避免被推力与 max_speed 上限吸收（见方案 §3.2）。
double lambda = projectile.getAeroLoadFactor();
double kInduced = data.getProjectileData().resolveInducedDragCoefficient();
if (lambda > 0.0 && kInduced > 0.0 && velocity.lengthSqr() > 1.0E-8) {
    double speed = velocity.length();
    double loss = kInduced * lambda * lambda * speed;
    double floor = Math.max(data.getProjectileData().getMinSpeed(), 0.01);
    velocity = velocity.normalize().scale(Math.max(speed - loss, floor));
}
```

---

## 13. 与既有文档的关系

| 文档 | 关系 |
| --- | --- |
| `docs/RVP弹体-fish/RVP导弹虚拟中段弹道具体实施方案.md` | 本方案改动 `RVP_RvpTrajectoryIntegrator`（`VERSION` 7→8），需该文档同步 §字段表与版本说明 |
| `docs/plan/RVP_GPS弹道导弹三段式弹道（PRESET）设计方案.md` | 该文档 §"对比本体"已指出本体用 `maxG × 动压因子`，本方案即把该能力补齐到 RVP 两条链路 |
| `docs/plan/RVP武器数据模型文档.md` 等 4 份数据模型文档 | 需补 4 个新键的字段行 |
| `docs/plan/RVP导弹vs本体导弹对比.md` | 转向模型差异记录项，实施后应更新 |

---

## 14. 一句话总结

**把"制导直接旋转速度向量"改成"制导提交期望方向 → 统一气动求解器按 `rvp_maxg × 动压因子` 裁决可用转角 → 按载荷因子平方追加诱导阻力"**；`turning_factor` 通过离轴 90° 转角折算成等效设计过载复用同一模型，因此 JSON 只需新增 `rvp_aero_steering`（默认开、单键回滚）与 `rvp_induced_drag`（默认复用 `drag_coefficient`）两个可选键，其余全部复用现有字段。

---

## 15. 附录一：若引入攻角 α 的增量评估

> 本章是**独立增量评估**，不改变 §1–§14 的结论。若采纳本章，则 §4.5 的载荷因子 `λ` 被真实攻角 `α` 取代，§6.3 的非目标需要相应改写。
> 本章给出**为什么 / 值不值得 / 影响哪些地方**；具体怎么落地见 **§16 附录二（实施方案）**。

### 15.1 为什么当前 3DOF 下没有 α

见 §1.6：姿态（`xRot/yRot`）是从速度向量**派生**的，`applyGuidanceFacing` 与 `tickMissileMove` 开头的 `applyMissileCoastFacing(..., 1.0F)` 每 Tick 都把机体轴对齐到速度方向，因此恒有 `α ≡ 0`。推力沿 `getLookAngle()` 施加——在 `α ≡ 0` 的前提下这恰好等于"沿速度方向"，所以这个隐含假设一直没有暴露。

**引入 α 的本质，是把姿态从派生量改为带独立动力学的状态量**（哪怕只有一阶）。

### 15.2 最小可行模型（一阶滞后 + 饱和）

不做 6DOF（不引入力矩、转动惯量、静稳定性、滚转），只加一个 α 状态与它的响应：

```text
① 制导给出期望方向 d（与 §3 相同，制导层接口不变）
② 本 Tick 需要的横向加速度：a_req = 2v·sin(θ_cmd/2) / 1        θ_cmd = angle(v, d)
③ 所需攻角：α_cmd = a_req / (K_L · qp)                         K_L = 升力增益
④ 饱和：α_cmd ← clamp(α_cmd, −α_max, +α_max)
⑤ 一阶滞后：α ← α + (α_cmd − α)·min(1, 1/τ)                    ← 持久状态
⑥ 机体轴 = 速度方向绕转轴旋转 α → 写 xRot/yRot                  ← 取代 snap
⑦ 实际横向加速度 a = K_L · qp · α
⑧ 速度方向旋转 θ = 2·asin(clamp(a/(2v), 0, 1))
⑨ 诱导阻力 Δv = k_i · (α/α_max)² · v
⑩ 推力沿机体轴（getLookAngle()），自带 cos α 的轴向分量损失
```

**关键性质：这是现有方案的严格推广，不是换模型。** 令升力增益由设计过载与失速攻角反解：

```text
K_L = G_design · PhysicsEngine.G / α_max
⇒ a = K_L · qp · α = G_design·G · qp · (α/α_max)
⇒ G_avail = G_design · qp · (α/α_max)   ≤  G_design · qp
```

即 **§4.3 的 `G_avail = G_design × qp` 等价于"α 瞬间拉满到 α_max"的退化情形**；引入 α 后，多出来的只是 `α` 不会瞬间拉满，而按时间常数 `τ` 建立。同时 `λ = θ_applied/θ_aero` 这个每 Tick 派生量，被 `α/α_max` 这个持久状态量精确取代——**§4.5 的诱导阻力公式形式不变，物理依据变扎实**。

一阶滞后无超调、无条件稳定；若日后升级为二阶（短周期振荡），必须同时引入阻尼比 `ζ`，否则会出现抖振，第一版不建议。

### 15.3 需要新填什么数

| JSON 键 | 类型 | 建议默认 | 作用 | 可推导性 |
| --- | --- | --- | --- | --- |
| `rvp_alpha_max` | Float（度） | `15.0`（尾控弹）/ `25.0`（带 TVC） | 失速/可用攻角上限，与 `qp` 共同决定可用过载 | 不能从现有字段推导（导弹 JSON **没有** `caliber`，见下），需给常量默认 |
| `rvp_alpha_tau` | Float（Tick） | `3.0`（≈0.15 s，舵机+气动滞后） | α 一阶响应时间常数，取代 `rvp_turn_rate_limit` 的"起步限速"作用 | 需要默认值 |
| `rvp_lift_gain` | Float（可选） | `null` → 由 `G_design/α_max` 反解 | 升力增益覆盖（一般不需要配） | 默认不用填 |

**净新增字段数 = 1**：`+rvp_alpha_max +rvp_alpha_tau −rvp_turn_rate_limit`。原来的 `rvp_turn_rate_limit`（度/Tick 硬上限）被 `τ`（起步限速）与 `α_max`（稳态上限）完整覆盖，可以删除或保留为仅给非 α 路径用的兼容键。

**关于参考面积**：真实气动需要 `q·S·C_Nα`，其中 `S` 本可从弹径推。但 `caliber` 只存在于**火炮/机枪**武器（如 `f16_m61a1` 的 `caliber: 30`），**导弹 JSON 里一个都没有**（74 个导弹全部无 `caliber`）。所以：
- 要么给导弹新增弹径字段（多一个键、多 74 处待填）；
- 要么按 RVP 既有惯例（见 `RVP_BallisticTrajectoryMath.thrustAccelerationPerTick` 的 JavaDoc：刻意使用"游戏调优单位"而非 SI）把 `S`、`C_Nα`、`ρ_ref` 全部吸收进单一游戏单位增益 `K_L`。
- **推荐后者**，这也正是 `K_L` 由 `G_design/α_max` 反解、无需用户填写的原因。

**AIM-120D 实算**：`G_design = 42.7 G`，`α_max = 15° = 0.2618 rad` → `K_L = 42.7 × 0.0245 / 0.2618 ≈ 4.0 格/Tick²/rad`。在 `qp = 1`、`α = 15°` 时 `a = 4.0 × 0.2618 = 1.047 格/Tick² = 42.7 G` ✓ 自洽。

### 15.4 新增的持久状态与同步

| 状态 | 位置 | 同步 | 说明 |
| --- | --- | --- | --- |
| `alphaRadians` | `RVP_BaseBullet`（transient double） | **不需要网络同步** | 制导与运动本来就只在服务端跑（`tick()` 中 `if (level().isClientSide()) return;`，客户端只用同步的 `xRot/yRot` + `lerpTo`）。姿态变化已经通过既有的 `writeAdditional`/`lerpTo` 传出去，**不新增包** |
| `alphaRadians` | `RVP_VirtualTrajectoryState` | 进 `SavedData` | 虚拟中段在途记录必须带上 α，否则实体↔虚拟交接时 α 归零 → 速度方向不连续。`VERSION` 需再提升一次（8 → 9），在途记录按既有策略失效重建 |
| α 初值 | 出膛时 | — | `α_0 = angle(出膛机体轴, 出膛速度)`；`applySpawnAimRot` 与冷发射 `applyPreIgnitionVelocity` 之后计算 |

### 15.5 对弹体动力学的影响范围

**环 1 — 动力学核心（必改，且必须成对改）**

| 位置 | 改动 |
| --- | --- |
| `RVP_AeroSteeringModel.solve` | 插入 α 状态机（②–⑨）；返回体增加 `alphaRadians`、`alphaCommandRadians` |
| `RVP_ProjectileMotion.tickMissileMove` | **必须删除开头的 `applyMissileCoastFacing(projectile, velocity, 1.0F)`**，否则每 Tick 把 α 抹零；推力方向语义从"=速度"变为"=机体轴" |
| `RVP_ProjectileMotion.applyGuidanceFacing` | 从"对齐速度"改为"速度方向 ⊗ α"，且**不再 snap**（姿态由 α 决定） |
| `RVP_ProjectileMotion.MISSILE_COAST_LERP = 0.2` | 关机段原有的姿态滞后与 α 配平语义重叠，需重写：关机后无推力时 α 由气动配平自然衰减（可复用 τ） |
| `RVP_BallisticTrajectoryMath.integrateForces`（虚拟链） | 推力当前沿 `velocity.normalize()`，必须改为沿**机体轴**；否则虚拟链与实体链推力口径不同，交接点速度不连续 |
| `tickMissileMove` 阻力项 | 二次阻力（沿速度反向，不变）+ 诱导阻力（改为 `∝ α²`） |

**环 2 — 制导层（接口不变、语义变化）**

| 位置 | 影响 |
| --- | --- |
| `RVP_GuidanceRuntimeMath.applyIntent` / `applyPresetBallistic` | 调用签名不变，但实际转角不再由 `θ_aero` 单点决定，而是 α 建立过程的结果 |
| `RVP_WireGuidanceSteering.applyFromDirection` | HITL/线导直控改成"给期望 α"；`turning_factor: 1` 的"瞬转"弹需要显式豁免（例如 `τ→0`） |
| `RVP_RuntimeSaclosGuidanceSource.applyVelocityRotation` | 干扰注入仍绕开气动（外部强制力矩），但要计入 α 基准 |
| `steerGpsCruise` / `steerPresetBallistic` / `resolveTurnRadius` / `canReachTarget` | **可达性与转弯半径必须重算**：转弯能力现在由 `α_max·K_L·qp` 与 `τ` 共同决定，只用 G 会高估（τ 使初始段转不动），虚拟中段会误判"能接入目标" |
| `RVP_GuidanceRuntimeGeometry`（锥角/视轴） | 现用 `projectile.getLookAngle()` 当视轴。有 α 后必须明确：导引头固连机体（用机体轴，真实）还是稳定平台（用速度轴）。这是**语义决策**，不是纯实现 |

**环 3 — 读取姿态的子系统（需逐个复核，共约 12 处）**

| 子系统 | 位置 | 影响 |
| --- | --- | --- |
| 导引头扫描/视场 | `RVP_RuntimeSeekerSupport`（131/193）、`RVP_RuntimeArmGuidanceSource`（64/163/200）、`AntiRadiationSeekerHelper`（26/30） | 以 `getLookAngle()` 为视轴，α ≠ 0 时视场中心与速度方向偏离 |
| 告警/威胁/定向干扰几何 | `RVP_SbwThreatManager`（157）用 `missile.getLookAngle()` 作威胁方向 | 告警箭头与 DIRCM 束流方向基准会随 α 偏 |
| 近炸检测盒后移 | `RVP_BaseBullet`（2650）`getLookAngle().scale(-radius)` | 探测盒偏移方向随 α 偏（影响极小，但需确认） |
| 尾焰/尾迹锚点 | `RVP_BaseBullet`（4281/4346/4394）沿 `-getLookAngle()` 偏移 | 尾焰随 α 偏（视觉上更真实） |
| 子母弹继承 | `RVP_SubmunitionSpawner`（142/276）、`RVP_BaseBullet`（789） | 子弹出膛姿态继承自机体轴 |
| HITL 摄像机 / TV 叠加 | `RVP_ClientHitlCamera`（45/46/116）、`RVP_TVMissileOverlay`（188–195） | 画面视轴 = 机体轴，α 抖动会传到玩家画面（真实但可能不适），需决策 |
| 渲染 | `VehicleProjectileRenderLogic`（50/51）、`RVP_BedrockProjectileEntityRenderer`（160） | 自动获得 α 视觉，无需改 |
| 网络同步 | `RVP_HbmMissileSyncService`（78）、`RVP_RemoteAmmoSyncService`（81） | 已同步 `yRot`，**无需新增包** |
| 跳弹/反射 | `RVP_BaseBullet`（3528）`applyRotationFromVelocity(reflected)` | 反射后必须重置 α |

**环 4 — 明确绕过模型的路径（豁免或需单独决定）**

`tickSmartFuseGuidance`（直接缩放速度）、`tickDeploymentBallisticMotion`（子母弹部署）、`applyWindDrift`（风漂）、干扰注入旋转、无推力弹的 `tickBallisticMotion`（建议保持纯 3DOF：无控制力矩时 α 无意义）、`RVP_BulletEntity` 机枪弹（保持现状）。

### 15.6 引入 α 后仍然不是 6DOF

| 仍缺 | 说明 |
| --- | --- |
| 滚转自由度 | 弹体侧依旧 0 处 `setZRot`/`getZRot` |
| 力矩与转动惯量 | α 由一阶滞后**人为给定**，不是由气动力矩平衡解出 |
| 静稳定性 / 配平 | α 不会自稳回零，靠 `α_cmd` 驱动 |
| 侧滑角 β 与横航向耦合 | 无滚转-偏航耦合、无侧滑 |
| 舵面/TVC 动力学 | `τ` 是舵机+气动的合并常数，不区分 |

准确叫法应是 **"3DOF 平动 + 1 个攻角状态"**（伪 4DOF），而不是 5DOF/6DOF。

### 15.7 成本与收益判断

| | 现有方案（λ 代理） | 增量引入 α |
| --- | --- | --- |
| 新增 JSON 键 | 2 个（`rvp_aero_steering`、`rvp_induced_drag`） | +1 个净增（`rvp_alpha_max`、`rvp_alpha_tau` 换掉 `rvp_turn_rate_limit`） |
| 零配置可用 | ✅ | ✅（`α_max=15°`、`τ=3 Tick` 有合理默认） |
| 新增持久状态 | λ（每 Tick 重置，非状态） | α（跨 Tick 状态 + 虚拟态字段 + `VERSION` 再升一次） |
| 必改文件 | 8 个 | 12 个（新增环 2 的可达性重算与环 3 的 12 处姿态消费者复核） |
| 解决的观感 | 转弯掉速、低动压拉不出 G | 追加：**机头不再瞬转、G 有建立过程、失速饱和真正涌现**（而非用 `qp` 硬压） |
| 风险 | 低（无新状态，可单键回滚） | 中（姿态语义变化会外溢到导引头/摄像机/告警；`VERSION` 再升一次） |

**建议**：先按 §1–§14 落地 λ 版（无新状态、影响面可控），把 `rvp_induced_drag` 与动压减载调稳；**把 α 作为第二阶段**，在 λ 版证明了"转弯掉速手感正确"之后再升级——因为 α 版的 `G_avail` 公式与 λ 版完全一致，升级是纯增量，不会推翻已有调参。唯一需要提前预留的是：**姿态语义决策（导引头视轴取机体轴还是速度轴）必须在动 α 之前定，否则环 3 的 12 处要改两遍。**

---

## 16. 附录二：引入攻角 α 的实施方案

> 以下为**可执行实施方案**：决策表 → 数据模型 → 状态机 → 逐文件改动 → 版本号 → 切片 → 测试 → 调参 → 验收。
> 前置假设：§1–§14 的 λ 版**已落地并验证通过**（α 版是它的增量，见 §16.7 的等价性证明）。

### 16.1 前置决策（写代码前必须定死，否则后期返工）

| # | 决策点 | 选项 | **建议** | 不定的后果 |
| --- | --- | --- | --- | --- |
| D1 | `xRot/yRot` 字段语义 | A. 改为**机体轴**<br>B. 保持"速度方向"，α 另存新字段 | **A** | 选 B 需第二套姿态字段，渲染/尾焰/子母弹全部要改；选 A 则这些自动获得 α |
| D2 | 导引头视轴 | A. 机体轴（固连）<br>B. 速度轴（稳定平台）<br>C. 按 `guidance_type` 分派 | **C**：IR / ARH / ARM → 机体轴；SARH / SALH / 激光 / 指令线导 → 速度轴 | 决定环 3 中 6 处导引头代码取哪个向量，**必须与 D1 同时定** |
| D3 | 关机段 α 行为 | A. 按同一 τ 衰减到 0（气动配平）<br>B. 维持最后 α | **A** | 不定的后果：与残留的 `MISSILE_COAST_LERP` 双重滞后叠加 |
| D4 | `turning_factor ≥ 1` | A. τ→0 瞬转豁免<br>B. 仍受 α 限制 | **A** | 4 枚线导直控弹（TOW-2B/N、HJ-73E、Switchblade）手感被压掉 |
| D5 | 无动力弹走不走 α | A. 不走（保持 3DOF）<br>B. 也走 | **A** | 机枪弹/火箭/炸弹/2 枚滑翔导弹无需引入无意义的 α |
| D6 | α 是否网络同步 | A. **不同步**（客户端用同步的 `xRot/yRot` 插值）<br>B. 新增包同步 α | **A** | 选 B 要多一个包与一套插值；选 A 客户端误差 ≤1 Tick |
| D7 | `rvp_turn_rate_limit` | A. 删除（新 schema 无历史负担）<br>B. 保留为兼容键 | **A**，但**等 λ 版上线稳定后再删** | 需同步 4 份数据模型文档 |

**D1 + D2 是唯一必须在写第一行代码前定死的两项**，因为它们决定"机体轴"这个概念在项目里的定义与消费方。

### 16.2 数据模型改动

**`RVP_ProjectileData`（+2 键，JavaDoc 按 `RVP_FireData` 同级规范）**

```java
/**
 * 可用攻角上限（失速边界），单位度，默认 15.0，运行时钳制到 [1, 90]；
 * 生效条件：rvp_aero_steering=true 且 turning_factor<1。
 * 该值只改变"用多大攻角换取设计过载"——升力增益由
 * K_L = G_design·PhysicsEngine.G/α_max 反解，故不改变设计点过载本身，
 * 只改变满舵时的攻角与失速余量。
 */
@SerializedName("rvp_alpha_max")
private float rvpAlphaMax = 15.0f;

/**
 * 攻角一阶响应时间常数，单位 Tick，默认 3.0（≈0.15 s），运行时下限 1.0；
 * 生效条件：同 rvp_alpha_max。数值越大机头建立过载越慢、发射初期越"转不动"；
 * 置 1.0 表示瞬转，此时本模型精确退化为无滞后版本（可作逐弹灰度开关）。
 */
@SerializedName("rvp_alpha_tau")
private float rvpAlphaTau = 3.0f;
```

Getter（换算集中在一处，内部一律用弧度）：

```java
public float getRvpAlphaMaxRadians() { return (float) Math.toRadians(Mth.clamp(rvpAlphaMax, 1.0F, 90.0F)); }
public float getRvpAlphaTauTicks()   { return Math.max(rvpAlphaTau, 1.0F); }
```

**record 组件新增（全部追加在末尾，降低构造点破坏面）**

| record | 新增组件 |
| --- | --- |
| `RVP_AeroSteeringLimits` | `float alphaMaxRadians`、`float alphaTauTicks` |
| `RVP_AeroSteeringSolution` | `double alphaRadians`（更新后的 α）、`double alphaCommandRadians`、`boolean stalled` |
| `RVP_VirtualTrajectoryParameters` | `float alphaMaxRadians`、`float alphaTauTicks` |
| `RVP_VirtualTrajectoryState` | `double alphaRadians` **（必须末位，全部构造点按位置传参）** |

**`RVP_VirtualMissileState`（NBT）**

```java
// saveSnapshot 追加
tag.putDouble("alphaRadians", t.alphaRadians());
// loadSnapshot 追加：缺失回退 0.0，保证旧 NBT 仍可读
tag.contains("alphaRadians") ? tag.getDouble("alphaRadians") : 0.0
```

### 16.3 版本号：两个独立版本都要动

| 版本号 | 现値 | α 版 | 位置 | 说明 |
| --- | --- | --- | --- | --- |
| `RVP_RvpTrajectoryIntegrator.VERSION` | 7（λ 版后 8） | **8 → 9** | 积分器 | 轨迹数学语义变更 |
| `RVP_VirtualMissileState.STATE_SCHEMA_VERSION` | 1 | **1 → 2** | SavedData NBT | 权威记录 schema 变更 |

两者**分开演进**（见 `RVP_VirtualMissileState` 类注释）。旧版本记录按既有校验安全返回空并按策略重建，无需手工清理。

### 16.4 核心状态机（纯数学层保持无状态）

α 是**跨 Tick 状态**，但它不进数学层：由调用方传入、由返回体带出，落在实体/虚拟态上。这与本项目"数学层无状态、状态在实体/虚拟态"的分层一致，也让 α 可被单测直接注入。

```java
/**
 * 单 Tick 转向求解（α 版）。alphaRadians 为持久状态，由调用方传入并从返回体写回。
 */
public static RVP_AeroSteeringSolution solve(Vec3 current, Vec3 desiredDirection,
                                             double alphaRadians, RVP_AeroSteeringLimits limits) {
    double speed = current.length();
    if (!limits.enabled()) {
        return RVP_AeroSteeringSolution.disabled(   // 回退 λ 版/旧路径，α 原样返回
                legacySteering(current, desiredDirection, limits), alphaRadians);
    }
    if (speed <= 1.0E-8 || desiredDirection == null
            || desiredDirection.lengthSqr() <= 1.0E-12) {
        return RVP_AeroSteeringSolution.noTurn(current, alphaRadians);
    }

    double gDesign = resolveDesignGs(limits);        // rvp_maxg 优先，否则 turning_factor 折算
    if (!Double.isFinite(gDesign)) {                 // f ≥ 1：瞬转豁免（D4）
        Vec3 steered = RVP_TrajectorySteeringMath.applyTurningFactor(
                current, desiredDirection, speed, limits.turningFactor());
        return RVP_AeroSteeringSolution.exempt(steered, 0.0, 0.0);
    }

    double qp = dynamicPressureFactor(speed, limits);
    double alphaMax = limits.alphaMaxRadians();
    // K_L 每 Tick 反解，不缓存：gDesign 会随 turning_factor 的 Tick 区间变化（现网 5 枚用区间表）
    double liftGain = gDesign * PhysicsEngine.G / alphaMax;

    double thetaCmd = RVP_BallisticTrajectoryMath.angleBetween(current, desiredDirection);
    if (thetaCmd <= 1.0E-9) {
        // 无转向需求：α 按配平衰减（D3），不影响速度方向
        double alpha = decayAlpha(alphaRadians, limits.alphaTauTicks());
        return RVP_AeroSteeringSolution.noTurn(current, alpha);
    }

    // ② ③ 需求攻角：把"本 Tick 完全对齐所需的横向加速度"折成攻角
    double requiredChord = 2.0 * speed * Math.sin(thetaCmd * 0.5);
    double lift = Math.max(liftGain * qp, 1.0E-9);   // 防除零；qp 已有 qp_min 下限
    double alphaCmdRaw = requiredChord / lift;

    // ④ 失速饱和
    double alphaCmd = Mth.clamp(alphaCmdRaw, -alphaMax, alphaMax);
    boolean stalled = Math.abs(alphaCmdRaw) > alphaMax;

    // ⑤ 一阶滞后：τ ≤ 1 → 瞬转（此时精确退化为 λ 版，见 §16.7）
    double tau = limits.alphaTauTicks();
    double gain = tau <= 1.0 + 1.0E-6 ? 1.0 : Math.min(1.0, 1.0 / tau);
    double alpha = alphaRadians + (alphaCmd - alphaRadians) * gain;

    // ⑦ ⑧ 实际横向加速度 → 实际转角
    double accel = liftGain * qp * alpha;
    double thetaApplied = 2.0 * Math.asin(Mth.clamp(accel / (2.0 * speed), 0.0, 1.0));
    thetaApplied = Math.min(thetaApplied, thetaCmd);  // 关键：不得越过期望方向（防抖振）
    double fraction = thetaApplied / thetaCmd;
    Vec3 next = RVP_BallisticTrajectoryMath.slerpDirection(
            current.normalize(), desiredDirection.normalize(), fraction).scale(speed);

    // ⑨ 载荷因子改用真实攻角比 → §4.5 诱导阻力公式一字不改
    double lambda = Mth.clamp(Math.abs(alpha) / alphaMax, 0.0, 1.0);
    double thetaAero = 2.0 * Math.asin(Mth.clamp(
            liftGain * qp * alphaMax / (2.0 * speed), 0.0, 1.0));
    return new RVP_AeroSteeringSolution(next, thetaApplied, thetaAero, lambda,
            gDesign * qp, inducedDragLoss(limits.inducedDrag(), lambda, speed),
            alpha, alphaCmd, stalled, LimitReason.AERO_G);
}

/** 关机/无指令段：α 按同一 τ 指数衰减到 0（D3），取代 MISSILE_COAST_LERP。 */
private static double decayAlpha(double alpha, double tau) {
    double gain = tau <= 1.0 + 1.0E-6 ? 1.0 : Math.min(1.0, 1.0 / tau);
    return alpha - alpha * gain;
}
```

**六个实现要点（都是容易踩的坑）**

1. **单位**：内部一律弧度，只在 `RVP_ProjectileData` 的 getter 换算一次；JSON 面向用户用度。
2. **`K_L` 每 Tick 反解，不缓存**：`gDesign` 随 `turning_factor` 的 Tick 区间变化（现网有 5 枚用区间表：`f14d_mk84`、`f16_gbu53`、`j10c_gb3_*`、`_backup/pl_15_2`），缓存会与区间失配。
3. **`θ_applied` 必须再钳到 `θ_cmd`**：一阶滞后 + 饱和后，上一 Tick 残留的大 α 可能让本 Tick 的实际加速度**超过**需求，不钳会越过期望方向产生抖振。
4. **转轴退化**：机体轴 = 速度方向绕 `axis = v̂ × d̂` 归一化后旋转 α。当 `v̂ ∥ d̂`（轴退化）时必须回退到上一 Tick 的轴或世界 up 向量，否则 `normalize()` 出 NaN。α 的符号由 `v̂ × d̂` 自然给出，无需额外符号位。
5. **`stalled` 只记录不改行为**：第一版仅用于调试与后续扩展（失速后额外诱导阻力），避免一次引入太多变量。
6. **`θ_aero` 与 λ 版完全一致**：`2·asin(K_L·qp·α_max/(2v)) = 2·asin(G_design·G·qp/(2v))`，这是 §16.7 等价性证明的基础。

### 16.5 实体链接入（逐文件逐方法）

| 文件 | 位置 | 改动 | 必须成对的原因 |
| --- | --- | --- | --- |
| `RVP_BaseBullet` | 字段 | 新增 `alphaRadians`（transient double，**跨 Tick 保留**）。注意与 λ 相反：λ 每 Tick 复位，α**不能**复位 | 状态语义 |
| `RVP_BaseBullet` | `tick()`（`guidanceWireDirectApplied = false;` 旁） | **不复位 α**，仅在此处旁加注释说明为何不复位 | 防止照抄 λ 的写法误加复位 |
| `RVP_BaseBullet` | 出膛/`finalizeSpawnOrientation` 之后 | `initAlphaFromSpawn()`：`α_0 = angle(出膛机体轴, 出膛速度)` | 冷发射/载机初速会让两者不一致 |
| `RVP_BaseBullet` | 跳弹反射（`applyRotationFromVelocity(reflected)`） | `resetAlpha()` → 0 | 反射无气动过程 |
| `RVP_GuidanceRuntimeMath` | `applyIntent` | 读 `getAlphaRadians()` → `solve(...)` → `setAlphaRadians(solution.alphaRadians())`；λ 与诱导阻力从返回体取 | α 的唯一写入点（自动制导） |
| `RVP_GuidanceRuntimeMath` | `applyPresetBallistic` | 同上 | PRESET 弹道也要有 α |
| `RVP_WireGuidanceSteering` | `applyFromDirection` | 同上（HITL / 线导直控） | α 的第二写入点 |
| `RVP_ProjectileMotion` | `applyGuidanceFacing` | 改名/新增 `applyBodyFacing(entity, velocityDirection, alphaRadians)`：速度方向绕转轴旋转 α 后写 `xRot/yRot` | 取代 snap，是 D1 的落点 |
| `RVP_ProjectileMotion` | `tickMissileMove` 开头 | **删除 `applyMissileCoastFacing(projectile, velocity, 1.0F)`** | 不清掉就每 Tick 把 α 抹零，整个模型失效 |
| `RVP_ProjectileMotion` | `tickMissileMove` 推力行 | `Vec3 lookDir = projectile.getLookAngle()` 语义由"=速度"变为"=机体轴"，**代码不变、注释必须改** | 推力自带 `cos α` 轴向损失，是期望行为 |
| `RVP_ProjectileMotion` | `MISSILE_COAST_LERP = 0.2` 分支 | 删除；关机段姿态滞后改由 `decayAlpha` 承担 | 避免双重滞后 |
| `RVP_ProjectileMotion` | `applyRotationFromVelocity` | **保持不变**（D5：无动力弹不入 α 模型），但注释写明"该路径 α ≡ 0" | 机枪弹/火箭/炸弹回归风险为零 |
| `RVP_BallisticTrajectoryMath` | `integrateForces` | 推力方向由 `velocity.normalize()` 改为**传入的机体轴** | 虚拟链与实体链推力口径必须一致，否则交接点速度不连续 |
| `RVP_RuntimeSaclosGuidanceSource` | `applyVelocityRotation` | 保留绕开气动（外部强制力矩），但把旋转角折算进 α/λ 记账 | 干扰仍强于机动性，同时付能量代价 |
| `RVP_SbwThreatManager` | 威胁方向（157） | 按 D2 改取 `getFlightAxis()` | 束流几何应以速度轴为基准 |
| `RVP_SubmunitionSpawner` | 142 / 276 | 子弹出膛姿态继承机体轴（已由 D1 自动获得），确认无需改 | 回归确认项 |

### 16.6 虚拟中段链与姿态语义落地

**虚拟链**

| 文件 | 改动 |
| --- | --- |
| `RVP_VirtualTrajectoryParameters` | +`alphaMaxRadians`、`alphaTauTicks` |
| `RVP_VirtualTrajectoryInputFactory.createParameters` | 填 `projectile.getRvpAlphaMaxRadians()` / `getRvpAlphaTauTicks()` |
| `RVP_RvpTrajectoryIntegrator.step` | 读 `state.alphaRadians()` → `solve(...)` → 写入新 state；推力改沿机体轴；`VERSION` **8 → 9** |
| `RVP_VirtualMissileState` | NBT +`alphaRadians`；`STATE_SCHEMA_VERSION` **1 → 2** |
| 交接 | 进虚拟态时快照 α、恢复实体时写回 `projectile.alphaRadians`，**双向都要搬** |

**姿态语义（D2 的落点）**：新增一个薄封装，把"取哪个轴"收在唯一一处，避免 12 处各自判断。

```java
/** 机体轴：机头指向，导引头固连基准（D1 后 xRot/yRot 即此轴）。 */
public Vec3 getBodyAxis() { return getLookAngle(); }

/** 速度轴：弹道方向，稳定平台/随动基准。 */
public Vec3 getFlightAxis() {
    Vec3 v = getDeltaMovement();
    return v.lengthSqr() > 1.0E-8 ? v.normalize() : getLookAngle();
}

/** 导引头视轴：按 guidance_type 分派（D2）。 */
public Vec3 getSeekerBoresight() { /* IR/ARH/ARM → getBodyAxis()；SARH/SALH/激光/指令 → getFlightAxis() */ }
```

替换清单（环 3 收敛为 6 处导引头 + 1 处告警）：

| 位置 | 改为 |
| --- | --- |
| `RVP_RuntimeSeekerSupport` 131 / 193 | `getSeekerBoresight()` |
| `RVP_RuntimeArmGuidanceSource` 64 / 163 / 200 | `getSeekerBoresight()` |
| `AntiRadiationSeekerHelper` 26 / 30 | `getSeekerBoresight()` |
| `RVP_GuidanceRuntimeGeometry` 85 / 149（锥角/视轴） | `getSeekerBoresight()` |
| `RVP_SbwThreatManager` 157 | `getFlightAxis()` |
| `RVP_ClientHitlCamera` 45 / 46 / 116、`RVP_TVMissileOverlay` 188–195 | `getBodyAxis()`，并加 `rvp_hitl_camera_speed_axis` 开关（默认 false = 机体轴）供玩家规避眩晕 |
| 近炸检测盒后移 2650、尾焰锚点 4281/4346/4394 | 保持 `getLookAngle()`（= 机体轴），视觉自然获得 α，**确认无需改** |

### 16.7 与 λ 版的迁移路径（等价性证明）

| 量 | λ 版 | α 版 | 关系 |
| --- | --- | --- | --- |
| `θ_aero` | `2·asin(G_design·qp·G/(2v))` | 同 | **完全一致** |
| `θ_applied` | `min(θ_cmd, θ_aero)` | `2·asin(K_L·qp·α/(2v))`，且 `≤ θ_aero` | α 版 = λ 版 + 滞后 |
| `λ`（载荷因子） | `θ_applied/θ_aero` | `|α|/α_max` | 小角近似下等价；升级后以 α 为准 |
| 诱导阻力 | `k_i·λ²·v` | `k_i·λ²·v`（λ 换源） | **公式一字不改** |
| `G_avail` | `G_design·qp` | `G_design·qp·(α/α_max)` | `α = α_max` 时退化为 λ 版 |

**结论：α 版只改"α 怎么来"，不改"α → 速度"的映射。** 由此得到两个可操作的好处：

1. **λ 版调好的 `rvp_maxg`、`rvp_ref_speed`、`rvp_induced_drag` 全部继续有效**，升级不推翻调参。
2. **`rvp_alpha_tau: 1` 让单枚弹精确退化为 λ 版** —— α 版天然包含 λ 版，可逐弹灰度、可逐弹回退，不需要改代码。

相对 λ 版的 diff 摘要：+2 JSON 键；+2 组件 `Limits`；+3 组件 `Solution`；+2 组件 `Parameters`；+1 组件 `State`；+1 NBT 字段；新增 `decayAlpha` / `applyBodyFacing` / `getBodyAxis|getFlightAxis|getSeekerBoresight`；删除 `MISSILE_COAST_LERP` 分支与 `tickMissileMove` 开头的 `applyMissileCoastFacing`；`VERSION` +1；`STATE_SCHEMA_VERSION` +1；环 3 的 6～7 处视轴替换。

### 16.8 分阶段实施切片

| 切片 | 内容 | 前置 | 验收 |
| --- | --- | --- | --- |
| **A1** | 定死 D1–D7，把决策表填成本附录的最终版 | — | 决策表无待选项 |
| **A2** | 数据模型：2 字段 + getter + JavaDoc；4 份数据模型文档补行；`rvp_turn_rate_limit` 标记弃用 | A1 | `./gradlew build` 通过 |
| **A3** | `RVP_AeroSteeringModel` α 状态机 + `RVP_AeroSteeringModelTest`（**纯数学、不接入**） | A2 | 单测全绿，含"α=α_max 时与 λ 版逐位一致" |
| **A4** | 虚拟链：`Parameters`/`State`/`Integrator`/`InputFactory`/NBT + 双版本号提升 | A3 | 编解码单测 + 手动长航程仿真 |
| **A5** | 实体链：状态字段、求解器接入、`applyBodyFacing`、删除 coast lerp、推力方向注释 | A3 | 冒烟 + 3 弹实弹（`pl_15` / `9k720_9m723` / `lav25_tow2b`） |
| **A6** | 姿态语义：`getSeekerBoresight()` + 环 3 替换 + HITL 摄像机开关 | A5 | 导引头捕获/丢失回归 |
| **A7** | 全弹种回归 + 按 §16.9 调参 | A4 + A6 | §16.11 验收标准 |

依赖关系：**A4 与 A5 可并行，但都必须等 A3**；A6 必须在 A5 之后（否则视轴替换无法验证）；A7 需要 A4 与 A6 都完成。

### 16.9 调参指南（α 专有参数）

| 参数 | 物理含义 | 推荐值 | 怎么测出来 |
| --- | --- | --- | --- |
| `rvp_alpha_max` | 失速/可用攻角上限 | 空空·防空 `20`；反坦克·空地·巡航 `15`；弹道 `15`；线导 `f=1` **不配** | 满舵直飞，读稳态 G：应等于 `G_design × qp`；若达不到，说明 α 上限偏低或 `qp` 被 `v_ref` 压低 |
| `rvp_alpha_tau` | 舵机 + 气动滞后 | 快速弹 `3`；重型弹 `4～6`；退化到 λ 版用 `1` | 阶跃指令后数到 **90% 稳态 G** 的 Tick 数，应 ≈ `2.3τ` |
| `rvp_lift_gain` | 升力增益覆盖 | **不填**（由 `G_design/α_max` 反解） | 只有想让"满舵攻角换不到设计过载"时才填 |
| `qp_min` | 低速舵效下限 | `0.05` | 观察发射首 Tick 是否有舵效 |

**组合自检三条**

```text
1. 满舵稳态：G_steady = G_design · qp · (α/α_max)，qp=1 且 α=α_max 时应等于 G_design
2. 建立时间：90% 稳态 ≈ 2.3τ Tick
3. 关机后：α → 0，速度衰减率 = drag_coefficient·v² + k_i·(α/α_max)²·v
```

参数交互提醒：`rvp_alpha_tau` 与 `rvp_induced_drag` 会叠加出"转不动且掉速快"的双重惩罚，**调 τ 时先固定 `rvp_induced_drag`**；`rvp_alpha_max` 与 `rvp_maxg` 是"用什么换过载"与"最多能换多少"的关系，**改 α_max 不会改变设计点过载**。

### 16.10 测试计划

**新增单测 `RVP_AeroSteeringModelTest`（纯数学，无 MC 依赖）**

| # | 用例 | 断言 |
| --- | --- | --- |
| 1 | `τ = 1` 瞬转 | `θ_applied = min(θ_cmd, θ_aero)`，`α = α_cmd` |
| 2 | `τ → ∞` 极限 | α 单 Tick 增量趋 0，`θ_applied → 0`（转不动） |
| 3 | α 建立过程 | 从 0 到 `α_max` 的 90% 用时应 ≈ `2.3τ`（±1 Tick） |
| 4 | 失速饱和 | `qp` 减半时同一 `α_cmd` 仍被钳到 `α_max`，`θ_aero` 减半，`stalled = true` |
| 5 | 关机段配平 | `α_cmd = 0`，经 `5τ` 后 `|α| < 1.0E-4` |
| 6 | `f ≥ 1` 豁免 | 返回瞬转结果且 `α = 0`，`λ = 0` |
| 7 | 转轴退化 | `v̂ ∥ d̂` 时三个分量有限、不产生 NaN、α 单调衰减 |
| 8 | **等价性回归** | `α = α_max` 时输出与 λ 版 `solve` **逐位一致** |
| 9 | **回退回归** | `rvp_aero_steering = false` 时输出与今日 `applySteering` 逐位一致 |
| 10 | 不越过期望方向 | 构造"上 Tick α 很大、本 Tick θ_cmd 很小"的场景，`θ_applied ≤ θ_cmd` |

**需改写的既有断言**（契约变更必须显式改，不得悄悄改语义）

| 文件 | 原断言 | 改为 |
| --- | --- | --- |
| `RVP_RvpTrajectoryIntegratorTest` | `assertEquals(current.length(), steered.length())`（两条） | "速率不增 + 变化量 ≤ `G_avail` 上限 + 诱导阻力使速率下降" |
| `RVP_VirtualMissileStateCodecTest` | 字段往返 | 增 `alphaRadians` 往返 + 旧 `stateSchemaVersion` 被拒绝的用例 |
| `RVP_ProjectileDataTurningFactorTest` | — | 增 `rvp_alpha_max` / `rvp_alpha_tau` 的解析与钳制（含越界值 `0`、`90`、`−3`） |

**手动仿真**：复用 `manualFullVirtualFlightMaintainsCruiseAltitudeAndExpectedArrivalTime`，对比 λ 版与 α 版的 ETA、最大稳定高度误差、末端是否仍进入一个 Tick 航程。α 的滞后会让 ETA 略微增加，**容差需在对照后重新固定**，不能沿用 λ 版数值。

**服务端冒烟**：`./gradlew runServer` 后台启动，每 10 秒轮询日志，出现 `Done (Xs)!` 即通过；`grep` 加 `-a`；只看新增错误（基线见 `docs/调试与修复规范.md` §5.1）。

**实弹验收三组**

| 弹 | 观察点 |
| --- | --- |
| `pl_15` | 发射后前 10 Tick 机头转角 **小于**速度转角（α ≠ 0 可观测）；关机后急转速度明显下降 |
| `9k720_9m723` | PRESET 抛物线形态不变；虚拟中段进出点速度方向连续（无折线）；带 `altitude_drag_factor` 的高空段舵效下降 |
| `lav25_tow2b` | `f = 1` 瞬转手感**完全不变**（回归对照） |

### 16.11 风险、回滚与验收标准

**新增风险（α 专有）**

| 风险 | 缓解 |
| --- | --- |
| D1/D2 决策错误 → 环 3 的 12 处返工 | D1+D2 在 A1 定死；A6 单列切片，不与动力学混提 |
| α 在实体↔虚拟交接时丢失 → 交接折线 | 双向搬运 + 手动长航程对比（`9k720` 验收项） |
| 转轴退化产生 NaN 污染 SavedData | 单测 7 + `RVP_BallisticTrajectoryMath.isFinite` 兜底 |
| HITL 玩家眩晕（画面随 α 抖动） | HITL 摄像机加 `rvp_hitl_camera_speed_axis` 开关，默认机体轴 |
| `constant_speed` 导弹看不到掉速 | 恒速配置按历史峰值回填；未启用恒速的弹体保留实际掉速 |
| 未来二阶化引入短周期振荡 | 第一版**限定一阶**；二阶化必须同时引入阻尼比 `ζ` |
| 双版本号提升使在途记录失效 | 既有机制安全重建，无需手工清理 |

**三层回滚开关**

| 层 | 操作 | 效果 |
| --- | --- | --- |
| 单弹（α） | `"rvp_alpha_tau": 1` | 该弹**精确退化到 λ 版**，保留动压与诱导阻力 |
| 单弹（全部） | `"rvp_aero_steering": false` | 回退到今日行为（含严格保速契约） |
| 全局 | `RVP_ProjectileData.rvpAlphaTau` 默认值改 `1` | 全弹种退化到 λ 版，代码不回退 |

第 1 层是 α 版最大的工程优势：**因为 `τ = 1` 时 α 版与 λ 版逐位一致（单测 8/1 覆盖），所以 α 版可以逐弹灰度上线，任何一枚手感不对就单独降级，不需要回滚代码。**

**验收标准（可判定）**

1. `./gradlew build` 通过；单测全绿，**含用例 8（α=α_max 与 λ 版逐位一致）与用例 9（总开关关闭与今日逐位一致）**。
2. `pl_15` 发射后前 10 Tick 存在稳定的 `α ≠ 0`（机头角与速度方向夹角 > 0.5°）且单调收敛。
3. `lav25_tow2b` 与 λ 版逐帧对照，瞬转手感无差异。
4. `9k720_9m723` 虚拟中段进出点两侧速度方向夹角 < 1°。
5. 导引头捕获/丢失、告警方向、DIRCM 束流三项回归通过。
6. 服务端冒烟出现 `Done (Xs)!` 且无新增错误。
7. 连续 60 秒满舵机动，`position`/`velocity`/`alphaRadians` 全程有限（无 NaN/Infinity）。
