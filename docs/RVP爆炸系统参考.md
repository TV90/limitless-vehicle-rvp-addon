注：表格中的字段名称为java类中使用的驼峰命名法，在json中需使用全小写+下划线连接的格式

# RVP_Explosion

爆炸核心参数数据模型

| RVP_Explosion字段 | 解释 | 类型 | 默认值 |
| ----------------- | ---- | ---- | ------ |
| `explode` | 是否启用爆炸 | boolean | false |
| `damage` | 爆炸伤害 | float | 0 |
| `radius` | 爆炸半径（格） | float | 0 |
| `proximityFuze` | 是否启用近炸引信（旧版，已被 RVP_FuseData 替代） | boolean | false |
| `proximityRadius` | 近炸检测半径（旧版，已被 RVP_FuseData 替代） | float | 0 |
| `destroyBlock` | 是否破坏方块 | boolean | false |
| `destroyRadius` | **方块破坏半径**（独立于 `radius` 杀伤半径，语义对齐 MCHeli 的 ExplosionBlock）：null=继承 `radius`（破坏范围=杀伤范围，历史行为）；0=不破坏方块；>0=地形破坏用独立半径，>32 时引擎自动走核爆炸批量破坏。仅 `destroyBlock=true` 时生效 | Float | null |

---

# RVP_DetonateData

落点爆炸与自定义效果数据模型

| RVP_DetonateData字段 | 解释 | 类型 | 默认值 |
| -------------------- | ---- | ---- | ------ |
| `effectsBeforeExplosion` | true 时先执行自定义效果再爆炸；false 时先爆炸再自定义效果 | boolean | true |
| `explosionData` | 爆炸参数，见 RVP_Explosion | RVP_Explosion | null |
| `fireData` | **点燃方块**：`radius`(扩散半径)、`chance`(概率0-1)、`soulFire`(灵魂火)、`onBlock`(方块上点燃)/`onEntity`(实体上点燃) | RVP_FireEffectData | null |
| `potionCloudData` | **药水云**：`effect`(效果ID)、`amplifier`、`durationTicks`、`cloudDurationTicks`(云存在时长)、`radius`、`radiusPerTick`、`targets`(过滤) | RVP_PotionCloudEffectData | null |
| `potionEffectData` | **直接上 debuff**（同 potion_cloud 字段，不生成云） | RVP_PotionEffectData | null |
| `placeBlockData` | **放置方块**：`block`(方块ID)、`radius`、`chance`、`replaceMode`(air_only/replaceable/always) | RVP_PlaceBlockEffectData | null |
| `lightningData` | **召唤闪电**：`damage`(是否造成伤害) | RVP_LightningEffectData | null |
| `igniteEntityData` | **点燃实体**：`radius`(范围)、`seconds`(燃烧秒数)、`targets`(过滤) | RVP_IgniteEffectData | null |
| `knockbackData` | **范围击退**：`radius`(范围)、`strength`(击退力度)、`targets`(过滤) | RVP_KnockbackEffectData | null |
| `clearPlantsData` | **清除植物**：`radius`(范围) | RVP_ClearPlantsEffectData | null |
| `hbmEffectData` | **HBM 模组特效**：`type`(VNT/NUCLEAR/SHRAPNEL/WHITE_PHOSPHORUS/CHLORINE)、`radius` | RVP_HbmEffectData | null |

`targets` 过滤选项：`all`(所有)、`living`(活体)、`players`(玩家)、`hostile`(敌对生物)、`non_allied`(排除发射者和其载具乘员)

---

# destroy_radius：爆炸破坏范围与杀伤范围隔离

`explosion_data.destroy_radius` 把**方块破坏范围**从**对生物/实体的杀伤范围**中隔离出来（对齐 MCHeli `ExplosionBlock` 参数语义）：

| 配置 | 行为 |
| ---- | ---- |
| 未配置（null） | 破坏范围 = 杀伤范围 = `radius`（历史行为，单次 `VehicleExplosion`，零变化） |
| `destroy_radius: 0` | 只伤人不破坏地形（单次爆炸，`destroy_block` 被视为 false） |
| `destroy_radius: N`（N>0 且 N≠radius） | **双爆炸**：地形爆炸按 N 破坏方块，杀伤爆炸按 `radius` 伤害实体并决定视觉档位 |
| `destroy_radius: N`（N = radius） | 等价于未配置（单次爆炸） |

双爆炸机制（`RVP_BaseBullet.triggerExplosion`）：

```
triggerExplosion（destroy_radius 有效且 ≠ radius 时）
  ├─ 爆炸 A（地形）: VehicleExplosion(pos, destroy_radius, damage=0, destroyBlock=true)
  │     ├─ 实体伤害被 RVP_TerrainOnlyExplosion 窗口 + VehicleExplosionHurtSkipMixin 跳过
  │     ├─ destroy_radius ≤32 → 引擎即时 Grid 破坏；>32 → 引擎核爆炸批量破坏（Spherical）
  │     └─ 本体视觉包：与爆炸 B 只保留半径更大的一发（两个半径都可能 >32，避免双份蘑菇云）
  └─ 爆炸 B（杀伤）: VehicleExplosion(pos, radius, damage, destroyBlock=false)
        └─ AoE 伤害、ServerVehicleExplosion 视觉档位、ERA/命中提示/波及补发均按杀伤半径
```

注意：

- 弹坑深度规则 `craterDepthRules` 对两条路径都生效，且按**破坏半径**（地形爆炸的 radius）查表。
- 引擎在半径 >32 时切换核爆炸路径（`VehicleExplosion.BATCHED_DESTRUCTION_RADIUS_THRESHOLD = 32`：分 tick 批量破坏 + 蘑菇云视觉），destroy_radius 大于 32 的地形爆炸自动享受该路径。
- 引信半径覆盖（`airburst_explosion_radius` 等）只重写杀伤半径；destroy_radius 始终取 `explosion_data` 配置值。
- `hbm_effect_data` 的真实爆炸接管整次引爆时（`realExplosionApplied`），destroy_radius 不参与（HBM 有自己的 `destroy_block`）。

---

# RVP_FuseData

引信参数数据模型

| RVP_FuseData字段 | 解释 | 类型 | 默认值 |
| ---------------- | ---- | ---- | ------ |
| `delayTick` | 定时引信：飞行 N tick 后引爆，0=不启用 | int | 0 |
| `programmableAirburst` | 启用可编程空爆：测距有效时沿弹道累计距离达到 `测距+offset` 时引爆 | boolean | false |
| `airburstOffset` | 空爆测距偏移（米） | float | 3 |
| `airburstMeasureMin` | 测距有效最小 tick | int | 5 |
| `airburstMeasureMax` | 测距有效最大 tick | int | 300 |
| `airburstExplosionDamage` | 空爆专用爆炸伤害重写，null=使用 RVP_Explosion.damage | Float | null |
| `airburstExplosionRadius` | 空爆专用爆炸半径重写，null=使用 RVP_Explosion.radius | Float | null |
| `aheadEnabled` | 启用 AHEAD 编程空爆 | boolean | false |
| `aheadBurstOffsetMeters` | AHEAD 空爆偏移（米） | float | 3 |
| `aheadRequireLock` | AHEAD 是否需要锁定 | boolean | true |
| `aheadMinGroundClearance` | AHEAD 最低地面净空 | float | 0 |
| `proximityFuseTick` | 近炸引信：发射后多少 tick 才启用，<0=不启用 | int | -1 |
| `proximityFuseHeight` | 近炸最低高度：目标贴地时不触发（格） | int | 20 |
| `proximityRadius` | 近炸检测半径（格），0=不启用 RVP 近炸检测 | float | 0 |
| `proximityFuseDamage` | 近炸直接命中伤害，null=无额外伤害 | Float | null |
| `proximityFuseExplosionDamage` | 近炸专用爆炸伤害重写，null=使用 RVP_Explosion.damage | Float | null |
| `proximityFuseExplosionRadius` | 近炸专用爆炸半径重写，null=使用 RVP_Explosion.radius | Float | null |
| `groundProximityFuseDistance` | 世界系正下方近地引信高度（格），检测可碰撞方块并忽略流体；0=禁用 | float | 0 |
| `groundProximityFuseArmTick` | 近地引信独立解保 tick，计时达到该值后启用 | int | 0 |
| `detonateOnLifeEnd` | 生命周期结束时是否爆炸 | boolean | false |
| `entityCollisionSafeTick` | 发射后多少 tick 内忽略与发射者碰撞 | Integer | null |

---

# 爆炸执行链路

弹药飞行中通过 `RVP_BaseBullet` 触发爆炸，链路如下：

```
RVP_BaseBullet tick()
  ├─ 碰撞前 → 近地扫掠引信(groundProximityFuseDistance + groundProximityFuseArmTick)
  │               → 还原准确离地起爆点 → on_fuse / 标准爆炸
  ├─ tick() → 近炸引信检测(proximityRadius + proximityFuseTick)
  │               → 命中实体 → detonateWithFuse()
  ├─ 与方块碰撞 → BlockHit → detonateImpact()
  ├─ 生命周期结束(lifeTick) → detonateOnLifeEnd()
  └─ 定时引信(delayTick) → 直接引爆
        │
        ▼
  VehicleExplosion.explode(entities)   ← 本体爆炸核心引擎
        │
        ├─ 伤害计算：AoE 距离衰减
        ├─ 方块破坏：圆形弹坑 + destroyBlock 检测
        ├─ 网络包：ServerVehicleExplosion(radius, pos, ...)
        │        └─ 客户端 → effect() → 粒子/声音/屏幕震动
        └─ 击退效果
```

爆炸前/后通过 `RVP_DetonateApplier.apply()` 执行自定义效果（由 `effectsBeforeExplosion` 控制顺序）：

1. 点燃方块（fireData）
2. 生成药水云（potionCloudData）
3. 施加 potion 效果（potionEffectData）
4. 放置方块（placeBlockData）
5. 召唤闪电（lightningData）
6. 点燃实体（igniteEntityData）
7. 击退（knockbackData）
8. 清除植物（clearPlantsData）
9. HBM 模组特效（hbmEffectData）

---

# RVP 爆炸相关 Mixin

## VehicleExplosionVisualPacketMixin

| 属性 | 内容 |
| ---- | ---- |
| 注入目标 | VehicleExplosion.explode(List) |
| Mixin 类型 | @ModifyArg |
| 作用 | 当 RVP_ExplosionVisualSuppression 激活时，将 ServerVehicleExplosion 网络包中的半径取负值，标记该爆炸不播放视觉特效 |

## VehicleExplosionClientVisualMixin

| 属性 | 内容 |
| ---- | ---- |
| 注入目标 | VehicleExplosion.effect(ServerVehicleExplosion) 客户端 |
| Mixin 类型 | @Inject(cancellable) HEAD |
| 作用 | 客户端收到含负半径的爆炸包时取消视觉播放，与 VehicleExplosionVisualPacketMixin 配对使用 |

## VehicleExplosionCraterMixin

| 属性 | 内容 |
| ---- | ---- |
| 注入目标 | VehicleExplosion.GridCollectionTask.finish() |
| Mixin 类型 | @Inject RETURN |
| 作用 | 根据 craterDepthRules 配置限制弹坑深度（≤32 即时破坏路径），移除 centerY - maxDepth 以下的方块 |

## VehicleExplosionCraterSphericalMixin

| 属性 | 内容 |
| ---- | ---- |
| 注入目标 | VehicleExplosion.SphericalCollectionTask.flushBlocks() |
| Mixin 类型 | @Inject HEAD |
| 作用 | 核爆炸路径（半径>32，分 tick 批量破坏）的弹坑深度限制：在每个 flush 周期把低于 centerY - maxDepth 的待处理方块（破坏与烧灼转化）移出队列 |

## VehicleExplosionHurtSkipMixin

| 属性 | 内容 |
| ---- | ---- |
| 注入目标 | VehicleExplosion.hurt(List) |
| Mixin 类型 | @Inject(cancellable) HEAD |
| 作用 | RVP_TerrainOnlyExplosion 窗口激活时跳过实体伤害结算，供 destroy_radius 双爆炸的地形爆炸使用 |

## AbstractVehicleHitboxDamageFactorMixin

| 属性 | 内容 |
| ---- | ---- |
| 注入目标 | AbstractVehicle 受伤相关 |
| Mixin 类型 | @ModifyReturnValue |
| 作用 | RVP 武器对载具不同部位（hitbox）的伤害系数修正 |

---

# 视觉效果抑制机制

RVP_ExplosionVisualSuppression 使用 ThreadLocal 深度计数器控制爆炸视觉的抑制：

```
RVP_ExplosionVisualSuppression.run(action)
  ├─ DEPTH += 1（进入抑制范围）
  ├─ action.run()（执行爆炸）
  │     └─ VehicleExplosion → 网络包 radius 被 mixin 取负
  └─ DEPTH -= 1（离开抑制范围）
```

抑制激活时，VehicleExplosionVisualPacketMixin 将网络包半径取负；客户端收到负半径后 VehicleExplosionClientVisualMixin 取消视觉播放。

用于 HBM 或自定义爆炸替代 vanilla 爆炸视觉时，避免双份特效。

---

# 文件路径

| 类 / Mixin | 路径 |
| ---------- | ---- |
| RVP_Explosion | `ywzj_rvp/.../weapon/data/RVP_Explosion.java` |
| RVP_DetonateData | `ywzj_rvp/.../weapon/data/RVP_DetonateData.java` |
| RVP_FuseData | `ywzj_rvp/.../weapon/data/RVP_FuseData.java` |
| RVP_BaseBullet | `ywzj_rvp/.../entity/projectile/RVP_BaseBullet.java` |
| RVP_DetonateApplier | `ywzj_rvp/.../weapon/effects/RVP_DetonateApplier.java` |
| RVP_ExplosionVisualSuppression | `ywzj_rvp/.../weapon/effects/RVP_ExplosionVisualSuppression.java` |
| RVP_TerrainOnlyExplosion | `ywzj_rvp/.../weapon/effects/RVP_TerrainOnlyExplosion.java` |
| RVP_HbmEffectBridge | `ywzj_rvp/.../weapon/effects/RVP_HbmEffectBridge.java` |
| VehicleExplosionVisualPacketMixin | `ywzj_rvp/.../mixin/VehicleExplosionVisualPacketMixin.java` |
| VehicleExplosionClientVisualMixin | `ywzj_rvp/.../mixin/VehicleExplosionClientVisualMixin.java` |
| VehicleExplosionCraterMixin | `ywzj_rvp/.../mixin/VehicleExplosionCraterMixin.java` |
| VehicleExplosionCraterSphericalMixin | `ywzj_rvp/.../mixin/VehicleExplosionCraterSphericalMixin.java` |
| VehicleExplosionHurtSkipMixin | `ywzj_rvp/.../mixin/VehicleExplosionHurtSkipMixin.java` |
| AbstractVehicleHitboxDamageFactorMixin | `ywzj_rvp/.../mixin/AbstractVehicleHitboxDamageFactorMixin.java` |
| Explosion（本体基础类） | `ywzj_vehicle/.../vehicle/pojo/Explosion.java` |
| VehicleExplosion（本体引擎） | `ywzj_vehicle/.../util/VehicleExplosion.java` |
