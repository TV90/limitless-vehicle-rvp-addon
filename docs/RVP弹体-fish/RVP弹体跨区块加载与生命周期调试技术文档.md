# RVP 弹体跨区块加载与生命周期调试技术文档

> 文档基线：`limitless-vehicle-rvp-addon` 提交链 `5a57ac9` → `abae7cf` → `65593d3` → `c245b77` → `aa84cb1` → `10d7b48` → `7e42772`。  
> 最终实现基线：`7e4277293c315769ba64607a3e306eb16b4a5ac0`。  
> 适用环境：Minecraft 1.20.1、Forge、Gradle。本文只描述 RVP Submod 内的实现，不包含合并分支上的其他提交，也不要求修改 `ywzj_vehicle` 本体。

## 1. 变更解决了什么问题

这组提交围绕同一个服务端问题建立了完整闭环：高速或远程 RVP 弹体离开玩家附近后，可能在进入尚未加载或尚未达到 `entity-ticking` 状态的区块时停止 Tick。由于 RVP 弹体不保存到区块，停止 Tick 后也不能依靠区块重新加载恢复正常飞行。

最终方案包含四层能力：

1. 用二维 supercover DDA 计算速度向量未来 5 Tick 穿过的全部水平区块，而不是只加载当前位置或固定距离前方。
2. 由服务器级管理器集中刷新精确 `ChunkPos` Ticket，并以全局预算、优先级和轮转机制控制新增请求。
3. 弹体移动前严格检查本 Tick 路径是否“已经获票、已经加载且允许实体 Tick”；未就绪时原地等待，并暂停飞行语义时钟。
4. 用生命周期日志、区块路径日志、Tick 耗时、实际平均速度和剩余距离定位仍可能出现的停止 Tick、追踪结束或加载落后。

此外，首个提交移除了弹体相对发射者的水平距离上限。原实现的 `checkShooterValid()` 会在水平距离平方达到 `3.38724E7` 后返回 `false`，现在只保留“必须存在发射载具或 owner”的校验，不再因飞行距离主动丢弃弹体。

## 2. 提交链概览

| 提交 | 日期 | 主要作用 |
| --- | --- | --- |
| `5a57ac9` | 2026-08-04 | 新增服务器权威的弹体全生命周期监测；在生成、Tick、命中、引信、伤害、爆炸、子弹药和移除路径埋点；取消相对发射者的飞行距离限制。 |
| `abae7cf` | 2026-08-04 | 新增纯 `ChunkPos` 路径规划器、无加载副作用的就绪判定，以及路径算法单元测试。 |
| `65593d3` | 2026-08-04 | 新增服务器级路径加载管理器、精确 `POST_TELEPORT` Ticket、全局新增请求预算、优先级、同级轮转和统计。 |
| `c245b77` | 2026-08-05 | 将非 Bullet 的 RVP 弹体接入动态路径；增加首 Tick 预热、制导前后双重移动门、等待状态和扣除等待时间的飞行 Tick。 |
| `aa84cb1` | 2026-08-05 | 生命周期日志支持 `zh_cn`/`en_us`；增加实际路径长度、真实耗时、平均速度、目标剩余直线距离和 Tick 分段耗时。 |
| `10d7b48` | 2026-08-05 | 调整控制器参数：全局新增预算 `32 → 64`、单实体上限 `64 → 128`、Ticket level `3 → 2`；补充弹体早退检查点和日志字段。 |
| `7e42772` | 2026-08-05 | 增加 30 Tick 驻留短租约，在弹体漏 Tick 后独立刷新最后位置与本 Tick 终点；补充移动后观察；区块统计日志改由生命周期调试开关统一门控。 |

这 7 个提交在 Git 中形成直接父子链。当前仓库 HEAD 可能已经通过合并包含其他功能，排查本功能时应以 `7e42772` 的文件内容为准，避免把并行分支改动误算进本方案。

## 3. 最终架构

### 3.1 组件职责

| 组件 | 职责 |
| --- | --- |
| `RVP_ChunkPathLoader` | 纯路径规划与就绪判定；自身不直接加载或生成区块。 |
| `RVP_ChunkPathLoadManager` | 按服务器保存提交、授权、租约和统计；在 `ServerTick START` 刷新 Ticket 并分配新增预算。 |
| `RVP_BaseBullet` | 提交路径、执行移动门、维护等待状态和飞行 Tick、移动后回报驻留状态。 |
| `RVP_BulletEntity` | 覆盖类型钩子并退出动态区块强加载，保持机枪/机炮弹丸的既有策略。 |
| `RVP_ProjectileSpawner` / `RVP_SubmunitionSpawner` | 实体成功加入世界后立即提交首个路径窗口。 |
| `RVP_ProjectileLifecycleDebug` | 记录服务端弹体生命周期、性能、制导和区块等待数据。 |
| `RVP_ProjectileLifecycleDebugEvents` | 接入 `EntityLeaveLevelEvent`，并在 `ServerTick END` 驱动 watchdog。 |

### 3.2 服务端时序

~~~text
弹体成功 addFreshEntity
  -> primeDynamicChunkPath()
  -> 提交初始未来 5 Tick 路径

下一次 ServerTick START
  -> RVP_ChunkPathLoadManager.processServerTick()
  -> 校验实体 UUID + entityId
  -> 刷新上一轮连续授权前缀
  -> 按优先级和轮转顺序分配最多 64 个新增区块
  -> addExactTicket(...)
  -> 刷新漏 Tick 实体的驻留短租约

弹体 tick
  -> super.tick()                         // 原生 tickCount 仍增长
  -> 等待态复查（WAITING_PROJECTILE）
  -> 制导前路径门（旧速度）
  -> 引信/子弹药/制导
  -> 制导后路径门（新速度）
  -> 命中检测
  -> 运动
  -> 从新位置提交下一轮滚动窗口
  -> recordPostMoveObservation()
  -> 其余引信、尾迹、线导与寿命逻辑
~~~

这里的关键是“提交”和“放行”分离：本 Tick 提交的路径要等下一次 `ServerTick START` 才可能获票；即使已经获票，也必须继续确认区块已加载且进入 `entity-ticking`。Ticket 是加载请求，不等于立即就绪。

## 4. 路径规划与就绪判定

### 4.1 预测窗口

`RVP_BaseBullet.PROJECTILE_CHUNK_HORIZON_TICKS` 最终为 `5`。预测水平距离为：

~~~text
sqrt(motion.x² + motion.z²) × 5
~~~

规划只关心 X/Z 区块；纯垂直或几乎静止的运动只保护当前区块。`horizonTicks <= 0` 按 1 处理，保证调用方不能绕过本 Tick 路径检查。

### 4.2 supercover DDA

`collectSupercoverChunks(...)` 使用二维 Amanatides-Woo DDA 遍历线段经过的区块：

- 结果从起点到终点有序且去重。
- 正负坐标都先 `floor` 再右移 4 位，符合 Minecraft 区块坐标语义。
- 精确穿过区块角点时，同时加入两个侧邻区块和对角区块。
- 达到区块数量上限时只保留连续前缀，不会跳到终点采样并留下中间缺口。
- 起点、速度或预测终点出现 `NaN`/Infinity 时采用拒绝移动的安全语义；能确定起点时至少保留起点区块。

### 4.3 完整窗口与本 Tick 窗口

一次 `requestProjectedPath(...)` 同时生成两条路径：

- `projectedChunks`：未来 5 Tick 的预加载窗口，用于提前请求。
- `currentTickChunks`：只覆盖当前 1 Tick 运动线段，用于决定本 Tick 能否移动。

完整窗口被 128 区块上限截断时，`projectedPathTruncated=true`。如果本 Tick 路径本身也未覆盖真实终点，则返回 `PATH_TRUNCATED` 并禁止移动。

### 4.4 就绪状态

`ChunkReadiness` 的最终状态如下：

| 状态 | 含义 | 是否允许移动 |
| --- | --- | --- |
| `READY` | 已获管理器授权、区块已加载、位置允许实体 Tick。 | 是，前提是本 Tick 路径全部为该状态。 |
| `NOT_REQUESTED` | 路径已提交，但该区块尚未获得全局预算授权。 | 否。 |
| `NOT_LOADED` | 已授权区块当前仍未加载。 | 否。 |
| `NOT_ENTITY_TICKING` | 区块已加载，但尚未晋级到实体 Tick 状态。 | 否。 |
| `PATH_TRUNCATED` | 连续规划前缀没有覆盖真实终点。 | 否。 |
| `INVALID_PATH` | 路径或服务端世界无效。 | 否。 |

检查严格从起点向终点短路，因此 `firstUnreadyChunk` 始终表示运动方向上的第一个失败点。未获授权的区块即使碰巧已经由玩家加载，也会先返回 `NOT_REQUESTED`，从而防止绕过全局预算。

## 5. Ticket 管理、预算与公平性

### 5.1 最终参数

| 参数 | 最终值 | 语义 |
| --- | ---: | --- |
| `GLOBAL_NEW_CHUNK_REQUESTS_PER_TICK` | 64 | 每台服务器每 Tick 最多授权的新增路径中心区块数。 |
| `MAX_CHUNKS_PER_ENTITY_TICK` | 128 | 单实体单次提交允许的最大连续路径长度。 |
| `POST_TELEPORT_TICKET_LEVEL` | 2 | 精确区块 Ticket 的 level。 |
| `RESIDENCY_LEASE_TICKS` | 30 | 实体漏提交后继续保护最后驻留位置的短租约。 |
| `STATS_LOG_INTERVAL_TICKS` | 200 | 聚合统计日志周期。 |

管理器直接调用 `ServerChunkCache.addRegionTicket(TicketType.POST_TELEPORT, chunkPos, 2, entityId)`，只给指定 `ChunkPos` 加票。它没有调用本体 `EntityUtil.keepChunkLoaded(...)`，因此不会为每个路径节点额外派生一个“前方区块”请求，也不需要改动本体。

预算只约束“新增授权”。已经位于连续授权前缀内的区块可继续刷新而不重复消耗新增预算。路径改变后，旧方向残留的 Ticket 不能用于放行新路径；管理器只暴露新路径与旧授权集合相交的连续前缀。

### 5.2 分配顺序

`RequestPriority` 的枚举顺序就是预算顺序：

1. `WAITING_PROJECTILE`
2. `ACTIVE_PROJECTILE`
3. `FIXED_WING`

同一优先级按维度和 UUID 稳定排序，然后从每级的轮转游标开始分配。每一轮每个实体最多增加一个区块，直到预算耗尽或全部路径完成；游标每 Tick 前移，避免稳定排序造成长期饥饿。

同一实体在一个 Tick 内多次提交时，后一次路径覆盖前一次路径，但保留两次中更高的优先级。这正好支持 `RVP_BaseBullet` 在制导前、制导后和移动后多次刷新窗口。

### 5.3 实体身份与服务器隔离

- 顶层状态以 `MinecraftServer` 实例隔离，集成服务器重新启动时不会沿用旧预算。
- 单实体键为“维度 ID + UUID”。
- 消费提交时还会同时校验当前实体 ID，防止旧 ID 被新实体复用。
- `ServerStoppedEvent` 清理整台服务器的静态状态。
- 弹体 `remove(...)` 会调用 `releaseEntity(...)` 清理提交、授权、观察、租约和移动后快照；临时 Ticket 不被同步强删，而由原版生命周期自然过期。

## 6. 弹体移动门与等待语义

### 6.1 为什么需要两次移动门

一次 Tick 内，制导可能改变速度方向，因此最终实现检查两次：

1. 制导前用上一 Tick 已确定的速度检查。若不就绪，不推进 `updateCount`、引信、制导、发动机或寿命。
2. 制导后用新速度再次检查。若新路径不就绪，停止在当前位置，避免沿刚修正的方向进入未就绪区块。

真正运动后还会从新位置提交下一轮 5 Tick 窗口，并立即采集 `postMoveChunk`、`loaded`、`entityTicking` 与 `pendingRequestAccepted`，供下一次管理器 Tick 关联诊断。

### 6.2 等待状态

等待由两个同步字段表达：

- `DATA_CHUNK_WAITING`：当前是否等待区块。
- `DATA_CHUNK_WAIT_TOTAL`：累计暂停的飞行 Tick 数。

`getFlightTickCount()` 返回：

~~~text
max(0, tickCount - DATA_CHUNK_WAIT_TOTAL)
~~~

因此原生 `tickCount` 继续随世界 Tick 增长，但制导阶段、导引头扫描、发动机点火与燃烧、二脉冲、碰撞安全期、尾迹节拍等飞行语义改用 `getFlightTickCount()`。客户端在等待时也停止生成尾迹。

等待中的弹体以最高优先级重新提交相同运动路径。路径恢复后记录 `CHUNK_READY_RESUME` 并继续本 Tick；连续等待达到 200 Tick 时记录 `CHUNK_WAIT_TIMEOUT`，释放管理状态并无爆炸地 `discard()`。

制导后才进入等待的转换 Tick 已经推进了一部分飞行状态，因此该 Tick 不重复计入暂停总数；后续完整等待 Tick 才递增 `DATA_CHUNK_WAIT_TOTAL`。

### 6.3 接入范围

`RVP_BaseBullet.shouldKeepDynamicChunkPathLoaded()` 默认返回 `true`，因此 Missile、Rocket、Bomb、Dispensed 等 RVP 类型化弹体共享该机制。`RVP_BulletEntity` 按实体类型覆盖为 `false`，机枪/机炮弹丸不参加动态区块强加载。实现没有按武器资源 ID 分支。

普通弹体和子弹药都只在 `addFreshEntity(...)` 返回成功后调用 `primeDynamicChunkPath()`，避免给未成功入世的实体保留管理状态。

## 7. 漏 Tick 驻留短租约

移动门解决的是“移动前可判定的未就绪”，但实体一旦意外漏 Tick，就无法再由自身 `tick()` 提交下一轮路径。`7e42772` 因此引入服务器级 `ResidencyLease`：

- 每次路径提交更新弱实体引用、最后提交时间、最后位置区块和当前 1 Tick 终点区块。
- 正常请求已处理时，授权路径会按常规流程刷新。
- 本 Tick 没有有效请求或实体查找失败时，管理器独立刷新最后位置与当前 1 Tick 终点两个精确 Ticket。
- 租约只保护最后已知驻留区域，不继续推算无限前方路径。
- 实体明确移除、换世界，或超过 30 Tick 没有提交时，租约及相关状态被清理。

该租约是恢复窗口，不是永久强加载。它使用 `WeakReference<Entity>` 判断生命周期，不以强引用维持实体存活；租约补票也不等价于新增远端路径预算。

## 8. 生命周期监测与诊断

### 8.1 启用方式

监测器默认关闭，日志独立写入游戏目录：

~~~text
logs/rvp_projectile_lifecycle_debug.log
~~~

命令如下：

~~~text
/rvpdebug projectilelife on
/rvpdebug projectilelife off
/rvpdebug projectilelife status
/rvpdebug projectilelife language zh_cn
/rvpdebug projectilelife language en_us
/rvpdebug projectilelife dump
/rvpdebug projectilelife clear
~~~

`on` 会先清空旧日志和内存 trace，再开始新一轮监测。语言切换只改变输出文本，不改变内部事件、状态机和字段语义。

### 8.2 生命周期状态机

监测以 UUID 为主键，避免服务器复用 entityId 导致不同弹体串线。最终版本区分以下容易混淆的事件：

- `REMOVED`：代码已经进入 `Entity.remove(...)`，表示发起了权威移除请求。
- `LEFT_LEVEL`：`EntityLeaveLevelEvent` 携带非空 `RemovalReason`，trace 到达终止状态。
- `TRACKING_END`：事件中的 `RemovalReason` 为空，只能确认实体停止追踪/停止 ticking，不能当作最终移除。
- `TICK_STALLED`：watchdog 发现活动 trace 长时间没有收到 Tick。
- `TICK_RESUMED`：先前 stalled 或 tracking-ended 的实体重新收到 Tick。

watchdog 每 20 个服务器 Tick 扫描一次，连续 40 Tick 未更新后标记一次 `TICK_STALLED`。单弹体内存历史最多 400 行，常规最多保留 256 条 trace；容量治理优先淘汰最旧终止记录，其次淘汰停滞至少 1200 Tick 的记录，不静默删除仍活动的 trace。

### 8.3 事件覆盖

除周期 `TICK` 外，监测还覆盖：初始化、生成就绪、配置缺失、发射者失效、区块等待/恢复/超时、子弹药、方块与实体命中、穿透、跳弹、直接伤害、各类引信、空爆抑制、爆炸、撒布、寿命耗尽和 Tick 阶段早退。

`NOT_ALIVE_TICK_EXIT` 使用三个语义化检查点：

- `AFTER_SUBMUNITION`
- `AFTER_HIT`
- `AFTER_MOTION`

日志同时写出刚完成的行为和被跳过的后续范围，便于判断 `discard()` 后是否还有本 Tick 逻辑继续执行。

### 8.4 TICK 性能与航程字段

每条服务端 `TICK` 快照包含：

- 当前速度、位置、旋转、制导相位/来源/阶段、目标、雷达与发动机状态。
- `superTickMicros`：父类 `super.tick()` 耗时。
- `rvpTickMicros`：RVP 自身 Tick 段耗时。
- `totalTickMicros`：两段总耗时。
- `traveledDistance`：trace 建立以来按相邻精确位置累计的实际折线路径长度。
- `actualElapsedSeconds`：基于 `System.nanoTime()` 的真实经过时间，包含等待和服务器卡顿。
- `averageSpeedBlocksPerSecond`：实际路径长度除以真实经过时间。
- `remainingDistance`：到有效目标的直线距离，优先级为存活实体目标、固定目标点、最后制导记忆点；不存在时为 `<null>`。

这些字段不能混为一谈：`speed` 是当前瞬时格/Tick，`averageSpeedBlocksPerSecond` 是含等待和卡顿的真实平均值，`remainingDistance` 是直线距离而不是预计剩余弹道长度。

### 8.5 区块管理日志

当生命周期监测器开启时，区块管理器同时启用两类日志：

- `[RVP][ChunkPath][PostMove]`：逐实体移动后的区块状态、最后授权块、最近续票时间、管理器实体查找结果、终点是否刷新以及漏提交计数。
- `[RVP][ChunkPath][ChunkReqInfo]`：每 200 Tick 聚合请求量、新增请求量、就绪量、等待弹体数、预算耗尽数、活动实体数和待处理实体数。

虽然 `setStatisticsLoggingEnabled(...)` API 仍存在，但 `7e42772` 的实际周期日志门控已经桥接为 `RVP_ProjectileLifecycleDebug.isEnabled()`。排查时只需开启 `projectilelife`；关闭后不会继续输出这两类管理器诊断日志。

## 9. 单元测试覆盖

最终提交状态下有 23 个相关 JUnit 测试：`RVP_ChunkPathLoaderTest` 17 个，`RVP_ChunkPathLoadManagerTest` 6 个。

路径规划测试覆盖：

- 静止、纯垂直、正负轴移动和负坐标。
- 精确角点、精确区块边界和第三次实测的边界跨越案例。
- 5 Tick 窗口缩放、非法输入、零/负窗口。
- 达到上限后只保留连续前缀、终点覆盖判定。
- 全部就绪、首个未加载、已加载但不可实体 Tick、未授权和空路径。

预算管理测试覆盖：

- 已有连续前缀无须新增预算即可刷新。
- 多实体共享服务器级预算。
- 等待弹体优先于低优先级请求。
- 同优先级轮转起点。
- 缺口后的旧授权不能绕过缺失区块。
- 滚动路径丢弃旧尾部并扩展新前方。

建议开发时执行：

~~~powershell
$env:JAVA_HOME=''
./gradlew test --tests org.ywzj.rvp.util.RVP_ChunkPathLoaderTest --tests org.ywzj.rvp.util.RVP_ChunkPathLoadManagerTest
./gradlew build
~~~

## 10. 开发者修改指南

### 10.1 调整预测或预算参数

- 预测距离改 `RVP_BaseBullet.PROJECTILE_CHUNK_HORIZON_TICKS`。
- 单实体路径上限和服务器新增预算改 `RVP_ChunkPathLoadManager` 中的常量。
- 修改任一参数后都要同时观察等待时间、`budgetExhaustedCount`、单 Tick Tick 耗时和服务器内存；只提高窗口可能把问题从弹体停止转化为加载压力。
- 不要把 `hasChunkAt(...)` 当作充分条件，`isPositionEntityTicking(...)` 仍然必须成立。

### 10.2 接入新的弹体类型

新的 RVP 弹体若继承 `RVP_BaseBullet`，默认会启用动态路径。若它像高射速 Bullet 一样不应强加载，应按实体类型覆盖 `shouldKeepDynamicChunkPathLoaded()`，不要在实体或渲染类里硬编码武器 ID。

如果新增独立生成入口，必须遵守以下顺序：

1. 完成武器数据、位置、速度和朝向初始化。
2. 调用 `addFreshEntity(...)`。
3. 仅在返回成功后调用 `primeDynamicChunkPath()`。

### 10.3 修改 Tick 时钟

凡是表达“实际飞行经过了多少 Tick”的逻辑，应优先检查是否需要使用 `getFlightTickCount()`。当前已经迁移的范围包括制导阶段、扫描间隔、发动机、二脉冲、碰撞安全期和尾迹。原生实体生命周期或 Forge 调度语义仍应使用 `tickCount`/`gameTime`，不要机械替换。

### 10.4 修改管理器状态

新增状态必须同时考虑四个清理边界：

- `RVP_BaseBullet.remove(...)` / `releaseEntity(...)`
- 租约到期或实体生命周期结束
- 实体换维度
- `ServerStoppedEvent`

不得用强实体引用替代当前弱引用租约，也不要同步删除临时 `POST_TELEPORT` Ticket；现有设计依赖原版 Ticket 生命周期自然回收滚出窗口的区块。

## 11. 已知边界与风险

- 这套实现降低了真实弹体跨入未就绪区块的概率，并为漏 Tick 提供 30 Tick 恢复窗口，但它不是数万格战略弹道的低成本模拟方案。超远程导弹仍更适合使用虚拟中段弹道。
- 单 Tick 水平路径超过 128 个区块时会被判为 `PATH_TRUNCATED` 并等待，最终可能在 200 Tick 后被丢弃。这是明确的负载保护，而不是无限放大 Ticket 走廊。
- `POST_TELEPORT` Ticket 是临时请求；“已请求”“已加载”“允许实体 Tick”是三个不同阶段。
- 生命周期逐 Tick 日志有明显 I/O 和格式化成本，只应在诊断期间开启。管理器的周期统计和移动后日志在最终提交中与同一个开关联动。
- `checkShooterValid()` 不再施加距离上限后，实际最大航程主要由武器 `life`、制导/引信、区块等待超时和服务器运行条件决定。

## 12. 代码导航

以下路径均相对于 `D:\WgameProject\limitless-vehicle-rvp-addon`：

- `src/main/java/org/ywzj/rvp/util/RVP_ChunkPathLoader.java`
- `src/main/java/org/ywzj/rvp/util/RVP_ChunkPathLoadManager.java`
- `src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java`
- `src/main/java/org/ywzj/rvp/entity/projectile/RVP_BulletEntity.java`
- `src/main/java/org/ywzj/rvp/entity/projectile/RVP_ProjectileMotion.java`
- `src/main/java/org/ywzj/rvp/weapon/core/RVP_ProjectileSpawner.java`
- `src/main/java/org/ywzj/rvp/weapon/submunition/RVP_SubmunitionSpawner.java`
- `src/main/java/org/ywzj/rvp/debug/RVP_ProjectileLifecycleDebug.java`
- `src/main/java/org/ywzj/rvp/debug/RVP_ProjectileLifecycleDebugEvents.java`
- `src/main/java/org/ywzj/rvp/debug/RVP_ServerDebugCommands.java`
- `src/test/java/org/ywzj/rvp/util/RVP_ChunkPathLoaderTest.java`
- `src/test/java/org/ywzj/rvp/util/RVP_ChunkPathLoadManagerTest.java`

审阅某一历史状态时应使用类似 `git show 7e42772:src/main/java/org/ywzj/rvp/util/RVP_ChunkPathLoader.java` 的命令，而不是直接以当前 HEAD 文件推断；当前分支在这条提交链之外还合入了其他功能。
