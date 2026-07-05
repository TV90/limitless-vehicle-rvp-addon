# Gunner 驾驶载具区块加载可行性调研

> 目标：让 Gunner AI 驾驶的载具拥有区块加载能力，防止飞出玩家加载范围后区块卸载导致载具冻结/炮手消亡。

---

## 一、问题分析

### 1.1 现状

Minecraft 服务端只 tick 玩家附近已加载的区块。当 Gunner 驾驶载具飞出玩家加载范围后：

| 阶段 | 发生什么 |
|------|---------|
| 区块卸载 | 载具实体被从世界移除（`onRemovedFromWorld`），Gunner **被动脱载** |
| 被动脱载 | `getVehicle()` 返回 `null`，触发 `detachedTicks` 递增 |
| 2 秒后 | **Gunner 被 `discard()` 永久删除** |
| 载具本身 | 被 NBT 保存（`shouldBeSaved=true`），但不再 tick（物理停止、AI 停止） |
| 重新加载 | 区块重新加载时实体恢复，但 Gunner 已经消失，目标丢失 |

### 1.2 Gunner 的 `detachedTicks` 机制

```java
// GunnerEntity.java — 每 tick 检查
if (getVehicle() instanceof AbstractVehicle vehicle) {
    detachedTicks = 0;  // 在载具上，正常
    // ... AI tick
} else {
    detachedTicks++;
    if (detachedTicks > 40) {  // 2秒后
        stopRiding();
        discard();  // 炮手被移除！
    }
}
```

**风险**：区块卸载时，载具先被移除（`onRemovedFromWorld`），Gunner 被动脱离骑乘关系，`getVehicle()` 返回 `null`。即使 Gunner 本身被 NBT 保存（`shouldBeSaved=true`、`removeWhenFarAway=false`），重新加载后它已不在载具上，`detachedTicks` 立刻开始累加，2 秒后永久消失。

### 1.3 高危场景

| 场景 | 风险等级 | 说明 |
|------|---------|------|
| 固定翼 AI 攻击 | 🔴 极高 | 攻击半径最大 350 格，远超玩家加载范围（10 chunk ≈ 160 格） |
| 旋翼 AI 追击 | 🟠 高 | 巡航高度 28-60 格，追击可能飞出范围 |
| 地面 AI 追击 | 🟡 中 | 追击距离 64 格，可能驶出范围 |
| Gunner 炮手（非驾驶） | 🟢 低 | 载具由玩家驾驶，通常不会离开加载区 |

---

## 二、本体现有区块加载机制

### 2.1 UAV 载具（已实现）

```java
// AbstractVehicle.tick() 第 509-515 行
if (uav) {
    EntityUtil.keepChunkLoaded(this, position());                          // 当前位置
    EntityUtil.keepChunkLoaded(this, position().add(getLookAngle().normalize().scale(16)));  // 前方16格
    if (fakeOperatorPosition != null) {
        EntityUtil.keepChunkLoaded(this, fakeOperatorPosition);           // 操作员位置
    }
}
```

- 仅 `uav == true` 的载具（无人机）才有区块加载
- 普通载具**完全没有**区块加载能力

### 2.2 弹药实体（已实现）

```java
// AmmoEntity.tick() 第 84-88 行
if (keepChunkLoaded) {
    EntityUtil.keepChunkLoaded(this, this.position());
    EntityUtil.keepChunkLoaded(this, this.position().add(getLookAngle().normalize().scale(16)));
}
```

- 导弹/火箭/炸弹：`keepChunkLoaded = true`
- 机枪弹：`keepChunkLoaded = false`

### 2.3 `EntityUtil.keepChunkLoaded()` 工具方法

```java
// EntityUtil.java
public static void keepChunkLoaded(Entity entity, Vec3 position) {
    ChunkPos chunkpos = new ChunkPos(BlockPos.containing(position));
    ((ServerLevel) entity.level())
        .getChunkSource()
        .addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 3, entity.getId());
}
```

| 参数 | 值 | 含义 |
|------|-----|------|
| `TicketType.POST_TELEPORT` | 内置票类型 | 临时区块票，约 300 tick 后自动过期 |
| `chunkpos` | 当前/前方区块 | 需要加载的区块坐标 |
| `3` | 加载距离 | 实际加载范围 ≈ 3+2 = 5 chunk（80 格） |
| `entity.getId()` | 实体 ID | 票标识，每个实体独立 |

**关键特性**：
- 票会自动过期（约 300 tick），必须**每 tick 重新申请**
- 载具 `discard()` 后不再申请，区块自然卸载，无需手动清理
- 仅服务端有效，客户端无需处理

---

## 三、方案对比

### 方案 A：`TicketType.POST_TELEPORT` 每 tick 刷新 ⭐ 推荐

与本体 UAV、弹药实体使用完全相同的机制。

| 维度 | 评估 |
|------|------|
| **实现复杂度** | 低 — 复用 `EntityUtil.keepChunkLoaded()`，一个 Mixin 类即可 |
| **Mixin 可行性** | 高 — 注入 `AbstractVehicle.tick()` TAIL，条件判断有无 Gunner 驾驶员 |
| **自然清理** | ✅ 载具销毁后不再申请票，区块自然卸载 |
| **移动跟踪** | ✅ 每 tick 按载具当前位置申请，自动跟随 |
| **性能** | 每载具每 tick 2 次 `addRegionTicket` ≈ 2-10 μs，正常场景可忽略 |
| **跨重启** | ❌ 不持久化，重启后需重新 tick 才会申请 |
| **已有先例** | ✅ UAV、AmmoEntity、RVP 可部署无人机 Mixin 均用此方案 |

### 方案 B：Forge `ForcedChunkManager` 持久区块加载

Forge 官方提供的持久化区块加载 API。

| 维度 | 评估 |
|------|------|
| **实现复杂度** | 高 — 需要在实体注册层面配合，项目中无先例 |
| **Mixin 可行性** | 中 — 需要管理 force/unforce 生命周期，Mixin 注入点更多 |
| **自然清理** | ❌ 必须在实体销毁时手动 `unforceChunk`，否则区块永远不卸载 |
| **移动跟踪** | ❌ 载具移动后需 unforce 旧位置 + force 新位置，频繁 IO |
| **加载级别** | ⚠️ **致命缺陷** — 只提供 `LOAD` 级别（实体存在但不 tick），不是 `TICKING` 级别。载具物理引擎、AI、武器都不会工作 |
| **性能** | 每次 force/unforce 涉及事件分发和 IO，比 addRegionTicket 重得多 |
| **跨重启** | ✅ 区块加载持久化到存档 |
| **已有先例** | ❌ 项目中从未使用 |

**不推荐原因**：
1. `ForcedChunkManager` 设计目标是**静态区块**（如工厂/方块实体），对每 tick 移动的载具极不友好
2. **只提供 `LOAD` 级别而非 `TICKING`**：区块内实体存在但不会 tick，这对载具是致命的——物理引擎、AI、武器全部失效，等于白加载
3. 频繁 force/unforce 的 IO 开销远高于 per-tick `addRegionTicket`

### 方案 C：SBW 的 `ChunkPosSavedData`（重启恢复）

运行时用 `TicketType.POST_TELEPORT`，服务器关闭时保存载具位置到 SavedData，启动时恢复加载。

| 维度 | 评估 |
|------|------|
| **跨重启** | ✅ 解决重启后区块恢复问题 |
| **运行时** | 与方案 A 相同 |
| **额外代码** | 需要 SavedData 管理 + 事件监听 |
| **必要性** | 低 — Gunner 载具重启后通常需要重新部署，不是长期存在的结构 |

**暂不推荐**：核心问题是运行时区块卸载，跨重启场景优先级低。

---

## 四、推荐方案详解（方案 A）

### 4.1 实现思路

在 `ywzj_rvp` 中创建 Mixin，注入 `AbstractVehicle.tick()` 的 TAIL，检测载具上是否有 Gunner 驾驶员，有则调用 `EntityUtil.keepChunkLoaded()`。

### 4.2 伪代码

```java
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleGunnerChunkLoadMixin {

    @Shadow public abstract LivingEntity getDriver();
    @Shadow public abstract boolean isDestroyed();
    @Shadow public abstract boolean isUav();

    @Inject(method = "tick", at = @At("TAIL"))
    private void ywzj_rvp$gunnerChunkLoading(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle)(Object) this;
        Level level = self.level();

        // 仅服务端
        if (level.isClientSide()) return;
        // UAV 载具已有区块加载，不重复
        if (this.isUav()) return;
        // 载具已销毁，无需加载
        if (this.isDestroyed()) return;
        // 驾驶员不是 Gunner，无需加载（玩家自己会加载区块）
        LivingEntity driver = this.getDriver();
        if (!(driver instanceof GunnerEntity)) return;

        // 区块加载：当前位置 + 前方预加载（与 UAV 一致）
        EntityUtil.keepChunkLoaded(self, self.position());
        EntityUtil.keepChunkLoaded(self, self.position().add(
            self.getLookAngle().normalize().scale(16)));
    }
}
```

> **说明**：`AbstractVehicle` 已有 `getDriver()` 方法返回 `LivingEntity`，可直接判断是否为 `GunnerEntity`，无需遍历乘客列表。Gunner 作为载具乘客，只要载具所在区块被加载，乘客自然也会被 tick，不需要为 Gunner 单独加载区块。

### 4.3 关键设计考量

#### 条件触发

- 只在载具上有 **Gunner 驾驶员**时才启用区块加载
- 普通玩家驾驶的载具不需要（玩家自己会加载区块）
- UAV 载具已有区块加载，跳过避免重复

#### 前方预加载

- 与本体 UAV 一致：加载前方 16 格区块
- 对固定翼载具至关重要：速度可达数 chunk/tick，预加载防止撞入未加载区块

#### 可配置化建议

通过 `GunnerProfile` 添加配置字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `keep_chunk_loaded` | boolean | `true` | 是否为该 Gunner 驾驶的载具启用区块加载 |
| `chunk_load_ahead_distance` | int | `16` | 前方预加载距离（格） |

或通过 RVP 服务端配置：

```toml
[gunner]
    # Gunner 驾驶载具是否启用区块加载
    keepChunkLoaded = true
    # 前方预加载距离（格）
    chunkLoadAheadDistance = 16
    # 刷新间隔（tick），0=每 tick 刷新
    chunkLoadRefreshInterval = 0
```

#### 性能优化：降低刷新频率

`TicketType.POST_TELEPOST` 票的有效期约 300 tick，可以在保证安全的前提下降低刷新频率：

```java
// 优化版：每 5 tick 刷新一次
if (self.tickCount % 5 != 0) return;
EntityUtil.keepChunkLoaded(self, self.position());
EntityUtil.keepChunkLoaded(self, self.position().add(...));
```

| 刷新间隔 | 安全性 | 性能节省 |
|---------|--------|---------|
| 每 1 tick | ✅ 最安全 | 基准 |
| 每 5 tick | ✅ 安全（300/5=60 倍余量） | 降低 80% 调用 |
| 每 20 tick | ⚠️ 勉强（300/20=15 倍余量，高 TPS 波动时可能断开） | 降低 95% 调用 |
| 每 60 tick | ❌ 不安全 | 不推荐 |

### 4.4 性能评估

| 场景 | 载具数 | 每 tick 调用次数 | 额外耗时估算 |
|------|--------|-----------------|-------------|
| 正常玩法 | 3-5 | 6-10 | 6-50 μs（可忽略） |
| 大规模战斗 | 10-20 | 20-40 | 20-200 μs（轻微） |
| 极端压力测试 | 50+ | 100+ | 100+ μs（需配置限流） |

**对比**：本体 UAV 每 tick 3 次调用，弹药实体每 tick 2 次调用，均无性能问题。

### 4.5 边界情况处理

| 边界情况 | 处理方式 |
|---------|---------|
| 载具被摧毁 | `discard()` 后不再 tick，不再申请票，区块自然卸载 |
| Gunner 下车 | `hasGunnerDriver()` 返回 false，停止申请票 |
| 载具传送 | 下一 tick 自然在新位置申请票 |
| 服务器卡顿 | 票 300 tick 有效期，短时卡顿（< 300 tick）不会导致区块卸载 |
| 区块卸载后重新加载 | 实体从 NBT 恢复，下一 tick 重新申请票 |

---

## 五、与本体代码的关系

| 本体类 | 是否需要 Mixin | 说明 |
|--------|---------------|------|
| `AbstractVehicle` | ✅ 注入 `tick()` TAIL | 添加 Gunner 驾驶员区块加载逻辑 |
| `EntityUtil` | ❌ 直接调用 | `keepChunkLoaded()` 是 public static，可直接使用 |
| `GunnerEntity` | ❌ 不需要 | 只需 `instanceof` 判断，无需修改 |
| `GunnerBrain` | ❌ 不需要 | 驾驶逻辑不变，区块加载是透明的 |

**Mixin 注入点**：仅 1 处 — `AbstractVehicle.tick()` 的 `@At("TAIL")`

---

## 六、实现记录

### 已完成 ✅

| 步骤 | 状态 | 文件 |
|------|------|------|
| 创建 Mixin 类 | ✅ | `ywzj_rvp/.../mixin/AbstractVehicleGunnerChunkLoadMixin.java` |
| 注册 Mixin | ✅ | `ywzj_rvp/.../resources/ywzj_rvp.mixins.json` |

### 实现细节

**核心逻辑**：注入 `AbstractVehicle.tick()` 的 TAIL，按以下顺序短路求值：

1. 仅服务端 → `level().isClientSide()` 检查
2. 非UAV载具 → `this.uav` 字段检查（UAV 已有区块加载）
3. 载具未销毁 → `this.isDestroyed()` 检查
4. 驾驶员是 Gunner → `getDriver() instanceof GunnerEntity`
5. 距离玩家 ≤ 96 区块 → `ywzj_rvp$isTooFarFromAnyPlayer()` 检查

**96 区块距离限制**：
- 使用区块坐标（`blockPosition() >> 4`）比较，避免浮点运算
- 使用切比雪夫距离（`max(|dx|, |dz|)` 语义），即 dx 和 dz 分别 ≤ 96
- 96 区块 = 1536 格，是正常玩家加载范围（10 chunk ≈ 160 格）的 9.6 倍
- 足以覆盖 AI 驾驶员的正常作战半径（固定翼攻击半径最大 350 格）
- 超过此距离后区块强载器失效，防止无限制远距离区块加载

**前方预加载**：与本体 UAV 一致，加载前方 16 格区块，防止高速飞行载具撞入未加载区块。

### 待测试场景

- [ ] 地面 AI 驾驶员追击目标驶出玩家加载范围
- [ ] 固定翼 AI 飞出加载范围后继续飞行
- [ ] 旋翼 AI 追击飞出加载范围
- [ ] 验证载具销毁后区块自然卸载
- [ ] 验证 Gunner 下车后区块自然卸载
- [ ] 验证距离玩家超过 96 区块后区块强载器失效

---

## 七、总结

| 结论 | 说明 |
|------|------|
| **可行性** | ✅ 完全可行，已有成熟模式可复用 |
| **推荐方案** | 方案 A — `TicketType.POST_TELEPORT` 每 tick 刷新 |
| **实现量** | 1 个 Mixin 类 + 配置支持 |
| **性能影响** | 正常场景可忽略（< 50 μs/tick） |
| **风险** | 低 — 复用已验证的 API，自然清理无需手动管理 |

---

## 附录：关键文件路径

| 文件 | 角色 | 位置 |
|------|------|------|
| `AbstractVehicle.java` | 载具基类，tick() 中有 UAV 区块加载逻辑（第509-515行） | `ywzj_vehicle/.../entity/vehicle/` |
| `GunnerEntity.java` | Gunner 实体，脱载 40 tick 后 discard | `ywzj_rvp/.../entity/gunner/` |
| `GunnerBrain.java` | Gunner AI，控制驾驶/射击 | `ywzj_rvp/.../entity/gunner/ai/` |
| `EntityUtil.java` | `keepChunkLoaded()` 工具方法（第36-38行） | `ywzj_vehicle/.../util/` |
| `AmmoEntity.java` | 弹药基类，`keepChunkLoaded` 参考实现（第84-87行） | `ywzj_vehicle/.../entity/weapon/` |
| `AbstractVehicleLinkedUavMixin.java` | RVP 已有的区块加载 Mixin（可部署 UAV），**最佳参考模板** | `ywzj_rvp/.../mixin/` |
| `ContainerCraft.java` | 载具父类，`shouldBeSaved()=true` | `ywzj_vehicle/.../entity/` |
| `ChunkPosSavedData.kt` | SBW 的服务器重启区块恢复方案参考 | `SBW/.../world/saveddata/` |
