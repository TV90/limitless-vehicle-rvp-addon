# RVP 导弹航程采用虚拟中段弹道的具体实施方案

> 状态：阶段 B 实施文档；已按 `RVP_RvpTrajectoryIntegrator` 当前 `rvp_current` / `VERSION = 3` 实现同步。
> 适用项目：`limitless-vehicle-rvp-addon`，Minecraft 1.20.1 Forge。  
> 约束：所有新逻辑只写在 RVP 子模组，不修改 `ywzj_vehicle` 本体源码。

## 1. 背景与目标

现有 `RVP_BaseBullet` 将导弹作为真实 Minecraft 实体逐 Tick 运行。每 Tick 都要执行实体调度、制导、运动、碰撞、引信、尾迹、同步，并通过 `RVP_ChunkPathLoadManager` 为未来 5 Tick 的路径申请临时 Ticket。该方案适合中短程飞行，但不适合数千至数万格的巡航导弹或 GPS 导弹：

- 真实实体若一路飞行，就必须持续加载其经过的区块和 entity section；
- 高速导弹会在较短时间内触发大量新区块生成与加载；
- 即使单 Tick 路径安全，长距离持续强加载仍会放大服务器 CPU、内存与磁盘压力；
- `RemoteTickEntity` 只负责远距离网络广播和客户端引用恢复，不能让实体脱离服务端实体调度后继续飞行；
- 单纯增加 `life`、Ticket 数量、前探 Tick 或同步调用 `getChunk(...)` 都不能解决长航程的总成本问题。

因此，超长航程导弹应只在需要真实世界交互的阶段作为实体存在，中段巡航阶段改用服务器级轻量记录推进。目标状态为：

```text
真实初段实体
  -> 满足虚拟化条件
  -> 服务器级虚拟飞行记录
  -> 接近末段恢复点
  -> 异步申请恢复区块 Ticket
  -> 区块 loaded + entity-ticking
  -> 恢复真实导弹实体
  -> 真实末制导、碰撞、引信与爆炸
```

本方案必须保证：

1. 虚拟飞行不加载沿途区块，不生成沿途实体，不执行沿途方块碰撞。
2. 末段恢复前先异步预热恢复区块及短路径，禁止在 Tick 内同步 `getChunk(...)`。
3. 虚拟中段不能绕过导弹 `life`、最大航程、飞行时间和配置的末段触发条件。
4. 虚拟化是武器 JSON 显式启用的能力，不自动套用到全部导弹。
5. HITL、SACLOS、线导、持续激光照射等依赖实时世界交互的模式默认禁止虚拟化。
6. 同一枚导弹在实体态和虚拟态之间保持稳定的“飞行 UUID”，实体 ID 允许变化。
7. 服务端重启后可恢复虚拟飞行记录，或按明确策略安全取消，不产生永久幽灵导弹。

## 2. 范围与非目标

### 2.1 第一版支持范围

第一版只支持目标和中段轨迹可在不查询沿途世界的情况下确定的导弹：

| 制导场景 | 第一版支持 | 说明 |
| --- | --- | --- |
| GPS 固定坐标 | 是 | 最稳定，目标坐标在发射时已确定 |
| INS / 惯性飞向最后指示点 | 是 | 使用发射时或失锁前保存的目标点 |
| GPS 主制导 + ARH 末制导 | 是 | 中段飞向最后目标点，恢复实体后弹载雷达再搜索 |
| 预设巡航航路点 | 可选 | 需要把航路点快照保存进虚拟记录 |
| 对移动实体的指令修正 | 第二阶段 | 需要独立的数据链目标更新服务，不能依赖目标实体始终 loaded |
| SARH / SALH / LH | 默认否 | 依赖照射源、视线或载机持续支持 |
| SACLOS / MCLOS / HITL TV | 否 | 需要玩家逐 Tick 控制、摄像机或瞄准线 |
| 线导导弹 | 否 | 线缆、距离和发射平台状态均要求真实实体 |
| 近炸、地形跟随、沿途子母弹布撒 | 否 | 需要沿途碰撞、实体或方块查询 |

### 2.2 明确不解决的问题

- 不模拟未加载区块中的真实地形碰撞和破坏。
- 不允许虚拟记录直接在目标位置结算爆炸或伤害。
- 不代替现有单 Tick 路径上限、路径 Ticket 预算和移动前就绪门控。
- 不把沿途区块一次性加入 Ticket，也不建立整条航线的强加载走廊。
- 不使用武器资源 ID 判断是否虚拟化；全部行为由当前 schema 的 JSON 字段决定。
- 不修改 `ywzj_vehicle` 的 `MissileEntity` 或 `RemoteTickEntity`。

## 3. 总体架构

总体架构必须按物理侧拆分为服务端权威域、客户端表现域和只承载数据的公共协议域。服务端不调用任何 `net.minecraft.client.*` 类型；客户端不持有、推进或恢复权威虚拟导弹。两侧只通过显式 S2C 消息传递虚拟导弹的只读表现快照。

```text
                         服务端权威域
真实导弹实体 ──虚拟化──> VirtualMissileState ──恢复──> 真实导弹实体
                              │
                              │ 低频只读 S2C 快照/离散状态事件
                              ▼
                         客户端表现域
                    VirtualMissileContactCache
                              │
                    战术地图图标/可选航迹表现
```

### 3.1 服务端组件与职责

将权威逻辑放在独立的 server 包中：

```text
org.ywzj.rvp.virtualflight.server
├─ RVP_VirtualMissileManager          权威状态机、预算与 ServerTick 调度
├─ RVP_VirtualMissileState            单枚导弹的可持久化权威记录
├─ RVP_VirtualMissileEligibility      进入虚拟态的资格与运行时否决检查
├─ RVP_VirtualMissileSavedData        跨重启持久化
├─ RVP_VirtualMissileRestoreService   恢复区块预热与实体重建
├─ RVP_VirtualMissileTicketManager    恢复区块临时 Ticket 管理
├─ RVP_VirtualMissileSyncService      生成并分发只读客户端快照
└─ RVP_VirtualMissileDebug            状态转换、失败原因与统计日志

org.ywzj.rvp.virtualflight.common
├─ RVP_VirtualMissileContact          网络与 UI 使用的不可变只读 DTO
├─ RVP_VirtualFlightPhase             稳定的协议阶段枚举
└─ RVP_VirtualFlightReason            稳定的状态转换原因码

org.ywzj.rvp.virtualflight.trajectory
├─ RVP_VirtualTrajectoryIntegrator    可替换的虚拟弹道积分接口
├─ RVP_RvpTrajectoryIntegrator        当前 rvp_current/VERSION=3 纯积分实现
├─ RVP_VirtualTrajectoryState         积分器所需的最小运动/发动机状态
├─ RVP_VirtualGuidanceInput           当前仅承载固定 GPS 目标点
├─ RVP_VirtualTrajectoryParameters    从武器配置解析的冻结积分参数
└─ RVP_VirtualTrajectoryResult        单逻辑 Tick 的不可变计算结果

org.ywzj.rvp.weapon.data
└─ RVP_VirtualMidcourseData           当前 schema 的 JSON 数据模型
```

服务端负责且仅服务端有权执行：

- 判断真实导弹能否进入虚拟态；
- 创建、推进、持久化和删除 `RVP_VirtualMissileState`；
- 扣除 `life`、累计航程、更新目标和制导阶段；
- 申请恢复区块 Ticket，并检查 `loaded + entity-ticking`；
- 重建真实导弹实体，执行最终碰撞、引信、爆炸和伤害；
- 按玩家、阵营、维度和权限过滤可见 Contact，再发送 S2C 消息。

`RVP_VirtualMissileManager` 以 `MinecraftServer` 实例隔离状态，只在 Forge `ServerTick START` 推进。它不持有真实导弹实体的强引用；虚拟化完成后，原实体从世界移除，后续只推进服务端的 `RVP_VirtualMissileState`。集成服务器也必须走相同的 S2C 通道，不能因为客户端和服务端位于同一进程就直接共享 Map 或对象引用。

第一阶段不重构真实实体的 `RVP_GuidanceRuntimeMath` 和 `RVP_ProjectileMotion` 调用链，而是把其中虚拟中段需要的现有算法复制到 `RVP_RvpTrajectoryIntegrator`。`RVP_VirtualMissileManager` 只依赖 `RVP_VirtualTrajectoryIntegrator` 接口，不依赖具体实现；以后可以把实现替换为共享内核、解析积分器或其他弹道模型，而不修改管理器、持久化、Ticket 和客户端协议。

积分接口和第一阶段实现均不得引用 `Level`、`Entity` 或 `net.minecraft.client.*`。生产环境只由服务端管理器调用；客户端不能通过该接口推进权威位置。

### 3.2 客户端组件与职责

客户端只维护短生命周期的表现缓存：

```text
org.ywzj.rvp.virtualflight.client
├─ RVP_ClientVirtualMissileContactCache  按 flightUuid 保存最新只读快照
├─ RVP_ClientVirtualMissileInterpolator  两个服务端快照之间的视觉插值
├─ RVP_ClientVirtualMissileMapAdapter    向战术地图提供只读 Contact
└─ RVP_ClientVirtualMissileEffects       可选、非实体化的航迹/提示表现
```

客户端允许执行：

- 接收服务端 Contact 快照并按 `flightUuid` 更新缓存；
- 在相邻快照之间插值图标或航迹位置；
- 收到删除、维度切换或实体恢复事件后清理缓存；
- 将 `flightUuid -> newEntityId` 交给战术地图或 UI 做显示对象切换。

客户端禁止执行：

- 扣除服务端 `life`、推进权威弹道或自行决定进入末段；
- 申请 Chunk Ticket、查询服务端区块是否 ready 或调用实体重建；
- 根据客户端插值位置结算碰撞、引信、伤害或爆炸；
- 创建加入客户端 `Level` 的假导弹实体来冒充虚拟记录；
- 将客户端丢包、暂停或渲染距离变化反馈成虚拟导弹终止。

客户端缓存超时只影响显示。例如连续 60 Tick 未收到更新，可以隐藏图标并等待后续快照；不得据此通知服务端删除导弹。

### 3.3 网络协议边界

网络层只传输 UI 和视觉所需的最小快照，不序列化完整 `RVP_VirtualMissileState`，避免泄露服务端内部状态或让客户端与持久化 schema 耦合。建议消息：

```text
S2CVirtualMissileContacts
  contacts[]:
    flightUuid
    dimension
    position
    velocity
    phase
    serverGameTime
    faction/contactVisibility

S2CVirtualMissileRemoved
  flightUuid
  reason

S2CVirtualMissileReified
  flightUuid
  newEntityId
  position
  velocity
```

普通巡航快照可每 10～20 Tick 发送；`REAL_TO_VIRTUAL`、`RESTORE_REQUESTED`、`VIRTUAL_TO_REAL` 和终止属于离散事件，应在状态转换时立即发送。客户端用消息中的 `serverGameTime` 进行插值基准校正，但不能把外推结果回传为权威位置。

第一版不需要任何 C2S 虚拟飞行控制包。以后增加数据链时，C2S 只能表达“玩家请求更新目标”，服务端仍须重新验证操作者、武器、距离、阵营、目标合法性和导弹当前阶段，再决定是否修改权威记录。

### 3.4 服务端权威状态机

```text
REAL_INITIAL
  ├─ 不符合资格 ──────────────────────────────> 保持真实实体
  └─ 达到 entry 条件
       -> VIRTUALIZING
       -> VIRTUAL_CRUISE
            ├─ life/航程耗尽 -> TERMINATED
            ├─ 配置/维度无效 -> TERMINATED
            ├─ 到达恢复触发距离 -> RESTORE_REQUESTED
            └─ 数据链更新目标 -> 继续 VIRTUAL_CRUISE

RESTORE_REQUESTED
  -> RESTORE_WAITING_CHUNKS
       ├─ 区块未就绪且未超时 -> 保持虚拟记录并刷新短 Ticket
       ├─ 等待超时 -> TERMINATED 或延后重试
       └─ loaded + entity-ticking -> REIFYING

REIFYING
  ├─ addFreshEntity 成功 -> REAL_TERMINAL
  └─ 失败 -> RESTORE_WAITING_CHUNKS（有限次数）
```

该状态机只存在于服务端。客户端收到的 `phase` 是用于 UI 的只读投影，不运行同构状态机，也不根据本地时间自行触发下一阶段。

状态转换只允许单向进行。第一版不允许末段实体再次进入虚拟态，避免在目标附近反复实体化/虚拟化。每次转换先提交服务端权威状态，再生成对应 S2C 事件；网络发送失败或客户端未在线都不能回滚服务端飞行状态。

### 3.5 解耦前后对比

解耦前的总体架构把虚拟飞行管理、状态、积分、恢复、调试和数据模型放在同一个 `org.ywzj.rvp.virtualflight` 包中，只描述了服务器管理器如何推进导弹，没有明确客户端需要哪些数据、由谁维护表现缓存，以及集成服务器能否直接读取管理器状态。虽然原设计意图仍是服务端运行，但代码落地时容易出现以下问题：

- 战术地图或 HUD 直接读取 `RVP_VirtualMissileManager` 的内部 Map；
- common 类为了显示功能逐渐引用 `Minecraft`、客户端 Level 或 Renderer；
- 客户端复制一套虚拟弹道积分逻辑，并错误地承担阶段切换判断；
- 集成服务器因为运行在同一 JVM 中而绕过网络同步，导致单人可用、专用服务器失效；
- 完整权威状态被直接序列化给客户端，使网络协议与 SavedData schema 绑定；
- 客户端断线、缓存超时或渲染范围变化反向影响服务端导弹生命周期。

解耦后以“服务端拥有事实，客户端只拥有表现快照”为基本规则。对比如下：

| 对比项 | 解耦之前 | 解耦之后 |
| --- | --- | --- |
| 权威状态所有者 | 文档只隐含为 `RVP_VirtualMissileManager`，边界不够明确 | 仅服务端 `RVP_VirtualMissileState` 是权威状态 |
| 包结构 | server、client、纯数据类可能混在 `virtualflight` 包 | 明确拆成 `virtualflight.server`、`virtualflight.client` 和 `virtualflight.common` |
| Tick 驱动 | 只说明 `ServerTick START`，未禁止客户端另行推进 | 只有服务端推进弹道；客户端只按快照插值 |
| 客户端数据来源 | 未定义，UI 可能直接访问服务端管理器或依赖实体 | 只接收经过权限过滤的 S2C Contact 和状态事件 |
| 客户端持有内容 | 未规定，可能复制完整虚拟记录 | 只保存短生命周期、只读的 `RVP_VirtualMissileContact` |
| 飞行计算 | 虚拟态容易把积分细节直接写死在管理器中 | 管理器仅依赖 `RVP_VirtualTrajectoryIntegrator`；当前默认安装 `RVP_RvpTrajectoryIntegrator`，之后可替换 |
| 区块与实体操作 | 恢复服务是服务器逻辑，但没有明确禁止客户端调用 | Ticket、ready 查询、实体重建全部限定在服务端 |
| 状态机 | 可被误解为两端各运行一份 | 状态机仅存在于服务端，客户端 `phase` 只是显示投影 |
| 网络协议 | 未在总体架构定义 | 明确区分低频快照、删除事件和恢复实体映射事件 |
| 持久化与网络 | 可能复用同一完整状态序列化结构 | SavedData 使用完整权威记录；网络只使用最小只读 DTO |
| 集成服务器 | 可能通过同进程对象引用走捷径 | 与专用服务器一致，必须走显式 S2C 通道 |
| 客户端掉线/丢包 | 行为边界未定义，可能影响导弹状态 | 只影响本地显示，不影响服务端推进、恢复或终止 |
| 无客户端服务器 | 客户端依赖混入后可能触发类加载错误 | server 包不引用 `net.minecraft.client.*`，可独立运行 |
| 测试方式 | 往往需要同时搭建管理器和显示环境 | 服务端状态机、积分接口实现、网络 DTO、客户端缓存可分别测试 |
| 安全性 | 客户端可能获得不必要的目标、寿命或内部制导信息 | 服务端先做阵营和权限过滤，只下发表现所需字段 |

依赖方向也从潜在的双向调用改为单向数据流：

```text
解耦之前（存在落地风险）

VirtualMissileManager <────> HUD / 战术地图
        │                         │
        └──── 共享状态对象 ───────┘

解耦之后

server manager
    -> immutable common DTO
    -> S2C network message
    -> client contact cache
    -> HUD / 战术地图 / 非权威视觉插值
```

解耦不改变以下战斗规则：

- 导弹是否进入虚拟态仍由服务端资格检查决定；
- `life`、航程、目标更新和恢复距离仍按服务端 Tick 计算；
- 恢复区块仍须达到 `loaded + entity-ticking`；
- 末段碰撞、引信、爆炸和伤害仍由恢复后的真实 RVP 导弹实体结算；
- 客户端插值位置永远不参与命中判断。

因此，这次解耦不是额外复制一套客户端虚拟弹道，而是把原架构中未明确的客户端部分收缩为只读投影层。服务端即使没有任何玩家在线，也能完整推进、持久化和恢复虚拟导弹；客户端即使完全关闭虚拟导弹显示，也不会改变服务端结果。

## 4. 武器 JSON 数据模型

在 `RVP_WeaponData` 增加：

```json
{
  "virtual_midcourse_data": {
    "enabled": true,
    "entry_min_flight_tick": 100,
    "entry_min_distance_from_launch": 800.0,
    "entry_min_target_distance": 1600.0,
    "restore_target_distance": 768.0,
    "restore_lead_tick": 60,
    "restore_ticket_radius": 1,
    "restore_wait_timeout_tick": 200,
    "virtual_update_interval_tick": 1,
    "max_virtual_flight_tick": 12000,
    "virtual_midcourse_maxg": 18.0,
    "cruise_altitude": 320.0,
    "target_update_mode": "FIXED_SNAPSHOT",
    "on_restore_timeout": "DISCARD"
  }
}
```

字段如下。每个 `@SerializedName` 字段必须按项目约束编写与 `RVP_WeaponData` 同级的 JavaDoc，说明单位、默认值和生效条件。

| 字段 | 类型 | 单位/默认值 | 说明 |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | 是否允许该武器进入虚拟中段；必须显式开启 |
| `entry_min_flight_tick` | int | tick，`0` | 达到该有效飞行 Tick 后才允许虚拟化；区块等待 Tick 不计入 |
| `entry_min_distance_from_launch` | float | 格，`800` | 离发射点足够远后才进入虚拟态，保留可观察的真实助推段 |
| `entry_min_target_distance` | float | 格，`1600` | 距目标仍足够远时才值得虚拟化，防止短程导弹频繁切换 |
| `restore_target_distance` | float | 格，`768` | 距末段目标小于该值时开始恢复准备 |
| `restore_lead_tick` | int | tick，`2` | 根据当前速度提前计算恢复点的时间余量 |
| `restore_ticket_radius` | int | 区块半径，`1` | 恢复点周围 Ticket 半径，建议限制为 0～2 |
| `restore_wait_timeout_tick` | int | tick，`200` | 恢复区块长期无法 ready 时的最大等待时间 |
| `virtual_update_interval_tick` | int | tick，`1` | 虚拟积分间隔；第一版固定钳制为 1，后续才允许批量积分 |
| `max_virtual_flight_tick` | int | tick，`12000` | 单次虚拟态最长持续时间，不得超过弹体剩余 `life` |
| `virtual_midcourse_maxg` | double | G，`18` | 虚拟中段独立的最大法向过载；只约束虚拟积分，不改变实体阶段 |
| `cruise_altitude` | double/null | 世界 Y，`null` | 可选虚拟巡航高度；配置后在目标仍可达的前提下由高度闭环跟踪，不可达时取沿命中路线能够接近的高度，为空时以当前虚拟高度为闭环基准 |
| `target_update_mode` | enum | `FIXED_SNAPSHOT` | `FIXED_SNAPSHOT` 或第二阶段的 `DATALINK` |
| `on_restore_timeout` | enum | `DISCARD` | 第一版只建议 `DISCARD`；不得在未加载目标处直接爆炸 |

校验规则：

- `restore_target_distance` 必须小于 `entry_min_target_distance`，建议至少留出 256 格迟滞区间。
- `restore_lead_tick × 当前水平速度` 与 `restore_target_distance` 取较大值作为实际恢复触发距离。
- `restore_ticket_radius` 最大为 2，避免单枚导弹恢复时请求过多区块。
- `max_virtual_flight_tick <= weapon life`；运行时最终使用两者较小值。
- `virtual_midcourse_maxg` 必须为非负有限值；非法值回退为默认 18 G，0 G 表示保持当前方向。
- 虚拟积分不读取 `projectile_data.turning_factor`，也不使用 `guidance_data.cruise_leveling_factor` 或 `max_turn_degree_per_tick`。
- 不添加旧键别名、`legacy*` 或迁移逻辑；历史 JSON 由 `scripts/` 批量修改。

`virtual_midcourse_maxg` 是虚拟积分器的明确参数契约，不是本体 `max_g` 的别名。实体态仍按当前 RVP 实体逻辑飞行，虚拟态则用独立最大 G 值保证单 Tick 机动上限可解释、可测试。以后替换积分方法时，新实现必须显式声明参数和状态版本，不得静默改变在途记录语义。

## 5. 虚拟飞行记录

`RVP_VirtualMissileState` 建议保存下列字段：

```java
public record RVP_VirtualMissileState(
        UUID flightUuid,
        ResourceKey<Level> dimension,
        ResourceLocation weaponId,
        UUID ownerUuid,
        UUID shooterVehicleUuid,
        Vec3 launchPosition,
        Vec3 position,
        Vec3 velocity,
        float yaw,
        float pitch,
        float flightSpeed,
        Vec3 targetPosition,
        UUID targetEntityUuid,
        Vec3 lastKnownTargetVelocity,
        int originalEntityId,
        int flightTick,
        int remainingLife,
        double travelledDistance,
        RVP_GuidancePhase guidancePhase,
        int motorBurnEndTick,
        int secondPulseStartTick,
        int secondPulseBurnTimeTick,
        boolean activeRadarOn,
        boolean activeRadarCatch,
        long enteredGameTime,
        long lastUpdatedGameTime,
        String trajectoryImplementationId,
        int trajectoryStateVersion,
        VirtualFlightPhase phase,
        int restoreWaitTick,
        int restoreAttemptCount
) {}
```

实际实现可使用普通可变类，持久化时写入 `CompoundTag`。至少应保存：

- 稳定飞行 UUID、维度、武器 ID；
- 当前位置、速度、发射点、目标点和已飞距离；
- 当前 `xRot`、`yRot` 和历史峰值飞行速度；当前积分器保留传入的旋转值，既不用它们决定推力方向，也不在虚拟 Tick 内按速度回写；
- 有效飞行 Tick、剩余 `life` 和 `secondPulseStartTick`；后者当前只透传保存，未参与积分；
- MAIN/TERMINAL 制导相位与必要的惯导记忆；
- 目标实体 UUID 和最后已知位置/速度；
- 恢复状态、等待时间、失败次数；
- 积分实现 ID 和状态版本，保证后续替换实现时不会静默改变在途导弹算法；
- 配置快照版本或必要的关键参数快照。

不要只保存原实体 ID。实体 ID 在重建、重启和客户端追踪变化后都可能改变；UUID 才是服务器级稳定主键。

### 5.1 哪些运行状态必须恢复

实体重建不能只恢复位置和速度，否则会产生重复点火、引信重置或制导相位倒退。至少需要恢复：

- `life` 与 `getFlightTickCount()` 对应的有效飞行时钟；
- `flightDistance`；
- `targetPos`、`lastGuidancePos`、目标 UUID；
- `guidancePhaseState`；
- 主发动机的点火 Tick 和燃烧时间来自冻结的 `RVP_VirtualTrajectoryParameters`；`secondPulseStartTick` 目前仅在状态中保留，第二脉冲尚未实装；
- `activeRadarOn`、`activeRadarCatch` 及必要的丢失计时；
- GPS 偏移、巡航垂直重置、Top Attack 顶点状态；
- 子母弹、延时引信等资格检查所需状态。

给 `RVP_BaseBullet` 增加明确的内部快照接口，而不是由管理器直接修改大量 protected 字段：

```java
RVP_VirtualMissileSnapshot createVirtualMidcourseSnapshot();
void restoreFromVirtualMidcourseSnapshot(RVP_VirtualMissileSnapshot snapshot);
```

该接口只在 `RVP_MissileEntity` 上启用，普通 Bullet、Rocket、Bomb 第一版不接入。

## 6. 进入虚拟态

### 6.1 静态资格检查

`RVP_VirtualMissileEligibility` 在武器加载或首次发射时检查：

1. `virtual_midcourse_data.enabled == true`；
2. 实体类型为 RVP 类型化 `RVP_MissileEntity`；
3. 主制导为 GPS、INS 或允许的预设中段类型；
4. 存在稳定 `targetPos`；
5. 不包含 HITL、SACLOS、MCLOS、线导等实时操控能力；
6. 不包含虚拟期间必须触发的 proximity、bounce、沿途 dispenser 或 submunition 行为；
7. 不要求沿途 terrain-following 或方块射线；
8. 发射维度和目标维度相同。

不满足时只记录一次稳定原因码，例如 `INELIGIBLE_HITL`、`INELIGIBLE_NO_TARGET_POS`，并保持真实实体飞行。

### 6.2 动态进入条件

在 `RVP_BaseBullet.tick()` 的服务端流程中，在完成本 Tick 运动、移动后路径提交以及引信处理后检查虚拟化，确保本 Tick 状态已经完整结算。进入条件全部满足才转换：

```text
isAlive
&& !isWaitingForChunk
&& guidancePhase == MAIN
&& flightTick >= entry_min_flight_tick
&& distanceFromLaunch >= entry_min_distance_from_launch
&& distanceToTarget >= entry_min_target_distance
&& targetPos finite
&& velocity finite and non-zero
&& manager does not already contain flightUuid
```

进入流程必须保持原子性：

1. 从实体创建完整快照。
2. 调用管理器注册，注册成功后才移除实体。
3. 从 `RVP_ChunkPathLoadManager` 释放实体路径状态。
4. 用专用转换原因移除实体，禁止触发爆炸、寿命结束或命中逻辑。
5. 广播 `REAL_TO_VIRTUAL` 状态，清理客户端实体摄像机和实体 ID 引用。

不要直接复用普通 `discard()` 作为唯一语义。应在 RVP 内增加 `virtualizing` 标志，确保 `remove()`、生命周期日志和 HITL 退出包能够区分“正常消失”与“进入虚拟中段”。

## 7. 虚拟中段积分

### 7.1 第一阶段决策：复制 RVP 运动积分、独立 G 值转向并用接口隔离

第一阶段不改造真实实体弹体的运行链，也不尝试把实体态和虚拟态立即合并为一个共享内核。实现方式是：把当前 RVP 虚拟中段需要的运动积分方法复制到 `org.ywzj.rvp.virtualflight.trajectory` 包，移除其中对 `Entity`、`Level`、同步数据和世界查询的依赖；制导侧改为“生成期望方向 + 独立最大 G 值钳制”，然后由 `RVP_RvpTrajectoryIntegrator` 实现稳定的 `RVP_VirtualTrajectoryIntegrator` 接口。

```text
当前真实 RVP Missile
  -> 继续调用现有 RVP_GuidanceRuntimeMath / RVP_ProjectileMotion

服务器虚拟管理器
  -> 只依赖 RVP_VirtualTrajectoryIntegrator
       -> 第一阶段注入 RVP_RvpTrajectoryIntegrator
            -> 独立 applySteering G 值钳制与 GPS 高度闭环
            -> 当前实现的主推力/阻力/重力/速度积分
       -> 后续可替换为其他实现
```

这样做的目的：

- 第一阶段改动范围可控，不同时重构正在运行的真实弹体链；
- 虚拟弹道保留当前 RVP 的动力学预期，同时由明确的 `virtual_midcourse_maxg` 约束机动能力；
- 管理器、SavedData、恢复 Ticket 和网络协议不感知具体积分方法；
- 后续若抽取真正共享内核，只需新增接口实现并完成状态迁移，不必重写虚拟飞行基础设施。

复制只发生在 RVP 子模组内，不修改 `ywzj_vehicle` 本体源码。禁止构造未加入世界的 `RVP_MissileEntity` 来调用实体方法；复制后的实现必须是无世界副作用的普通 Java 计算。

### 7.2 可替换积分接口

当前接口采用不可变输入/输出，并显式声明实现 ID 与实现版本：

```java
public interface RVP_VirtualTrajectoryIntegrator {
    String implementationId();

    int implementationVersion();

    RVP_VirtualTrajectoryResult step(
            RVP_VirtualTrajectoryState state,
            RVP_VirtualGuidanceInput guidance,
            RVP_VirtualTrajectoryParameters parameters);
}
```

第一阶段实现：

```java
public final class RVP_RvpTrajectoryIntegrator
        implements RVP_VirtualTrajectoryIntegrator {
    public static final String ID = "rvp_current";
    public static final int VERSION = 3;
}
```

各数据对象职责：

- `RVP_VirtualTrajectoryState`：`position`、`velocity`、`xRot`、`yRot`、`peakFlightSpeed`、`flightDistance`、`flightTick`、`remainingLife` 和 `secondPulseStartTick`；
- `RVP_VirtualGuidanceInput`：第一阶段仅包含 `fixedTargetPosition`；
- `RVP_VirtualTrajectoryParameters`：包含 `maxGs`、`cruiseAltitude`、推进开关、质量、推力、燃烧时间、点火 Tick、阻力、高度阻力因子、重力及速度上下限等冻结参数；不包含 `turningFactor` 和 `levelingFactor`。`rotateToMotion`、`cruiseStartTick` 和 `cruiseEndHorizontalDistance` 虽已在参数 record 中，当前积分器尚未读取；
- 当前没有 `RVP_VirtualEnvironmentSnapshot`；管理器在每次积分前以当前位置 Y 调用 `RVP_VirtualTrajectoryParameters.from(...)`，高度阻力因子在该次参数中冻结；
- `RVP_VirtualTrajectoryResult`：只包含新状态、`turnAngleRadians` 和 `invalid`。

管理器当前在静态字段中默认创建 `new RVP_RvpTrajectoryIntegrator()`，Tick 中只通过 `RVP_VirtualTrajectoryIntegrator` 接口调用。`installIntegrator(...)` 可替换实现，但 `lastKnownActiveCount > 0` 时会抛出 `IllegalStateException`，防止在途导弹静默换算法。当前全部支持虚拟化的导弹共用默认实现 `rvp_current`，不按武器 ID 选择实现。

虚拟状态必须保存 `implementationId` 和与 `implementationVersion()` 对应的版本值。替换实现时只能在明确兼容的状态版本间继续飞行；不兼容记录应保持旧实现到航程结束，或通过显式状态升级器转换，不能把运行中的导弹静默切换到不同算法。

### 7.3 虚拟积分的统一 G 值转向模型

`RVP_RvpTrajectoryIntegrator` 在主推力、阻力和重力计算后，用 `virtual_midcourse_maxg` 独立约束制导转向。GPS 高度闭环生成期望方向，并统一调用：

```java
Vec3 applySteering(Vec3 velocity, Vec3 desiredDir, double maxGs)
```

该方法保持当前速率不变，并把单 Tick 速度向量变化量限制为 `maxGs × PhysicsEngine.G`。实现把允许的速度弦长换算成最大转角，再对当前单位方向和期望单位方向做球面插值：

```text
speed       = length(velocity)
maxDeltaV   = maxGs × PhysicsEngine.G
maxTurn     = 2 × asin(clamp(maxDeltaV / (2 × speed), 0, 1))
actualTurn  = min(angle(currentDir, desiredDir), maxTurn)
newVelocity = slerp(currentDir, desiredDir, actualTurn) × speed
```

因此 `applySteering` 同时满足：结果速率等于调用时输入速率、该次转向产生的速度向量变化量不超过最大 G 值、期望方向较近时不会过度转向。由于推力、阻力和重力在此前已改变速度，不能用整个 Tick 的 `nextVelocity - previousVelocity` 直接断言其不超过 `maxGs × G`。0 G、零速或无效期望方向保持调用时的输入速度；反向向量使用确定性的正交轴处理，避免球面插值奇点。

GPS 巡航先由水平制导和高度闭环合成候选方向：

```text
hDes     = normalize(target.x - pos.x, 0, target.z - pos.z)
height   = cruise_altitude（有配置）否则当前虚拟位置 Y
vertCmd  = clamp((height - pos.y) × 0.015 - velocity.y × 0.05,
                 -speed × 0.5, speed × 0.5)
cruiseDir      = normalize(hDes.x, vertCmd / speed, hDes.z)
cruiseVelocity = applySteering(velocity, cruiseDir, virtual_midcourse_maxg)
```

候选方向不能直接执行。积分器先评估“本 Tick 继续追踪巡航高度后，目标点是否仍处于当前 `maxGs` 的可接入区域”。设速率为 `speed`，单 Tick 最大速度弦长为 `maxDeltaV`，则离散转向对应的最小转弯半径为：

```text
maxDeltaV = virtual_midcourse_maxg × PhysicsEngine.G
radius    = speed² / maxDeltaV
```

把候选位置、候选速度方向和固定目标点张成的平面作为二维转弯平面。目标相对候选速度方向的前向距离为 `forward`，横向距离为 `lateral`。不绕回目标后方的最短接入路线由“最大曲率圆弧 + 切线”组成，所需最小前向距离为：

```text
minForward = lateral >= radius
    ? radius
    : sqrt(lateral × (2 × radius - lateral))

reachable = forward >= minForward + reserveTicks × speed
```

当前实现使用 `reserveTicks = 2`。在距目标不超过 `speed × (reserveTicks + 1)` 时，会跳过巡航高度候选，直接追踪三维目标，避免最后数 Tick 在巡航与末端路线间摆动。其余情况只有 `reachable = true` 时才采用 `cruiseVelocity`；否则立即用固定目标相对向量调用 `applySteering`。若水平目标差近乎为零，也直接追踪三维目标。

`canReachTarget(...)` 的边界行为也与代码一致：目标已在一个 Tick 航程内时直接视为可达；目标不在当前速度前方时视为不可达；`maxGs` 非正或非有限时，只有正前方且基本共线的目标才可达；当 `maxGs × G >= 2 × speed` 时，前方目标直接视为可达。

当前 `step(...)` 每 Tick 都调用 `steerGpsCruise(...)`，没有使用 `cruiseStartTick` 或 `cruiseEndHorizontalDistance` 切换巡航阶段。`max_guidance_angle`、`projectile_data.turning_factor`、`guidance_data.cruise_leveling_factor` 和本体 `max_g` 均不参与虚拟积分。`steerGpsCruise(...)` 当前仍接收 `mass`，但质量补偿项已被注释，该参数不影响高度控制结果。

### 7.4 复制的运动积分范围

`RVP_RvpTrajectoryIntegrator` 当前实装的动力学范围为：

- `tick = flightTick + 1`，仅在 `tick >= ignitionTick` 时执行推力、阻力和重力分支；
- 当 `propulsion == true` 且 `tick - ignitionTick <= motorBurnTime` 时，以 `thrust / max(mass, 1.0E-6)` 作为加速度，沿当前速度单位方向施加主推力；零速时使用世界 `+Y` 方向兜底；
- 阻力为 `dragCoefficient × altitudeDragFactor`，速度修正为 `-normalize(velocity) × drag × speed²`；
- `gravity != 0` 时直接向 Y 速度加上配置值；`gravity == 0` 时改为减去 `PhysicsEngine.G`；
- 最低/最高速度钳制；
- `position += velocity`；
- `peakFlightSpeed = max(oldPeakFlightSpeed, speed)`、`flightDistance += speed`、`flightTick = tick`、`remainingLife -= 1`；
- 输出位置、速度、旋转值、峰值速度或累计航程中任一出现 NaN/Infinity 时设置 `invalid = true`。

当前尚未实装第二脉冲、GPS 专用重力缩放或按速度更新 `xRot/yRot`。`secondPulseStartTick` 仅原样写入下一状态，`rotateToMotion` 当前未读取。高度阻力因子由管理器在每 Tick 构造参数时按当前高度重新解析，积分器本身只消费该冻结值。

不得复制或调用以下世界相关行为：

- `targetEntity` 查找、雷达/红外扫描、LOS 和干扰判定；
- `isInWater()`、载具 OBB 和冷发射载机轴向查询；
- Chunk Ticket、方块/实体碰撞、近炸和爆炸；
- SynchedEntityData、粒子、声音、网络包和客户端状态。

需要这些信息的分支必须在进入虚拟态前完成并快照化，或由资格检查拒绝虚拟化。例如虚拟态只接受已经解析好的 GPS 目标点；GPS 散布在发射时采样一次，积分器不得重新使用随机数。

### 7.5 每 Tick 执行顺序

当前 `step(...)` 的实际计算顺序如下：

```text
1. 令 `tick = state.flightTick + 1`，读取当前速度
2. 若已达点火 Tick，在主发动机包含结束 Tick 的燃烧窗口内沿当前速度方向施加推力
3. 仍在已点火分支内，先应用二次阻力，再应用配置重力或默认重力
4. 以动力学处理后的速度进入 `steerGpsCruise(...)`
5. 近目标或水平目标差为零时，直接对三维目标方向执行 `applySteering`
6. 否则由水平目标方向和高度 PD 闭环生成巡航候选，执行一次 `applySteering`
7. 用候选位置、候选速度和 `maxGs` 评估目标可达性；不可达时改为从同一动力学后速度直接转向目标
8. 记录动力学后速度与制导后速度的夹角 `turnAngleRadians`
9. `xRot` 和 `yRot` 原样保留，不按制导方向更新
10. 钳制最低/最高速度；若 `maxSpeed > 0 && minSpeed > maxSpeed`，则先把最低速度视为 0
11. `position += velocity`，更新峰值速度、累计航程、飞行 Tick 和剩余寿命
12. 原样透传 `secondPulseStartTick`，检查输出状态有限性并返回 `RVP_VirtualTrajectoryResult`
```

真实实体仍按现有路径执行移动前区块门控、`tickHit()`、近炸、子母弹、粒子和网络同步。虚拟积分器没有这些步骤，也不能以插值结果结算命中。

### 7.6 复制算法的同步治理

第一阶段采用复制而非共享调用，必须显式治理实体算法与虚拟副本漂移：

1. 当前类 JavaDoc 只声明“从 RVP 实体制导/运动链抽出”及其纯计算边界；后续若继续复制实体算法，应补充具体来源方法和同步基线。
2. 为 `applySteering` 建立速率保持、G 值上限、零 G、充足 G 和反向向量测试。
3. 修改高度闭环、G 值换算、推力、阻力、重力或速度钳制时，代码评审清单必须要求同步检查虚拟实现；未来实装第二脉冲时再将其纳入同步范围。
4. `implementationId + implementationVersion` 写入 SavedData 和调试日志，便于定位算法版本。
5. 未来替换为共享内核时新增实现，例如 `rvp_shared_v2`，先通过等价测试再迁移；管理器接口保持不变。

这里的“支持轻松替换”指替换 `RVP_VirtualTrajectoryIntegrator` 实现，不是允许运行时随意热切换一枚正在飞行的导弹。

### 7.7 第一版采用逐服务器 Tick 积分

虚拟态不等于跳过时间。第一版管理器每个 `ServerTick START` 对每条记录调用一次积分接口，但不调用：

- `Level#getChunk`、`hasChunkAt` 或方块射线；
- 实体查找、AABB 碰撞、伤害或爆炸；
- 粒子、声音和普通实体同步；
- `RVP_ChunkPathLoadManager` 的沿途路径 Ticket。

每 Tick 只做当前 `rvp_current` 的纯制导/动力学计算。等行为稳定后，可在远离点火窗口和恢复阈值时进行 5～20 Tick 批量调度，但内部仍应逐逻辑 Tick 调用同一 `step(...)`；禁止用一条直线乘时间直接跳过多个 Tick。

到达 `life < 0`、出现非有限坐标或越过世界坐标安全边界时，服务端管理器终止记录且不爆炸。虚拟中段不执行雷达扫描、红外扫描或光学 LOS；第一版只为固定 GPS/惯导目标生成 `RVP_VirtualGuidanceInput`，真实末制导在恢复实体后启用。

### 7.8 移动目标

第一版 `FIXED_SNAPSHOT` 只使用进入虚拟态时的目标位置。适用于 GPS 点和固定目标。

第二阶段可增加 `RVP_VirtualMissileDatalinkService`：由已加载的载机、外部雷达或目标追踪服务按 UUID 更新 `targetPosition` 和 `lastKnownTargetVelocity`。若目标未加载，不得为找目标而同步加载其区块；维持最后位置惯导，超过数据时效后按配置进入失链策略。

### 7.9 当前实现风险项（已解决）

下表描述当前代码已存在的风险，不把尚未实施的远期功能当作已解决能力。

| 编号 | 等级 | 当前实现 | 具体风险 | 收敛要求 |
| --- | --- | --- | --- | --- |
| R-01 | 高 | `flightSpeed` 恢复为虚拟段历史峰值，而实体制导使用 `max(flightSpeed, currentVelocity.length())` | 若虚拟段末尾已因阻力减速，恢复后首次成功制导可把速率拉回历史峰值，形成非物理瞬时加速 | 区分“当前制导基准速率”和“历史峰值统计”，恢复时不得用历史峰值覆盖当前速率 |
| R-02 | 中 | 每次积分只替换快照中的 `trajectory`，制导相位、雷达开关/捕获状态、丢失计时、Top Attack 状态等保留进入虚拟态时的值 | 虚拟飞行经过大量 Tick 后，恢复的子系统时钟和记忆可与 `flightTick`、当前位置不一致 | 逐字段定义“虚拟期间冻结”、“继续计时”或“恢复时重算”语义，不能统一原样写回 |
| R-03 | 中 | `cruiseStartTick`、`cruiseEndHorizontalDistance` 和 `rotateToMotion` 已进入参数对象，但积分器不读取 | JSON 表面上可配置的巡航切换和朝向行为对虚拟段无效，真实段/虚拟段轨迹语义不一致 | 字段`rotateToMotion`语义是让弹体模型方向朝向速度方向，虚拟积分不需要这个字段，仅记录然后原样恢复即可。从虚拟参数契约中删除`cruiseStartTick`、`cruiseEndHorizontalDistance` |

#### 7.9.1 `xRot/yRot` 不参与虚拟飞行的恢复影响

当前姿态数据的实际流转如下：

```text
进入虚拟态
  -> createVirtualTrajectoryState() 记录当时 xRot/yRot
  -> RVP_RvpTrajectoryIntegrator.step() 只改变 velocity，每 Tick 原样透传 xRot/yRot
  -> restoreEntity() 用旧 xRot/yRot 构造 AimRot 并执行 initFromWeapon(...)
  -> restoreFromVirtualMidcourseSnapshot() 再把旧 xRot/yRot 写回实体
  -> xRotO/yRotO 也被设为同一旧值
  -> addFreshEntity() 后的出生包把该姿态发给客户端
```

因此，虚拟段只要发生了转向，恢复时就会出现：

```text
authoritative motion direction = normalize(trajectory.velocity)
restored body/look direction   = directionFromRotation(old xRot, old yRot)
attitude error                 = angle(motion direction, body/look direction)
```

该误差上限接近 180°，且虚拟飞行时间越长不代表误差会自动收敛。但影响必须按具体调用链区分：

- **恢复瞬间的位置和速度不会因旧姿态自动改写。** `restoreVirtualTrajectoryState(...)` 分别写入 `setDeltaMovement(state.velocity())` 和旋转值；安全点搜索也沿速度反向进行。当前导弹的实体 AABB 不随 yaw/pitch 旋转，直接命中检查也沿运动线段而不是弹体视线；因此两者不是旧姿态的主要风险点。
- **普通 GPS/惯导转向不一定在首 Tick 被旧姿态拒绝。** `passesGuidanceAngle(...)` 优先用 `getDeltaMovement()` 而不是 `getLookAngle()` 作为导引轴。制导成功时，`applyGuidanceFacing(...)` 会在 `tickMotion()` 前把姿态对齐制导后速度。
- **`rotate_to_motion=true` 的推进导弹有额外自愈路径。** `tickMissileMove(...)` 在取 `getLookAngle()` 作为推力方向前，会先按当前速度对齐姿态。因此该分支通常不会把恢复的首次推力施加到旧朝向。
- **末制导导引头的首次搜索是确定风险。** 雷达/红外搜索与 `passesAcquireLimits(...)` 直接使用 `getLookAngle()` 判断 `maxLockHalfAngle`；它们可在姿态被普通制导纠正前运行。旧姿态可使原本位于弹头前方的目标落到搜索锥外。若此时又没有成功的惯导兜底来更新姿态，后续搜索可继续使用错误朝向，不只是一 Tick 的视觉问题。
- **`rotate_to_motion=false` 且当 Tick 制导失败时存在实际动力学风险。** 真实实体的主推力沿 `getLookAngle()` 施加。若恢复后未能通过制导或 `rotate_to_motion` 先纠正姿态，推力会沿虚拟化入口的旧方向叠加到当前速度，造成恢复点轨迹折线；只要制导持续失败且导弹仍有目标记忆，该偏差就可能持续多 Tick。
- **近炸的无锁定目标扫描可能偏位。** `tickProximityFuse()` 用 `getLookAngle() × -radius` 后移检测盒，旧姿态会使该检测盒不再位于真实运动方向后方，可能漏检或扫到错误一侧的实体。已锁定目标的 `boundingBox.inflate(radius).contains(position())` 分支不依赖姿态。
- **客户端会先看到旧朝向。** 出生包显式携带 `xRot/yRot`，并把 `xRotO/yRotO` 初始化为相同值。弹体模型、战术地图朝向、本地尾迹起点都可在服务端下一次姿态同步前指向虚拟化入口方向，随后发生跳变或平滑器追赶。HITL 虽已被虚拟化资格检查拒绝，但这一视觉风险仍适用于普通导弹渲染。

结论：**当前不更新 `xRot/yRot` 不会立即破坏恢复位置和速度，但会创造“运动向量正确、弹体视线错误”的不一致状态。该状态在成功普通制导或 `rotate_to_motion=true` 时可以很快自愈，但在导引头首次搜索、制导失败且不随动姿态、近炸扫描以及出生渲染中会转化为真实的战斗或表现问题。**

#### 7.9.2 姿态风险的建议修复顺序

1. **P0：恢复前按最终速度对齐姿态。** 在 `restoreFromVirtualMidcourseSnapshot(...)` 之后、安全点搜索和 `addFreshEntity(...)` 之前，若速度非零，使用与实体制导相同的旋转换算对齐 `xRot/yRot`，并同时设置 `xRotO/yRotO`。修正必须放在快照恢复之后，否则会被旧快照再次覆盖。零速时可保留旧姿态。
2. **P1：虚拟状态每 Tick 派生姿态。** 在速度钳制后由最终 `velocity` 计算下一状态的 `xRot/yRot`，使 SavedData、调试日志和未来 Contact 快照也保持一致。姿态仅是权威速度的派生值，不应反向参与当前虚拟推力或转向计算。
3. **P1：增加恢复专项测试。** 构造虚拟段转向 90° 以上的记录，分别覆盖 `rotate_to_motion=true/false`、制导成功/失败、ARH/IR 首次捕获、主发动机燃烧中恢复、近炸检测盒和出生包朝向。
4. **P2：增加恢复连续性日志。** 记录恢复前速度方向、恢复姿态方向、夹角误差，以及恢复后第 1～5 Tick 的速度、推力方向、导引头搜索结果和姿态收敛时间。

姿态恢复验收门应至少满足：

- 非零速度恢复时，弹体视线与权威速度夹角小于浮点容差；
- 恢复首 Tick 不因旧姿态导致末制导目标落出原本应通过的搜索锥；
- `rotate_to_motion=false` 时，恢复首次推力方向不得回到虚拟化入口朝向；
- 出生包中的 `xRot/yRot` 与服务端恢复姿态一致，客户端不出现由旧姿态引起的瞬时反向或大角度跳变。

## 8. 末段恢复与区块预热

### 8.1 恢复触发距离

实际触发距离取：

```text
restoreTriggerDistance = max(
    restore_target_distance,
    horizontalSpeed * restore_lead_tick)
```

这样高速导弹也能留下足够的异步区块晋级时间。恢复点不应直接放在目标中心，而应位于当前虚拟位置沿弹道向前、且距离目标仍有完整末制导空间的位置。

### 8.2 专用恢复 Ticket

虚拟记录没有实体 ID，不能直接复用以实体为状态主体的 `RVP_ChunkPathLoadManager`。新增专用临时 Ticket：

```java
TicketType<UUID> RVP_VIRTUAL_MISSILE_RESTORE = TicketType.create(
        "rvp_virtual_missile_restore",
        Comparator.comparing(UUID::toString),
        40);
```

实际 timeout 需根据 Forge 1.20.1 API 编译验证。Ticket 键使用 `flightUuid`，Ticket level 与现有可进入 entity-ticking 的参数保持一致。管理器每 Tick 刷新恢复点半径内的有限区块，并纳入服务器级全局恢复预算，例如：

| 参数 | 初始值 |
| --- | ---: |
| 每 Tick 新进入恢复阶段的导弹 | 4 |
| 每 Tick新增恢复中心区块 | 16 |
| 单导弹恢复半径 | 1（3×3） |
| 恢复 Ticket 租约 | 40 Tick |
| 最大等待 | 200 Tick |

恢复检查只使用无同步加载副作用的查询：

```text
level.hasChunkAt(probe)
level.isPositionEntityTicking(probe)
```

至少确认恢复点所在区块和恢复后第 1 Tick 运动终点区块均已 ready。更稳妥的实现是用 `RVP_ChunkPathLoader.collectSupercoverChunks()` 计算恢复后 5 Tick 短路径，但只对预算批准的连续前缀加票。

### 8.3 实体重建

区块 ready 后：

1. 重新解析 `weaponId`，确认仍为当前 schema 的有效 RVP Missile 配置。
2. 创建 RVP 类型化 `RVP_MissileEntity`。
3. 在 `addFreshEntity` 前设置 `flightUuid`。若同 UUID 实体仍存在则拒绝恢复并记录冲突。
4. 通过专用恢复接口写回位置、速度、目标、寿命、飞行 Tick、制导相位和发动机状态。
5. 调用 `level.addFreshEntity(entity)`。
6. 成功后调用 `primeDynamicChunkPath()`，让正常 5 Tick 路径加载接管。
7. 至少保留恢复 Ticket 20～40 Tick，覆盖实体重新进入常规 Tick 的交接窗口。
8. 从虚拟管理器删除记录，并广播新实体 ID 与稳定 UUID 的映射。

若 `addFreshEntity` 失败，记录继续保持 `RESTORE_WAITING_CHUNKS`，有限次数重试。达到次数或等待上限后安全删除虚拟记录，不在未加载世界中生成爆炸。

### 8.4 恢复点碰撞安全

虚拟中段没有沿途碰撞，因此恢复时必须避免在方块内部直接生成：

- 第一版以高于普通地形的巡航高度为主要安全前提；
- 区块 ready 后、生成前允许在恢复点附近做一次有限范围方块碰撞检查；
- 若包围盒被阻挡，沿虚拟速度反方向或竖直方向搜索不超过 32 格的安全点；
- 找不到安全点则延后恢复或安全终止，禁止把实体硬塞进方块后立刻误爆。

该检查发生在已经异步加载完成的恢复区块中，不会触发同步生成。

## 9. 碰撞、引信与战斗语义

虚拟态不能执行真实世界碰撞，因此必须采用“资格否决 + 末段真实结算”，不能假装沿途碰撞不存在仍支持所有载荷。

### 9.1 虚拟期间允许推进

- `remainingLife` 和有效飞行 Tick；
- 主发动机点火/燃烧时间窗；`secondPulseStartTick` 当前只随状态透传，不会触发第二脉冲推力；
- 总航程累计；
- 固定目标的惯性/GPS 巡航；
- 末段恢复条件。

### 9.2 虚拟期间禁止触发

- 方块或实体命中；
- proximity fuse；
- bounce fuse；
- 基于沿途高度/地形的引信；
- 子母弹释放、dispenser 布撒；
- 雷达/红外真实扫描、干扰弹判定和 LOS；
- 爆炸与伤害。

若某武器的触发时间可能落在虚拟区间内，资格检查应拒绝虚拟化；后续若要支持定时子母弹，必须先设计“在释放点预热区块并提前恢复实体”的独立流程。

### 9.3 目标消失

- GPS 固定点：继续飞向固定坐标。
- 目标实体 + `FIXED_SNAPSHOT`：继续飞向最后位置，恢复后由末制导按正常规则重捕获或失锁。
- 数据链模式：超过更新时效后转惯导；不得为查询目标主动加载目标沿途区块。
- 武器数据热重载后已不存在：安全终止记录，并输出 `WEAPON_DATA_MISSING`。

## 10. 生命周期、持久化与网络

### 10.1 SavedData

`RVP_VirtualMissileSavedData` 按维度保存虚拟记录。建议每次增删记录、改变阶段或每 20 Tick 批量标脏，避免每 Tick 强制磁盘写入。

加载时执行：

1. 校验 schema version、UUID、维度、坐标有限性和武器 ID。
2. 根据世界当前 gameTime 计算停服期间策略。第一版建议停服期间不推进飞行时间。
3. 清除超过 `max_virtual_flight_tick` 或 `remainingLife < 0` 的记录。
4. 恢复为 `VIRTUAL_CRUISE` 或 `RESTORE_REQUESTED`，绝不在加载 SavedData 时同步加载区块。

### 10.2 网络表现

虚拟态没有普通实体，原有 `RVP_ExtendedAirEntityBroadcastService` 无法直接广播它。网络表现必须遵守第 3.3 节的单向只读协议，可按玩法需求选择：

- 最低实现：不向客户端显示虚拟导弹，只保留服务端权威记录和服务器日志；战术地图也不直接读取服务端 Map。
- 推荐实现：由服务端 `RVP_VirtualMissileSyncService` 生成最小 `RVP_VirtualMissileContact`，通过低频 `S2CVirtualMissileContacts` 每 10～20 Tick 发给有权限的玩家；客户端缓存只画战术地图图标，不创建假实体。

禁止在客户端生成参与碰撞或摄像机的“镜像导弹实体”。HITL 已被资格检查排除，因此虚拟态不应维持导弹摄像机。

恢复实体后广播：

```text
flightUuid
newEntityId
position
velocity
phase=REAL_TERMINAL
```

所有跨阶段 UI、战术地图和日志关联使用 UUID，不缓存旧实体 ID。

## 11. 与现有系统的接入点

| 现有类 | 建议改动 |
| --- | --- |
| `RVP_WeaponData` | 增加 `virtual_midcourse_data` 当前 schema 字段和 getter |
| `RVP_VirtualMidcourseData` | 新数据类；每个字段写完整 JavaDoc |
| `RVP_ProjectileData` | 保持实体态参数职责；`turning_factor` 不传入虚拟积分参数 |
| `RVP_BaseBullet` | 提供快照/恢复接口、有效飞行 Tick 恢复接口和虚拟转换移除标志 |
| `RVP_MissileEntity` | 第一阶段保留现有实体积分链；服务端运动结算后调用虚拟资格检查，重建后恢复 Missile/雷达相关状态 |
| `RVP_GuidanceRuntimeMath` | 保持实体行为；其 `turningFactor` 转向与虚拟积分的独立 G 值转向互不调用 |
| `RVP_ProjectileMotion` | 第一阶段保持实体行为；当前虚拟积分器只实装主推力、阻力、重力和速度钳制子集，尚未包含第二脉冲或 GPS 专用重力缩放 |
| `RVP_VirtualTrajectoryIntegrator` | 新增稳定的可替换积分接口；管理器只依赖此接口 |
| `RVP_RvpTrajectoryIntegrator` | `rvp_current` / `VERSION = 3` 纯弹道实现；先执行已点火动力学，再以 `applySteering` 按 `virtual_midcourse_maxg` 钳制全程 GPS 高度闭环，用最小转弯半径评估目标可达性；旋转值原样保留 |
| `RVP_VirtualMissileManager` | 仅服务端持有权威记录，当前以固定目标快照调用已安装的积分接口，每 Tick 按当前高度重建冻结参数 |
| `RVP_ChunkPathLoader` | 复用 supercover 算法检查恢复后的短路径，不增加同步加载 |
| `RVP_ChunkPathLoadManager` | 实体恢复成功后接管常规滚动路径；不要让虚拟记录混入实体租约 Map |
| `RVP_ProjectileSpawner` | 抽取可供恢复服务复用的 RVP 类型化导弹构造与初始化逻辑 |
| `RVP_ProjectileLifecycleDebug` | 增加虚拟化、恢复请求、恢复成功/失败事件 |
| `RVP_ExtendedAirEntityBroadcastService` | 不负责虚拟推进；可与虚拟 Contact 包共享权限过滤 |
| `RVP_VirtualMissileSyncService` | 服务端把权威记录投影成最小 Contact，并按权限发送 S2C 消息 |
| 客户端 Contact Cache/战术地图 | 只消费 UUID Contact 和恢复映射，不访问服务端状态，不依赖旧实体 ID |

必须继续遵守：

- 不在实体或管理器中按 `weaponId.getPath().equals(...)` 区分弹种；
- 不修改 `ywzj_vehicle`；
- 不在 Java 中维护 Bedrock 模型 ID 白名单；
- 恢复后的弹体仍使用 RVP 类型化实体和 Renderer；
- 不写旧版 JSON 迁移兼容代码。

## 12. 日志与可观测性

建议生命周期事件：

```text
VIRTUAL_ENTRY_ELIGIBLE
VIRTUAL_ENTRY_REJECTED
REAL_TO_VIRTUAL
VIRTUAL_TICK_SUMMARY
VIRTUAL_TARGET_UPDATED
RESTORE_REQUESTED
RESTORE_CHUNK_WAITING
RESTORE_CHUNK_READY
VIRTUAL_TO_REAL
RESTORE_FAILED
VIRTUAL_EXPIRED
VIRTUAL_TERMINATED
```

单枚状态转换日志至少包含：

```text
flightUuid
oldEntityId/newEntityId
weaponId
dimension
phase
gameTime
flightTick
remainingLife
position
velocity
targetPosition
distanceToTarget
travelledDistance
restoreChunk
restoreChunkLoaded
restoreChunkEntityTicking
restoreTicketRefreshGameTime
restoreWaitTick
reason
```

服务器每 200 Tick 汇总：虚拟导弹数、进入数、恢复等待数、成功恢复数、超时数、无效记录数、积分器实现 ID、积分耗时、虚拟管理调度耗时和新增恢复 Ticket 数。逐 Tick 单弹日志只在 `projectilelife` 或专用调试开关开启时输出。

## 13. 测试方案

### 13.1 纯单元测试

`RVP_RvpTrajectoryIntegratorTest`：

- 直线 GPS 巡航位置、速度和距离累计；
- 负坐标、极大坐标和非有限输入；
- `applySteering` 在各方向夹角下保持输入速率；
- 对 `applySteering` 单独验证 `|steeredVelocity - steeringInputVelocity| <= virtual_midcourse_maxg × PhysicsEngine.G`；不把推力、阻力和重力产生的整 Tick 速度变化误算为转向过载；
- 0 G 保持原方向，充足 G 可在单 Tick 达到期望方向，反向向量结果有限且确定；
- `cruise_altitude` 高于/低于当前位置时分别产生受限爬升/下降指令，且不会瞬移到目标高度；
- 低 `maxGs` 场景会在目标进入最小转弯圆不可接入区域前结束巡航，并提前转入目标点；
- `cruise_altitude` 不可达时实际峰值/谷值向可达高度退化，同时轨迹仍进入目标点一个 Tick 航程内；
- 长航程场景在巡航稳定段接近设定高度，末端允许主动离开巡航高度以命中三维目标点；
- `life`、飞行 Tick、主发动机点火/燃烧窗口准确推进，并验证 `secondPulseStartTick` 当前仅透传；
- 跨过恢复距离阈值时只转换一次；
- 相同初始快照重复运行得到相同结果。

`RVP_RvpTrajectoryParityTest`：

- 对主发动机、点火前滑行、阻力、配置/默认重力和速度钳制建立黄金输入/输出；第二脉冲在实装前不得列为已复制行为；
- 虚拟转向不再与实体 `steerPursuit + turningFactor` 做数值等价断言，改为验证速率保持、G 值上限和闭环收敛契约；
- 逐 Tick 对比可共享的动力学状态，并单独记录虚拟态保留的 `xRot/yRot`、制导转角、转向步骤的 actualGs、航程和主发动机状态；
- 真实转虚拟及虚拟恢复真实的首个 Tick 不得重复积分或漏积分；
- 客户端插值器不参与该等价性测试，也不能影响任何权威结果。

`RVP_VirtualTrajectoryIntegratorContractTest`：

- 管理器可通过 `installIntegrator(...)` 安装测试积分器，Tick 调用链不依赖 `RVP_RvpTrajectoryIntegrator` 具体类型；
- `implementationId` 和 `implementationVersion` 正确写入/读取 SavedData；
- 不兼容状态版本拒绝静默切换；
- 替换积分实现不改变虚拟管理器、恢复 Ticket 或网络 Contact 行为。

`RVP_VirtualMissileEligibilityTest`：

- GPS/INS 合法配置允许；
- HITL、SACLOS、线导、沿途子母弹、缺目标点均拒绝；
- JSON 参数边界和默认值。

`RVP_VirtualMissileStateCodecTest`：

- NBT 往返后 UUID、位置、速度、寿命、相位和目标一致；
- schema version 不支持时安全拒绝；
- 缺字段和非法数值不会生成实体。

### 13.2 GameTest / 集成测试

1. 发射 GPS 导弹，在初段真实飞行后进入虚拟态，沿途区块不因导弹加载。
2. 距目标约 768 格时只加载有限恢复区块，ready 后恢复实体。
3. 恢复后第一 Tick 路径已经 ready，不出现 `TRACKING_END reason=<null>`。
4. 原实体 ID 消失，新实体 ID 产生，但 UUID 和战术地图轨迹连续。
5. 服务端保存退出并重启，虚拟记录恢复且不重复生成实体。
6. 目标区块生成很慢时，导弹停留在虚拟等待态，不在主线程同步加载。
7. 恢复超时后记录被安全删除，不发生远程爆炸。
8. 同时 50～200 枚虚拟导弹时，恢复预算有界且无饥饿。
9. 武器数据重载/删除、维度卸载、UUID 冲突均按原因码安全终止。

### 13.3 性能验收

建议采集三组对照：

| 场景 | 指标 |
| --- | --- |
| 20 枚、5000 格真实实体全程飞行 | Tick 耗时、加载区块峰值、Ticket 数、区块生成数 |
| 20 枚虚拟中段飞行 | 同上，并记录共享内核与虚拟管理调度耗时 |
| 100 枚虚拟导弹同时接近恢复点 | 恢复队列长度、最久等待、公平性和 TPS |

验收目标不是让恢复瞬时完成，而是保证：沿途不强加载；恢复请求受预算控制；服务器 Tick 无同步生成尖峰；恢复后末段仍由真实实体权威结算。

## 14. 分阶段实施顺序

### 阶段 A：固定 GPS 目标的最小闭环

1. 定义 `RVP_VirtualTrajectoryIntegrator`、不可变输入/输出、实现 ID 和状态版本契约。
2. 把当前 RVP 弹体运动积分复制并纯化为 `RVP_RvpTrajectoryIntegrator`，通过接口隔离具体实现。
3. 实现 `applySteering` 的速率保持、G 值上限和 θ 单元测试。
4. 增加 `RVP_VirtualMidcourseData.virtual_midcourse_maxg` 与可选 `cruise_altitude`；不复用本体 maxG 或实体 `turning_factor`。
5. 实现仅服务端的内存管理器，并通过接口注入 `rvp_current`；第一步不做持久化和客户端同步。
6. 只允许 GPS 固定点导弹进入虚拟态。
7. 实现恢复 Ticket、ready 检查和 RVP Missile 重建。
8. 完成实体态 → 虚拟态 → 实体态的单局闭环。

### 阶段 B：完整状态与可靠性

1. 补齐 life、制导相位、GPS/Top Attack 状态快照，并补全当前积分器尚缺失的第二脉冲等发动机状态语义。
2. 增加 SavedData、重启恢复和 UUID 冲突保护。
3. 接入生命周期日志、统计与调试命令。
4. 增加恢复失败重试、超时和公平预算。
5. 用高并发场景校准恢复距离、Ticket 半径和预算。
6. 对实体初段—虚拟中段—实体末段全程记录速度、`maxGs`、转向步骤的 `actualGs`、实际 θ 和转弯轨迹，并特别验证当前不更新 `xRot/yRot` 时的恢复朝向语义。
7. 完成第 7.9.2 节 P0 姿态对齐，并在 GPS + ARH 末制导和 `rotate_to_motion=false` 推进场景通过恢复专项测试。

### 阶段 C：玩法系统集成

1. 实现服务端 Contact 投影、S2C 消息、客户端只读缓存和战术地图实体 ID 重绑定。
2. 支持 GPS + ARH 末段重捕获。
3. 增加可选数据链目标更新。
4. 评估预设航路点和定时恢复后释放子母弹。

不要在阶段 A 同时支持移动目标、HITL、地形跟随和沿途载荷；这些功能会把“轻量数学记录”重新变成隐式世界实体系统，难以验证性能收益和战斗语义。

## 15. 推荐初始参数与最终决策

推荐首个验证武器使用 GPS 主制导、固定坐标目标和真实末段：

```text
真实初段最少时间       100 Tick（5 秒）
距发射点最少距离       800 格
进入时距目标最少距离   1600 格
虚拟最大法向过载       virtual_midcourse_maxg = 18 G
可选虚拟巡航高度       cruise_altitude = 320 世界 Y
第一阶段积分实现       rvp_current（implementationVersion=3）
目标恢复基础距离       768 格
恢复提前时间           60 Tick（3 秒）
恢复 Ticket 半径       1 区块
恢复等待上限           200 Tick（10 秒）
短期交接租约           40 Tick（2 秒）
虚拟更新间隔           1 Tick
```

最终采用“删除真实实体、服务器级记录通过可替换接口调用 RVP 复制积分器、目标前方异步恢复”的方案，而不是隐藏实体、关闭渲染或让实体在未加载区块中继续设置坐标。后几种方式仍然依赖实体 section、区块生命周期或客户端追踪，无法真正消除长航程的沿途加载成本。

第一版成功标准是：一枚 5000～20000 格 GPS 导弹只在发射区和目标末段产生真实实体与区块加载；中段只有轻量服务器记录；虚拟态按当前 `rvp_current` / `VERSION = 3` 顺序执行“已点火动力学 → GPS 高度闭环与可达性判断 → G 值转向 → 速度钳制 → 位置与时钟更新”；配置 `cruise_altitude` 时先按最小转弯半径评估目标可达性，可达则接近设定高度，不可达则直接追踪三维目标。位置和速度应在转换点连续；朝向字段目前在虚拟 Tick 中保留旧值，恢复时必须按实体接口明确验证，不再假定它已随速度同步。恢复后仍由 RVP 实体碰撞、引信、爆炸和动态路径 Ticket 体系完成权威结算。积分实现可在不改管理器的前提下替换。

在 R-01 的 P0 恢复姿态对齐完成前，GPS + ARH 末制导重捕获和 `rotate_to_motion=false` 且恢复时仍在燃烧的导弹，不应视为已通过虚拟中段生产验收。
