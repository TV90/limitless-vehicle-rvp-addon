# [OPEN] AHEAD Client Fatal

## Session
- id: `ahead-client-fatal`
- date: `2026-06-22`
- symptom: AHEAD 母弹 `on_fuse` 已成功散出 `10` 发 `ahead_cbc`，随后客户端出现 `Render thread/ERROR] [net.minecraft.util.thread.BlockableEventLoop/FATAL]: Error executing task on Client`

## User Evidence
- server log:
  - `[RVP][AHEAD] fuse weapon=rvp:PS1_2A38_AHEAD entityId=2700 airburst=281m events=1 configured_per_event=10 spawned=10 payloads=rvp:ahead_cbc ...`
- client log:
  - `Render thread/ERROR] [net.minecraft.util.thread.BlockableEventLoop/FATAL]: Error executing task on Client`

## Falsifiable Hypotheses
1. 客户端在接收 `ahead_cbc` 子弹药实体生成包后，渲染或反序列化阶段抛异常，导致 render thread fatal。
2. `ahead_cbc` 的某个资源或显示数据在客户端缺失，AHEAD 空爆真正生成子弹药时才触发客户端崩溃。
3. `submunition` 生成的子弹药数量或某个 payload 字段与客户端实体初始化不兼容，服务端能生成，客户端在处理 spawned bullets 时失败。
4. 新加的 AHEAD 调试日志不是根因，真正异常发生在客户端任务队列处理 spawned entity / packet / render task 时；需要完整客户端堆栈确认。
5. 客户端 fatal 与 AHEAD 主体逻辑无关，而是空爆后触发了已有的 projectile/client render bug，只是被这次 `on_fuse` 高概率复现出来。

## Plan
1. 读取客户端 `latest.log` / crash report，拿到完整堆栈。
2. 根据堆栈确认是网络包、实体初始化、还是渲染崩溃。
3. 仅在证据不足时再加最小化插桩。
4. 确认根因后做最小修复并复验。

## Evidence
- `latest.log` 关键堆栈：
  - `io.netty.handler.codec.DecoderException: Received unexpected null component`
  - `at org.ywzj.vehicle.entity.weapon.AmmoEntity.readSpawnData(AmmoEntity.java:181)`
  - `at org.ywzj.rvp.entity.projectile.RVP_BaseBullet.readSpawnData(RVP_BaseBullet.java:1723)`
  - `at org.ywzj.rvp.entity.projectile.RVP_BulletEntity.readSpawnData(RVP_BulletEntity.java:260)`
- 对照代码：
  - [AmmoEntity.writeSpawnData](file:///D:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/entity/weapon/AmmoEntity.java#L172-L177) 会直接 `writeComponent(name)`，`name` 不能为 `null`
  - 普通主发射链 [RVP_ProjectileSpawner](file:///D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/weapon/core/RVP_ProjectileSpawner.java#L76-L80) 会补 `projectile.name = Component.translatable(data.getName())`
  - 子母弹发射链 [RVP_SubmunitionSpawner](file:///D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/weapon/submunition/RVP_SubmunitionSpawner.java#L99-L107) 之前没有给 `child.name` 赋值

## Conclusion
- 假设 1 confirmed：客户端不是渲染模型崩，而是子弹药出生包解码时读到 `null component`
- 假设 2 rejected：不是资源缺失主因
- 假设 3 confirmed：是 `submunition -> child projectile spawn data` 与客户端解码契约不兼容
- 最小修复：在 `RVP_SubmunitionSpawner.spawnRvpWeapon()` 中给 `child.name` 补与普通主发射链一致的赋值

## Follow-up Observation
- 新症状：子弹药已生成，但方向不对
- 静态证据：
  - [RVP_SubmunitionSpawner](file:///D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/weapon/submunition/RVP_SubmunitionSpawner.java#L97-L118) 原逻辑用 `parent.getDeltaMovement()` 继承速度
  - 但散布参考角度仍使用 `parent.getXRot()/getYRot()`
  - 母弹进入弹道下坠后，`deltaMovement` 与 `xRot/yRot` 可能已不一致，导致子弹药看起来朝错误方向散开
- 修复：
  - 子母弹释放现在优先用母弹当前 `deltaMovement` 通过 `VectorUtil.vecToRot(...)` 反推参考角度
  - `position offset`、`velocity spread`、`child spawn aim` 统一使用这套参考角度
