# Gunner 与弹药区块加载（Chunk Loading）功能调研文档

> 本文档面向 ywzj_rvp 开发者，梳理 Gunner（AI 炮手）系统和弹药区块加载（Chunk Loading）的架构、数据流及关键代码入口。

---

## 目录

- [一、Gunner 系统](#一gunner-系统)
  - [1.1 系统概览](#11-系统概览)
  - [1.2 核心类及职责](#12-核心类及职责)
  - [1.3 生成与登车流程](#13-生成与登车流程)
  - [1.4 AI 主循环（GunnerBrain.tick）](#14-ai-主循环gunnerbraintick)
  - [1.5 武器操作与切换](#15-武器操作与切换)
  - [1.6 Mixin 扩展层](#16-mixin-扩展层)
  - [1.7 阵营与目标选择](#17-阵营与目标选择)
  - [1.8 数据流图](#18-数据流图)
  - [1.9 JSON 配置（Gunner Profile）](#19-json-配置gunner-profile)
- [二、弹药区块加载（Chunk Loading）](#二弹药区块加载chunk-loading)
  - [2.1 问题背景](#21-问题背景)
  - [2.2 核心实现：EntityUtil.keepChunkLoaded()](#22-核心实现entityutilkeepchunkloaded)
  - [2.3 AmmoEntity 基类的区块加载逻辑](#23-ammoentity-基类的区块加载逻辑)
  - [2.4 本体各类弹药的区块加载状态](#24-本体各类弹药的区块加载状态)
  - [2.5 RVP 弹药的区块加载](#25-rvp-弹药的区块加载)
  - [2.6 区块加载的运行时流程](#26-区块加载的运行时流程)
  - [2.7 与本体 BulletEntity 的差异](#27-与本体-bulletentity-的差异)
  - [2.8 潜在问题与优化方向](#28-潜在问题与优化方向)
- [三、Gunner 与区块加载的交互](#三gunner-与区块加载的交互)

---

## 一、Gunner 系统

### 1.1 系统概览

Gunner 系统允许玩家生成 AI 控制的炮手实体，使其登上载具（`AbstractVehicle`）并自动操控武器站（`WeaponUnit`）进行战斗。整个系统分为**实体层**、**AI 决策层**、**配置层**和 **Mixin 扩展层**四大模块。

> **核心原则**：GunnerEntity 是纯粹的 AI 实体（`Mob`），不是玩家替身。它骑乘载具，通过 `AbstractVehicle.getOwnOperatorUnit()` 关联到武器站。

### 1.2 核心类及职责

#### 实体层

| 类 | 路径 | 职责 |
|---|---|---|
| `GunnerEntity` | `entity/gunner/GunnerEntity.java` | 炮手实体，继承 `Mob`。持有运行时状态（目标追踪、爆发射击、驾驶状态、战术规避等），通过 `SynchedEntityData` 同步关键数据给客户端。 |
| `GunnerRenderer` | `client/render/GunnerRenderer.java` | 客户端渲染器，使用 `PlayerModel`（Slim 模型），0.9375 缩放。 |
| `RVP_GunnerOverlay` | `client/gui/RVP_GunnerOverlay.java` | HUD 叠加层，显示载具上所有炮手的 Profile、武器索引和追踪目标。 |

**GunnerEntity 关键同步字段**：

| 字段 | 说明 |
|------|------|
| `PROFILE_ID` / `PROFILE_FACTION` | 炮手配置 ID 和阵营 |
| `TRACKED_TARGET_ID` | 当前追踪目标的实体 ID |
| `CONTROLLED_WEAPON_INDEX` | 当前控制的武器索引 |
| `ownerUuid` / `ownerTeamName` | 归属玩家和队伍（友军判定） |
| `burstFireTicks` / `burstRestTicks` | 爆发射击节奏控制 |
| `countermeasureCooldown` | 红外诱饵/烟幕冷却 |
| `detachedTicks` | 离开载具后的存活计时器（40 tick 后自动移除） |

#### AI 决策层

| 类 | 路径 | 职责 |
|---|---|---|
| `GunnerBrain` | `entity/gunner/ai/GunnerBrain.java` | 核心行为逻辑，每 tick 调用。统筹目标扫描、雷达锁定、对抗措施、驾驶控制、战斗射击。 |
| `GunnerTargeting` | `entity/gunner/ai/GunnerTargeting.java` | 目标搜索与评分。根据 Profile 的 `target_types` 过滤，用距离+角度评分选出最佳目标；检测来袭弹药威胁。 |
| `GunnerProfile` | `entity/gunner/ai/profile/GunnerProfile.java` | JSON 可配置的炮手参数集合。 |
| `GunnerProfileManager` | `entity/gunner/ai/profile/GunnerProfileManager.java` | 资源重载监听器，从 `gunner_profiles/` 加载 JSON。 |
| `RVP_EnumGunnerFaction` | `entity/gunner/ai/profile/RVP_EnumGunnerFaction.java` | 阵营枚举：`FRIENDLY`、`ENEMY`、`TEAM`。 |

#### 生成物品层

| 类 | 路径 | 职责 |
|---|---|---|
| `GunnerSpawnerItem` | `item/GunnerSpawnerItem.java` | 通用炮手生成器。右键载具生成炮手，Shift+右键切换座位，普通右键切换 Profile。 |
| `FixedProfileGunnerSpawnerItem` | `item/FixedProfileGunnerSpawnerItem.java` | 固定 Profile 炮手生成器，用于敌人/NPC 炮手。 |

### 1.3 生成与登车流程

```
玩家持有 GunnerSpawnerItem → 右键载具
  ├── GunnerSpawnerItem.interactEntity()
  ├── 检测目标为 AbstractVehicle
  ├── 创建 GunnerEntity，调用 initOwner(player) 设置归属
  ├── gunner.setProfileId(...) 设置 AI 配置
  ├── gunner.startRiding(vehicle, true) 骑乘载具
  ├── 有 preferredSeat → vehicle.changeSeat()
  └── 无 preferredSeat → trySelectWeaponSeat() 自动选择武器座位
        └── vehicle.getOwnOperatorUnit(gunner) 返回 WeaponUnit → 优先坐入
```

**离载具处理**：炮手不在载具时 `detachedTicks` 递增，超过 40 tick 自动 `discard()`。

### 1.4 AI 主循环（GunnerBrain.tick）

```
GunnerBrain.tick(gunner, vehicle)
  ├── gunner.tickCooldowns()
  ├── 获取 GunnerProfile / seatUnit / isDriver
  ├── resolveWeaponUnit()
  ├── tickTargeting()           -- 周期性扫描目标
  ├── tickCountermeasure()      -- 检测来袭弹药，发射诱饵
  ├── tickRadarLock()           -- ENEMY 阵营的 RF 雷达锁定
  ├── if (driver && allowDrive):
  │     ├── refillDriverVehicle()       -- 补满弹药/能量/引擎
  │     ├── sustainDriverSingleShotWeapons() -- 单发武器自动装填
  │     └── tickDriving()               -- 地面/固定翼/旋翼驾驶
  └── if (weaponUnit && target && allowFire):
        └── tickCombat()               -- 瞄准 + 选择武器 + 射击
```

**驾驶分派**：

| 载具类型 | 方法 | 行为 |
|---------|------|------|
| 地面 | `tickGroundDriving()` | 追踪目标、战术停火/规避、卡住恢复、漫游 |
| 固定翼 | `tickFixedWingDriving()` | 攻击/脱离阶段切换、巡航高度、俯冲攻击 |
| 旋翼 | `tickRotaryDriving()` | 起降、攻击姿态、低空保护 |

**战斗流程** (`tickCombat`)：

1. `predictAimPoint()` 计算预测瞄准点 + `leadScale` 提前量
2. `weaponUnit.aim(aimPoint)` 转动武器站
3. `selectWeaponIndex()` 选第一个有弹、未冷却、非对抗措施的武器
4. 检查瞄准误差 < `fireWindowDeg`
5. `weaponUnit.shoot(weaponIndex, aimContexts, gunner)` 射击
6. 更新爆发射击节奏

### 1.5 武器操作与切换

#### 本体 WeaponUnit 结构（只读参考）

```
WeaponUnit
  ├── weapons: List<AbstractVehicleWeapon>          -- 主武器
  ├── secondaryWeapons: List<AbstractVehicleWeapon>  -- 副武器
  ├── independentWeapons: List<AbstractVehicleWeapon> -- 独立武器
  ├── indexedWeapons: List<AbstractVehicleWeapon>     -- 统一索引
  ├── currentWeaponIndex / currentSecondaryWeaponIndex
  ├── switchWeapon(secondary, next)    -- 循环切换
  ├── cycleMultiWeapon(next)           -- VehicleMultiWeapons 子武器切换
  ├── shoot(weaponIndex, aimContexts, operator)
  ├── aim(Vec3 worldPos)
  └── tickFireControl() / fireControlLock() / toggleSeeker(Boolean)
```

**射击流程**：
1. 检查弹舱（`WeaponBayUnit`）是否开启
2. 触发 `VehicleFireEvent.Pre`
3. 调用 `weapon.shoot(aimContexts, operator)`
4. 触发 `VehicleFireEvent.Post`
5. 发送 `ServerVehicleFire` 网络包

### 1.6 Mixin 扩展层

#### Accessor Mixin（暴露私有字段/方法）

| Mixin | 目标类 | 暴露内容 | 用途 |
|---|---|---|---|
| `GunnerWeaponAccessorMixin` | `AbstractVehicleWeapon` | `setReloadTime(int)` (Invoker) | AI 驾驶员强制设 reloadTime=0 |
| `PartUnitAccessorMixin` | `PartUnit` | `data` 字段 | 读取 PartUnitData |
| `WeaponUnitAccessor` | `WeaponUnit` | `weaponBayUnits`、`currentWeaponIndex` 等 | 访问内部字段 |

#### 行为注入 Mixin

| Mixin | 目标方法 | 注入点 | 功能 |
|---|---|---|---|
| `WeaponUnitSwitchWeaponMixin` | `setCurrentWeaponIndex` 等 | TAIL | 切换武器时关闭/恢复 seeker、雷达锁定 |
| `WeaponUnitSetWeaponMixin` | `tick` | TAIL | 检测武器索引变化，自动开关弹舱 |
| `WeaponUnitTickFireControlMixin` | `tickFireControl` | TAIL | RVP 导弹自动锁定；ARM 跳过 IR/RF 锁定 |
| `WeaponUnitFireControlLockMixin` | `fireControlLock` | HEAD+TAIL | 接管 RF 雷达锁定流程；锁定后自动开导引头 |
| `WeaponUnitGetSeekerFovMixin` | `getSeekerFov` | RETURN | 修正 RVP 武器 seekerFov 返回值 |
| `WeaponUnitSensorOverrideMixin` | `getFireControlSensorType` | HEAD | 允许 RVP 武器覆盖火控传感器类型 |
| `WeaponUnitSoftRfMixin` | `tickFireControl` 中 `aim()` | Redirect | "软 RF"：限制离轴角度 + 瞄准平滑 + 机枪提前量 |
| `WeaponUnitFollowParentRotationMixin` | `updateRot` | TAIL | 子 PartUnit 跟随父级旋转 |
| `WeaponUnitCustomMountSuppressMixin` | `render` / `onClientFire` | HEAD / TAIL | 抑制默认渲染，改用 RVP 自定义挂载渲染 |
| `VehicleMultiWeaponsChargeGateMixin` | `VehicleMultiWeapons.doClientShoot` | HEAD+RETURN | RVP 武器发射门控（见第二章） |

#### 数据扩展 Mixin（接口注入额外字段）

| Mixin | 目标类 | 扩展字段 | 用途 |
|---|---|---|---|
| `WeaponUnitArmMixin` | `WeaponUnit` | `armPreselectedVehicleId` 等 | ARM 反辐射导弹预选目标 |
| `WeaponUnitDataMixin` | `WeaponUnitData` | `fireControlMode`, `rfOffAxisDeg` 等 | RVP 扩展武器站数据 |
| `WeaponUnitPojoMixin` | `WeaponUnitPojo` | 同上 | JSON 反序列化层 |
| `WeaponUnitPendingRadarLockMixin` | `WeaponUnit` | `pendingRadarLockEntityId` | 雷达预锁定请求 |
| `WeaponUnitExternalRadarLockMixin` | `WeaponUnit` | `externalRadarRequestedEntityId` 等 | 外部雷达锁定状态 |

### 1.7 阵营与目标选择

**阵营** (`RVP_EnumGunnerFaction`)：

| 阵营 | 行为 |
|------|------|
| `FRIENDLY` | 过滤同队目标 |
| `ENEMY` | 不过滤队伍关系，会主动雷达锁定玩家 |
| `TEAM` | 只攻击非同盟敌人 |

**目标类型** (`GunnerTargeting.matchProfileTarget()` 支持)：

- 实体类型：`rvp:missile`, `monster`, `player`, `neutral`, `living`, `vehicle`
- 载具子类型：`vehicle:enemy_gunner`, `vehicle:friendly_gunner`, `vehicle:team_gunner`, `vehicle:player`, `vehicle:non_allied_gunner`

敌方炮手会优先攻击其他敌方炮手驾驶的载具（`isRelativeHostileGunnerVehicle`）。

### 1.8 数据流图

#### 射击流程

```
GunnerBrain.tickCombat()
  ├── aimPoint = predictAimPoint() + leadScale
  ├── weaponUnit.aim(aimPoint)
  │     ├── aimRot() → 计算本地旋转
  │     └── (客户端) ClientVehicleAction 网络包
  ├── selectWeaponIndex() → 遍历 indexedWeapons
  ├── 瞄准误差 < fireWindowDeg?
  └── weaponUnit.shoot(weaponIndex, aimContexts, gunner)
        ├── 检查弹舱
        ├── VehicleFireEvent.Pre
        ├── weapon.shoot(aimContexts, operator)
        │     ├── 消耗弹药
        │     └── 创建弹药实体
        ├── VehicleFireEvent.Post
        └── ServerVehicleFire 网络包
```

#### AI 驾驶员补弹流程

```
GunnerBrain.refillDriverVehicle()
  ├── markDriverRide()
  ├── 设置 homePos
  ├── toggleEngine(true)
  ├── setEnergy(max)
  └── 遍历所有 WeaponUnit 的武器:
        ├── weapon.setRemainAmmo(maxCapacity)
        └── GunnerWeaponAccessorMixin.ywzj_rvp$setReloadTime(0)
              ↑ 通过 Invoker Mixin 调用私有方法

sustainDriverSingleShotWeapons()
  ├── 遍历 maxCapacity==1 的单发武器
  ├── 弹药用尽 → readyTime = now + 装填间隔
  ├── 定期设置 reloadTime 模拟装填进度
  └── 到时 → setRemainAmmo(1) + setReloadTime(0)
```

### 1.9 JSON 配置（Gunner Profile）

路径：`data/<namespace>/gunner_profiles/<id>.json`

| 字段 | 说明 | 默认值 |
|------|------|--------|
| `name` | 配置名称 | 文件名 |
| `faction` | 阵营 | `friendly` |
| `target_types` | 目标类型列表 | — |
| `search_radius` | 搜敌半径 | `96` |
| `scan_interval_tick` | 搜敌间隔 | `10` |
| `fire_window_deg` | 开火窗口角（度） | `6` |
| `lead_scale` | 提前量倍率 | `1.0` |
| `burst_fire_tick` / `burst_rest_tick` | 扳机按住/松开节奏 | `6` / `10` |
| `countermeasure_range` | 干扰弹威胁距离 | `36` |
| `countermeasure_cooldown_tick` | 干扰弹冷却 | `80` |
| `allow_drive` | 允许驾驶载具 | `true` |
| `drive_pursuit_distance` / `drive_stop_distance` | 地面追击/刹车距离 | `64` / `12` |
| `drive_stuck_check_tick` / `drive_stuck_distance` / `drive_recovery_tick` | 卡住检测与恢复 | `20` / `1.0` / `20` |
| `rotary_cruise_altitude_min` / `rotary_cruise_altitude_max` | 直升机巡航高度 | `28` / `60` |
| `fixedwing_cruise_altitude_min` / `fixedwing_cruise_altitude_max` | 固定翼巡航高度 | `150` / `500` |
| `fixedwing_combat_radius_min` / `fixedwing_combat_radius_max` | 固定翼攻击半径 | `40` / `350` |
| `ground_wander_enabled` | 地面载具闲逛机动 | `true` |
| `ground_big_turn_interval_tick_min` / `_max` | 地面大转向间隔 | `300` / `600` |
| `ground_big_turn_angle_deg_min` / `_max` | 地面大转向角 | `120` / `180` |
| `ground_big_turn_duration_tick` | 大转向持续时间 | `40` |
| `air_attack_phase_tick` / `air_disengage_phase_tick` | 空中攻击/脱离时长 | `200` / `200` |

---

## 二、弹药区块加载（Chunk Loading）

### 2.1 问题背景

Minecraft 的服务端只 tick 玩家附近的区块。当弹药（导弹、火箭、炸弹等）飞出已加载区块后，服务端不再更新其位置，导致：

- 弹药"悬停"在区块边界，不再移动
- 碰撞检测停止工作，弹药无法命中目标
- 制导系统失灵（导弹无法追踪）

**弹药区块加载**就是在弹药飞行期间，强制加载弹药所在及前方区块的机制，确保弹药可以跨区块飞行并正常工作。

### 2.2 核心实现：`EntityUtil.keepChunkLoaded()`

```java
// ywzj_vehicle / org.ywzj.vehicle.util.EntityUtil
public static void keepChunkLoaded(Entity entity, Vec3 position) {
    ChunkPos chunkpos = new ChunkPos(BlockPos.containing(position));
    ((ServerLevel) entity.level())
        .getChunkSource()
        .addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 3, entity.getId());
}
```

**原理**：
- 使用 Minecraft 的 `TicketType.POST_TELEPORT` 区块票类型
- 距离参数 `3` 表示加载范围约 3 个区块半径
- `entity.getId()` 作为票的标识，每个实体独立持票
- 区块票会在一段时间后自动过期（约 300 tick），因此需要每 tick 重新申请

### 2.3 `AmmoEntity` 基类的区块加载逻辑

```java
// ywzj_vehicle / org.ywzj.vehicle.entity.weapon.AmmoEntity
public abstract class AmmoEntity extends Projectile implements IEntityAdditionalSpawnData {
    // ...
    protected boolean keepChunkLoaded = false;  // 默认关闭

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            tickLerp();
        } else {
            triggered = true;
            if (discard) {
                this.discard();
            }
            if (keepChunkLoaded) {
                // 1. 加载弹药当前所在区块
                EntityUtil.keepChunkLoaded(this, this.position());
                // 2. 预加载弹药前方 16 格区块（提前加载避免高速弹药撞墙）
                EntityUtil.keepChunkLoaded(this, this.position().add(getLookAngle().normalize().scale(16)));
            }
        }
    }
}
```

**关键设计**：

| 要点 | 说明 |
|------|------|
| **仅服务端** | 区块加载只在服务端执行（`!level().isClientSide()`） |
| **双重加载** | 当前位置 + 前方 16 格，防止高速弹药穿越未加载区块 |
| **每 tick 重新申请** | `TicketType.POST_TELEPORT` 票会自动过期，需要每 tick 刷新 |
| **字段控制** | `keepChunkLoaded` 布尔字段控制是否启用，子类按需设置 |

### 2.4 本体各类弹药的区块加载状态

| 弹药实体 | 父类 | `keepChunkLoaded` | 原因 |
|---------|------|-------------------|------|
| **`MissileEntity`** | `AmmoEntity` | ✅ `true` | 导弹有制导，需长距离飞行 |
| **`RocketEntity`** | `AmmoEntity` | ✅ `true` | 火箭弹射程远，需跨区块 |
| **`AerialBombEntity`** | `AmmoEntity` | ✅ `true` | 炸弹高空投下，跨区块落地 |
| **`BulletEntity`** | `AmmoEntity` | ❌ `false`（默认） | 机枪子弹射程短，通常不跨区块 |
| **`DecoyFlareEntity`** | `AmmoEntity` | ❌ `false`（默认） | 诱饵弹短距离飞行 |
| **`GrenadeEntity`** | `Projectile`（非 AmmoEntity） | ❌ 无此机制 | 手榴弹类，不继承 AmmoEntity |
| **`ActiveProtectionGrenadeEntity`** | `GrenadeEntity` | ❌ 无此机制 | APS 拦截弹，短距离 |

### 2.5 RVP 弹药的区块加载

```java
// ywzj_rvp / org.ywzj.rvp.entity.projectile.RVP_BaseBullet
public abstract class RVP_BaseBullet extends AmmoEntity implements RemoteTickEntity {
    public RVP_BaseBullet(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
        this.keepChunkLoaded = true;  // RVP 所有弹体默认启用区块加载
    }
}
```

**RVP 设计决策**：所有 `RVP_BaseBullet` 子类（导弹、火箭、炸弹、机枪弹、布撒器载荷）**默认启用区块加载**。

| RVP 弹药实体 | `keepChunkLoaded` | 说明 |
|-------------|-------------------|------|
| `RVP_BaseBullet`（所有子类） | ✅ `true` | 统一启用，包括机枪弹 |
| `RVP_MissileEntity` | ✅ `true` | 继承自 RVP_BaseBullet |
| `RVP_RocketEntity` | ✅ `true` | 继承自 RVP_BaseBullet |
| `RVP_BombEntity` | ✅ `true` | 继承自 RVP_BaseBullet |
| `RVP_BulletEntity` | ✅ `true` | 继承自 RVP_BaseBullet |
| `RVP_DispensedEntity` | ✅ `true` | 继承自 RVP_BaseBullet |

### 2.6 区块加载的运行时流程

```
每服务端 tick：
  AmmoEntity.tick()
    └── if (!level().isClientSide() && keepChunkLoaded):
          ├── EntityUtil.keepChunkLoaded(this, this.position())
          │     └── ServerLevel.getChunkSource().addRegionTicket(
          │           POST_TELEPORT, 当前区块Pos, 距离3, entity.getId())
          │
          └── EntityUtil.keepChunkLoaded(this, position + lookAngle*16)
                └── ServerLevel.getChunkSource().addRegionTicket(
                      POST_TELEPORT, 前方区块Pos, 距离3, entity.getId())

区块票生命周期：
  addRegionTicket() → 区块被加载到内存 → 300 tick 后票过期自动释放 → 下 tick 重新申请
  弹药 discard() 后 → 不再申请新票 → 区块票自然过期 → 区块可被卸载
```

### 2.7 与本体 `BulletEntity` 的差异

本体 `BulletEntity`（机枪弹）**不启用**区块加载，原因是机枪弹射程短、速度快，通常在 1-2 个 tick 内就能命中或消失。但 RVP 的 `RVP_BulletEntity` 统一启用了区块加载，原因：

1. RVP 机枪弹可能有更大射程（如 30mm 机炮、大口径机炮）
2. RVP 机枪弹支持穿墙、衰减等特性，跨区块飞行更常见
3. 统一启用简化逻辑，避免遗漏

### 2.8 潜在问题与优化方向

| 问题 | 说明 |
|------|------|
| **服务器性能** | 每个弹药每 tick 申请 2 张区块票，大量弹药同时飞行可能造成性能压力 |
| **区块票过期** | `POST_TELEPORT` 票约 300 tick 后过期，但弹药每 tick 刷新，影响不大 |
| **高速弹药的区块穿越** | 前方 16 格预加载对高速导弹可能不够（速度 > 1 chunk/tick 时），可能仍会穿过未加载区块 |
| **弹药消亡后的区块残留** | 弹药 `discard()` 后不再申请票，但最后一次申请的票仍有约 300 tick 的残留加载时间 |
| **无配置化** | `keepChunkLoaded` 是硬编码布尔值，无法通过 JSON 配置开关 |

---

## 三、Gunner 与区块加载的交互

1. **Gunner AI 射击的弹药也会触发区块加载**：Gunner 通过 `weaponUnit.shoot()` 发射的弹药，走的是同一套 `AmmoEntity.tick()` → `keepChunkLoaded` 逻辑
2. **Gunner 本身不需要区块加载**：GunnerEntity 作为乘客坐在载具上，跟随载具的区块加载状态
3. **Gunner 发射的导弹可跨区块追踪**：启用区块加载后，AI 炮手发射的导弹可以飞越多个区块追踪目标
4. **AI 驾驶员补弹不影响区块加载**：`refillDriverVehicle()` 只补充弹药数量，不修改 `keepChunkLoaded` 状态
