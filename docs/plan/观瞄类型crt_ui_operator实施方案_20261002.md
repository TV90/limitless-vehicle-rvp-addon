# `crt_ui_operator` 观瞄类型实施方案

> 状态：**已实施**（2026-10-02；数据侧 052d.json weapon2 已配 `crt_ui_operator`，载具包按规范不入库）
> 需求来源：052D 的 HPJ-38（`weapon2`）要求「开镜镜头跟随炮管俯仰」，但又不肯放弃 `crt_ui` 那套观瞄 HUD。
> 约束：本体 `ywzj_vehicle` **只读永不改**，一切改动只能落在 RVP（`ywzj_rvp/src` + 数据包）。

---

## 一、背景：为什么"直接改成 `operator`"不够用

2026-10-02 已实测：把 `052d.weapon2.optical_sight_type` 从 `crt_ui` 改成 `operator` **确实能让镜头跟随炮管俯仰**，但代价是**整套观瞄 HUD 消失**。

根因（已查实，两处 overlay 逻辑一致）：

| 位置 | 判定 | 结果 |
|---|---|---|
| 本体 `VehicleScopeOverlay.renderCrosshair` | `== OPTICAL_SCOPE` → 光学镜圈；`== CRT` → 全套十字线；**其余 → 只画 1×1 小点** | `operator` 只剩小点 |
| RVP `RVP_ScopeOverlay.renderCrosshair` | 同上（`== CRT` 才画十字线 + 距离 + 倍率 + 稳定器 + 焦点锁定框） | 同上 |

⚠️ 052D 顶层写了 `"ui_preset": "052d"`，而 `RVP_OverlayCancelHandler` 会在**载具配了 `ui_preset` 时取消本体 `ywzj_vehicle:vehicle_scope`**。⇒ **052D 实际生效的是 RVP 那份 overlay**（没配 `ui_preset` 的车才走本体那份）。

所以：`operator` 拿位置、HUD 必须靠 `crt` —— 两个取值各占一半，必须把它们拆开。

---

## 二、`optical_sight_type` 的完整影响面（本方案的设计依据）

它**不是 UI 样式参数**，而是「这门武器装的是哪种瞄具」的总开关，下面挂着五件事：

| # | 影响面 | 判定点 | `operator` | `crt` / `crt_ui` |
|---|---|---|---|---|
| 1 | 能否开镜 | `LocalVehiclePlayer:270/303/317`（`== NONE` 才禁） | 能 | 能 |
| 2 | **开镜相机挂点** | `cameraPosition:555` → `worldOwnerViewPosition` / `worldOpticalSightPosition`；`WeaponUnit:992/1003` | **走 `xTurnGroup`（炮口骨）⇒ 随俯仰** | 走 `structureGroup`（偏航骨）⇒ 只随偏航 |
| 3 | 开镜 HUD | 两份 `renderCrosshair` | **1×1 小点** | **十字线 + 距离 + 倍率 + 稳定器 + 锁定框** |
| 4 | CRT 后处理滤镜 | 本体 `checkState:594`（`SCOPE && == CRT`）；RVP `RVP_ClientEvents.ywzj_rvp$applyScopeOverrides`（`!= CRT` 直接关 `RVP_CrtUiLiteHandler`） | 关 | `crt`=开；`crt_ui`=关后处理、改用 `RVP_CrtUiLiteHandler` |
| 5 | 弹丸 / 测距原点 | RVP `RVP_ProjectileSpawner:211`、`RVP_ScopeViewSyncClient:167`（都按 `== OPERATOR` 二选一） | 操作员视角位置 | 观瞄镜位置 |

> 注：052D 四站的 `operator_view_offset` 与 `optical_sight_offset` **填的是同一个值**，所以 #5 两种取值实际算出的位置相同，不构成差异。

**结论**：要让"位置随俯仰 + HUD 用 CRT"，必须**把 #2 与 #3 解耦**。

---

## 三、方案设计（推荐：方案 X）

### 3.1 对外形态：新增取值 `crt_ui_operator`

数据包里写：

```json
"optical_sight_type": "crt_ui_operator"
```

语义 = **`operator` 的位置行为 + `crt_ui` 的界面/滤镜表现**。

### 3.2 展开规则（扩展现有语法糖）

`PartUnitTypeScopeCompatMixin.ywzj_rvp$normalizeScopeConfig` 现有逻辑：`crt_ui` → `"crt"` + `rvp_disable_crt_effect: true`。

追加一条分支：

```
crt_ui_operator  →  "optical_sight_type": "crt"
                    "rvp_disable_crt_effect": true
                    "rvp_optical_sight_follow_pitch": true
```

- 类型落 `crt` ⇒ HUD（#3）与滤镜（#4）**天然就是 `crt_ui` 的表现**，两份 overlay 都不用改。
- 只多一个 `rvp_optical_sight_follow_pitch` 开关 ⇒ 由新 Mixin 把 #2 的位置改回"走炮口骨"。
- 沿用现有的命名空间/路径门禁（`ywzj_vehicle:weapon` / `auto_weapon`）与 `!patched.has(...)` 不覆盖已有字段的写法。

### 3.3 新字段 `rvp_optical_sight_follow_pitch`（bool，默认 false）

三处配套改动，照抄 `rvp_optical_sight_pivot` 的现成套路：

| 文件 | 改动 |
|---|---|
| `src/.../rvp/mixin/WeaponUnitPojoMixin.java` | 新增 `@Unique @SerializedName("rvp_optical_sight_follow_pitch") private boolean ywzj_rvp$opticalSightFollowPitch;` + getter |
| `src/.../rvp/ext/WeaponUnitDataExt.java` | 新增 `boolean ywzj_rvp$opticalSightFollowPitch();` |
| `src/.../rvp/mixin/WeaponUnitDataMixin.java` | 新增 `@Unique private boolean ywzj_rvp$opticalSightFollowPitch;` + 从 Pojo 拷贝 + getter 实现 |

### 3.4 新 Mixin：`WeaponUnitOpticalSightFollowPitchMixin`

**直接照抄 `WeaponUnitOpticalSightPivotMixin` 的骨架**（同一个注入点、同一套 shadow、同一个 ext 判定），只换返回值的计算方式：

```java
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitOpticalSightFollowPitchMixin {
    @Shadow private Vec3 opticalSightOffset;
    @Shadow private VehicleCubeGroup xTurnGroup;

    @Inject(method = "worldOpticalSightPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$applyFollowPitch(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        if (cir.isCancelled()) return;                       // 见 §3.6 优先级
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (!(self.getData() instanceof WeaponUnitDataExt ext) || !ext.ywzj_rvp$opticalSightFollowPitch()) return;
        if (opticalSightOffset == null) return;              // 交给本体走 worldOwnerViewPosition
        Vec3 offsetFromVehicle = self.getPivotOffset().add(opticalSightOffset);
        cir.setReturnValue(self.worldPositionWithGroupRot(offsetFromVehicle, xTurnGroup, partialTick));
    }
}
```

注释里必须写明：**`worldPositionWithGroupRot(offset, xTurnGroup, pt)` 就是本体给 `operator` 用的那一行**（`WeaponUnit.java:1004`），本 Mixin 只是把它独立出来、不再依赖 `optical_sight_type == OPERATOR`。

可用的现成访问器（都已确认）：`PartUnit.getPivotOffset()` 是 **public**；`PartUnit.worldPositionWithGroupRot(...)` 是 **public**；`WeaponUnit.xTurnGroup` / `opticalSightOffset` 可用 `@Shadow`（`WeaponUnitDataMixin` 里已经有 `@Shadow private VehicleCubeGroup xTurnGroup;` 的先例）。

注册：`src/main/resources/ywzj_rvp.mixins.json` 的 **`mixins` 数组**（与 `WeaponUnitOpticalSightPivotMixin` 同一段，common 侧）。

### 3.5 为什么用这个注入点，而不是别的

| 候选注入点 | 评价 |
|---|---|
| **`worldOpticalSightPosition`（HEAD, cancellable）** ✅ | 只影响观瞄相机挂点。**不碰渲染、不碰 OBB/命中箱、不碰发射方向、不碰座位**（`worldSeatPosition` 走 `worldPositionWithSelfRot`，不走本方法）。与 `WeaponUnitOpticalSightPivotMixin` 同点同风格。 |
| `WeaponUnit.updateRot()` 里给 `structureGroup.rotation` 补俯仰 | ❌ **禁止**。`structureGroup.rotation` 会被 OBB 与结构组渲染吃到 ⇒ 炮塔整块跟着俯仰、命中箱转向。 |
| `WeaponUnit.getViewGroupRotation()` | ⚠️ 会被 `worldPositionWithSelfRot` / `worldSeatPosition` 一起吃到 ⇒ **座位落点也会跟着炮管动**，且 `partialTick==1.0F` 分支另走一条路，需要额外判断，容易漏。 |

### 3.6 与 `rvp_optical_sight_pivot` 的互斥

两个 Mixin 都注入 `worldOpticalSightPosition` 的 HEAD 且都 cancellable。Mixin 不会因为前一个 `cancel()` 而自动跳过后续注入点，因此：

- 新 Mixin 首行 `if (cir.isCancelled()) return;` ⇒ **先到先得**。
- 文档与字段注释里写明：**同一武器站不要同时配 `rvp_optical_sight_pivot` 与 `rvp_optical_sight_follow_pitch`**；若同时配置，行为取决于 mixin 注册顺序，不保证。
- 如需严格优先级，可用 `@Inject` 的 `priority` 显式排序（当前不建议引入，保持两个功能正交即可）。

---

## 四、改动清单（文件级）

| # | 文件 | 动作 | 说明 |
|---|---|---|---|
| 1 | `src/.../rvp/mixin/PartUnitTypeScopeCompatMixin.java` | 改 | `normalizeScopeConfig` 增加 `crt_ui_operator` 分支 |
| 2 | `src/.../rvp/mixin/WeaponUnitPojoMixin.java` | 改 | 新增 `@Unique` 字段 + getter |
| 3 | `src/.../rvp/ext/WeaponUnitDataExt.java` | 改 | 新增 getter 声明 |
| 4 | `src/.../rvp/mixin/WeaponUnitDataMixin.java` | 改 | 新增 `@Unique` 字段 + 拷贝 + 实现 |
| 5 | `src/.../rvp/mixin/WeaponUnitOpticalSightFollowPitchMixin.java` | **新增** | 见 §3.4 |
| 6 | `src/main/resources/ywzj_rvp.mixins.json` | 改 | `mixins` 数组加一行 |
| 7 | `data/rvp/vehicles/052d.json` | 改 | `weapon2.optical_sight_type`: `"operator"`（现为试水值）→ `"crt_ui_operator"` |

**不需要改**：两份 `renderCrosshair`、`RVP_ClientEvents.applyScopeOverrides`、`RVP_ProjectileSpawner`、`RVP_ScopeViewSyncClient`、任何结构模型 / 渲染模型 / 动画。

---

## 五、备选方案对照（记录取舍过程）

| 方案 | 做法 | 代价 / 风险 | 结论 |
|---|---|---|---|
| A | 直接 `operator` | HUD 只剩小点，主炮等于盲射 | ❌ 只能临时试水 |
| B | 并成单骨件（`structure_bone` 指 `_barrel` 骨） | 052D 全部 `_barrel` 的 pivot 与父骨差 15~31 格 ⇒ 偏航会绕炮耳轴画圈；`weapon3/4_barrel` 各 6 cube 还会退成单管 | ❌ 不可行 |
| C | 注入 `getViewGroupRotation` | 座位落点被动、pt 分支易漏 | ⚠️ 次优 |
| Z | `operator` + 新字段 `rvp_force_crt_hud`，改两份 overlay 判定 | 不用新 Mixin，但要改 `RVP_ScopeOverlay.renderCrosshair` 与 `RVP_ClientEvents.applyScopeOverrides`；**没配 `ui_preset` 的载具仍会只画小点**（本体 overlay 未被取消） | ⚠️ 可行但不一致 |
| **X** | **`crt` + `rvp_optical_sight_follow_pitch`（推荐）** | 需 1 个新 Mixin + 1 个新字段；HUD/滤镜天然正确，与 `ui_preset` 有无无关 | ✅ **推荐** |

---

## 六、验证步骤

1. 构建 RVP（先试 `--offline`；判据 = `compileJava` BUILD SUCCESSFUL + 测试 XML 无 failures/errors）。
2. 数据包改动**不用重打 jar，但要重启游戏**。
3. 进 052D 座位 2（HPJ-38），把炮管从最低压到最高：
   - 镜头基准点应随炮管一起抬（这是 `operator` 已验证过的效果）；
   - **准星十字线、距离 `m`、倍率 `x`、稳定器提示、焦点锁定框应全部还在**（这是 A 方案丢失的部分）；
   - 不应有 CRT 扫描线滤镜（`crt_ui` 的语义 = 关后处理）。
4. 对照座位 1 / 3 / 4（仍为 `crt_ui`）：它们画面正常但**镜头不随俯仰**，用来确认差异只来自新字段。
5. 出弹点回归：`weapon2` 仍应单管出弹、`weapon3/4` 仍是 6 管循环（本方案不碰 `bolts`）。

## 七、风险与回退

- **风险**：新增 Mixin 与 `WeaponUnitOpticalSightPivotMixin` 同注入点（已用 `isCancelled` 规避）；`@Shadow` 依赖 refmap，构建失败时检查 `ywzj_rvp.refmap.json` 是否刷新。
- **回退（数据侧）**：`cp ywzj_rvp/_bak/052d.json.bak_20261002a limitless_vehicle/rvp/data/rvp/vehicles/052d.json`（`weapon2` 回到 `crt_ui`）。
- **回退（代码侧）**：`git checkout` 相关 6 个文件 + 删除新 Mixin 与其在 `mixins.json` 的注册。
