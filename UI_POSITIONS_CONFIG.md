# UI 组件位置配文件化 — 设计文档与实施计划

## 1. 概述

### 目标
将 RWR、搜索雷达/跟踪雷达扇区、载具骨骼俯视图 3 个 UI 组件的屏幕位置从硬编码改为配置文件驱动，允许用户/整合包作者自由拖动。

| 组件 | 源码位置 | 当前硬编码位置 |
|------|---------|---------------|
| 搜索雷达扇区 | `VehicleRadarOverlay.java` | `(screenWidth/2 + 128, screenHeight - 80)` |
| RWR 文字 | `VehicleRadarOverlay.java` | 圆心上方 `(0, -100)` 相对中心，再相对雷达圆心 |
| 载具骨骼俯视图 | `VehicleScopeOverlay.java#renderVehicleHeading` | `(screenWidth/2 + 116, screenHeight/2 + 80)` |
| 锁定框&跟踪雷达 | `VehicleScopeOverlay.java` | world-to-screen 投影，无法通过固定偏移调整，**本次不纳入** |

### 设计原则

- **不改动 vehicle 本体**，仅通过 RVPMixin 注入替换硬编码计算
- 配置文件路径 `config/limitless_vehicle/ui_positions.json`
- 可选存在，文件缺失或字段缺失时 fallback 到原硬编码值（零侵入）
- 组件以 `screenWidth/screenHeight` 为分母的**比例值**存储，兼容所有分辨率

---

## 2. 配置文件格式

文件路径：`config/limitless_vehicle/ui_positions.json`

```jsonc
{
  // 搜索雷达扇区圆心位置
  "radar": {
    "anchor": "right",          // 锚点: center | right | right_bottom
    "offset_x": 128,            // 相对锚点的 X 偏移（px）
    "offset_y": -80,            // 相对锚点的 Y 偏移（px）
    "scale": 1.0                // 缩放系数（可选，默认 1.0）
  },
  // RWR 圆心位置（独立于雷达）
  "rwr": {
    "anchor": "right",
    "offset_x": 128,
    "offset_y": -180,
    "scale": 1.0
  },
  // 载具骨骼俯视图位置
  "vehicle_bones": {
    "anchor": "right_bottom",
    "offset_x": 116,
    "offset_y": 80,
    "scale": 1.0
  }
}
```

### 锚点定义

| anchor | 计算公式 |
|--------|----------|
| `center` | `(screenWidth/2 + offsetX, screenHeight/2 + offsetY)` |
| `right` | `(screenWidth/2 + offsetX, screenHeight + offsetY)` screenHeight - 80 → `offsetY = -80`，锚点右边缘 |
| `right_bottom` | `(screenWidth/2 + offsetX, screenHeight + offsetY)` |
| `left` (预留) | `(offsetX, screenHeight/2 + offsetY)` |
| `top_left` (预留) | `(offsetX, offsetY)` |

> 选择 `right` 而非 `right_bottom` 作为雷达锚点是因为原代码 `centerY = screenHeight - 80`，offsetY 为负值时从屏幕底部向上偏移，语义更清晰。

---

## 3. 实施计划

### 3.1 新增文件

#### `org.ywzj.rvp.config.UIPositionsConfig`

职责：
- 读取 `config/limitless_vehicle/ui_positions.json`
- 提供静态 getter 返回各组件位置
- 文件不存在或解析失败时返回 null，调用方 fallback 到硬编码

```java
public class UIPositionsConfig {
    private static UIPositions INSTANCE = null;

    // anchor enum
    public enum Anchor { CENTER, RIGHT, RIGHT_BOTTOM }

    // 组件位置记录
    public record UIPosition(Anchor anchor, int offsetX, int offsetY, float scale) {}

    // 根配置
    public record UIPositions(
        UIPosition radar,
        UIPosition rwr,
        @SerializedName("vehicle_bones") UIPosition vehicleBones
    ) {}

    public static UIPositions get() { return INSTANCE; }

    // 在 AllConfigs.loadExternal() 类似的地方初始化
    public static void load() { ... }
}
```

#### `org.ywzj.rvp.mixin.VehicleRadarOverlayPositionMixin`

目标：`VehicleRadarOverlay.render()`

```java
@Mixin(value = VehicleRadarOverlay.class, remap = false)
public class VehicleRadarOverlayPositionMixin {
    @ModifyVariable(
        method = "render",
        at = @At(value = "STORE", ...),
        index = ...  // centerX 或 centerY 局部变量
    )
    // 或直接 @Redirect 替换 paint 调用中的坐标
}
```

由于 `VehicleRadarOverlay.render()` 中 `centerX`/`centerY` 是局部变量且在后续多处使用（雷达扇区、RWR），最干净的方案是：

> **方案 A：@Inject(HEAD) 提前写入配置值到字段，@ModifyVariable 替换局部变量**

或者在 RVP 中创建一个新的 Overlay 类完全覆盖？不妥，改动太大。

> **方案 B（推荐）：@Inject(HEAD) 注入，用配置值覆盖局部变量**

```java
@Inject(method = "render", at = @At("HEAD"), remap = false)
private void ywzj_rvp$overridePosition(
    ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
    int screenWidth, int screenHeight, CallbackInfo ci
) {
    UIPositionsConfig.UIPosition pos = UIPositionsConfig.getRadar();
    if (pos != null) {
        // 计算新坐标写入 ThreadLocal / 修改 screenWidth/screenHeight? 不行，它们是参数
    }
}
```

方案 B 不行，因为 `screenWidth/screenHeight` 是参数不可修改。

> **方案 C（最终推荐）：@Redirect 替换 `screenWidth / 2 + 128` 计算表达式**

```java
@Mixin(value = VehicleRadarOverlay.class, remap = false)
public class VehicleRadarOverlayPositionMixin {
    // 替换 int centerX = screenWidth / 2 + 128;
    @Redirect(
        method = "render",
        at = @At(value = "FIELD", target = "screenWidth"),
        remap = false
    )
    private int ywzj_rvp$centerX(int screenWidth, int /* screenHeight, partialTick, ... */) {
        UIPosition pos = UIPositionsConfig.getRadar();
        if (pos == null) return screenWidth / 2 + 128;
        return switch (pos.anchor()) {
            case CENTER -> screenWidth / 2 + pos.offsetX();
            case RIGHT -> screenWidth / 2 + pos.offsetX();
            case RIGHT_BOTTOM -> screenWidth / 2 + pos.offsetX();
        };
    }
}
```

但 `@Redirect` 有崩溃历史。改用 `@ModifyVariable`：

```java
@ModifyVariable(
    method = "render",
    at = @At(value = "STORE", ordinal = 0),  // 第一个局部变量赋值
    index = 4,  // centerX 在局部变量表中的位置
    remap = false,
    argsOnly = false
)
private int ywzj_rvp$centerX(int centerX, int screenWidth, int screenHeight, ...) {
    ...
}
```

`@ModifyVariable` 比 `@Redirect` 更安全，不依赖字节码匹配。

### 3.2 配置文件加载时机

在 `AllConfigs.loadExternal()` 之后调用，或通过 Mod Bus `FMLConstructModEvent` 初始化。

RVPRVP 已经有初始化入口：

```java
// 在 RVP 主类中
@Mod.EventBusSubscriber(modid = "ywzj_rvp", bus = Mod.EventBusSubscriber.Bus.MOD)
public static class ModEvents {
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        UIPositionsConfig.load();
    }
}
```

### 3.3 涉及 mixin 与坐标计算公式对照

#### VehicleRadarOverlay

| 原文 | 替换目标 | 组件 |
|------|---------|------|
| `int centerX = screenWidth / 2 + 128;` | 雷达 anchor + offsetX | 雷达扇区 |
| `int centerY = screenHeight - 80;` | 雷达 anchor + offsetY | 雷达扇区 |
| `poseStack.translate(centerX, centerY - 100, 0);` | RWR anchor + offsetY | RWR |

注意：RWR 位置 `(centerX, centerY - 100)` 当前**相对雷达圆心**偏移 -100。配置化为 RWR 独立位置，与雷达解耦。

#### VehicleScopeOverlay#renderVehicleHeading

| 原文 | 替换目标 | 组件 |
|------|---------|------|
| `poseStack.translate(screenWidth / 2f + 116f, screenHeight / 2f + 80f, 0f);` | vehicle_bones anchor + offset | 骨骼俯视图 |

---

## 4. 文件清单

| # | 操作 | 文件 | 说明 |
|---|------|------|------|
| 1 | **新增** | `src/main/java/org/ywzj/rvp/config/UIPositionsConfig.java` | 配置加载与解析 |
| 2 | **新增** | `src/main/java/org/ywzj/rvp/mixin/VehicleRadarOverlayPositionMixin.java` | 替换雷达/RWR 位置 |
| 3 | **新增** | `src/main/java/org/ywzj/rvp/mixin/VehicleScopeOverlayHeadingMixin.java` | 替换骨骼俯视图位置 |
| 4 | **修改** | `src/main/resources/ywzj_rvp.mixins.json` | 注册 2 个新 mixin |
| 5 | **新增** | 配置文件 `config/limitless_vehicle/ui_positions.json`（用户手动创建） | 用户自定义位置 |

---

## 5. 实施步骤

### Step 1: 新建 `UIPositionsConfig`

- Gson 反序列化 `ui_positions.json`
- 自动创建默认配置文件（与原硬编码值一致）
- `load()` 方法在 Mod 初始化时调用

### Step 2: 新建 `VehicleRadarOverlayPositionMixin`

- `@ModifyVariable` 替换 `centerX`、`centerY` 局部变量
- 分离 RWR 位置（不再相对雷达圆心）
- fallback 到硬编码

### Step 3: 新建 `VehicleScopeOverlayHeadingMixin`

- `@ModifyVariable` 或 `@Inject(HEAD)` 替换 `translate` 坐标

### Step 4: 注册 mixin

- 添加到 `ywzj_rvp.mixins.json` 的 `"client"` 列表

### Step 5: 编译测试

- `gradlew build`
- 创建配置文件验证覆盖

---

## 6. 注意事项

- **`@ModifyVariable` 的 index 值需要确认**：需要反编译 `VehicleRadarOverlay.render()` 的字节码确认局部变量表。如果 mixin 编译时 index 不对，可能会注入到错误变量或抛出 `IndexOutOfBoundsException`。**实施时先反编译确认。**
- RWR 当前使用 `centerX, centerY - 100`（相对雷达圆心）。配置化后 RWR 独立为一个位置，与原位置解耦。
- 缩放 `scale` 字段预留但不一定需要立即实现——`vehicle_bones` 的缩放可以通过 `poseStack.scale()` 实现，雷达扇区缩放则需要调整半径计算，较为复杂，可后续迭代。
