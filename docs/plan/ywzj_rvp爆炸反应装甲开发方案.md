# ywzj_rvp 爆炸反应装甲（ERA）开发方案（基于碰撞箱）

## 目标与约束

目标：在现有“按结构骨骼 cube→OBB 命中”的体系上，引入爆炸反应装甲（ERA）功能，使其成为一种“特殊碰撞箱”：

- 命中 ERA 碰撞箱时可对伤害做独立的系数处理
- 满足触发条件时，ERA 会引爆并被消耗（进入失效状态）
- 失效的 ERA 碰撞箱不再参与命中计算，弹药应继续命中后方的车体/其它碰撞箱（“穿过去”）
- 需要联动模型侧渲染：ERA 激活时渲染对应部件，失效时隐藏对应部件

约束：

- 代码改动仅允许在 ywzj_rvp
- 不改动 ywzj_vehicle
- 现有 hitbox 倍率与“外源伤害转换”链路必须保持可用

## 核心思想（与 MCH 对齐）

MCH 的核心思想是：ERA 是一种特殊的子碰撞箱（有自己的 `damageFactor`、有 `active/inactive` 状态、命中后可一次性失效，并且失效后在命中检测阶段被跳过）。

RVP 侧保持同样的思想，但将“子碰撞箱”映射为：

- `boneName` 名下 cubes 生成的一组 OBB

其中一部分 bone 通过配置被标记为 ERA 碰撞箱。

## 数据设计（vehicle json 扩展字段）

在 `data/<ns>/vehicles/<id>.json` 顶层新增（ywzj_vehicle 会忽略未知字段）：

```json
{
  "hitbox_damage_factor_default": 1.0,
  "hitbox_damage_factor": {
    "vehicle_body": 1.0
  },
  "hitbox_era": {
    "Upper_front_era": {
      "damage_factor": 0.35,
      "min_trigger_damage": 12.0,
      "explosion": 1.5
    }
  }
}
```

字段语义：

- `hitbox_era`：键为 `boneName`
- `damage_factor`：命中该 ERA 碰撞箱时对伤害乘的倍率（与普通 hitbox 倍率不同，ERA 有独立倍率）
- `min_trigger_damage`：触发阈值（以“最终结算伤害/或预测基础伤害”为判定基础，见下文），低于该值不引爆、不消耗
- `explosion`：可选，触发时播放小爆炸/特效的强度参数

默认行为：

- `hitbox_era` 未配置时不启用 ERA
- `damage_factor` 默认 `1.0`
- `min_trigger_damage` 默认 `Float.POSITIVE_INFINITY`（即永不触发/仅作普通碰撞箱倍率）

## 运行时状态模型

每台载具需要维护“哪些 ERA bone 仍处于激活状态”。

建议的数据结构：

- `vehicleId + entityId -> BitSet/Set<String>`（服务端权威）
- 以 `boneName` 为键的激活状态：
  - `true`：激活（参与命中）
  - `false`：失效（命中检测阶段跳过，相当于不存在）

同步与持久化建议：

- 同步：服务端在状态变化时发 S2C 包，客户端缓存并用于渲染联动
- 持久化：将状态写入实体 NBT（或附着能力/数据组件），以支持重登/区块卸载后仍保持失效状态

## 命中与结算（关键：失效后“穿过去”）

### 总体流程

对一次“弹药/伤害线段”命中载具，执行分层命中：

1) 使用线段 `(start, end)` 与所有候选 OBB 做射线求交，得到按距离从近到远排序的命中列表
2) 依次处理命中（从最近开始）：
   - 若命中的是“失效 ERA OBB”：跳过，继续处理下一次命中（相当于穿过）
   - 若命中的是“激活 ERA OBB”：按 ERA 规则结算；若触发则消耗并停止；若不触发则停止（因为弹药确实撞在 ERA 上）
   - 若命中的是普通碰撞箱：按普通 hitbox 倍率结算并停止

该策略保证：

- ERA 失效后不会把伤害回退到普通倍率，而是让命中继续向后寻找“真正的下一层碰撞箱”

### 触发边界（防机枪快速摧毁）

触发判定不应使用“输入 damage”或“显示 damage”，而应使用一个稳定基准，避免受到：

- 核心距离衰减
- 阈值减伤
- 残血扣血截断

建议优先使用“预测基础伤害”（现有外源伤害转换链路里已经计算的 predictedBaseDamage），再乘上 ERA 自身的 `damage_factor` 或在触发前不乘（实现时二选一并固定）。

推荐规则（更接近直觉）：

- `triggerDamage = predictedBaseDamage`
- 若 `triggerDamage > min_trigger_damage` 才引爆并消耗

这样机枪由于单发基础伤害低，无法触发 ERA 消耗；而反坦克弹单发基础伤害高，会触发。

### ERA 结算与后续伤害

命中激活 ERA 时：

- 本次对载具造成的伤害：`finalDamage = baseDamage * damage_factor`
- 若触发：将该 ERA 标记为失效，并播放爆炸/特效

触发后是否让“同一发弹”继续作用于后方车体：

- 默认建议：不继续（触发 ERA 等价于该发弹被反应装甲有效干扰）
- 可选扩展：允许“减伤后剩余伤害/穿深”继续命中后方（需要与 RVP 自己的穿透系统联动）

## 外源伤害与 RVP 武器的接入点

需要覆盖两类链路：

1) RVP 武器（直击/直伤）：在命中载具时使用上述分层命中规则，得到最终 bone（ERA 或普通），再做伤害结算
2) 外源伤害（ywzj_vehicle 默认武器、原版弓箭等）：在 `AbstractVehicle#hurt` 后置做最终扣血转换时，同样用“命中线段”做分层命中，从而决定 ERA 是否拦截/触发/失效

## 模型渲染联动（JS 脚本方向）

本项目已有 JS 脚本驱动骨骼姿态的机制：

- 动画控制器会调用脚本函数 `fn(ctx)`，参数是 `VehicleContext`
- 脚本可通过 `createPoseBuilder()` 得到 `PoseHelper`
- `PoseHelper.hideBone(boneName)` 可以通过缩放为 0 的方式隐藏骨骼

参考实现入口：

- 脚本调用方式：[ScriptPoseNode.evaluate](file:///d:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/render/animation/graph/node/ScriptPoseNode.java#L25-L35)
- `createPoseBuilder` 注入：[VehicleDisplay.initializeScriptScope](file:///d:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/resource/vehicle/VehicleDisplay.java#L123-L137)
- 隐藏 bone API：[PoseHelper.hideBone](file:///d:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/render/animation/util/PoseHelper.java#L31-L41)

联动方案：

- rvp 在客户端维护 `boneName -> eraActive` 的状态（由服务端同步）
- JS 脚本里按状态隐藏/显示 ERA 对应 bone：

```js
function updateBones(ctx) {
  const pose = createPoseBuilder();
  const v = ctx.getEntity();

  if (!v.rvp_isEraActive("Upper_front_era")) {
    pose.hideBone("Upper_front_era");
  }

  return pose;
}
```

这里的 `rvp_isEraActive` 是 rvp 需要通过 mixin/扩展方法提供给 `AbstractVehicle` 的查询接口（仅 rvp 改动即可实现）。

## 调试与表现（建议）

- 命中调试输出增加：是否命中 ERA、是否触发、是否消耗、命中 bone 名
- HUD 显示增加：`ERA` 标签（例如 `hitbox×0.35(Upper_front_era) ERA:TRIGGER`）
- 触发特效：局部爆炸粒子/烟尘/音效（不建议默认破坏方块）

## 里程碑（交付顺序）

1) 仅实现：ERA 作为特殊碰撞箱 + 激活/失效 + 失效后穿透到下一层命中
2) 触发边界：`min_trigger_damage` 生效，机枪不再快速消耗 ERA
3) 同步与持久化：S2C 同步 + NBT 保存
4) 模型联动：提供 `rvp_isEraActive(boneName)` 供 JS 脚本隐藏/显示 ERA 骨骼
5) 可选扩展：触发后残余穿深、对不同弹药类别不同阈值/不同倍率
