# RVP 机枪预瞄圈开发计划

## 目标

- 仅对 `rvp:machinegun` 启用预瞄圈。
- HUD 形态为一个小的绿色圆圈。
- 预瞄圈与当前锁定目标之间用绿色虚线连接。
- 当当前武器站为 `rvp_rf` 或 `rvp_ballistic_lead` 且当前武器为 `rvp:machinegun` 时，火控辅助中心从目标中心切换为预瞄圈。
- 两种模式复用通用火控执行器；`rvp_rf` 保留历史软限位缩放，`rvp_ballistic_lead` 使用通用离轴角原值。

## 已确认真值

### 机枪弹发射链

- `RVP_ProjectileSpawner.spawn(...)` 用 `RVP_WeaponData.resolveMuzzleSpeed(MACHINEGUN)` 计算初速。
- 初始方向来自 `AimContext.direction`，并叠加散布。
- 弹丸生成位置优先使用 `AimContext.from`，即炮口位置。
- 如果武器配置了 `inherit_vehicle_velocity`，弹丸会额外叠加载具速度。

### 机枪弹运动链

- `RVP_BulletEntity.tickBulletMotionAndFacing()` 每 tick 执行一次运动积分。
- 当前运动学是：
  - 先按当前速度更新位置。
  - 再按 `setDeltaMovement(getDeltaMovement().scale(1 - friction))` 施加线性摩擦。
  - 最后按 `add(0, -gravity, 0)` 施加重力。
- 这里的 `friction` 来自 `RVP_WeaponData.getCannonFriction()`。
- 这里的 `gravity` 来自 `RVP_WeaponData.getCannonGravity()`。

### 预瞄圈第一版需要匹配的物理量

- 炮口位置：`RVP_AimContexts.muzzle(aim)`
- 初速：`RVP_WeaponData.resolveMuzzleSpeed(MACHINEGUN)`
- 重力：`RVP_WeaponData.getCannonGravity()`
- 阻力：`RVP_WeaponData.getCannonFriction()`
- 载具速度继承：`RVP_WeaponData.isInheritVehicleVelocity()`
- 锁定目标位置和速度：从当前 `WeaponUnit` / 主雷达锁定目标读取

## 推荐架构

### 武器数据层

- 保持由 `RVP_WeaponData` 提供弹道参数。
- 第一版不强制新增复杂 JSON 开关，先只按 `weaponKind == MACHINEGUN` 启用。
- 如果后面要细分，再补 `lead_circle_enabled` 之类的字段。

### 公共解算层

- 新增 `RVP_LeadSolver`
- 新增 `RVP_LeadSolution`
- 负责将炮口、目标、速度、重力、摩擦整合成预瞄点世界坐标。

### HUD 层

- 新增 `RVP_MachinegunLeadOverlay`
- 只负责：
  - 判断当前是否满足显示条件
  - 世界坐标投影到屏幕
  - 画绿色小圆圈
  - 画锁定目标到预瞄圈的绿色虚线

### 火控层

- 复用现有 `WeaponUnitSoftRfMixin`
- 在受支持火控模式与 `machinegun` 组合下，把软限位目标点从目标中心切到 `leadWorldPos`
- 其余武器继续保持当前 `rvp_rf` 逻辑

## 执行顺序

### 1. 运动层调研

- 核对 `RVP_BulletEntity` 真实积分顺序
- 核对 `RVP_ProjectileSpawner` 的初速和载具速度继承
- 核对目标速度应取值方式
- 核对机枪预瞄是否必须考虑阻力

### 2. 设计解算接口

- 定义 `RVP_LeadSolution`
- 定义 `RVP_LeadSolver.solveMachinegunLead(...)`
- 约定输入输出字段

### 3. 实现预瞄解算

- 第一版做客户端本地解算
- 优先保证与真实弹道趋势一致
- 如果解析解不足，再使用数值迭代

### 4. 实现 HUD

- 新建独立 overlay
- 仅在 `rvp:machinegun` 且当前有锁定目标时显示
- 圆圈为小绿色圆圈
- 目标点到预瞄点之间绘制绿色虚线

### 5. 接入火控

- 在 `rvp_rf` 或 `rvp_ballistic_lead` 与 `machinegun` 组合下将火控目标点切到预瞄点
- 不改变其他传感器类型
- 不改变非机枪武器行为

### 6. 验证

- 静止目标
- 横向匀速目标
- 己方高速运动
- 双方同时运动
- HUD 位置与实际落点的一致性
- `rvp_rf` 火控手感是否仍可接受

## 当前执行状态

- [x] 写计划文档
- [x] 运动层调研
- [x] 解算接口设计
- [x] 预瞄解算实现
- [x] HUD 实现
- [x] 火控接入
- [x] 构建验证

## 2026-09-23 MI-28 实测问题与方案二实施记录

### 实测问题

使用 RVP MI-28 验证自动提前量时发现：

1. 静止目标的提前量圈会向目标左侧或右侧偏移；
2. 低速目标的提前量过大，且绿色虚线的目标端没有连接到锁定方框；
3. 按 `6` 切换到 `火控模式：稳定` 后移动鼠标，会引发明显客户端卡顿。

### 根因

#### 静止与低速目标偏移

旧解算在目标速度较低时使用目标朝向，并沿该方向固定前推 `8.5` 格。该补偿不是由弹丸飞行时间或目标实测速度推导，因此静止目标也会产生水平提前量；目标朝向变化时，偏移会表现为时而向左、时而向右。

旧实现还会对长飞行时间额外增加最多 `3.5 tick` 的目标运动时间。低速目标同时受到固定距离补偿和额外时间补偿，导致预瞄量明显过大。

#### 虚线与锁定框脱离

锁定方框使用当前渲染帧插值后的目标包围盒中心，而旧提前量状态把带方向补偿且经过独立平滑的目标点作为虚线锚点。两者不是同一空间点，也不使用同一插值时刻，因此虚线目标端无法稳定贴合锁定框。

#### 稳定模式移动鼠标卡顿

完整提前量解算会：

- 以 `0.5 tick` 步长扫描候选命中时间，最长扫描 `120 tick`；
- 每个候选时间最多执行 6 次炮口方向修正；
- 每次修正都按真实重力和阻力逐 tick 积分弹丸轨迹。

旧稳定模式的鼠标 X、Y 转向回调会分别触发完整解算，火控执行器和 HUD 随后还会各自解算。解算次数因鼠标采样率和渲染帧率放大，形成客户端主线程卡顿。

### 方案二实际落地

#### 1. 目标预测只使用真实运动量

目标基准位置统一为当前渲染时刻的包围盒中心：

```text
C(α) = lerp(previousPosition, currentPosition, α) + boundingBoxCenterOffset
```

目标速度同时参考相邻 tick 位置差分与实体运动速度：

```text
v_target = 0.35 × (positionNow - positionPrevious)
         + 0.65 × entityDeltaMovement
```

当 `|v_target| < 0.01 格/tick` 时按静止目标处理，以过滤载具物理和网络插值产生的近零抖动。候选命中时刻 `t` 的目标位置改为：

```text
P_target(t) = C(α) + v_target × t
```

目标朝向不再参与计算；已删除固定 `8.5` 格前推和远距离额外时间补偿。

#### 2. 弹丸预测继续匹配真实积分顺序

候选炮口方向为 `d` 时，初始速度为：

```text
v_0 = normalize(d) × muzzleSpeed + inheritedVehicleVelocity
```

每个完整 tick 按真实弹体顺序推进：

```text
p_(n+1) = p_n + v_n
v_(n+1) = (1 - friction) × v_n + (0, -gravity, 0)
```

解算器在 `1..min(projectileLife, 120)` tick 内以 `0.5 tick` 为步长搜索，并对每个候选时刻最多修正 6 次炮口方向，最终选取预测脱靶距离最小的解。

#### 3. 锁定框和虚线共用目标锚点

目标锚点只保存真实包围盒中心，不再做滞后平滑或方向前推。HUD 渲染时使用与锁定框相同的 `partialTick` 在上一 tick 与当前 tick 的真实中心之间插值。

预瞄圈本身仍保留自适应平滑和 `0.02` 的轻微前馈，避免圆圈跳动；该平滑不会再影响虚线连接目标的位置。

#### 4. 每武器站每 tick 只完整解算一次

`RVP_MachinegunLeadState.resolveCurrent(...)` 以“载具实体 ID + 武器站索引”为键缓存本 tick 原始解：

- 火控执行器、HUD 和客户端 AHEAD 显示共用同一份结果；
- 鼠标 X/Y 转向回调只检查当前武器类型和是否存在有效锁定，不再运行弹道积分；
- 原始解短暂缺失时最多保持 8 tick，超过后清空平滑状态；
- 切换目标、切换武器或重新获得目标时重新初始化，避免继承旧目标的提前量。

该改动不新增 Mixin 类或注入点，只收窄既有 `LocalVehiclePlayerMachinegunLeadTurnMixin` 内的回调工作量。

### 代码落点

| 职责 | 文件 |
| --- | --- |
| 目标速度过滤、目标预测和数值弹道解算 | `client/lead/RVP_MachinegunLeadSolver.java` |
| 每武器站每 tick 缓存、预瞄圈平滑、目标锚点插值 | `client/state/RVP_MachinegunLeadState.java` |
| HUD 共用缓存与虚线端点对齐 | `client/gui/RVP_MachinegunLeadOverlay.java` |
| 通用弹道提前量火控消费缓存 | `client/firecontrol/RVP_BallisticLeadFireControlExecutor.java` |
| 稳定模式鼠标回调改为廉价锁定检查 | `mixin/LocalVehiclePlayerMachinegunLeadTurnMixin.java` |
| AHEAD 客户端读数复用已解算结果 | `weapon/ahead/RVP_AheadProgrammer.java` |
| 静止、微抖、低速与速度融合自动化测试 | `src/test/java/org/ywzj/rvp/client/lead/RVP_MachinegunLeadSolverTest.java` |

以上路径的 Java 主源码均位于 `src/main/java/org/ywzj/rvp/`；测试文件路径按表中完整路径解析。本次没有修改 `ywzj_vehicle` 本体、载具包资源或 JSON schema。

### 自动验证结果

- 定向测试通过：`RVP_MachinegunLeadSolverTest`、`RVP_BallisticLeadFireControlPolicyTest`；
- 项目根目录执行规定的 `./gradlew build`：`BUILD SUCCESSFUL`；
- `./gradlew runServer` 服务端冒烟：日志出现 `Done (2.891s)!`；
- 冒烟日志中的缺失模型、Create/TACZ 类、AbramsX 非法路径、`rvp_bomber:ac130u` 配方与 `rvp_bomber:tu160` 数据错误均与既有噪音基线一致，未发现本次新增类加载、Mixin 或服务端错误；
- 测试服务端已正常清理，端口 `25565` 无残留监听。

### 客户端实机回归清单

- [ ] MI-28 锁定静止目标：提前量圈不再因目标朝向偏左或偏右；
- [ ] MI-28 锁定低速横移目标：提前量与速度、距离连续变化，不再出现固定大幅前推；
- [ ] 虚线目标端在不同帧率及目标运动状态下持续贴合锁定方框；
- [ ] 按 `6` 切到 `火控模式：稳定` 后持续移动鼠标，不再出现此前的主线程卡顿；
- [ ] 高速横移、己方高速运动和双方同时运动时，预瞄圈与实际弹着趋势一致；
- [ ] AHEAD 客户端距离/时间读数正常，切换目标后不显示上一目标的旧解。

## 2026-09-25 半自动相对预瞄微调

### 行为定义

- `STABLE`：继续严格跟随理论预瞄方向，瞄具内鼠标输入不改变炮口目标角。
- `SEMI_AUTO`：炮口每 Tick 跟随理论预瞄方向，并叠加玩家锁存的武器站局部俯仰/方位角偏置；
  玩家把准星移动到预瞄点略前、略后、略上或略下后即可松开鼠标，无需继续追随移动目标。
- `OFF`：继续完全手动，不由弹道提前量火控驱动。

微调偏置使用武器站局部角而非世界坐标点，因此载具转向、俯仰和横滚后仍保持相对预瞄点的
屏幕方向关系。双轴偏置按圆形包线限制，最大夹角复用当前火控模式已有的离轴角配置；不新增
JSON 字段。切换目标、当前武器或火控模式会清零偏置，同一目标不超过 8 Tick 的短暂丢解保留
偏置以抵抗网络抖动。

### 代码落点

| 职责 | 文件 |
| --- | --- |
| 微调身份、输入累积、局部角换算与离轴钳制 | `client/state/RVP_SemiAutoLeadTrimState.java` |
| 复用现有 X/Y 重定向记录真实鼠标增量，并过滤进入瞄具时的自动对齐 | `mixin/LocalVehiclePlayerMachinegunLeadTurnMixin.java` |
| 半自动每 Tick 应用“理论预瞄方向＋锁存偏置” | `client/firecontrol/RVP_BallisticLeadFireControlExecutor.java` |
| 模式切换时清除旧偏置 | `client/state/RVP_FireControlStabilizerState.java` |
| 局部角跟随与圆形离轴包线自动化测试 | `src/test/java/org/ywzj/rvp/client/state/RVP_SemiAutoLeadTrimStateTest.java` |

本次复用已有 Mixin 类及注入点，不新增 Mixin，不修改 `ywzj_vehicle` 本体、载具包资源、JSON
schema 或网络协议。

### 自动验证结果

- 定向测试：`RVP_SemiAutoLeadTrimStateTest` 与 `RVP_BallisticLeadFireControlPolicyTest` 通过；
- 项目根目录执行规定的 `./gradlew build`：`BUILD SUCCESSFUL`；
- `./gradlew runServer` 服务端冒烟：日志出现 `Done (2.276s)!`；
- 日志仅出现既有 Create/TACZ 缺类、配方与 `rvp_bomber:tu160` 数据噪音，未发现本次新增
  类加载、Mixin 或服务端错误；结束后 `BootstrapLauncher` 无残留，端口 `25565` 已释放。

### 客户端实机回归清单

- [ ] MI-28 / AH-64 半自动模式：向理论预瞄圈前、后、上、下微调后松开鼠标，偏置持续跟随目标；
- [ ] 目标加减速、转弯以及本机转向/横滚时，微调方向不固定在世界轴上且不反转；
- [ ] 双轴微调达到离轴边界时平滑饱和，反向移动鼠标能立即退出边界；
- [ ] 切换目标、武器或火控模式后旧偏置清零；短暂丢解恢复后同目标偏置不跳变；
- [ ] `STABLE` 仍严格跟预瞄圈且屏蔽鼠标，`OFF` 仍完全手动；
- [ ] 非机炮、非导弹的 `rvp_rf` 软修正手感不变。

## 2026-09-25 `rvp_rf` 导弹完整三态与硬锁跟踪

### 行为定义

- 所有当前有效传感器为RF、且根武器站配置 `rvp_fire_control_mode: rvp_rf`
  的RVP导弹获得 `STABLE / SEMI_AUTO / OFF` 完整三态，不新增JSON字段。
- `STABLE`：视线严格跟随本车或外置雷达硬锁目标中心，屏蔽瞄具鼠标改角。
- `SEMI_AUTO`：以硬锁目标方向为移动基准，叠加玩家锁存的武器站局部俯仰/方位
  偏置；双轴圆形包线复用 `rvp_rf_off_axis_deg` 原值。
- `OFF`：不由RVP火控驱动视线，恢复玩家完全手动控制。
- 仅本车 `RadarUnit` 硬锁与外置雷达正式锁定有效；仅存在于
  `WeaponUnit.lockedEntity` 的TWS/导引头软航迹不会启动自动视线。
- LBR与非稳定模式SACLOS仍只消费操作手视线。SACLOS在 `STABLE` 下若当前阶段配置
  `predict_target_pos: true`，则由服务端复核正式雷达硬锁后生成当Tick临时实体意图，复用
  现有PIP预测拦截；不向弹体持久 `targetEntity` 注入雷达目标。

### 代码落点

| 职责 | 文件 |
| --- | --- |
| `rvp_rf` RVP导弹资格与完整三态策略 | `client/firecontrol/RVP_BallisticLeadFireControlPolicy.java`、`client/state/RVP_FireControlStabilizerState.java` |
| 本车/外置雷达硬锁目标解析 | `client/firecontrol/RVP_RadarMissileTrackHelper.java` |
| 基准类型隔离、双轴输入锁存与局部角应用 | `client/state/RVP_SemiAutoLeadTrimState.java` |
| 三态执行与瞄准视线转动 | `client/firecontrol/RVP_BallisticLeadFireControlExecutor.java` |
| 复用现有鼠标 X/Y 重定向记录导弹微调/在稳定态屏蔽输入 | `mixin/LocalVehiclePlayerMachinegunLeadTurnMixin.java` |
| STABLE请求同步、服务端新鲜度与硬锁复核 | `network/C2SSaclosDesignation.java`、`guidance/saclos/RVP_SaclosOperatorSession.java`、`guidance/saclos/RVP_SACLOSStablePIPAssist.java` |
| SACLOS临时实体意图与现有PIP转向复用 | `guidance/runtime/RVP_RuntimeSaclosGuidanceSource.java`、`guidance/RVP_GuidanceRuntimeMath.java` |

本次仅扩展现有 Mixin 转发后的业务逻辑，不新增 Mixin 类或注入点，不修改
`ywzj_vehicle` 本体或载具包JSON。SACLOS STABLE PIP同步使网络协议由15升至16。

定向资格测试与完整 `./gradlew build` 已通过；开发服务端冒烟达到
`Done (2.321s)!`，对照历史基线无新增错误，服务端进程与25565端口已清理。

### 实机回归清单

- [ ] PS1SM 选择 TKB-1055 / 95Ya6M：雷达硬锁后 `SEMI_AUTO` 视线跟随目标，微调后松鼠标仍保持偏置。
- [ ] PS1SM 在途 SACLOS 导弹：`STABLE` 下导弹走PIP预测拦截；切到 `SEMI_AUTO/OFF`、丢失硬锁或切换武器后立即回到原视线制导。
- [ ] `STABLE` 下PIP只影响TKB-1055/95Ya6M等当前阶段为SACLOS且配置 `predict_target_pos: true` 的导弹；LBR、SALH与ARH路径不变。
- [ ] PS1SM Hermes 1A：观瞄/发射架跟随硬锁目标，ARH导引目标链不变。
- [ ] 仅有TWS软航迹时三态不驱动视线；建立硬锁后才开始跟踪。
- [ ] 切换目标、导弹/机炮或火控模式后不继承上一份双轴偏置。
- [ ] Buk-M3、CSSA-5、IRIS-T SLM TEL 在 `rvp_rf` 导弹下也具有相同三态，其他非导弹武器保留旧软修正。
