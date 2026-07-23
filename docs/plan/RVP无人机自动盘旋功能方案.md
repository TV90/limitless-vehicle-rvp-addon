# RVP 无人机自动盘旋功能方案

## 一、功能概述

为 RVP 的可部署侦察无人机增加自动盘旋能力。核心场景：

1. 玩家驾驶多管火箭炮系统，释放侦察无人机前出侦察
2. 侦察无人机配备目标指示吊舱，标记敌方载具和轰炸点位并回传
3. 玩家从无人机切换回火箭炮母车时，无人机**自动进入盘旋**，防止飞远
4. 也可通过按键 / 战术地图手动切换盘旋状态
5. 盘旋圆心可通过战术地图标记，未标记时以母车位置为圆心

## 二、需求分析

| 需求 | 说明 |
|------|------|
| 自动盘旋 | 无人机围绕圆心做圆周飞行，保持高度 |
| 切回母车自动激活 | `switchBackToParent()` 成功后自动开启盘旋 |
| 按键手动切换 | 驾驶无人机或母车时按按键开关盘旋 |
| 战术地图标记圆心 | 在战术地图右键选点设为盘旋圆心 |
| 圆心策略 | 有标记点用标记点，无标记点跟随母车 |
| 无 Mixin | 遵循 rvp-avoid-mixin 原则，不新增 Mixin |

## 三、SBW 参考实现

SBW (SuperbWarfare) 的盘旋逻辑核心在 `VehicleEngineUtils.kt` 的 `aircraftLoiter()` 方法。

### 3.1 参数存储

- `loiterParams`: `Quaternionf(centerX, altitude, centerZ, radius)`，用 `EntityDataAccessor` 同步
- `loiterActive`: `boolean`，盘旋开关

### 3.2 触发条件

```
空中 + 引擎启动 + energy > 1024 + 未坠毁 + 有乘客 + AIRCRAFT 类型 + loiterActive
```

### 3.3 控制方式

SBW **不直接设 motion**，而是操纵与玩家输入相同的变量，复用现有气动物理模型：

- `mouseMoveSpeedX` → 偏航控制
- `deltaRot` → 直接偏航旋转
- `xRot` → 俯仰
- `mouseMoveSpeedY` → 俯仰输入
- `power` → 油门

### 3.4 制导算法（两阶段航向）

```
航向 A：切线航向（盘旋阶段）
  tangentYaw = atan2(-dz, -dx)   // 左舷朝向圆心的切线方向

航向 B：径向航向（拦截阶段）
  toCenterYaw = atan2(dx, -dz)   // 指向圆心
  圈内时反转 180° 指向圈外

混合权重：
  blend = clamp(1 - distFromOrbit / radius, 0, 0.8)
  blendFactor = clamp((distFromOrbit - 20) / 20, 0, 1)
  effectiveRadial = (1 - blend) * blendFactor
  yawError = errorTangent * (1 - effectiveRadial) + radialYaw * effectiveRadial

径向位置精修：
  yawError += clamp(radialError * 0.12, -35, 35)
```

### 3.5 高度与油门

```
高度控制（PID 式）：
  targetPitch = clamp(altError * -0.15, -10, 10)
  xRot = lerp(0.01, xRot, targetPitch)

油门控制（高度自适应防失速）：
  低于目标高度 → power = clamp(0.9 + altError * 0.002, 0.9, 2.0)
  高于目标高度 → power = clamp(0.9 + altError * 0.0005, 0.5, 0.9)
```

### 3.6 障碍规避

SBW 有完整的扇形射线扫描 + 紧急爬升系统（360 格 9 射线），RVP 首期可不实现。

## 四、RVP 现有架构分析

### 4.1 无人机实体体系

- 无人机是 `AbstractVehicle` 子类，通过 `AbstractVehicleLinkedUavMixin` 附加 `AbstractVehicleLinkedUavExt` 接口
- `AbstractVehicleLinkedUavExt` 提供：
  - `ywzj_rvp$getLinkedParentVehicleUuid()` → 母车 UUID
  - `ywzj_rvp$isDeployableUavInstance()` → 是否为可部署无人机实例
  - `ywzj_rvp$isDeployableUavControlSwitchAllowed()` → 是否允许切换控制

### 4.2 母车 ↔ 无人机切换

| 方法 | 方向 | 文件 |
|------|------|------|
| `RVP_DeployableUavService.switchToLinkedUav()` | 母车 → 无人机 | `uav/RVP_DeployableUavService.java` |
| `RVP_DeployableUavService.switchBackToParent()` | 无人机 → 母车 | `uav/RVP_DeployableUavService.java` |

切换网络包：`C2SSwitchDeployableUav`

### 4.3 ControlUnit 控制模型

`AbstractVehicle.controlUnit` 是 **public final** 字段，包含以下公开可变字段：

| 字段 | 类型 | 作用 |
|------|------|------|
| `forward` | boolean | 前进 / 油门增加 |
| `backward` | boolean | 后退 / 油门减少 |
| `left` | boolean | 左滚转 |
| `right` | boolean | 右滚转 |
| `up` | boolean | 上升 / 俯仰抬头 |
| `down` | boolean | 下降 / 俯仰低头 |
| `leftYaw` | boolean | 左偏航 |
| `rightYaw` | boolean | 右偏航 |
| `xRot` | float | 俯仰角（目标值） |
| `yRot` | float | 偏航角（目标值） |
| `xRotKeep` | boolean | 保持当前俯仰 |
| `yRotKeep` | boolean | 保持当前偏航 |

客户端通过 `ClientVehicleMoveControl` 包将这些值发送到服务端，服务端 `ControlUnit` 存储后由物理引擎消费。

## 五、可行性调研结论

### 5.1 ControlUnit 在无驾驶员时仍被处理

**旋翼机 `RotaryWingVehicle`：**

- `tickInput()`（L150-L170）读取 `controlUnit.left/right/forward/backward`，**无 driver 检查**
- `tickMove()`（L184-L231）读取 `controlUnit.up/down`，**无 driver 检查**
- yaw 通过 `controlUnit.yRot` 差值平滑追踪（L237），**无 driver 检查**
- `xRot/yRot` 不会因无驾驶员被重置（仅在 `hoverMode` 时重置）

**固定翼 `FixedWingVehicle`：**

- `travel()` 读取 `controlUnit.forward/backward/up/down/leftYaw/rightYaw`，**无 driver 检查**
- `getDriver() == null` 时仅重置 `xRot = 0; yRot = getYRot()`（L317-L319），不影响 forward/up/down/leftYaw 等

### 5.2 controlUnit.reset() 不会自动调用

- `reset()` 仅在 `WheeledVehicle` 和 `TrackedVehicle`（地面载具）中调用
- `FixedWingVehicle` 和 `RotaryWingVehicle` **从不调用 reset()**
- 驾驶员离开后，controlUnit 字段**保留上次值**，不会被清零

### 5.3 旋翼机可用 analog 控制

- 无 `forward/backward` 时，pitchInput 通过 `controlUnit.xRot` 差值计算（L167）：
  `setPitchInput(Mth.clamp((getXRot() - controlUnit.xRot) / 30, -1f, 1f))`
- yaw 通过 `controlUnit.yRot` 差值平滑追踪（L237）

### 5.4 结论

**直接写 `vehicle.controlUnit` 字段即可驱动无人机飞行，无需 Mixin、无需 FakePlayer、无需直接操纵 motion。**

## 六、设计方案

### 6.1 架构总览

```
┌─ JSON 配置 (RVP_DeployableUavConfig)
│   loiterRadius, loiterAltitudeOffset, autoLoiterOnSwitchBack
│
├─ 状态管理 (RVP_UavLoiterManager) [非 Mixin, 按 UUID 索引]
│   active, centerX/Y/Z, radius, altitude
│   ← 战术地图标记 / switchBackToParent 写入
│
├─ 制导计算 (RVP_UavLoiterGuidance) [非 Mixin 工具类]
│   两阶段航向算法 → 输出 controlUnit 字段值
│
├─ 服务端 Tick 处理器 (RVP_UavLoiterTickService)
│   @SubscribeEvent ServerTickEvent
│   遍历 active UAV → 写入 vehicle.controlUnit
│
├─ 触发入口
│   ├─ switchBackToParent() → 自动激活
│   ├─ C2SToggleUavLoiter → 按键手动切换
│   └─ 战术地图右键菜单 → 标记圆心
│
└─ 客户端状态 (RVP_ClientUavLoiterState)
    HUD 指示器、盘旋圆显示
```

### 6.2 核心类设计

#### RVP_UavLoiterManager（`org.ywzj.rvp.uav` 包）

按 UAV 实体 UUID 存储盘旋状态。非 Mixin，纯静态管理器。

```java
public final class RVP_UavLoiterManager {
    private static final Map<UUID, LoiterState> STATES = new ConcurrentHashMap<>();

    public record LoiterState(
        boolean active,
        double centerX, double centerY, double centerZ,
        double radius, double altitude,
        UUID followParentUuid,  // 非空表示跟随母车，每 tick 更新 center
        boolean markedCenter     // true=战术地图标记的固定点
    ) {}

    // 激活盘旋（跟随母车模式）
    public static void enableFollowParent(UUID uavUuid, UUID parentUuid,
                                          double radius, double altitudeOffset);

    // 激活盘旋（固定圆心模式）
    public static void enableMarkedCenter(UUID uavUuid, Vec3 center,
                                          double radius, double altitude);

    // 更新圆心（战术地图重新标记时）
    public static void updateCenter(UUID uavUuid, Vec3 newCenter);

    // 关闭盘旋
    public static void disable(UUID uavUuid);

    // 获取状态
    public static LoiterState get(UUID uavUuid);

    // 获取所有 active 状态（tick 遍历用）
    public static Map<UUID, LoiterState> getAll();

    // 清除无效条目（UAV 被移除时）
    public static void remove(UUID uavUuid);
}
```

#### RVP_UavLoiterGuidance（`org.ywzj.rvp.uav` 包）

制导算法核心，参考 SBW 两阶段航向，适配 ywzj_vehicle 的 ControlUnit 模型。

```java
public final class RVP_UavLoiterGuidance {

    /**
     * 制导输出。描述要写入 ControlUnit 的字段值。
     * 旋翼机用 targetYRot 做平滑 yaw 追踪；固定翼用 leftYaw/rightYaw 做离散控制。
     */
    public record GuidanceOutput(
        boolean forward,       // 前倾推进 / 油门
        boolean up,            // 上升
        boolean down,          // 下降
        boolean leftYaw,       // 左偏航（固定翼用）
        boolean rightYaw,      // 右偏航（固定翼用）
        float targetYRot,      // 目标偏航角（旋翼机用，analog）
        boolean useAnalogYaw   // true=旋翼机模式，false=固定翼模式
    ) {}

    /**
     * 计算制导输出。
     * @param uavX, uavY, uavZ  无人机当前位置
     * @param uavYaw            无人机当前偏航角
     * @param centerX, centerY, centerZ  盘旋圆心
     * @param radius            盘旋半径
     * @param altitude          目标高度
     * @param isRotaryWing      是否为旋翼机
     */
    public static GuidanceOutput compute(
        double uavX, double uavY, double uavZ, float uavYaw,
        double centerX, double centerY, double centerZ,
        double radius, double altitude, boolean isRotaryWing
    ) {
        // 1. 计算径向误差
        double dx = uavX - centerX;
        double dz = uavZ - centerZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double radialError = horizontalDist - radius;

        // 2. 切线航向（盘旋阶段）：左舷朝向圆心
        double tangentYaw = Math.toDegrees(Math.atan2(-dz, -dx));
        float errorTangent = Mth.wrapDegrees((float)(tangentYaw - uavYaw));

        // 3. 径向航向（拦截阶段）
        double toCenterYaw = Math.toDegrees(Math.atan2(dx, -dz));
        float errorToCenter = Mth.wrapDegrees((float)(toCenterYaw - uavYaw));
        float errorOutward = Mth.wrapDegrees((float)(toCenterYaw + 180.0 - uavYaw));
        float radialYaw = radialError < 0 ? errorOutward : errorToCenter;

        // 4. 混合权重：远→径向，近→切线
        double distFromOrbit = Math.abs(radialError);
        double blend = Mth.clamp((float)(1.0 - distFromOrbit / radius), 0f, 0.8f);
        double blendZone = 20.0;
        double blendFactor = Mth.clamp((float)((distFromOrbit - blendZone) / blendZone), 0f, 1f);
        double effectiveRadial = (1.0 - blend) * blendFactor;
        float yawError = (float)(errorTangent * (1.0 - effectiveRadial)
                                  + radialYaw * effectiveRadial);

        // 5. 径向位置精修
        yawError += Mth.clamp((float)radialError * 0.12f, -35f, 35f);

        // 6. 高度控制
        double altError = altitude - uavY;
        boolean up = altError > 2;
        boolean down = altError < -2;

        // 7. 前倾推进（距离轨道远时增加推进力）
        boolean forward = radialError > 5 || horizontalDist < radius * 0.5;

        // 8. 输出
        if (isRotaryWing) {
            // 旋翼机：用 targetYRot 平滑追踪
            float targetYRot = Mth.wrapDegrees(uavYaw + yawError);
            return new GuidanceOutput(forward, up, down, false, false, targetYRot, true);
        } else {
            // 固定翼：用 leftYaw/rightYaw 离散控制
            boolean leftYaw = yawError > 3;
            boolean rightYaw = yawError < -3;
            return new GuidanceOutput(forward, up, down, leftYaw, rightYaw, 0f, false);
        }
    }
}
```

#### RVP_UavLoiterTickService（`org.ywzj.rvp.uav` 包）

服务端 tick 处理器，遍历 active UAV 写入 ControlUnit。

```java
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_UavLoiterTickService {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();

        for (var entry : RVP_UavLoiterManager.getAll().entrySet()) {
            UUID uavUuid = entry.getKey();
            LoiterState state = entry.getValue();

            // 1. 查 UAV 实体
            AbstractVehicle uav = resolveUav(server, uavUuid);
            if (uav == null || !uav.isAlive() || uav.isRemoved()) {
                RVP_UavLoiterManager.remove(uavUuid);
                continue;
            }

            // 2. 有真实玩家驾驶时不干预（玩家手动控制优先）
            if (uav.getDriver() instanceof ServerPlayer) {
                continue;
            }

            // 3. 跟随母车模式：更新圆心
            Vec3 center = new Vec3(state.centerX(), state.centerY(), state.centerZ());
            if (state.followParentUuid() != null) {
                AbstractVehicle parent = resolveUav(server, state.followParentUuid());
                if (parent != null && parent.isAlive()) {
                    center = parent.position();
                }
            }

            // 4. 计算制导
            boolean isRotaryWing = uav instanceof RotaryWingVehicle;
            GuidanceOutput out = RVP_UavLoiterGuidance.compute(
                uav.getX(), uav.getY(), uav.getZ(), uav.getYRot(),
                center.x, center.y, center.z,
                state.radius(), state.altitude(), isRotaryWing
            );

            // 5. 写入 ControlUnit（先 reset 确保干净状态）
            uav.controlUnit.reset();
            uav.controlUnit.forward = out.forward();
            uav.controlUnit.up = out.up();
            uav.controlUnit.down = out.down();
            if (out.useAnalogYaw()) {
                // 旋翼机：设置目标偏航角，物理引擎自动平滑追踪
                uav.controlUnit.yRot = out.targetYRot();
                uav.controlUnit.yRotKeep = false;
            } else {
                // 固定翼：离散偏航控制
                uav.controlUnit.leftYaw = out.leftYaw();
                uav.controlUnit.rightYaw = out.rightYaw();
            }

            // 6. 区块加载保持
            EntityUtil.keepChunkLoaded(uav, uav.position());
        }
    }
}
```

### 6.3 触发入口

#### 自动激活（切换回母车）

在 `RVP_DeployableUavService.switchBackToParent()` 成功后调用：

```java
// switchBackToParent() 末尾
if (config.autoLoiterOnSwitchBack()) {
    RVP_UavLoiterManager.enableFollowParent(
        child.getUUID(),        // UAV UUID
        parent.getUUID(),       // 母车 UUID（跟随目标）
        config.loiterRadius(),
        config.loiterAltitudeOffset()
    );
}
```

#### 按键手动切换

新增网络包 `C2SToggleUavLoiter`：

```java
public class C2SToggleUavLoiter {
    public static void handle(C2SToggleUavLoiter msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.getSender();
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) return;
        if (vehicle instanceof AbstractVehicleLinkedUavExt ext
                && ext.ywzj_rvp$isDeployableUavInstance()) {
            // 当前驾驶无人机 → 切换盘旋
            UUID uavUuid = vehicle.getUUID();
            if (RVP_UavLoiterManager.get(uavUuid) != null
                    && RVP_UavLoiterManager.get(uavUuid).active()) {
                RVP_UavLoiterManager.disable(uavUuid);
            } else {
                RVP_DeployableUavConfig config = RVP_DeployableUavConfigCache.get(
                    ext.ywzj_rvp$getLinkedParentVehicleUuid() != null
                        ? resolveParentVehicleId(vehicle) : vehicle.getVehicleId());
                RVP_UavLoiterManager.enableFollowParent(
                    uavUuid, ext.ywzj_rvp$getLinkedParentVehicleUuid(),
                    config.loiterRadius(), config.loiterAltitudeOffset());
            }
        } else if (vehicle instanceof AbstractVehicleLinkedUavExt ext) {
            // 当前驾驶母车 → 切换关联无人机的盘旋
            AbstractVehicle uav = RVP_DeployableUavService.getLinkedChild(vehicle).orElse(null);
            if (uav != null) { /* 同上逻辑 */ }
        }
    }
}
```

在 `RVP_Keys` 注册按键绑定，在 `RVP_ClientEvents` 中监听按键并发送 `C2SToggleUavLoiter` 包。

#### 战术地图标记圆心

在 `RVP_TacticalMapScreen` 的右键上下文菜单中新增"设为盘旋圆心"选项：

```java
// RVP_TacticalMapScreen 右键菜单
if (isOwnLinkedUav(entity)) {
    contextMenu.add(new MenuEntry(
        Component.translatable("gui.ywzj_rvp.tactical_map.set_loiter_center"),
        () -> sendC2SSetLoiterCenter(entity.getUUID(), worldX, worldY, worldZ)
    ));
}

// 或在空白处右键（地图坐标）：
contextMenu.add(new MenuEntry(
    Component.translatable("gui.ywzj_rvp.tactical_map.set_loiter_center_here"),
    () -> sendC2SSetLoiterCenter(currentLinkedUavUuid, worldX, terrainY, worldZ)
));
```

新增 `C2SSetLoiterCenter` 网络包，服务端调用 `RVP_UavLoiterManager.enableMarkedCenter()` 或 `updateCenter()`。

### 6.4 盘旋中心策略

```
enable(uavUuid, uav, config):
    if (hasMarkedCenter(uavUuid)):
        center = getMarkedCenter(uavUuid)        // 战术地图标记的固定点
        enableMarkedCenter(uavUuid, center, ...)
    else:
        followParentUuid = getParentUuid(uav)    // 母车 UUID
        enableFollowParent(uavUuid, followParentUuid, ...)
        // 每 tick 更新 center = 母车当前位置
```

### 6.5 玩家重新进入无人机时自动关闭

在 `RVP_DeployableUavService.switchToLinkedUav()` 成功后，或通过 `onEnterVehicle` Mixin 回调：

```java
// switchToLinkedUav() 成功后
RVP_UavLoiterManager.disable(child.getUUID());
```

### 6.6 边界条件处理

| 场景 | 处理 |
|------|------|
| 玩家重新进入无人机 | 自动关闭盘旋（`switchToLinkedUav` 末尾） |
| 无人机被摧毁/移除 | tick 处理器检测到 `!isAlive()` → `remove(uavUuid)` |
| 母车被摧毁 | 跟随模式转为最后已知位置固定点（tick 中 parent==null 时停止更新 center） |
| 无人机引擎未启动 | 盘旋仍尝试写 controlUnit，但物理引擎可能不出力（后续可加引擎检查） |
| 盘旋中区块卸载 | tick 处理器调用 `EntityUtil.keepChunkLoaded(uav, uav.position())` |

### 6.7 盘旋半径设计

#### 6.7.1 物理约束

| 载具类型 | 最小半径约束 | 原因 |
|----------|-------------|------|
| 旋翼机 | ~30 格 | 可悬停转向，但半径过小会变成原地打转，yaw 追踪震荡 |
| 固定翼 | V² / (g·tan(bank)) | 必须保持前飞速度，受最大坡度限制。典型无人机速度下约 80-150 格 |

#### 6.7.2 半径确定流程

JSON 配置值 → 运行时 clamp → 实际半径：

```
configuredRadius (JSON)
    ↓
minRadius = resolveMinRadius(uav)     // 根据载具类型和当前速度计算
    ↓
actualRadius = max(configuredRadius, minRadius)
```

旋翼机最小半径固定 30 格；固定翼最小半径根据当前速度动态计算：

```java
static double resolveMinRadius(AbstractVehicle uav) {
    if (uav instanceof RotaryWingVehicle) {
        return 30.0;  // 旋翼机固定下限
    }
    // 固定翼：R = V² / (g · tan(maxBank))
    // V = 水平速度 (blocks/tick → m/s 换算)
    // maxBank = 30° (保守坡度)
    double speed = Math.sqrt(uav.getDeltaMovement().horizontalDistanceSqr()) * 20; // tick→秒
    double g = 9.8;
    double maxBankRad = Math.toRadians(30);
    double minR = (speed * speed) / (g * Math.tan(maxBankRad));
    return Math.max(80.0, minR);  // 硬下限 80 格
}
```

#### 6.7.3 半径动态修正（震荡检测）

盘旋中如果检测到 yaw 追踪震荡（航向误差反复正负跳变），自动增大半径 10%：

```java
// 记录最近 N tick 的 yawError 符号变化次数
if (signFlipCount > 3) {
    actualRadius *= 1.1;  // 扩大半径缓解震荡
}
```

### 6.8 盘旋高度设计

#### 6.8.1 三重高度基准

之前方案仅用 `loiter_altitude_offset`（相对圆心的高度偏移），存在问题：如果圆心（母车）在山顶，无人机可能撞山；如果母车在谷底，绝对高度可能不够。

改为**三重高度基准取最大值**：

```
targetAltitude = max(
    center.y + altitudeOffset,           // 相对圆心
    terrainHeight + terrainClearance,    // 地形安全
    minSafeAltitude                       // 最低安全高度（海平面以上）
)
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `loiter_altitude_offset` | 40 | 高于圆心的高度 |
| `terrain_clearance` | 30 | 高于地表的最小间隙 |
| `min_safe_altitude` | 80 | 绝对最低安全高度 |

#### 6.8.2 前方地形采样

盘旋中和航渡中每 2 秒（40 tick）对前方航迹做地形采样：

```java
// 采样前方 60 格的地形最高点
double sampleTerrainAhead(AbstractVehicle uav, Vec3 heading) {
    double maxTerrainY = 0;
    for (int i = 10; i <= 60; i += 10) {
        Vec3 sample = uav.position().add(heading.scale(i));
        int terrainY = uav.level().getHeight(Heightmap.Types.WORLD_SURFACE,
            BlockPos.containing(sample.x, 0, sample.z)).getY();
        maxTerrainY = Math.max(maxTerrainY, terrainY);
    }
    return maxTerrainY;
}
```

如果前方地形高于当前高度 - `terrainClearance`，临时爬升。

#### 6.8.3 高度控制律

```java
double altError = targetAltitude - uav.getY();

if (isRotaryWing) {
    // 旋翼机：直接 up/down 控制
    boolean up = altError > 2;
    boolean down = altError < -2;
    // 大误差时持续触发，小误差时 pulse 调制防震荡
    if (Math.abs(altError) < 8) {
        int duty = (int) Mth.clamp(Math.abs(altError) / 2, 1, 4);
        boolean pulse = (tickCount % 5) < duty;
        up = altError > 2 && pulse;
        down = altError < -2 && pulse;
    }
} else {
    // 固定翼：up/down 控制俯仰，forward 维持油门
    boolean up = altError > 5;
    boolean down = altError < -5;
    // 固定翼 always forward 维持升力
    forward = true;
}
```

### 6.9 航渡（前往盘旋点）自动驾驶

当激活盘旋时，无人机可能离圆心很远，需要一个**航渡阶段**先飞到盘旋圆周，再切入盘旋。

#### 6.9.1 四阶段状态机

```
                    激活盘旋
                       │
                       ▼
               ┌─── CLIMB ───┐
               │ 爬升到安全高度 │
               └──────┬──────┘
                      │ altError < 10 且高度 > minSafeAltitude
                      ▼
               ┌─── TRANSIT ──┐
               │ 朝盘旋点直飞   │
               └──────┬──────┘
                      │ 水平距离 < radius × 1.5
                      ▼
               ┌─── APPROACH ──┐
               │ 减速 + 对齐切线 │
               └──────┬──────┘
                      │ 水平距离 ∈ [radius-15, radius+15]
                      ▼
               ┌─── LOITER ───┐
               │  两阶段制导    │
               └──────────────┘
```

#### 6.9.2 CLIMB（爬升段）

触发条件：当前高度 < `minSafeAltitude` 或 高度差 > 50 格

```java
case CLIMB:
    // 旋翼机：原地爬升，不前进
    uav.controlUnit.reset();
    uav.controlUnit.up = true;
    if (isFixedWing) {
        // 固定翼不能原地悬停，必须前进爬升
        uav.controlUnit.forward = true;
        uav.controlUnit.up = true;  // 抬头爬升
        // 朝圆心方向转向
        setYawToward(uav, center);
    }
    // 退出条件：高度达标
    if (uav.getY() > minSafeAltitude && altError < 30) {
        phase = TRANSIT;
    }
```

#### 6.9.3 TRANSIT（航渡段）

触发条件：高度达标，水平距离 > `radius × 1.5`

```java
case TRANSIT:
    // 目标点：盘旋圆周上离自己最近的点
    double dx = center.x - uav.getX();
    double dz = center.z - uav.getZ();
    double dist = Math.sqrt(dx*dx + dz*dz);
    // 圆周接入点 = 圆心方向上距离 = radius 的点
    double approachX = center.x - (dx / dist) * radius;
    double approachZ = center.z - (dz / dist) * radius;

    // 航向：直接指向接入点
    double targetYaw = Math.toDegrees(Math.atan2(approachX - uav.getX(),
                                                  -(approachZ - uav.getZ())));
    setYaw(uav, targetYaw);

    // 全速前进
    uav.controlUnit.forward = true;

    // 高度维持
    applyAltitudeControl(uav, targetAltitude);

    // 地形规避（前方地形过高时临时爬升）
    double terrainAhead = sampleTerrainAhead(uav, heading);
    if (terrainAhead > uav.getY() - terrainClearance) {
        uav.controlUnit.up = true;
    }

    // 退出条件：接近圆周
    if (dist < radius * 1.5) {
        phase = APPROACH;
    }
```

#### 6.9.4 APPROACH（接近段）

触发条件：水平距离 < `radius × 1.5`，尚未进入圆周

```java
case APPROACH:
    // 计算切线航向（与盘旋段相同）
    double tangentYaw = Math.toDegrees(Math.atan2(-dz, -dx));
    // 航向逐渐从"指向圆心"过渡到"切线方向"
    double blend = Mth.clamp((dist - radius) / (radius * 0.5), 0, 1);
    double targetYaw = lerp(tangentYaw, toCenterYaw, blend);
    setYaw(uav, targetYaw);

    // 旋翼机减速：pulse 调制 forward
    if (isRotaryWing) {
        int duty = (int)(4 * blend + 1);  // 远时全速，近时减速
        uav.controlUnit.forward = (tickCount % 5) < duty;
    } else {
        uav.controlUnit.forward = true;  // 固定翼不能减速太多
    }

    applyAltitudeControl(uav, targetAltitude);

    // 退出条件：进入圆周 ±15 格
    if (Math.abs(dist - radius) < 15) {
        phase = LOITER;
    }
```

#### 6.9.5 LOITER（盘旋段）

即 6.2 节设计的两阶段制导算法（切线航向 + 径向航向混合）。

#### 6.9.6 状态存储扩展

`LoiterState` record 新增阶段字段：

```java
public record LoiterState(
    boolean active,
    LoiterPhase phase,            // CLIMB / TRANSIT / APPROACH / LOITER
    double centerX, double centerY, double centerZ,
    double radius, double altitude,
    UUID followParentUuid,
    boolean markedCenter,
    int signFlipCounter,          // yaw 震荡检测
    float lastYawError,           // 上一 tick 的航向误差
    int phaseTickCounter,         // 当前阶段已持续时间
    int phaseTimeout              // 当前阶段超时阈值
) {}

public enum LoiterPhase {
    CLIMB, TRANSIT, APPROACH, LOITER
}
```

由于 record 不可变，`RVP_UavLoiterManager` 内部用可变类存储，对外暴露 record 快照：

```java
// 内部可变状态
private static final class MutableLoiterState {
    volatile LoiterPhase phase = LoiterPhase.CLIMB;
    volatile double centerX, centerY, centerZ;
    volatile double radius, altitude;
    volatile UUID followParentUuid;
    volatile boolean markedCenter;
    volatile int signFlipCounter = 0;
    volatile float lastYawError = 0;
    volatile int phaseTickCounter = 0;
    volatile int phaseTimeout = 0;
}
```

#### 6.9.7 阶段超时降级

如果某个阶段卡住（如固定翼风速导致无法切入圆周），设置超时降级：

```java
// CLIMB 阶段超时 200 tick（10 秒）未达标 → 强制进入 TRANSIT
// TRANSIT 阶段超时 1200 tick（60 秒）未接近 → 进入 LOITER（直接开始盘旋）
// APPROACH 阶段超时 400 tick（20 秒）未切入 → 进入 LOITER

if (phaseTickCounter > phaseTimeout) {
    phase = nextPhase(phase);
    phaseTickCounter = 0;
}
```

#### 6.9.8 完整制导流程

```
onServerTick:
    for each active UAV:
        1. 更新 center（跟随母车模式）
        2. 计算 targetAltitude = max(center.y + offset, terrain + clearance, minSafe)
        3. switch (phase):
              CLIMB    → 爬升 + 朝圆心转向（固定翼）
              TRANSIT  → 朝圆周接入点直飞 + 地形规避
              APPROACH → 航向混合 + 减速
              LOITER   → 两阶段制导 + 半径震荡检测
        4. 写入 controlUnit
        5. 阶段切换检查 + 超时降级
        6. 区块加载保持
```

## 七、控制映射

### 7.1 旋翼无人机（RotaryWingVehicle）

| ControlUnit 字段 | 作用 | 盘旋控制 |
|------------------|------|----------|
| `yRot` | 目标偏航角（analog 平滑追踪） | 制导算法计算的目标航向 |
| `up` / `down` | 总距（高度） | 高度差 > ±2 格时触发 |
| `forward` | 前倾（前进推进） | 距轨道远或圈内时触发 |
| `yRotKeep` | 保持偏航 | false（允许 yaw 追踪） |

旋翼机优势：`yRot` 提供 analog 平滑追踪，物理引擎自动计算偏航速率，无需 pulse 调制。

### 7.2 固定翼无人机（FixedWingVehicle）

| ControlUnit 字段 | 作用 | 盘旋控制 |
|------------------|------|----------|
| `forward` | 油门增加 | 持续 true 维持速度 |
| `leftYaw` / `rightYaw` | 偏航（离散） | yawError > ±3° 时触发 |
| `up` / `down` | 俯仰（高度） | 高度差 > ±2 格时触发 |

固定翼注意：`getDriver() == null` 时 `xRot` 被重置为 0，但 `up/down/leftYaw/forward` 仍生效。

### 7.3 Pulse 调制（可选优化）

对于需要比例控制的场景（如固定翼 yaw 微调），可用 tick 计数做 pulse 调制：

```java
// 将 yawError 映射到 tick 占空比
int dutyCycle = (int) Mth.clamp(Math.abs(yawError) / 10f * 5, 1, 5);  // 1~5 tick
boolean yawActive = (server.getTickCount() % 5) < dutyCycle;
uav.controlUnit.leftYaw = yawError > 3 && yawActive;
```

## 八、JSON 配置扩展

在 `RVP_DeployableUavConfig` record 新增字段。

### 8.1 新增参数清单

| Java 字段名 | JSON 键名 | 类型 | 默认值 | 说明 |
|-------------|-----------|------|--------|------|
| `loiterRadius` | `loiter_radius` | double | 120 | 盘旋半径（格），运行时会按载具类型做最小半径 clamp |
| `loiterAltitudeOffset` | `loiter_altitude_offset` | double | 40 | 高于盘旋圆心的高度（格） |
| `autoLoiterOnSwitchBack` | `auto_loiter_on_switch_back` | boolean | true | 切回母车时自动盘旋 |
| `loiterTerrainClearance` | `loiter_terrain_clearance` | double | 30 | 高于地表的最小间隙（格），用于地形规避 |
| `loiterMinSafeAltitude` | `loiter_min_safe_altitude` | double | 80 | 绝对最低安全高度（海平面以上，格） |
| `loiterFixedWingMinBank` | `loiter_fixed_wing_min_bank` | double | 30 | 固定翼最大坡度（度），用于计算最小转弯半径 |
| `loiterSignFlipThreshold` | `loiter_sign_flip_threshold` | int | 3 | yaw 误差符号翻转次数阈值，超过则自动扩大半径 |
| `loiterRadiusExpandFactor` | `loiter_radius_expand_factor` | double | 1.1 | 震荡时半径扩大倍率 |
| `loiterClimbTimeout` | `loiter_climb_timeout` | int | 200 | CLIMB 阶段超时（tick，10 秒=200） |
| `loiterTransitTimeout` | `loiter_transit_timeout` | int | 1200 | TRANSIT 阶段超时（tick，60 秒=1200） |
| `loiterApproachTimeout` | `loiter_approach_timeout` | int | 400 | APPROACH 阶段超时（tick，20 秒=400） |
| `loiterTerrainSampleInterval` | `loiter_terrain_sample_interval` | int | 40 | 地形采样间隔（tick，2 秒=40） |
| `loiterTerrainSampleRange` | `loiter_terrain_sample_range` | int | 60 | 前方地形采样范围（格） |
| `loiterApproachTolerance` | `loiter_approach_tolerance` | double | 15 | 进入盘旋段的距离容差（格） |

### 8.2 参数分组说明

| 分组 | 参数 | 用途 |
|------|------|------|
| **基础盘旋** | `loiter_radius`, `loiter_altitude_offset`, `auto_loiter_on_switch_back` | 盘旋核心参数 |
| **高度安全** | `loiter_terrain_clearance`, `loiter_min_safe_altitude` | 三重高度基准中的地形安全项 |
| **固定翼约束** | `loiter_fixed_wing_min_bank` | 计算固定翼最小转弯半径 |
| **震荡抑制** | `loiter_sign_flip_threshold`, `loiter_radius_expand_factor` | yaw 追踪震荡检测与自动扩半径 |
| **航渡超时** | `loiter_climb_timeout`, `loiter_transit_timeout`, `loiter_approach_timeout` | 四阶段状态机超时降级 |
| **地形采样** | `loiter_terrain_sample_interval`, `loiter_terrain_sample_range` | 航渡/盘旋中的前方地形规避 |
| **阶段切换** | `loiter_approach_tolerance` | APPROACH→LOITER 切换距离容差 |

### 8.3 JSON 配置示例

母车 JSON 的 `rvp_deployable_uav` 段：

```json
{
    "rvp_deployable_uav": {
        "enabled": true,
        "vehicle_id": "rvp:recon_uav",
        "loiter_radius": 150,
        "loiter_altitude_offset": 50,
        "auto_loiter_on_switch_back": true,
        "loiter_terrain_clearance": 30,
        "loiter_min_safe_altitude": 80,
        "loiter_fixed_wing_min_bank": 30,
        "loiter_sign_flip_threshold": 3,
        "loiter_radius_expand_factor": 1.1,
        "loiter_climb_timeout": 200,
        "loiter_transit_timeout": 1200,
        "loiter_approach_timeout": 400,
        "loiter_terrain_sample_interval": 40,
        "loiter_terrain_sample_range": 60,
        "loiter_approach_tolerance": 15
    }
}
```

### 8.4 最小配置（仅必填项）

大多数参数有合理默认值，最小配置只需：

```json
{
    "rvp_deployable_uav": {
        "enabled": true,
        "vehicle_id": "rvp:recon_uav",
        "loiter_radius": 120,
        "loiter_altitude_offset": 40
    }
}
```

## 九、网络包清单

| 包名 | 方向 | 用途 |
|------|------|------|
| `C2SToggleUavLoiter` | C→S | 按键手动开关盘旋 |
| `C2SSetLoiterCenter` | C→S | 战术地图标记盘旋圆心 |
| `S2CUavLoiterState` | S→C | 同步盘旋状态到客户端（HUD 显示） |

## 十、客户端显示

### 10.1 HUD 指示器

在无人机 HUD 上显示盘旋状态：

- 盘旋激活时显示 `LOITER ON` 文字 + 圆心距离
- 盘旋关闭时显示 `LOITER OFF`

### 10.2 战术地图盘旋圆

在战术地图上绘制盘旋圆：

- 以 GPS_ICON_COLOR 绘制圆心标记
- 以半透明线条绘制盘旋圆
- 旋翼机显示当前 yaw 指向

## 十一、实现计划

| 阶段 | 内容 | 涉及文件 |
|------|------|----------|
| 1 | 状态管理器 + 制导算法 | `RVP_UavLoiterManager`, `RVP_UavLoiterGuidance` |
| 2 | 服务端 tick 处理器 | `RVP_UavLoiterTickService` |
| 3 | JSON 配置扩展 | `RVP_DeployableUavConfig`, `RVP_DeployableUavConfigCache` |
| 4 | 自动激活（switchBackToParent） | `RVP_DeployableUavService` |
| 5 | 按键 + 网络包 | `C2SToggleUavLoiter`, `RVP_Keys`, `RVP_Network` |
| 6 | 战术地图标记圆心 | `C2SSetLoiterCenter`, `RVP_TacticalMapScreen` |
| 7 | 客户端 HUD + 地图显示 | `S2CUavLoiterState`, `RVP_ClientUavLoiterState` |
| 8 | 编译验证 + 测试 | `gradlew compileJava` |

## 十二、文件清单（新建）

```
org/ywzj/rvp/uav/
    RVP_UavLoiterManager.java          — 状态管理器
    RVP_UavLoiterGuidance.java         — 制导算法
    RVP_UavLoiterTickService.java      — 服务端 tick 处理器

org/ywzj/rvp/network/
    C2SToggleUavLoiter.java            — 按键切换包
    C2SSetLoiterCenter.java            — 地图标记圆心包
    S2CUavLoiterState.java             — 状态同步包

org/ywzj/rvp/client/state/
    RVP_ClientUavLoiterState.java      — 客户端盘旋状态
```

## 十三、文件清单（修改）

```
org/ywzj/rvp/config/
    RVP_DeployableUavConfig.java       — 新增 loiter 参数字段
    RVP_DeployableUavConfigCache.java  — 缓存适配

org/ywzj/rvp/uav/
    RVP_DeployableUavService.java      — switchBackToParent 末尾激活盘旋
                                         switchToLinkedUav 末尾关闭盘旋

org/ywzj/rvp/network/
    RVP_Network.java                   — 注册新网络包

org/ywzj/rvp/client/
    RVP_Keys.java                      — 注册盘旋切换按键
    RVP_ClientEvents.java              — 按键监听

org/ywzj/rvp/client/screen/
    RVP_TacticalMapScreen.java         — 右键菜单 + 盘旋圆绘制
```
