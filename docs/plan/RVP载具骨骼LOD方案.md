# RVP 载具骨骼 LOD 方案

> 状态：设计稿（调研已完成，实现未开始）
> 关联：SBM 渲染瓶颈分析、no_cull_bones（骨骼级双面控制）

## 1. 背景与动机

SimpleBedrockModel（SBM）v2 的渲染是**每帧 CPU 全量顶点组装**：

- 每次 `renderToBuffer` 走**两遍骨骼树**（quad pass + triangle pass），把所有顶点的坐标/法线/UV 用当前 PoseStack 重新做矩阵变换，写入 MC 的 BufferBuilder
- BufferBuilder 每帧重建，**无跨帧 VBO 缓存**，静态几何也每帧全量重写
- 结果：**顶点数 ∝ 每帧 CPU 成本**，面数高的模型（J-20A 等）明显吃力，多架载具时成本线性叠加

其中**座舱内部占面数不少**（cop/cop_door 的 mesh 顶点量大），而**座舱盖（J20B_Cop_Gate1）是透明件**（`special_bone_effects` 的 COCKPIT），透明渲染本身比不透明更贵（无法深度优化、overdraw 高）。距离远了还逐帧渲染完整座舱+座舱盖，纯属浪费。

目标：提供**骨骼级 LOD**——通过 js 脚本检测"与玩家的距离 / 载具状态"，按条件隐藏指定骨骼，减少每帧组装的顶点量。

## 2. 现状调研（已确认事实）

### 2.1 SBM v2 渲染路径与 visible 短路

`BakedBedrockModel.renderBone`（SBM 2.5.1，GitHub 1.20.1-dev 分支）核心逻辑：

```java
BakedBoneDefinition def = bones[boneIndex];
if (quadsPass ? !def.hasQuadsInTree() : !def.hasVerticesInTree()) {
    return;
}
BoneState bone = instance.getBone(boneIndex);
if (bone == null || !bone.visible) {
    return;   // ★ 骨骼不可见 → 该骨骼 chunk 与整棵子树顶点全部跳过
}
...
BakedGeometryChunk chunk = chunkByBone[boneIndex];
if (chunk != null) renderChunkForPass(chunk, ...);
for (int childIndex : def.children()) {
    renderBone(instance, childIndex, ...);   // 子树递归
}
```

**`BoneState.visible = false` 能跳过该骨骼及整棵子树的顶点写入**——这是骨骼 LOD 的性能支点，正打在"每帧全量组装"的瓶颈上。

### 2.2 RVP 后端全量保留骨骼

`RVP_VehicleModelFactory.createStaticBakerOptions` 把**模型全部骨骼**同时设为 `animatedBones` + `preservedBones`：

```java
preservedBones.addAll(new BedrockModel(pojo).getBoneMap().keySet());  // 全部骨骼
Set<String> animatedBones = backend == RVP_BedrockBackend.RVP ? preservedBones : Set.of();
```

效果与代价：

| 项 | 说明 |
|---|---|
| 效果 | 关闭 SBM"静态骨骼折叠"优化，每根骨骼保留独立 chunk、运行时可变换 |
| 对 LOD 的意义 | 每根骨骼都是独立 chunk → **visible 短路对任意骨骼都有效**，隐藏哪根就省哪根 |
| 代价 | 放弃折叠 → 高面数模型 CPU 组装成本更高（性能放大器） |

全量保留的联动功能：BlockBench 动画 / js 脚本变换、CustomMount 挂架逐枚隐藏导弹、specialBoneEffects（座舱透明件）、no_cull_bones 双面骨骼。

### 2.3 现有"隐藏"机制与坑

**`PoseHelper.hideBone`（本体）不是性能隐藏**：

```java
public void hideBone(String boneName) {
    int boneIndex = boneIndexProvider.getIndex(boneName);
    if (boneIndex < 0) return;
    transforms.put(boneIndex, new BoneTransformData(0, 0, 0, 0, 0, 0, 0, 0, 0));  // scale=0
}
```

它塞一个全零缩放变换，把骨骼**塌缩到不可见**（j20a.js 的 `hideBone(pl15[i])` 逐枚隐藏导弹就是这个机制）。顶点**照样全量变换并写入 BufferBuilder**——视觉隐藏有效，**性能优化为零**。

**js 沙箱限制**：`ScriptContextFactory` 的 `ClassShutter` 只放行 `org.ywzj.vehicle.*` 和 `java.lang.String`，脚本**无法直接访问 SBM 的 `BoneState`**，不能直接 `bone.visible = false`。

**脚本 context 现状**：j20a.js 的 `updateBones(context)` 能拿到 `pitchInput / yawInput / rollInput` 等飞行输入，但**没有玩家距离**，需要注入。

## 3. 方案设计

### 3.1 总体分层

| 层级 | 触发条件 | 隐藏对象 | 收益类型 |
|---|---|---|---|
| 状态 LOD | 起落架收起 | 起落架骨骼 | 纯赚（收起本就不该画，零视觉损失） |
| 距离 LOD 1 | 距玩家 > 50m | **座舱组**：座舱内部（cop/cop_door）+ 座舱盖（J20B_Cop_Gate1） | 省内部 mesh 顶点 + 透明盖 overdraw，双份收益 |
| 距离 LOD 2 | 距玩家 > 100m | 更多细节骨骼（垂尾/鸭翼等可配置） | 随距离递增 |
| 模型 LOD（远期） | 距玩家 > 200m | 切换低面数模型 / 隐藏整根机身 | 顶点数直接砍半，效果最显著 |

> **注意：座舱 ≠ 座舱盖**。`cop`/`cop_door` 是座舱内部（不透明 mesh），`J20B_Cop_Gate1` 是座舱盖（透明件）。
> **两者必须成组隐藏**：若只隐藏座舱内部、保留透明盖，外部视角会"透过透明盖看到空座舱"；若只隐藏盖、保留内部，则失去透明件 overdraw 收益。距离 LOD 时盖+内部一起隐藏。

### 3.2 js 检测参数注入

向脚本 context 注入：

- `playerDistance`：客户端计算 `Minecraft.player` 与本载具的 `distanceToSqr`（平方距离比较，无 sqrt 成本）
- 载具状态查询：起落架等部件状态（可复用现有 `switchable_animation` / `PartUnit` 状态，或直接暴露查询函数）

注入点：本体 `ScriptContextFactory`/脚本运行 context 构建处（只读 → 若需改动本体，改用 RVP mixin 或 RVP 侧包装）。

### 3.3 骨骼可见性桥接（核心）

脚本新增标记型 API（不直接碰 SBM 类，绕开沙箱）：

```js
// 示例：距离 LOD + 状态 LOD
function updateBones(context) {
    if (context.playerDistance > 50 * 50) {   // 平方距离
        // 座舱组成组隐藏：座舱盖（透明件）+ 座舱内部，避免"透过透明盖看到空座舱"
        builder.setVisible("J20B_Cop_Gate1", false);   // 座舱盖（透明件）
        builder.setVisible("cop", false);               // 座舱内部
        builder.setVisible("cop_door", false);          // 座舱门
    }
    if (context.landingGearRetracted) {
        builder.setVisible("gear_*", false);            // 起落架
    }
}
```

实现路径（候选）：

1. **扩展 PoseHelper 语义（mixin 注入）**：新增 `setVisible(boneName, bool)`，收集到独立集合，渲染前统一应用到 `BoneState.visible`。不破坏现有 `hideBone`（scale=0）的逐枚导弹用法
2. **RVP 侧独立桥接**：RVP 自建"每帧应隐藏骨骼集合"收集器，在渲染入口（`VehicleBedrockModel.renderToBuffer` 系列）之前应用

渲染层应用点：`VehicleBedrockModel.renderToBuffer(...)`（本体 L98/L108 两个重载）与 `renderSpecialBones(...)` 之前。应用成本：Set 遍历 + `getIndex` + visible 赋值，远小于顶点组装。

### 3.4 与现有系统的兼容性

- **visible=false 对 `renderSingleBone` 同样生效**（`renderSingleBone` 也走 `renderBone` 的 visible 检查）→ 座舱组被隐藏后，`renderSpecialBones` 的 COCKPIT 补渲（座舱盖透明件）也不会画，行为一致
- **与 no_cull_bones 并存**：双面骨骼若同时被距离隐藏，以 visible=false 优先（不画）
- **本地玩家视角**：第一人称操作座舱视角时，座舱组（内部+座舱盖）不能被距离 LOD 隐藏 → 需结合 `LocalVehiclePlayer.viewType`（OPERATOR 视角时跳过座舱组隐藏）

## 4. 收益评估

- **隐藏座舱内部（cop/cop_door）**：省 mesh 顶点组装；座舱内部占面数不少，收益显著
- **隐藏座舱盖（J20B_Cop_Gate1）**：省透明件 overdraw（透明渲染比不透明贵）；但必须与座舱内部**成组隐藏**，否则"透过透明盖看到空座舱"
- **起落架收起**：纯赚，收起时不渲染，零视觉损失
- **多架载具**：收益线性叠加（每架各省一份）
- **限制**：骨骼粒度受模型骨骼划分限制；单根骨骼隐藏无法再细分。更彻底的模型 LOD（换低模）收益最大，列入远期

## 5. 实现步骤（待办）

1. 确认 js 脚本 context 构建位置，注入 `playerDistance` 与载具状态查询
2. 设计并实现 `setVisible` 标记桥接（mixin 扩展 PoseHelper 或 RVP 侧收集器）
3. 渲染入口应用隐藏集合（兼容 COCKPIT 第一人称视角）
4. j20a 脚本接入（座舱 + 起落架），游戏内测试
5. 距离阈值与隐藏骨骼列表按实测调优

## 6. 风险与注意

- js 每帧执行的逻辑要轻（距离比较、状态判断），避免每帧 `build()` 大对象
- 距离计算用平方距离（`distanceToSqr`），避免每帧 sqrt
- 隐藏骨骼名需保证在 preserved/animated 集合内（RVP 后端全量保留天然满足）
- 第一人称座舱视角 / 玩家自己的载具**不能**做距离 LOD，否则自己座舱（内部+座舱盖）消失
