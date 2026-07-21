# SBM 迁移后续计划

本文档只讨论 `ywzj_rvp` 侧对 `simplebedrockmodel / vehicle render` 的后续迁移收口，不讨论雷达、导弹、爆炸、武器逻辑等其它 mixin。

---

## 1. 这次问题说明了什么

这次 `custom mount` 不渲染，已经用实测证明了一个关键事实：

- `RVP` 自己的 `display / backend / model / render type` 主链已经存在
- `rvp_custom_mounts` 配置也已经正确加载
- 但**最终实际生效的载具渲染入口，还没有完全稳定地收敛到 RVP 自己的原生 renderer 链**

也就是说，当前状态不是“SBM 完全没迁”，而是：

- **中层能力已经迁了不少**
- **最关键的最后一跳接管还不彻底**

本次修复里，重新接回了：

- `VehicleRenderCustomMountMixin`
- `WeaponUnitCustomMountSuppressMixin`

这说明当前 `custom mount` 这条链，仍然需要借助本体 `VehicleRender / WeaponUnit.render` 的挂接点才能稳定落地。

---

## 2. 当前已经迁到 RVP 侧的部分

以下部分可以视为已经具备了 “RVP 原生后端” 雏形：

### 2.1 Display / backend 分流

已完成：

- `ywzj_rvp:*` display type 注册
- `bedrock_backend` 字段支持
- `RVP_BaseDisplay / RVP_VehicleDisplay / RVP_*VehicleDisplay`
- `RVP_BedrockBackend`

对应代码：

- [RVP_DisplayTypes.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/all/RVP_DisplayTypes.java)
- [RVP_BaseDisplay.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/vehicle/RVP_BaseDisplay.java)
- [RVP_VehicleDisplay.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/vehicle/RVP_VehicleDisplay.java)

### 2.2 Bedrock model 后端

已完成：

- `RVP_VehicleBedrockModel`
- `RVP_VehicleModelFactory`
- `RVP_RenderTypes`
- 透明座舱 / cockpit depth fix / special bone 的一部分自有实现

对应代码：

- [RVP_VehicleBedrockModel.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/vehicle/RVP_VehicleBedrockModel.java)
- [RVP_VehicleModelFactory.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/vehicle/RVP_VehicleModelFactory.java)
- [RVP_RenderTypes.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_RenderTypes.java)

### 2.3 RVP 自己的 vehicle renderer

已完成：

- `RVP_VehicleRender`
- `RVP_ClientBootstrap` 里的 entity renderer 注册
- `RVP_CockpitPassengerRenderContext`
- `RVP_CockpitPassengerRenderer`
- `RVP_CustomMountRenderLogic`

对应代码：

- [RVP_VehicleRender.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_VehicleRender.java)
- [RVP_ClientBootstrap.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/RVP_ClientBootstrap.java)
- [RVP_CustomMountRenderLogic.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_CustomMountRenderLogic.java)

### 2.4 client reload 辅助链

已完成：

- `RVP_CustomMountReloadListener`
- `RVP_DisplayTransparentModeManager`
- `RVP_ClientReloadListeners`

---

## 3. 当前还没有真正迁完的部分

这部分才是后续工作的重点。

### 3.1 最关键缺口：最终渲染入口接管还不彻底

当前仍依赖：

- [VehicleRenderCustomMountMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleRenderCustomMountMixin.java)
- [WeaponUnitCustomMountSuppressMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/WeaponUnitCustomMountSuppressMixin.java)

说明：

- `RVP_VehicleRender` 理论上已经包含了 `custom mount + suppress default weapon display` 的逻辑
- 但运行实测中，**本体 `VehicleRender` 仍然是更稳定、甚至可能是实际主导的渲染入口**
- 因此当前 RVP 自己的 renderer 还不能视为“唯一可信入口”

这就是当前 SBM 迁移最大的未完成项。

### 3.2 Weapon/bolt 结构扩展链还没原生化

当前仍依赖：

- [VehicleDataManagerMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleDataManagerMixin.java)
- [WeaponUnitDataMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/WeaponUnitDataMixin.java)

尤其要注意：

- `WeaponUnitDataMixin` 里和 `rvp_structure_bolt_bones` 相关的 `initExtraBoltBones` 目前还是空实现
- 这条链虽然不属于“纯 renderer”，但它直接影响 `custom mount` / 多挂点 / attach bolt 结果
- 所以它属于 **SBM 迁移的结构数据边界问题**

### 3.3 decoration / weapon display 的 backend 落地还不统一

当前状态：

- 载具 display 已经较明确地走 `ywzj_rvp:* + bedrock_backend=rvp`
- 但 `custom mount` 实际是通过 decoration display / weapon display / model path 多重回退来找模型
- 这说明 `attachment display` 这条链还没有像 vehicle display 那样彻底收敛

换句话说，当前 `custom mount` 的模型查找还偏“兼容层”，不是完全干净的原生链。

### 3.4 动画 runner 体系还没有完全 RVP 化

当前已经有：

- `RVP_RadarLoopSwitchableRunner`
- `RVP_SwitchableRunnerFactory`

但还不能直接下结论说：

- 所有 switchable / loop / special animation runner 都已经脱离本体行为

这块要单独做一次 runner 维度的迁移梳理。

---

## 4. 最终目标形态

SBM 真正迁完后，理想状态应该是：

### 4.1 载具渲染

- RVP 载具统一走 `RVP_VehicleRender`
- 不再需要 `VehicleRenderCustomMountMixin`

### 4.2 武器挂载渲染

- `custom mount` 的显示、隐藏、逐枚消失、挂架替换全部由 RVP 自己的 render 分发层接管
- 不再需要 `WeaponUnitCustomMountSuppressMixin`

### 4.3 display/backend

- vehicle / decoration / weapon 三级 display 都明确支持 `bedrock_backend`
- 模型来源、后端选择、special bone 行为不再依赖兼容式回退

### 4.4 结构挂点

- `rvp_structure_bolt_bones`
- `rvp_custom_mounts`
- attach part / attach bone / bolt 推导

这些要么：

- 做成显式 RVP 数据扩展加载链  

要么：

- 迁入 RVP 自己的资源解析层  

总之不能长期靠本体 data manager / weapon data mixin 打补丁。

---

## 5. 后续迁移阶段

## 阶段 A：把当前“为什么必须靠 mixin 才显示”彻底查实

目标：

- 搞清楚为什么 `RVP_VehicleRender` 理论上已具备逻辑，但 `custom mount` 实测仍要靠本体 `VehicleRender` 注入点

要做的事：

1. 给 `RVP_VehicleRender` 加一次性探针  
2. 给 `RVP_ClientBootstrap` 的 renderer 注册加探针  
3. 在游戏里确认 `rafale / j15 / cssa5` 实际拿到的 renderer class  
4. 确认是否存在：
   - entity renderer 被后注册覆盖
   - 某些 vehicle entity type 仍绑定到本体 renderer
   - `RVP_VehicleRender` 被注册但未实际使用

阶段完成标准：

- 能明确写出“当前真正生效的 renderer 是谁”
- 能明确说明“为什么 mixin 能显示、纯 RVP renderer 却不能稳定显示”

这是当前第一优先级。

---

## 阶段 B：让 RVP renderer 成为唯一可信入口

目标：

- 让 `custom mount`、默认武器显示抑制、cockpit passenger、special bones 全部只通过 `RVP_VehicleRender` 生效

要做的事：

1. 验证所有 RVP vehicle entity type 都实际落到 `RVP_VehicleRender`
2. 在 `RVP_VehicleRender` 内建立明确的 render pipeline：
   - vehicle model
   - special bones
   - cockpit passenger
   - custom mount
   - part units
   - decoration units
3. 明确 `WeaponUnit.render()` 的默认显示抑制责任归属
4. 找到 `WeaponUnitCustomMountSuppressMixin` 的替代挂点

可能的落地方式：

- 在 part render 分发前统一判断 `replaceWeaponDisplay`
- 或在 RVP 侧建立自己的 `WeaponUnit` 显示代理层

阶段完成标准：

- 删除 `VehicleRenderCustomMountMixin` 后，custom mount 仍稳定可见
- 删除 `WeaponUnitCustomMountSuppressMixin` 后，不会出现默认武器和 custom mount 双重渲染

---

## 阶段 C：清理 custom mount 的兼容回退链

目标：

- 让 custom mount 的 attachment model / display / backend 解析变成清晰的单链路

要做的事：

1. 统一 decoration display 的 `type`
2. 统一 `bedrock_backend=rvp` 的 attachment display 处理
3. 缩减 `getAttachmentModel()` 里的多路回退
4. 明确：
   - display id 查找
   - model path 查找
   - direct model load
   的优先级和适用场景

阶段完成标准：

- `RVP_CustomMountRenderLogic` 中 attachment model 获取逻辑明显收敛
- 不再依赖“多个 display 容器兜底碰碰运气”

---

## 阶段 D：迁移结构挂点与 bolt 扩展链

目标：

- 把 `custom mount` 相关的结构挂点数据链从“mixin 修补”收束到 RVP 自己的显式扩展层

要做的事：

1. 恢复并校正 `rvp_structure_bolt_bones` 的真实实现
2. 审核 `WeaponUnitDataMixin` 是否继续保留
3. 审核 `VehicleDataManagerMixin` 是否继续保留
4. 评估是否需要建立 `RVP` 自己的 vehicle extension parse 层

阶段完成标准：

- 多挂点 attach transform 可稳定计算
- `rafale / j15 / F22 / IRIST SLM / CSSA5` 这类多挂点、可变挂架、发射架类载具全部正确

---

## 阶段 E：runner / animation 体系收口

目标：

- 让 switchable / radar loop / special animation runner 不再依赖本体行为差异

要做的事：

1. 梳理当前 RVP 已有 runner 子类
2. 统计仍继承本体 runner 但行为被补丁修正的点
3. 将真正需要魔改的 runner 迁入 RVP 自己的 factory / runner 链

阶段完成标准：

- radar、scan_radar、launcher deploy、bay、透明座舱相关动画在 RVP backend 下行为一致

---

## 6. 当前大致完成度评估

按“SBM 从本体迁到 RVP 原生链”的总目标粗略估算：

- **已完成：约 60%**
- **未完成：约 40%**

这个 60% 主要来自：

- display/backend 分流已建立
- RVP model factory 已建立
- RVP render type 已建立
- RVP vehicle renderer 已存在
- cockpit / transparent / custom mount logic 已经有 RVP 版本

剩下 40% 主要集中在：

- 最终渲染入口接管不彻底
- custom mount 仍要靠 mixin 托底
- weapon suppress 仍要靠 mixin
- bolt / structure 扩展链未原生化
- animation runner 体系未完全收口

如果只看“custom mount 完全原生化”这个子目标，当前完成度更低，约：

- **35% ~ 45%**

因为它还卡在最关键的入口接管和结构挂点链。

---

## 7. 推荐执行顺序

建议后续严格按这个顺序推进：

1. **先做阶段 A**  
   查清当前到底是谁在渲染载具，别再盲拆

2. **再做阶段 B**  
   让 `RVP_VehicleRender` 成为唯一可信入口

3. **再做阶段 C**  
   清掉 custom mount attachment model 的兼容回退链

4. **再做阶段 D**  
   收口 bolt / structure 数据扩展链

5. **最后做阶段 E**  
   清理 runner / animation 细枝末节

---

## 8. 验收清单

当以下条件全部成立时，可以认为 SBM 迁移主任务基本完成：

- [ ] 删除 `VehicleRenderCustomMountMixin` 后，custom mount 仍正常渲染
- [ ] 删除 `WeaponUnitCustomMountSuppressMixin` 后，不会双重渲染
- [ ] `rafale / j15 / f22` 的可变挂架全部正常
- [ ] `IRIST SLM / CSSA5` 等含复杂骨骼、雷达、发射架的载具正常
- [ ] 半透明座舱、cockpit passenger、special bone 都正常
- [ ] `bedrock_backend=rvp` 下的 vehicle / decoration / weapon display 行为一致
- [ ] 不再需要为 SBM 主链新增 mixin 托底

---

## 9. 当前结论

当前不是“SBM 白迁了”，而是：

- **骨架已经搭起来了**
- **但最后一跳控制权还没真正抢过来**

这次 `custom mount` 事件非常有价值，因为它把真正的缺口钉出来了：

- 问题不是配置
- 问题不是模型资源
- 问题不是 attach weapon 匹配
- 问题是 **最终渲染接管边界还不稳**

后续只要沿着这个边界继续收口，SBM 迁移就是能做完的，而且方向已经比之前清楚很多。
