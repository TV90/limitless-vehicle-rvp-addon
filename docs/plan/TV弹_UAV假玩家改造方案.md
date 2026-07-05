# TV弹 UAV假玩家改造方案

> 日期：2026-07-05
> 范围：`ywzj_rvp` TV/HITL 导弹超出玩家视距后消失问题的架构方案、实施计划与风险控制。

## 1. 背景与问题

当前 `ywzj_rvp` 的 TV 弹/HITL 方案，本质上是：

- 玩家仍然留在原载具中；
- 客户端切换到 `missile camera`；
- 服务端继续让 `RVP_MissileEntity` 执行 HITL / guidance 逻辑。

这套方案在近距离内可正常工作，但当 TV 弹飞出玩家可见范围后，客户端会出现“导弹直接消失”的现象。

结合现有代码实现，问题更接近于：

- 服务端导弹实体不一定真的销毁；
- 客户端不再围绕导弹所在区域接收区块与实体同步；
- 结果是 HITL 视角失去导弹实体，TV 画面被迫中断。

这与本体 `ywzj_vehicle` 的 UAV 机制不同。UAV 的关键不是单纯“区块保活”，而是：

- 真玩家实体会被转移到 UAV 上；
- 客户端的观察中心跟随真玩家移动；
- 原载具位置由 `FakePlayer` 占位；
- UAV 与原位置额外通过 `POST_TELEPORT` ticket 保持运行时区块活跃。

因此，单纯给 TV 弹补 chunk loading，大概率不能彻底解决“超视距消失”；更可靠的方向是把 TV 弹改造成一个“类似 UAV 的短生命周期远程载具会话”。

## 2. 目标

本方案目标不是复刻 UAV 交互，而是为 TV 弹建立一套专用的 `TV Session` 机制：

1. 发射 TV 弹后，让真玩家临时转移到导弹上。
2. 在原载具中生成假玩家占位，维持座位与操作上下文。
3. TV 弹爆炸、被移除、玩家退出、载具被摧毁时，统一把玩家送回原载具或安全 fallback 位置。
4. 让客户端 HITL 状态不再依赖“玩家当前仍骑在原载具上”。

## 3. 当前代码约束

### 3.1 TV/HITL 现状

当前 `RVP_ClientHitlState.tick()` 存在硬性前提：

- `player.getVehicle()` 必须仍然是 `AbstractVehicle`；
- 否则会直接 `clear()` 退出 HITL。

这意味着如果直接把玩家切到 `missile.startRiding()`，而客户端状态机不改，TV 模式会立刻被自己关闭。

### 3.2 本体 UAV 现状

本体 UAV 的关键能力包括：

- 进入 UAV 时生成 `FakePlayer`；
- 真玩家传送并骑乘 UAV；
- UAV 每 tick 保活自身区块、前方区块、`fakeOperatorPosition` 区块；
- UAV 销毁或退出时，尝试把真玩家送回假玩家位置。

### 3.3 不能直接复用本体 FakePlayer

本体 `FakePlayer` 生命周期里带有明显 UAV 假设：

- 它会检查 `copyPlayer.getVehicle()` 是否还是 `uav=true` 的 `AbstractVehicle`；
- 如果不是，会 `discard()`。

TV 弹场景下，真玩家骑的是 `RVP_MissileEntity`，不是 UAV 载具，因此直接复用本体 `FakePlayer` 大概率会异常自毁。

结论：

- 可以借鉴 UAV 模式；
- 但要做一套 TV 专用的 `Proxy Occupant` / `Session` 机制；
- 不建议直接硬复用本体 UAV 的 `FakePlayer` 生命周期。

## 4. 推荐方案

推荐采用：

`TV Missile Session + Fake Occupant + Real Player Riding Missile`

即：

1. 发射 TV 弹时创建一个 `TV Session`。
2. 真玩家隐身并骑乘导弹。
3. 原载具座位生成一个 TV 专用假玩家或占位实体。
4. 客户端 HITL、HUD、Scope 在会话期间基于 `session.launcherVehicle` 运行，而不是基于 `player.getVehicle()`。
5. 任一退出条件触发后，统一结束 session，并把玩家送回原载具。

## 5. 核心架构设计

### 5.1 Session 对象

建议新增：

- `RVP_TvMissileSession`
- `RVP_TvMissileSessionManager`

`RVP_TvMissileSession` 建议字段：

- `UUID playerUuid`
- `int playerEntityId`
- `UUID launcherVehicleUuid`
- `int launcherVehicleId`
- `int launcherSeatIndex`
- `int missileEntityId`
- `Vec3 returnPos`
- `float returnYaw`
- `float returnPitch`
- `UUID proxyOccupantUuid`
- `boolean ended`
- `long startGameTime`

说明：

- `launcherSeatIndex` 用于尽量恢复到原座位；
- `returnPos` 是载具不可恢复时的 fallback；
- `ended` 用于防止重复收尾；
- `startGameTime` 便于 debug 和超时保护。

### 5.2 假玩家/占位实体

建议不要直接复用 `ywzj_vehicle` 的 `FakePlayer`，而是新建一个轻量 TV 专用实体，例如：

- `RVP_TvProxyOccupantEntity`

职责：

- 占据原载具座位或原位置；
- 保存玩家名、朝向、基础装备外观；
- 作为返回锚点；
- 在 session 结束时被移除；
- 不携带 UAV 专用生命周期判断。

可选做法：

- 若不需要完整生物实体表现，也可以做更轻量的占位器；
- 但从现有代码习惯看，实体化占位器更容易接现有座位和渲染逻辑。

### 5.3 导弹侧扩展

建议在 `RVP_MissileEntity` 增加 TV session 关联字段：

- `tvSessionOwnerId`
- `tvLauncherVehicleId`
- `tvSessionActive`

职责：

- 标识该导弹是否为 TV session 挂载体；
- 在 `discard()`、爆炸、超时、命中等路径里通知 manager 收尾；
- 允许客户端和服务端通过 missile 反查 session。

### 5.4 客户端状态解耦

`RVP_ClientHitlState` 需要从“当前骑乘物驱动”改成“session 驱动”。

当前错误前提：

- 玩家必须还在 `AbstractVehicle` 上。

改造后应为：

- 只要 `activeMissileId` 对应 session 仍有效，就继续 TV/HITL；
- 原载具信息从 `session.launcherVehicleId` 获取；
- `HUD` / `Scope` / `weaponHitPos` 等也优先读取 session 绑定的发射车。

这一步是整个方案能否成立的关键。

## 6. 生命周期设计

### 6.1 发射阶段

触发时机：

- TV/HITL missile 服务端生成成功后。

流程：

1. 校验发射者为 `ServerPlayer`。
2. 校验玩家当前正在原载具上。
3. 记录原载具、座位、姿态、返回点。
4. 创建 `RVP_TvMissileSession`。
5. 生成 `RVP_TvProxyOccupantEntity`。
6. 将假占位实体安置到原载具/原位。
7. 将真玩家设置为：
   - 隐身
   - 禁止普通交互
   - 可选无碰撞
8. 让真玩家 `startRiding(missile, true)`。
9. 向客户端同步 TV session 激活状态。

### 6.2 运行阶段

会话期间：

- 客户端 camera 跟随 missile；
- 客户端 HUD 以 `session.launcherVehicle` 为上下文；
- 服务端持续校验：
  - missile 是否存活；
  - launcher vehicle 是否存活；
  - player 是否在线、未死亡；
  - session 是否超时。

### 6.3 结束阶段

结束条件包括：

- missile 爆炸；
- missile 被移除；
- launcher vehicle 被摧毁；
- 玩家主动退出 HITL；
- 玩家死亡；
- 玩家掉线；
- session 异常失配。

统一走：

- `RVP_TvMissileSessionManager.endSession(session, reason)`

收尾步骤：

1. 防重复：若 `ended=true` 直接返回。
2. 标记 `ended=true`。
3. 移除假占位实体。
4. 尝试把真玩家传回原载具原座位。
5. 若失败，则传回 `returnPos`。
6. 恢复玩家：
   - 可见性
   - 碰撞
   - camera/HITL 状态
   - 其他临时标记
7. 清理 session 注册表。

## 7. 关键技术点

### 7.1 玩家骑乘导弹的副作用

这是可行方案里最大的风险点之一。

需要特别处理：

- rider 不应被自己的导弹直接命中；
- 爆炸时不应错误地对 rider 二次伤害；
- `isPassengerOfSameVehicle()` 相关命中逻辑可能受到影响；
- 某些 renderer / camera / input 逻辑可能默认 projectile 没 rider。

建议：

- 给 TV session rider 加明确标记；
- 在导弹碰撞、命中、自伤判定处排除当前 session 玩家；
- 在爆炸伤害中按 `sessionOwnerId` 显式豁免。

### 7.2 客户端 HUD 依赖当前载具

当前大量逻辑默认：

- `mc.player.getVehicle()`
- `LocalVehiclePlayer.instance.getVehicle()`

就是当前操作载具。

TV session 改造后，这个等式不再成立。必须引入一个统一解析入口，例如：

- `RVP_TvSessionClientContext.getLauncherVehicle()`

让 TV 模式期间：

- Scope
- Overlay
- Fire control
- Aim point
- Radar/HMD 相关逻辑

都能优先读 session 的 launcher，而不是读玩家当前 mount。

### 7.3 区块与追踪

在“真玩家骑导弹”模式下，客户端观察中心会跟着真玩家移动，这本身就能显著缓解 TV 弹超视距丢失问题。

同时仍建议保留导弹自身区块保活：

- `RVP_BaseBullet.keepChunkLoaded = true`

必要时可追加：

- 发射车区块保活；
- fallback 返回点区块保活；
- 导弹前方预加载。

但这些应视为辅助保障，而不是主解法。

## 8. 分阶段实施计划

### 阶段 1：打基础

目标：

- 引入 session 基础设施；
- 不立刻改所有 HUD，只先建立服务端闭环。

任务：

1. 新增 `RVP_TvMissileSession` 与 `Manager`。
2. 新增 `RVP_TvProxyOccupantEntity`。
3. 发射 TV 弹时建立 session。
4. 真玩家骑乘 missile，假占位实体留在原位。
5. missile 爆炸/移除时可把玩家送回。

验收：

- 单人环境下，TV 弹发射后玩家能稳定跟随 missile；
- 导弹结束后玩家能回到原车或 fallback 点；
- 不出现玩家永久丢失、卡骑乘、卡隐身。

### 阶段 2：客户端 HITL 解耦

目标：

- 让 TV 模式不再依赖“玩家当前仍在原载具上”。

任务：

1. 修改 `RVP_ClientHitlState`。
2. 引入 session 上下文解析 launcher vehicle。
3. 改 TV camera / exit / input 状态恢复。

验收：

- 玩家骑乘 missile 时，HITL 不会因 `player.getVehicle()` 改变而自清空；
- TV 画面、退出流程稳定。

### 阶段 3：HUD 与火控适配

目标：

- 修正 scope、overlay、aim point、武器上下文。

任务：

1. 对 TV 期间依赖当前载具的 HUD 做兼容。
2. 补 launcher vehicle 解析接口。
3. 校验 thermal / BW / SCOPE 恢复逻辑。

验收：

- TV 模式期间 HUD 正常；
- 退出后视角模式与热成像状态恢复正确；
- 不污染非 TV 武器流程。

### 阶段 4：异常与多人稳定性

目标：

- 处理复杂边界条件。

任务：

1. 载具先炸、导弹后炸。
2. 玩家掉线、死亡、切维度。
3. 多人同时发射 TV 弹。
4. 代理占位实体被攻击。

验收：

- session 不泄漏；
- 不出现多个玩家绑定到同一 missile；
- 不出现幽灵占位实体残留。

## 9. 风险评估

### 高风险

- 客户端大量逻辑默认玩家当前仍在原载具上；
- projectile 作为 mount 可能触发未知的输入、碰撞、渲染副作用；
- 退出收尾链路如果不统一，容易出现玩家卡死或状态残留。

### 中风险

- 假占位实体与原载具座位逻辑兼容性；
- missile 高速飞行时的多端同步边界；
- 玩家与导弹、自身爆炸的伤害排除。

### 低风险

- session 数据结构本身；
- 基础 manager/registry；
- 发射与结束时的常规状态同步。

## 10. 不推荐方案

### 10.1 只加强 chunk loading

不推荐作为主方案。

原因：

- 它只能增加服务端实体继续运行的概率；
- 不能保证客户端继续把远处 missile 当作观察目标同步回来；
- 对“玩家视距外直接消失”这个体验问题，命中率不高。

### 10.2 直接复用本体 FakePlayer

不推荐直接使用。

原因：

- 本体 `FakePlayer` 生命周期绑定 `uav=true` 假设；
- TV 场景中真玩家骑的是导弹，不满足该假设；
- 后续维护成本会更高。

## 11. 建议的首轮实施范围

首轮建议只做：

1. 服务端 `TV Session` 基础设施；
2. 玩家骑乘 missile；
3. TV 专用假占位实体；
4. 统一回传与状态恢复；
5. `RVP_ClientHitlState` 最小解耦。

先不要一口气改所有 HUD。优先拿到“超视距不消失、爆炸能回车、不会卡状态”的主链路，再逐步收 HUD 边角。

## 12. 验收用例

建议至少验证以下场景：

1. 单人发射 TV 弹，飞出原始视距后仍能持续控制。
2. TV 弹命中爆炸后，玩家正常返回原载具。
3. TV 弹飞行中主动退出，玩家正常回传。
4. TV 弹飞行中原载具被摧毁，玩家回 fallback 点。
5. TV 弹飞行中玩家死亡/掉线，session 正常清理。
6. 多名玩家分别发射 TV 弹，互不串线。
7. 假占位实体被攻击时，不会导致 session 异常残留。

## 13. 结论

这个问题的核心不在“再加一个 chunk loader”，而在于当前 TV 模式仍然把玩家留在原载具里，导致客户端观察中心没有跟着导弹移动。

合理的解决路径是：

- 借鉴 UAV 的“真玩家转移 + 假玩家占位”思路；
- 但单独实现一套 TV 专用 `Session`；
- 再把 HITL 客户端状态从“当前骑乘物”解耦到“session 绑定的发射载具”。

这是当前代码结构下最稳、最可扩展，也最符合后续维护成本的方案。
