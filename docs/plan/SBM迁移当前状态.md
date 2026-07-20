# SBM 迁移当前状态

本文档只记录 **RVP 侧 simplebedrockmodel / vehicle render 迁移的当前落地状态**，不讨论其它武器、雷达、爆炸、制导等 mixin。

## 已删除的 SBM 相关 mixin

以下 mixin 已经完成平替并从 `ywzj_rvp.mixins.json` 中移除：

1. `VehicleDisplayMixin`
2. `VehicleBedrockModelCockpitRenderMixin`
3. `VehicleRenderCockpitPassengerContextMixin`
4. `VehicleRenderCustomMountMixin`
5. `ClientAssetsManagerCustomMountMixin`
6. `BedrockModelRenderTypesMixin`
7. `ModRenderTypesMixin`

## 当前已迁入 RVP 的主链

### 1. Display Type 与 backend 分流

- `ywzj_rvp:*` display type 已注册
- `display` 根节点支持 `bedrock_backend`
- 当前游戏目录中的 RVP 载具 display 已切到：
  - `type = ywzj_rvp:*`
  - `bedrock_backend = "rvp"`

对应代码：

- [RVP_DisplayTypes.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/all/RVP_DisplayTypes.java)
- [RVP_BaseDisplay.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/vehicle/RVP_BaseDisplay.java)
- [RVP_VehicleDisplay.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/vehicle/RVP_VehicleDisplay.java)

### 2. Vehicle bedrock model 主链

- `special bone` 渲染逻辑已在 RVP 自己的 `VehicleBedrockModel` 子类内落地
- `cockpit_depth_fix` 不再通过 overwrite 本体 `VehicleBedrockModel.renderSpecialBones(...)` 实现
- `poly mesh / cube` 透明渲染已改为走 `RVP_RenderTypes`

对应代码：

- [RVP_VehicleBedrockModel.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/vehicle/RVP_VehicleBedrockModel.java)
- [RVP_RenderTypes.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_RenderTypes.java)
- [RVP_VehicleModelFactory.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/vehicle/RVP_VehicleModelFactory.java)

### 3. Vehicle renderer 主链

- RVP 已新增自己的 vehicle renderer
- `cockpit passenger context`
- `custom mount render`
- `special bones render`

以上三条链路现在由 RVP renderer 显式调用，不再通过 mixin 插入本体 `VehicleRender`

对应代码：

- [RVP_VehicleRender.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_VehicleRender.java)
- [RVP_CockpitPassengerRenderContext.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_CockpitPassengerRenderContext.java)
- [RVP_CockpitPassengerRenderer.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_CockpitPassengerRenderer.java)
- [RVP_CustomMountRenderLogic.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_CustomMountRenderLogic.java)
- [RVP_ClientBootstrap.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/RVP_ClientBootstrap.java)

### 4. Projectile bedrock 渲染主链

- RVP 自己的 projectile renderer 已改为直接走 `RVP_RenderTypes.polyMeshCutout(...)`
- 不再依赖 `BedrockModelRenderTypesMixin`

对应代码：

- [VehicleProjectileRenderLogic.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/VehicleProjectileRenderLogic.java)
- [RVP_BedrockProjectileEntityRenderer.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/render/RVP_BedrockProjectileEntityRenderer.java)

### 5. Custom mount 缓存清理主链

- custom mount model cache 的清理不再 hook 本体 `ClientAssetsManager.reload(...)`
- 改为 RVP 自己注册 `client reload listener`

对应代码：

- [RVP_CustomMountReloadListener.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/resource/RVP_CustomMountReloadListener.java)
- [RVP_ClientReloadListeners.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/client/RVP_ClientReloadListeners.java)

## 当前故意保留、但不应误删的“渲染边界相关 mixin”

以下类虽然和显示/动画/挂架边界有关，但 **不属于这次已经替掉的 SBM 核心 mixin**，目前应保留：

### `WeaponUnitCustomMountSuppressMixin`

作用：

- 抑制本体 `WeaponUnit.render(...)` 的默认武器显示
- 配合 custom mount 的显示替代
- 在 `onClientFire` 时做预测弹药可视数更新

为什么现在不删：

- 这不是 `simplebedrockmodel` 本体渲染器的 patch
- 它 patch 的是 `WeaponUnit` 业务层行为
- 如果现在直接删掉，会导致 custom mount 与本体默认武器显示重叠，或者弹药逐枚消失逻辑退化

文件：

- [WeaponUnitCustomMountSuppressMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/WeaponUnitCustomMountSuppressMixin.java)

### `SwitchableRunnerLoopMixin`

作用：

- 给搜索雷达 `scan_radar` 这类 switchable 动画补循环行为

为什么现在不删：

- 虽然和动画 runner 有关，但它 patch 的是 `SwitchableRunner.tick()`
- 目前只是把 `runner` 的创建迁进了 `RVP_VehicleDisplay`
- `scan_radar` 的循环 tick 行为还没有完全做成 RVP 自己的 runner 子类

文件：

- [SwitchableRunnerLoopMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/SwitchableRunnerLoopMixin.java)

### `VehicleDataManagerMixin`

作用：

- 读取 vehicle json 里的 `custom_mounts`
- 填充 `RVP_CustomMountConfigCache`

为什么现在不删：

- 这是数据加载链，不是 SBM renderer patch
- 如果删掉，custom mount 配置本身就读不到

文件：

- [VehicleDataManagerMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/VehicleDataManagerMixin.java)

### `WeaponUnitDataMixin`

作用：

- 从 structure model / bolt bone 扩展额外发射点

为什么现在不删：

- 这是 weapon data / bolt 解析逻辑
- 和 vehicle bedrock renderer 解耦，但仍属于结构模型数据扩展

文件：

- [WeaponUnitDataMixin.java](D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/WeaponUnitDataMixin.java)

## 当前结论

可以明确说：

- **RVP 的 vehicle display/model/renderer 主链已经接管**
- **前面那批最核心的 SBM 渲染 mixin 已经删掉**
- **剩余 mixin 没有乱删到其它系统**

但还不能说：

- “整个 RVP 项目已经彻底不依赖任何 mixin”
- “所有和结构模型/动画/挂架边界有关的 patch 都已经不需要了”

## 下一步若继续推进

后续如果还要进一步去 mixin，建议顺序：

1. 评估是否把 `SwitchableRunnerLoopMixin` 改造成 RVP 自己的 runner 类型
2. 评估是否把 `WeaponUnitCustomMountSuppressMixin` 的显示覆盖逻辑移到更明确的 weapon render 分发层
3. 最后再评估 `VehicleDataManagerMixin` / `WeaponUnitDataMixin` 是否值得做成显式扩展点

这三步已经不属于“先把 SBM 主链迁走”的范围，而属于第二阶段清理。
