# RVP爆炸系统挂钩点与近炸双伤调研

## 1. 当前与本体爆炸系统挂钩的 mixin

当前 `ywzj_rvp` 里，和 `ywzj_vehicle` 本体爆炸系统直接挂钩的 mixin 可按 4 条链理解。

### 1.1 爆炸视觉链

1. `VehicleExplosionVisualPacketMixin`
   - 文件：[VehicleExplosionVisualPacketMixin.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleExplosionVisualPacketMixin.java)
   - 目标：`VehicleExplosion.explode(Ljava/util/List;)V`
   - 作用：修改 `ServerVehicleExplosion` 的 `radius` 参数；当 RVP 需要压掉本体爆炸视觉时，把半径写成负值。

2. `VehicleExplosionClientVisualMixin`
   - 文件：[VehicleExplosionClientVisualMixin.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleExplosionClientVisualMixin.java)
   - 目标：`VehicleExplosion.effect(ServerVehicleExplosion)`
   - 作用：客户端收到负半径爆炸包时，直接取消本体爆炸特效。

### 1.2 爆炸破坏链

3. `VehicleExplosionCraterMixin`
   - 文件：[VehicleExplosionCraterMixin.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleExplosionCraterMixin.java)
   - 目标：`VehicleExplosion$GridCollectionTask.finish()`
   - 作用：在本体收集待破坏方块列表后，按 `RVP_Config.craterDepthRules` 限制弹坑深度。

### 1.3 爆炸伤害链

4. `AbstractVehicleHitboxDamageFactorMixin`
   - 文件：[AbstractVehicleHitboxDamageFactorMixin.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/AbstractVehicleHitboxDamageFactorMixin.java)
   - 目标：`AbstractVehicle.hurt(DamageSource,float)`
   - 作用：通过 `source.getMsgId().equals("ywzj_vehicle.explosion")` 识别本体爆炸伤害，并决定命中盒倍率、核心距离衰减等逻辑是否参与。

## 2. 非 mixin，但属于爆炸主入口的关键代码

### 2.1 RVP 爆炸主入口

- 文件：[RVP_BaseBullet.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java)
- 关键方法：`triggerExplosion(Vec3 pos, FuseDetonation kind, @Nullable Entity excludeEntity)`
- 说明：
  - 这里会直接 `new VehicleExplosion(...)`
  - RVP 大部分弹药最终都从这里进入本体爆炸结算
  - 后续如果重构爆炸伤害、爆炸破坏、视觉屏蔽，不能只盯 mixin，也必须处理这里

### 2.2 APS 上游触发

- 文件：[AbstractVehicleApsMixin.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/AbstractVehicleApsMixin.java)
- 关键调用：`bullet.rvp$detonateByAps()`
- 说明：APS 只是爆炸的上游触发者，最终仍回到 `RVP_BaseBullet.triggerExplosion()`

## 3. 近炸引信双倍伤害调研结论

### 3.1 现象判断

当前“带近炸引信的弹药有概率打出双倍伤害”，高概率不是数值翻倍问题，而是：

- 近炸链先对触发目标补了一次“保证命中伤害”
- 随后本体 `VehicleExplosion` 又对真正的目标本体补了一次范围伤害
- 由于排除列表排错了对象，所以两次都生效

### 3.2 直接可疑链路

近炸触发入口：

- `tickProximityFuse()`  
  文件：[RVP_BaseBullet.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java:1111)
- `tryAmmoProximityFuze()` / `findProximityTargetOnSegment()`  
  文件：[RVP_BaseBullet.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java:1253)

这里的近炸目标来自 `level().getEntities(...)` 的原始实体列表，**没有像直击链那样做 `PartEntity -> parent` 归一化**。

而直击链是做了归一化的：

- `findEntityOnPathForSegment()` -> `ywzj_rvp$normalizeBulletHitResult(...)`
- 文件：[RVP_BaseBullet.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java:1220)

### 3.3 双伤是怎么出现的

近炸真正结算在：

- `detonateFuseAt(Vec3 pos, FuseDetonation kind, @Nullable Entity proximityTarget)`
- 文件：[RVP_BaseBullet.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java:1840)

逻辑是：

1. 若是近炸，先对 `proximityTarget` 施加一次 guaranteed damage
2. 然后把 `proximityTarget` 作为 `excludeEntity` 传给 `triggerExplosion(...)`
3. `triggerExplosion(...)` 再把这个排除对象放进 `VehicleExplosion.explode(List<Entity>)`
4. 本体 `VehicleExplosion.hurt(...)` 只用 `excludedEntities.contains(entity)` 做**精确对象匹配**

对应文件：

- [RVP_BaseBullet.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java:1797)
- [VehicleExplosion.java](/D:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/util/VehicleExplosion.java:299)

这意味着如果 `proximityTarget` 是：

- `PartEntity`
- 载具挂件
- 其它附属子实体

那么 guaranteed damage 可能先打在子实体/代理对象上，而本体爆炸阶段真正遍历命中的却是父级 `AbstractVehicle`。  
由于排除列表里只有子实体，没有父实体，所以**爆炸伤害不会被排掉**，于是出现“近炸补伤 + 本体爆炸再伤一次”的双伤。

### 3.4 为什么是“有概率”

因为近炸链并不总是命中同一种对象：

- 有时直接拿到的是父实体
- 有时拿到的是 `PartEntity`
- 有时来自 `targetEntity`
- 有时来自 proximity scan 的原始 `nearbyEntities`

所以它不是必现翻倍，而是**取决于当 tick 捕获到的是父实体还是子实体**。

### 3.5 当前最优先的修复方向

后续修复时，优先级最高的是这两步：

1. 近炸目标在进入 `detonateFuseAt(...)` 前，先做一次和直击链一致的 root 归一化
   - `PartEntity -> parent`
   - 其它代理实体 -> 真正吃伤害的根实体

2. `excludeEntity` 不要直接沿用原始 `proximityTarget`
   - 应传入“真正吃 guaranteed damage 的根实体”
   - 否则 `VehicleExplosion` 的排除表仍会漏掉父实体

## 4. 额外备注

当前本体 `VehicleExplosion.hurt(...)` 的排除逻辑是“精确对象排除”，并不会自动理解“排除一个 `PartEntity` 也等于排除它的父载具”。  
因此只要 RVP 这一侧传进去的不是根实体，这个 bug 就会反复出现。
