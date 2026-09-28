# M1AGDS（防空战车）模型层级结构梳理

本文档用于记录 `m1agds` 当前在载具包中的渲染模型、结构模型层级关系，便于后续配置动画、武器站、履带、负重轮、雷达、导弹、烟幕等逻辑。文末附「还需要补充的文件清单」与「待确认项」。

> 解析基于 2026-09-28 的模型文件（渲染模型 97 骨 / 结构模型 20 骨 / 动画 3 个）。
> 骨骼树可用 `scripts/parse_bone_tree.py <模型json路径>` 随时重新生成。
> 蓝本：**M1A2SEP**（同底盘 → 部件/物理/命中表/履带）、**CSSA5 + PS1SM**（双雷达防空 → 雷达站/火控/UI 预设）。

---

## ⚠️ 先记住两条前提（本文档多处依赖）

1. **结构模型的骨不等于渲染模型的骨。** 本体只把「被某个 part 的 `structure_bone` 命中」的骨收进命中箱集合（`BaseVehicleData` 里 `vehicleOBBs.addAll(partUnitEntry.data().getRawPartCubeOBBs())`，约 L131），而渲染骨只服务于视觉。**结构模型里完全允许存在没有对应渲染骨的骨**（如本例的 `lock_radar`、两根烟幕骨），也允许渲染模型里存在没有结构骨的骨（如 `fzl`/`lvdai`）。
2. **M1AGDS 的跟踪雷达没有独立渲染骨。** 它做成**固定式、整合进炮塔几何**（几何在渲染模型 `$weapon0` 的 poly_mesh 里），因此**不需要** `part_bindings`、也不会有独立的外观旋转。只有**搜索雷达**有可动的渲染骨（`radar_base` 基座 + `radar` 旋转阵面）。

---

## 一、文件位置与现状

### 1.1 已备好的资源

| 类别 | 路径 |
| --- | --- |
| 渲染模型 | `assets/rvp/models/bedrock/entity/m1agds.json`（97 骨） |
| 结构模型 | `data/rvp/models/bedrock/vehicle/m1agds.structure.json`（20 骨，`format_version 1.21.0`） |
| 贴图 | `assets/rvp/textures/entity/m1agds.png` |
| 槽位贴图 | `assets/rvp/textures/slot/m1agds.png` |
| 动画 | `assets/rvp/animations/bedrock/entity/m1agds.animation.json`（`tread_r_move` / `radar_turn_off` / `auto_cannon`） |
| 弹药 | `assets/rvp/models/bedrock/entity/ammo/missile_mim146.json` + `assets/rvp/textures/entity/ammo/missile_mim146.png` |
| 武器 | `data/rvp/weapons/m1agds_kda35.json`（`rvp:machinegun`）、`data/rvp/weapons/m1agds_mim146.json`（`rvp:missile`） |

### 1.2 还需要补的文件（9 项）

| # | 文件 | 说明 |
| --- | --- | --- |
| 1 | `data/rvp/vehicles/m1agds.json` | ★ 主配置，见 §五～§十 |
| 2 | `assets/rvp/display/vehicle/m1agds.json` | 见 §七.1 |
| 3 | `assets/rvp/animation_controllers/m1agds_controller.json` | 见 §七.2 |
| 4 | `assets/rvp/scripts/m1agds.js` | 见 §七.3 |
| 5 | `assets/rvp/display/weapon/m1agds_kda35.json` | 机炮 → 只需 `sounds`（照 `mbt_127.json` / `cssa5_ahead.json`），内容见 §七.4 |
| 6 | `assets/rvp/display/weapon/m1agds_mim146.json` | 导弹 → `model: rvp:entity/ammo/missile_mim146` + 对应贴图，内容见 §七.4 |
| 7 | `data/rvp/recipes/m1agds.json` | 打印配方，`YwzjVehicleId: rvp:m1agds` |
| 8 | `assets/rvp/lang/zh_cn.json` + `en_us.json` | 各加 `entity.rvp.m1agds` 与 `rvp.m1agds` |
| 9 | `assets/rvp/sounds.json` | 加 KDA35 开火事件（用户已放入 `sounds/weapon/kda_35_snd_2.ogg`，**尚未注册**），见 §七.4 |

**不需要新建**：`ui_preset` 文件（复用现成的 `ps1sm`，见 §七.1）、弹药、槽位贴图。

### 1.3 落盘方式（用户批注 #14）

> **以 `data/rvp/vehicles/m1a2sep.json` 为蓝本复制后改**，不要从 0 手写 —— 同底盘，部件/物理/命中表/履带/烟幕可直接沿用，成功率高。
> 需要**删掉** m1a2sep 有而 m1agds 没有的部分：全部 `ERA*`、全部 `aps_*`（含 4 个 APS 雷达 + 2 个发射器）、`turret_machine_gun`、`commander_machine_gun`（本车结构模型里没有这些骨）。
> 需要**改/加**的部分见 §五～§十。

---

## 二、结构模型层级（20 骨）

```
bone                                        (根，无 cube)
├─ vehicle_body                             [cube:1 pivot:[0, 0, -8.675]]
│  ├─ track                                 [cube:2 pivot:[0, 0, 0]]
│  ├─ Engine                                [cube:1 pivot:[0, 0, 0]]
│  ├─ Upper_front                           [cube:2 pivot:[0, 0, 0]]
│  ├─ Lower_front                           [cube:1 pivot:[0, 0, 0]]
│  ├─ Periscope                             [cube:1 pivot:[0, 0, 0]]
│  └─ virtual                               [cube:1 pivot:[0, 0, -8.675]]   ← 物理体
└─ turret                                   [cube:2 pivot:[-0.0698, 23.4869, 0.2221]]
   ├─ lock_radar                            [cube:1 pivot:[9, 27, 32]]
   ├─ scan_radar                            [cube:1 pivot:[0, 56, -43]]
   ├─ turret_r / turret_l / turret_back / turret_top
   │                                        [cube:1 pivot:[-0.0054, 24.8414, 0.0327]]
   ├─ turret_barrel                         [cube:2 pivot:[-0.1589, 32.4999, 13.5912]]
   ├─ turret_smoke_grenade_r_barrel         [cube:1 pivot:[29.95, 37.6375, 16.7625]]
   ├─ turret_smoke_grenade_l_barrel         [cube:1 pivot:[-31.05, 37.6375, 16.7625]]
   └─ missile                               [cube:1 pivot:[-0.0698, 23.4869, 0.2221]]
      └─ missile_barrel                     [cube:2 pivot:[0, 35, -47]]
```

### 2.1 逐骨明细（含 cube 尺寸，单位 1/16 格）

| 骨 | parent | pivot | cube（origin / size） | 用途 |
| --- | --- | --- | --- | --- |
| `bone` | — | `[0,0,0]` | 无 | 虚拟根（照 m1a2sep 保留，无碰撞） |
| `vehicle_body` | bone | `[0,0,-8.675]` | `[-29.875,5,-35.25] / [59.7,20.4,65.575]` | 车体侧面命中箱 |
| `track` | vehicle_body | `[0,0,0]` | `[-28.075,0,-68.25]/[9.7,20.4,118.575]`、`[18.925,0,-68.25]/[9.7,20.4,118.575]` | 左右履带命中箱（2 cube） |
| `Engine` | vehicle_body | `[0,0,0]` | `[-28.875,5,-70.25] / [57.7,22.4,35.575]` | 发动机舱命中箱 + `ENGINE` 模块（**与 m1a2sep 完全同尺寸**） |
| `Upper_front` | vehicle_body | `[0,0,0]` | `[-29.875,13,28.75]/[59.7,4.4,28.575]`、`[-29.875,11,24.75]/[59.7,8.4,34.575] rot(-10,0,0)` | 首上 |
| `Lower_front` | vehicle_body | `[0,0,0]` | `[-29.8,5,28.75] / [59.5,8.4,33.575]` | 首下 |
| `Periscope` | vehicle_body | `[0,0,0]` | `[-12.875,20.5,25.75] / [24.7,4.4,17.575]` | 潜望镜 |
| `virtual` | vehicle_body | `[0,0,-8.675]` | `[-29,0,-69.675] / [58,25,133]` | **`physics_only_bone`**，不写进任何命中表 |
| `turret` | bone | `[-0.0698,23.4869,0.2221]` | `[-26.4,24.45,-45.675]/[53.7,16.275,72.825]`、`[-27,28.45,-11.675]/[5.7,11.275,10.825]` | 炮塔（偏航骨） |
| `lock_radar` | turret | `[9,27,32]` | `[6,27,26] / [13,12,7]` | **跟踪雷达**（固定，整合进炮塔） |
| `scan_radar` | turret | `[0,56,-43]` | `[-7,42,-48] / [15,25,10]` | **搜索雷达**（可旋转） |
| `turret_r` | turret | `[-0.0054,24.8414,0.0327]` | `[27.6,28.45,-50.675]/[1.7,11.275,57.825]` | 炮塔右侧 |
| `turret_l` | turret | 同上 | `[-28,28.45,-50.675]/[1.7,11.275,57.825]` | 炮塔左侧 |
| `turret_back` | turret | 同上 | `[-26.4,28.45,-50.675]/[53.7,11.275,2.825]` | 炮塔尾舱 |
| `turret_top` | turret | 同上 | `[-24.4,36.45,-43.675]/[49.7,4.4,68.825]` | 炮塔顶部 |
| `turret_barrel` | turret | `[-0.1589,32.4999,13.5912]` | `[2.225,31.075,13.325]/[2,2,57]`、`[-4.025,31.075,13.325]/[2,2,57]` | **双管 35mm 机炮**（俯仰骨；2 cube → 2 个出弹点，左右交替） |
| `turret_smoke_grenade_l_barrel` | turret | `[-31.05,37.6375,16.7625]` | `[-33.425,35.8,14.225]/[4.75,3.675,5.075]` | 左烟幕发射器 |
| `turret_smoke_grenade_r_barrel` | turret | `[29.95,37.6375,16.7625]` | `[27.575,35.8,14.225]/[4.75,3.675,5.075]` | 右烟幕发射器 |
| `missile` | turret | `[-0.0698,23.4869,0.2221]`（**与 turret 同点**） | `[-1.0698,25.4869,-0.7779] / [2,2,2]` | 导弹发射箱的**偏航骨**（pivot 与炮塔重合 → 只给俯仰，不给偏航） |
| `missile_barrel` | missile | `[0,35,-47]` | `[-26.025,30.075,-46.925]/[8,11,42.25]`、`[15.475,30.075,-46.925]/[8,11,42.25]` | **导弹发射箱俯仰骨**（左右两只 → 2 个出弹点） |

### 2.2 出弹点自动生成规则（无需手写 `bolts`）

本体按 `part.structure_bone + "_barrel"` 找到锚定骨，再把该骨的**每颗 cube 生成一个 bolt**：

| part | 锚定骨 | cube 数 | 出弹点数 | 效果 |
| --- | --- | --- | --- | --- |
| `turret` | `turret_barrel` | 2 | **2** | 双管机炮左右交替开火 |
| `missile` | `missile_barrel` | 2 | **2** | 左右两个发射箱交替出弹 |
| `turret_smoke_grenade_l/r` | 同名 `_barrel` | 1 | 各 1 | 左右烟幕各一个出弹点 |

> `Structure_bone` **必须填不带 `_barrel` 的名字**（如 `turret`、`missile`、`scan_radar`）——数据层会自己拼 `_barrel`。填成 `turret_barrel` 会拼出 `turret_barrel_barrel` → 取不到 → 整站被静默跳过（fa18f 踩过的坑）。

---

## 三、渲染模型层级（97 骨）

```
$body                                   (根)  车体
$weapon0                                (根)  pivot = 结构 turret 的 pivot
├─ $weapon0_0                           炮塔俯仰骨（pivot = 结构 turret_barrel）
│  └─ $weapon0_1                        双管炮管几何 → auto_cannon 后坐动画
├─ $weapon0_2                           pivot = 结构 missile_barrel（导弹发射箱）
└─ radar_base                           radar_turn_off 折叠动画
   └─ radar                             搜索雷达旋转阵面
fzl                                     (根)  负重轮组
└─ $track_roller0 ~ $track_roller8      9 个负重轮（各自含左右两侧几何）
lvdai                                   (根)  履带组
└─ tread_r_0 ~ tread_r_78               79 节履带动画骨
```

### 3.1 主骨明细（其余为履带/负重轮，略）

| 骨 | parent | pivot | 顶点数 | 几何范围 x / y / z | 归属 |
| --- | --- | --- | --- | --- | --- |
| `$body` | — | `[0,0,0]` | 3652 | x[-33.2,33.0] y[4.7,29.5] z[-81.3,63.4] | 车体（含炮塔以下全部） |
| `$weapon0` | — | `[-0.0698,23.4869,0.2221]` | 2801 | x[-33.4,31.0] y[23.8,55.2] z[-69.0,31.3] | 炮塔（含**跟踪雷达几何**） |
| `$weapon0_0` | $weapon0 | `[-0.1589,32.4999,13.5912]` | 66 | x[-6.8,6.4] y[25.3,39.6] z[9.9,31.8] | 炮塔俯仰件 |
| `$weapon0_1` | $weapon0_0 | 同上 | 320 | x[-4.6,4.6] y[30.6,33.5] z[31.8,70.8] | 双管炮管（后坐动画目标） |
| `radar_base` | $weapon0 | `[0,38.8244,-42.8651]` | 86 | x[-3.2,3.2] y[38.2,48.8] z[-44.7,-41.1] | 搜索雷达基座（折叠） |
| `radar` | radar_base | `[-0.0453,48.8043,-42.8635]` | 83 | x[-7.3,7.1] y[48.8,64.3] z[-47.2,-37.4] | 搜索雷达旋转阵面 |
| `$weapon0_2` | $weapon0 | `[0,35,-47]` | 416 | x[-26.7,24.2] y[28.9,40.9] z[-59.5,-5.8] | 导弹发射箱 |
| `fzl` | — | `[0,0,0]` | 0 | — | 负重轮组（纯父骨） |
| `lvdai` | — | `[-0.32238,19.46067,-65.23361]` | 0 | — | 履带组（纯父骨） |

> ⚠️ `$body` / `$weapon0` / `fzl` / `lvdai` **都是根骨（parent = null）**，这是本项目模型的惯例（炮塔与车体各自成根、运行时分别受 `part_bindings` 控制），不要试图把它们接成父子。

---

## 四、结构骨 ↔ 渲染骨 对应表

| 结构骨 | 渲染骨 | 绑定方式 | 备注 |
| --- | --- | --- | --- |
| `turret` | `$weapon0` | `part_bindings` `axis: y`，`invert: true` | 偏航 |
| `turret_barrel` | `$weapon0_0`（俯仰）+ `$weapon0_1`（炮管几何） | `part_bindings` `axis: x`，`invert: false` | 俯仰 |
| `missile` | `$weapon0_2` | `part_bindings` `axis: x`，`invert: false` | 只俯仰（偏航 pivot 与炮塔重合，不给偏航） |
| `scan_radar` | `radar` | `part_bindings` `axis: y`，`invert: false` | 旋转阵面 |
| `scan_radar` | `radar_base` | **switchable_animation `radar_turn_off`** | 折叠/展开，不做 part_binding |
| `lock_radar` | **无** | — | 固定，几何整合在 `$weapon0` |
| `turret_smoke_grenade_l/r_barrel` | **无** | — | 几何在 `$weapon0` 里，静态 |
| `vehicle_body` / `track` / `Engine` / `Upper_front` / `Lower_front` / `Periscope` / `turret_l` / `turret_r` / `turret_back` / `turret_top` | `$body`（履带另见下） | — | 视觉上都是静态几何 |
| `track`（履带） | `lvdai` / `tread_r_*` | `display.track_config` | 走 `tread_r_move` 动画 |
| `track`（负重轮） | `fzl` / `$track_roller0..8` | `special_bindings` `left_wheel_rotation` | 见 §七.2 的注意点 |

---

## 五、部件（parts）规划

> `PartUnitPojo.isSeat` **默认 `true`** —— 除两个观瞄/武器座以外，**每个非座部件都必须显式写 `"is_seat": false`**，否则会凭空多出座位。

| # | part id | type | structure_bone | is_seat | base | 用途 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `turret` | `ywzj_vehicle:weapon` | `turret` | **（默认 true = 座）** | — | 车长/炮手座；主武器槽；`sub_part_unit_ids: ["scan_radar","lock_radar"]` |
| 2 | `missile` | `ywzj_vehicle:weapon` | `missile` | false | `turret` | 导弹箱（实际发射站） |
| 3 | `lock_radar` | `ywzj_vehicle:radar` | `lock_radar` | false | `turret` | 跟踪雷达 |
| 4 | `scan_radar` | `ywzj_vehicle:radar` | `scan_radar` | false | `turret` | 搜索雷达 |
| 5 | `turret_smoke_grenade_l` | `ywzj_vehicle:weapon` | `turret_smoke_grenade_l_barrel` | false | `turret` | 左烟幕 |
| 6 | `turret_smoke_grenade_r` | `ywzj_vehicle:weapon` | `turret_smoke_grenade_r_barrel` | false | `turret` | 右烟幕 |
| 7 | `bone` | `ywzj_vehicle:generic` | `bone` | false | — | 空根（照 m1a2sep） |
| 8 | `vehicle_body` | `ywzj_vehicle:generic` | `vehicle_body` | false | — | 命中箱载体 |
| 9 | `track` | `ywzj_vehicle:generic` | `track` | false | — | 命中箱载体 |
| 10 | `Engine` | `ywzj_vehicle:generic` | `Engine` | false | — | `ENGINE` 模块锚点 |
| 11 | `Upper_front` | `ywzj_vehicle:generic` | `Upper_front` | false | — | 命中箱载体 |
| 12 | `Lower_front` | `ywzj_vehicle:generic` | `Lower_front` | false | — | 命中箱载体 |
| 13 | `Periscope` | `ywzj_vehicle:generic` | `Periscope` | false | — | 命中箱载体 |
| 14–17 | `turret_r` / `turret_l` / `turret_back` / `turret_top` | `ywzj_vehicle:generic` | 同名 | false | — | 命中箱载体 |

**不需要**为 `turret_barrel` / `missile_barrel` 单独建 part —— 它们的 cube 由 `WeaponUnitData` 的 `xTurnGroup` 自动纳入所属武器站的 OBB（m1a2sep 的 `turret_barrel` 命中箱就是这么来的）。

### 5.1 座位与观瞄（`turret` part）

**观瞄位置（用户批注 #12 已给定）**：观瞄 BB 坐标 `[11, 46, 14]`，**减枢轴再除 16**：

```
optical_sight_offset = ([11,46,14] − turret.pivot[-0.0698,23.4869,0.2221]) / 16
                     = [0.6918625, 1.40706875, 0.86111875]
反推校验：pivot/16 + offset = [0.6875, 2.875, 0.875] ＝ bb/16 ✓
```

**座位位置**：用户只给了观瞄坐标，座位**沿用 m1a2sep**（`turret` 结构 pivot 两车几乎重合：`[-0.0698,23.4869,0.2221]` vs `[0,23.2485,0]`）：

```jsonc
"seat_offset":          [-0.5, 1.5, 0.375],       // → bb [-8.07, 47.49, 6.22]
"operator_view_offset": [0, 2.5, 0],              // → bb [-0.07, 63.49, 0.22]
"optical_sight_offset": [0.6918625, 1.40706875, 0.86111875],
"optical_sight_type":   "crt_ui"                  // 批注 #3 确认
```

> 换算规则：`偏移 = (bb坐标 − 该 part 的 structure_bone 在结构模型里的 pivot) / 16`。
> 若座位另有指定 BB 坐标，按同法替换 `seat_offset` / `operator_view_offset` 即可。

### 5.2 旋转限位（`rot_info`）

| part | `x_rot_max`（**俯角**，正=向下） | `x_rot_min`（**仰角**，负=向上） | `x_rot_speed` / `y_rot_speed` |
| --- | --- | --- | --- |
| `turret`（机炮） | **10** | **-50** | **4 / 4**（批注 #2 折中） |
| `missile`（导弹箱） | **0** | **-50** | 3 / 0 |
| `lock_radar` | 85 | 0 | 0 / 0（同 cssa5） |
| `scan_radar` | 90 | 0 | 0 / 0（旋转由 `scan_*` 逻辑驱动） |
| 烟幕 `_l` / `_r` | — | — | 0 / 0 |

> 符号约定：`x_rot_max` = 俯角上限（**正值向下**）、`x_rot_min` = 仰角上限（**负值向上**）。
> 需求：机炮俯仰 −10°(俯) ～ +50°(仰) → `x_rot_max: 10`、`x_rot_min: -50`；导弹只仰不俯（0～50°）→ `x_rot_max: 0`、`x_rot_min: -50`。
> ⇒ **观瞄站与机炮是同一站**（`turret` 既是座位又是武器站），所以不存在 mi28/ah64 那种「准星压得下去、炮打不到」的问题。

---

## 六、武器与出弹点

`turret.weapons`（滚轮切槽）：

```jsonc
"weapons": [
  "rvp:m1agds_kda35",
  { "id": "rvp:m1agds_mim146", "part_unit_id": "missile" }
]
```

- 槽 0 = `rvp:m1agds_kda35`（`rvp:machinegun`，150 发弹匣，`shoot_interval: 20`ms ≈ 20 发/s，`caliber 15.24`）→ 从 `turret_barrel` 的 2 颗 cube 交替出弹
- 槽 1 = `rvp:m1agds_mim146`（`rvp:missile`，LBR 激光架束，8 发，`proximity_radius 5.0` + `require_radar_lock`）→ 从 `missile_barrel` 的 2 颗 cube 交替出弹
- `missile` part 建议 `"ammo_capacity": 8`（照 cssa5）、`"parent_weapon_unit_aim": true`、`"crosshair_style": "none"`

**没有自定义挂架（`rvp_custom_mounts`）** —— 本车武器是固定站，不涉及挂架。

---

## 七、动画与控制器规划

### 7.1 `display/vehicle/m1agds.json`

照 `m1a2sep.json` / `ps1sm.json` 结构：

```jsonc
{
  "type": "ywzj_rvp:tracked_vehicle",
  "bedrock_backend": "rvp",
  "model": "rvp:entity/m1agds",
  "texture": "rvp:textures/entity/m1agds.png",
  "slot_texture": "rvp:textures/slot/m1agds.png",
  "animations": "rvp:entity/m1agds.animation",
  "animation_controller": "rvp:m1agds_controller",
  "track_config": {
    "left_track": "tread_r_move",
    "right_track": "tread_r_move",
    "module_length": 0.25,
    "track_width": 3.0
  },
  "sounds": {
    "engine_start": "ywzj_vehicle:ztz99a_engine_start",
    "engine_idle":  "ywzj_vehicle:ztz99a_engine_idle",
    "engine_run":   "ywzj_vehicle:ztz99a_engine_run",
    "track_run":    "ywzj_vehicle:track_run"
  },
  "description": "",
  "tab_index": 306          // 批注 #1 确认
}
```

**不需要 `special_bone_effects`**：渲染模型里既没有 `sign` 也没有 `turret_muzzle_flash`（这两根是 m1a2sep 独有的）。若日后要加炮口焰，需在渲染模型补一根 `turret_muzzle_flash` 骨。

### 7.2 `animation_controllers/m1agds_controller.json`

结构照 `ps1sm_controller.json`（同为「履带 + 雷达 + 事件动画」组合）：

```jsonc
{
  "name": "m1agds_controller",
  "script": "rvp:m1agds",
  "parameters": {},
  "switchable_animations": {
    "scan_radar": { "part_id": "scan_radar", "animation": "radar_turn_off", "invert": true }
  },
  "event_animations": {
    "turret_fire": ["auto_cannon"]
  },
  "graph": {
    "type": "additive",
    "base": {
      "type": "merge",
      "inputs": [
        {
          "type": "bone_binding",
          "special_bindings": [
            {
              "bones": ["$track_roller0","$track_roller1","$track_roller2","$track_roller3","$track_roller4",
                        "$track_roller5","$track_roller6","$track_roller7","$track_roller8"],
              "source": "left_wheel_rotation",
              "axis": "x",
              "param": 0.375
            }
          ],
          "part_bindings": [
            { "bone": "$weapon0",   "part": "turret",     "rotation_type": "y", "axis": "y", "invert": true  },
            { "bone": "$weapon0_0", "part": "turret",     "rotation_type": "x", "axis": "x", "invert": false },
            { "bone": "$weapon0_2", "part": "missile",    "rotation_type": "x", "axis": "x", "invert": false },
            { "bone": "radar",      "part": "scan_radar", "rotation_type": "y", "axis": "y", "invert": false }
          ]
        },
        { "type": "track_animation" },
        { "type": "script", "function": "updateBones" },
        { "type": "switchable_animation", "ref": "scan_radar" }
      ]
    },
    "add": { "type": "event_animation" }
  }
}
```

> ⚠️ **`lock_radar` 不写任何 `part_binding`**（无渲染骨）。
> ⚠️ `event_animations` 的键 = `<武器站 part id>_fire` → 这里是 `turret_fire`（值是**动画名** `auto_cannon`，不是 part 名）。

**关于负重轮的一个坑（实测代码结论）**：`special_bindings` 最终走 `BoneBindingNode.OptimizedPoseBuilder.setRotation()`，它是**覆盖赋值**，因此**同一根骨写两条（左+右）时只有后一条生效**。`ps1sm` 把 `$track_roller0..10` 同时绑了 `left_wheel_rotation` 和 `right_wheel_rotation`，实际只有**右侧**生效；`m1a2sep` 只绑了左侧。本车 `$track_roller0..8` 的几何**同时包含左右两侧**（x[-28.9,+28.4] 跨中），所以只能二选一 —— 建议**照 m1a2sep 只绑 `left_wheel_rotation`**，不要照抄 ps1sm 的双绑。
抄m1a2吧

### 7.3 `scripts/m1agds.js`

最简形态即可（本车没有 ERA、也没有 `_hurt` 骨）：

```javascript
function updateBones(context) {
    return createPoseBuilder();
}
```

> 若希望「炮管被 BARREL 模块打坏时隐藏炮管」，渲染模型需要先补一根受损替换骨（m1a2sep 用的是 `$weapon0_1_hurt`）；本车暂无该骨，故脚本保持空实现。

### 7.4 `display/weapon/*.json` + `sounds.json`

**① 先把用户新加的 KDA35 音效注册进 `assets/rvp/sounds.json`**（批注 #13：`sounds/weapon/kda_35_snd_2.ogg` 已放入，**但 `sounds.json` 里还没有对应事件**，不注册则在 display 里引用不到）：

```jsonc
"kda_35_snd": {
  "sounds": ["rvp:weapon/kda_35_snd_2"],
  "subtitle": "rvp.sounds.kda_35_snd"
}
```

> `sounds` 数组里的路径 = `assets/rvp/sounds/` 下的相对路径去掉扩展名。
> 若同时给 `subtitle`，建议在 `assets/rvp/lang/{zh_cn,en_us}.json` 补 `rvp.sounds.kda_35_snd`（包内只有 `laser_shot` 有这个翻译，其它音效字幕都会裸显 key）。

**② 机炮 display**（`rvp:machinegun` 类型只需音效，照 `mbt_127.json`）：

```jsonc
{
  "type": "ywzj_rvp:weapon",
  "bedrock_backend": "rvp",
  "sounds": {
    "fire": "rvp:kda_35_snd",
    "reload": "ywzj_vehicle:gun_reload"
  }
}
```

**③ 导弹 display**（照 `fa18f_agm84hk.json`，弹药模型/贴图在 `rvp` 命名空间下）：

```jsonc
{
  "type": "ywzj_rvp:weapon",
  "bedrock_backend": "rvp",
  "model": "rvp:entity/ammo/missile_mim146",
  "texture": "rvp:textures/entity/ammo/missile_mim146.png",
  "sounds": {
    "fire": "ywzj_vehicle:missile_launch",
    "reload": "ywzj_vehicle:common_reload"
  }
}
```

> 包内其它可用的机枪类 `fire` 音效：`rvp:gsh_30_1_shot`（30mm）、`rvp:zbl08a_ru_30_shot`（30mm）、`rvp:nato_25_snd`（25mm）、`rvp:m61a1_snd`（M61 转管）。

---

## 八、骨模块 `bone_modules`

| 骨 | modules | 参数 | 依据 |
| --- | --- | --- | --- |
| `Engine` | `["engine"]` | `engine: { threshold_light: 550, threshold_heavy: 800 }` | 照 **M1A2SEP**（用户指定按 M1 给） |
| `lock_radar` | `["radar"]` | `min_damage: 20.0` | 照 ps1sm / cssa5 |
| `scan_radar` | `["radar"]` | `min_damage: 20.0` | 照 ps1sm / cssa5（双雷达各自独立失效） |
| `turret_barrel` | `["barrel"]` | **`smoke: true`** —— 要烟雾；m1a2 之所以 `false`，是因为它主炮坏了有**专门的模型表现**（`$weapon0_1_hurt` 受损替换骨），不靠烟雾提示 | 2026-09-28 用户纠正 |
| `missile_barrel` | `["barrel"]` | —（`BoneModuleConfig` 缺省即 `true`） | 照 cssa5 / ps1sm |

> ⚠️ **通用规则**：渲染模型有 `*_hurt` 之类**受损替换骨**（坏掉时换模型）→ 可关冒烟；**没有该骨 → 必须开冒烟**，否则炮管失效在视觉上完全看不出来。m1agds 没有该骨，故 `turret_barrel` 必须 `true`。

**雷达模块要点**：`RADAR` 模块要求**骨名 = 雷达 PartUnit 的 id**（`RVP_RadarModuleEnforcer`）→ 所以 `lock_radar` / `scan_radar` 两个 part 的 id 必须与骨名逐字相同；直击 ≥ `min_damage` 即打坏 → 强制关机、快修后自动恢复；不参与爆炸百分比破坏。

---

## 九、碰撞箱（因子 + 中英文别名）

> 倍率**先参考 m1a2sep**（同底盘），新增骨用包内既有命名（ps1sm/cssa5）。两张表的**键集合必须完全一致**。
> 中英文名走 **2026-09-28 的数据文件双语名机制**：`hitbox_display_name`（英文）+ `hitbox_display_name_CN`（中文），由客户端按当前语言二选一（`RVP_LangHelper.isChineseUi()`），**不写进 lang 文件**。

| 骨 | `hitbox_damage_factor` | `hitbox_display_name` | `hitbox_display_name_CN` |
| --- | --- | --- | --- |
| `vehicle_body` | 1.0 | Hull Side | 车体侧面 |
| `track` | 0.5 | Track | 履带 |
| `Engine` | 1.3 | Engine Bay | 发动机舱 |
| `Upper_front` | 0.6 | Upper Glacis | 首上 |
| `Lower_front` | 0.5 | Lower Glacis | 首下 |
| `Periscope` | 0.8 | Periscope | 潜望镜 |
| `turret` | 0.8 | Turret | 炮塔 |
| `turret_r` | 1.0 | Turret Right | 炮塔右侧 |
| `turret_l` | 1.0 | Turret Left | 炮塔左侧 |
| `turret_back` | 1.3 | Turret Bustle | 炮塔尾舱 |
| `turret_top` | 1.0 | Turret Top | 炮塔顶部 |
| `turret_barrel` | 0.4 | Main Gun | 机炮 |
| `missile` | 1.0 | Missile Launcher | 导弹发射架 |
| `missile_barrel` | 1.3 | Missile Tube | 导弹发射管 |
| `lock_radar` | 0.4 | Tracking Radar | 跟踪雷达 |
| `scan_radar` | 0.4 | Search Radar | 搜索雷达 |
| `turret_smoke_grenade_l_barrel` | 0.4 | Smoke Launcher | 烟幕弹发射器 |
| `turret_smoke_grenade_r_barrel` | 0.4 | Smoke Launcher | 烟幕弹发射器 |

**共 18 条**。另需顶层 `"hitbox_damage_factor_default": 1.0`。

⛔ **禁止**把 `virtual`（`physics_only_bone`）写进这两张表 —— 它 Y 从 0 起、比真实命中骨更靠外，会抢先结算并顶掉真实倍率（本包 7 台配 `physics_only_bone` 的车**零台**写入命中箱）。

---

## 十、顶层配置建议

以 **m1a2sep** 为底（同底盘），键顺序照 m1a2sep / ps1sm：

| 键 | 建议值 | 依据 |
| --- | --- | --- |
| `defense_stats` | `{ "damage_threshold": 0.1 }` | 同 m1a2sep |
| `armor_min_damage` | `15` | 批注 #9：同 M1A2 SEP |
| `type` | `"ywzj_vehicle:tracked_vehicle"` | 履带车 |
| `ui_preset` | `"ps1sm"` | 含 `radars.lock_radar` / `radars.scan_radar`，键名与本车 part id 完全一致（cssa5 也是这么复用的）。**不需要新建预设文件** |
| `show_skeleton` | `false` | 同 ps1sm / cssa5 |
| `attributes` | 同 m1a2sep（履带那套 `forward_acceleration` … ） | 同底盘 |
| `max_health` | `1200` | 批注 #9：同 M1A2 SEP |
| `view_info` | 同 m1a2sep | 同底盘 |
| `energy_info` | 同 m1a2sep（`[[0.2,1.7,-4.2]]`） | 两车 `Engine` cube 尺寸**完全一致**（中心和都是 `(-0.002,1.012,-3.279)` 格） |
| `physics_info` | 同 m1a2sep，且 `"physics_only_bone": "virtual"` | 本车结构模型有 `virtual` 骨 |
| `hide_passenger` | `true` | 同 m1a2sep |
| `structure_model` | `"rvp:vehicle/m1agds"` | |
| `core_distance_scale_multiplier` | `0.0` | 同 m1a2sep |
| `hitbox_damage_factor_default` | `1.0` | 同 m1a2sep |
| `countermeasure` | `smoke`，`launcher_parts: ["turret_smoke_grenade_l","turret_smoke_grenade_r"]`，`total 4 / per_round 2 / burst_rounds 2 / launch_interval_tick 4 / reload_tick 250` | 两个发射器照 **t90m** 的写法，数值同 m1a2sep |
| 载具名（lang） | zh **`M1 AGDS 多用途防空火力支援车`**、en `M1 AGDS` | 批注 #8 |
| `display/vehicle` 的 `description` | 留空（同 m1a2sep / ps1sm） | — |

**批注 #6 / #7 明确「不加」的两项**：`with_focus_locker`（炮口/焦点锁定圈，地面车辆不要）、`rvp_sight_fire_disguise`（开火伪装）都不写。
**批注 #10**：不需要炮口焰特效 → display **不写** `special_bone_effects`。

---

## 十一、批注答复（用户已确认 · 2026-09-28）

| # | 议题 | 批注答复 | 落实情况 |
| --- | --- | --- | --- |
| 1 | `tab_index` | **确认** | `306`（→ §七.1） |
| 2 | `turret` 转速 | **折中给个 4** | `x/y_rot_speed = 4 / 4`（→ §5.2） |
| 3 | `optical_sight_type` | **确认** | `"crt_ui"`（→ §5.1） |
| 4 | `crosshair_style` | **确认** | `"circle"` |
| 5 | 火控传感器 | **确认** | `fire_control_sensor_type: "rf"` + `rvp_fire_control_mode: "rvp_rf"` + `rvp_rf_off_axis_deg: 10` |
| 6 | `with_focus_locker` | **不加**（"这是车啊，加毛线焦点锁定圈"） | 不写该字段 |
| 7 | `rvp_sight_fire_disguise` | **不加** | 不写该字段 |
| 8 | 载具名 | **M1 AGDS 多用途防空火力支援车** | 见 §十 |
| 9 | 血量 / 免伤 | **同 M1A2 SEP** | `max_health 1200` / `armor_min_damage 15` |
| 10 | 炮口焰特效 | **不需要** | display 不写 `special_bone_effects` |
| 11 | `track_config.track_width` | **确认** | `3.0` |
| 12 | 观瞄 BB 坐标 | **`11 46 14`，需减枢轴除 16** | `optical_sight_offset = [0.6918625, 1.40706875, 0.86111875]`（→ §5.1，已反推校验） |
| 13 | 音效 | **已放入 KDA35 音效** | ⚠️ 需先在 `sounds.json` 注册事件 `kda_35_snd` 才能在 display 里引用（→ §七.4） |
| 14 | 落盘方式 | **找一台车的数据文件复制过来当蓝本改，不要从 0 写** | 蓝本 = `data/rvp/vehicles/m1a2sep.json`（→ §1.3） |

> 至此**所有设计口径已定**，可直接落盘：以 `m1a2sep.json` 为蓝本复制 → 增删部件 → 替换命中表 → 新建 8 个文件 + `sounds.json` 注册。

---

## 十二、落盘记录（2026-09-28 已完成）

### 12.1 新建 7 个文件

| 文件 | 要点 |
| --- | --- |
| `data/rvp/vehicles/m1agds.json` | 9.2 KB · 17 parts · 命中表 18 条 · 顶层 20 键。以 `m1a2sep.json` 为蓝本生成（生成脚本留档 `docs/plan/gen_m1agds_20260928.py`） |
| `assets/rvp/display/vehicle/m1agds.json` | `tab_index 306`、`track_config` 双轨 → `tread_r_move`、无 `special_bone_effects` |
| `assets/rvp/animation_controllers/m1agds_controller.json` | 4 条 `part_bindings` + 9 根负重轮 `special_bindings` + `switchable_animations.scan_radar` + `event_animations.turret_fire` |
| `assets/rvp/scripts/m1agds.js` | 空实现 `createPoseBuilder()` |
| `assets/rvp/display/weapon/m1agds_kda35.json` | 仅 `sounds.fire = rvp:kda_35_snd` |
| `assets/rvp/display/weapon/m1agds_mim146.json` | `model rvp:entity/ammo/missile_mim146` + 对应贴图 |
| `data/rvp/recipes/m1agds.json` | 材料/时长同 m1a2sep（`YwzjVehicleId: rvp:m1agds`，printingTime 1500） |

### 12.2 修改 3 个文件

| 文件 | 改动 |
| --- | --- |
| `assets/rvp/sounds.json` | 新增事件 `kda_35_snd` → `rvp:weapon/kda_35_snd_2`（用户放的 ogg）；原为 **纯 LF / 无末行换行**，按字节追加保持原样 |
| `assets/rvp/lang/zh_cn.json` | `entity.rvp.m1agds` / `rvp.m1agds` = `M1 AGDS 多用途防空火力支援车`；`rvp.sounds.kda_35_snd`；**CRLF 保持**（76→79） |
| `assets/rvp/lang/en_us.json` | `M1 AGDS Air Defense` / `M1 AGDS`；`rvp.sounds.kda_35_snd`；**CRLF 保持** |

### 12.3 校验结果（全部通过）

```
✓ 载具 JSON 有效 / 结构骨 20 / 渲染骨 97 / 动画 3 个
✓ parts 全部锚定骨命中结构模型
✓ 命中表三张键集合完全一致（18 条，连顺序也一致）
✓ 命中表键全部命中结构骨、未混入 virtual
✓ bone_modules 键全部命中结构骨：Engine / lock_radar / scan_radar / turret_barrel / missile_barrel
✓ 控制器引用骨 13 根全在渲染模型
✓ 控制器 event/switchable 动画名全部命中：event=[auto_cannon] switch=[radar_turn_off]
✓ display：track_config 动画名命中 / controller 与 animations 指向正确
✓ display 内 model/texture/slot_texture 引用均存在
✓ 武器 m1agds_kda35（cap 150）/ m1agds_mim146（cap 8）JSON + display 齐备
✓ 音效事件 rvp:kda_35_snd 已注册且 ogg 存在
✓ 配方 result = rvp:m1agds；lang 中英均含条目；ui_preset=ps1sm 文件含 lock_radar/scan_radar 键
全包：JSON 656 个零解析失败；37 台载具 / 108 条挂架引用零缺失
```

> 全包仅剩 3 处**既有**（非本次引入）的命中表差异：`bmpt72` / `cssa5` / `ps1sm` 的三张表**键集合相同、仅顺序不同** —— 属观感问题，需要时可统一；`m142` 的两条"缺 weapon display"是 M30 火箭巢本来就不渲染独立弹药模型，非缺陷。

### 12.4 实现时与文档的一处偏差（已按实际情况处理）

- ⚠️ **`turret_barrel` 的 `smoke` 必须为 `true`（2026-09-28 用户纠正，已改）**：本文档 §八 原写"照 m1a2sep 给 `smoke: false`"是**错的**。
  m1a2sep 之所以能关冒烟，是因为它渲染模型里有**受损替换骨 `$weapon0_1_hurt`**，炮管被打坏时由 `m1a2sep.js` 隐藏好炮管、显示受损炮管——**靠换模型提示**，所以不需要冒烟。
  **m1agds 没有 `_hurt` 骨**（也就没有隐藏炮管模型），炮管坏了只能靠冒烟提示 ⇒ **必须 `"smoke": true`**。
  > 通用规则：**渲染模型有 `<炮管>_hurt` 之类的受损替换骨 → 可关冒烟；没有 → 必须开**。`missile_barrel` 未写该键，`BoneModuleConfig` 缺省即 `true`，效果相同。
- 文档 §八 曾提到要核 `bone_modules.*.damage_factor` 与 `hitbox_damage_factor` 数值一致 —— 该字段**已于 2026-09-27 统合移除**（倍率统一由顶层 `hitbox_damage_factor` 提供），本次 `bone_modules` 未写该字段。

### 12.5 进游戏后建议首查

1. 履带与负重轮转动方向/速度（`$track_roller0..8` 只绑了左侧转速，因单骨含左右轮几何）；
2. 搜索雷达是否随开机展开（`radar_turn_off` invert）并持续旋转；
3. 双管机炮是否左右交替出弹、后坐动画是否触发；
4. 导弹箱俯仰 0～50° 是否正常、`mim146` 近炸是否生效；
5. 命中提示是否显示中文名（切 zh_cn 与 en_us 各看一次）。

### 12.6 补充修正：搜索雷达"不转"的根因与修法（2026-09-28）

**现象**：m1agds 的搜索雷达装上后完全不自转。

**根因（不是控制器配错，是缺资产）**：全包**自转的搜索雷达只有 4 台**，它们**一律靠一段 `loop: true` 的动画**驱动，`rot_info.y_rot_speed` **全是 0**：

| 载具 | 动画名 | 骨 | 时长 | 控制器 |
| --- | --- | --- | --- | --- |
| `cssa5` | `radar_loop` | `$radar` | 3.0s | `switchable_animations.scan_radar` |
| `96l6` | `radar_loop` | `radar` | 3.0s | 同上 |
| `irist_slm_tads` | `radar_loop` | `radar1` | 3.0s | 同上 |
| `ps1sm` | `radar1_loop` | `radar1` | 3.0s | `switchable_animations.scan_radar`；另 `radar_turn_off`→`lock_radar` |

而 **`m1agds.animation.json` 原本只有 `tread_r_move` / `radar_turn_off` / `auto_cannon`，没有自转动画** —— 控制器只能引用已存在的动画，所以怎么配都转不起来。（另有原因：`RadarUnit.tickRot()` 的机械扫描**只在服务端**跑，客户端不模拟，所以 `y_rot_speed` 这条路对雷达本就不可靠。）

**修法（已落地）**：
1. **`m1agds.animation.json` 补 `radar_loop`**（制表符缩进 / LF / 无末行换行，按字节追加，未重排全文）：
   ```jsonc
   "radar_loop": { "loop": true, "animation_length": 3.0,
                   "bones": { "radar": { "rotation": { "0.0": [0,0,0], "3.0": [0,360,0] } } } }
   ```
   备份 `_bak/m1agds.animation.json.bak_20260928`。
2. **控制器 `switchable_animations` 加第二条 + 图里加第二个节点**：同一台雷达需要**两条**（折叠 + 自转）：
   ```jsonc
   "switchable_animations": {
     "scan_radar":      { "part_id": "scan_radar", "animation": "radar_turn_off", "invert": true  },
     "scan_radar_spin": { "part_id": "scan_radar", "animation": "radar_loop",     "invert": false }
   }
   ...
   { "type": "switchable_animation", "ref": "scan_radar" },
   { "type": "switchable_animation", "ref": "scan_radar_spin" }
   ```

> ⚠️ **关键机制（读码定论）**：`PoseGraphCompiler.switchableAnimationNode()` 把图里的 **`ref`** 当作查找键（`new SwitchableAnimationNode(ref)`），而 runner 也是按 **`switchable_animations` 的【键】** 注册的（`RVP_TrackedVehicleDisplay` → `context.addSwitchableRunner(entry.getKey(), …)`）。
> ⇒ **图 ref 必须等于 switchable 的键**（不是 `part_id`）；**同一 part 想挂多条动画，只要键各不相同即可**（本车就是这么给"折叠 + 自转"两条的）。
> ⇒ 雷达要走这条路，必须由 `RVP_*VehicleDisplay` 的 `RadarUnitSwitchableAdapter` 适配（普通 `VehicleDisplay` 只认 `SwitchableUnit` 部件，雷达不是）。
> ⚠️ 动画在 graph `merge` 里**排在 `bone_binding` 之后** ⇒ 同骨时动画覆盖 part_binding（所以 `radar` 骨既绑了 `scan_radar` 又能被 `radar_loop` 转动）。
