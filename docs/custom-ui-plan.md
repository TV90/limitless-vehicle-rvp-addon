# 自定义 UI 系统 - 设计与实施计划

## 一、目标

用渲染层复制+替换的方式，替代原版载具 UI 的固定布局，使**所有 HUD 组件的位置和大小可通过 JSON 配置**，同时保持渲染逻辑、颜色、动画完全不变。

| 目标 | 描述 |
|------|------|
| **可定位** | 雷达、RWR、准心、射界、武器卡片、骨骼俯视图等每个组件独立配置位置 |
| **可缩放** | 每个组件独立配置 scale |
| **零黑盒依赖** | 不依赖 Mixin 修改本体渲染参数，全部用复制代码 + 独立注册 |
| **可开关** | `show_skeleton: false` 同时控制骨骼/雷达/RWR 隐藏 |
| **向后兼容** | 不配置 `ui_preset` 的载具不受影响 |
| **低维护** | 复制后的代码与原版差异极小，升级本体时容易 diff |

---

## 二、架构

```
┌─────────────────────────────────────────────────────────────┐
│                     mods/ywzj_rvp.jar                        │
│                                                              │
│  config/limitless_vehicle/ui_presets/                        │
│   ├── default.json               ← 全局默认布局               │
│   ├── ps1sm.json                 ← 铠甲专用布局               │
│   └── (任意名称).json            ← 其他预设                   │
│                                                              │
│  mixins (仅用于隐藏原版 UI，不再用于定位):                      │
│   ├── VehicleRadarOverlayVisibilityMixin   ← cancel 原版雷达   │
│   └── VehicleScopeOverlayHeadingMixin      ← cancel 原版骨骼   │
│                                                              │
│  RVP 自渲染 Overlays (独立注册, 与本体并行):                    │
│   ├── RVP_RadarOverlay.java         ← 复制 VehicleRadarOverlay │
│   ├── RVP_RWROverlay.java           ← RWR 独立组件            │
│   ├── RVP_ScopeOverlay.java         ← 复制 VehicleScopeOverlay │
│   └── RVP_HudOverlay.java           ← 复制 VehicleOverlay 部分 │
│                                                              │
│  RVP_OverlayRegistry.java           ← 注册所有 RVP overlay    │
│  UIPresetManager.java               ← 预设加载与管理           │
│  UIPresetConfig.java                ← 配置校验与回退           │
└─────────────────────────────────────────────────────────────┘
```

### 布局覆盖关系

```
原版 (ywzj_vehicle)              RVP 替代 (ywzj_rvp)
────────────────────────────     ────────────────────────────
VehicleRadarOverlay        →     RVP_RadarOverlay (定位+缩放)
  ├─ 雷达扇区               →       ├─ 雷达扇区 (可定位)
  └─ RWR 圆圈              →       └─ [可选拆为 RVP_RWROverlay]
VehicleScopeOverlay        →     RVP_ScopeOverlay (定位+缩放)
  ├─ 准心                  →
  ├─ 射界                  →     (全部保留, 只改 translate)
  ├─ 目标框                →
  └─ 骨骼俯视图            →     去掉(由 show_skeleton 控制)
VehicleOverlay (部分)      →     RVP_HudOverlay (罗盘+乘员)
VehicleWeaponOverlay       →     [暂不复制, 原版位置已够用]
```

---

## 三、预设配置格式

### `config/limitless_vehicle/ui_presets/ps1sm.json`

```json
{
  "name": "ps1sm",
  "radar": {
    "anchor": "right",
    "offset_x": 128,
    "offset_y": -80,
    "scale": 1.0
  },
  "rwr": {
    "anchor": "right",
    "offset_x": 128,
    "offset_y": -280,
    "scale": 1.3
  },
  "scope_crosshair": {
    "anchor": "center",
    "offset_x": 0,
    "offset_y": 0,
    "scale": 1.0
  },
  "scope_envelope": {
    "anchor": "center",
    "offset_x": 0,
    "offset_y": 0,
    "scale": 1.0
  },
  "vehicle_bones": {
    "anchor": "right_bottom",
    "offset_x": 116,
    "offset_y": 80,
    "scale": 1.0
  },
  "compass": {
    "anchor": "center",
    "offset_x": 0,
    "offset_y": -100,
    "scale": 1.0
  }
}
```

| 字段 | 对应组件 | 回退行为 |
|------|---------|---------|
| `radar` | 雷达扇区 + 扫描线 + 目标点 | 原版硬编码位置 |
| `rwr` | RWR 告警圆环 + 威胁指示 | 原版硬编码位置 |
| `scope_crosshair` | 观瞄准心 | 屏幕中心 |
| `scope_envelope` | 射界扇形 | 屏幕中心 |
| `vehicle_bones` | 骨骼俯视图 | 原版硬编码位置 |
| `compass` | 方位罗盘 | 原版硬编码位置 |

### 锚点系统

| Anchor | 基准点 | X 公式 | Y 公式 |
|--------|--------|--------|--------|
| `center` | 屏幕中心 | `screenWidth/2 + offsetX` | `screenHeight/2 + offsetY` |
| `right` | 屏幕右边缘 | `screenWidth/2 + offsetX` | `screenHeight + offsetY` |
| `right_bottom` | 右下角 | `screenWidth/2 + offsetX` | `screenHeight + offsetY` |

---

## 四、实施步骤

### Phase 1: 基础设施（已完成 ✓）

| 步骤 | 文件 | 状态 |
|------|------|------|
| `UIPresetManager` 预设加载系统 | `config/UIPresetManager.java` | ✅ |
| `VehicleUIPresetCache` 车辆预设映射 | `config/VehicleUIPresetCache.java` | ✅ |
| `VehicleDataManagerMixin` 从 JSON 读取配置 | `mixin/VehicleDataManagerMixin.java` | ✅ |
| `show_skeleton` 开关（骨骼+雷达+RWR） | 已在 ps1sm.json 中配置 | ✅ |

### Phase 2: 隐藏原版 UI

| 步骤 | 文件 | 难度 | 行数 |
|------|------|------|------|
| `VehicleRadarOverlayVisibilityMixin` — cancel 雷达+RWR | 新建 | ★☆☆ | ~30 行 |
| `VehicleScopeOverlayHeadingMixin` — cancel 骨骼 | 已有 | ★☆☆ | ~10 行 |
| 注册到 `mixins.json` | 配置 | ★☆☆ | ~1 行 |

> **注意**：cancel 类 mixin 注入 `render()` HEAD，只调 `ci.cancel()`，不碰任何渲染逻辑。Connector 兼容性最好。

### Phase 3: 复制 + 改造核心 Overlay

#### 3a. `RVP_RadarOverlay.java`（最高优先级）

- **源文件**：`VehicleRadarOverlay.java`（约 290 行）
- **修改点**：
  ```
  - int centerX = screenWidth / 2 + 128;
  - int centerY = screenHeight - 80;
  + UIPosition pos = UIPresetManager.getRadar(presetName);
  + int centerX = pos != null ? pos.computeX(screenWidth) : screenWidth/2 + 128;
  + int centerY = pos != null ? pos.computeY(screenHeight) : screenHeight - 80;
  ```
- **额外修改**：
  - 雷达半径 `50.0f` → 乘以 `pos.scale`
  - 多雷达间距 `- (radarUnits.size() - 1) * 32` → 乘以 `pos.scale`
- **RWR 部分**：保留在同一个 overlay 内，位置单独读 `rwr` 字段
- **RWR 缩放**：`poseStack.scale(rwrPos.scale, rwrPos.scale, rwrPos.scale)`

#### 3b. `RVP_ScopeOverlay.java`

- **源文件**：`VehicleScopeOverlay.java`（约 374 行）
- **修改点**：
  - 准心位置 → `scope_crosshair` 预设
  - 射界位置 → `scope_envelope` 预设
  - **删除**整个 `renderVehicleHeading()` 调用（骨骼已由 `show_skeleton` 控制）
- **保留**：准心形状、射界算法、目标框、速度线等完全不变

#### 3c. `RVP_HudOverlay.java`（可选）

- **源文件**：`VehicleOverlay.java` 中的罗盘/乘员部分（约 420 行中的 ~150 行）
- **修改点**：
  - 罗盘 Y 位置 → `compass` 预设

### Phase 4: 注册新 Overlay

在 `RVP_OverlayRegistry.java` 中：

```java
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RVP_OverlayRegistry {
    @SubscribeEvent
    public static void onRegisterHud(RegisterGuiOverlaysEvent event) {
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_radar", new RVP_RadarOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_scope", new RVP_ScopeOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_hud", new RVP_HudOverlay());
    }
}
```

注册顺序要与原版一致（在原版 cancel 之后渲染），不干扰其他 mod。

### Phase 5: 清理旧 Mixin

| 旧 Mixin | 状态 |
|----------|------|
| `VehicleRadarOverlayMixin.java`（HMD 扇区定位） | 保留（与自定义 UI 无冲突） |
| `VehicleRadarOverlayPositionMixin.java`（translate 替换） | **删除**（已被 RVP_RadarOverlay 替代） |
| `VehicleScopeOverlayHeadingMixin.java`（骨骼位置 + cancel） | 保留（只保留 cancel） |

---

## 五、优先级

```
Phase 3a (RVP_RadarOverlay) ─── 最高
  └── 雷达+RWR 位置最不可调，痛点最大

Phase 3b (RVP_ScopeOverlay) ─── 高
  └── 准心和射界位置对观瞄体验影响大

Phase 3c (RVP_HudOverlay) ──── 中
  └── 罗盘位置可调，但原版位置相对合理

Phase 2 (隐藏原版) ──────────── 与 Phase 3 并行
  └── cancel mixin 简单安全，可逐个添加
```

---

## 六、复制代码的原则

1. **最小差异原则**：复制的代码与原版只差 `import` 和 `centerX/centerY/scale` 计算行，其他一字不改
2. **不优化不重构**：原版代码怎么写就怎么抄，即使有冗余逻辑也保留
3. **升级友好**：升级本体时，只需 diff 原版文件的变化，同步到 RVP 副本
4. **不引入新依赖**：RVP 副本用的都是本体已有的 `Color`、`GuiHelper`、`RenderHelper` 工具类

---

## 七、风险与回退

| 风险 | 缓解 |
|------|------|
| 原版代码升级导致 RVP 副本过期 | 差异小，diff 容易；用代码注释 `// [RVP]` 标记修改处 |
| Cancel mixin 在 Connector 崩溃 | 所有 cancel mixin 使用 `require = 0`，崩溃不阻塞启动 |
| 自定义 UI 渲染异常 | 删掉 RVP overlay 注册 + 关掉 cancel mixin 即可立即回退到原版 |
