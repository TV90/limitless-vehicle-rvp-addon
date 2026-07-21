# simplebedrockmodel_rvp接入设想

## 1. 目标

计划在 `RVP` 侧逐步引入一套私有的 Bedrock model 渲染后端，暂命名为：

- `simplebedrockmodel_rvp`

这套后端的核心诉求不是替代全项目所有模型，而是：

1. 允许指定载具/武器 display 走 `RVP` 自己的 Bedrock 渲染链。
2. 保持与本体 `ywzj_vehicle` 内置 SBM 并存。
3. 为后续透明座舱、special bone、自定义动画、渲染排序等魔改提供稳定落点。
4. 在迁移成熟后，逐步删除当前与本体/SBM 强耦合的相关 mixin。

---

## 2. 基本原则

### 2.1 不做第二个“同名 SBM 模组”

不建议把原始 SBM 源码几乎不改地再打包成一个独立 mod。

原因：

1. 容易和本体已内置的 SBM 形成类名、入口、资源注册或 mixin 级冲突。
2. 后续排查问题时，很难区分当前到底走的是哪一套渲染链。
3. 你真正想掌控的不是“模组名”，而是“RVP 自己的渲染后端解释权”。

### 2.2 改包名，做成 RVP 私有实现

推荐做法：

1. 把需要的 Bedrock model 能力迁入 `RVP` 自己的包空间，例如：
   - `org.ywzj.rvp.sbm.*`
   - `org.ywzj.rvp.client.bedrock.*`
2. 不复用原 SBM 的 mod id。
3. 不复用原 SBM 的类全名。
4. 不让它作为“第二个通用 SBM 模组”对外暴露，而是只作为 `RVP` 内部后端使用。

这样才能避免和本体自带的 SBM 直接冲突。

---

## 3. 后端选择开关应放在哪

## 3.1 结论

**开关最适合放在 display 根节点，而不是 model 文件里。**

推荐类似写法：

```json
{
  "type": "rvp:fixed_wing_vehicle",
  "model": "rvp:entity/f22",
  "texture": "rvp:textures/entity/f22.png",
  "animations": "rvp:entity/f22",
  "animation_controller": "rvp:entity/f22",
  "bedrock_backend": "rvp"
}
```

建议保留两个值：

- `vehicle`：走本体内置 `VehicleBedrockModel`
- `rvp`：走 `simplebedrockmodel_rvp`

默认值建议为：

- `vehicle`

只有需要测试或已迁移完成的 display 才显式写：

- `bedrock_backend: "rvp"`

---

## 3.2 为什么不建议放在 model 文件里

当前项目里：

- `display` 决定模型、贴图、动画、special bone effect、声音、controller 等整套渲染表现
- 同一个 `model` 可能被多个不同 `display` 复用

如果把后端开关写在 `model` 文件里，会带来这些问题：

1. 同一个模型无法被两个 display 分别用不同后端渲染。
2. 后端选择变成“几何资源属性”，而不是“显示实现策略”，层级不对。
3. 以后要做灰度迁移时不方便。

所以后端选择本质上应属于：

- **display/render backend 配置**

而不是：

- **model geometry 配置**

---

## 4. 为什么不能在最终渲染时再切后端

从当前本地链路看：

- [BaseDisplayPojo.java](D:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/resource/vehicle/BaseDisplayPojo.java)
- [BaseDisplay.java](D:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/resource/vehicle/BaseDisplay.java)
- [DisplayManager.java](D:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/resource/DisplayManager.java)
- [VehicleRender.java](D:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/render/entity/vehicle/VehicleRender.java)

当前流程是：

1. `DisplayManager` 读取 `display/*.json`
2. `BaseDisplay` 构造时就已经：
   - 加载 model pojo
   - `new VehicleBedrockModel(...)`
   - 绑定 animations
   - 初始化 special bone effect
3. `VehicleRender.render(...)` 拿到的是已经构造完成的 model 实例

这意味着：

**如果到最终渲染时才切“用哪套 SBM”，已经太晚了。**

因为此时：

1. model 已经按旧后端建好了
2. animation 已经和旧 model 绑定了
3. special bone / cockpit / transparent 的处理链也已经定型

所以后端开关必须在 **display parse / display construct** 阶段生效。

---

## 5. 推荐实现路线

## 5.1 不推荐路线

### 路线 A：强行给本体 `BaseDisplayPojo/BaseDisplay` 打补丁

做法大概是：

1. mixin `BaseDisplayPojo` 增字段
2. mixin `BaseDisplay` 改构造逻辑
3. 让它根据 `bedrock_backend` 在 `VehicleBedrockModel` 和 `RVPVehicleBedrockModel` 间切换

问题：

1. 仍然高度侵入本体 display 装载链
2. 后续本体一改构造逻辑就容易再炸
3. 和你最终“把相关 mixin 去掉”的目标不一致

所以不建议把它作为长期方案。

---

## 5.2 推荐路线

### 路线 B：RVP 注册自己的 display type

建议由 `RVP` 自己注册一套 display type，例如：

- `rvp:generic`
- `rvp:fixed_wing_vehicle`
- `rvp:rotary_wing_vehicle`
- `rvp:wheeled_vehicle`
- `rvp:tracked_vehicle`

然后在 `RVP` 自己的 display pojo / display 实现里加入：

- `bedrock_backend`

例如：

- `RVP_BaseDisplayPojo extends BaseDisplayPojo`
- `RVP_VehicleDisplay extends VehicleDisplay`

在构造阶段决定：

1. `bedrock_backend = "vehicle"`  
   使用本体 `VehicleBedrockModel`
2. `bedrock_backend = "rvp"`  
   使用 `RVPVehicleBedrockModel`

这样有几个优点：

1. 完全可以只在 `RVP` 侧完成。
2. 不需要改本体源码。
3. 旧 display 不受影响。
4. 新 display 可以渐进迁移。
5. 日后删 mixin 时，迁移边界更清楚。

---

## 6. 第一阶段最小可行版本建议

如果只是“先搭架子，未来再慢慢迁”，建议第一阶段不要贪大，最小版本只做这些：

### 6.1 数据层

新增：

- `bedrock_backend`

建议枚举值：

- `vehicle`
- `rvp`

默认：

- `vehicle`

### 6.2 显示层

新增：

- `RVPVehicleBedrockModel`
- `RVPBaseDisplay`
- `RVPVehicleDisplay`

### 6.3 渲染层

先只确保：

1. 能 render cube / mesh
2. 能绑定 texture
3. 能正常应用基础 pose
4. 能跑基本 animation

### 6.4 暂时不急着完整迁的部分

这些可以后续再迁：

1. cockpit depth fix
2. special bone effect
3. 自定义透明模式
4. switchable runner
5. afterburner 附加模型
6. custom mount 深度整合

也就是说，第一阶段目标不是功能全平替，而是先建立：

- “**一份 display 可以决定走旧后端还是新后端**”

这条能力。

---

## 7. 迁移完成后想去掉的 mixin，对应前提

### 7.1 最优先想去掉的

- [VehicleBedrockModelCockpitRenderMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleBedrockModelCockpitRenderMixin.java)

前提：

1. `simplebedrockmodel_rvp` 已能接管 special bone 渲染
2. cockpit 深度修复已由新后端自身实现
3. 透明骨骼不再依赖 overwrite 本体函数

### 7.2 第二批

- [VehicleRenderCockpitPassengerContextMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleRenderCockpitPassengerContextMixin.java)
- [VehicleDisplayMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleDisplayMixin.java)

前提：

1. 乘员 cockpit 渲染时序已有显式替代链
2. animation/switchable 扩展不再靠后置插入

### 7.3 最后评估

- [ModRenderTypesMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/ModRenderTypesMixin.java)
- [BedrockModelRenderTypesMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/BedrockModelRenderTypesMixin.java)

前提：

1. `RVP` 自己的 render type 分发已经完整
2. 新后端不再依赖截流本体/SBM 默认 render type

---

## 8. 主要风险点

### 8.1 透明排序与深度行为回归

你现在最在意的就是半透明座舱、乘员排序、cockpit 深度修复。

如果新后端先只把“模型画出来”，但没把：

1. 深度写入
2. 深度测试
3. layering
4. 乘员渲染时序

这些一起迁过去，很容易出现：

1. 玩家又被整层玻璃剔除
2. 玩家像坐在座舱外
3. 透明支架和玻璃互相穿插

### 8.2 动画和 model 的绑定时机

当前本体是 `BaseDisplay` 构造时就创建：

1. model
2. animation map
3. controller 相关链路

如果 `rvp` 后端对 animation 的模型绑定规则和本体稍有差异，就会出现：

1. 首帧姿态错误
2. switchable 骨骼状态错乱
3. 某些部件动画不动

### 8.3 同一个 display 只迁一半

如果只是给 display 写了 `bedrock_backend: "rvp"`，但新后端还没实现：

1. special bone
2. cockpit
3. animation controller
4. custom mount 相关骨骼访问

那它不是“部分功能缺失”这么简单，而可能是整台车渲染体验直接回退。

---

## 9. 建议的长期路线

### 第一阶段

建立后端切换能力：

- display 可选 `vehicle / rvp`

### 第二阶段

让少量测试载具先跑 `rvp` 后端：

- 优先拿几台你最想魔改透明/座舱的机体试

### 第三阶段

补齐：

1. transparent
2. cockpit
3. special bone
4. animation/switchable

### 第四阶段

验证成熟后，再逐步删除现在这些 SBM 相关 mixin。

---

## 10. 结论

这套方案是可行的，但要注意：

1. 不要把它做成“第二个同名 SBM 模组”
2. 要做成 `RVP` 私有的 Bedrock 渲染后端
3. 后端开关应放在 **display 根节点**
4. 开关必须在 **display 构造阶段** 生效
5. 最好的落地方式，是 **RVP 自己注册一套 display type** 来承载这套切换，而不是强行侵入本体 `BaseDisplay`

如果以后正式开始做，这份文档可作为第一版总体设计记录。
