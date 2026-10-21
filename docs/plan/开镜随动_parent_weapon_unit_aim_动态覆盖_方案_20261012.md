# 开镜随动：`eo_ccip` 武器在 SCOPE 视角下强制锚定母武器站（方案）

> 日期：2026-10-12　状态：**待实现**（本文只出方案，不落代码）
> 触发：AH1Z `rocket` 站把 `parent_weapon_unit_aim` 设为 `false` 后，APKWS（激光制导火箭弹）在**观瞄视角**下准星不再随动、固定为火箭站自身指向。

## 1. 需求

对配了 `rvp_fire_control_sensor_mode: "eo_ccip"` 的弹药，**只在 SCOPE（开镜）视角**把 `parent_weapon_unit_aim` 当 `true` 用（弹着点/准星锚定到母武器站 ⇒ 准星随动）；其他视角保持武器站的静态配置（本例 `false`）。

## 2. 现状与原因（已核对）

链路：`LocalVehiclePlayer.tickAim → tickHit` → `WeaponUnit.currentWeaponHitPosition()`（`ywzj_vehicle/.../WeaponUnit.java:824-889`）

```java
WeaponUnit currentWeaponUnit = vehicleWeapon.getWeaponUnit();   // = rocket 站
if (currentWeaponUnit.isParentWeaponUnitAim()) {               // ← 关键开关
    currentWeaponUnit = currentWeaponUnit.getRootParentWeaponUnit();  // 换成观瞄站
}
...
if (getFireControlSensorType() == CCIP) { ... 沿 currentWeaponUnit 的方向算 CCIP ... }
if (aimHitPosition == null) aimHitPosition = currentWeaponUnit.aimHitPosition();
```

- `false` ⇒ 沿 **rocket 站自身**的方向：它的 `rot_info` 转速为 0 ⇒ 方向恒定 ⇒ **准星/弹着点定死**（SCOPE 下尤其明显，因为 `eo_ccip` 在 SCOPE 会把传感器切成 `EO`，直接跳过 CCIP 分支，落到 `aimHitPosition()`）。
- `true` ⇒ 换成 **观瞄站（母站）** 的方向 ⇒ 随动。

**已有武器级覆盖机制**（不必新造轮子）：

| 位置 | 作用 |
|---|---|
| `rvp/weapon/data/RVP_WeaponData.java:150` | 武器 JSON 字段 `parent_weapon_unit_aim_override`（`Boolean`，可空） |
| `rvp/util/RVP_WeaponResolveHelper.java:55-62` | `resolveParentWeaponUnitAimOverride(weapon)`：RVP 武器有配置则返回，否则 `null` |
| `rvp/mixin/WeaponUnitParentAimOverrideMixin.java`（**公共数组**） | `@Redirect` `currentWeaponHitPosition` 内那次 `isParentWeaponUnitAim()` 调用 |
| `rvp/mixin/VehicleAimAtOverlaySeekerColorMixin.java:137-153`（**client 数组**） | `@Redirect` `VehicleAimAtOverlay` HUD 准心的同款调用 |

⇒ 只要改 `resolveParentWeaponUnitAimOverride` 一处，**两个消费点同时生效**（两者都调它）。

## 3. 方案对比

### 方案 A：纯数据（0 代码，立即可用）
给武器 JSON 加 `"parent_weapon_unit_aim_override": true`。

- ✅ 立刻生效，不动代码。
- ❌ 覆盖**所有视角**：非开镜（HUD）时弹着点也锚定观瞄站，和当前 `false` 的取舍冲突。
- ❌ 将来想让某武器"只在开镜随动"仍要回代码。

### 方案 B：`eo_ccip` × SCOPE 动态覆盖（**推荐**，符合需求）
把 `eo_ccip` 的语义补完：它已经是"SCOPE→EO、其余→CCIP"，再补上"SCOPE→锚定母站"这一条。

```java
// RVP_WeaponResolveHelper.resolveParentWeaponUnitAimOverride —— 改后
@Nullable
public static Boolean resolveParentWeaponUnitAimOverride(@Nullable AbstractVehicleWeapon<?> weapon) {
    if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
        return null;
    }
    RVP_WeaponData data = rvpWeapon.getData();
    // 1) 显式配置优先（可预测：写了就按写的来）
    Boolean explicit = data.getParentWeaponUnitAimOverride();
    if (explicit != null) {
        return explicit;
    }
    // 2) eo_ccip 动态语义：SCOPE 视角按"电视/激光指示"处理 ⇒ 必须随动，锚定母武器站
    if ("eo_ccip".equalsIgnoreCase(data.getFireControlSensorMode())
            && RVP_ClientActionsAccess.isLocalOperatorInScopeView()) {
        return Boolean.TRUE;
    }
    // 3) 其余回退武器站静态值
    return null;
}
```

**必须走双端安全桥**：`WeaponUnitParentAimOverrideMixin` 在**公共数组**，不能直接引用 `@OnlyIn(CLIENT)` 的 `LocalVehiclePlayer`（见 `agents.md` Mixin 纪律）。

新增桥接（4 个文件，照现有 `shouldSuppressTrailParticleNearHitlMissile` 的写法）：

| 文件 | 改动 |
|---|---|
| `client/bridge/RVP_IClientActions.java` | `boolean isLocalOperatorInScopeView();` |
| `client/bridge/RVP_ClientActionsAccess.java` | `public static boolean isLocalOperatorInScopeView() { return INSTANCE.isLocalOperatorInScopeView(); }` |
| 同上，`NoopClientActions` | `return false;`（专用服务端恒 false ⇒ 服务端行为与今天完全一致） |
| `client/bridge/RVP_ClientActions.java` | `LocalVehiclePlayer p = LocalVehiclePlayer.instance; return p != null && p.onVehicle() && p.viewType == LocalVehiclePlayer.ViewType.SCOPE;` |

判据可直接复用 `RVP_WeaponSensorHelper.effectiveSensorType` 里同款写法（`RVP_WeaponSensorHelper.java:43-47`）。

### 方案 C：纯公共 API（**零 Mixin 依赖**，客户端 tick 动态置位）
本体提供了公开 setter：`WeaponUnit.setParentWeaponUnitAim(boolean)`（`ywzj_vehicle/.../WeaponUnit.java:1223`；本体自带车 `Z10.java:95` 就是这么用的）。

- 已核实该字段**无网络同步、无序列化**：全库只有"构造时从 data 取初值 / getter / setter"三处（`WeaponUnit.java:106,151,1220,1223-1224`）⇒ 客户端本地改写安全，服务端副本不受影响。
- 写法：在 RVP 已有的客户端 tick 事件（如 `RVP_ClientArmTickHandler.onClientTick` 同款 `TickEvent.ClientTickEvent`）里，对"当前操作站选中的武器所属武器站"执行：
  ```java
  boolean scope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
  boolean eo_ccip = "eo_ccip".equalsIgnoreCase(rvpData.getFireControlSensorMode());
  // 原始静态值从数据层取，无需自己记账
  boolean staticValue = unit.getData().isParentWeaponUnitAim();
  unit.setParentWeaponUnitAim(staticValue || (scope && eo_ccip));
  ```
  每 tick 按上式**幂等重算**（含切视角/切武器/下车），无需保存旧值。

**代价（必须知情）**：
1. 该 flag 是**站级**、不是武器级：同一站换到非 `eo_ccip` 武器时必须靠每 tick 重算回退，一旦 tick 没跑到（如极端卡顿）会残留状态。
2. 该 flag 是**客户端全局字段**：客户端上**所有**读 `isParentWeaponUnitAim()` 的地方都会跟着变——比方案 B 只重定向 2 个调用点**面更大**（含 `RVP_ClientSaclosState:151` 等）。
3. 属于"运行时状态注入"，后续若有人改 tick 顺序/站点归属，容易再次错位。

**结论**：能用，但只在"绝不允许新增/依赖 Mixin"这一条优先时才选它。

## 3bis. 结论与推荐

| | 新增 Mixin | 依赖已有 Mixin | 精准度 | 数据要改 | 推荐 |
|---|---|---|---|---|---|
| A 纯数据 `parent_weapon_unit_aim_override: true` | 0 | 否 | 全天候生效（非仅 SCOPE） | 是 | 应急/临时验证 |
| **B 改公共解析函数（`eo_ccip` × SCOPE）** | **0** | 是（已有 2 处，不动它们） | **只影响准心/弹着点锚定** | 否 | ⭐ 推荐 |
| C 公共 setter + 客户端 tick | 0 | 否 | 客户端全局 flag | 否 | 仅在硬性要求零 Mixin 依赖时 |

> 注意：**方案 A/B/C 都不需要新增任何 Mixin**。B 之所以"经过 Mixin"，是因为 RVP 早就用两个既有 mixin 把 `parent_weapon_unit_aim_override` 接到了 2 个调用点上——那是 2026 年早已落地的机制，本次只改它调用的公共解析函数。


## 4. 边界与约束（重要）

1. **不要**去改 `WeaponUnit.isParentWeaponUnitAim()` 本体（也不要全局 `@Inject` 它的返回值）。该方法还被这些地方直接调用：
   - `RVP_ClientSaclosState.java:151`（SACLOS 驾束）
   - `RVP_CommandGuidanceAim.java:124`（指令制导瞄准）
   - `RVP_RuntimeLbrGuidanceSource.java:34`（激光驾束起点）
   - `WeaponUnitBombCcipMixin.java:49`（炸弹 CCIP）
   - 本体 `VehicleScopeOverlay.java:210`（炮兵射击诸元）
   全局改写会连带改动制导/炸弹出弹语义，属于超范围。只改**准心/弹着点锚定**这条链。
2. 优先级：显式 `parent_weapon_unit_aim_override` > `eo_ccip`+SCOPE 动态 > 武器站静态值。若某武器既要 `eo_ccip` 又不想开镜随动，写 `override: false` 即可关掉。
3. 纯客户端表现：不动弹道、不动发射方向、不动服务器逻辑（服务端桥返回 false ⇒ 与现状一致）。
4. 影响面 = 所有 `eo_ccip` 武器（当前 10 个：`lavad_hydra70`、`f16_gbu39/53`、`f16_spice1000`、`f18f_specialweapon`、`j10c_gb3_ir/laser`、`j16_akf98a`、`m142_atacms`、`rafale_storm_shadow`）。开镜时它们的准心都会改为锚定母站——符合同一语义，但需要回归这几台开镜表现。

## 5. 数据侧

- AH1Z：`ah1z_apkws` / `ah1z_mpsm` 已配 `rvp_fire_control_sensor_mode: "eo_ccip"`（2026-10-12），**本次无需改数据**。
- 若先要应急验证，可临时给 `ah1z_apkws` 加 `"parent_weapon_unit_aim_override": true`（方案 A），副作用是 HUD 视角也随动。

## 6. 验证清单

1. AH1Z 一号位选 APKWS → 开镜（SCOPE）：转动观瞄，准星/弹着点**跟随**移动。
2. 同一武器切回 HUD：弹着点仍按武器站自身方向（**不**随动）——与改前一致。
3. 一号位选 MPSM（`rvp:rocket`，同样 eo_ccip）：开镜随动；HUD 下 CCIP 圆圈仍在。
4. 一号位选 AGM114 / TOW2B（无 eo_ccip）：开镜**不**随动（回退站点 `false`）。
5. `lavad_hydra70` 等既有 eo_ccip 武器开镜回归：准心锚定自身母站、不闪断。
6. 专用服务端启动无异常（桥 NOOP 路径）。
7. `./gradlew build` 通过；`./gradlew runServer` 冒烟出 `Done (Xs)!`。

## 7. 工作量

1 个 helper 改 1 个方法 + 桥接 4 处（含 NOOP）≈ 30 行，无新 Mixin、无新注入点 ⇒ 符合"非必要不新增 Mixin"纪律。
