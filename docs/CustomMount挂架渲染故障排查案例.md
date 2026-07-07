# CustomMount 挂架渲染故障排查案例

> **适用场景**：RVP 自定义挂架（`rvp_custom_mounts`）配置正确但游戏中挂架不渲染  
> **案例来源**：阵风（Rafale）AASM 挂架不显示问题排查  
> **日期**：2026-07-08

---

## 一、问题现象

阵风战机的 AASM 挂架配置完整（`rvp_custom_mounts` 4 条、parts 3 个、weapon JSON 和 display JSON 均存在），但进入游戏后挂架完全不渲染，而 J15 的同类挂架正常。

---

## 二、排查链路（按优先级排序）

### 坑位 1：挂架模型加载失败（**本案例根本原因**）

**现象**：调试日志显示 `render SKIP: attachmentModel null`

**根因**：`ClientAssetsManager` 在资源重载完成后会 **清空原始模型 POJO 缓存**（`models.clearData()`，见 `ClientAssetsManager.java:75`）。`getAttachmentModel` 按以下顺序查找模型：

1. `getDecorationDisplay(id)` → 持久化的装饰 display 模型
2. `getWeaponDisplay(id)` → 持久化的武器 display 模型
3. `getModel(id)` → 原始模型 POJO（**重载后被清空，运行时不可用**）

如果挂架模型 ID 没有被任何 **decoration display** 或 **weapon display** 注册，则三个路径全部返回空，模型加载失败。

**易错点**：
- 挂架模型 JSON 文件（如 `weapon_mount_aasm.json`）存在于 `models/bedrock/entity/` 目录 **不代表运行时可用**
- 必须同时创建对应的 **decoration display 配置文件**，才能让 display manager 在重载时持久化该模型

**正确做法**：每个挂架模型必须创建对应的 decoration display 文件：

```
路径：assets/rvp/display/decoration/entity/weapon_mount_aasm.json
内容：
{
  "type": "ywzj_vehicle:weapon",
  "model": "rvp:entity/weapon_mount_aasm",
  "texture": "rvp:textures/entity/weapon_mount/pylon_aasm.png",
  "description": "",
  "tab_index": 0
}
```

**对照参考**：J15 的 4 个挂架模型（`weapon_mount_pl12`、`weapon_mount_pl15`、`weapon_mount_yj91`、`weapon_mount_kd88a`）全部有对应的 decoration display 文件，所以正常工作。

---

### 坑位 2：`structure_bone` 命名约定违反

**现象**：挂架 PartUnit 的 `xTurnGroup` 为 null 或 bolts 为空

**根因**：本体 `WeaponUnitData.initStructureModel()` 的骨骼查找逻辑：

```java
// yTurnBone = model.getBoneMap().get(this.structureBone)
// xTurnBone = model.getBoneMap().get(this.structureBone + "_barrel")  ← 自动追加 _barrel！
```

- `structure_bone` 必须使用**基础骨骼名**（如 `variable_agm_1`），本体会自动拼接 `_barrel` 查找俯仰骨骼
- 如果误写为 `variable_agm_1_barrel`，本体会查找 `variable_agm_1_barrel_barrel`，找不到则 xTurnGroup 退化

**易错点**：
- 将 `structure_bone` 写成 barrel 骨骼全名（如 `variable_agm_1_barrel`）
- J15 正确用法：`structure_bone: "variable_agm_1"`，本体自动找到 `variable_agm_1_barrel`

**正确做法**：

| PartUnit | structure_bone 值 | 说明 |
|----------|-------------------|------|
| 武器控制器 | `variable_agm_1` | 基础名，本体找 `_barrel` 后缀 |
| 挂架 mount_1 | `variable_agm_1` | 同上 |
| 挂架 mount_2 | `variable_agm_2` | 第二个挂架的基础名 |

---

### 坑位 3：`ammo_capacity` 覆盖武器个体容量

**现象**：弹药数量显示异常（如固定为 6 发而非各武器独立容量）

**根因**：`AbstractVehicleWeapon.getMaxCapacity()` 的逻辑：

```java
return weaponUnit.getAmmoCapacity() != -1 
    ? weaponUnit.getAmmoCapacity()    // 使用 PartUnit 的 ammo_capacity
    : this.getData().getMaxCapacity(); // 使用武器自身的 max_capacity
```

如果 PartUnit 的 `ammo_capacity` 设为正数（如 6），会**覆盖**所有挂载武器的 `max_capacity`。

**正确做法**：当同一 PartUnit 挂载多种不同容量的武器时，设置 `ammo_capacity: -1` 让每个武器使用自身的 `max_capacity`。

---

### 坑位 4：Mixin `@Shadow` 不能访问父类字段

**现象**：游戏启动崩溃，错误 `@Shadow field structureBone was not located in the target class WeaponUnitData`

**根因**：`@Shadow` 只能 shadow **目标类自身声明的字段**，不能访问继承自父类的字段。`structureBone` 声明在 `PartUnitData`（祖父类）中，`WeaponUnitData` 并无此字段的直接声明。

**正确做法**：通过 `@Accessor` 在父类的 Mixin 接口中暴露该字段：

```java
// PartUnitDataAccessor.java
@Mixin(value = PartUnitData.class, remap = false)
public interface PartUnitDataAccessor {
    @Accessor("structureBone")
    String getStructureBone();
}

// WeaponUnitDataMixin.java 中使用
String structureBone = ((PartUnitDataAccessor)(Object)this).getStructureBone();
```

---

### 坑位 5：`rvp_structure_bolt_bones` 多挂点 Bolt 补充

**现象**：多挂点武器只有第一个挂点有 Bolt，导致挂架渲染位置偏移到单侧

**根因**：本体 `initStructureModel` 只为 xTurnBone（`structureBone + "_barrel"`）构建一个 Bolt。对于左右两侧挂架（如 `variable_agm_1_barrel` + `variable_agm_2_barrel`），需要额外为第二个挂点补充 Bolt。

**正确做法**：在 `rvp_structure_bolt_bones` 中列出所有挂点骨骼名，`WeaponUnitDataMixin.ywzj_rvp$initExtraBoltBones` 会自动跳过本体已处理的 xTurnBone，为其余骨骼计算偏移并追加 Bolt：

```json
{
  "id": "variable_agm",
  "structure_bone": "variable_agm_1",
  "rvp_structure_bolt_bones": ["variable_agm_1_barrel", "variable_agm_2_barrel"]
}
```

---

## 三、排查流程速查表

| 步骤 | 检查项 | 工具/方法 |
|------|--------|----------|
| 1 | 开启调试：`/rvpdebug custommount on` | 游戏内命令 |
| 2 | 查看日志 `logs/custommountdebug.log` | 文本编辑器 |
| 3 | 日志有 `attachmentModel null`？→ 检查 decoration display 文件是否存在且 ID 匹配 | 对照 J15 的 display 目录 |
| 4 | 日志有 `resolveAttach FAIL: xTurnGroup=NULL`？→ 检查 `structure_bone` 是否用了基础名 | 参照坑位 2 |
| 5 | 日志有 `resolveAttach FAIL: bolts=EMPTY`？→ 检查 rvp_structure_bolt_bones 和结构模型骨骼 | 参照坑位 5 |
| 6 | 日志有 `resolved not found`？→ 检查 resolveMounts 中 weaponId 匹配逻辑 | 确认当前选中武器 ID |
| 7 | 弹药数量异常？→ 检查 ammo_capacity 设置 | 参照坑位 3 |

---

## 四、新增挂架配置 Checklist

添加新载具的自定义挂架时，确保以下文件全部就位：

- [ ] **挂架模型 JSON**：`assets/rvp/models/bedrock/entity/weapon_mount_xxx.json`
- [ ] **挂架贴图**：`assets/rvp/textures/entity/weapon_mount/pylon_xxx.png`
- [ ] **decoration display**：`assets/rvp/display/decoration/entity/weapon_mount_xxx.json`（⚠️ 最易遗漏！）
- [ ] **武器 display**：`assets/rvp/display/weapon/xxx.json`
- [ ] **武器数据**：`data/rvp/weapons/xxx.json`
- [ ] **载具 JSON** 中 `rvp_custom_mounts` 条目完整（`part_unit_id`、`attach_part_unit_id`、`weapon_id`、`model`、`texture`、`rack_bones`、`missile_bones`）
- [ ] **载具 JSON** 中 `parts` 条目：`structure_bone` 使用基础名、`rvp_structure_bolt_bones` 列出所有挂点
- [ ] **结构模型** 中对应骨骼存在（barrel 骨骼为顶层骨骼，无 parent 字段）
