# RVP 导弹新气动调研报告（fishking 攻角模型 + 质量/推力批次）

> 调研日期：2026-10-09。**只调研，未修改任何代码/JSON/权威包。**
> 入口提交：`b20084e9`（10-07，弹体气动学 V0.4：数学层接入导弹攻角，25 文件）→ `0ec1b723`（10-09，84 导弹启用新气动 + 质量/推力调整，105 文件）。
> 两笔提交之间及之后，制导/弹体源码无再改动（`git diff b20084e9..HEAD` 仅 docs 与粒子通道），本报告行号以当前工作区为准。
> 调研背景：本会话刚交付弹间直击拦截（`isInterceptableEnemyAmmo` 放开 + `collision_box_size` 0.7 兑现近炸），见交接文档 §一.1。

---

## 〇、结论速览（TL;DR）

1. **两笔提交是一个整体**：b20084e9 落地"机头与速度分离"的准三自由度攻角模型（默认关闭，零 JSON 改动）；0ec1b723 一天内把 **84 枚活动 rvp:missile 全部批量启用**，并同步把质量/推力/燃料从归一化小数重标定为真实单位（kg/N 口径），另有 10 机炮 + 3 火箭弹加 `inherit_vehicle_velocity`。
2. **实配与 fishking 自己的设计文档存在两处明显偏离**（最重要发现）：
   - 攻角统计文档按弹种给出 **8°（弹道/巡航）～30°（PL-10）** 的分级建议值（`docs/RVP弹体-fish/RVP武器公开数据与攻角统计_20261008.md`），但 JSON 里 **84 弹统一写死 `rvp_attack_angle_limit_deg: 60`**——所有弹一个宽锥，弹种差异化尚未做；
   - 方案文档 §9.2 明确"**建议先单独配置一枚测试弹，不要直接批量改载具包**"（`RVP制导转向气动化方案_最小JSON改动.md`），实际是全量批量启用。60° 更像"先放开不扼杀机动"的首跑值，不是调参终值。
3. **有效启用面是 80/84，不是 84**：4 弹 `turning_factor>=1`（tow2b/tow2n/ucav_switchblade/zbl08a_hj73e）按设计继续瞬转豁免，攻角不生效；3 弹未配 tf（agm_114/aim_120/pl_8）运行时默认 0.5；其余 77 弹以 tf 0.06~0.12 生效。**当前没有任何弹配显式 `rvp_maxg`**。
4. **质量/推力取向**：88 个导弹 JSON（含 4 个 `_backup`）全部重写 mass/thrust 并新增 fuel_mass；**56 枚保持原初始加速度**（纯单位换算，thrust/mass 比值不变）、**30 枚刻意下调**（空空弹 ×4~7.5、反舰/巡航 ×6.5~48、ATACMS ×206）、**2 枚上调**（9M723 ×9、YJ-20 ×1.2）。同时 **fuel_mass 变质量机制首次实装**（字段与线性燃耗代码 b20084e9 之前就有，此前全项目 fuel_mass=0 等于死字段）——燃烧期质量线性递减、加速度渐增，是全弹种的二重新行为。
5. **速度语义变化（设计动压点等价、两端偏离）**：tf→等效 G 折算保证在参考速度处新旧转向能力逐点一致；但**低速段可用过载按 (v/v_ref)² 动压减载（下限 0.05）**——发射初速阶段转向角速度只有旧版的约 1/5~1/20，近距弹/贴脸拦截的手感与命中率会明显变化；高速段转向半径随 v² 放大（旧版是固定角速度插值）。`rvp_ref_speed` 全部走 `max_speed→初速→3.0` 回退，无人显式配置。
6. **与本会话直击拦截工作的耦合为零代码耦合、有节奏耦合**：直击候选过滤/命中结算是位置驱动的，与弹轴姿态无关，`collision_box_size`/近炸逻辑不动；但导弹加速变慢 + 低速转向变差 → **双方交战窗口、拦截提前量、被拦概率全部漂移**，此前实机验证过的拦截手感结论需要在新气动下重新校验。
7. **一次性部署后果**：虚拟弹道积分器 VERSION 8→9，升级瞬间**在途虚拟弹以 `INCOMPATIBLE_INTEGRATOR` 终止**（不迁移不重建），部署前尽量清空在途虚拟弹。测试服当前 jar 是旧版（含无害 [RVP-WreckBones] 日志的 wreckdiag 版），正式验证前必须更新最新构建。

---

## 一、两笔提交概览

| | b20084e9（10-07） | 0ec1b723（10-09） |
| --- | --- | --- |
| 主题 | 攻角模型底座（纯代码+测试+方案文档，默认关闭） | 批量启用 + 数值重标定（JSON+统计文档） |
| 规模 | 25 文件（+2502/−1189），新增 20 项测试 | 105 文件 = 4 docs + 101 JSON（84 活动 + 4 backup + 13 机炮/火箭） |
| JSON 改动 | **无**（明示"未修改任何载具包武器 JSON"） | 84 弹 ×`rvp_aero_steering:true`+`rvp_attack_angle_limit_deg:60`+mass/fuel_mass/thrust 重写；13 非导弹 ×`inherit_vehicle_velocity:true` |
| 自带文档 | 《最小JSON改动》方案重写 474 行（旧版 1332 行归档）+ 阶段1实现文档 | 新增《速度统计》，更新《攻角统计》《推进与燃料统计》，docs/README.md 入口 |

fishking 的工作流是"方案文档 → 统计文档 → 阶段实现 → 批量启用"四步，文档密度很高，三份统计文档就是 0ec1b723 数值的直接出处（质量/燃料来源分 P1~P7/W1~W2/D1~D2/E 八级溯源，DCS AIM-120C 的 `mass=161.48/fuel_mass=51.26/work_time=6.5` 是基准锚点）。

## 二、参数体系与实配盘点（JSON 层）

### 2.1 启用方式与攻角值

- 启用最小片段 = `projectile_data.rvp_aero_steering=true` + `rvp_attack_angle_limit_deg ∈ (0,90)`（`aim_120.json:20-21` 等全部 84 弹）。非法值/缺省一律解析为 0=关闭（`RVP_ProjectileData.getRvpAttackAngleLimitDeg`，`RVP_ProjectileData.java:379-384`）。
- **84 弹攻角值全部为 60**（`grep -c` 实测 84/84），无弹种差异。对照攻角统计文档的建议分级：

| 弹种（统计文档建议） | 建议值 | 实配 |
| --- | --- | --- |
| 弹道/巡航（9M723、战斧、风暴阴影、ATACMS、AKF-98A…） | 8° | 60° |
| 反舰（AGM-84、YJ-83/80…） | 10° | 60° |
| 空地（AGM-65/179、KD-88、KH-38…） | 12~15° | 60° |
| 中程空空（AIM-120/54/7、PL-12/15、9M317MA、ESSM…） | 15~20° | 60° |
| 近距红外（AIM-9M、PL-10/8、IRIS-T、FIM-92、HQ-10、MICA） | 25~30° | 60° |

### 2.2 有效生效面：80/84

门控终判在 `RVP_AeroSteeringLimits.attackAngleEnabled()`（`RVP_AeroSteeringLimits.java:37-41`）：导弹 ∧ 智能引信未接管 ∧ aero_steering ∧ (0,90)° ∧（显式 `rvp_maxg` 或 `turning_factor<1`）。实配扫描结果：

- **77 弹 tf 0.06~0.12 生效**（33×0.12、31×0.10、7×0.09、其余为 0.06/0.08 及 5 枚分段区间值）；
- **3 弹无 tf**（agm_114/aim_120/pl_8）→ `resolveTurningFactor` 为 null 时运行时默认 0.5（`RVP_GuidanceRuntimeMath.java:606-609`），生效且响应最快；
- **4 弹 tf=1.0 瞬转豁免**（lav25_tow2b/tow2n、ucav_switchblade、zbl08a_hj73e）——JSON 虽写了 aero_steering+60°，实际不生效，行为不变（方案文档 §3.1 第 5 条设计如此）；
- 全 84 弹 **无显式 `rvp_maxg`**、无显式 `rvp_ref_speed`、无显式 `rvp_induced_drag`（默认复用 `drag_coefficient`）、无 `constant_speed`——即**诱导阻力对所有生效弹都开**，动压参考速度全部走 `max_speed→武器初速→3.0` 回退。

### 2.3 质量/推力/燃料重标定

- 单位口径：`RVP_QuadraticAirDrag.java:16,58`——**质量 <1 按"吨"×1000 换算阻力质量，≥1 按原值**；推力加速度恒为 `thrust/mass`（`RVP_PropulsionMath.accelerationPerTick`，`RVP_PropulsionMath.java:61-62`）。旧配置全是 <1 的小数（吨口径），新配置全是真实 kg 值。
- 88 个导弹 JSON 全部重写三件套并新增 `fuel_mass`；`motor_burn_time`、`ignition_delay_tick`、`drag_coefficient`、`max_speed`、二脉冲触发参数**全部未动**。4 个 `_backup` 文件被写成 `mass=0.0`（不在活动加载口径内，仅备注）。
- 初始加速度（thrust/mass，格/Tick²）取向：

| 取向 | 数量 | 代表（旧→新） |
| --- | --- | --- |
| 不变（纯换算） | 56 | HQ-10/HQ-9B/HQ-13/RIM-161/SM-6 0.70、AGM-65 2.00、TOW/HJ-73E 10.0、AIM-260/LD-10 2.50、AGM-179/硫磺石 0.125 |
| 下调 | 30 | ATACMS 72.9→0.354；YJ-80 4.0→0.083；战斧 1.0→0.021；鱼叉 ×12~20；AIM-120D 1.42→0.275；AIM-120 1.25→0.228；PL-12/MICA/AIM-7P/R-27R ~2.1→0.27；AIM-9M/PL-10 1.9~2.5→0.456；KD-88 系 0.25→0.038 |
| 上调 | 2 | 9M723 0.017→0.155（×9）；YJ-20 0.167→0.201 |

- 设计取向解读：**防空/反坦克弹速度剖面基本原样，空空弹点火爬坡拉长**（AIM-120 系达 max_speed 6.0 从约 5 Tick 拉长到 20 Tick≈1 秒），**大弹（弹道/巡航/反舰）从"秒到满速"改为"缓慢加速"**，观感向真实推进曲线靠拢。
- **变质量首次实装**：`fuel_mass` 字段与线性燃耗 `mass(motorTick)=干质量+燃料×(1−进度)` 代码在 b20084e9 之前已存在（`RVP_ProjectileData.java:146-151,557-570`；`RVP_PropulsionMath.resolveMotorState`，`RVP_PropulsionMath.java:32-46`），但此前全项目无配置。现在燃烧期阻力质量与推力加速度都随燃耗变化（如 AIM-120 加速度 0.228→0.334 渐增）。这是叠加在攻角之外的**全弹种二重新行为**。
- fishking 自证的配置疑点（《速度统计》§4）：`9k720_9m723` 配 `max_speed:9` 但当前推进物理峰值只有 ~1.95（不可达）；`agm_114`/`pl_8` 无 `max_speed`，平衡速度 ~76/69 格/Tick（无钳制直飞会一直加速；旧配置换算后平衡速度相近，属既有特性非本批回归，但叠加变质量后值得实测确认）。

### 2.4 `inherit_vehicle_velocity` 扩面

- 13 个非导弹武器新增：10 机炮（m61a1×4、gsh_30_1×2、m230、2a42、gsh_23、m791）+ 3 火箭弹（hydra70、yasser、s13）——**机炮/火箭弹初速开始叠加载具速度**，移动平台射击的弹道与命中率会变。
- 导弹侧净增 41（现 55/84 为 true）：空空/空地弹多数叠加载机速度，舰载弹多数 false。

## 三、行为面（代码层）

### 3.1 单 Tick 攻角求解（`RVP_AttackAngleModel.solve`，纯函数，实体/虚拟共用）

1. **机头响应**：机头轴（`getLookAngle()`）按球面插值朝制导期望方向推进，响应比例 = 显式 G 时固定 0.5，否则 `turning_factor`（`RVP_AttackAngleModel.java:43-46`）；
2. **攻角锥**：机头被限制在速度轴周围 α_limit 锥内（`:47-53`）；
3. **载荷**：`λ = sin(α)/sin(α_limit) ∈ 0~1`（`:54`）；
4. **法向转角**：`G_normal = availableGs × λ`，`turn = 2·asin(clamp(G_normal·G/(2v),0,1))`，受 `rvp_turn_rate_limit` 与"最多转到机头轴"双钳制，速率不变（`:56-62`）；
5. **可用过载**：`availableGs = 设计G × clamp(密度×(v/v_ref)², 0.05, 1)`（`RVP_AeroSteeringModel.java:41-57,98-118`）；设计 G = 显式 `rvp_maxg`，否则 tf 折算 `equivalentGsFromTurningFactor`（`:69-85`，弦长反演——**在设计动压点与旧版 tf 插值逐点等价**，如 tf=0.12 → 7.79°/Tick、tf=0.5 → 45°/Tick）；
6. **速度转向后**由运动阶段沿机头施加推力、基础阻力/重力、速度钳制，再扣一次诱导阻力 `rvp_induced_drag × λ² × speed`（`RVP_ProjectileMotion.applyInducedDrag`，`RVP_ProjectileMotion.java:535-558`；实测默认 = `drag_coefficient`，如 AIM-120 为 0.003）。

**转向语义的三段变化**（相对旧版"速度向量按 tf 固定比例插值"）：
- v ≈ v_ref：与旧版等价（折算保证）；
- v < v_ref：可用 G 按 (v/v_ref)² 减载（0.05 下限），但转角公式分母是 2v——**转向半径在低速段趋于常数 v_ref²/(G_design·G)，角速度随速度线性缩小**。发射初速段（如 AIM-120 v=1.5、v_ref=6）实际转向角速度约 11°/Tick vs 旧版 45°/Tick，**近距弹道明显变"宽"**；
- v > v_ref：G 封顶，转向半径 ∝ v²（高速大机动代价上升）。

### 3.2 优先级与"豁免面"（旧链路全部被门控跳过）

| 环节 | 攻角生效时的行为 | 证据 |
| --- | --- | --- |
| 速度预插值（tf/G 裁决） | `steeringFactor=1.0` 绕过，期望方向原样下传（普通制导与 PRESET 两处） | `RVP_GuidanceRuntimeMath.java:148,228` |
| `rotate_to_motion` | 点火后贴速度/滑行 lerp 全部跳过 | `RVP_ProjectileMotion.java:126,188-204` |
| 制导出口 `applyGuidanceFacing` | 不再强制弹轴=速度 | `RVP_ProjectileMotion.java:329-338` |
| 穿透减速 `applyRotationFromVelocity` | 同上 | `RVP_ProjectileMotion.java:340-344` |
| 发射朝向 `finalizeSpawnOrientation` | 出膛后不再贴速度 | `RVP_ProjectileMotion.java:302-308` |
| 远程弹药克隆渲染 | 保留独立机头姿态 | `RVP_RemoteAmmoVisualRenderer.java:295-299` |
| 快照恢复 `alignVirtualRestoredAttitudeToVelocity` | 姿态原样恢复（xRot/yRot 本就随快照走），不再按速度校正 | `RVP_BaseBullet.java:2081-2086` |
| 智能引信精确追点 | `smartFuseActive` 整体豁免攻角，沿用原运动契约 | `RVP_ProjectileMotion.java:33`、`RVP_BaseBullet.java:1885` |

**steeringFactor 绕过的影响要拆开说**（交接文档预判的方向正确，但幅度要修正）：tf=0.12 的弹（agm179_ir、mi28_kh_39 等 33 弹）**机头**仍按 tf=0.12 响应（语义与旧直觉同源），被绕过的是"速度层的 tf 插值"——速度转向改由 `等效G(tf)×λ×动压` 裁决。设计速度附近总效果与旧版相当，**真正改变的是低速/高速两端与"机头先转、弹道漂移"的形态**，而非"转向完全失控"。分段 tf（5 弹含 0.06→0.10 等区间）的区间切换语义同样从"速度插值强度"变为"机头响应+设计 G 折算"双通道。

### 3.3 各制导路径接入情况

- **常规/PRESET/GPS**：`RVP_GuidanceRuntimeMath.java:148,228` 绕过预插值 → `applyResolvedAeroSteering`（`:622-650`，`isMissile && attackAngleEnabled` 走攻角适配器 `:633-635`）；预测拦截 PIP/追踪/PRESET 三段式的期望方向生成逻辑未动。
- **HITL/TV**：`tickHitlTvMove` 首行改道——攻角弹走 `tickMissileMove`/`tickBallisticMotion`（`RVP_ProjectileMotion.java:360-366`），**旧"速度每 Tick 投影到操作手视线+峰值补速"路径整体绕开**，TV 手感从"指哪飞哪"变为"机头指哪、弹道惯性漂移"。这是 TV 弹玩家感知最大的单点变化。
- **线导/MCLOS 直控**：`RVP_WireGuidanceSteering.java:85-92` 接同一攻角适配器并提前返回，防直控代码二次覆盖姿态。
- **SACLOS/DIRCM 强制偏转**：偏转本身不受攻角裁决（仍可把速度掰走），但实际机头-速度夹角按饱和载荷记账、承担诱导阻力（`RVP_RuntimeSaclosGuidanceSource.java:235,245`；`RVP_JamDeflectionHelper.java:62`）；软杀对抗的相对效能因此略变（被干扰弹掉速更多）。
- **丢锁/无指令兜底**：每 Tick 制导未执行时 `finishAttackAngleGuidance` 以当前速度为期望方向推进残留攻角（`RVP_BaseBullet.java:1882-1885` + `RVP_ProjectileMotion.java:63-73`）——弹道平滑回直、不掉出模型。
- **无发动机导弹**：`tickBallisticMotion` 里攻角弹也结算诱导阻力（`RVP_BaseBullet.java:2565-2568`）。

### 3.4 虚拟弹道预览与实体一致性

- `RVP_RvpTrajectoryIntegrator` VERSION **8→9**（`RVP_RvpTrajectoryIntegrator.java:24`），新增 `stepAttackAngle` 分支（`:44-45,124-186`）：与实体同序（制导方向生成→攻角求解→沿机头推力→阻力→重力→钳制→诱导阻力一次），GPS/PRESET 拆出 `resolveGPSCruiseDirection`/`resolvePresetBallisticDirection` 纯方向生成器（`RVP_BallisticTrajectoryMath.java`，实体/虚拟共用）。
- fishking 明示**不逐 Tick 等价**的剩余缺口（方案文档 §6.3/阶段1文档）：第二脉冲触发、载机冷发射窗口、风场/水中、实体 GPS 特殊阶段、近距离目标点算法与碰撞时序。当前 16 枚二脉冲弹（AIM-260/PL-15 系/YJ-19/战斧等）的虚拟预览与实际弹道偏差是已知缺口，**虚拟轨迹预览 UI 的可信度对这些弹下降**。
- 部署即版本断层：旧在途虚拟弹 `INCOMPATIBLE_INTEGRATOR` 终止（不静默迁移）。

### 3.5 诊断与调试入口

`RVP_ProjectileLifecycleDebug` 新增攻角诊断字段：`attackAngleActive / attackAngleLimitDeg / bodyVelocityAngleDeg（机头-速度夹角）/ aeroLoadFactor（λ）/ aeroAvailableG / aeroReferenceSpeed / aeroDensityFactor / aeroInducedDrag`——实测时开生命周期日志即可直接读出攻角是否生效、锥角、载荷与可用 G，无需靠弹道反推。

## 四、与本会话直击拦截/近炸/collision_box_size 的交互

- **零代码耦合**：直击候选过滤（`isInterceptableEnemyAmmo`：服务端+canDamageEntity+敌对判定）与 `handleEntityImpact`（命中敌对弹药按其引信语义引爆+双方 discard）全部是**位置驱动**，与弹轴姿态、攻角模型无交集；`collision_box_size` 0.7 的近炸+0.32m 兑现路径不变。攻击角弹的碰撞盒仍随实体位置走，不存在"机头 60° 漂移导致判定盒偏移"的问题。
- **节奏耦合（需要重校验）**：
  1. 拦截窗口变长——来袭弹加速变慢（如 AIM-120 系 20 Tick 才达 6.0）、低速段转向变差，近防炮/近空弹的**可拦时间窗和拦截成功率都会上升**；同时被保护方反击弹同理变慢，攻防两侧对称变化；
  2. PIP 预测拦截（`steerPredictiveIntercept` 用目标速度外推）不受影响，但**被拦截目标的轨迹形态变了**（漂移曲线 vs 旧直线束），拦截弹末段修正需求上升；
  3. 攻角弹被干扰/偏转后掉速更多（λ² 诱导阻力），**DIRCM/SACLOS 干扰的实战收益略升**；
  4. 直击拦截（弹间对撞）双方相对速度普遍下降，碰撞判定本身不敏感，但**直击造成的双爆时机后移**，近战观感变化。
- 结论：本会话实机验证过的"直击拦截生效"结论在新气动下**依然成立但参数已漂移**，建议按 §七 复测一轮拦截三件套（近炮拦弹、弹间对撞、近炸半径观感）。

## 五、风险点清单（按优先级）

| # | 等级 | 风险 | 依据 |
| --- | --- | --- | --- |
| R1 | 高 | **60° 统一攻角锥与弹种严重失配**：低机动弹（战斧/ATACMS/9M723 建议 8°）允许漂到 60°，转弯观感失真、推力投影损失（60° 时轴向推力只剩 50%）+λ² 诱导阻力全额燃烧速度；近距格斗弹（建议 25~30°）反而约束偏松 | 统计文档建议表 vs 84/84 实配 60° |
| R2 | 高 | **低速段转向能力大幅低于旧版**：发射初速段可用过载按 (v/v_ref)² 减载（0.05 下限），近距/自卫拦截命中率、贴脸躲闪手感变化最大；与 tf 数值的旧调参直觉不再对应，沿用"调 tf"的老经验会双重生效（机头响应+设计 G 同降） | `RVP_AeroSteeringModel.java:41-57`；方案文档 §3.3 双重效果声明 |
| R3 | 中高 | **30 弹加速度下调 + 变质量首次生效**：全弹种速度剖面变化（空空爬坡 1s、巡航弹 4~7s 到满速、ATACMS 从"瞬发"变 3.5s 爬坡），此前所有命中/拦截/射程实测数据失效 | §2.3 统计；`RVP_ProjectileData.java:557-570` |
| R4 | 中 | **HITL/TV 手感重写**：速度不再贴视线，操作手需适应惯性漂移；若用户觉得"TV 弹不听话"优先查此路径 | `RVP_ProjectileMotion.java:360-366` |
| R5 | 中 | **虚拟预览与实体已知不等价**（二脉冲/冷发射/风/水/GPS 特殊阶段），16 枚二脉冲弹预览可信度下降；虚拟-实体交接边界（快照姿态恢复）已门控但依赖实机验证 | 方案文档 §6.3；`RVP_BaseBullet.java:2081-2086` |
| R6 | 低中 | **部署断层**：积分器 v9 使在途虚拟弹 `INCOMPATIBLE_INTEGRATOR` 终止；测试服 jar 仍是 wreckdiag 旧版，正式验证前必须换 `build/libs/ywzj_rvp-1.20.1-0.6.0-all.jar` | `RVP_RvpTrajectoryIntegrator.java:24`；交接 §会话状态 |
| R7 | 低 | 4 弹 tf≥1 豁免但 JSON 已写 aero_steering——配置层面"看起来开了实际没开"，后续调参易困惑；`_backup/` 4 文件 mass=0.0（不加载，防误拷） | §2.2；`RVP_AeroSteeringLimits.java:40` |
| R8 | 低 | `agm_114`/`pl_8` 无 `max_speed`：燃烧 600/60 Tick 内可加速到 ~76/69 格/Tick（3.8 km/s 级游戏速度），远超所有直击/近炸交战尺度——旧配置同类问题，非本批回归，但攻角+变质量叠加后建议顺手补钳制 | 《速度统计》§4.2 |
| R9 | 低 | 机炮/火箭 `inherit_vehicle_velocity`：移动平台弹道整体偏移，配合既有落点习惯的玩家会感知"枪变了" | §2.4 |

## 六、测试覆盖盘点

b20084e9 新增/扩展 4 个测试类（+20 项，当时全量 771/0/0；当前仓库基线 788/0/0 含其它批次）：

- `RVP_AttackAngleModelTest`（151 行，10 项）：直飞零载荷、饱和时机头/速度分离+G 兑现、正弦载荷+平方诱导阻力、动压减载、转角上限、显式 G（含 0G）覆盖 tf、tf≥1 豁免仅限无 G、反向/零速/坏输入有限性、丢锁回正收敛、强制偏转饱和记账；
- `RVP_AttackAngleIntegratorTest`（134 行，7 项）：虚拟与实体共用模型+机头分离持久化、推力沿机头+零重力保持零、诱导阻力单次+恒速豁免、连续 Tick 姿态消费、PRESET 方向同模型、缺省/0 攻角等价、坏输入上报；
- `RVP_ProjectileDataAeroSteeringTest`（100 行，5 项）：缺省/非法值关闭、aero 前置+瞬转豁免保留、默认关闭向后兼容、显式字段覆盖派生值、参考速度回退链（max_speed→初速→3.0）；
- `RVP_VirtualMissileStateCodecTest`（+16 行）：NBT xRot/yRot 姿态往返。

覆盖评价：**数学层与门控层覆盖扎实**（这是纯函数测试的舒适区）；**实体链（GuidanceRuntimeMath/PremiereMotion 各制导路径）与虚拟-实体等价性没有集成测试**——与方案文档"后续启用具体弹种前需实机比较"的表态一致，属已知留白。

## 七、用户可感知变化清单 + 建议实测验证项

**可感知变化（按玩家视角）**：
1. 所有导弹转弯变"漂"：机头先指目标、弹道滞后拖出漂移曲线（最大 60° 漂角），不再贴速度拖尾直线；
2. 空空弹发射后"肉"一下再加速（~1 秒到满速）；反舰/巡航/弹道弹加速期明显拉长；ATACMS/9M723 等大弹爬坡观感重做；
3. 近距狗斗/贴脸拦截：导弹初期转向半径变大，"发射即咬中"的贴脸击杀变难；远距 BVR 末段高速修正半径变大；
4. TV/HITL 弹操作手感惯性化；SACLOS 驾束弹跟线变"软"；
5. 被干扰/偏转的导弹掉速更快（软杀收益上升）；
6. 机炮/火箭弹在移动平台上弹道偏移（初速叠加）；
7. 远程弹模型朝向=机头轴，远程观感上弹体姿态与弹迹分离（渲染已适配）。

**建议实测验证项**（测试服先换最新 jar）：
1. **A/B 基准**：checkout `b20084e9^`（攻角前+旧质量）同场景对比，或临时单弹 `rvp_aero_steering:false` 回滚对照（方案文档 §8.3 的单弹回滚键）；
2. 攻角诊断核对：`/rvpdebug` 生命周期日志读 `attackAngleActive/bodyVelocityAngleDeg/aeroAvailableG`，确认各弹种生效面（重点核 4 弹豁免与 3 弹 tf=null 组）；
3. 拦截三件套复测（新气动下）：近炮拦弹（hpj12 无近炸的直击窗口）、弹间对撞（collision_box_size 0.7）、近炸半径观感——旧结论"直击拦截生效"需在新弹道下复认；
4. 低速端专项：贴脸空空互射、舰载近防对刚发射弹的拦截率——验证 R2 的体感幅度；
5. 大机动端专项：AIM-9M/PL-10 末段 60° 锥下的过载观感与能量（燃尽后速度掉档是否可接受）——为 R1 的按弹种收锥（60→8/10/12/15/20/25/30）提供依据；
6. TV 弹手感验收（R4）+ GPS/PRESET 巡航弹虚拟预览 vs 实际落点抽查（R5，重点二脉冲弹 AIM-260/PL-15/YJ-19）；
7. 移动平台机炮/火箭对地/对空命中复查（R9）；
8. 部署时机：确认无在途虚拟弹再升级（R6）。

## 八、证据索引

**提交**：`b20084e9`（25 文件全 diff 已核）、`0ec1b723`（105 文件，JSON 键变更直方图 + 88 弹 mass/thrust 新旧对照已脚本化核验）。

**JSON**（`limitless_vehicle/rvp/data/rvp/weapons/`）：`aim_120.json`（mass 0.012→161.5、fuel_mass 51.3、thrust 0.015→36.79、aa 60）、`ah64_agm179_ir.json`（tf `[[1,inf]]:0.12` + loft 已移除）、`lav25_tow2b.json`（tf=1 豁免样例）、`ah64_hydra70.json`（ivv 样例）。

**代码**：
- 门控/求解：`RVP_AeroSteeringLimits.java:37-41`；`RVP_AttackAngleModel.java:43-67,71-79`；`RVP_AeroSteeringModel.java:16,41-57,69-85,98-118,128-136`；`RVP_ProjectileData.java:91,146-151,267,379-384,450-458,467,532,557-570`
- 实体链：`RVP_GuidanceRuntimeMath.java:148,228,606-609,622-650`；`RVP_ProjectileMotion.java:32-44,47-60,63-73,76-82,126,157,173,188-204,302-308,329-338,340-344,360-366,402,505-522,535-558`；`RVP_BaseBullet.java:551,1733-1738,1882-1885,2081-2086,2565-2568`；`RVP_WireGuidanceSteering.java:85-92`；`RVP_RuntimeSaclosGuidanceSource.java:235,245`；`RVP_JamDeflectionHelper.java:62`
- 推进/阻力：`RVP_PropulsionMath.java:20,32-46,61-62`；`RVP_QuadraticAirDrag.java:16,58-60`
- 虚拟/渲染/调试：`RVP_RvpTrajectoryIntegrator.java:24,44-45,124-186`；`RVP_RemoteAmmoVisualRenderer.java:295-299`；`RVP_ProjectileLifecycleDebug`（aero 诊断字段）；`RVP_BallisticTrajectoryMath`（resolveGPSCruiseDirection/resolvePresetBallisticDirection 方向生成器拆分）
- 常量：`ywzj_vehicle`（只读）`PhysicsEngine.java:35` + `PhysicsHelper.java:6-7` → **G = 9.8/400 = 0.0245 格/Tick²**

**fishking 文档**（`docs/RVP弹体-fish/`）：`RVP制导转向气动化方案_最小JSON改动.md`（现行方案：§3.1 生效条件、§3.3 tf 语义变化、§6.3 虚拟边界、§8 回滚、§9.2 实测清单）；`RVP导弹攻角阶段1实现_20261007.md`；`RVP武器公开数据与攻角统计_20261008.md`（8~30° 建议表+来源溯源）；`RVP武器推进与燃料统计_20261008.md`；`RVP导弹速度相关数据统计_20261008.md`（84 弹达速/v_eq/二脉冲全表、§4 三个异常弹自证）。
