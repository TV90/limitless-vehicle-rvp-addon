# SBM迁移到RVP侧方案

## 1. 目标

本方案的目标不是简单“能跑”，而是把当前 `ywzj_rvp` 对内置 SBM 与本体 `ywzj_vehicle` 的渲染耦合逐步抽离，最终达到以下状态：

1. `RVP` 自己持有并解释需要魔改的 Bedrock model/render/animation 扩展能力。
2. 当前依赖 mixin 改写的透明渲染、座舱渲染、special bone 渲染、部分动画挂接能力，尽量改为 `RVP` 自己的显式实现。
3. 在迁移完成后，删除当前与 SBM/Vehicle 渲染强绑定的相关 mixin，降低后续本体更新时的签名风险。

本次规划只讨论**客户端模型/渲染/动画链路**，不涉及武器、雷达、制导、爆炸等其它系统。

---

## 2. 当前现状

### 2.1 当前直接或间接和 SBM 强耦合的点

当前 `RVP` 对 SBM 的耦合，分为三层：

#### A. 直接 mixin 到 SBM 类

- [BedrockModelRenderTypesMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/BedrockModelRenderTypesMixin.java)

作用：

- 直接拦截 `simplebedrockmodel` 的 `BedrockModelRenderTypes.polyMeshCutout(...)`
- 把 SBM 默认 `RenderType` 替换为 `RVP_RenderTypes.polyMeshCutout(...)`

这条属于**直接打进 SBM 包内类**的 mixin。

#### B. 打到 `ywzj_vehicle` 的 SBM 封装层

- [VehicleBedrockModelCockpitRenderMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleBedrockModelCockpitRenderMixin.java)
- [VehicleRenderCockpitPassengerContextMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleRenderCockpitPassengerContextMixin.java)
- [ModRenderTypesMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/ModRenderTypesMixin.java)
- [VehicleDisplayMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleDisplayMixin.java)

这些不是直接改 SBM，但它们都建立在 `ywzj_vehicle` 已经封装好的 Bedrock model/display/render/animation 体系上。

其中最重的一条是：

- [VehicleBedrockModelCockpitRenderMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleBedrockModelCockpitRenderMixin.java)

它当前直接 `@Overwrite` 了 `VehicleBedrockModel.renderSpecialBones(...)`，已经不是简单补丁，而是**整段 special bone 渲染流程由 RVP 接管**。

#### C. RVP 自己的扩展解释层

- [RVP_RenderTypes.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_RenderTypes.java)
- [RVP_DisplayTransparentModeManager.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/RVP_DisplayTransparentModeManager.java)

当前 `RVP` 已经开始自己解释 display 扩展字段：

- `ywzj_rvp_transparent_mode`
- 当前已实现值：`cockpit_depth_fix`

说明 `RVP` 已经不是单纯“借用 SBM”，而是开始拥有自己的 display 扩展协议。

---

## 3. 当前需要迁移的能力清单

如果要把 SBM 相关能力逐步迁到 `RVP` 侧，实际至少要覆盖下面五块：

### 3.1 RenderType 工厂层

当前能力来源：

- SBM 的 `BedrockModelRenderTypes`
- 本体的 `ModRenderTypes`
- RVP 自己的 [RVP_RenderTypes.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_RenderTypes.java)

迁移目标：

- 以后 RVP 所需的 `cutout / transparent / cockpit transparent / additive / depth-fix` 等类型，全部在 `RVP` 自己的 render type 层完成，不再需要 mixin 去截流 SBM 或本体现成方法。

### 3.2 Bedrock model 渲染入口

当前能力来源：

- `VehicleBedrockModel.renderSpecialBones(...)`

迁移目标：

- RVP 自己提供 special bone 渲染分发器，至少能处理：
  - `MUZZLE_FLASH`
  - `TRANSPARENT`
  - `COCKPIT`
  - 后续自定义 transparent mode

### 3.3 display 扩展字段解释器

当前能力来源：

- [RVP_DisplayTransparentModeManager.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/RVP_DisplayTransparentModeManager.java)

迁移目标：

- 不只是透明模式，后续凡是和骨骼渲染行为有关的扩展，都统一由 `RVP` 自己的 display loader/manager 解释。

### 3.4 cockpit 乘员渲染上下文

当前能力来源：

- [VehicleRenderCockpitPassengerContextMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleRenderCockpitPassengerContextMixin.java)
- [VehicleBedrockModelCockpitRenderMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleBedrockModelCockpitRenderMixin.java)

迁移目标：

- 乘员预渲染、座舱玻璃深度修复、operator 视角跳过 cockpit 骨骼等逻辑，不再靠 overwrite 原函数硬插。

### 3.5 动画与 switchable runner 接入层

当前能力来源：

- [VehicleDisplayMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleDisplayMixin.java)

迁移目标：

- RVP 自己把 display/controller/switchable 的扩展 runner 注册进去，不再在 `VehicleDisplay.createAnimationInstance(...)` 返回后插 runner。

---

## 4. 建议迁移路线

建议按“先旁路新增，再平替接管，最后删 mixin”的路线走，不建议一步到位硬拔。

---

## 5. 分阶段实施

### 阶段一：抽离 RenderType 层

#### 目标

把所有 RVP 需要的透明渲染类型、座舱类型、无深度写入类型，全部收口到 `RVP_RenderTypes`。

#### 当前基础

- [RVP_RenderTypes.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_RenderTypes.java)

#### 本阶段新增/调整建议

1. 明确 RVP 自己的 render type 命名规范。
2. 按用途拆分：
   - 普通透明
   - cockpit 透明
   - 仅颜色写入、不写深度
   - additive
   - cutout/cull/no-cull
3. 为后续不依赖 `ModRenderTypes` 做准备。

#### 本阶段完成后可删除的 mixin

无。

说明：

- 这一阶段只是把 render type 能力收口，还没有替换上层调用入口。

---

### 阶段二：抽离 display 扩展解释层

#### 目标

把目前散落在 render/mixin 里的 display 特殊模式解释，统一放到 `RVP` 自己的 display 扩展管理器中。

#### 当前基础

- [RVP_DisplayTransparentModeManager.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/RVP_DisplayTransparentModeManager.java)

#### 本阶段新增/调整建议

1. 把当前 `ywzj_rvp_transparent_mode` 扩展成正式协议层。
2. 允许未来加入更多模式，例如：
   - `cockpit_depth_fix`
   - `translucent_no_depth_write`
   - `additive_no_depth_write`
   - `cockpit_shell`
3. 让 manager 不只绑定 bone 名，还能绑定：
   - effect type
   - texture route
   - render profile

#### 本阶段完成后可删除的 mixin

仍然无。

说明：

- 因为真正的调用入口还在 `VehicleBedrockModel.renderSpecialBones(...)` 里。

---

### 阶段三：建立 RVP 自己的 special bone 渲染分发器

#### 目标

把当前 `VehicleBedrockModelCockpitRenderMixin` overwrite 的整段 special bone 渲染链，从“改别人函数”变为“RVP 自己的显式渲染器”。

#### 当前最关键的痛点

- 当前逻辑在 [VehicleBedrockModelCockpitRenderMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleBedrockModelCockpitRenderMixin.java)
- 它重写了本体 `VehicleBedrockModel.renderSpecialBones(...)`
- 以后只要本体稍改 special bone 渲染流程，这条 overwrite 就有爆签名或行为漂移风险

#### 推荐做法

新增一层类似：

- `RVP_SpecialBoneRenderDispatcher`
- `RVP_CockpitSpecialBoneRenderer`

职责建议：

1. 输入：
   - `VehicleBedrockModel`
   - `specialBoneEntries`
   - `PoseStack`
   - `MultiBufferSource`
   - 乘员上下文
2. 输出：
   - 正确的 `VertexConsumer`
   - 正确的透明/座舱渲染分发
3. 把 `TRANSPARENT / COCKPIT / MUZZLE_FLASH` 的选择权拿回 RVP

#### 本阶段完成后可删除的 mixin

理论上可以准备删除：

- [VehicleBedrockModelCockpitRenderMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleBedrockModelCockpitRenderMixin.java)

但前提是：

1. `special_bone_effects` 已经不再依赖 overwrite 原函数。
2. cockpit 乘员预渲染路径已在 RVP 显式调度。
3. local operator 视角隐藏 cockpit 的逻辑已迁入新渲染器。

---

### 阶段四：抽离 cockpit 乘员渲染上下文

#### 目标

让“乘员在 cockpit 前后渲染”的顺序和上下文，不再靠在 `VehicleRender.render(...)` 上打注入点。

#### 当前依赖

- [VehicleRenderCockpitPassengerContextMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleRenderCockpitPassengerContextMixin.java)

#### 推荐做法

把 `RVP_CockpitPassengerRenderContext` 与 `RVP_CockpitPassengerRenderer` 做成一套显式调用链，由 RVP 的 vehicle render 包装层自己调用：

1. before cockpit
2. render passenger
3. render cockpit shell/glass
4. clear context

#### 本阶段完成后可删除的 mixin

- [VehicleRenderCockpitPassengerContextMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleRenderCockpitPassengerContextMixin.java)

前提：

1. 乘员预渲染调用点已不依赖 `VehicleRender.render(...)` 的特定 invoke 时机。
2. cockpit 深度修复在新链路下行为一致。

---

### 阶段五：抽离 display/animation/switchable 扩展接入

#### 目标

让动画实例创建时的扩展 runner 注册不再依赖 `VehicleDisplayMixin`。

#### 当前依赖

- [VehicleDisplayMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleDisplayMixin.java)

#### 当前功能

- 给 radar unit 自动补 `SwitchableRunner`

#### 推荐做法

把这部分迁成：

1. RVP 自己的 animation instance factory 包装层
2. 或 RVP 自己的 switchable runner registrar

避免在 `createAnimationInstance(...)` 返回后再塞 runner。

#### 本阶段完成后可删除的 mixin

- [VehicleDisplayMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleDisplayMixin.java)

---

### 阶段六：评估是否还需要直接打 SBM 类

#### 当前依赖

- [BedrockModelRenderTypesMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/BedrockModelRenderTypesMixin.java)

#### 目标

理想状态是，RVP 自己的渲染链已经不再需要通过 mixin 劫持 SBM 的 `BedrockModelRenderTypes`。

#### 删除条件

只有在以下条件都满足时，才建议删：

1. RVP 车型显示链路已经不再走 SBM 默认的 `polyMeshCutout(...)` 分发入口。
2. RVP 自己能为所需 mesh/cube 渲染路径显式指定 render type。
3. 删除后不会让旧 display/model 回落到本体默认透明实现。

---

## 6. 最终希望删除的 SBM/渲染相关 mixin 清单

按优先级排序：

### 第一优先级

- [VehicleBedrockModelCockpitRenderMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleBedrockModelCockpitRenderMixin.java)

原因：

- 这是当前最脆弱、最重、最容易被本体更新打爆的一条 overwrite。

### 第二优先级

- [VehicleRenderCockpitPassengerContextMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleRenderCockpitPassengerContextMixin.java)

原因：

- 强依赖本体 `VehicleRender.render(...)` 内部调用顺序。

### 第三优先级

- [VehicleDisplayMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleDisplayMixin.java)

原因：

- 逻辑不算复杂，但属于运行时后置插入，长期维护性一般。

### 第四优先级

- [ModRenderTypesMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/ModRenderTypesMixin.java)
- [BedrockModelRenderTypesMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/BedrockModelRenderTypesMixin.java)

原因：

- 这两条属于 render type 截流层，风险没 overwrite 那么大，但它们说明渲染控制权仍没真正回到 RVP 自己手里。

---

## 7. 迁移中的主要风险点

### 7.1 透明排序行为变化

当前座舱玻璃问题本质上就和透明排序、深度写入、乘员渲染顺序强相关。迁移过程中最容易出现：

1. 玩家又被玻璃挡没
2. 玩家被排到玻璃前面，像坐在座舱外
3. 透明层级和 cockpit 骨骼支架混在一起

所以迁移不能只追求“删 mixin”，还必须逐车复测。

### 7.2 本体 display/model 兼容性

如果新链路只照顾 RVP 自己的扩展车型，而没有兼容旧 display/special bone 写法，会导致：

1. 老载具渲染退化
2. cockpit 消失
3. 透明材质回退到本体默认实现

### 7.3 动画 runner 注册时机变化

当前 `VehicleDisplayMixin` 是在 animation instance 创建后补 runner。改成显式注册后，容易出现：

1. runner 注册过早，模型/动画还未绑定
2. runner 注册过晚，首帧状态异常
3. radar/launcher/switchable 初始姿态不一致

### 7.4 继续依赖本体 `VehicleBedrockModel`

如果你只删 mixin，但底层仍完全依赖本体 `VehicleBedrockModel` 的内部流程，那只是把风险从“可见”挪成“隐性”，后续照样会被本体更新反咬。

所以真正的迁移，不是只删文件，而是要把关键渲染控制点显式化。

---

## 8. 推荐执行顺序

建议按下面顺序做，而不是想到哪改到哪：

1. 继续收口 `RVP_RenderTypes`
2. 扩展 `RVP_DisplayTransparentModeManager`，把透明模式协议做完整
3. 新建 `RVP_SpecialBoneRenderDispatcher`
4. 新建 RVP 自己的 cockpit 乘员渲染调用链
5. 平替 `VehicleDisplayMixin` 的 runner 注册逻辑
6. 删除 `VehicleBedrockModelCockpitRenderMixin`
7. 删除 `VehicleRenderCockpitPassengerContextMixin`
8. 删除 `VehicleDisplayMixin`
9. 评估并删除 `ModRenderTypesMixin`
10. 最后评估并删除 `BedrockModelRenderTypesMixin`

---

## 9. 建议的验收标准

在真正删 mixin 之前，至少要满足以下验收项：

1. 单层半透明座舱下，第三人称能稳定看到乘员，不会被整层剔除。
2. 乘员不会被排序到座舱外侧。
3. `cockpit_depth_fix` 相关 display 写法继续生效。
4. 各类 special bone 透明效果与当前 RVP 行为一致。
5. radar / launcher / bay / canopy 等 switchable 动画首帧和切换过程无回退。
6. 删除某条 mixin 后，没有其它载具 display 出现大面积回归。

---

## 10. 结论

当前 `RVP` 已经不是“简单依赖 SBM”，而是：

- 一部分能力直接拦截 SBM
- 一部分能力重写本体 SBM 封装层
- 一部分能力已经在 RVP 自己这边解释和扩展

因此，正确的迁移方向不是“一次性把 SBM 全搬过来”，而是：

1. 先把**渲染控制权**拿回 RVP
2. 再把**special bone / cockpit / display 扩展解释权**拿回 RVP
3. 最后再删掉相关 mixin

其中优先级最高、最值得最先替换的，就是：

- [VehicleBedrockModelCockpitRenderMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleBedrockModelCockpitRenderMixin.java)

因为它是当前整条 SBM 魔改链里最重、最脆、也最值钱的一块。
