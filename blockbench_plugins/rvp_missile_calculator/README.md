# RVP 导弹计算器（单文件 HTML 小软件）

仿 StatShark 导弹计算器（statshark.net/missilecalculator）的本地版：读取工作目录下全部
`rvp:missile` 武器 JSON，按 **RVP 实体链动力学逐 Tick 演算**，可视化弹道与交战遥测。
双击 `rvp_missile_calculator.html` 即可运行（Edge/Chrome，零依赖，离线可用）。

## 使用

1. 点 **选择工作目录**，选载具包的 `rvp/data/rvp/weapons` 目录（同步包或权威包均可，递归扫描，自动跳过 `_backup/`）。
   - Edge/Chrome 走 `showDirectoryPicker`；其它浏览器自动回退为目录多选上传模式。
2. 顶部下拉框选导弹（支持搜索过滤，显示中文名与制导类型）。
3. 左侧调发射参数与目标参数，任意修改即时重算。
4. 顶部时间轴播放/暂停/单步/拖动；**悬停图表**查看逐 Tick 遥测（速度 km/h + 马赫、加速度、G、攻角、到目标距离等）；侧视图（前向×高度）与顶视图（前向×横向）切换。

### 参数约定

- **载机速度**：仅 `inherit_vehicle_velocity=true` 的导弹生效（其它弹输入框自动置灰）。
- **发射角度**：仰角为正；**发射偏航角**：0 = +X 前向。
- **目标方位角**：相对发射航向；**目标航向**：0 = 迎头直奔发射点，180 = 同向逃离。目标匀速直飞。
- **命中判定**：弹目距离 ≤ max(collision_box_size, fuse_data.proximity_fuse_height)。
- **地面高度**可配置（默认 -64=MC 世界底），坠地判定为弹体 Y ≤ 地面高度；发射高度可为负（-64~0 区间按阻力表 2.0× 高密度段正常飞行）。主世界海平面地形可把地面填 63。
- 单位换算：1 格/Tick = 20 m/s = 72 km/h；马赫按 343 m/s；G = 0.0245 格/Tick²（PhysicsEngine.G）。

## 物理内核（与实体链同序：制导 → 推力 → 阻力 → 重力 → 速度钳制 → 诱导阻力）

| 环节 | 移植依据（本仓库源码） |
| --- | --- |
| 初速回退链 + 继承载机速度 | `RVP_ProjectileData.velocity` → 武器顶层 `velocity` → 10；`inherit_vehicle_velocity` |
| 点火门 | `ignition_delay_tick`（`getResolvedIgnitionDelayTick`） |
| 攻角模型（机头/速度分离、攻角锥、λ=sinα/sinα_limit、availableGs×λ 法向转角、转率/锥角双钳制） | `RVP_AttackAngleModel.solve`；机头响应 = rvp_maxg 显式时 0.5，否则 tf（缺省 0.5）；tf≥1 瞬转豁免 |
| 可用过载 = 设计 G × clamp(密度×(v/v_ref)², 0.05, 1)；tf 折算等效 G | `RVP_AeroSteeringModel.availableGs / equivalentGsFromTurningFactor` |
| v_ref 回退链：rvp_ref_speed(0=关) → max_speed → 武器初速 → 3.0 | `RVP_ProjectileData.resolveAeroReferenceSpeed` |
| 旧转向分支（未启用攻角）：速度方向按 tf 插值 | `RVP_TrajectorySteeringMath.applyTurningFactor` 口径 |
| 变质量推进：mass(t)=干+燃料×(1−t/burn)，a=thrust/mass 沿机头 | `RVP_PropulsionMath.resolveMotorState/applyThrust`、`RVP_ProjectileData.resolveMassAt` |
| 二脉冲：主燃尽后 v≤trigger_speed 或 目标距离≤trigger_distance 触发；推力用主燃尽干质量 | `RVP_ProjectileMotion.shouldStartSecondPulse`（16 弹配置，7 弹距离触发照搬） |
| 速度平方阻力：Δv=cd·v²/dragMass×高度因子，dragMass=mass<1?×1000:mass | `RVP_QuadraticAirDrag` |
| 高度阻力/密度因子：JSON 显式 `altitude_drag_factor` 区间表优先，缺省内置默认大气表 | `RVP_ProjectileData.resolveAltitudeDragFactor` + 当前默认表（-64/2.0、64/1.5、320/0.5、550/0.25、1000/0.014） |
| 重力：projectile_data.gravity（矢量施加） | `RVP_ProjectileMotion.applyPropulsionGravity` |
| 速度钳制 min/max（0=不限） | `RVP_ProjectileMotion.clampSpeed` |
| 诱导阻力：inducedDrag(缺省=cd)×λ²×v，钳制后一次、低速下限保护 | `RVP_ProjectileMotion.applyInducedDrag`、`RVP_AeroSteeringModel.inducedDragLoss` |
| **PRESET 弹道制导**（YJ-20/9M723 类）：抛物线中段（顶点=min(巡航高, 0.5×射程)、4p(1-p) 采样、前视进度）+ 中段正弦蛇形 + 俯冲判据（动压转弯半径×dive_lead/高度因子/dive_radius）+ 终端锁定下压 | `RVP_GuidanceRuntimeMath.shouldBeginPresetDive / steerPresetTerminal / steerPresetBallisticArc`、`RVP_BallisticTrajectoryMath.resolveTurnRadius / samplePresetArcHeight / resolvePresetLookAheadProgress` |
| **Loft 高抛**（rgm84/yj19/agm84/yj83/yj15/agm179-ir/kh39t 等 8 弹）：瞄准点=目标实时位置+(0, loft_height×smoothstep, 0)，水平距离 [end, end+blend] 退坡归零；ARH 主动导引头进入 active_radar_activation_range 后闩锁转直追；引导起始 `guidance_tick_range` 生效 | `RVP_GuidanceRuntimeMath` loft 塑形段、`RVP_RuntimeActiveSeekerGuidance`（截获为距离闩锁近似） |

## 验证记录（2026-10-09）

直飞基准与 `docs/RVP弹体-fish/RVP导弹速度相关数据统计_20261008.md` §3 对账（该表为同口径直飞积分）：

| 弹 | 文档达满速 | 计算器内核 | 结论 |
| --- | --- | --- | --- |
| aim_120 | 20 次更新 | 第 20 次更新 | ✓ |
| ddg51_rgm109 | 100 次更新（无重力口径） | 第 100 次更新 | ✓（文档达速口径不含重力；本工具按矢量施加重力，更接近游戏） |
| f14d_aim54c | 67 次更新 | 第 67 次更新 | ✓ |

默认表取样（y=-64/0/64/192/320/550/1000 → 2.0/1.75/1.5/1.0/0.5/0.25/0.014）与 tf=0.12@v_ref=3.5 折算 19.35 G（7.77°/Tick）均与源码/会话分析一致。

## 已知简化

- 常规制导为**纯追踪**；**PRESET 弹道已移植**（配置了 `preset_cruise_altitude` 的弹，当前包内即 YJ-20/9M723）：按 GPS 固定点语义**瞄准目标初始位置**（目标移动时不追，弹道导弹打固定坐标的默认语义），命中/脱靶仍按移动目标测量。顶攻 `top_attack_height` 未实现——当前载具包仅 ATACMS 配了该键且值为 null（游戏内同为直追），故无实际差异。
- Loft 截获用**距离闩锁近似**（进入 active_radar_activation_range 即视为截获，含 IR 弹无该键时全程 loft）；不含 ECM/诱饵/FOV/扫描间隔等截获细节。
- PIP 预测拦截、SACLOS/HITL/干扰偏转、loft 三件套不在范围。
- 冷发射窗口不建模（点火=max(ignition_delay_tick,0)）；推力按平推力（当前 84 弹均为平推力）。
- 机头轴用全精度向量，不复刻游戏 xRot/yRot 量化；智能引信接管、区块加载等不在范围。
- 二脉冲类型白名单（IR/ARH/SARH/ARM/GPS）未校验，配置了 `second_pulse:true` 即生效（当前 16 弹均在白名单内）。

## 文件

- `rvp_missile_calculator.html` — 全部逻辑内联的单文件应用。
- 不进构建、不改任何 Java/JSON；工作目录里被读取的 JSON 是只读的。
