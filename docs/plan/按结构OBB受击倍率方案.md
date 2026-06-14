# 按结构 OBB（骨骼分组）配置载具受击倍率 —— 执行方案（仅改 ywzj_rvp）

## 目标

为 **ywzj_vehicle** 的载具实现“按结构模型不同骨骼分组（bone）对应的 cube OBB”配置不同受击倍率的能力，例如：

- `vturret` 受击倍率 `0.5`
- `turret_barrel` 受击倍率 `1.2`
- 当 `turret_barrel` 是 `vturret` 的子骨骼时，命中 `turret_barrel` 名下 cubes 对应的 OBB，应按 `turret_barrel` 的倍率结算（而不是父骨骼）。

约束：

- **代码改动仅允许在 ywzj_rvp 中进行**
- **不允许改动 ywzj_vehicle**

## 总体思路

参考 MCH-Reforged 的做法：命中阶段选中“最近命中的子碰撞箱”，并在伤害结算时乘以对应倍率。

在本项目中，将“子碰撞箱”映射为 **结构模型中某个 bone 名下 cubes 生成的 OBB 集合**，配置倍率按 **boneName → factor** 提供。

## 生效范围（明确边界）

由于不能改动 ywzj_vehicle，本方案的实现位置在 ywzj_rvp，但可以通过 mixin 在 `AbstractVehicle#hurt` 后置做“最终扣血转换”，因此生效范围分两类：

1) **RVP 武器自身直击/直伤**：在 RVP 的伤害入口直接乘倍率（弹体直击、激光直击、近炸直伤等）
2) **外源伤害命中载具**：包括 ywzj_vehicle 默认武器、原版弓箭等，走 ywzj_vehicle `DamageSystem` 后由 rvp 做“最终扣血转换”，使其也能按碰撞箱倍率结算

- 弹体直击伤害（RVP_BaseBullet）
- 激光直击（若存在独立伤害入口）
- 近炸“直伤”（proximity fuse direct damage）

以下不覆盖（除非未来允许改 ywzj_vehicle）：

- ywzj_vehicle 自身弹药的伤害体系
- VehicleExplosion 的波及伤害
- 载具碰撞/跌落等非 RVP 造成的伤害

## 配置设计（写在 vehicle json 中，由 rvp 读取）

在 `data/<ns>/vehicles/<id>.json` 里新增字段（ywzj_vehicle 会忽略未知字段）：

```json
{
  "core_distance_scale_multiplier": 1.0,
  "hitbox_damage_factor_default": 1.0,
  "hitbox_damage_factor": {
    "vturret": 0.5,
    "turret_barrel": 1.2
  },
  "hitbox_era": {
    "Upper_front": {
      "damage_factor": 0.35,
      "min_trigger_damage": 12.0,
      "explosion": 1.5
    }
  }
}
```

字段含义：

- `core_distance_scale_multiplier`：控制 ywzj_vehicle 本体的“命中点离主 OBB 核心越远伤害越低”缩放的强度（rvp 侧通过 mixin 调整最终扣血结果实现）
  - `1.0`：保持原逻辑（默认）
  - `0.0`：关闭该距离缩放（等效恒为 1 倍）
  - `0.0~1.0`：部分保留（线性插值）
- `hitbox_damage_factor_default`：当未命中任何配置分区或未配置对应 bone 时的倍率，默认 `1.0`
- `hitbox_damage_factor`：按结构模型 bone 名称配置倍率
- `hitbox_era`：爆炸反应装甲（ERA）配置（以“特殊碰撞箱”的思想实现，见下文扩展章节）

约定：

- 仅对列出的 boneName 做命中判定（避免遍历全车骨骼，减少性能开销）
- 若列表为空或未写，则不做分区倍率（倍率为 1.0）

## 命中判定（核心算法）

### 输入

- `AbstractVehicle vehicle`
- 子弹本 tick 线段：`from = collisionSegmentStart()`，`to = collisionSegmentEnd()`
- vehicle data 中的倍率表（boneName 列表）
- vehicle data 的 `structure_model`（用于加载结构模型）

### 输出

返回 `(hitBoneName, hitFactor, hitPoint)`，其中：

- `hitBoneName`：命中的骨骼名（可能为 null）
- `hitFactor`：命中倍率（命中则取该骨骼倍率，否则 default）
- `hitPoint`：最近命中的交点（可选，用于 debug）

### 实现方式

1. 读取 `structure_model`，通过现有结构模型管理器加载 `BedrockModel`
2. 对倍率表中的每个 `boneName`：
   1) `bone = model.getBoneMap().get(boneName)`，不存在则跳过  
   2) 生成该 bone 的 cube OBB 列表（仅该 bone “自己名下 cubes”，不包含子 bone）：
      - 使用 ywzj_vehicle 的 `OBB.getOBBsFromBone(bone, vehicle, namedBones)`
      - 其中 `namedBones` 传 `new HashSet<>(model.getBoneMap().values())`，以确保只拿当前 bone 的 cubes（不递归匿名/子 bone）
   3) 对每个 OBB 调用 `obb.clip(from, to)` 求交点，取离 `from` 最近的交点距离
3. 在所有候选命中中取最近者，得到 `hitBoneName`
4. `hitFactor = hitbox_damage_factor.getOrDefault(hitBoneName, default)`

### “子骨骼优先覆盖父骨骼”的保证

本方案不是按“父子优先级”硬编码，而是依赖一个事实：

- 每个 cube 只属于其所在 bone
- 我们生成 OBB 时只取该 bone 自己的 cubes，不包含子 bone

因此，当 `turret_barrel` 是 `vturret` 的子骨骼时：

- `vturret` 的 OBB 集合不会包含 `turret_barrel` 的 cubes
- 命中 `turret_barrel` 的 cubes 只可能落在 `turret_barrel` 的 OBB 集合中

天然满足你希望的覆盖规则。

## 伤害应用位置（rvp 插入点）

### 弹体直击

在 `RVP_BaseBullet#applyEntityHitDamage(Entity entity, BulletHitResult result)` 中，现有流程为：

1) 计算 distance / incidence / penetration / vehicle-type factor  
2) 得到 `finalDamage = base * totalMult`  
3) `EntityUtil.hurt(source, entity, finalDamage)`

在第 2 步和第 3 步之间插入：

- 若 `entity instanceof AbstractVehicle`：
  - 计算 `hitboxFactor`（按上文命中判定）
  - `finalDamage *= hitboxFactor`

### 激光直击 / 近炸直伤

- 激光：若有类似 `shoot -> target.hurt(dmg)` 的入口，按相同逻辑对 `AbstractVehicle` 乘 `hitboxFactor`
- 近炸直伤：对 proximity target 也可乘 `hitboxFactor`（同样只对 `AbstractVehicle`）

## 数据获取与缓存（性能建议）

### vehicle json 的读取

rvp 侧需要从 `AbstractVehicle` 获取其 vehicleId（实体类型 ID），再映射到对应的 vehicle data json。

建议缓存结构：

- `vehicleId -> parsed (structure_model, factor_default, factor_map)`

缓存失效策略：

- 通过现有的 “reload resources / reload vehicle packs” 事件触发清空（与 rvp 现有热重载机制保持一致）

### 结构模型 BedrockModel 缓存

- `structure_model ResourceLocation -> BedrockModel`

通常结构模型已经在 ywzj_vehicle 里缓存，rvp 侧可直接复用同一管理器的缓存对象；如果访问层级受限，则在 rvp 自己做一层弱缓存也可。

## Debug（建议但可后置）

为方便调试命中分区是否正确，建议提供可开关的服务端日志或客户端 overlay：

- 命中骨骼名 `hitBoneName`
- 采用倍率 `hitboxFactor`
- 命中点坐标 `hitPoint`

并仅在 debug 开关开启时输出。

## 与 MCH-Reforged 的可借鉴点（对应关系）

- MCH 的 `extraBoundingBox[]`（每个盒子带 `damageFactor`）  
  对应本方案的：`boneName -> OBB 集合 -> factor`
- MCH 在 `calculateIntercept` 里选最近命中并记录 `lastBBDamageFactor`  
  对应本方案的：命中阶段选 `hitBoneName`，结算阶段乘 `hitboxFactor`

## 扩展：爆炸反应装甲（ERA）作为“特殊碰撞箱”

目标：在现有“bone→OBB→命中”的基础上，引入一类特殊碰撞箱：**ERA 碰撞箱**。

- ERA 仍然是按 bone 的 cubes 生成的 OBB（本质仍是碰撞箱）
- ERA 具备“活跃/失效”状态（消耗式）
- **失效的 ERA 碰撞箱不再参与命中计算**：射线命中时应当忽略该 ERA OBB，让弹药继续与后方车体/其它碰撞箱发生反应（穿过去）
- 触发边界：当实际结算伤害低于一定阈值时，ERA 不引爆、不消耗（防止机枪快速摧毁大量爆反）

建议字段（车包级）：

- `hitbox_era.<bone>.damage_factor`：命中该 ERA 碰撞箱时的伤害系数（类似 MCH 的 `damageFactor`）
- `hitbox_era.<bone>.min_trigger_damage`：大于该值才会引爆并将该 ERA 标记为失效
- `hitbox_era.<bone>.explosion`：可选，触发时在该 bone 的近似中心播放一个小爆炸/特效（是否伤及车体由实现方案决定）

实现细节与联动渲染方案见：`docs/plan/ywzj_rvp爆炸反应装甲开发方案.md`

## 交付与验证步骤

1. rvp 增加：vehicle json 扩展字段解析与缓存（不改 ywzj_vehicle）
2. rvp 增加：命中阶段的 bone OBB 射线命中选择逻辑
3. rvp 修改：RVP_BaseBullet 对 `AbstractVehicle` 直击伤害乘 `hitboxFactor`
4. 用两台车验证：
   - 一台配置 `vturret=0.5`, `turret_barrel=1.2`
   - 近距离固定角度射击不同位置，观察同口径造成的最终伤害差异与命中分区 debug 输出一致
