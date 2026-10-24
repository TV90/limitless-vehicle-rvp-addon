# EBRC AA 模型层级结构梳理

本文档记录 `ebrc_aa` 当前在包内的渲染模型、结构模型层级关系，以及要为它补齐的配置文件清单。
EBRC AA 定位：**6×6 轮式防空车**（CT-40 40mm 机炮 + 双联 MICA-NG 防空导弹 + 搜索雷达）。
最贴近的现存模板是 `m1agds`（同为防空车：机炮 + 导弹 + 搜索/锁定双雷达）与 `lav25`（同为轮式，车体/车轮/烟幕发射器写法可抄）。

## 文件位置

### 已存在

- 渲染模型：`assets/rvp/models/bedrock/entity/ebrc_aa.json`（源文件 `ebrc_aa.bbmodel` 同目录）
- 结构模型：`data/rvp/models/bedrock/vehicle/ebrcaa.structure.json`
- 载具动画：`assets/rvp/animations/bedrock/entity/ebrc_aa.animation.json`
- 贴图：`assets/rvp/textures/entity/ebrc_aa.png`、`assets/rvp/textures/slot/ebrc_aa.png`
- 武器配置（4 个，已调好）：
  - `data/rvp/weapons/ebrc_afpsds.json` —— CT-40 40mm 尾翼稳定脱壳穿甲弹
  - `data/rvp/weapons/ebrc_ahead.json` —— CT-40 40mm AHEAD 编程空爆弹（`fuse_data.ahead_enabled`，`submunition_data` 释放 8 发 `ebrc_ahead_sub`）
  - `data/rvp/weapons/ebrc_ahead_sub.json` —— AHEAD 子弹药
  - `data/rvp/weapons/ebrc_mica_ng.json` —— MICA-NG 双脉冲主动雷达弹（`max_capacity: 2`，`fire_data.require_lock: true`）
    > **2026-10-24 已改**：`projectile_data.ignition_delay_tick` 显式写 `0`（发射即点火）。
    > 说明：该字段本体默认值本来就是 `0`（`RVP_ProjectileData:188`「默认 0；仅火箭发动机启用时生效」），EBRC 之前未配置也等于 0，所以这次是**显式声明**、行为不变。若实际观感仍是「出管后迟滞」，那是另一处：`guidance_data.guidance_tick_range` 当前为 `[[5,inf]]`（制导从第 5 tick 起，250 ms 无制导），要更彻底就把它改成 `[[0,inf]]` —— **这个改动会影响弹道表现，需你确认后再动**。

> 命名注意：**渲染侧/资源侧文件名是 `ebrc_aa`（带下划线），数据侧结构模型与载具 id 是 `ebrcaa`（不带下划线）**。
> 这是既有事实，配置里不要写混：`structure_model` 填 `rvp:vehicle/ebrcaa`，实体/控制器/脚本按 `ebrc_aa` 绑。

---

## EBRC AA

### 渲染模型层级

根骨骼：

- `EBRC`

一级主分组：

- `EBRC -> wheel`（车轮总组）
- `EBRC -> $weapon0`（炮塔偏航，枢轴 `[0.0021, 30.7067, 0.0005]`）
- `EBRC -> $body`

车轮链（**6 轮 + 6 个悬挂骨**）：

- `wheel -> xuangua0 -> wheel0`（枢轴 `[-15.4544, 10.1992, 26.8131]`，左前）
- `wheel -> xuangua1 -> wheel1`（枢轴 `[15.4587, 10.1992, 26.8131]`，右前）
- `wheel -> xuangua2 -> wheel2`（枢轴 `[-15.4544, 10.1992, -13.8885]`，左中）
- `wheel -> xuangua3 -> wheel3`（枢轴 `[15.4587, 10.1992, -13.8885]`，右中）
- `wheel -> xuangua4 -> wheel4`（枢轴 `[-15.4544, 10.1992, -40.3631]`，左后）
- `wheel -> xuangua5 -> wheel5`（枢轴 `[15.4587, 10.1992, -40.3631]`，右后）

主炮链（CT-40）：

- `$weapon0 -> $weapon0_0`（炮管俯仰，枢轴 `[0.0036, 37.9522, 10.0516]`）
- `$weapon0_0 -> $weapon0_1`（**炮管后坐/炮口焰触发骨**，枢轴同 `$weapon0_0`）
- `$weapon0_1 -> turret_muzzle_flash`（炮口焰，枢轴 `[0.003, 38.0169, 59.2052]`）

导弹链（MICA-NG 双联）：

- `$weapon0 -> $weapon0_2`（导弹发射架俯仰，枢轴 `[18.8225, 51.2103, -1.0234]`）
- `$weapon0_2 -> missile1`（枢轴 `[0, 0, 0]`，按枚隐藏）
- `$weapon0_2 -> missile2`（枢轴 `[0, 0, 0]`，按枚隐藏）

雷达：

- `$weapon0 -> radar`（枢轴 `[-0.0359, 48.8987, -28.6556]`）

> 要点：
> 1. **渲染模型有 `turret_muzzle_flash` 骨，且动画 json 里有 `cannon_fire` + `static` 两段** —— 与 `lav25` / `zbl08a` 同类，**不是** `cssa5` 那种"无载具动画"模式。控制器必须写 `loop_animations.static` + `event_animations.turret_fire → cannon_fire`。
> 2. **导弹有 `missile1` / `missile2` 两个子骨**，可以按剩余弹药逐枚隐藏（仿 `cssa5` / `zbl08a` 的 `missiles` hide 逻辑），也可以不写（省事，弹打光后模型仍显示两枚）。
> 3. 悬挂骨 `xuangua0~5` 与轮骨是同名一一对应，**轮子滚动/转向要绑 `wheelN`（子骨），不要绑 `xuanguaN`**（悬挂骨是父骨，绑它会把悬挂一起转）。
> 4. 渲染 `radar` 枢轴 z=`-28.6556`，结构 `scan_radar` 枢轴 z=`-22.817`，**两者差 5.84**。渲染侧是视觉雷达罩的转轴，结构侧是碰撞箱的中心参照，允许不同；但若做 `bone_modules` 或雷达联动动画，用渲染侧枢轴。

### 结构模型层级

根骨骼：

- `main_structure`（pivot `[0, 0, 0]`，cube `origin [-24, 8, -62] size [46, 24, 111]`）——**全车包围盒，仅物理用，做 `physics_only_bone`**
- `vehicle_body` 与 `turret` 平级（无根包装骨，与 lav25 / m1agds 一致）

车体命中区（`vehicle_body` 下）：

- `vehicle_body -> upper_front`（首上）
- `vehicle_body -> lower_front`（首下）
- `vehicle_body -> passenger`（载员舱/后部，z 为负=车尾侧）

炮塔命中区（`turret` 下，全部 `parent=turret`）：

- `turret -> turret_barrel`（CT-40 炮管，cube `size [2, 2, 51]`，枢轴 `[0.004, 37.952, 10.052]`）
- `turret -> missile`（cube 仅 `0.1³`，**纯枢轴占位**，枢轴 `[0.002, 30.707, 0.001]` 与 turret 相同）
- `turret -> missile_barrel`（**2 个 cube**，`size [2, 2, 41]`，y=51.21 与 y=47.21 上下两层，枢轴 `[18.823, 51.21, -1.023]`）
- `turret -> scan_radar`（搜索雷达，cube `size [12.93, 15.82, 4.58]`，枢轴 `[0, 48.414, -22.817]`）
- `turret -> turret_smoke_grenade_l_barrel`（枢轴 `[-19.8, 35.638, 34.763]`）
- `turret -> turret_smoke_grenade_r_barrel`（枢轴 `[20.95, 35.638, 34.763]`）

车轮（6 个，**全部 `parent=None`，直接挂根**）：

- `wheel0`（左前，pivot `[-16.122, 8.979, 25.747]`）
- `wheel1`（右前，pivot `[16, 8.979, 25.747]`）
- `wheel2`（左中，pivot `[-16.122, 8.979, -15.003]`）
- `wheel3`（右中，pivot `[16, 8.979, -15.003]`）
- `wheel4`（左后，pivot `[-16.122, 8.979, -41.503]`）
- `wheel5`（右后，pivot `[16, 8.979, -41.503]`）

> **方位校验（已核对，与全包一致）**：`upper_front.z = 28.5 ~ 48.02`（正）、`passenger.z = -61.5 ~ -24.98`（负）⇒ **+z = 车头**。
> 与 `lav25` / `zbl08a` / `lavad` 完全同向（它们的 `upper_front.z` 都是 `37.5 ~ 80.025`）。

### 结构特点

- **6×6 轮式**，不是 8 轮。三轴：前轴 `wheel0/1`、中轴 `wheel2/3`、后轴 `wheel4/5`，左右成对。
- **结构模型无 `virtual` 骨但有 `main_structure`**：`main_structure` 就是全车包围盒（`46 × 24 × 111`），用它做 `physics_info.physics_only_bone`；不需要再新建 `virtual`。
- **无 `lock_radar`（锁定/火控雷达）骨**，只有 `scan_radar`（搜索雷达）。而 `ebrc_mica_ng` 的 `fire_data.require_lock` 为 `true` —— 这是本车最大的配置缺口，三种处理方式的取舍见「待补充文件清单 → 第 1 项」末尾的火控雷达缺口说明。**不要直接照抄 m1agds 的双雷达 part，它有两个骨而 EBRC 只有一个。**
- **无 `turret_side` / `turret_back` / `turret_top` / `Engine` 等细分骨**（m1agds / lav25 有这些，EBRC 没有）。碰撞表只能按实际存在的 18 个骨写，**不要照抄 m1agds 的键**。
- `missile` 的 cube 是 `0.1³` 的占位箱：它是发射架枢轴的参照点，实际命中由 `missile_barrel`（两根 2×2×41）承担。`missile` 若要入伤害表，倍率随意（几乎打不到）。
- `turret_barrel` 长度 51、截面 2×2，是细长管；`missile_barrel` 两根 41 长 —— 这两个是"细管类"部件，倍率照 lavad 的 `turret_barrel 0.4` / `missile_barrel 1.3`。
- 渲染 `$weapon0` 与结构 `turret` 枢轴一致（`[0.0021, 30.7067, 0.0005]` vs `[0.002, 30.707, 0.001]`）；渲染 `$weapon0_0` 与结构 `turret_barrel` 一致（`[0.0036, 37.9522, 10.0516]` vs `[0.004, 37.952, 10.052]`）；渲染 `$weapon0_2` 与结构 `missile_barrel` 一致（`[18.8225, 51.2103, -1.0234]` vs `[18.823, 51.21, -1.023]`）。**三条主链枢轴对齐，可直接映射。**

---

## 待补充文件清单（9 项）

> 已有：渲染模型、结构模型、动画、实体贴图、槽贴图、4 个武器 JSON。**以下 9 项全部缺失**。
> 约定：`rvp:` 前缀即 `limitless_vehicle/rvp/` 包内对应路径。

### 1. `data/rvp/vehicles/ebrcaa.json` —— 主配置（必须，最关键）

骨架照抄 `lav25.json`（车体类、轮式），炮塔部分参考 `m1agds.json`（防空车：机炮 + 导弹 + 雷达）。

必须包含：

- `type`: `ywzj_vehicle:wheeled_vehicle`
- `structure_model`: `rvp:vehicle/ebrcaa`
- `physics_info.physics_only_bone`: `"main_structure"`
- `hitbox_damage_factor` / `hitbox_display_name` / `hitbox_display_name_CN`：见下方第 7 节表
- `hitbox_damage_factor_default`: `1.0`
- `defense_stats: {damage_threshold: 0.1}`、`armor_min_damage`、`max_health`、`attributes`、`energy_info`、`view_info`
- `countermeasure.smoke`（左右烟幕发射器对应 `turret_smoke_grenade_l/r_barrel`）
- `bone_modules`（按需：如把 `passenger` 或 `vehicle_body` 配成 engine 模块）

`parts` 需包含以下 16 个（**type 与字段已按 lav25 / m1agds 实测核对，不要改成 generic**）：

| part id | type | structure_bone | 关键字段 |
|---|---|---|---|
| `turret` | `ywzj_vehicle:weapon` | `turret` | **`rot_info: {x_rot_speed: 4, y_rot_speed: 2, x_rot_max: 10, x_rot_min: -70}`**（**负角=仰角**，故仰 70 / 俯 10）、`seat_offset`、**`optical_sight_offset: [0.6249, 1.2683, 0.6874]`**（换算见第 1.1 节）、`optical_sight_type`、`with_stabilizer`、`with_thermal_imager`、`zoom_max`、`fire_control_sensor_type`、`sub_part_unit_ids: ["scan_radar"]`、`weapons`（见下） |
| `missile` | `ywzj_vehicle:weapon` | `missile` | **`base: "turret"`**、`parent_weapon_unit_aim: true`、`crosshair_style: "none"`、`rot_info`（俯仰限位）、**`weapons: null`**（导弹写在 `turret.weapons` 里，照 cssa5） |
| `scan_radar` | **`ywzj_vehicle:radar`** | `scan_radar` | **`radar_role: "all"`（单雷达，不要 `search`）**、`base: "turret"`、`radar_type`、`scan_sector_angle`、`scan_period_tick`、`scan_min/max_height`、`max_scan_distance`、`nctr_mode`、`rot_info`（y 限 0~360） |
| `turret_smoke_grenade_l` | **`ywzj_vehicle:weapon`** | `turret_smoke_grenade_l_barrel` | `base: "turret"`、`rot_info: {x_rot_speed: 0, y_rot_speed: 0}` |
| `turret_smoke_grenade_r` | **`ywzj_vehicle:weapon`** | `turret_smoke_grenade_r_barrel` | 同上 |
| `suspension_wheel0` ~ `suspension_wheel5` | **`ywzj_vehicle:suspension`** | `wheel0` ~ `wheel5` | `render_bone`（同 bone 名）、`max_health`、`defense_stats`、`spring_stiffness`、`damping`、`rest_length`、`max_compression`、`max_extension`、`max_droop` |
| `vehicle_body` | `ywzj_vehicle:generic` | `vehicle_body` | 命中区 |
| `upper_front` | `ywzj_vehicle:generic` | `upper_front` | 命中区 |
| `lower_front` | `ywzj_vehicle:generic` | `lower_front` | 命中区 |
| `passenger` | `ywzj_vehicle:generic` | `passenger` | 命中区 |
| `turret_barrel` | `ywzj_vehicle:generic` | `turret_barrel` | 命中区（lav25 就是这么建的，需要单独成 part 才能独立受损 / 脱落） |

> **不需要建的 part**：`turret_side` / `turret_back` / `turret_top` / `top` / `Engine` / `missile_bay` / `commander_machine_gun` / `lock_radar` —— EBRC 结构模型里没有对应骨（lav25 / m1agds 有，但那是它们的模型）。**照 lav25 抄 parts 时要把这些删掉。**
> **`lock_radar` 明确不建**：本车走单雷达模式（见下）。

> **三个最容易写错的 type**（我原本按 generic 猜，实测全部不是）：
> - 车轮是 **`ywzj_vehicle:suspension`**，part id 前缀 `suspension_`，且必须带 `render_bone` + 悬挂参数（照抄 lav25 的 `spring_stiffness: 153600` / `damping: 7900` / `rest_length: 0.4` / `max_compression: 0.4` / `max_extension: 0.22` / `max_droop: 0.12` 作起点，按 EBRC 整备质量微调）。
> - 雷达是 **`ywzj_vehicle:radar`**，不是 generic；本车 `radar_role` 写 **`"all"`**。
> - 烟幕发射器是 **`ywzj_vehicle:weapon`**（不是 generic），`base: "turret"`，`rot_info` 全 0。

### 1.1 观瞄坐标换算（`optical_sight_offset`）

公式（已在 lav25 上反算验证）：**`offset = (观瞄点 − 结构模型 turret.pivot) / 16`**

- 观瞄点（用户给出）：`(10, 51, 11)`
- 结构模型 `turret` pivot：`[0.002, 30.707, 0.001]`

```
x: (10 − 0.002) / 16  = 9.998  / 16 = 0.624875
y: (51 − 30.707) / 16 = 20.293 / 16 = 1.2683125
z: (11 − 0.001) / 16  = 10.999 / 16 = 0.6874375
```

⇒ **`"optical_sight_offset": [0.6249, 1.2683, 0.6874]`**

> **x 符号需要你确认一次**：上表是按「10 已经是导出坐标（= 渲染模型 json 里的坐标空间）」算的。如果你报的 10 是从 **Blockbench 原始视图**读的，那么导出时 **x 要反号**，结果应为 `[-0.6251, 1.2683, 0.6874]`（`(-10 − 0.002)/16`）。
> 验证方法：进游戏开镜，看观瞄点是否落在炮塔**右侧**（渲染模型里导弹架在 x=+18.8 一侧）。若左右反了，就是 x 符号要取反。
> 参考对照：lav25 的 pivot `[-0.0602, 37.0046, 0.1862]` + offset `[0.4142, 0.6775, 0.6895]` ⇒ 反算观瞄点 `(6.57, 47.84, 11.22)`，观瞄镜高出炮塔枢轴 10.84 单位（0.68 格），量级合理，可用来校验你自己的车。

**`turret` 的 `weapons` 写法**（一号机炮双弹种二选一 + 二号导弹，照 `ps1sm`）：

```json
"weapons": [
  { "ids": ["rvp:ebrc_ahead", "rvp:ebrc_afpsds"], "modding_only_multi": true },
  { "id": "rvp:ebrc_mica_ng", "part_unit_id": "missile" }
]
```

> 依据 `ps1sm` 实测写法：**二选一 = `{"ids": [a, b], "modding_only_multi": true}`，不带 `save_id`**（带 `save_id` 的是 lav25 那种「切弹种槽」，语义不同）。
> `ebrc_ahead_sub` **不要**写进 `weapons`：它是 `ebrc_ahead` 的 `submunition_data` 内部引用的子弹药，不占弹药槽。

> **雷达：单雷达模式（已定）**：只建 `scan_radar` 一个 radar part，`radar_role: "all"`（搜索 + 跟踪合一），**不写 `"search"`、不建 `lock_radar`**。
> 模板直接抄 `irist_slm_tads`（同为防空车，它就是 `scan_radar` + `radar_role: "all"` 单雷达）：
> ```json
> {
>   "id": "scan_radar", "type": "ywzj_vehicle:radar", "structure_bone": "scan_radar",
>   "radar_role": "all", "nctr_mode": "MODERN", "is_seat": false, "base": "turret",
>   "rot_info": { "x_rot_speed": 0, "y_rot_speed": 0, "x_rot_max": 90, "x_rot_min": 0, "y_rot_max": 360, "y_rot_min": 0 },
>   "scan_sector_angle": 180, "radar_type": "SLM", "scan_animation_mode": "phase",
>   "scan_period_tick": 20, "scan_min_height": 15, "scan_max_height": 10000,
>   "enable_hms": false, "max_scan_distance": 3000
> }
> ```
> `radar_type` 改成本车自己的标识（如 `"EBRC"`），其余可先照抄，射程/高度按平衡调。
> `ebrc_mica_ng` 的 `require_lock` 靠这个 `"all"` 雷达提供锁定，**不需要 `fire_control_sensor_type` 特殊设置**（炮塔写 `"rf"` 即可）。

### 2. `assets/rvp/animation_controllers/ebrcaa_controller.json` —— 控制器（必须）

**直接照抄 `lav25_controller.json` 改写**（已实测核对：`lav25_controller` 与 `zbl08a_controller` 结构完全同构，二者都是 `loop_animations.static` + `event_animations.turret_fire → cannon_fire` + 8 轮只前两轴转向；EBRC 的动画 json 同样是 `static` + `cannon_fire`，完全对得上）。

> **JSON 结构注意**：控制器文件**顶层直接是** `name` / `script` / `parameters` / `switchable_animations` / `loop_animations` / `event_animations` / `state_machines` / `graph`，**没有** `animation_controllers` 这层包装。照抄时不要多包一层。

骨架（照此改写，`name` 改为 `ebrcaa_controller`）：

```json
{
  "name": "ebrcaa_controller",
  "script": "rvp:ebrcaa",
  "parameters": {},
  "switchable_animations": {},
  "loop_animations": { "static": { "animation": "static" } },
  "event_animations": { "turret_fire": ["cannon_fire"] },
  "state_machines": {},
  "graph": {
    "type": "merge",
    "inputs": [
      { "type": "loop_animation", "ref": "static" },
      {
        "type": "additive",
        "base": {
          "type": "merge",
          "inputs": [
            {
              "type": "bone_binding",
              "special_bindings": [
                { "bones": ["wheel0","wheel1","wheel2","wheel3","wheel4","wheel5"],
                  "source": "wheel_rotation", "axis": "x", "param": 0.8 },
                { "bones": ["wheel0","wheel1"], "source": "steering_angle",
                  "axis": "y", "multiplier": 1.0, "min": -30.0, "max": 30.0 },
                { "bones": ["wheel2","wheel3"], "source": "steering_angle",
                  "axis": "y", "multiplier": 0.5, "min": -15.0, "max": 15.0 }
              ],
              "part_bindings": [
                { "bone": "$weapon0",   "part": "turret",     "rotation_type": "y", "axis": "y", "invert": true },
                { "bone": "$weapon0_0", "part": "turret",     "rotation_type": "x", "axis": "x", "invert": false },
                { "bone": "$weapon0_2", "part": "missile",    "rotation_type": "x", "axis": "x", "invert": false },
                { "bone": "radar",      "part": "scan_radar", "rotation_type": "y", "axis": "y", "invert": false }
              ]
            },
            { "type": "script", "function": "updateBones" }
          ]
        },
        "add": { "type": "event_animation" }
      }
    ]
  }
}
```

要点与依据：

- **6 轮全部 `wheel_rotation`**（axis `x`，param `0.8`，与 lav25 一致）。
- **转向只有前两轴**：`wheel0/1` 满角（1.0 / ±30），`wheel2/3` 半角（0.5 / ±15），`wheel4/5` **不绑**（随车体）。这与 `lav25` / `zbl08a` 完全相同——它们虽是 8 轮，实际也是只绑 `wheel0~3` 转向。**不要因为 EBRC 是 6×6 就自创分组。**
- `radar → scan_radar` 的写法**直接抄 `m1agds_controller`**：`{"bone":"radar","part":"scan_radar","rotation_type":"y","axis":"y","invert":false}`（已核实 m1agds 就是这么绑的）。
- `event_animations.turret_fire` 的动画名必须是 `cannon_fire`（EBRC animation.json 里的名字），**不要抄 m1agds 的 `auto_cannon`**（那是它自己的动画名）。
- `script` 字段：如果不需要导弹逐枚隐藏，**可以删掉 `script` 和 `{"type":"script","function":"updateBones"}` 这个 input**，同时也不必建 js 文件（见第 3 项）。

> ⚠️ **EBRC 动画 json 缺雷达动画**：`ebrc_aa.animation.json` 只有 `cannon_fire` 和 `static`，**没有** `radar_loop` / `radar_turn_off`（m1agds 有，用 `switchable_animations` 做雷达开关+自转）。所以：
> - 若只要雷达**跟随炮塔/瞄准角**转：上面 `part_bindings` 的 `radar → scan_radar` 就够了。
> - 若要雷达**持续自转**（搜索雷达的典型观感）：必须先在 `ebrc_aa.animation.json` 里补一段 `radar_loop`（`loop: true`，旋转 `radar` 骨的 y），再在控制器里加 `switchable_animations` + `switchable_animation` input（照抄 m1agds 的 `scan_radar_spin`）。
> - **这是本次唯一需要动动画文件的地方，先和用户确认要不要雷达自转。**

### 3. `assets/rvp/scripts/ebrcaa.js` —— 脚本（**必须**，导弹逐枚显隐）

用途：**`missile1` / `missile2` 按剩余弹药逐枚隐藏**（打掉一枚，模型上对应那枚消失）。

- CT-40 是**单管**机炮，不是转管 ⇒ **不要**写 cssa5 / lavad 那种 `$weapon0_1` 绕 Z 轴累加旋转。
- 取弹药数用 `context.getWeaponRemainAmmo("turret", 1)`：EBRC 的导弹写在 `turret.weapons` 的第 2 项（index 1），**照 cssa5 的取法**，不是 zbl08a 那种独立 `missile` part 的 `("missile", 0)`。
- 隐藏顺序照 cssa5：`i < missiles.length - remain` ⇒ 先打掉的先消失。

完整实现（照 `cssa5.js` 裁剪，可直接落地）：

```js
// EBRC 双联导弹：按剩余弹药逐枚隐藏（渲染骨在 $weapon0_2 下）
const missiles = ["missile1", "missile2"];

function clampAmmo(value, max) {
    return (typeof value === "number" && isFinite(value))
        ? Math.max(0, Math.min(max, Math.floor(value)))
        : max;
}

function updateBones(context) {
    const builder = createPoseBuilder();

    try {
        // 导弹挂在 turret 的第 2 个武器（index 1），照 cssa5 的取法
        const remain = clampAmmo(context.getWeaponRemainAmmo("turret", 1), missiles.length);
        for (let i = 0; i < missiles.length; i++) {
            if (i < missiles.length - remain) {
                builder.hideBone(missiles[i]);
            }
        }
    } catch (e) {
    }

    return builder;
}
```

对应地在控制器里保留：

```json
"script": "rvp:ebrcaa",
...
{ "type": "script", "function": "updateBones" }
```

> 行为核对：`remain=2` → 两枚都显示；`remain=1` → 隐藏 `missile1`、显示 `missile2`；`remain=0` → 两枚都隐藏。若你希望「先打掉的留下空管、第二枚才消失」之类的观感差异，改 `missiles` 数组顺序即可。

### 4. `assets/rvp/display/vehicle/ebrcaa.json` —— 载具展示模型（必须）

仿 `display/vehicle/m1agds.json` 或 `lav25.json`。

### 5–7. `assets/rvp/display/weapon/ebrc_{afpsds,ahead,mica_ng}.json` —— 武器展示模型（必须）

- `ebrc_afpsds`、`ebrc_ahead` 各一个（机炮弹外观可共用同一模型，分别写文件）
- `ebrc_mica_ng` 一个（导弹外观：需 `model` + `texture` + `animations`）
- `ebrc_ahead_sub` **不需要** display（子弹药不单独展示），但要确认它不要求 `display`

### 8. 双语条目（必须，**只加载具名**）

`assets/rvp/lang/en_us.json` + `zh_cn.json` **各加两个键，两个值相同**（已核实现有约定：m1agds / lav25 都是成对的两个键）：

```json
"entity.rvp.ebrcaa": "EBRC AA Wheeled AA Vehicle",
"rvp.ebrcaa": "EBRC AA Wheeled AA Vehicle"
```

中文文件里值写 `EBRC AA 轮式防空车`。

> **武器名不要加 lang**。4 个武器 JSON 已自带 `name`（英）/ `name_CN`（中）；已核实全包武器都不走 lang（`m1agds_kda35` / `m1agds_mim146` 在 `lang/zh_cn.json` 里查不到）。
> 键名用的是**载具 id `ebrcaa`**，不是资源名 `ebrc_aa` —— 这是最容易写错的地方。

### 9. 配方（必须，手写）

`data/rvp/recipes/` 下按同类车写一条载具配方 + 若武器需要单独合成则各一条。载具物品不用注册（走 `vehicle_spawn_item` + NBT `YwzjVehicleId`）。

### 可选：`tab_index`

参考现有占用：`305 VT4`、`309 ZTZ100`、`312 ZTZ99B`、`313 AH1Z`。建议 EBRC AA 取 **`314`**（防空车紧邻 AH1Z 之后）。

---

## 推荐碰撞箱别名与倍率（重点）

用于 `hitbox_damage_factor` / `hitbox_display_name` / `hitbox_display_name_CN`。
`main_structure` 为仅物理骨骼（`physics_only_bone`），**不计入伤害命中区，故不入表**。

约定：

- 键名必须**逐字命中结构模型 bone 名**（大小写敏感；EBRC 全小写，所以键全小写）
- **倍率一律照 `lavad` 原值**，不做任何"按车种微调"的建议
- `lavad` 表里没有的骨（`scan_radar`）单列在表末，**留空待定，不自作主张填值**

| bone | 英文别名 | 中文别名 | 倍率 | lavad 对应项 |
| --- | --- | --- | --- | --- |
| `vehicle_body` | Hull | 车体 | **1.0** | `vehicle_body` 1.0 |
| `upper_front` | Upper Glacis | 首上 | **0.6** | `upper_front` 0.6 |
| `lower_front` | Lower Glacis | 首下 | **0.8** | `lower_front` 0.8 |
| `passenger` | Crew Compartment | 载员舱 | **1.3** | `passenger` 1.3 |
| `wheel0` | Front-Left Wheel | 左前轮 | **0.4** | `wheel` 0.4 |
| `wheel1` | Front-Right Wheel | 右前轮 | **0.4** | `wheel` 0.4 |
| `wheel2` | 2nd Axle Left Wheel | 左中轮 | **0.4** | `wheel` 0.4 |
| `wheel3` | 2nd Axle Right Wheel | 右中轮 | **0.4** | `wheel` 0.4 |
| `wheel4` | Rear-Left Wheel | 左后轮 | **0.4** | `wheel` 0.4 |
| `wheel5` | Rear-Right Wheel | 右后轮 | **0.4** | `wheel` 0.4 |
| `turret` | Turret | 炮塔 | **0.8** | `turret` 0.8 |
| `turret_barrel` | CT-40 Cannon | CT-40 机炮 | **0.4** | `turret_barrel` 0.4 |
| `missile` | Missile Launcher | 导弹发射架 | **1.0** | `missile` 1.0 |
| `missile_barrel` | Missile Tube | 导弹发射管 | **1.3** | `missile_barrel` 1.3 |
| `turret_smoke_grenade_l_barrel` | Smoke Launcher (L) | 烟幕发射器（左） | **0.4** | `smoke_barrel` 0.4 |
| `turret_smoke_grenade_r_barrel` | Smoke Launcher (R) | 烟幕发射器（右） | **0.4** | `smoke_barrel` 0.4 |
| `scan_radar` | Search Radar | 搜索雷达 | **待定** | **lavad 无此骨** |

> `scan_radar` 在 lavad 的模型里不存在（lavad 是防空导弹车但没有独立雷达骨），所以没有可照抄的值。
> 这一项留空，**由你填**；若暂时不想区分，可先用 `hitbox_damage_factor_default`（1.0）兜住。

### 可直接粘贴的片段

```json
"hitbox_damage_factor_default": 1.0,
"hitbox_damage_factor": {
  "vehicle_body": 1.0,
  "upper_front": 0.6,
  "lower_front": 0.8,
  "passenger": 1.3,
  "wheel0": 0.4,
  "wheel1": 0.4,
  "wheel2": 0.4,
  "wheel3": 0.4,
  "wheel4": 0.4,
  "wheel5": 0.4,
  "turret": 0.8,
  "turret_barrel": 0.4,
  "missile": 1.0,
  "missile_barrel": 1.3,
  "turret_smoke_grenade_l_barrel": 0.4,
  "turret_smoke_grenade_r_barrel": 0.4
},
"// scan_radar": "lavad 无此骨，倍率待定；暂由 hitbox_damage_factor_default(1.0) 兜住",
"hitbox_display_name": {
  "vehicle_body": "Hull",
  "upper_front": "Upper Glacis",
  "lower_front": "Lower Glacis",
  "passenger": "Crew Compartment",
  "wheel0": "Front-Left Wheel",
  "wheel1": "Front-Right Wheel",
  "wheel2": "2nd Axle Left Wheel",
  "wheel3": "2nd Axle Right Wheel",
  "wheel4": "Rear-Left Wheel",
  "wheel5": "Rear-Right Wheel",
  "turret": "Turret",
  "turret_barrel": "CT-40 Cannon",
  "scan_radar": "Search Radar",
  "missile": "Missile Launcher",
  "missile_barrel": "Missile Tube",
  "turret_smoke_grenade_l_barrel": "Smoke Launcher (L)",
  "turret_smoke_grenade_r_barrel": "Smoke Launcher (R)"
},
"hitbox_display_name_CN": {
  "vehicle_body": "车体",
  "upper_front": "首上",
  "lower_front": "首下",
  "passenger": "载员舱",
  "wheel0": "左前轮",
  "wheel1": "右前轮",
  "wheel2": "左中轮",
  "wheel3": "右中轮",
  "wheel4": "左后轮",
  "wheel5": "右后轮",
  "turret": "炮塔",
  "turret_barrel": "CT-40 机炮",
  "scan_radar": "搜索雷达",
  "missile": "导弹发射架",
  "missile_barrel": "导弹发射管",
  "turret_smoke_grenade_l_barrel": "烟幕发射器（左）",
  "turret_smoke_grenade_r_barrel": "烟幕发射器（右）"
}
```

### 入射角钳制（可选，参考 lav25）

`hitbox_max_incidence_angle` 按骨骼配，只对需要跳弹的倾斜面开：

```json
"hitbox_max_incidence_angle": { "upper_front": 20 }
```

---

## 建完必交叉校验（8 项）

1. **骨名命中结构模型**：`parts[].structure_bone`、`hitbox_*` 三张表的键、`bone_modules` 的键，全部逐字命中 `ebrcaa.structure.json` 的 18 个骨名
2. **三张命中表键集合 + 顺序一致**：`hitbox_damage_factor` / `hitbox_display_name` / `hitbox_display_name_CN`
3. **`part_bindings.bone` 在渲染模型**：`$weapon0` / `$weapon0_0` / `$weapon0_2` / `radar` / `wheel0~5` 全部存在于 `ebrc_aa.json`
4. **动画名逐字命中**：`static`、`cannon_fire`（照抄 `ebrc_aa.animation.json` 的拼写）
5. **引用文件存在**：`rvp:vehicle/ebrcaa`（结构）、`rvp:ebrc_ahead` / `rvp:ebrc_afpsds` / `rvp:ebrc_mica_ng` 三个武器（`ebrc_ahead_sub` 由 `ebrc_ahead` 内部引用）、controller、js、display 车 + 武器、lang 键
6. **武器 JSON ↔ `display/weapon` 对应**：`ebrc_afpsds` / `ebrc_ahead` / `ebrc_mica_ng` 各有一个 display 文件；`ebrc_ahead_sub` 不需要
7. **js ↔ 武器槽索引一致**：`ebrcaa.js` 里 `getWeaponRemainAmmo("turret", 1)` 的 `1` 必须对应 `turret.weapons` 里导弹那一项的索引（机炮 index 0、导弹 index 1）；`hideBone` 的 `"missile1"` / `"missile2"` 必须逐字命中渲染模型的骨名
8. **雷达是单雷达**：只有 `scan_radar` 一个 `ywzj_vehicle:radar` part，`radar_role` 为 `"all"`；`parts` 里**不应出现 `lock_radar`**

## 额外注意（容易踩）

- **`ebrc_aa` vs `ebrcaa` 命名混用**：资源/实体用 `ebrc_aa`，结构模型与载具 id 用 `ebrcaa`。写 `structure_model` 时只能写 `rvp:vehicle/ebrcaa`，写错会静默丢模型。
- **AHEAD 的 `submunition_data` 引用 `ebrc_ahead_sub` 不带 `rvp:` 前缀**（现有 JSON 就是裸 id），若要改成带前缀需两处同步改并实测。
- **MICA-NG 的 `require_lock: true`**：本车靠 `scan_radar` 的 `radar_role: "all"` 提供锁定（单雷达兼搜索+跟踪），**不要建 `lock_radar`、不要把 role 写成 `"search"`**（写 `"search"` 就只有搜索没有锁定，导弹打不出去）。
- **`optical_sight_offset` 的 x 符号**：按导出坐标算是 `+0.6249`；若你报的观瞄点是从 BB 原始视图读的，x 要取反成 `-0.6251`。进游戏开镜看观瞄点是否落在炮塔右侧（导弹架那一侧）即可判定。
- **轮子滚动绑 `wheelN`，不要绑 `xuanguaN`**：悬挂骨是父骨，绑它会把悬挂一起旋转。
- **`main_structure` 只能做 `physics_only_bone`**：它是 `46 × 24 × 111` 的全车包围盒，若同时当伤害箱会导致全身倍率统一、`hitbox_damage_factor` 形同虚设。
- **6 轮不是 8 轮**：转向分组只有 3 轴，不要抄 lav25 的 4 轴分组（它有 `wheel0~7`）。
