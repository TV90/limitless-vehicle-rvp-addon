# ywzj_rvp 爆炸反应装甲（ERA）开发方案

## 架构概览

两套独立的 ERA 破坏机制并行共存，互不冲突：

```
武器命中载具
    │
    ├── 直击命中（有弹道/线段）
    │      │
    │      ├── APFSDS / 穿甲弹 / 直击武器
    │      │     └── OBB 射线求交 → 命中单块 ERA → 消耗它 → 穿透继续
    │      │
    │      └── HE 弹（有爆炸范围）
    │            └── 按爆炸范围分档 → 百分比摧毁直击附近的 ERA 块
    │
    └── 非直击爆炸（附近爆炸）
           └── 按爆炸范围 + 距离衰减 → 百分比摧毁远处的 ERA 块（无 1 保底）
```

---

## 机制一：AP/穿甲弹 → OBB 穿透式命中（单块消耗）

### 适用对象

- 直击伤害高、爆炸范围 ≤ 5 或无爆炸的弹药
- APFSDS、破甲弹（HEAT）、半穿甲弹等

### 工作流程

1. 弹道线段与载具所有 ERA bone 的 OBB 做射线求交
2. 得到按距离从近到远排序的 ERA 命中列表
3. 从最近开始处理：
   - 若该 ERA 已失效 → 跳过，继续处理下一块
   - 若该 ERA 激活 → 按 `damage_factor` 结算伤害，该块消耗→失效
   - 弹药穿透继续命中后方车体碰撞箱

### 配置（vehicle json）

```json
{
  "hitbox_era": {
    "Upper_front_era": {
      "damage_factor": 0.35,
      "min_trigger_damage": 12.0,
      "explosion": 1.5
    },
    "Side_skirt_era": {
      "damage_factor": 0.5,
      "min_trigger_damage": 8.0,
      "explosion": 1.0
    }
  }
}
```

| 字段 | 意义 |
|:--|:--|
| `key`（boneName） | 载具模型中的骨骼名，该骨骼下的 cubes 被当作 ERA 碰撞箱 |
| `damage_factor` | 命中该 ERA 时对伤害的倍率（减伤系数） |
| `min_trigger_damage` | 触发阈值，用 `predictedBaseDamage` 判定，低于此值不触发不消耗 |
| `explosion` | 可选，触发特效强度 |

---

## 机制二：HE/高爆弹 → 爆炸范围分档式破坏（百分比）

### 适用对象

- 爆炸范围 > 5 的弹药
- HE 高爆弹、航弹、火箭弹、导弹的战斗部

### 规则

#### A. 直击命中载具

以弹药爆炸范围（`explosion.radius`）查阈值表，**摧毁离直击位置最近的 N 块 ERA**：

| 爆炸范围 | 破坏比例 | 保底 |
|:--:|:--:|:--:|
| 0 ~ 5 | 0%（不破坏） | 0 |
| 5 ~ 8 | 10% | **至少 1 块** |
| 9 ~ 12 | 25% | **至少 1 块** |
| 13 ~ 18 | 50% | **至少 1 块** |
| 19+ | **100%（全毁）** | 全部 |

计算方式：
```java
destructCount = Math.max(minGuarantee, (int) Math.floor(eraTotalCount * percentage));
```
按"离直击命中点最近的 blocks"顺序选取。

#### B. 非直击命中（附近爆炸）

非直击爆炸指爆炸中心没有直接命中载具，但载具在爆炸范围内。

- 不按直击位置计算
- 按爆炸中心到车体最近点的距离 `dist` 做线性衰减
- 无 1 保底（可衰减到 0）

```java
float distanceFactor = 1.0 - (dist / radius);
// 以最大档位查表，再用 distanceFactor 衰减
float effectiveRadius = radius * distanceFactor;
// 按 effectiveRadius 查上述阈值表
// 无 Math.max(1, ...) 保底
```

**示例**：radius=15 的航弹在车旁 10 格爆炸
- `distanceFactor = 1.0 - 10/15 = 0.333`
- `effectiveRadius = 15 × 0.333 = 5`
- 进入 0~5 档 → 不破坏（衰减后半径不够）

**示例2**：同样航弹在车旁 4 格爆炸
- `distanceFactor = 1.0 - 4/15 = 0.733`
- `effectiveRadius = 15 × 0.733 = 11`
- 进入 9~12 档 → 破坏 25%（无保底）

---

## 两种机制共存时的联动

| 弹药特性 | 直击伤害 | 爆炸范围 | 走哪条路径 |
|:--|:--:|:--:|:--|
| APFSDS | 高 | 0~5 | 机制一（OBB 穿透） |
| 125mm HE | 低 | 8 | 机制二A（直击百分比） |
| 航弹/JDAM | 中 | 25+ | 机制二A（直击→全毁） |
| 附近爆炸（未直击） | — | 任意 | 机制二B（衰减百分比） |

注意：
- **两种机制不同时执行**——一发弹药要么走机制一，要么走机制二
- 区分依据：`explosion.radius > 5` 走机制二，否则走机制一
- 机制一（OBB 穿透）只消耗命中的那一块 ERA，不涉及百分比

---

## 运行时状态模型

### 数据结构

载具维护一个 `Map<boneName, Boolean>` 或 `BitSet`：
- 以 `boneName` 为 key
- `true` = 激活（ERA 仍在）
- `false` = 失效（已被消耗）

### 初始化

- 载具生成时，从配置读取所有 ERA bone 名称，全部设为激活

### 同步

- 服务端权威，状态变化时通过 S2C 数据包同步到客户端
- 持久化：写入实体 NBT，支持重登后保持失效状态

### 渲染

- 客户端同步 bone 状态
- JS 脚本中通过 `v.rvp_isEraActive("boneName")` 查询状态
- 失效的 bone 通过 `pose.hideBone("boneName")` 隐藏

---

## 配置数据结构（vehicle json 顶层新增）

```json
{
  "hitbox_era": {
    "Upper_front_era": {
      "damage_factor": 0.35,
      "min_trigger_damage": 12.0,
      "explosion": 1.5
    }
  }
}
```

- `hitbox_era` 不存在 = 该载具无 ERA
- ywzj_vehicle 忽略未知字段，无冲突

---

## 代码调研

### 已有基础设施（全部就绪）

| 组件 | 位置 | 状态 |
|:--|:--|:--:|
| RVPEraStateAccess 接口 | [RVPEraStateAccess.java](file:///d:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/ext/RVPEraStateAccess.java) | ✅ |
| AbstractVehicleEraStateMixin | [AbstractVehicleEraStateMixin.java](file:///d:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/AbstractVehicleEraStateMixin.java) | ✅ S2C同步+NBT持久化+生成数据包 |
| S2CVehicleEraState 包 | [S2CVehicleEraState.java](file:///d:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/network/S2CVehicleEraState.java) | ✅ |
| VehicleHitboxConfig 加载 | [RVP_VehicleHitboxFactorManager.java](file:///d:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/weapon/damage/RVP_VehicleHitboxFactorManager.java) | ✅ 从 `rvp/vehicles/<id>.json` 加载 |
| 机制一单块消耗 | [RVP_VehicleHitboxFactorManager.java#L136-L158](file:///d:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/weapon/damage/RVP_VehicleHitboxFactorManager.java#L136-L158) | ✅ `tryTriggerEra()` |
| 机制一触发点 | [RVP_BaseBullet.java#L1012-L1014](file:///d:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java#L1012-L1014) | ✅ `applyEntityHitDamage()` 末尾 |
| OBB 射线求交 | [VectorUtil.java#L155-L214](file:///d:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/util/VectorUtil.java#L155-L214) | ✅ `closestHitObbPosition()` → `hitOBB()` |

### 缺失组件

| 组件 | 作用 |
|:--|:--|
| RVP_EraDestructionHelper | 核心逻辑：计算爆炸半径对应百分比，筛选离直击点最近的 N 块活跃 ERA，标记失效 |
| VehicleExplosion 接入 | 非直击爆炸：`triggerExplosion()` 后遍历范围内的载具 |

### 数据流

```
弹药命中（服务端）
    │
    ├── findEntityOnPath → handleEntityImpact → applyEntityHitDamage
    │      ├── RVP_VehicleHitboxFactorManager.resolveHitboxDamage()
    │      │      └── OBB 射线求交 → 找到命中的 bone
    │      ├── EntityUtil.hurt() → DamageSystem.hurt() → vehicle.setHealth()
    │      └── tryTriggerEra(vehicle, hitboxRes, preHitboxDamage)   ← 机制一（已有）
    │
    ├── resolveImpactDetonation → triggerExplosion
    │      └── VehicleExplosion.explode()
    │             └── hurt() → EntityUtil.hurt() → AbstractVehicle.hurt()
    │
    └── [新增] applyEntityHitDamage 末尾:
           如果 explosion.radius > 5（直击HE）:
              RVP_EraDestructionHelper.destroyByExplosionRadius(vehicle, radius, hitPos, true)
       
       [新增] triggerExplosion 末尾:
           ex.explode() 返回后:
              遍历爆炸范围内所有载具:
                 RVP_EraDestructionHelper.destroyByExplosionRadius(v, radius, null, false)
```

### 实现方案

#### RVP_EraDestructionHelper 核心方法

```java
public static void destroyByExplosionRadius(
    AbstractVehicle vehicle,
    float explosionRadius,
    @Nullable Vec3 hitPos,       // 直击命中点（非直击传 null）
    boolean isDirectHit          // true=直击（有保底）, false=非直击（无保底）
)
```

**计算步骤**：
1. 从 `RVP_VehicleHitboxFactorManager.INSTANCE` 获取 `VehicleHitboxConfig`
2. 获取所有 ERA bone 名称列表 → 总块数
3. 过滤出当前活跃的（`rvp_isEraActive()` → true）
4. 查阈值表确定破坏百分比 → 计算破坏数量
5. 有 `hitPos` 时：按 bone 的 OBB 中心到 hitPos 距离排序；无时：随机选取
6. 调用 `rvp$consumeEra()` 逐一标记失效
7. 触发 `tryTriggerEra()` 的视觉特效（粒子+音效）

#### 区分 HE/AP

```java
// 在 applyEntityHitDamage 中：
if (explosion != null && explosion.explode && explosion.radius > 5) {
    // HE 弹 → 机制二A（百分比破坏）
    RVP_EraDestructionHelper.destroyByExplosionRadius(targetVehicle, explosion.radius, hitPos, true);
} else {
    // 非 HE → 机制一（OBB 单块）
    RVP_VehicleHitboxFactorManager.INSTANCE.tryTriggerEra(targetVehicle, hitboxRes, preHitboxDamage);
}
```

---

## 开发阶段

1. ✅ **机制一（OBB 穿透）基线实现** — 已完成
2. ✅ **同步 + 持久化** — S2C 同步 + NBT 保存已完成
3. **机制二（百分比破坏）实现** — RVP_EraDestructionHelper + 两个接入点
4. **模型渲染联动** — JS 脚本 `hideBone` API
