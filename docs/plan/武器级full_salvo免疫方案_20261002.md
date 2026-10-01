# 武器级 `full_salvo` 免疫（`rvp_full_salvo_immune`）实施方案

> 状态：**已实施**（2026-10-02 代码落地，含两处按本体实际声明修正：按键直接引用 AllKeys 而非 @Shadow、InputHandler Mixin 入 client 数组）。数据侧已先行落地（见 §六）。
> 约束：本体 `ywzj_vehicle` **只读永不改**，全部改动落在 RVP。

---

## 一、需求

DDG51 座位 1（`weapon0`）同时有：
- **MK-15 密集阵 ×2 站**（`weapon0` 前 +  `weapon2` 后），要求**一个武器、一个键、两门同时开火**；
- **RIM-161** 与 **ESSM** 两个垂发，要求**能单独选弹发射**（滚轮切、左键打）。

---

## 二、现状机制：为什么纯数据无解

站级 `"firing_mode": "full_salvo"` 时，客户端开火分发是**白名单**：

`InputHandler.handleShoot()`（本体 `vehicle/control/InputHandler.java:292`）

```java
if (MAIN_WEAPON_SHOOT.isDown()) {                                  // 293
    if (vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit weaponUnit) {
        if (weaponUnit.getFiringMode() == FULL_SALVO) {            // 295
            for (AbstractVehicleWeapon<?> weapon : weaponUnit.fullSalvoWeapons) {
                weapon.doClientShoot();                            // 297  ← 只遍历名单
            }
        } else {
            weaponUnit.getCurrentWeapon().ifPresent(...);          // 300
        }
    }
}
if (SECONDARY_WEAPON_SHOOT.isDown()) { ... }                       // 306 副武器：独立分支
```

⇒ `full_salvo` 分支**完全不读 `getCurrentWeapon()`**。推论：

1. **名单外的武器在该站哑火**（不是误射）——滚轮切过去按左键也发不出去；
2. 把垂发也标 `full_salvo: true` ⇒ 会被齐射**一起打**，且按住左键会按各自射速**持续发射直到打光**。

⇒ 「密集阵齐射」与「垂发可在主槽单独发射」在**纯数据下互斥**，必须改代码。

---

## 三、字段设计

**`rvp_full_salvo_immune`**（bool，缺省 `false`）

| 项 | 说明 |
|---|---|
| 位置 | `weapons` 数组的**条目**上，与 `full_salvo` / `secondary` / `part_unit_id` **同层** |
| 语义 | 该条目**不受所在站 full_salvo 机制接管**，走常规「当前选中即发射」路径 |
| 生效范围 | 仅主武器分支；副武器分支（按 2 / 按 1）本就不看 `firing_mode`，标了也无副作用 |

---

## 四、目标交互（DDG51 座位 1）

| 当前选中 | 实际走的路径 | 结果 |
|---|---|---|
| MK-15 密集阵 | 遍历 `fullSalvoWeapons` | 前 + 后两门**同时**开火 |
| RIM-161 | 常规路径 | **只**发射 RIM-161 |
| ESSM | 常规路径 | **只**发射 ESSM |

---

## 五、实现方案

### 5.1 文件清单

| # | 文件 | 动作 |
|---|---|---|
| 1 | `src/main/java/org/ywzj/rvp/ext/WeaponInfoExt.java` | **新增**接口：`boolean ywzj_rvp$fullSalvoImmune();` |
| 2 | `src/main/java/org/ywzj/rvp/mixin/WeaponInfoMixin.java` | **新增**。`@Mixin(WeaponInfo.class)`：`@Unique @SerializedName("rvp_full_salvo_immune") private boolean ...` + 实现 getter |
| 3 | `src/main/java/org/ywzj/rvp/mixin/InputHandlerFullSalvoImmuneMixin.java` | **新增**。注入 `handleShoot`（见 §5.4） |
| 4 | `src/main/resources/ywzj_rvp.mixins.json` | **改**：`mixins` 数组加上述两个 Mixin 类名 |

> 可选（若不想每次开火查表）：再加 5. 给 `AbstractVehicleWeapon` 挂 `@Unique boolean` 标记位，在 `WeaponUnit.combineAndInit` 的 TAIL 注入里按 index 对齐写入。**推荐先做简单版（按 index 查表）**，性能足够（一次开火一次查询）。

### 5.2 字段为什么落在 `WeaponInfo`

`full_salvo` 定义在 `org.ywzj.vehicle.vehicle.pojo.WeaponInfo`（`WeaponUnitPojo.weapons` 列表的元素）。
⚠️ RVP 现有的 `WeaponUnitPojoMixin` 作用于**最外层** `WeaponUnitPojo`，**管不到列表元素** ⇒ 必须单独 mixin `WeaponInfo`。

写法照抄 `rvp_optical_sight_follow_pitch` 的既有套路：`@Unique` + `@SerializedName` + 独立 `Ext` 接口 + getter。
⚠️ 用原始类型 `boolean`（Gson 反序列化走 Unsafe 分配，字段初始化器不执行，原始类型"未配置"落 `false`，正好是我们要的默认关闭）。

### 5.3 武器实例 → 字段的对应关系

- `AbstractVehicleWeapon.getIndex()` 是 **public**（`AbstractVehicleWeapon.java:429`）
- `WeaponUnit.indexedWeapons` 与 `WeaponUnitData.weapons`（`WeaponInfo` 列表）**同序**：`combineAndInit`（`WeaponUnit.java:245`）里每 push 一次武器就 `index += 1`
- ⇒ 可按 index 对齐反查：

```java
List<WeaponInfo> infos = ((WeaponUnitData) wu.getData()).getWeapons();
int i = cur.getIndex();
if (i >= 0 && i < infos.size() && infos.get(i) instanceof WeaponInfoExt ext && ext.ywzj_rvp$fullSalvoImmune()) { ... }
```

### 5.4 注入点：`InputHandler.handleShoot`（必须）

```java
@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerFullSalvoImmuneMixin {

    @Shadow private static KeyMapping MAIN_WEAPON_SHOOT;
    @Shadow private static KeyMapping SECONDARY_WEAPON_SHOOT;

    @Inject(method = "handleShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$immuneFullSalvo(AbstractVehicle vehicle, LocalPlayer player, CallbackInfo ci) {
        // 接管条件卡到最窄：不满足直接 return，走本体原逻辑
        if (!MAIN_WEAPON_SHOOT.isDown()) {
            return;
        }
        if (!(vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit weaponUnit)) {
            return;
        }
        if (weaponUnit.getFiringMode() != WeaponUnitData.FiringMode.FULL_SALVO) {
            return;
        }
        AbstractVehicleWeapon<?> current = weaponUnit.getCurrentWeapon().orElse(null);
        if (current == null || !ywzj_rvp$isImmune(weaponUnit, current)) {
            return;
        }
        // 免疫武器：只发它自己
        current.doClientShoot();
        // ci.cancel() 会跳过本体的副武器分支 ⇒ 这里补上（照抄本体 306-311 行语义）
        if (SECONDARY_WEAPON_SHOOT.isDown()) {
            weaponUnit.getCurrentSecondaryWeapon().ifPresent(AbstractVehicleWeapon::doClientShoot);
        }
        ci.cancel();
    }
}
```

- `@Shadow` 的字段可见性以本体实际声明为准（`MAIN_WEAPON_SHOOT` / `SECONDARY_WEAPON_SHOOT` 是 `InputHandler` 的静态按键字段）。
- 参数类型 `AbstractVehicle` / `LocalPlayer` 必须与本体一致，否则 `@Inject` 匹配不到。
- ⚠️ **不要在 `@At("HEAD")` 里做任何状态修改**（如改 `fullSalvoWeapons` 列表）—— 只做「读 + 接管 + cancel」，避免污染本体状态。

---

## 六、数据侧（本 agent 已落地 ✅）

`limitless_vehicle/rvp/data/rvp/vehicles/ddg51.json` 的 `weapon0` 站：

```json
"firing_mode": "full_salvo",
"weapons": [
  { "id": "rvp:ddg51_mk15", "part_unit_id": "weapon0", "save_id": "ddg51_mk15_fwd", "full_salvo": true },
  { "id": "rvp:ddg51_mk15", "part_unit_id": "weapon2", "save_id": "ddg51_mk15_aft", "full_salvo": true },
  { "part_unit_id": "rim161", "weapon_bay_unit_id": "rim161_bay", "rvp_full_salvo_immune": true },
  { "part_unit_id": "essm",   "weapon_bay_unit_id": "essm_bay",   "rvp_full_salvo_immune": true }
]
```

⚠️ **在 §五 的代码生效之前，游戏里这两个垂发是「哑火」状态**（在 `full_salvo` 站里、又不在名单内）。若不打算马上实施代码，请先把数据回退成 `"secondary": true`（见 §八）。

---

## 七、验证步骤

1. 构建 RVP（先试 `--offline`；判据 = `compileJava` BUILD SUCCESSFUL + 测试 XML 无 failures/errors）。
2. 数据包改动**不用重打 jar，但要重启游戏**。
3. 进 DDG51 座位 1：
   - 滚轮选密集阵 → 按左键：**前后两门同时开火**；
   - 滚轮选 RIM-161 → 按左键：**只发射 RIM-161**（不再哑火）；
   - 滚轮选 ESSM → 同上；
   - 按住左键：垂发应按自身 `shoot_interval`/`max_capacity` 节奏发射，**不会**带出密集阵。
4. **回归（必做）**：随便开 052D / 一辆坦克 / 一架飞机，确认开火行为与改动前**完全一致**（接管条件未命中 ⇒ 必须走本体原逻辑）。

---

## 八、边界与回退

**边界**
- 同一站**多个免疫武器**：各自独立，互不影响。
- **非 `full_salvo` 站**标免疫：无效果（本来就走常规路径）。
- 某条目**同时**标了 `full_salvo: true` 与 `rvp_full_salvo_immune: true`：**以免疫优先**（选中它时只发它）。建议不要在数据里同时标，避免歧义。
- 副武器标免疫：无效果，无副作用。

**回退**
- 代码：`git checkout` 上述 3 个文件 + `ywzj_rvp.mixins.json`。
- 数据：把两个垂发条目改回 `"secondary": true` 并删除 `rvp_full_salvo_immune`（或恢复备份）。
