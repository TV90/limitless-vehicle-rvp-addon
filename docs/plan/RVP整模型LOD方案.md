# RVP 载具渲染性能优化方案

本文档汇总 RVP 在载具渲染侧的全部性能优化手段，包括**整模型 LOD**、**状态机骨骼隐藏**、**距离骨骼隐藏**、**半透明材质距离降级**。这些机制均配置在车辆 display JSON 中，**纯客户端**生效，不影响服务端与其他玩家。

---

## 一、整模型 LOD（Level of Detail）

### 功能概述

当玩家与载具的距离达到配置阈值时，用**低面数模型**整体替换载具主体渲染，降低远距离下高模载具的渲染压力。

**核心特性**：
- **纯客户端**：只在当前玩家自己的渲染画面生效
- **全静态**：LOD 级使用烘焙后的静态 bind pose，不播放动画、不做动画混合，节省 CPU
- **贴图随 LOD 更换**：每级可指定独立贴图，缺省沿用原贴图
- **地面 / 空中双模型**：同一 LOD 级可配置 `model`（地面）与 `model_air`（离地），飞行时用更简化的模型
- **离地判定**：载具相对地面高度达到阈值视为飞行，切换不同距离阈值与模型

### 工作原理

1. 资源加载完成后（游戏启动 / F3+T 重载），`RVP_LodModelManager.rebindAll()` 扫描所有 display 配置，解析 `lod_models` 字段，预烘焙 LOD 静态模型并注册 LOD 贴图
2. 渲染每帧调用 `RVP_LodModelManager.resolve(vehicle)`，每 **20 tick（1 秒）** 评估一次当前玩家到载具的距离与载具离地状态
3. 命中阈值后，`VehicleRenderLodMixin` 在渲染时把该载具的模型实例替换为 LOD 实例、贴图替换为 LOD 贴图、跳过动画 pose 应用
4. 载具卸载 / 距离退回时恢复原模型；每车独立实例缓存，载具卸载自动回收

### 配置方法

在 display JSON 中加入顶层字段：

```json
"lod_models": [
  {
    "model": "rvp:entity/lod/j20a_lod",
    "model_air": "rvp:entity/lod/j20a_lod_air",
    "texture": "rvp:textures/entity/lod/j20lod.png",
    "distance": 250,
    "air_distance": 250,
    "air_height": 10
  }
]
```

#### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | 字符串 | 是 | LOD 模型资源 ID，对应 `assets/<ns>/models/bedrock/<path>.json` |
| `model_air` | 字符串 | 否 | 离地（飞行）时使用的 LOD 模型；缺省沿用 `model` |
| `texture` | 字符串 | 否 | LOD 贴图资源 ID，对应 `assets/<ns>/textures/<path>.png`；缺省沿用原贴图 |
| `distance` | 数字 | 是 | 地面状态下的进入阈值（格） |
| `air_distance` | 数字 | 否 | 离地状态下的进入阈值；缺省等于 `distance` |
| `air_height` | 数字 | 否 | 离地判定高度（格），载具 Y - 地面高度 ≥ 该值视为飞行；缺省 10 |

#### 多级 LOD

数组内可配置多级，**按距离从近到远排列**（最远级放最后），渲染时自动选择合适的一级：

```json
"lod_models": [
  { "model": "rvp:entity/j20a_lod1", "distance": 60,  "air_distance": 40 },
  { "model": "rvp:entity/j20a_lod2", "texture": "rvp:textures/entity/lod2.png", "distance": 150, "air_distance": 100 },
  { "model": "rvp:entity/j20a_lod3", "texture": "rvp:textures/entity/lod3.png", "distance": 300, "air_distance": 200 }
]
```

### 资源文件要求

- **模型文件**：与普通载具模型相同的 Blockbench 模型 JSON（`minecraft:geometry` 格式，支持 `poly_mesh`），导出为单文件
- **文件位置**：`assets/<namespace>/models/bedrock/<任意子目录>/<id>.json`，ID 即去掉 `models/bedrock/` 前缀与 `.json` 后缀的路径（可放任意深度的子目录）
- **格式要求**：必须能被 SBM v1 解析并烘焙；LOD 模型作为纯静态模型烘焙，**不要包含动画**
- **贴图**：任意位置的 PNG，直接放 `assets/<ns>/textures/` 下即可

### 行为规则

- **进入阈值**：`dist > T`（地面）或 `dist > air_distance`（离地）时切换
- **滞后防抖**：退回采用 `T × 0.85`，避免在阈值边缘反复横跳
- **离地判定**：`vehicle.getY() - level.getHeight(MOTION_BLOCKING, x, z) >= air_height`
- **评估频率**：每 20 tick（1 秒）评估一次
- **LOD 渲染期间**：模型、贴图整体替换；动画 pose 不应用（全静态）；部件、饰品、弹孔等仍按原逻辑叠加渲染
- **每车独立**：每辆载具独立实例缓存，多人服中每辆车在每台客户端上独立评估

---

## 二、状态机骨骼隐藏（state_hidden_bones）

### 功能概述

当载具进入指定状态并持续达到延时后，**隐藏（不渲染）**配置的骨骼。典型用途：起落架收起后隐藏起落架模型，避免收起状态下的模型残留。

**当前支持的状态**：`landing_gear_up`（起落架收起）。

### 配置方法

```json
"state_hidden_bones": [
  {
    "state": "landing_gear_up",
    "bones": ["lg0", "lg1", "lg0_0", "lg2", "lg5"],
    "delay_ticks": 60
  }
]
```

#### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `state` | 字符串 | 是 | 载具状态名，当前支持 `landing_gear_up` |
| `bones` | 字符串数组 | 是 | 要隐藏的骨骼名（与模型 JSON 中的骨骼名一致） |
| `delay_ticks` | 数字 | 否 | 进入状态后延时（tick）才开始隐藏，用于让收起动作动画完整播放；缺省 0 |

### 行为规则

- 状态进入后计时 `delay_ticks`，期满后这些骨骼不再渲染（`BoneState.visible = false`）
- 状态退出（如起落架放下）时**立即恢复**渲染
- 与距离骨骼隐藏共用一套渲染注入，互不干扰

---

## 三、距离骨骼隐藏（distance_hidden_bones）

### 功能概述

当玩家与载具的距离达到阈值时，**隐藏（不渲染）**配置的骨骼。典型用途：30m 外隐藏座舱内饰、武器挂架、驾驶舱细节等近距离才看得到的部件，中远距离直接省掉这些顶点的渲染开销。

### 配置方法

```json
"distance_hidden_bones": [
  {
    "distance": 30,
    "bones": ["cop", "J20B_Cop_Gate1", "youmen", "$steering_wheel4", "weapon0", "weapon1", "weapon2", "weapon3", "weapon4", "weapon5"]
  }
]
```

#### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `distance` | 数字 | 是 | 距离阈值（格），玩家到载具距离 **≥** 该值后隐藏 |
| `bones` | 字符串数组 | 是 | 要隐藏的骨骼名 |

### 行为规则

- 距离达标后骨骼不再渲染；进入阈值 **≤** 该值时恢复（无滞后，纯距离判定）
- 普通骨骼通过 `BoneState.visible = false` 跳过渲染
- **特殊骨骼（special bone）**：本身强制渲染不检查 visible，已被距离隐藏逻辑显式跳过（见第四节交互）

---

## 四、半透明材质距离降级（座舱盖变不透明）

### 功能概述

半透明渲染是渲染管线中开销最大的路径之一（深度排序、关闭深度写入、blend）。针对大面积半透明部件（如座舱盖玻璃），在距离达标后**保留网格但改为不透明（cutout）材质渲染**：

- 近距离（< 阈值）：正常半透明玻璃，带舱内深度修复
- 远距离（≥ 阈值）：座舱内饰已被距离隐藏，此时座舱盖换不透明材质——**既能遮住空座舱的难看，又省掉透明渲染开销**

### 配置方法

1. 座舱盖玻璃已在 `special_bone_effects` 中声明为透明骨骼：

```json
"special_bone_effects": [
  {
    "bone": "J20B_Cop_Gate1",
    "texture": "rvp:textures/entity/j20a.png",
    "type": "transparent",
    "ywzj_rvp_transparent_mode": "cockpit_depth_fix"
  }
]
```

2. 把同一骨骼加入 `distance_hidden_bones`，其距离阈值即半透明降级的触发阈值：

```json
"distance_hidden_bones": [
  {
    "distance": 30,
    "bones": ["cop", "J20B_Cop_Gate1", "youmen", "$steering_wheel4", "weapon0", "weapon1", "weapon2", "weapon3", "weapon4", "weapon5"]
  }
]
```

#### 字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| `ywzj_rvp_transparent_mode` | 字符串 | RVP 透明渲染模式，`cockpit_depth_fix` = 舱内深度修复模式 |
| `distance_hidden_bones[].distance` | 数字 | 既是座舱等骨骼的隐藏阈值，也是座舱盖玻璃从半透明降级为不透明的阈值 |

### 行为规则

- 距离 < 阈值：玻璃半透明渲染 + 舱内深度修复（与原行为一致）
- 距离 ≥ 阈值：玻璃保留网格，改用不透明 cutout 材质渲染（新增 `cubeCutout`/`polyMeshCutout` 渲染类型），不再做透明排序与 blend

---

## 通用说明

- 以上所有优化**纯客户端**，不修改实体状态、不发数据包，服务端无感知；多人服中每台客户端独立生效
- 载具必须在玩家渲染范围内才会渲染并评估优化（超出视距不渲染、不评估）
- 四类机制共用一条资源重载挂钩：游戏启动 / F3+T 重载后自动重建规则，无需重启

## 相关代码

| 文件 | 作用 |
|---|---|
| `RVP_LodModelManager.java` | LOD 规则解析、模型烘焙、贴图注册、选级评估、实例缓存 |
| `VehicleRenderLodMixin.java` | LOD 渲染重定向：替换模型实例 / 贴图 / 跳过动画 |
| `RVP_LodModel.java` | LOD 规则数据类与 Pojo 解析 |
| `RVP_StateBoneHider.java` | 状态机骨骼隐藏规则与 apply |
| `RVP_DistanceBoneHider.java` | 距离骨骼隐藏规则与 apply（含特殊骨骼隐藏索引） |
| `VehicleRenderStateHiddenBoneMixin.java` | 骨骼隐藏渲染注入入口 |
| `VehicleBedrockModelCockpitRenderMixin.java` | 特殊骨骼渲染循环：距离隐藏跳过 + 半透明换不透明 |
| `RVP_RenderTypes.java` | RVP 自定义渲染类型（含 cutout / cockpit 深度修复） |
| `RVP_BaseDisplayPojo.java` 及子类 | display JSON 扩展字段声明 |
| `ClientAssetsManagerCustomMountMixin.java` / `MinecraftReloadResourcePacksMixin.java` | 资源重载后触发规则重建 |
