# RVP 武器数据模型重构前行为基线

## 1. 基线信息

| 项目 | 基线值 |
| --- | --- |
| Git 分支 | `refactor/weapon-data-model` |
| Git commit | `2a921e8`（`存档：武器数据模型重构前基线`） |
| Java | 17 |
| 构建命令 | `.\gradlew.bat clean build` |
| 构建结果 | 通过 |
| 重构范围 | 仅 `ywzj_rvp` 武器级数据与对应运行逻辑 |
| 新增 Mixin 预算 | 0 |

重构前备份分支、Tag 与 worktree 均固定在 `2a921e8`：

- 分支：`backup/pre-weapon-data-refactor-20260718`
- Tag：`pre-weapon-data-refactor-20260718`
- worktree：`D:\ywzj\ywzj\ywzj_rvp_pre_refactor`

## 2. Mixin 基线

| 项目 | 数量 |
| --- | ---: |
| 含 `@Mixin` 的 Java 文件 | 76 |
| `mixins` 注册项 | 43 |
| `client` 注册项 | 31 |
| 总注册项 | 74 |

当前存在两个含 `@Mixin` 但未在 `ywzj_rvp.mixins.json` 注册的类：

- `VehicleRadarOverlayPositionMixin`
- `VehicleScopeOverlayMixin`

重构期间不得因数据模型迁移新增 Mixin。优先修改普通 data、resolver、helper、controller 与现有注入点；每个里程碑均重新统计上述数量。

## 3. 角度与范围语义

- `maxLockAngle` 表示完整 FOV。运行时参与锥角比较前必须除以 2。
- `maxGuidanceAngle` 表示相对弹体或导引头轴线的单侧角，不除以 2。
- `maxOffAxisLockAngle` 表示发射前锁定后的单侧离轴保持角，不除以 2。
- IR 搜索范围、发射前锁定保持范围、发射后制导范围是三个阶段，不得在迁移时混用。
- 原 `lockMinHeight` 的正负值同时承载目标高度过滤和 IR HUD 类型选择。迁移为范围并集后，HUD 必须由内部 `AIR`、`GROUND`、`MIXED`、`UNRESTRICTED` profile 推导，不能继续依赖单个数值正负号。

## 4. IR 与武器级 HMD

关键入口：

- `RVP_ClientHmdState`：客户端 IR HMD 状态、扫描、锁定、20 tick 暂存保持与 HUD 状态。
- `RVP_IrLockHelper`：发射前 acquire/hold 范围、距离、高度、遮挡和轴线判断。
- `RVP_GuidanceSeekerUtil`、`RVP_IrGuidanceSource`：发射后目标有效性、自主扫描和制导目标更新。
- `WeaponUnitGetSeekerFovMixin`、`WeaponUnitFireControlLockMixin`：与本体武器站发射前锁定链衔接。

必须保持的行为：

- IR HMD 是武器级能力，由当前武器配置控制，不得与载具级 Radar HMD 合并。
- 无 HMD 时仍存在机头轴线搜索圈；搜索按完整 FOV 的半角执行。
- acquire 使用搜索 FOV，锁定后的发射前 hold 使用离轴保持角，发射后使用制导角与制导距离。
- 发射前超出角度或短暂丢失时允许 20 tick 暂存锁定；超时后必须断锁。
- EO 传感器可启动 IR 发射前捕获，但不能绕过武器的距离、高度和角度限制。
- 雷达已有锁定只能作为候选目标来源，不能让 IR 绕过自身 `range`。

## 5. EO_CCIP 与 CCIP

关键入口：

- `WeaponUnitSensorOverrideMixin`：按当前武器在 EO 观瞄与 CCIP 非观瞄状态间切换传感器行为。
- `RVP_ClientEvents`：更新 RVP 炸弹 CCIP 落点。
- `WeaponUnitBombCcipMixin`：向本体武器站 CCIP 查询链提供 RVP 炸弹落点。
- `RVP_CcipUtil`：按 RVP `ProjectileData` 的重力、阻力、推进、速度上下限逐 tick 模拟落点。

必须保持的行为：

- `EO_CCIP` 在非观瞄状态显示并持续更新 CCIP，进入观瞄状态后按 EO 工作。
- CCIP 不能只在切换武器的一瞬间存在。
- CCIP 使用 RVP 弹体物理，不得退回本体炸弹的固定弹道参数。
- IR 炸弹的 HMD/锁定 HUD 与 CCIP 落点可以同时存在，二者不能互相覆盖状态。

## 6. 激光照射与当前 SACLOS 命名

关键入口：

- `RVP_ClientSaclosState`：载具级激光照射开关、手动优先级、当前武器自动启停和照射点同步。
- `C2SSaclosDesignation`、`RVP_SaclosOperatorSession`：客户端至服务端的照射状态与目标点。
- `RVP_SaclosGuidanceSource`：当前名为 `SACLOS` 的照射点跟踪实现。

当前行为必须原样迁移，但命名需要拆分：

- 当前非 HITL `SACLOS` 实际对应激光照射点制导，应迁移为 `LH`/`SALH` 对应语义。
- 当前 `MCLOS` 的非 HITL 视线指令实现更接近真正的 `SACLOS`。
- 当前 `MCLOS` 的 HITL 分支属于 `HITL_CLOS_TV`。
- 真正键盘操纵的 `MCLOS` 尚未实现。
- `LOSBR`、`TV`、`ATV` 尚无完整运行实现；迁移不能把预留 enum 误判为已实现功能。

激光开关保持载具级状态，但只在切换到激光制导武器时自动开启、切换到其他武器时自动关闭；玩家手动操作优先于自动状态。

## 7. HITL

关键入口：

- `RVP_MissileEntity`：HITL 生命周期、控制距离、超时、视频模式、鼠标转向、链路遮挡与断链。
- `RVP_HumanInTheLoopData`：控制距离、超时、视角偏移、每 tick 最大转角和信号源。
- `C2SHitlDesignate`、`S2CEnterHitlView`：目标重指定与视角进入同步。

必须保持的行为：

- `VIEW` 与 `MOUSE` 控制模式不能在迁移中混为同一种转向。
- `RADIO` 链路受遮挡并可断链，其他信号源保持各自语义。
- HITL 的 `hITLMaxTurnDegPerTick` 不得被通用 `maxGuidanceAngle` 代替。
- 主制导切换到末端制导时执行单向原子切换，不能让两个阶段并行争夺转向权。

## 8. ARH

关键入口：

- `RVP_ArhGuidanceSource`：主动导引头目标验证、开机后扫描与目标接管。
- `RVP_MissileEntity`：发射前指定目标保持、开机距离判断和 ARH 目标状态维护。
- `RVP_GuidanceSeekerUtil`：雷达目标扫描、FOV、距离和目标有效性。

必须保持的行为：

- 主动导引头开机前仍可接受发射平台雷达对最初目标的制导，不等于导引头提前自由扫描。
- 尚有有效雷达引导时，开机不得擅自改锁更近的其他目标。
- 取消锁定但目标仍在雷达扫描范围时，优先保持最初目标。
- 仅在雷达关机、载机被毁或初始目标离开有效雷达扫描范围后，才允许自由捕获最接近中心的目标。
- `activeRadarActivationRange` 控制主动导引头开机阶段，不得与发射前雷达照射或数据链范围混用。

## 9. ARM

关键入口：

- `RVP_ClientArmState`：发射前辐射源扫描、预选和目标循环。
- `RVP_ArmGuidanceSource`：发射后辐射源扫描、预选源优先和记忆点续飞。
- `RVP_MissileEntity`：当前仍保留一条绕过 stage 转向系统的 ARM 独立管理链。

迁移风险：

- ARM 当前同时存在 source 实现和 `RVP_MissileEntity` 特例链，是重构时最容易重复扫描或重复转向的区域。
- 在统一 `GuidanceData` 后，必须明确由单一控制器拥有目标管理和转向权，再删除旧特例；不能先删特例再假设 stage 已完全接管。
- 辐射源关闭后的记忆点行为、预选源优先级与扫描间隔必须保持。

## 10. GPS

关键入口：

- `RVP_ClientGPSState`、`RVP_ClientGPSUtil`：客户端单点/多点状态与操作。
- `C2SSetGPSTarget`、`S2CGpsStateSync`、`GPSTargetManager`：服务端目标存储与同步。
- `RVP_GpsGuidanceSource`：弹体按 `targetPos` 进行点目标制导。

必须保持的行为：

- 单点与多点 GPS 模式、当前待发点序号和维度信息不变。
- GPS 目标是点目标，不应被实体目标有效性检查清空。
- GPS 巡航、末端切换和散布参数迁移后仍由 GPS 专用数据提供。

## 11. AHEAD

关键入口：

- `RVP_AheadProgrammer`：锁定目标/参考点求解、引信距离计算与每发编程。
- `RVP_AirburstRangeStore`：向弹体传递本次射击的空爆距离。
- `RVP_AheadData`：当前独立数据模型。

迁移要求：

- AHEAD 逻辑整体迁入 `RVP_FuseData`，字段语义先保持不变。
- `requireLock` 属于 `RVP_FireData`；AHEAD 自身不得继续维护第二套含义重叠的开火门控。
- 编程结果是每发弹药状态，不能错误提升为整件武器共享的实时引信距离。

## 12. 里程碑验收

每次重大改动至少执行：

1. 运行相关单元测试。
2. 运行 `.\gradlew.bat clean build`。
3. 重新统计 Mixin 文件与注册项，确认仍为 76/74，且未新增注册。
4. 检查 `git diff --check` 和 `git status --short`。
5. 使用中文 commit message 提交。
6. 推送到 Gitee `refactor/weapon-data-model`，测试完成前不合并 `master`。
