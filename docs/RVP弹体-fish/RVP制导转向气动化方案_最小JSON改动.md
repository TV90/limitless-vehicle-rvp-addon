# RVP 制导转向气动化方案（最小 JSON 改动）

> 状态：基础气动转向与导弹攻角阶段 1 已实现；默认关闭，尚未替任何载具包武器批量开启攻角。
>
> 适用项目：`limitless-vehicle-rvp-addon`，Minecraft 1.20.1 Forge；不修改 `ywzj_vehicle` 本体源码。
>
> 本文只描述当前工作区已经存在的实现。不要把旧的设计草案参数（例如
> `rvp_alpha_max`、`rvp_alpha_tau`、`rvp_lift_gain` 或 `alphaRadians`）当作当前 JSON
> schema；当前攻角开关只有 `rvp_attack_angle_limit_deg`。

---

## 1. 当前结论

原有转向链仍然保留两层行为：

1. `rvp_aero_steering=false`：严格走旧版 `rvp_maxg` 或 `turning_factor` 转向，不产生新的气动载荷和诱导阻力。
2. `rvp_aero_steering=true` 且未开启攻角：走已有的归一化动压、可用 G、绝对转角和 `λ²` 诱导阻力模型。
3. `rvp_aero_steering=true` 且满足攻角门控：导弹的机头轴与速度轴分离，机头先转向期望方向，速度只按攻角产生的法向过载转动；推力沿机头轴，转弯付出诱导阻力。

因此，**相对于已经接入基础气动转向的数据模型，攻角只新增 1 个 JSON 键**：
`rvp_attack_angle_limit_deg`。如果某个旧武器还没有开启基础气动转向，则最小启用片段是：

```json
{
  "projectile_data": {
    "rvp_aero_steering": true,
    "rvp_attack_angle_limit_deg": 20
  }
}
```

这里的 `20` 只是示例，不是全弹种推荐值。当前工作区没有把这两个键写入任何载具包武器 JSON；开启前仍需按具体弹种实测。

旧 JSON 的默认行为保持不变：`rvp_aero_steering` 默认 `false`，
`rvp_attack_angle_limit_deg` 缺省或非法时等价于关闭攻角。

---

## 2. 改动目标与不变项

### 2.1 要解决的问题

旧制导路径直接改写速度方向，速度转角与机体姿态、动压和能量损失脱钩，导致：

- 低速或高空仍可按配置的满 G 转向；
- `turning_factor` 的方向插值不是机头响应过程；
- 转弯没有诱导阻力代价；
- 推力方向无法体现机头与速度方向的夹角。

当前实现把制导层和运动层拆开：制导只产生期望方向，气动模型裁决机头和速度各自能转多少，运动阶段再沿机头施加推力并结算能量损失。

### 2.2 明确不变项

- 不删除、改名或迁移现有 JSON 键。
- 不在实体或渲染类用武器 ID 区分弹种。
- 不修改 `ywzj_vehicle` 本体源码。
- 不新增 Mixin，也不新增资源、模型、贴图或音效依赖。
- 不把当前攻角实现描述成完整 6DOF：没有滚转、转动惯量、力矩、侧滑状态或真实升阻力表。
- `turning_factor >= 1` 且未显式配置 `rvp_maxg` 的瞬转弹继续保留瞬转豁免。

---

## 3. JSON 字段与门控

所有字段都位于 `projectile_data`，与 `rvp_maxg`、`turning_factor`、
`drag_coefficient` 同级。

| 字段 | 当前默认/合法范围 | 当前语义 |
| --- | --- | --- |
| `rvp_aero_steering` | `false` | 气动转向总开关。关闭时回到旧版转向；开启后基础气动模型才可能生效。 |
| `rvp_attack_angle_limit_deg` | 缺省 `null`；运行时有效范围为 `(0, 90)` 度 | 弹轴相对速度轴的总攻角上限。缺省、`null`、`0`、负数、`90` 及以上、非有限值均关闭攻角。 |
| `rvp_maxg` | 缺省 `null`；显式 `0` 仍算“已配置” | 显式设计法向过载，优先于 `turning_factor`。在攻角模式中决定可用法向过载上限。 |
| `turning_factor` | 既有字段，按飞行 Tick 区间解析 | 未显式配置 `rvp_maxg` 时既用于机头响应，也用于折算设计 G；`>=1` 的瞬转语义保留。 |
| `rvp_ref_speed` | 缺省时依次回退到 `max_speed`、武器初速、`3.0` | 归一化动压参考速度，单位格/Tick；显式 `0` 关闭速度动压减载。 |
| `altitude_drag_factor` | 缺省使用默认大气密度表；显式 `{}` 为 `1.0` | 同时作为当前高度的密度倍率参与动压和基础阻力。 |
| `rvp_turn_rate_limit` | 默认 `0` | 额外的单 Tick 绝对转角上限，单位度/Tick；非正值不限制。 |
| `rvp_induced_drag` | 缺省时复用有效 `drag_coefficient`；显式 `0` 关闭 | 诱导阻力系数。只在气动开关开启且 `constant_speed=false` 时结算。 |
| `constant_speed` | 既有默认 `false` | 恒速导弹跳过独立诱导阻力损失，并按既有峰值速率规则补速。 |
| `rotate_to_motion` | 既有默认 `true` | 攻角模式中不再覆盖机头姿态；关闭攻角时保持原语义。 |

### 3.1 攻角实际生效条件

同时满足以下条件才进入 `RVP_AttackAngleModel`：

1. 弹体是 RVP 导弹；
2. 不是智能引信精确追点接管阶段；
3. `rvp_aero_steering=true`；
4. `rvp_attack_angle_limit_deg` 是有限的 `(0,90)` 数值；
5. 显式配置了 `rvp_maxg`，或当前 `turning_factor < 1`。

第 5 条是为了保留线导直控瞬转弹的既有手感：未配置 G 且 `turning_factor>=1` 时，即使填了攻角上限，也仍走瞬转豁免。显式 `"rvp_maxg": 0` 会被视为明确的 0 G 预算，因此可以进入攻角模式；它会禁止法向速度转向，但不会禁止机头动作或沿机头的推力。

普通炮弹、火箭、炸弹和子弹药不进入攻角模型。攻角字段可以被它们读取，但不会改变其运动链。

### 3.2 配置示例

使用已有 `turning_factor` 折算设计 G：

```json
{
  "projectile_data": {
    "rvp_aero_steering": true,
    "rvp_attack_angle_limit_deg": 20,
    "turning_factor": { "[[1,inf]]": 0.15 }
  }
}
```

使用显式 G 覆盖 `turning_factor` 的设计 G：

```json
{
  "projectile_data": {
    "rvp_aero_steering": true,
    "rvp_attack_angle_limit_deg": 15,
    "rvp_maxg": 35,
    "rvp_induced_drag": 0.002
  }
}
```

回滚某一枚弹：

```json
{
  "rvp_aero_steering": false
}
```
### 3.3 turning_factor 语义变化

开启攻角后，`turning_factor` 不再直接表示“速度方向每 Tick 向目标方向插值多少”。

具体分三种情况：

| 配置情况 | `turning_factor` 的新语义 |
| --- | --- |
| 未配置 `rvp_maxg`，且 `turning_factor < 1` | 机头轴朝期望方向的响应比例；同时用于折算设计 G |
| 显式配置 `rvp_maxg` | 基本不再控制攻角转向，机头响应固定为 `0.5`，可用 G 由 `rvp_maxg` 决定 |
| 未配置 `rvp_maxg`，且 `turning_factor >= 1` | 继续作为瞬转豁免，攻角模型不生效，保持旧的瞬转行为 |

攻角模式下的实际流程是：

```text
turning_factor
    → 机头轴响应速度
    → 当前机头—速度夹角 α
    → λ = sin(α) / sin(攻角上限)
    → G_normal = G_available × λ
    → 实际速度转角
```

因此，当没有显式 `rvp_maxg` 时，降低 `turning_factor` 会产生双重效果：

1. 机头转向目标方向更慢；
2. 由 `turning_factor` 折算出的设计 G 更低。

显式配置 `rvp_maxg` 后，建议把 `turning_factor` 视为兼容性/门控字段，而使用 `rvp_maxg` 控制速度转向能力。

代码入口见 [RVP_AttackAngleModel.java](<D:/WgameProject/limitless-vehicle-rvp-addon/src/main/java/org/ywzj/rvp/guidance/trajectorymath/util/RVP_AttackAngleModel.java>) 和 [RVP_AeroSteeringModel.java](<D:/WgameProject/limitless-vehicle-rvp-addon/src/main/java/org/ywzj/rvp/guidance/trajectorymath/util/RVP_AeroSteeringModel.java>)。

---

## 4. 当前数学模型

当前模型是“无滚转、无角动量的准三自由度近似”：

- 速度轴暂作为无风条件下的相对气流方向；
- 机头轴由现有 `xRot/yRot` 表示并跨 Tick 保留；
- 不单独积分攻角变量，不引入 `alphaRadians`；
- 不模拟真实升力面、静稳定性、侧滑和滚转。

### 4.1 基础气动量

归一化动压因子为：

```text
qp = clamp(densityFactor × (speed / referenceSpeed)², 0.05, 1.0)
```

`referenceSpeed <= 0` 时返回 `qp=1`，即显式关闭速度动压减载。设计 G 的来源为：

```text
rvp_maxg 已配置      → 使用显式 G（包括 0）
否则                 → turning_factor 在参考速度下的等效 G
turning_factor >= 1  → +∞，保持瞬转豁免
```

有限 G 下：

```text
G_available = G_design × qp
```

可用转角仍由弦长关系计算，并受 `rvp_turn_rate_limit` 进一步限制。基础气动模型的载荷使用率为实际转角与可用转角之比 `λ`，诱导阻力为：

```text
Δspeed = rvp_induced_drag × λ² × speed
```

该损失在速度上下限钳制之后结算，避免 `max_speed` 把转弯损失补回。

### 4.2 攻角单 Tick 求解

开启攻角后，`RVP_AttackAngleModel.solve` 按以下顺序计算：

1. 使用上一 Tick 的机头轴；若机头轴无效，回退到当前速度轴。
2. 制导层提供未经旧版 `turning_factor`/G 裁决的期望方向。
3. 机头轴以球面插值朝期望方向推进：未显式配置 G 时响应比例为当前 `turning_factor`；显式配置 G 时固定为 `0.5`，避免同时配置 `turning_factor` 改变显式 G 的主控语义。
4. 将机头轴限制在当前速度轴周围的攻角锥内：

   ```text
   α = angle(velocity_axis, body_axis)
   α <= rvp_attack_angle_limit_deg
   λ = sin(α) / sin(α_limit)
   ```

5. 用 `G_available × λ` 得到本 Tick 法向过载，再换算实际速度转角。速度最多转到机头轴，不会超过当前机头攻角。
6. 速度转向只改变方向，保持进入求解器时的速率；诱导阻力由运动阶段统一扣除。

因此，开启攻角后会出现可观察的“机头先转、速度滞后”：机头与速度的夹角越大，`λ` 越大，法向转向和诱导阻力越强，但攻角不会超过配置上限。

#### 假设数据：

- 攻角上限：`20°`
- 当前机头与速度夹角：`10°`
- `rvp_maxg=30`
- 当前动压因子：`qp=0.5`
- 当前速度：`4 格/Tick`
- `rvp_turn_rate_limit=2°/Tick`

#### 1. 载荷使用率 `λ`

攻角越大，越接近使用满载荷：

```text
λ = sin(当前攻角) / sin(攻角上限)
λ = sin(10°) / sin(20°)
λ ≈ 0.51
```

所以当前只使用约 51% 的气动转向能力。

如果攻角达到 `20°`，则：

```text
λ = 1
```

表示使用全部可用法向过载。

#### 2. 可用 G

显式配置了：

```json
"rvp_maxg": 30
```

表示设计状态下最多约 30 G。

如果没有配置 `rvp_maxg`，则代码会根据 `turning_factor` 折算。例如：

```json
"turning_factor": 0.15
```

在参考速度 `6 格/Tick` 下，大约折算为 42.7 G 的设计过载。

#### 3. 当前速度与高度动压

当前动压因子为 `0.5`，所以：

```text
G_available = 30 × 0.5 = 15 G
```

再乘以当前攻角对应的载荷率：

```text
G_normal = 15 × 0.51 ≈ 7.65 G
```

因此这一次实际用于改变速度方向的只有约 7.65 G。

低速或高空时，动压因子会降低；即使配置了 `rvp_maxg=30`，也无法直接使用满 30 G。

#### 4. `rvp_turn_rate_limit`

根据 7.65 G 和当前速度，数学模型可能算出本 Tick 最大转角约为：

```text
2.7°/Tick
```

但配置了：

```json
"rvp_turn_rate_limit": 2
```

所以最终实际转角是：

```text
min(2.7°, 2°) = 2°/Tick
```

也就是说：

- `λ` 决定当前攻角能调用多少 G；
- `rvp_maxg` 或 `turning_factor` 决定理论上有多少 G；
- 动压决定当前能发挥多少 G；
- `rvp_turn_rate_limit` 最后再设置一个绝对转角上限。

另外，当前攻角为 `10°` 时，诱导阻力大约按：

```text
λ² = 0.51² ≈ 0.26
```

计算，也就是只承担满攻角状态约 26% 的诱导阻力。



### 4.3 无目标、丢锁与强制干扰

- 无目标、丢锁或本 Tick 没有制导指令时，以当前速度方向作为期望方向；机头逐步回正，残余攻角仍会产生对应的载荷和诱导阻力。
- SACLOS/DIRCM 等强制偏转可以直接改变速度，不受攻角模型的法向转角裁决；但实际机头—速度夹角会被测量并记账，仍承担诱导阻力代价。
- 同一 Tick 先后出现干扰偏转和制导修正时，弹体只保留较大的载荷使用率，避免后一次小修正覆盖前一次大载荷。
- 智能引信路径不使用攻角模型，继续沿用原来的精确追点速度与姿态约束。

---

## 5. 实体态 Tick 顺序

### 5.1 常规/PRESET/GPS/HITL/线导

实体制导链的关键顺序为：

```text
原有制导算法
  → 生成期望方向
  → RVP_AttackAngleModel（仅满足攻角门控时）
       ├─ 更新 xRot/yRot 机头轴
       ├─ 记录本 Tick λ
       └─ 返回法向速度转向结果
  → 碰撞/命中相关逻辑
  → RVP_ProjectileMotion.tickMissileMove 或既有弹道运动
       ├─ 推力沿 getLookAngle()（攻角模式下即机头轴）
       ├─ 基础二次阻力、重力/水中重力
       ├─ min/max 速度钳制
       └─ 非恒速时扣除 λ² 诱导阻力
  → 位置推进
```

开启攻角时，`RVP_GuidanceRuntimeMath` 和 `RVP_WireGuidanceSteering` 都会跳过旧的速度预插值，直接把原始期望方向交给攻角模型，避免同一 Tick 被转向两次。普通制导朝向入口 `applyGuidanceFacing` 也不会再覆盖攻角模式刚写入的机头姿态。

### 5.2 发动机与恒速

- 发动机推力沿机头轴，而不是强制沿当前速度轴；因此攻角越大，推力对速度方向的投影越小。
- 主发动机、变质量、推力曲线、第二脉冲和燃尽判定仍使用现有实体运动链。
- `constant_speed=true` 时，诱导阻力独立损失被跳过；实体运动仍按已有历史峰值速率逻辑恢复速度。
- 无发动机导弹也会在攻角运动路径中执行诱导阻力结算；普通无动力弹不因此进入攻角模型。

---

## 6. 虚拟中段与姿态恢复

### 6.1 虚拟积分器

`RVP_RvpTrajectoryIntegrator.VERSION` 已从 8 提升为 **9**。攻角分支在 `step` 中执行：

```text
原始 GPS/PRESET 期望方向
  → RVP_AttackAngleModel（使用 state.xRot/yRot 作为当前机头轴）
  → 沿机头轴施加推力
  → 基础阻力、显式重力、速度钳制
  → 非恒速时扣除诱导阻力
  → 用机头轴写回 state.xRot/yRot
  → 平移并生成下一状态
```

未开启攻角时，虚拟积分器仍走原有分支，不改变旧的速度/姿态派生逻辑。攻角分支中 `gravity=0` 就不额外施加默认重力；这是为保持实体攻角运动链和虚拟攻角运动链的口径一致。

GPS/PRESET 在攻角分支使用 `RVP_BallisticTrajectoryMath.resolveGPSCruiseDirection` 和
`resolvePresetBallisticDirection` 只生成原始方向，不在方向生成阶段再次执行气动裁决。

### 6.2 持久化和交接

- `RVP_VirtualTrajectoryState` 原有的 `xRot/yRot` 已足够保存独立机头姿态，不新增 `alphaRadians` 字段。
- `RVP_VirtualMissileState` 的状态 schema 仍为 `1`，NBT 仍使用原有 `xRot`/`yRot` 字段。
- 实体进入虚拟态、虚拟态恢复实体时都携带机头姿态；恢复时攻角模式不会把机头重新强制对齐到速度。
- 积分器版本变化不是旧状态迁移。管理器遇到旧积分器版本的在途虚拟弹会以 `INCOMPATIBLE_INTEGRATOR` 终止，不会自动重建或迁移。部署该版本前应尽量清空在途虚拟弹。

### 6.3 当前边界

本阶段没有扩大虚拟化准入条件，也没有声称虚拟态完全复现实体态的所有特殊阶段。以下行为仍需具体弹种实测：

- 第二脉冲触发与实体/虚拟交接的完整逐 Tick 等价；
- 载具冷发射窗口；
- 风场、水中运动及实体 GPS 特殊阶段；
- 近距离目标点算法与实体碰撞时序。

---

## 7. 代码落点

| 位置 | 当前职责 |
| --- | --- |
| `RVP_ProjectileData` | 解析 `rvp_attack_angle_limit_deg`，构造实体/虚拟共用的 `RVP_AeroSteeringLimits`。 |
| `RVP_AeroSteeringLimits` | 保存当前 Tick 的气动快照，并负责攻角门控；七参数构造入口默认攻角关闭。 |
| `RVP_AttackAngleModel` | 无实体/世界依赖的机头轴、攻角锥、载荷和法向速度转向计算。 |
| `RVP_AttackAngleSolution` | 返回速度、机头轴、攻角、载荷使用率和实际转角。 |
| `RVP_GuidanceRuntimeMath` | 常规制导、GPS/PRESET 实体路径接入攻角模型。 |
| `RVP_WireGuidanceSteering` | HITL/线导接入攻角模型，并防止后续直控代码覆盖独立姿态。 |
| `RVP_ProjectileMotion` | 实体姿态写回、无指令时攻角回正、沿机头推力和诱导阻力结算。 |
| `RVP_BaseBullet` | 每 Tick 清空载荷/接管标记，合并最大载荷，并在运动阶段读取载荷。 |
| `RVP_RuntimeSaclosGuidanceSource`、`RVP_JamDeflectionHelper` | 强制偏转后的攻角载荷记账。 |
| `RVP_VirtualTrajectoryParameters`、`RVP_VirtualTrajectoryInputFactory` | 把攻角上限带入虚拟积分参数。 |
| `RVP_RvpTrajectoryIntegrator` | 实体/虚拟共用攻角数学的虚拟中段编排和姿态保存。 |
| `RVP_RemoteAmmoVisualRenderer` | 远程弹药显示保留独立机头姿态，不在渲染克隆端强制贴合速度。 |

---

## 8. 兼容性、回滚与影响面

### 8.1 默认行为

未配置新字段的旧武器不会进入攻角分支。`RVP_AeroSteeringLimits` 的旧七参数构造函数将攻角上限设为 `0`，因此基础气动链之外的既有调用也不会被误切换。

### 8.2 开启后的有意行为变化

对同时开启气动转向和攻角的导弹，以下变化是有意且可见的：

- 机头不再每 Tick 自动贴合速度；
- `rotate_to_motion` 不再覆盖机头轴；
- 推力方向变为机头轴；
- 攻角越大，法向转向和诱导阻力越强；
- 诱导阻力在速度钳制后扣除，非恒速弹会掉速；
- 高度和低速会通过 `qp` 降低有限 G 的可用转向。

### 8.3 回滚方式

优先对单个武器设置 `"rvp_aero_steering": false`，即可同时关闭基础气动与攻角分支并回到旧行为。也可以删除 `rvp_attack_angle_limit_deg`，仅保留基础气动转向。当前没有全局开启攻角的默认值修改。

---

## 9. 已完成验证与后续实机验收

### 9.1 自动化验证

当前工作区已验证：

- `./gradlew build` 成功；
- 强制重新编译与测试通过，**771 项测试，失败/错误/跳过均为 0**；
- 覆盖攻角缺省/非法值、气动开关门控、显式 0 G、瞬转豁免、机头/速度分离、攻角饱和、G/动压/转角限制、正弦载荷、平方诱导阻力、0 G、丢锁回正、虚拟推力方向、恒速豁免、GPS/PRESET 共用模型、连续 Tick 姿态和 NBT 往返；
- 服务端冒烟按仓库规范轮询日志，出现 `Done (3.490s)!`；本轮未发现相对历史基线新增的 ERROR/FATAL。

### 9.2 开启具体弹种前必须实测

建议先单独配置一枚测试弹，不要直接批量改载具包：

1. 发射与大离轴指令：确认机头先转、速度滞后，且攻角不超过配置上限；
2. 高速/低速与不同高度：确认 `qp` 对有限 G 的影响符合预期；
3. 推进、燃尽、恒速和第二脉冲：确认推力方向、掉速和恢复没有突跳；
4. GPS/PRESET、IR/ARH、SACLOS/HITL：确认各制导入口只裁决一次；
5. 丢锁、干扰、实体↔虚拟恢复、远程弹药显示：确认机头姿态连续；
6. 确认智能引信、`turning_factor>=1` 瞬转弹、普通非导弹仍保持原行为。

---

## 10. 一句话总结

当前实现把“制导直接旋转速度”改成“制导给出期望方向 → 机头在攻角锥内响应 → 有限动压过载裁决速度转向 → 推力沿机头、按攻角平方付出诱导阻力”；启用攻角只需在既有气动开关上增加 `rvp_attack_angle_limit_deg`，默认关闭，旧 JSON 不受影响。
