# ARM（反辐射导弹）重构前后功能差异报告

## 背景

旧版 ARM 位于 `E:\ywzj_rvp`，采用**独立武器类型**方案，有自己的实体、武器类、数据类、网络包和客户端 UI。  
新版重构到 `D:\ywzj\ywzj\ywzj_rvp` 后，改为使用通用 `rvp:missile` + `guidance_data.stages[].sources[].type=ARM` 的配置化方案。  
重构过程中部分旧版功能未能迁移到新版。

---

## 新版保留的功能

| 功能 | 旧版类 | 新版类 | 状态 |
| --- | --- | --- | --- |
| PDW 辐射源扫描与评分 | `ArmSeekerHelper` | `AntiRadiationSeekerHelper` | ✅ 保留（重命名） |
| 脉冲记忆与超时 | `ArmSeekerHelper` | `AntiRadiationSeekerHelper` | ✅ 保留 |
| 雷达单元扩展接口 | `RadarUnitDataExt`、`RadarUnitPojoExt` | 相同 | ✅ 保留 |
| 雷达 mixin（扫描扇区等） | `RadarUnitMixin`、`RadarUnitDataMixin`、`RadarUnitPojoMixin` | 相同 | ✅ 保留 |

---

## 新版丢失的功能

### 一、ARM 预选目标系统（完全丢失）

| 文件 | 说明 |
| --- | --- |
| `C2SSetArmPreselect.java` | 客户端 → 服务端预选辐射源网络包 |
| `WeaponUnitArmMixin.java` | 在 `WeaponUnit` 上存储预选目标（VehicleId / RadarIndex / Pos） |
| `WeaponUnitArmExt.java` | 预选目标访问接口 |
| `RvpClientArmState.java` | 客户端 ARM 状态管理：扫描辐射源、维护联系人列表、自动提交预选 |
| `RvpArmOverlay.java` | 客户端 HUD 叠加层：在 3D 空间绘制辐射源方框、高亮锁定目标 |

**效果丢失**：玩家不能再在 HUD 上看到敌雷达源的方位框，也无法预选某个雷达单元然后发射 ARM 弹。

---

### 二、独立 SEAD 武器类型（完全丢失）

| 文件 | 说明 |
| --- | --- |
| `VehicleSeadMissile.java` | 独立反辐射武器类，发射前检查是否有预选目标/锁定 |
| `VehicleSeadMissileWeaponData.java` | 专用数据类，8 个 `sead_*` 字段 |
| `SeadMissileEntity.java` | 独立弹体实体，含 PDW 扫描、脉冲记忆、目标跟踪 |
| `RvpVehicleWeaponTypes.java` | 注册 `"ywzj_rvp:anti_radar_missile"` 武器类型 |

**效果丢失**：

- `sead_allow_fire_without_seeker` — 不允许无目标发射
- `sead_preselect_enabled` — 预选目标后发射
- 弹体不再有 NBT 持久化（含 `seadMemoryLeftTick`、`seadTargetVehicleId` 等 7 项状态）
- 不再给被锁定载具发送 `ServerVehicleWarn`（RWR 不会显示被 ARM 锁定）
- 弹体 `memory_tick` 不再按对方雷达 `contactHoldTick` 动态计算

---

### 三、电子战模块（完全丢失）

| 文件 | 说明 |
| --- | --- |
| `EwJammingManager.java` | 干扰状态机：激活 / 冷却 / 烧穿距离管理 |
| `EwJammingState.java` | 干扰状态数据 |
| `EwLockDiversionService.java` | 干扰诱骗逻辑：把雷达锁定转移到假目标上 |
| `EwRadarCandidateService.java` | 把 EW 假目标注入雷达扫描结果 |
| `EwDecoyEntity.java` | 假目标实体 |
| `EwDecoyEntityRenderer.java` | 假目标渲染 |
| `EwBandProfile.java` | 频段配置 |
| `EwDistanceBand.java` | 距离带定义（近 / 中 / 远 / 烧穿） |
| `EwVehicleConfig.java` | 载具 EW 配置 |
| `EwDebugState.java` / `RvpEwDebugCommands.java` | EW 调试系统 |
| `RadarCheckTargetMixin.java` | 把假目标注入本体的雷达扫描 |

**效果丢失**：整套电子对抗系统都丢了，包括干扰机、假目标诱骗、频段管理等。

---

### 四、载具数据扩展（完全丢失）

| 文件 | 说明 |
| --- | --- |
| `BaseVehicleDataExt.java` | `isEwEnabled()` / `getEwConfig()` 接口 |
| `BaseVehicleDataPojoExt.java` | EW 配置的 JSON 反序列化 |
| `BaseVehicleDataMixin.java` | 实现 EW 扩展 |
| `BaseVehicleDataPojoMixin.java` | 解析 `ew_enabled` / `ew_config` JSON 字段 |

**效果丢失**：无法在载具 JSON 里配 `ew_enabled` 等字段。

---

### 五、网络包（部分丢失）

| 旧版 | 新版 | 状态 |
| --- | --- | --- |
| `C2SSetArmPreselect` | 无 | ❌ 丢失 |
| `C2SSetGpsTarget` | 无 | ❌ 丢失 |
| `C2STvControlInput` | 无 | ❌ 丢失 |
| `C2STvExit` | 无 | ❌ 丢失 |
| `S2CSetTvMissile` | 无 | ❌ 丢失 |

---

### 六、RWR 告警联动（丢失）

旧版 `SeadMissileEntity.tickSeadTrack()` 在每 2 tick 通过 `ServerVehicleWarn` 包通知被锁定载具受到 ARM 威胁。新版没有等效逻辑，被锁定的载具不会收到 RWR 告警。

---

## 优先级建议

**最优先补回**（影响玩家使用反辐射弹的基本体验）：

1. **ARM 客户端 HUD 叠加层**（`RvpArmOverlay` + `RvpClientArmState`）— 没有它就看不到辐射源在哪里
2. **预选目标发射**（`C2SSetArmPreselect` + `WeaponUnitArmExt`）— 打好反辐射弹的基本操作链
3. **RWR 告警**（`ServerVehicleWarn`）— 被锁的载具不知道自己被 ARM 打了

**后续可补回**（但不影响 ARM 基本功能）：

4. 电子战模块（干扰 / 诱骗 / 假目标）
5. 载具 EW JSON 配置
6. 弹体 NBT 持久化
7. 额外网络包
