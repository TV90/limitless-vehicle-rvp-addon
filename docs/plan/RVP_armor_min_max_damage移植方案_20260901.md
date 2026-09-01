# RVP `armor_min_damage` / `armor_max_damage` 移植方案

> 状态：**已落码（2026-09-01）**，实现记录见文末 §9。`./gradlew build` 编译通过；
> §7 的游戏内数值验收与 runServer 冒烟仍待执行。
> 参考实现：MCH-Reforged `ArmorMinDamage` / `ArmorMaxDamage`（`MCH_EntityAircraft#attackEntityFrom` 1085-1099 行）。
> 相关已有文档：`docs/RVP伤害倍率与爆炸.md`（武器侧 `direct_damage_factor`）。

---

## 1. 目标与设计决策（已定）

新增两个**载具级**装甲参数：

| 参数 | 默认值 | 语义 |
| --- | --- | --- |
| `armor_min_damage` | `0` | 固定值扣减。扣减后低于下限则**保底到 0.1** |
| `armor_max_damage` | `0`（不封顶） | 单发伤害上限 |

三条已确认的决策：

1. **与本体 `damage_threshold` 互斥**：两者同时存在时**仅 `armor_min_damage` 生效**，
   本体的 `damage_threshold` 在本次结算中被屏蔽。
2. **不做"完全免疫"**：与 MCH 的 `return false` 不同，扣减后 ≤0 时**保底为 0.1**（沿用本体
   `damage_threshold` 的手感）——低伤武器仍能造成擦伤，受击音效 / `HitVehicleEvent` / 命中提示照常触发。
3. **不新增 mixin，不污染服务端**：全部走已有 Forge 事件 + 公开 API，不注入本体类。

---

## 2. 现状：RVP 的两条伤害路径

两条路径**最终都汇入本体 `DamageSystem.hurt()`**，且命中箱系数都在进入之前就已乘完。

### 路径 ①：非 RVP 伤害源（本体武器、其它 mod、玩家近战等）

`RVP_VehicleHurtScalingHandler#onVehicleAttack`（监听 `VehicleAttackEvent`，
该事件在 `DamageSystem.hurt` **之前**触发且**可取消**）：

```
predicted    = predictBaseDamage(...)            // 复刻本体结算结果
deltaAfterCore = predicted 经 coreMult 调制       // core_distance_scale_multiplier
desiredFinal = deltaAfterCore × hitboxMult        // 命中箱系数
damageSystemScale = predicted / amount            // 本体自身缩放比
adjustedAmount    = desiredFinal / damageSystemScale   // ★反推输入量
event.setCanceled(true)
self.hurt(source, adjustedAmount)                 // ★重放，让本体音效/事件/死亡逻辑跑一遍
```

**反推 + 重放**是为了让本体的受击音效、`HitVehicleEvent`、死亡流程以正确伤害量执行一次。
这也是本方案最大的约束来源（见 §4.2）。

### 路径 ②：RVP 弹体 / 激光

`RVP_BaseBullet#applyEntityHitDamage`（3045-3095 行）：

```
preHitboxMult = distanceMult × incidenceMult × penetrationMult × vehicleMult
finalDamage   = base × preHitboxMult × hitboxMult
hurtAmount    = compensateCoreDistanceFalloff(vehicle, finalDamage, falloffScale)
pushSkip() → EntityUtil.hurt(...) → popSkip()    // handler 因 shouldSkip 跳过，避免重复缩放
```

### 本体 `DamageSystem#hurt` 的三分支（51-66 行）

```java
if (amount < 0.1f)                          amount = 0f;        // 归零
else if (amount < damageThreshold)          amount = 0.1f;      // 钳成 0.1 微伤
else                                        scale = 0.2 或 核心距离衰减(0~1)
amount *= scale;
```

**注意判断的是"输入 amount"而不是输出**，这是 §4.2 失真的根源。

---

## 3. 算法设计

### 3.1 完整管线（armor_min_damage > 0 时）

```
原始伤害 amount
  ↓ ① armor_max_damage 封顶（对原始伤害，同 MCH）
  ↓ ② 本体核心距离衰减（core_distance_scale_multiplier 调制）
  ↓ ③ × 命中箱系数（≤1 的减伤部分）—— RVP 已有 hitboxMult
  ↓ ④ − armor_min_damage                          ★新增：固定扣减
  ↓ ⑤ 保底：结果 < 0.1f 则抬到 0.1f                ★新增：不做完全免疫
  ↓ ⑥ × 命中箱系数（>1 的增伤部分）—— 后乘，同 MCH
  → 最终伤害
```

③④⑥ 的顺序复刻 MCH 的**不对称设计**：减伤先乘（装甲更容易吃干净）、增伤后乘
（装甲的固定扣减不被放大）。

数值对照（`armor_min_damage = 5`，原始 20）：

| 命中箱系数 | 本方案 | 若"一律先乘" |
| --- | --- | --- |
| 0.5（装甲区） | `(20×0.5) − 5 = ` **5** | 5（相同） |
| 2.0（弱点） | `(20 − 5) × 2 = ` **30** | `(20×2) − 5 = ` **35** |

### 3.2 装甲层伪代码

```java
static final float DAMAGE_FLOOR = 0.1f;   // 与本体下限一致

static float applyArmor(AbstractVehicle v, float dmg, float hitboxMult) {
    if (!isArmorConfigured(v)) return dmg;           // 未配置 → 零开销直通

    float minDmg = armorMinDamage(v);
    if (hitboxMult <= 1f) dmg *= hitboxMult;         // ③ 减伤先乘
    dmg -= minDmg;                                   // ④ 固定扣减
    if (dmg < DAMAGE_FLOOR) dmg = DAMAGE_FLOOR;      // ⑤ 保底 0.1（含负数与极小值）
    if (hitboxMult > 1f) dmg *= hitboxMult;          // ⑥ 增伤后乘
    return dmg;
}
```

> ⚠️ ⑤ 的取法待定：用 `< 0.1f → 0.1f`（下限 0.1，恒不归零）还是 `<= 0f → 0.1f`
> （仅负数和零保底，0~0.1 的极小值保持原值）。**默认推荐前者**，与本体"最低有效伤害 0.1"语义一致，
> 也避免出现比保底值更小的伤害。见 §8。

### 3.3 互斥的落地：有效阈值

RVP 侧所有读取 `damageThreshold` 的位置统一改用：

```java
static float effectiveThreshold(AbstractVehicle v) {
    return isArmorConfigured(v) ? DAMAGE_FLOOR : v.defenseStats.damageThreshold;
}
```

涉及 3 处（均在 `RVP_VehicleHurtScalingHandler`）：
- `predictBaseDamage`（336 行）
- `resolveCoreFalloffScale`（237 行）
- `compensateCoreDistanceFalloff`（300 行）

**但本体重放时读的是它自己的 `vehicle.defenseStats.damageThreshold`**——
RVP 改不到本体的判断，必须在重放期间处理，见下。

---

## 4. 核心风险与规避

### 4.1 风险一：`defenseStats` 是全局共享实例（已确认，不是推测）

代码事实：

```
AbstractVehicle.java:396   CommonAssetsManager.vehicleDataManager()   ← 按载具 ID 全局缓存
AbstractVehicle.java:420   this.defenseStats = vehicleData.getDefenseStats();   ← 引用，非拷贝
BaseVehicleData.java:100   this.defenseStats = pojo.defenseStats;     ← 源自 JSON 解析结果
BaseVehicleData.java:338   return defenseStats;                       ← 直接返回引用
```

**结论**：所有同类型载具实体的 `defenseStats` 字段指向**同一个对象**。
因此 `self.defenseStats.damageThreshold = 0.1f` 会污染**全部同类载具**；
即使 try/finally 恢复，在 `hurt()` 内部 post 的 `HitVehicleEvent` 若触发嵌套伤害，
仍会读到被污染的值。

**规避：替换本实体字段的指向，而不是改共享对象。**

```java
DefenseStats original = self.defenseStats;
DefenseStats shielded = new DefenseStats();
shielded.impactMultiplier = original.impactMultiplier;   // 保留碰撞系数，避免影响 impactHurt
shielded.damageThreshold  = DAMAGE_FLOOR;                // 屏蔽本体钳位
self.defenseStats = shielded;                            // 只改本实体的引用
try {
    self.hurt(source, adjustedAmount);
} finally {
    self.defenseStats = original;                        // 必须恢复
}
```

其他实体的字段仍指向共享对象 → **零污染**。`DefenseStats` 仅有 `damageThreshold` 与
`impactMultiplier` 两个字段，复制成本可忽略。

### 4.2 风险二：反推重放"跨分支"失真（本方案的主要技术难点）

反推数学 `adjustedAmount = desiredFinal / damageSystemScale` **只在重放后仍落在本体同一
分支时才成立**。本体按**输入 amount** 分支，而 `damageSystemScale ≤ 1`，所以
`adjustedAmount ≥ desiredFinal`——当 `desiredFinal` 较小时，`adjustedAmount` 仍可能撞上
`< damageThreshold` 的钳位分支。

反例（threshold=15，本体 scale=0.8）：

```
amount=20 → predicted=16 → hitboxMult=0.5 → desiredFinal=8
扣 armor_min=5 → 3
adjustedAmount = 3 / 0.8 = 3.75  < 15   → 本体重放时钳成 0.1
实际掉血 0.1，而非期望的 3        ✗ 线性特性退化成阶梯
```

**失效区间**：`desiredFinal ∈ (0.1, threshold × scale)`。以默认 threshold=50 计，这个区间
相当宽，装甲削到很低但没归零的情况几乎都会失真。

**规避**：§4.1 的临时换引用把重放时的 threshold 变成 0.1，则本体只有 `amount < 0.1` 才归零，
其余一律走 `amount × scale`——与 RVP 侧 `effectiveThreshold()` 的预测**完全一致**，数学闭合。

> 注意：只有当 `armor_min_damage > 0` 时才需要换引用。未配置装甲的载具走原路径，零改动零风险。

### 4.3 风险三：规避 mixin

明确**不**采用的方案：

- ❌ 新增 mixin 注入 `DamageSystem#hurt` 改伤害
- ❌ 新增 mixin 注入 `AbstractVehicle` 改 `defenseStats`
- ❌ 任何 `@OnlyIn(Dist.CLIENT)` 类的引用

理由（本项目已有教训，见 `MEMORY.md`）：

1. `RVP_VehicleHurtScalingHandler` 的类注释已明确它是**"非 mixin 恢复"**，替代了被删的
   `AbstractVehicleHitboxDamageFactorMixin`——这是本仓库既定的技术路线。
2. 客户端 mixin 的 `@Shadow` 字段在**增量构建**下 reobf 会失败，导致 `@Shadow field ... not located`
   客户端启动崩溃（`CameraTVMissileMixin` 实测）。新增 mixin 会重新引入这类风险。
3. 公共代码引用客户端类会触发 `RuntimeDistCleaner`。

本方案全部使用 **Forge 事件（`VehicleAttackEvent`）+ 公开字段**，装甲层是纯数值计算，
不触碰任何客户端类，天然满足 C/S 安全。

### 4.4 风险四：重入与事件风暴

`self.hurt()` 重放会再次触发 `VehicleAttackEvent`。现有代码已用 `REAPPLY_GUARD` 防环，
本方案的重放必须在**同一 guard 保护范围内**，不可另开调用。同时 `SKIP_DEPTH`（路径②的
`pushSkip/popSkip`）不可与换引用逻辑交叉，避免弹体路径误走 handler。

---

## 5. 方案对比

| | 方案 A（推荐） | 方案 B（备选，零侵入） |
| --- | --- | --- |
| 装甲位置 | 命中箱系数**之后**（MCH 顺序） | 命中箱系数**之前**（对原始 amount） |
| 是否改 `defenseStats` | 临时换**本实体引用**，finally 恢复 | **完全不碰** |
| 反推失真 | 需 §4.1 规避，规避后数学闭合 | 天然无跨分支问题 |
| 减伤语义 | `(20×0.5)−5 = 5` | `(20−5)×0.5 = 7.5` |
| 与 MCH 一致性 | ✅ 完全一致 | ❌ 装甲收益被命中箱稀释 |
| 改动量 | 中（3 处 threshold + 2 处装甲 + 换引用） | 小（1 处配置 + 2 处装甲） |

**推荐 A**：语义正确、与 MCH 一致、污染已通过"换引用"彻底规避。
**备选 B** 仅在后续发现换引用有副作用时启用。

---

## 6. 落点清单

### 6.1 配置字段（载具 JSON 顶层）

照 `RVP_VehicleHitboxFactorManager.VehicleHitboxConfig#parse`（797-841 行）现有写法：

```java
float armorMin = GsonHelper.getAsFloat(obj, "armor_min_damage", 0f);
float armorMax = GsonHelper.getAsFloat(obj, "armor_max_damage", 0f);
```

- `armor_min_damage` 取 `Math.max(0f, ...)`；`armor_max_damage` ≤ 0 视为不封顶。
- `VehicleHitboxConfig` record（684-692 行）**新增两个字段**；
  注意 824-831 行的"无配置则返回 null"短路判断**需同步加入这两个字段**，否则只配装甲
  会被判为 null 而失效。

访问器仿 `resolveCoreDistanceScaleMultiplier`（113-123 行）：

```java
public float resolveArmorMinDamage(AbstractVehicle vehicle)
public float resolveArmorMaxDamage(AbstractVehicle vehicle)
```

> ⚠️ 现有 `resolveCoreDistanceScaleMultiplier` 在 `configs.get(id) == null` 时返回默认值，
> 装甲访问器必须同样处理 null（返回 0 = 未配置）。

### 6.2 代码改动

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `RVP_VehicleHitboxFactorManager.java` | record 684、parse 824/832 | 新增 2 字段 + 解析 + 访问器 |
| `RVP_VehicleHurtScalingHandler.java` | 新增 | `effectiveThreshold()`、`applyArmor()`、换引用工具方法 |
| `RVP_VehicleHurtScalingHandler.java` | `onVehicleAttack` 157 行后 | 装甲层插入 `desiredFinal` 之后、反推之前 |
| `RVP_VehicleHurtScalingHandler.java` | `onVehicleAttack` 170-176 行 | 重放包裹换引用（仅 armor 生效时） |
| `RVP_VehicleHurtScalingHandler.java` | 237 / 300 / 336 行 | `damageThreshold` → `effectiveThreshold()` |
| `RVP_BaseBullet.java` | `applyEntityHitDamage` 3049 行后 | 装甲层插入 `finalDamage` 之后、预补偿之前 |

**不变的部分**：

- ERA / 骨骼模块触发继续用扣装甲**之前**的 `predicted`（现有 `tryDestroyBoneModules(self, res, predicted)`）
  ——装甲不该影响"这发弹有没有资格引爆反应装甲"。
- 爆炸保持**绕过**装甲与命中箱（与 MCH、与 RVP 现有 `if (!explosion)` 一致）。

---

## 7. 测试与验收

1. **互斥生效**：同一载具同时配 `damage_threshold=15` 与 `armor_min_damage=8`，
   打一发 12 点伤害 → 期望掉 `12 − 8 = 4`，**而不是**被本体钳成 0.1。
2. **保底 0.1**：打一发 5 点伤害（5 − 8 < 0）→ 期望掉 **0.1**，且**有**受击音效与命中提示
   （区别于 MCH 的完全无声免疫）。
3. **线性而非阶梯**：扫射一串伤害 8/10/12/14/16（armor_min=8）→ 掉血应为
   `0.1 / 0.1 / 4 / 6 / 8` 的连续过渡，**不得**出现集体塌到 0.1（验证 §4.2 已规避）。
4. **封顶**：`armor_max_damage=30`，一发 200 → 期望按 30 继续走后续系数。
5. **减伤/增伤顺序**：命中箱 0.5 打 20（armor_min=5）→ 5；命中箱 2.0 打 20 → 30。
6. **无污染**：同场地放两台**同类型**载具，打其中一台，确认另一台的伤害结算不受影响
   （特别是打完瞬间立刻打第二台）。
7. **未配置装甲回归**：不配 `armor_min_damage` 的载具，行为与改动前**完全一致**。
8. **服务端冒烟**：`./gradlew.bat runServer`，确认无 `RuntimeDistCleaner` 拦截日志。

---

## 8. 待确认项

1. **保底取法**：`dmg < 0.1f → 0.1f`（下限 0.1，推荐）还是 `dmg <= 0f → 0.1f`（仅负零保底）？

   采用下限0.1
2. **`armor_max_damage` 封在哪**：
   - 封**入口**（MCH 做法，防"伤害源本身离谱"）：`1000 → 100 → ×系数`
   - 封**最终**（防秒杀，平衡更好控）：系数全算完后才封
   - 当前方案按 MCH 封入口，**若你想要防秒杀请改成封最终，或两者都加**。
   
     我觉得封最终好点
3. **爆炸是否吃装甲**：当前按"绕过"设计（与 MCH 一致）。若希望 HE/HEAT 也被装甲削减，需另行确认。

   爆炸伤害不受装甲减免
4. 是否需要同步把这两个参数显示在载具信息面板 / 文档中。
   补充到文档里

---

## 9. 实现记录（2026-09-01）

按 §8 已确认的决策落码：保底取 `< 0.1 → 0.1`；`armor_max_damage` **封最终**（全部系数算完之后，
§3.1 管线中的步骤 ① 相应后移到 ⑥ 之后，实现于 `applyArmor` 末段）；爆炸绕过装甲；字段说明已补充到
[按结构OBB受击倍率方案.md](./按结构OBB受击倍率方案.md) 的载具 JSON 字段表。

### 9.1 改动文件

| 文件 | 内容 |
| --- | --- |
| `RVP_VehicleHitboxFactorManager` | `VehicleHitboxConfig` record 新增 `armorMinDamage`/`armorMaxDamage`；parse 读取 `armor_min_damage`/`armor_max_damage`（`Math.max(0f, ...)`，≤0 视为未配置）；"无配置返回 null"短路判断同步纳入两字段；`resolveArmorMinDamage`/`resolveArmorMaxDamage`/`isArmorConfigured` 访问器（null 配置返回 0） |
| `RVP_VehicleHurtScalingHandler` | `DAMAGE_FLOOR`、`effectiveThreshold()`、`applyArmor()`（减伤先乘→扣减→保底 0.1→增伤后乘→封最终→再保底）、`hurtWithShieldedThreshold()`（换引用重放，finally 恢复）；`onVehicleAttack` 中装甲层插在 `deltaAfterCore` 之后、反推之前，重放按 `isArmorConfigured` 走换引用 |
| `RVP_BaseBullet#applyEntityHitDamage` | 装甲层插在 `preHitboxDamage` 之后、预补偿之前：传**未乘命中箱系数**的 `preHitboxDamage` 给 `applyArmor` 统一施加（避免系数重复相乘）；未配置装甲时等价于原来的 `preHitboxDamage * hitboxMult` |
| `RVP_LaserWeapon`（超出 §6.2 清单，见 9.2-1） | 激光直击同样走 `applyArmor`（`hitDamage` 未乘命中箱系数时传入），`tryDestroyBoneModules` 仍用扣装甲前的 `hitDamageBeforeHitbox` |

### 9.2 与原方案的差异 / 补充

1. **激光路径一并纳入装甲**：§6.2 只列了 `RVP_BaseBullet`，但激光（`RVP_LaserWeapon`）与弹体同属
   §2 路径②（自行结算命中箱系数 + pushSkip + 预补偿），若不处理会完全绕过装甲。已补上；不希望激光
   吃装甲的话删掉那一处 `applyArmor` 调用即可。
2. **`predictBaseDamage` 改读 `effectiveThreshold()`**（§3.3 清单中的第 3 处）：装甲生效时预测阈值
   必须与换引用后的重放条件一致（均为 0.1），否则 §4.2 的跨分支失真依旧存在。
3. **换引用副本补拷 `damageTransferCoefficient`**：`DefenseStats` 实际有 3 个字段（§4.1 记录为 2 个），
   该字段目前只有部件级结算读取，载具级副本一并拷贝以防本体后续扩展读取。
4. **爆炸绕过装甲**（§8 决策 3）：`onVehicleAttack` 中 `explosion ? deltaAfterCore : applyArmor(...)`；
   弹体路径的爆炸本就走本体 `VehicleExplosion`，不经 `applyEntityHitDamage`，天然绕过。

### 9.3 待办

- §7 测试 1~7 的游戏内数值验收（需实机打靶：互斥生效 / 保底 0.1 / 线性非阶梯 / 封顶 / 减伤增伤顺序 / 双载具无污染 / 未配置回归）。
- §7.8 runServer 冒烟（确认无 `RuntimeDistCleaner` 拦截日志）。
