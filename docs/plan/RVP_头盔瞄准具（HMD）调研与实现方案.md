# 头盔瞄准具（HMD）实现方案 v2

## 设计概要

HMD 是一种**操作辅助模式**，不改变雷达自身工作方式。按 "5" 键切换，在雷达扫描模式和 HMD 模式之间切换。

- **普通雷达扫描模式**：默认，雷达按配置正常扫描，玩家用 `[ ]` 键切目标
- **HMD 格斗模式**：雷达工作不受影响，但目标选择变为**头部朝向 5° 锥体自动锁定**

## 一、模式切换与状态管理

### 1.1 按键绑定

在 `RVP_Keys.java` 新增：

```java
/** HMD 格斗模式切换（5 键） */
public static final KeyMapping HMD_TOGGLE = new KeyMapping(
        "key.ywzj_rvp.hmd_toggle.desc",
        KeyConflictContext.IN_GAME,
        KeyModifier.NONE,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_5,
        "key.category.ywzj_rvp"
);
```

### 1.2 状态管理（`RVP_ClientHmdState.java`）

| 字段 | 类型 | 默认值 | 说明 |
|:--|:--|:--|:--|
| `hmdMode` | boolean | false | HMD 模式是否激活 |
| `hmdCooldownTick` | int | 0 | 模式切换冷却（防误触） |
| `lockedEntityId` | int | -1 | HMD 自动锁定的实体 ID |
| `scanCounter` | int | 0 | 扫描间隔计数器 |

**流程**：

```
按 "5" → hmdMode = true
  ├── 扫描周期从正常 tick 变为每 5 tick
  ├── 扫描锥体从雷达 FOV（60-120°）变为固定 5°
  ├── 扫描中心从雷达物理朝向变为玩家头部朝向
  ├── HMD 准心框渲染到屏幕中央
  └── 离轴：头部朝向不能超出雷达物理扫描范围

找到目标 → WeaponUnit.setLockedEntity() → hmdMode = false
  └── 退出到普通雷达模式（保留已锁定的 STT 跟踪）

按 "5" 再按 → hmdMode = false
  └── 直接退出 HMD，雷达恢复正常
```

## 二、动画设计

### 2.1 HMD 屏幕 Overlay 动画（主视野叠加层）

当 `hmdMode = true` 时，在屏幕中央绘制一个**闪烁的绿色方形框**，代表 5° FOV 范围。

**方框动画**：

| 元素 | 行为 | 周期 |
|:--|:--|:--|
| 边框 | 绿色 1px 线框，透明度闪烁（颜色同雷达 UI 绿色） | 3 tick 亮 / 3 tick 半透明 |
| 角标 | 四个角各有一个小短角（十字准心风格） | 始终显示，透明度同边框 |
| 横向扫描线 | 方框内从左到右移动的横线 | 每 6 tick 扫完一次 |
| 目标标记 | 若已有锁定目标，在目标屏幕位置画菱形指示符 | 按 `worldToScreen` 更新位置 |

**效果示意**（文本模拟）：

```
  ┌─────────────┐
  │   ██        │    ← 扫描线（从左向右移动）
  │             │
  │      ◆      │    ← 锁定目标标记（菱形，目标在准心附近时显示）
  │             │
  │             │
  └─────────────┘
  [闪烁绿色方框，代表 5° FOV]
```

**计算方法**（屏幕像素大小）：

```java
int cx = screenWidth / 2;
int cy = screenHeight / 2;
double fov = mc.options.fov().get();
int boxHalf = (int)(Math.tan(Math.toRadians(2.5)) / Math.tan(Math.toRadians(fov / 2)) * cx);
```

### 2.2 雷达小地图 Overlay 动画（雷达屏幕叠加）

在 `VehicleRadarOverlay` 的雷达小地图上，当 `hmdMode = true` 时叠加一个 **5° 扇形**表示当前 HMD 扫描范围（雷达 UI 同款绿色半透明填充 + 轮廓）。

**实现方式**：在 `VehicleRadarOverlayMixin` 的 `render` 方法中追加：

```java
@Inject(method = "render", at = @At("TAIL"))
private void rvp$renderHmdIndicator(..., CallbackInfo ci) {
    if (!RVP_ClientHmdState.getInstance().isHmdMode()) return;
    // 在雷达小地图上画 5° 扇形（绿色半透明，同雷达 UI 颜色）
    // 中心方向 = 玩家头部朝向转换到雷达投影坐标系
    // 半径 = 显示距离刻度 × （HMD范围 / 雷达全范围）
    drawHmdSector(guiGraphics, centerBearing, centerElevation, 5f);
}
```

**效果**：雷达小地图上出现一个绿色扇形扫过目标，表示 HMD 当前正在看的方向。

### 2.3 模式切换过渡动画

当按 "5" 切换 HMD 模式时，用一个**短闪烁过渡**：

| 阶段 | tick 0-2 | tick 3-5 | tick 6+ |
|:--|:--|:--|:--|
| **切入 HMD** | 屏幕打标 "HMD ON" 渐显 | "HMD ON" 闪烁后消失 | 方框出现 |
| **退出 HMD** | 屏幕打标 "HMD OFF" 渐显 | "HMD OFF" 闪烁后消失 | 恢复普通模式 |

文字提示复用现有的 `player.displayClientMessage()`（绿色文字），不是 overlay 渲染。

### 2.4 离轴限制警告动画

当玩家头部转向超出雷达物理扫描范围时，HMD 方框**变红闪烁 1 秒后自动退出**。

| 阶段 | tick 0-5 | tick 6-10 | tick 11 |
|:--|:--|:--|:--|
| 方框颜色 | 红色闪烁 | 红色闪烁（加快） | 方框消失，HMD 退出 |

---

## 三、实现架构

### 3.1 新增文件

| 文件 | 作用 |
|:--|:--|
| `client/state/RVP_ClientHmdState.java` | HMD 状态管理（isHmdMode、扫描逻辑、自动锁定） |
| `client/gui/RVP_HmdOverlay.java` | 屏幕方框 + 扫描线 + 目标标记渲染 |
| `client/gui/RVP_HmdRadarOverlay.java` | 雷达小地图上的 5° 扇形 HMD 指示器 |

### 3.2 改动文件

| 文件 | 改动内容 |
|:--|:--|
| `RVP_Keys.java` | 新增 `HMD_TOGGLE` 键映射（5 键） |
| `RVP_ClientEvents.java` | `onClientTick` 中新增 `RVP_ClientHmdState.tick()` 调用；按键响应 |
| `VehicleRadarOverlayMixin.java` | 注入 `rvp$renderHmdIndicator()` 追加 HMD 扇形渲染 |

### 3.3 不修改文件

| 文件 | 理由 |
|:--|:--|
| `RadarUnit.java` | 本体代码，不侵入 |
| `WeaponUnit.java` | 锁定通过 `setLockedEntity()` 即可 |
| `WeaponUnitTickFireControlMixin.java` | HMD 锁定独立，不经过 tickFireControl |
| `RVP_ClientArmState.java` | HMD 独立，不耦合 ARM 逻辑 |

---

## 四、HMD 自定义扫描逻辑（`RVP_ClientHmdState.tick()`）

```java
public void tick() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !(mc.player.getVehicle() instanceof AbstractVehicle vehicle)) {
        if (hmdMode) hmdMode = false;
        return;
    }
    if (!hmdMode) return;

    // 1. 每 5 tick 扫描一次
    if (++scanCounter < 5) return;
    scanCounter = 0;

    WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
    if (weaponUnit == null) { hmdMode = false; return; }
    RadarUnit radar = weaponUnit.getMainRadarUnit();
    if (radar == null) { hmdMode = false; return; }

    // 2. 头部朝向
    Vec3 headLook = mc.player.getLookAngle();
    Vec3 headPos = mc.player.getEyePosition();
    float maxRange = radar.getMaxScanDistance() * 0.5f;

    // 3. 离轴限制
    if (!isWithinRadarLimits(radar, headLook)) {
        // 超出范围：方框变红，1 秒后自动退出
        if (++outOfBoundsTicks > 20) { hmdMode = false; outOfBoundsTicks = 0; }
        return;
    }
    outOfBoundsTicks = 0;

    // 4. 从雷达检测列表中过滤
    Entity bestTarget = null;
    double bestScore = Double.MAX_VALUE;
    for (RadarUnit.DetectedObject obj : radar.getDetectedEntities().values()) {
        Entity entity = obj.entity();
        if (entity == null || !entity.isAlive()) continue;
        Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(headPos);
        double dist = toTarget.length();
        if (dist > maxRange || dist < 1) continue;
        double angle = Math.toDegrees(Math.acos(headLook.dot(toTarget.normalize())));
        if (angle > 5f) continue;
        double score = angle * 0.7 + dist * 0.0003;
        if (score < bestScore) { bestScore = score; bestTarget = entity; }
    }

    // 5. 自动锁定
    if (bestTarget != null) {
        weaponUnit.setLockedEntity(bestTarget);
        lockedEntityId = bestTarget.getId();
        hmdMode = false;  // 退出 HMD，不退普通雷达的 STT 锁定
        player.displayClientMessage(
                Component.translatable("message.ywzj_rvp.hmd.locked"), true);
    }
}
```

---

## 五、开发阶段

| 阶段 | 内容 | 预估文件数 |
|:--|:--:|:--|
| 1 | `RVP_Keys.java` 新增 HMD_TOGGLE 键（5 键） | 1 |
| 2 | `RVP_ClientHmdState.java` 状态 + 扫描逻辑 | 1 新增 |
| 3 | `RVP_HmdOverlay.java` 屏幕方框 + 扫描线 + 目标标记 | 1 新增 |
| 4 | `RVP_ClientEvents.java` 注册 tick + 按键响应 | 1 修改 |
| 5 | `VehicleRadarOverlayMixin.java` 雷达小地图 HMD 扇形 | 1 修改 |
| 6 | 离轴警告动画 + 模式切换过渡动画 | 在 state + overlay 中 |
