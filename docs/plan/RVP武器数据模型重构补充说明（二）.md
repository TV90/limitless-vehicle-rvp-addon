# RVP 武器数据模型重构补充说明（二）

> 本文是对 [RVP武器数据模型文档.md](/D:/ywzj/ywzj/ywzj_rvp/docs/plan/RVP武器数据模型文档.md) 的补充说明。
>
> 本文不直接修改只读目标文档，只记录当前已经确认的补充字段、迁移方向与排雷结论，供后续手动回填到正式文档。

## 1. 当前补充结论

1. `IR / AIR / SARH / ARH` 的抗干扰参数暂不保留在当前白名单模型中。
原因：现阶段并未真正实现热焰弹、箔条、DIRCM、ECM 等干扰物运行链，继续把这批参数留在 `RVP_GuidanceData` 里，只会扩大基类而不会带来真实功能收益。

2. `ARM` 的记忆、锁定保持、目标优先级加权参数必须保留。
原因：这批参数已经对应实际行为，不属于“未来预留”，删除后会直接弱化反辐射导弹的核心逻辑。

3. `charge_time` 与 `minigun_spin_decay_tick` 目前仍然影响真实开火逻辑。
原因：它们决定 `CHARGE / MINIGUN / RAILGUN` 的门控与前端读条，暂时不能空删。

4. `dual_pulse` 统一改名为 `secondPulse`。
原因：该参数本质上是双脉冲火箭发动机开关，应该与 `secondPulseTriggerSpeed / secondPulseTriggerDistance / secondPulseThrust / secondPulseBurnTime` 命名族一致。

5. 高空阻力暂不改成单个 `Map`。
原因：当前分层阻力已经有平滑插值，直接硬改成离散 `Map` 容易把既有手感改坏；短期内先保留“分层参数 + 平滑插值”的模型更稳。

6. `RVP_GuidanceData` 为大多数制导武器服务这件事本身不是错误。
问题不在于“字段被很多制导类型复用”，而在于“哪些字段是公共字段、哪些字段应该下沉到子类”尚未划清边界。

## 2. ARM 子字段补充方案

建议新增 `RVP_GuidanceDataARM`，继承自 `RVP_GuidanceData`。

适用范围：
- `guidanceType == ARM`

用途：
- 承载反辐射导弹独有的辐射源记忆、脱锁保持、目标优先级加权逻辑。
- 从 `RVP_GuidanceData` 基类中移出 ARM 独占参数，避免继续污染公共制导模型。

### 2.1 建议补充到正式文档的字段

| RVP_GuidanceDataARM 特有字段 | 解释 | 类型 | 默认值 |
| ---------------------------- | ---- | ---- | ------ |
| radiationPulseMemoryTick | 导弹对雷达辐射脉冲的短时记忆 tick。即便辐射源瞬间停机或脉冲间歇，仍允许导弹在该时长内继续认为“最近一次辐射源有效”。用于避免 ARM 因脉冲雷达间歇发射而瞬时丢失引导。 | int | 40 |
| armMemoryTick | 导弹在彻底失去辐射源后，对最后一个有效辐射源位置/目标的持续记忆 tick。该阶段允许导弹继续朝最后记忆点飞行并尝试重新捕获。 | int | 120 |
| armLockedEmitterBonus | 对“已经被火控锁定/预选的辐射源”附加的优先级加权系数。值越高，ARM 越倾向继续攻击当前主目标，而不是被视场内新的辐射源轻易抢走。 | float | 0 |

### 2.2 迁移说明

旧字段来源：
- `radiation_pulse_memory_tick`
- `arm_memory_tick`
- `arm_locked_emitter_bonus`

迁移方向：
- 从 `RVP_GuidanceData` 移出
- 收拢到 `RVP_GuidanceDataARM`
- `RVP_GuidanceDataAdapter` 在 `guidance_type == ARM` 时反序列化为 ARM 子类

删除旧字段前提：
- `RVP_GuidanceModelResolver`
- ARM runtime source
- ARM 目标评分逻辑

以上三处必须全部改为读取 `RVP_GuidanceDataARM`，否则会直接丢失 ARM 记忆与优先级能力。

## 3. RVP_FireData 补充方案

当前建议不立刻删除这两个字段，而是先把它们明确写回 `RVP_FireData` 正式文档，再视后续是否统一命名。

### 3.1 建议补充到正式文档的字段

| RVP_FireData 补充字段 | 解释 | 类型 | 默认值 |
| --------------------- | ---- | ---- | ------ |
| chargeTime | 蓄力时间或转管爬升时间 tick。`CHARGE / RAILGUN` 模式下表示蓄满所需时间；`MINIGUN` 模式下表示转速爬满所需时间。JSON 现阶段对应旧键 `charge_time`。 | int | 0 |
| minigunSpinDecayTick | 仅 `MINIGUN` 生效。松开开火键后每 tick 的转速衰减量。若未填写或小于等于 0，则按 `max(chargeTime / 4, 1)` 回退。JSON 现阶段对应旧键 `minigun_spin_decay_tick`。 | int | 0 |

### 3.2 迁移说明

这两个字段当前已经被如下链路直接消费：
- `RVP_WeaponFireController`
- `RVP_WeaponBase`
- `RVP_ChargeBarOverlay`
- `RVP_ClientLaserDriver`

因此短期内不建议空删。

后续若要统一命名，有两种路线：

1. 保守路线  
继续在 Java 侧保留 `chargeTime / minigunSpinDecayTick` 语义，仅调整文档与 JSON 键说明。

2. 强重构路线  
将其改写为 `chargeTick / chargeDecayTick`，然后同步修改：
- weapon fire controller
- minigun spin 衰减逻辑
- charge HUD
- laser client driver

若走第二条路线，`chargeDecayTick` 必须先定义清楚它是否只服务 `MINIGUN`，还是同时服务 `CHARGE / RAILGUN` 的蓄力衰减。这个语义如果不先钉死，后续很容易再绕回来。

## 4. secondPulse 命名结论

建议将 `RVP_ProjectileData` 中的：
- `dual_pulse`

统一重命名为：
- `secondPulse`

同时保留以下同族字段不变：
- `secondPulseTriggerSpeed`
- `secondPulseTriggerDistance`
- `secondPulseThrust`
- `secondPulseBurnTime`

### 4.1 原因

`dual_pulse` 是旧时代的开关命名，放在新模型里不统一。  
当前这一组参数本质上描述的是“第二脉冲发动机是否存在、在何时触发、触发后持续多久、提供多大推力”，因此以 `secondPulse*` 命名更自然。

### 4.2 迁移要求

删除 `dual_pulse` 旧字段之前，必须把以下读取链全部切到新名字：
- `RVP_ProjectileData`
- `RVP_BaseBullet`
- 双脉冲 debug / 可视化链

否则双脉冲导弹会直接退化成单脉冲。

## 5. 高空阻力模型调整结论

`RVP_ProjectileData` 不再优先收敛为单个 `altitudeDragFactor: Map<RVP_Range<Float>, Float>`。

短期建议：
- 保留分层阻力字段
- 保留平滑插值

建议字段组如下：

| RVP_ProjectileData 分层阻力字段 | 解释 | 类型 | 默认值 |
| ------------------------------- | ---- | ---- | ------ |
| altitudeDragFactorEnabled | 是否启用分层空气阻力倍率。关闭后导弹仅使用基础 `drag / dragCoefficient`。 | boolean | true |
| altitudeDragLowY | 低空层锚点高度。 | float | -64 |
| altitudeDragLowFactor | 低空层阻力倍率。 | float | 2.0 |
| altitudeDragBaseY | 标准空气层锚点高度。 | float | 384 |
| altitudeDragBaseFactor | 标准空气层阻力倍率。 | float | 1.0 |
| altitudeDragThinY | 稀薄空气层锚点高度。 | float | 500 |
| altitudeDragThinFactor | 稀薄空气层阻力倍率。 | float | 0.6 |
| altitudeDragHighY | 高空层锚点高度。 | float | 1000 |
| altitudeDragHighFactor | 高空层阻力倍率。 | float | 0.34 |

### 5.1 为什么暂不改成单个 Map

1. 当前实现已经包含平滑插值，不是生硬跳段。  
2. 这套物理直接影响导弹能量保持、最大射程、末端速度、机动余量。  
3. 如果先把结构改成离散 `Map`，再临时补插值，等于平白多做一轮高风险改动。

### 5.2 后续解决方式

后续若仍希望收敛结构，可以单独新增：
- `RVP_AltitudeDragProfileData`

但这个动作应当在“物理回归测试稳定之后”再做，不应与当前制导主重构绑在同一批次。

## 6. RVP_GuidanceData 过度承载问题与解决思路

### 6.1 现象

当前 `RVP_GuidanceData` 中的同类字段，确实在为绝大多数制导武器服务。

这类字段包括：
- 距离范围
- 高度范围
- 时间范围
- 扫描间隔
- 视场角
- 离轴角
- 巡航段参数
- 角度门
- 惯性制导

除了 `SACLOS`、`LOSBR` 这类更偏手操/驾束的武器外，`IR / AIR / SARH / ARH / ARM / GPS / ATV` 都能复用其中大部分公共行为。

### 6.2 这是不是问题

这本身不是问题。

真正的问题是：
- 基类里既有“多数制导共享的公共参数”
- 又混进了“某一种制导独占的专用参数”

前者应该保留在基类。  
后者应该拆到子类。

### 6.3 建议的解决原则

`RVP_GuidanceData` 只保留“多数非手操制导共享”的字段：
- 制导类型
- 时机范围
- 距离/高度范围
- 视场/离轴/角度门
- 巡航段
- 攻顶
- 惯性制导
- 末端制导

子类只承载真正独占的专用字段：

- `RVP_GuidanceDataHITL`
  - `hitlMaxTurnDegPerTick`
  - `signalSource`
  - `hitlMaxControlDist`
  - `hitlMaxControlTick`
  - `hitlMaxLookOffset`
  - `hitlVideoModes`

- `RVP_GuidanceDataGPS`
  - `gpsSpreadRadius`

- `RVP_GuidanceDataARM`
  - `radiationPulseMemoryTick`
  - `armMemoryTick`
  - `armLockedEmitterBonus`

未来若真正实现抗干扰体系，再视需要新增：
- `RVP_GuidanceDataCountermeasure`
或按制导类型分别扩到：
- `RVP_GuidanceDataIR`
- `RVP_GuidanceDataRadar`

但在未落地真实干扰物前，不建议为了“未来也许会做”把它们继续塞在基类。

### 6.4 对 `SACLOS / LOSBR` 的处理建议

这两类武器不应为了迎合基类结构而硬吃 seeker 参数。

建议运行时处理方式为：
- 允许它们继续使用 `guidanceTickRange / cruiseStartTick / topAttackHeight` 这类时间或弹道辅助参数
- 忽略 `maxLockAngle / scanIntervalTick / activeRadarActivationRange` 这类 seeker 专属参数

也就是说，解决方式不是把公共基类拆碎，而是为“手操型制导”明确一份字段适用范围。

### 6.5 比例导引（PN）补充结论

当前口径调整为：

- `use_proportional_navigation` 可以删除
- 删除 `predictTargetPos` 原有的“预测截获点”语义
- `predictTargetPos` 这个字段本身保留，但语义改为“是否启用比例导引”
- `predictTargetPos=false` 表示不启用 PN
- `predictTargetPos=true` 表示启用 PN
- 当前方案目标是“真正的比例导引”，不是“预测截获点追踪”

换句话说，后续 `predictTargetPos` 不再表示“要不要算截获点”，而是直接表示“要不要走 PN”。
原先那套 `RVP_InterceptSolver` 预测截获点追踪逻辑，应当从主制导链中移除，而不是继续保留一个旧功能再额外挂一个 PN 开关。

#### 6.5.1 建议补充到正式文档的字段

| 字段 | 解释 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `predictTargetPos` | 字段保留但重定义。原“预测截获点”功能删除后，该字段改为“是否启用比例导引”。为 `false` 时不走 PN；为 `true` 时走 PN。 | `boolean` | `false` |
| `predictTargetPosGain` | 比例导引增益系数。值越大，导弹对 LOS 转率和闭合速度的响应越积极。仅在 `predictTargetPos=true` 时生效。 | `float` | 代码默认值 |
| `maxLateralAccel` | PN 横向修正的限幅值。用于限制单 tick 横向修正过强导致的大幅甩尾、绕大弯、撞地或乱飞。仅在 `predictTargetPos=true` 时生效。 | `float` | 代码默认值 |
| `predictTargetPosStartTick` | 发射后从第多少 tick 开始施加 PN 修正。用于避免导弹低速、离架、刚点火阶段就被 PN 拉出过大偏转。仅在 `predictTargetPos=true` 时生效。 | `int` | 代码默认值 |

#### 6.5.2 放置位置建议

主制导段：
- `guidance_data.predict_target_pos`
- `guidance_data.predict_target_pos_gain`
- `guidance_data.max_lateral_accel`
- `guidance_data.predict_target_pos_start_tick`

末端制导段：
- `guidance_data.terminal_guidance.predict_target_pos`
- `guidance_data.terminal_guidance.predict_target_pos_gain`
- `guidance_data.terminal_guidance.max_lateral_accel`
- `guidance_data.terminal_guidance.predict_target_pos_start_tick`

#### 6.5.3 与现有字段的关系

- `predictTargetPos`：保留字段名，但重定义为“是否启用比例导引”
- `turningFactor`：继续表示基础转向插值强度，不等价于 PN
- `maxGuidanceAngle`：继续表示发射后导引保持角限制，不等价于 PN 限幅

因此：

1. `use_proportional_navigation` 不再保留
2. `predictTargetPos` 直接承担 PN 开关语义
3. 预测截获点功能整体删除，不再作为独立能力存在

#### 6.5.4 迁移风险备注

如果只把 `predictTargetPos` 改成 PN 开关，但不补 `maxLateralAccel` 和 `predictTargetPosStartTick`，在当前 RVP 物理下很容易出现以下问题：

- 发射初段就猛拐
- 超级大弯
- 低空撞地
- 近距离乱飞
- 高机动目标下过冲严重

所以这轮文档里，PN 最少应视为“一个布尔开关 + 两到三个调参项”：

- `predictTargetPos`
- `predictTargetPosGain`
- `maxLateralAccel`
- `predictTargetPosStartTick`

## 7. 本轮建议的文档动作

1. 在正式文档中新增 `RVP_GuidanceDataARM` 章节。  
2. 在 `RVP_FireData` 中补回 `chargeTime` 与 `minigunSpinDecayTick`。  
3. 将 `dual_pulse` 的目标命名修正为 `secondPulse`。  
4. 将高空阻力方案暂时从“单 Map”改回“分层阻力 + 平滑插值”。  
5. 在文档中明确：`RVP_GuidanceData` 是“多数非手操制导的公共模型”，不是“所有制导一视同仁的参数垃圾桶”。  
6. 在文档附注中声明：`SACLOS / LOSBR` 对 seeker 类字段按运行时忽略处理。
