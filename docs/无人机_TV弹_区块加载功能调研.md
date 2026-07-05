# 无人机、TV 弹与区块加载功能调研

> 调研日期：2026-07-05  
> 范围：`ywzj_rvp` 当前代码实现、开发载具包示例、既有调研文档。本文偏“当前现状记录”，不是新方案草稿。

## 总结

当前三块功能都已经有不同程度的落地：

| 功能 | 当前状态 | 主要入口 |
| --- | --- | --- |
| 可部署无人机 / 分体式雷达车 | 已有服务端部署、母车/子车 UUID 绑定、切换控制、实例级 UAV 行为、外部雷达快照同步 | `RVP_DeployableUavService`、`AbstractVehicleLinkedUavMixin`、`RVP_ExternalRadarSyncService` |
| TV 弹 / HITL | 已通过 `rvp:missile` + `guidance_data.human_in_the_loop` 实现；支持 `VIEW`、`MOUSE`、`DESIGNATE` 控制模式和 COLOR/BW/THERMAL 三档画面 | `RVP_MissileEntity`、`RVP_ClientHitlState`、`CameraTVMissileMixin` |
| 区块加载器 | 使用本体 `EntityUtil.keepChunkLoaded()` + `TicketType.POST_TELEPORT` 临时票；RVP 在弹药、可部署 UAV、Gunner 驾驶载具三处接入 | `RVP_BaseBullet`、`RVP_BulletEntity`、`AbstractVehicleLinkedUavMixin`、`AbstractVehicleGunnerChunkLoadMixin` |

需要特别注意两点：

- `TV` 不是独立制导源类型。当前权威 schema 明确要求：弹载电视 / HITL 使用 `human_in_the_loop` + `MCLOS` 或 `SACLOS`，无独立 `TV` guidance source。
- 旧文档里“RVP 全弹体默认启用区块加载”的说法已经不完全准确。当前 `RVP_BaseBullet` 默认开启，但 `RVP_BulletEntity` 在 `initFromWeapon()` 里显式设为 `false`。

## 一、本体 ywzj_vehicle UAV 实现

### 1.1 数据入口

本体 UAV 是普通载具数据上的一个布尔字段，不是独立实体类型：

```java
// BaseVehicleDataPojo
@SerializedName("uav")
public boolean uav = false;
```

加载链路：

```text
载具 JSON: "uav": true
  -> BaseVehicleDataPojo.uav
  -> BaseVehicleData.build() 写入 BaseVehicleData.uav
  -> AbstractVehicle.initData() 调用 vehicleData.isUav()
  -> AbstractVehicle.uav = true
```

默认包里的 `quadcopter.json` 就是本体 UAV 示例：

- 类型是 `ywzj_vehicle:rotary_wing_vehicle`。
- 写有 `"uav": true`。
- 默认只有一个 `sighting_system` 武器/观瞄位，没有武器。

因此本体无人机本质上是“普通载具 + `uav` 标志 + 特殊交互/回位/区块加载逻辑”。

### 1.2 进入控制：玩家真身骑到 UAV

本体 UAV 不是远程输入代理。玩家使用 `UavControllerItem` 后，如果服务端能通过 UUID 找到目标 UAV：

```java
if (entity instanceof AbstractVehicle vehicle && vehicle.uav && !vehicle.isDestroyed()) {
    player.startRiding(vehicle);
}
```

也就是说玩家真实实体会直接骑乘 UAV，后续飞行、视角、武器站操作都继续走普通载具乘员链路。

UAV 本体直接交互被阻止：

```java
// AbstractVehicle.interact()
if (uav) {
    return InteractionResult.PASS;
}
```

所以常规玩法入口是用 `UavControllerItem` 绑定和重新接管，而不是直接右键上车。

### 1.3 遥控器绑定与重连

`UavControllerItem` 的 NBT 字段：

- `uavUUID`
- `uavX`
- `uavY`
- `uavZ`

绑定流程：

```text
主手用 UAV Controller 交互 UAV
  -> interactEntity()
  -> 要求 target 是 AbstractVehicle 且 vehicle.uav 且未摧毁
  -> 写入 uavUUID
```

位置记录：

```text
inventoryTick()
  -> 每 10 tick
  -> 如果 UUID 对应实体当前已加载
  -> 更新 uavX/Y/Z 为 UAV 当前坐标
```

重连流程：

```text
右键使用 UAV Controller
  -> 如果 UUID 实体已加载：player.startRiding(vehicle)
  -> 否则如果有上次坐标：给上次坐标所在 chunk 加 POST_TELEPORT ticket
  -> 提示“尝试重连中...”
```

这点很关键：遥控器重连不是直接传送或全局找实体，而是先用最后记录位置把 UAV 附近区块拉起来。等区块加载、实体从存档恢复后，再次使用遥控器就能按 UUID 接管。

### 1.4 FakePlayer 占位与回位

玩家进入 UAV 时，`AbstractVehicle.onEnterVehicle()` 会在服务端做一套“原地占位”：

```text
如果 vehicle.uav 且乘员是 ServerPlayer 且 tickCount != 0
  -> fakeOperatorPosition = 玩家当前位置
  -> 创建 FakePlayer
  -> 复制玩家名字、手持物、护甲、朝向
  -> FakePlayer 放到原位置
  -> 真实玩家 teleport 到 UAV 位置
```

`FakePlayer` 的职责：

- 在原地显示一个玩家替身。
- 作为 UAV 退出/销毁时的回位锚点。
- 如果真实玩家不再骑乘 UAV，就自动 `discard()`。
- `shouldBeSaved() == true`，`removeWhenFarAway() == false`，避免普通远离清理。
- 如果 FakePlayer 被伤害，会让真实玩家脱离 UAV、传回 FakePlayer 位置，并把伤害转给真实玩家。

退出 UAV 时，`getDismountLocationForPassenger()` 优先返回 FakePlayer 位置，并恢复玩家 yaw/body yaw/pitch；如果 FakePlayer 不在但 `fakeOperatorPosition` 还在，则用记录坐标兜底。

UAV 实体从世界移除时，`onRemovedFromWorld()` 也会尝试：

```text
如果 driver 是 ServerPlayer 且 fakeOperator != null
  -> onLeaveVehicle(serverPlayer)
  -> serverPlayer.unRide()
  -> teleport 到 fakeOperator.position()
  -> 恢复朝向
  -> 清空 fakeOperatorPosition
```

### 1.5 玩家视距外加载机制

本体真正让 UAV 能飞出玩家视距后继续 tick 的代码在 `AbstractVehicle.tick()` 服务端分支：

```java
if (uav) {
    EntityUtil.keepChunkLoaded(this, position());
    EntityUtil.keepChunkLoaded(this, position().add(getLookAngle().normalize().scale(16)));
    if (fakeOperatorPosition != null) {
        EntityUtil.keepChunkLoaded(this, fakeOperatorPosition);
    }
}
```

`EntityUtil.keepChunkLoaded()` 的实现是：

```java
ChunkPos chunkpos = new ChunkPos(BlockPos.containing(position));
((ServerLevel) entity.level())
    .getChunkSource()
    .addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 3, entity.getId());
```

实际效果：

- UAV 当前区块被临时加载。
- UAV 前方 16 格对应区块被提前加载，降低高速移动撞入未加载区块的概率。
- FakePlayer / 原操作者位置也被加载，保证玩家退出或 UAV 销毁时有回位锚点。

这套机制的前提是 UAV 实体正在 tick。只要 UAV 初始还在玩家加载范围里，或已经被遥控器重连票拉起来，它就会每 tick 继续刷新区块票，从而在玩家视距外保持运行。

### 1.6 POST_TELEPORT 临时票的语义

本体没有使用 Forge `ForcedChunkManager` 的持久区块加载，而是使用 vanilla `TicketType.POST_TELEPORT`：

- 票是临时的，会自动过期。
- 每 tick 刷新才能持续加载。
- UAV 销毁、卸载或停止 tick 后，不再刷新，区块会自然卸载。
- ticket id 对 UAV 自身加载使用 `entity.getId()`，遥控器重连位置加载使用 `player.getId()`。

所以“玩家视距外加载”不是无限期永久加载，而是运行时保活。它适合移动实体，因为不需要维护 force/unforce 生命周期，也避免静态持久票残留。

### 1.7 客户端表现细节

本体客户端还有两处 UAV 特判：

- `RenderLivingHandler`：如果实体骑乘的是 `vehicle.uav`，取消渲染该乘员。效果是玩家真身不显示在无人机模型上。
- `LocalVehiclePlayer.tickOverload()`：如果当前载具是 UAV，则跳过过载/G 力黑视逻辑。

这说明本体把 UAV 当作“玩家控制但不显示玩家身体、且不承受飞行员过载”的特殊载具。

### 1.8 与 RVP Deployable UAV 的关系

RVP 的 `AbstractVehicleLinkedUavMixin` 基本是在复刻本体 UAV 的关键运行时能力，但加了“实例级 UAV”条件：

```text
deployableUavInstance && !uav
```

也就是说：

- 本体 `uav=true` 是车型级属性。
- RVP deployable UAV 是实例级属性：普通车型模板被某台母车部署出来时，临时获得类似 UAV 的 fake operator、回位、区块加载能力。

这两者的区块加载方式一致，都是调用本体 `EntityUtil.keepChunkLoaded()` 刷 `POST_TELEPORT` 临时票。

## 二、RVP 无人机 / Deployable UAV

### 2.1 当前定位

RVP 的无人机功能不是新建一套独立“无人机实体系统”，而是复用本体 `AbstractVehicle` 载具体系：

- 母车通过 JSON 字段声明可部署 UAV。
- 服务端根据 `deployable_uav_vehicle_id` 构造一个现有载具模板实例。
- 运行时给这个子载具实例挂上“从属 UAV”状态。
- 玩家可按键在母车和子 UAV 之间切换骑乘控制。
- 如果启用 `autoLinkDatalink`，子车还可作为外部雷达源给母车提供雷达快照。

这符合旧调研里的推荐路线：车型模板保持独立，部署出来的实例才进入从属 UAV 模式。

### 2.2 配置入口

配置解析在 `VehicleDataManagerMixin` 的 `apply()` 尾部完成，直接读取原始载具 JSON 字段并写入 `RVP_DeployableUavConfigCache`。

支持字段：

| 字段 | 含义 | 默认 |
| --- | --- | --- |
| `deployable_uav_enabled` | 是否启用可部署 UAV | `false` |
| `deployable_uav_vehicle_id` | 子 UAV 使用的载具模板 ID | 必填 |
| `deployable_uav_role` | 子 UAV 角色，如 `radar_relay` / `uav` | `uav` |
| `deployable_uav_spawn_offset` | 相对母车的生成偏移，支持 object 或 array | `Vec3.ZERO` |
| `deployable_uav_spawn_yaw_mode` | 朝向模式，当前支持 `parent`、`operator_look` | `parent` |
| `deployable_uav_single_instance` | 同一母车是否只允许一个子 UAV | `true` |
| `deployable_uav_allow_control_switch` | 是否允许玩家切换到子 UAV | `true` |
| `deployable_uav_auto_link_datalink` | 是否自动建立数据链 | `true` |

当前在 `run/client_1/limitless_vehicle/rvp` 内未搜到实际 `deployable_uav_*` 配置样例，说明代码能力已经在，但开发载具包里可能还没有正式车型启用。

### 2.3 部署与切换流程

部署入口：

```text
客户端 N 键
  -> C2SDeployDeployableUav
  -> RVP_DeployableUavService.deployLinkedUav(player)
  -> 读取母车 vehicleId 对应的 RVP_DeployableUavConfig
  -> CommonAssetsManager.vehicleDataManager().getVehicleData(childVehicleId)
  -> childData.construct(...)
  -> configureLink(parent, child, player, config)
  -> ServerLevel.addFreshEntity(child)
```

切换入口：

```text
客户端 M 键
  -> C2SSwitchDeployableUav
  -> 如果当前在 deployable UAV 上：switchBackToParent(player)
  -> 否则：switchToLinkedUav(player)
```

切到子 UAV 时，代码会记录玩家在母车上的 `returnSeatIndex`，然后让玩家 `startRiding(child)`。切回母车时会优先尝试回到原座位，失败则至少回到母车骑乘状态。

### 2.4 运行时状态与持久化

`AbstractVehicleLinkedUavMixin` 给 `AbstractVehicle` 注入以下运行时字段，并写入 NBT：

- `linkedParentVehicleUuid`
- `linkedChildVehicleUuid`
- `linkedLauncherVehicleUuid`
- `deployableUavInstance`
- `deployableUavAllowControlSwitch`
- `returnSeatIndex`
- `deployableUavRole`
- `datalinkRole`
- `fakeOperatorPosition`

另外 `RVP_DeployableUavLinkRegistry` 维护一份运行时 `parent -> child` / `child -> parent` 双向 UUID 表，用于缓存查找。NBT 是持久状态，Registry 是运行时快速关系。

### 2.5 实例级 UAV 行为

`AbstractVehicleLinkedUavMixin` 的关键判断是：

```java
deployableUavInstance && !uav
```

也就是说，一个普通载具模板被部署出来后，即使模板本身不是本体 `uav=true`，也会在这个实例上获得类似 UAV 的能力：

- 每 tick 保持自身区块加载。
- 预加载前方 16 格。
- 如果存在 fake operator position，也保持原操作者位置区块加载。
- 进入子 UAV 时生成 `FakePlayer` 并记录原位置。
- 子 UAV 移除时尝试把真实玩家送回 fake operator 位置。
- 阻止普通直接交互，避免从属 UAV 被当作普通载具随意上下车。

### 2.6 外部雷达数据链

无人机系统已经和外部雷达链打通一部分：

- `RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(launcher)` 从母车的 linked child 或 Registry 查找雷达中继车。
- `RVP_ExternalRadarSyncService` 每 5 tick 给每个玩家构造 `S2CExternalRadarSnapshot`。
- 快照包含：
  - launcher vehicle UUID
  - relay vehicle UUID
  - relay radar on/off
  - 外部雷达探测目标 entries
  - 雷达扇区 sectors
  - requested entity id
  - locked entity id

外部雷达锁定流程大致是：

```text
客户端选择外部雷达目标
  -> RVP_ExternalRadarLinkHelper.applyClientLockRequest(...)
  -> C2SRequestExternalRadarLock
  -> 服务端在 RVP_ExternalRadarSyncService.syncExternalLockState() 中校验
  -> relay vehicle 找 preferred lock-capable RadarUnit
  -> relay radar 当前能探测目标则 setLockedEntity(target)
  -> launcher weapon unit 的 external locked id 同步给客户端
```

当前这套更像“一对一直属雷达车数据链”，还不是全队公共数据网。

### 2.7 风险与待补

- 当前载具包里缺少实际 `deployable_uav_*` 样例，后续需要用具体车型验证部署偏移、座位恢复和雷达链。
- `RVP_DeployableUavLinkRegistry` 是内存缓存，重启后依赖 NBT 字段恢复实体自身状态，但 parent/child runtime map 不会自动重建；查找逻辑优先读 ext 字段，可以覆盖常规场景，但复杂重连/跨维度仍要测试。
- 母车安全保持还没有看到专门状态机。玩家切到 UAV 后，母车无人驾驶时如何停车、悬停或平飞，需要按车型补。
- 外部雷达链目前按当前玩家所在母车构造快照，多玩家共乘、多武器站、多 relay vehicle 场景需要额外定义优先级。

## 三、TV 弹 / HITL

### 3.1 当前定位

当前 TV 弹不是独立武器类型，也不是独立 `TV` guidance source，而是：

```text
rvp:missile
  + guidance_data.human_in_the_loop.enabled = true
  + guidance stage 中使用 MCLOS / SACLOS / 或其它制导源
```

`RVP_HumanInTheLoopData.resolveControlMode()` 的默认推断规则是：

- 如果显式写了 `control_mode`，用配置值。
- 如果 guidance 中包含 `MCLOS`，默认 `MOUSE`。
- 如果 guidance 中包含 `SACLOS`，默认 `DESIGNATE`。
- 否则为 `VIEW`，即只提供弹载视角，不接管导弹目标。

### 3.2 配置字段

`guidance_data.human_in_the_loop` 支持：

| 字段 | 含义 |
| --- | --- |
| `enabled` | 是否开启 HITL / 弹载视角 |
| `control_mode` | `VIEW` / `MOUSE` / `DESIGNATE` |
| `signal_source` | `RADIO` 或 `FIBER`，支持若干别名 |
| `control_range` | 最大控制距离 |
| `timeout_tick` | HITL 会话寿命 |
| `max_turn_deg_per_tick` | MOUSE 模式每 tick 最大转向角 |
| `max_look_offset_deg` | DESIGNATE 模式相对弹体轴线的视角偏移上限 |
| `video_modes` | `COLOR`、`BW`、`THERMAL`，空则全部允许 |

开发包与示例中已经存在多种 HITL 武器：

- `docs/examples/guidance/m09_spike_tv.json`
- `docs/examples/mi28_s13_boundary/weapons/*`
- `run/client_1/limitless_vehicle/rvp/data/rvp/weapons/mi28_atgm_mclos_tv.json`
- `run/client_1/limitless_vehicle/rvp/data/rvp/weapons/mi28_atgm_saclos_tv.json`
- `run/client_1/limitless_vehicle/rvp/data/rvp/weapons/mi28_kh_39.json`

### 3.3 服务端弹体状态

`RVP_MissileEntity` 在 `initFromWeapon()` 读取 HITL 配置后写入：

- `hitlControlRange`
- `hitlTimeoutTick` / `hitlLife`
- `hitlVideoModeMask`
- `hitlDefaultVideoMode`
- `hitlControlMode`
- `hitlMaxTurnDegPerTick`
- `hitlMaxLookOffsetDeg`
- `hitlSignalSource`
- `hitlEnabled`

每 tick 的核心流程：

```text
RVP_MissileEntity.tickGuidance()
  -> maybeSyncEnterHitlView()
  -> tickHitlRadioLink()
  -> 如果链路断开/遮挡，停止 HITL 制导输入
  -> tickHitlSession()
  -> tickHitlMouseInertia()
  -> 服务端继续 ARM / ARH / stage guidance
```

其中 `maybeSyncEnterHitlView()` 会在发射后的前几个 tick 向 owner 玩家发送 `S2CEnterHitlView`，让客户端进入弹载视角。

### 3.4 控制模式

| 模式 | 行为 |
| --- | --- |
| `VIEW` | 客户端相机跟随导弹，只观察，不发送控制输入 |
| `MOUSE` | 鼠标输入改变 `hitlYaw/hitlPitch`，客户端每 tick 发送 `C2SHitlSteeringInput`，服务端平滑追随输入航向 |
| `DESIGNATE` | 鼠标只在导引头视场内移动准星；按 R 重指定目标点/实体，通过 `C2SHitlDesignate` 发送给服务端 |

`LocalVehiclePlayerTVMissileTurnMixin` 接管本体 `LocalVehiclePlayer.handlePlayerTurn()`：

- MOUSE 模式调用 `RVP_ClientHitlState.applySteeringDelta()`。
- DESIGNATE 模式调用 `RVP_ClientHitlState.applyLookOffsetDelta()`。
- 两者都会取消原本的载具视角转动。

服务端安全边界：

- `C2SHitlSteeringInput` 和 `C2SHitlDesignate` 都要求导弹实体存在且 `missile.getOwner() == player`。
- MOUSE 输入带 `seq`，`RVP_MissileEntity.rvp$setHitlSteeringInput()` 会丢弃旧序号。
- RADIO 模式下，如果母车到导弹的射线被方块遮挡，短时 blocked，连续约 40 tick 后 severed 并关闭 HITL。

### 3.5 客户端视角、HUD 与滤镜

客户端状态入口是 `RVP_ClientHitlState`：

- 收到 `S2CEnterHitlView` 后记录 active missile id。
- 保存进入前的 `LocalVehiclePlayer.viewType`。
- HITL 期间强制 `LocalVehiclePlayer.viewType = SCOPE`，复用观瞄 HUD。
- 根据视频模式控制：
  - `COLOR`：无额外后处理。
  - `BW`：启用 `TVMissileVideoPostHandler`，加载 `assets/ywzj_rvp/shaders/post/tvmissile_bw.json`。
  - `THERMAL`：设置 `LocalVehiclePlayer.instance.thermalImaging = true`，复用本体热成像链。

`CameraTVMissileMixin` 注入 `Camera.setup` TAIL，在第一人称且 HITL active 时，将相机位置/朝向覆盖到导弹头部附近。`RVP_ClientHitlCamera` 负责：

- 导弹姿态插值。
- 镜头位置平滑。
- DESIGNATE 模式的导弹轴向跟随与准星偏移平滑。
- 弹体短暂找不到时使用上帧 pose 兜底。

`RVP_TVMissileOverlay` 额外显示：

- 当前视频模式。
- 当前控制模式。
- 导弹转向角速度。
- RADIO 链路 blocked 时绘制雪花噪声。

### 3.6 与制导系统的关系

HITL 不替代所有制导逻辑，而是作为 `guidance_data` 的一部分参与：

- `MOUSE` 模式常与 `MCLOS` 组合，服务端把鼠标输入航向作为导弹转向目标。
- `DESIGNATE` 模式常与 `SACLOS` 组合，服务端用客户端指定的点/实体作为目标。
- `VIEW` 模式可以只做弹载视角观察，导弹仍按其它 stage/source 工作。

### 3.7 风险与待补

- `RVP_ClientHitlState.clear()` 会关闭 `thermalImaging`，如果玩家进入 HITL 前本来就开着热成像，当前恢复逻辑只恢复 viewType，没有恢复原热成像状态。
- MOUSE 与 DESIGNATE 已有 owner 校验，但 target position 本身仍来自客户端射线结果；需要依赖服务端导弹机动限制和射线/目标合法性控制玩法边界。
- RADIO 遮挡判断是母车中心到导弹中心的方块射线，地形/建筑遮挡很直接；如果未来做光纤弹、数据链中继或地形绕射，要在 `signal_source` 语义上继续扩展。
- HITL 强制 SCOPE 会让部分本体 HUD 数据源仍来自母车武器站，当前已把 `weaponHitPos` 和 `aimLocationDistance` 重写到导弹视角，但其它 HUD 元素仍需按体验逐项核对。

## 四、区块加载器

### 4.1 基础机制

RVP 没有自己实现 Forge `ForcedChunkManager` 式持久区块加载，而是复用本体：

```java
EntityUtil.keepChunkLoaded(entity, position)
```

旧文档记录的本体实现是：

```java
new ChunkPos(BlockPos.containing(position));
((ServerLevel) entity.level())
    .getChunkSource()
    .addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 3, entity.getId());
```

特点：

- 临时区块票，适合移动实体。
- 需要每 tick 刷新。
- 实体消亡后不再刷新，旧票自然过期。
- 不适合做静态、跨重启的长期区块加载器。

### 4.2 弹药区块加载

`RVP_BaseBullet` 构造函数默认：

```java
this.keepChunkLoaded = true;
```

因此以下重弹类默认保区块：

- `RVP_MissileEntity`
- `RVP_RocketEntity`
- `RVP_BombEntity`
- `RVP_DispensedEntity`

但 `RVP_BulletEntity.initFromWeapon()` 当前会执行：

```java
this.keepChunkLoaded = false;
```

所以当前状态是：

| 弹体 | 是否保区块 | 说明 |
| --- | --- | --- |
| `rvp:missile` | 是 | 远程制导弹，需要跨区块持续 tick |
| `rvp:rocket` | 是 | 火箭 / 子弹药载体，默认保区块 |
| `rvp:bomb` | 是 | 高空投放和 GPS/滑翔类需要 |
| `rvp:dispenser` payload | 是 | 布撒载荷默认继承 BaseBullet |
| `rvp:machinegun` / `RVP_BulletEntity` | 否 | 当前显式关闭，避免大量机枪弹带来区块票压力 |

这和旧文档 `Gunner与弹药区块加载调研.md` 中“RVP_BulletEntity 也启用”的记录不同，以当前代码为准。

### 4.3 可部署 UAV 区块加载

`AbstractVehicleLinkedUavMixin` 对“实例级 UAV”启用区块加载：

条件：

```text
deployableUavInstance == true
且本体 uav == false
且服务端
```

每 tick 加载：

- 子 UAV 当前区块。
- 子 UAV 前方 16 格区块。
- fake operator position 所在区块。

这用于保证“普通载具模板被当成从属 UAV 部署”时，也能像本体 UAV 一样在玩家远程操控期间不冻结。

### 4.4 Gunner 驾驶载具区块加载

`AbstractVehicleGunnerChunkLoadMixin` 解决的是 AI 驾驶载具远离玩家后区块卸载的问题。

启用条件：

- 仅服务端。
- 不是本体 `uav`，因为 UAV 已有自己的加载逻辑。
- 载具未销毁。
- `getDriver()` 是 `GunnerEntity`。
- 距离至少一名玩家不超过 96 chunk。

加载内容：

- 载具当前区块。
- 载具前方 16 格区块。

96 chunk 限制是安全阀：防止 AI 载具无限远飞后继续造成服务器区块加载压力。

### 4.5 战术地图区块缓存不是区块加载器

`RVP_TacticalMapChunkListener` 和 `RVP_TacticalMapCache` 也大量出现 `Chunk` 关键词，但它们做的是客户端地图高度缓存：

- 监听客户端 `ChunkEvent.Load`。
- 把已加载区块加入采样队列。
- 每 tick 处理少量区块高度数据。
- 写入/读取 `chunk_heights.bin`。

这套逻辑不会强制服务器加载区块，不能和上面的 chunk loading 混为一谈。

### 4.6 风险与优化点

- 当前使用临时票，适合运行时移动实体，不支持服务器重启后主动恢复某个区块。
- 弹药保区块依赖本体 `AmmoEntity.tick()` 对 `keepChunkLoaded` 的处理；RVP 自己只改开关。
- 机枪弹当前关闭保区块，远距离大口径机炮如果确实需要跨区块命中，后续可以考虑 JSON 参数化，而不是全局打开。
- 高速导弹只预加载前方 16 格，如果速度超过 1 chunk/tick，极端情况下仍可能撞入未加载区块；可考虑按速度动态前探。
- 可部署 UAV 和 Gunner 载具都没有手动 unforce，依赖临时票自然过期，这是设计选择；排查“区块残留”时要记得约有短暂尾票时间。

## 五、建议的下一步

1. 为开发载具包补一个最小 `deployable_uav_*` 样例，用具体母车和雷达车验证部署、切换、数据链。
2. 把 `deployable_uav_*` 字段补进 `docs/RVP包新增参数字段说明.md`，当前权威 schema 主要记录了 HITL，未系统记录 UAV 配置字段。
3. 给 `RVP_BulletEntity.keepChunkLoaded=false` 的设计写入弹体文档，避免后续按旧文档误判。
4. TV 弹测试重点放在：
   - 进入/退出后视角与热成像状态恢复。
   - RADIO 遮挡 40 tick 断链体验。
   - MOUSE 模式 `max_turn_deg_per_tick` 与导弹真实机动手感。
   - DESIGNATE 模式目标点/实体重指定与 HUD 落点一致性。
5. 区块加载测试重点放在：
   - Gunner 固定翼飞出玩家加载范围后是否持续 tick。
   - 子 UAV 远程操控时是否保持自身与 fake operator 区块。
   - 弹药实体销毁后区块是否自然卸载。

## 六、代码入口清单

### 本体 UAV

- `../ywzj_vehicle/src/main/java/org/ywzj/vehicle/custom/vehicle/BaseVehicleDataPojo.java`
- `../ywzj_vehicle/src/main/java/org/ywzj/vehicle/custom/vehicle/BaseVehicleData.java`
- `../ywzj_vehicle/src/main/java/org/ywzj/vehicle/entity/vehicle/AbstractVehicle.java`
- `../ywzj_vehicle/src/main/java/org/ywzj/vehicle/item/UavControllerItem.java`
- `../ywzj_vehicle/src/main/java/org/ywzj/vehicle/entity/misc/FakePlayer.java`
- `../ywzj_vehicle/src/main/java/org/ywzj/vehicle/util/EntityUtil.java`
- `../ywzj_vehicle/src/main/resources/default_vehicle/data/ywzj_vehicle/vehicles/quadcopter.json`

### 无人机 / 数据链

- `src/main/java/org/ywzj/rvp/uav/RVP_DeployableUavService.java`
- `src/main/java/org/ywzj/rvp/uav/RVP_DeployableUavLinkRegistry.java`
- `src/main/java/org/ywzj/rvp/config/RVP_DeployableUavConfig.java`
- `src/main/java/org/ywzj/rvp/config/RVP_DeployableUavConfigCache.java`
- `src/main/java/org/ywzj/rvp/mixin/VehicleDataManagerMixin.java`
- `src/main/java/org/ywzj/rvp/mixin/AbstractVehicleLinkedUavMixin.java`
- `src/main/java/org/ywzj/rvp/ext/AbstractVehicleLinkedUavExt.java`
- `src/main/java/org/ywzj/rvp/network/C2SDeployDeployableUav.java`
- `src/main/java/org/ywzj/rvp/network/C2SSwitchDeployableUav.java`
- `src/main/java/org/ywzj/rvp/network/RVP_ExternalRadarSyncService.java`
- `src/main/java/org/ywzj/rvp/radar/RVP_ExternalRadarLinkHelper.java`

### TV 弹 / HITL

- `src/main/java/org/ywzj/rvp/entity/projectile/RVP_MissileEntity.java`
- `src/main/java/org/ywzj/rvp/weapon/data/RVP_HumanInTheLoopData.java`
- `src/main/java/org/ywzj/rvp/client/state/RVP_ClientHitlState.java`
- `src/main/java/org/ywzj/rvp/client/state/RVP_ClientHitlCamera.java`
- `src/main/java/org/ywzj/rvp/mixin/CameraTVMissileMixin.java`
- `src/main/java/org/ywzj/rvp/mixin/LocalVehiclePlayerTVMissileTurnMixin.java`
- `src/main/java/org/ywzj/rvp/network/S2CEnterHitlView.java`
- `src/main/java/org/ywzj/rvp/network/C2SHitlSteeringInput.java`
- `src/main/java/org/ywzj/rvp/network/C2SHitlDesignate.java`
- `src/main/java/org/ywzj/rvp/client/shader/TVMissileVideoPostHandler.java`
- `src/main/java/org/ywzj/rvp/client/gui/RVP_TVMissileOverlay.java`

### 区块加载

- `src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java`
- `src/main/java/org/ywzj/rvp/entity/projectile/RVP_BulletEntity.java`
- `src/main/java/org/ywzj/rvp/mixin/AbstractVehicleLinkedUavMixin.java`
- `src/main/java/org/ywzj/rvp/mixin/AbstractVehicleGunnerChunkLoadMixin.java`
- `src/main/java/org/ywzj/rvp/client/map/RVP_TacticalMapChunkListener.java`
- `src/main/java/org/ywzj/rvp/client/map/RVP_TacticalMapCache.java`
