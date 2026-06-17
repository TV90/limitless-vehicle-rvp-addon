# ARM（反辐射导弹）功能恢复方案

## 现状

新版 ARM 使用通用 `rvp:missile` + `guidance_data.stages[].sources[].type=ARM`，但存在两个断链：

1. **发射前**：客户端无法扫描辐射源、无法预选目标、无法将预选传到服务端
2. **发射后**：通用 `MissileEntity` 的 tick 中没有 ARM 导引逻辑，导弹不追踪辐射源

两条链都断了，所以导引头无法开启、无法锁定。

---

## 恢复目标

ARM 弹的使用体验恢复到旧版水平：

1. 坐进载具后，HUD 显示附近辐射源方位框
2. 玩家可选择一个辐射源作为预选目标
3. 发射后导弹自主追踪该辐射源（含脉冲记忆/重捕）
4. 被锁定的载具收到 RWR 告警

---

## 方案选择

### 方案 A：补 Mixin（推荐）

在通用 `rvp:missile` 发射流程上补两条链，不新增独立武器/实体类型。

| 层 | 恢复内容 | 工作量 |
| --- | --- | --- |
| 数据承载 | `WeaponUnitArmExt` + `WeaponUnitArmMixin` | ~50 行 |
| 网络 | `C2SSetArmPreselect` 包 + 注册到 `RVP_Network` | ~90 行 |
| 客户端状态 | `RvpClientArmState`（扫描辐射源 + 维护联系人 + 提交预选） | ~180 行 |
| 客户端 HUD | `RvpArmOverlay`（绘制辐射源方框 + 高亮锁定目标） | ~120 行 |
| 弹体导引 | `MissileEntityMixin`（每 tick 调用 `AntiRadiationSeekerHelper`） | ~80 行 |
| RWR 告警 | 在 mixin 中加入 `ServerVehicleWarn` 发送 | ~20 行 |

**优点**：不改数据层、不改注册、不改现有实体，纯 mixin + 客户端事件。  
**缺点**：旧版的 `SeadMissileEntity` 中的 NBT 持久化（弹体记忆 tick 等）无法直接通过 mixin 恢复，需要转存到 `MissileEntity` 的 mixin 扩展接口上。

### 方案 B：恢复独立实体（更重）

把旧版 `SeadMissileEntity` + `VehicleSeadMissile` + `VehicleSeadMissileWeaponData` 完整迁移到新版，注册独立武器类型 `ywzj_rvp:anti_radiation_missile`。

**优点**：旧版逻辑全套恢复，NBT 持久化直接可用。  
**缺点**：需要额外注册武器类型、实体类型、数据类，与新版的通用 `rvp:missile` 体系不兼容。需要同时维护两套发射链路。

---

## 选定方案 A 的详细设计

### 第 1 步：`WeaponUnitArmExt` + `WeaponUnitArmMixin`

当前新版已有 `AntiRadiationSeekerHelper`，但缺少 `WeaponUnit` 上保存预选目标的能力。

新增：

- `ext/WeaponUnitArmExt.java` — 接口
- `mixin/WeaponUnitArmMixin.java` — 注入 `WeaponUnit`，存三个字段：
  - `armPreselectedVehicleId: int`（默认 -1）
  - `armPreselectedRadarIndex: int`（默认 -1）
  - `armPreselectedPos: Vec3`（可空）

注册到 mixins.json。

### 第 2 步：`C2SSetArmPreselect` 网络包

从旧版直接迁移：

- `network/C2SSetArmPreselect.java` — encode/decode/handle
- 注册到 `RVP_Network.CHANNEL`

handle 逻辑：从 `player.getVehicle()` 获取 `AbstractVehicle`，找到玩家所在的 `WeaponUnit`，调用 `WeaponUnitArmExt.setArmPreselected(vehicleId, radarIndex, pos)`。

### 第 3 步：`RvpClientArmState` 客户端状态管理

从旧版 `RvpClientArmState.java` 迁移，核心逻辑：

```
tick() {
    if 当前武器不是 ARM → clear() 返回

    从 weaponUnit 获取 seekerFov / seekRange / pulseMemoryTick / lockedBonus

    if 导引头未开启 → clear() 返回

    调用 ArmSeekerHelper.scanVisibleEmitters() 扫描辐射源
    更新 contacts 列表
    按评分排序

    if 有锁定的联系人:
        通过 C2SSetArmPreselect 提交到服务端
}
```

注意事项：

- `ArmSeekerHelper` 在新版中已重命名为 `AntiRadiationSeekerHelper`，接口签名相同
- 旧版中 `RvpClientArmState` 支持两种武器类型（`VehicleMissile` + `VehicleSeadMissile`），新版不需要 `VehicleSeadMissile` 分支，只需要检测 `sources[].type=ARM`

即在 `guidance_data.stages[].sources[]` 中检测是否含 `ARM` source：

```java
private boolean isCurrentWeaponArm(AbstractVehicleWeapon<?> weapon) {
    if (!(weapon instanceof VehicleMissile missile)) return false;
    var data = missile.getData();
    if (!(data instanceof RVP_WeaponData rvpData)) return false;
    return rvpData.isAntiRadiationMissile();
}
```

### 第 4 步：`RvpArmOverlay` 客户端 HUD

从旧版迁移，监听 `RenderGuiOverlayEvent.Post`：

- 如果 `RvpClientArmState.isActive()` 为 false，跳过
- 遍历 `RvpClientArmState.getContacts()`
- 对每个联系人：`VectorUtil.worldToScreen(position())` 转换为屏幕坐标，绘制方框
- 对锁定中的联系人：绘制高亮/锁定标记

### 第 5 步：`MissileEntityMixin` 弹体导引

新增 `mixin/MissileEntityMixin.java`，目标类 `org.ywzj.vehicle.entity.weapon.MissileEntity`。

注入点：`tick()` 方法末尾。

逻辑：

```java
@Inject(method = "tick", at = @At("TAIL"), remap = false)
private void rvp$armTick(CallbackInfo ci) {
    if (level().isClientSide()) return;
    RVP_WeaponData data = getRvpData();
    if (data == null || !data.isAntiRadiationMissile()) return;

    // 从 guidance_data 中提取 ARM stage 的 seeker/params 参数
    ArmStageParams params = getArmStageParams(data);

    // 每 scanIntervalTick 扫描一次
    if (tickCount % params.scanIntervalTick != 0) {
        // 非扫描 tick：如果 memoryTick > 0 且 有最后已知位置，继续惯性制导
        if (armMemoryLeftTick > 0 && armLastSeenPos != null) {
            targetPos = armLastSeenPos;
            armMemoryLeftTick--;
            return;
        }
        return;
    }

    // 扫描辐射源
    var target = AntiRadiationSeekerHelper.findBestRadiationSource(
        this, params.fov, params.range, tickCount,
        armPulseTickMap, params.pulseMemoryTick, params.lockedBonus
    );

    if (target != null) {
        armMemoryLeftTick = params.memoryTick;
        armLastSeenPos = target.position();
        targetPos = target.position();
        targetEntity = null;
        // 每 2 tick 发送 RWR 告警
        if (tickCount % 2 == 0) {
            sendRwrWarning(target.vehicle());
        }
    } else if (armMemoryLeftTick > 0 && armLastSeenPos != null) {
        armMemoryLeftTick--;
        targetPos = armLastSeenPos;
    } else {
        armLastSeenPos = null;
        if (!params.reacquire) {
            // 永久丢失
        }
    }
}
```

新增 `ext/MissileEntityArmExt.java` 接口用于存储 ARM 状态（`armPulseTickMap`、`armMemoryLeftTick`、`armLastSeenPos`），配合 `MissileEntityMixin` 实现。

### 第 6 步：注册新 mixin 和网络包

- mixins.json 注册 `MissileEntityMixin` 和 `WeaponUnitArmMixin`
- `RVP_Network` 注册 `C2SSetArmPreselect`

---

## 文件清单

| 文件 | 动作 | 角色 |
| --- | --- | --- |
| `ext/WeaponUnitArmExt.java` | 新增 | 预选目标接口 |
| `mixin/WeaponUnitArmMixin.java` | 新增 | 预选目标注入 WeaponUnit |
| `network/C2SSetArmPreselect.java` | 新增 | 预选目标网络包 |
| `client/state/RVP_ClientArmState.java` | 新增 | 客户端辐射源扫描与预选管理 |
| `client/gui/RVP_ArmOverlay.java` | 新增 | HUD 辐射源方框 |
| `mixin/MissileEntityMixin.java` | 新增 | ARM 弹体导引逻辑 |
| `ext/MissileEntityArmExt.java` | 新增 | 导 ARM 弹体状态接口 |
| `network/RVP_Network.java` | 修改 | 注册 C2SSetArmPreselect |
| `ywzj_rvp.mixins.json` | 修改 | 注册新 mixin |

---

## 不做的内容

- 电子战模块（EwJamming / EwDecoy 等）— 与 ARM 核心功能无关，后续独立恢复
- 载具 JSON `ew_enabled` 配置 — 同上
- 独立 `VehicleSeadMissile` 武器类型 — 走通用 `rvp:missile`
- NBT 持久化（弹体状态的跨存档恢复）— 简化，第一版不保存状态

---

## 实施顺序

```
第 1 步：WeaponUnitArmExt + WeaponUnitArmMixin     → 数据承载
第 2 步：C2SSetArmPreselect 网络包                 → 网络传输
第 3 步：RVP_ClientArmState                         → 客户端扫描+预选
第 4 步：RVP_ArmOverlay                             → 客户端 HUD
第 5 步：MissileEntityMixin + MissileEntityArmExt   → 弹体导引+RWR
第 6 步：注册（mixins.json + RVP_Network）          → 接入
```

每步独立可测。
