# Gunner RVP 武器与外置雷达适配方案

## 目标

让 Gunner 驾驶或操作 RVP 载具时，尽量走和玩家一致的火控、锁定、发射链路，而不是绕过 RVP 武器系统直接开火。

本方案暂不新增 Mixin。优先在 `GunnerBrain` 附近新增 server-side helper，复用现有 `WeaponUnit`、`RadarUnit`、`RVP_WeaponBase`、`WeaponUnitExternalRadarLockExt` 和可部署 UAV 链路。

## 已修复的前置问题

| 问题 | 当前处理 |
| --- | --- |
| 创造模式非 HARD 难度下敌对 Gunner 攻击玩家驾驶载具 | `GunnerTargeting` 现在会过滤受保护玩家自身，以及载有受保护玩家的 `AbstractVehicle`。 |
| 敌对 Gunner 不主动雷达锁定玩家飞机 | `GunnerBrain.tickRadarLock()` 会自动打开当前 RF weapon unit 的雷达，并使用可锁定 radar 写入 `RadarUnit.lockedEntity` 和 root `WeaponUnit.lockedEntity`。 |
| Gunner 驾驶载具打光弹药 | `GunnerBrain.sustainDriverInfiniteAmmo()` 在驾驶 AI 持续存在时补弹。 |
| 无限弹药绕过换弹/射速 | 补弹只在弹药归零后等待 reload/cooldown 计时；实际射速仍由 `weapon.isCoolingDown()` 和 `weapon.isReloading()` 钳制。 |

## 当前链路判断

### 玩家 RF 锁定链路

1. 客户端按锁定键或战术地图选中目标。
2. `RVP_ExternalRadarLinkHelper.applyClientLockRequest()` 写入 root `WeaponUnit` 的锁定实体或外置雷达请求。
3. `C2SRequestExternalRadarLock` 同步请求到服务端。
4. `RVP_ExternalRadarSyncService` 检查 relay radar 是否检测到目标。
5. 成功后写入 `WeaponUnitExternalRadarLockExt.externalRadarLockedEntityId`，并让 relay radar `setLockedEntity(target)`。
6. `RVP_WeaponBase.doClientShoot()` 和 `RVP_MissileEntity` 后续读取本机/外置雷达锁定状态。

### Gunner 当前 RF 锁定链路

1. `GunnerBrain.tickTargeting()` 先按 profile 从实体列表里选目标。
2. `GunnerBrain.tickRadarLock()` 只处理本车 RF weapon unit。
3. 当前实现会打开 weapon unit 直属 radar，并对 target 写入 `RadarUnit.lockedEntity` 和 root `WeaponUnit.lockedEntity`。
4. 还没有自动释放 relay radar，也没有使用外置雷达锁定状态。

## 需要新增的 AI 服务端链路

### 1. 通用 UAV 部署入口

当前 `RVP_DeployableUavService.deployLinkedUav(ServerPlayer player)` 只接受玩家。

建议新增：

| 方法 | 说明 |
| --- | --- |
| `deployLinkedUav(AbstractVehicle parent, @Nullable LivingEntity operator)` | server-side 通用部署入口，供玩家和 Gunner 共用。 |
| `deployLinkedUav(ServerPlayer player)` | 保留为 wrapper，内部调用通用入口。 |

通用入口需要处理：

| 项 | 处理 |
| --- | --- |
| parent 校验 | `parent != null` 且在 `ServerLevel`。 |
| 配置读取 | 仍使用 `RVP_DeployableUavConfigCache.get(parent.getVehicleId())`。 |
| 冷却 | 仍使用 `RVP_DeployableUavCooldownRegistry`。 |
| single instance | 复用 `getLinkedChild()` 和 `clearLinkedChild()`。 |
| spawn yaw | `operator_look` 有 operator 时用 operator yaw，否则退回 parent yaw。 |
| return seat | operator 是 `ServerPlayer` 时记录真实座位；Gunner 或 null 时使用 0。 |

### 2. Gunner 外置雷达运行时 helper

建议新增 `GunnerExternalRadarController`，由 `GunnerBrain.tick()` 调用。

职责：

| 步骤 | 行为 |
| --- | --- |
| 检测 launcher | 当前 Gunner 是 driver AI，且载具有 `deployable_uav_*` 配置。 |
| 自动部署 | 没有已链接 radar relay 时尝试部署；遇到 cooldown 或 invalid config 直接跳过。 |
| 打开雷达 | 对 relay vehicle 所有 `RadarUnit` 执行 `toggle(true)`。 |
| 刷新扫描 | 复用 `RVP_ExternalRadarSyncService` 中的扫描逻辑，最好抽出 server utility，避免复制 `scanTargets()`。 |
| 选择 lock radar | 使用 `RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relayVehicle)`。 |
| 写锁定 | 成功检测目标后写入 relay radar lock、root weapon unit lock、`WeaponUnitExternalRadarLockExt.externalRadarRequestedEntityId` 和 `externalRadarLockedEntityId`。 |
| 清锁 | 目标死亡、超距、relay 关闭或 relay 丢失时清理 root 和 ext。 |

关键点：不要依赖 `S2CExternalRadarSnapshot`。snapshot 是玩家 UI 同步结构，Gunner 应该走纯服务端实体状态。

### 3. 武器目标分类

建议新增 `GunnerWeaponSuitability`，在 `selectWeaponIndex()` 前过滤武器。

| 目标类型 | 允许武器 |
| --- | --- |
| 空中载具 | `IR`、`AIR`、`ARH`、`SARH`、可对空 cannon/rocket；同时必须满足该武器的锁定距离/高度/角度门限。 |
| 地面载具 | cannon、rocket、bomb、GPS、SALH/LH，以及锁定门限允许低空/地面目标的 `IR`、`AIR`、`ARH`、`SARH`。 |
| 坐标/标记点 | GPS、炮兵/火箭、部分 TV/HITL。 |
| 来袭弹药 | APS/机炮/近炸或专用反导，不应随便用普通对地弹。 |
| 辐射源 | ARM。 |

初期可用保守规则：

| 规则 | 说明 |
| --- | --- |
| GPS 不打空中实体 | `data.isGpsMissile()` 且目标是 `FixedWingVehicle`/`RotaryWingVehicle` 时禁用。 |
| 制导弹目标适配必须吃锁定门限 | `IR/AIR/ARH/SARH` 不能只按 guidance type 判定空地用途；还要检查 `guidance_data.lock_target_distance_range`、`lock_altitude_range`、`lock_angle_gate`、`max_lock_angle`、`max_off_axis_lock_angle`。例如 `lock_altitude_range = [[-inf,25]]` 表示允许锁定离地 25 米以下目标，应被视为对地/低空模式。 |
| 对地/对空模式由门限共同决定 | 空中目标需要落在锁定距离、高度、角度门限内；地面目标也一样。只有目标类型和锁定门限都不匹配时，才禁用该武器。 |
| 需要锁定的武器必须等锁定完成 | `data.isRequireLock()` 为 true 时，只有 root `lockedEntity` 或外置雷达 locked id 有效才允许射击。 |
| 操作者制导武器必须有 AI 控制源 | `LH/SALH/LBR/SACLOS/HITL_*` 只有在接入对应 Gunner control source 后才允许选择；当前已接入的类型仍必须通过距离/高度/角度 envelope。 |

### 3.1 锁定门限判定

`GunnerWeaponSuitability` 应新增 `canWeaponLockTarget(data, weaponUnit, target)`，作为制导武器选择和发射前锁定的共同前置条件。

| 门限 | 判定方式 |
| --- | --- |
| `lock_target_distance_range` | 目标到 seeker / weapon unit 的距离必须落在 range 内；未配置则使用制导类型默认锁定距离。 |
| `lock_altitude_range` | 目标离地高度必须落在 range 内；支持并集，`[[-inf,25]]` 这类写法代表低空/地面目标。 |
| `lock_angle_gate` | 如果配置了距离-角度门限，按当前距离取允许角度。 |
| `max_lock_angle` | seeker 扫描 FOV，为完整 FOV，运行时用半角参与中心扫描判定。 |
| `max_off_axis_lock_angle` | 相对发射/导引头轴线的单侧离轴角，不除以二。 |

分类逻辑应是：

1. 先判断武器是否有对应制导/火控能力。
2. 再判断目标类型是否大方向可接受。
3. 最后用锁定 range/angle 门限做精确过滤。

因此“对空导弹”和“对地导弹”不应写死在 `IR/AIR/ARH/SARH` 类型上，而应由 `guidance_type + lock_* range + angle gate` 共同决定。

### 4. 锁定流程与发射流程

Gunner 对制导武器应拆成三个阶段：

| 阶段 | 行为 |
| --- | --- |
| target acquisition | `GunnerTargeting` 选出候选目标。 |
| fire-control lock | 根据武器类型执行本机 radar、外置 radar、IR seeker、ARM preselect 等锁定流程。 |
| launch gate | 锁定满足 `fire_data.require_lock`、weapon reload/cooldown、角度窗口后才调用 `weaponUnit.shoot()`。 |

这样能保证 Gunner 不直接跳过玩家同款的 require lock、off-axis、reload、cooldown 和 seeker 约束。

## 实现阶段

| 阶段 | 内容 | 风险 |
| --- | --- | --- |
| A | 抽出 server radar scan utility，让玩家 external radar sync 和 Gunner 共用扫描逻辑。 | 需要保持 tactical map 和 external radar UI 行为不变。 |
| B | `RVP_DeployableUavService` 增加通用 deploy 方法。 | 注意玩家切换 UAV 的 return seat 不要受 Gunner 路径影响。 |
| C | `GunnerExternalRadarController` 接入自动部署、开机、扫描、锁定。 | 避免 relay radar 多个 launcher 抢锁；初期按单母车单 relay 处理。 |
| D | `GunnerWeaponSuitability` 接入武器目标分类。 | 可能导致旧 profile 下 Gunner 开火频率下降，需要日志调试。 |
| E | 为 IR/AIR/ARM/GPS/HITL 等补细化 AI 锁定策略。 | 手操/坐标类武器需要专门 AI control source，并且发射前要按即将发射的 weapon 再写一次 source，避免 current weapon 时序错位。 |

## 排雷点

| 风险点 | 排雷方式 |
| --- | --- |
| 外置雷达 UI snapshot 和 Gunner AI 状态混用 | Gunner 只写服务端 `WeaponUnit`、`RadarUnit`、`WeaponUnitExternalRadarLockExt`，不依赖 client snapshot。 |
| 自动部署 UAV 造成玩家冷却/实例状态错乱 | 共用 `RVP_DeployableUavService` 的 registry 和 cooldown，不另建影子状态。 |
| RF 雷达扫描周期导致锁定断续 | Gunner fire-control lock 可使用“已检测到或本 tick 主动检测”的状态，发射链仍检查 radar lock/ext lock。 |
| Gunner 无限弹药绕过换弹 | 不直接每 tick 填满；只在 0 弹进入 reload timer，到点补满。 |
| 对空/对地误用 | 在 weapon suitability 层集中判断，避免散落在 `tickCombat()` 里。 |
| 需要玩家输入的武器被 AI 乱用 | `GPS`、`LH/SALH`、`LBR`、`SACLOS`、`HITL_TV`、`HITL_CLOS_TV` 分别接入 Gunner control source 后再开放；选择阶段仍要通过距离/高度/角度 envelope。 |

## 当前代码落地状态

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 本机 RF 雷达锁定 | 已接入 | Gunner driver AI 会自动打开当前 RF weapon unit 的 radar，并把目标写入 `RadarUnit.lockedEntity` 与 root `WeaponUnit.lockedEntity`。 |
| 外置雷达车释放与锁定 | 已接入 | 无本机雷达的 RF 发射车会尝试通过 `RVP_DeployableUavService.deployLinkedUav(AbstractVehicle, LivingEntity)` 释放 relay radar，并写入 `WeaponUnitExternalRadarLockExt`。 |
| 锁定范围决定空地用途 | 已接入 | `IR/AIR/ARH/SARH` 不再只按 guidance type 判定用途，而是共同检查 `lock_target_distance_range`、`lock_altitude_range`、`lock_angle_gate`、`max_lock_angle`、`max_off_axis_lock_angle`。 |
| ARM 预选锁定 | 已接入 | Gunner 会复用 `AntiRadiationSeekerHelper` 扫描目标载具上的 radar emitter，并把最优 emitter 写入 `WeaponUnitArmExt`；`require_lock` 由这个 preselect 满足。 |
| 目标选择过滤 | 已接入 | `GunnerTargeting.findBestTarget()` 会先确认当前 weapon unit 至少有一把可用武器能打该目标，避免选中无法发射的目标。 |
| 操作者制导武器 | 已接入 | `GPS` 会给 Gunner UUID 写入 `GPSTargetManager`，`LH/SALH/HITL_TV` 会写入 Gunner 的 `RVP_SaclosOperatorSession` designation，`LBR/SACLOS` 复用 Gunner 对 weapon unit 的 aim direction，`HITL_CLOS_TV` 会给已发射 missile 写 steering input。 |

## Gunner control source 落地细节

| 制导类型 | Gunner source | 发射前处理 | 飞行中处理 |
| --- | --- | --- | --- |
| `GPS` | 目标实体中心点写入 Gunner UUID 下的 `GPSTargetManager`。 | `GunnerGuidedWeaponController.prepareForLaunch()` 在 `weaponUnit.shoot()` 前按即将发射的 weapon 冗余写入 GPS 点，保证 `RVP_ProjectileSpawner.consumeAssignedTarget()` 能取到。 | `RVP_RuntimeGpsGuidanceSource` 继续读取 projectile 的 `targetPos`，不需要实体锁。 |
| `LH` / `SALH` | Gunner UUID 下的 `RVP_SaclosOperatorSession` designation point。 | 发射前打开 designation 并写入目标中心点。 | `RVP_RuntimeLaserGuidanceSource` 通过 projectile owner 的 UUID 读取 designation point；owner 已放宽为 `LivingEntity`，因此 Gunner 可用。只要 Gunner 仍有目标且存在在途 laser/designate projectile，就会继续保持 designation，避免发射后被 current weapon 时序清掉。 |
| `LBR` | 当前 weapon unit aim direction。 | 不写实体锁，只要求 `GunnerBrain.tickCombat()` 已经 `weaponUnit.aim(target)`。 | `RVP_RuntimeLbrGuidanceSource` 通过 `RVP_CommandGuidanceAim.operatorAimDirection()` 取 beam direction。 |
| `SACLOS` | 当前 weapon unit aim direction。 | 不写实体锁，只要求 weapon unit 持续瞄准目标。 | `RVP_RuntimeSaclosGuidanceSource` 沿 weapon unit 瞄准方向生成控制点。 |
| `HITL_TV` | Gunner designation entity / designation point。 | 发射前写 designation point。 | `GunnerGuidedWeaponController` 扫描 Gunner 发射且 active 的 `RVP_MissileEntity`，在 `DESIGNATE` mode 下持续写目标实体。 |
| `HITL_CLOS_TV` | Gunner 对 missile 的 steering command。 | 发射前不需要实体锁。 | `GunnerGuidedWeaponController` 根据 missile 到目标的方向调用 `rvp$setHitlSteeringInput()`，`RVP_WireGuidanceSteering.resolveCommand()` 会读取该输入。 |

选择门控上，`GPS/LH/SALH/LBR/SACLOS/HITL_TV/HITL_CLOS_TV` 不再被整体禁用，而是走 `GunnerWeaponSuitability.canGuidanceReachTargetEnvelope()`：优先使用 `lock_target_distance_range`、`lock_altitude_range`、`lock_angle_gate`，缺省时回退到 `guidance_target_distance_range`、`guidance_altitude_range`、`guidance_angle_gate`。这样能保留“用高度/距离范围决定空地用途”的规则。
