# VT4（VT4A1）模型层级结构梳理

本文档用于记录重做后 VT4 当前在载具包中的渲染模型、结构模型层级关系，便于后续配置动画、武器站、履带、ERA、负重轮、APS、机枪等逻辑。文末附"重做 VT4 需要同步修改的配置清单"。

> 解析基于 2026-09-02 重做后的新文件（渲染/结构/动画均为当日新档）。
> 骨骼树可用 `scripts/parse_bone_tree.py <模型json路径>` 随时重新生成。

## 文件位置

### VT4（载具包内路径）

- 渲染模型：
  `D:\ywzj\ywzj\ywzj_rvp\limitless_vehicle\rvp\assets\rvp\models\bedrock\entity\vt4.json`
- 结构模型：
  `D:\ywzj\ywzj\ywzj_rvp\limitless_vehicle\rvp\data\rvp\models\bedrock\vehicle\vt4.structure.json`
- 动画文件：
  `D:\ywzj\ywzj\ywzj_rvp\limitless_vehicle\rvp\assets\rvp\animations\bedrock\entity\vt4.animation.json`
- 动画控制器：
  `D:\ywzj\ywzj\ywzj_rvp\limitless_vehicle\rvp\assets\rvp\animation_controllers\vt4_controller.json`
- 动画脚本：
  `D:\ywzj\ywzj\ywzj_rvp\limitless_vehicle\rvp\assets\rvp\scripts\vt4.js`

---

## 渲染模型层级

identifier：`geometry.unknown`，共 **177** 根骨骼，根骨骼：

- `VT4A1`

一级主分组：

- `VT4A1 -> $body`
- `VT4A1 -> $weapon0`
- `VT4A1 -> FZL`
- `VT4A1 -> lvdai_r`
- `VT4A1 -> lvdai_l`

主炮链：

- `$weapon0 -> $weapon0_0`
- `$weapon0_0 -> $weapon0_1`
- `$weapon0_1 -> turret_muzzle_flash`（5 个 cube，炮口特效挂点）

车长机枪链：

- `$weapon0 -> $weapon1`
- `$weapon1 -> $weapon1_0`
- `$weapon1_0 -> $weapon1_0_0`
  $weapon1_0_0为加特林转管机枪，转管机枪动画参考CSSA5和LAVAD

炮塔 ERA 分组：

- `$weapon0 -> ERA_PAOTA`
- `ERA_PAOTA -> $ERA3`
- `ERA_PAOTA -> $ERA4`
- `ERA_PAOTA -> $ERA5`
- `ERA_PAOTA -> $ERA6`

车体 ERA 分组：

- `$body -> ERA_BODY`
- `ERA_BODY -> $ERA0`
- `ERA_BODY -> $ERA1`
- `ERA_BODY -> $ERA2`

APS 渲染骨骼：

- `$weapon0 -> aps_l_base`
- `aps_l_base -> aps_l_launcher`
- `$weapon0 -> aps_r_base`
- `aps_r_base -> aps_r_launcher`

负重轮链：

- `VT4A1 -> FZL`
- `FZL -> FZL0`
- `FZL -> FZL1`
- …
- `FZL -> FZL7`（共 8 对，单组命名，无 `_L`/`_R` 后缀）

履带链：

- `VT4A1 -> lvdai_r -> tread_r_0 ~ tread_r_71`（右履带 72 段）
- `VT4A1 -> lvdai_l -> tread_l_0 ~ tread_l_71`（左履带 72 段）

---

## 结构模型层级

共 **36** 根骨骼，根骨骼：

- `bone`

一级主分组：

- `bone -> vehicle_body`
- `bone -> turret`

车体命中区：

- `vehicle_body -> ERA_UP`
- `ERA_UP -> ERA0`
- `ERA_UP -> ERA1`
- `ERA_UP -> ERA2`
- `vehicle_body -> track`（2 个 cube）
- `vehicle_body -> Engine`
- `vehicle_body -> Upper_front`（3 个 cube）
- `vehicle_body -> Lower_front`
- `vehicle_body -> Periscope`
- `vehicle_body -> virtual`（预留虚拟区）

炮塔命中区：

- `turret -> top`
- `turret -> turret_bellow`
- `turret -> turret_side`（4 个 cube）
- `turret -> turret_barrel`
- `turret_barrel -> turret_machine_gun_barrel`
- `turret -> turret_smoke_grenade_r_barrel`
- `turret -> turret_smoke_grenade_l_barrel`
- `turret -> commander_machine_gun`
- `commander_machine_gun -> commander_machine_gun_barrel`

炮塔 ERA 分组：

- `turret -> ERA_PAOTA2`
- `ERA_PAOTA2 -> ERA3`
- `ERA_PAOTA2 -> ERA4`
- `ERA_PAOTA2 -> ERA5`
- `ERA_PAOTA2 -> ERA6`

APS 结构骨骼：

- `turret -> aps_radar`（APS 雷达组）
- `aps_radar -> aps_radar_ne`
- `aps_radar -> aps_radar_nw`
- `aps_radar -> aps_radar_se`
- `aps_radar -> aps_radar_sw`
- `turret -> aps_l`
- `aps_l -> aps_l_barrel`
- `turret -> aps_r`
- `aps_r -> aps_r_barrel`（2026-09-02 已由 `aps_l_barrel2` 改名，见配置清单 §5）

---

## VT4 结构特点

- 根骨骼名为 `VT4A1`（与载具 id `vt4` 不同，配置动画控制器时注意区分）
- 主炮链是标准的 `$weapon0 -> $weapon0_0 -> $weapon0_1`，炮口特效骨骼 `turret_muzzle_flash` 挂在炮管末端
- 车长机枪链完整（`$weapon1` 三级），可独立做旋转/俯仰动画
- ERA 拆成两组：车体 `ERA_BODY -> $ERA0~2`（渲染）/ `ERA_UP -> ERA0~2`（结构），炮塔 `ERA_PAOTA -> $ERA3~6`（渲染）/ `ERA_PAOTA2 -> ERA3~6`（结构）
- APS 比照 M1A2SEP 预留了发射器骨骼，且额外做了 **APS 雷达四向组**（`aps_radar` 的 ne/nw/se/sw 四根子骨），适合做四向探测位
- 烟幕弹发射器**左右拆分**为 `turret_smoke_grenade_l_barrel` / `turret_smoke_grenade_r_barrel`（M1A2SEP/T90M 均为单骨）
- 履带是**双侧独立链**：`lvdai_r` / `lvdai_l` 各 72 段（M1A2SEP/T90M 文档中只记录了 `LVDAI -> tread_r_*` 单侧）
- 负重轮单组命名 `FZL0 ~ FZL7`（8 个），与 M1A2SEP（FZL0~8）一致、无左右拆分

---

## 与 M1A2SEP / T90M 的差异对照

| 项 | M1A2SEP | T90M | VT4（新） |
| --- | --- | --- | --- |
| 根骨骼 | `M1A2` | `T90M` | `VT4A1` |
| 负重轮 | `FZL0~FZL8`（9） | `FZL0~FZL7`（8） | `FZL0~FZL7`（8） |
| 履带 | `LVDAI -> tread_r_0~70` | `LVDAI -> tread_r_0~76` | `lvdai_r/l -> tread_r/l_0~71`（双侧独立分组） |
| 炮塔 ERA | 无独立组 | `ERA_PAOTA -> $ERA6~12` | `ERA_PAOTA -> $ERA3~6` |
| 车体 ERA | `ERA -> $ERA0~5` | `ERA_CHETI -> $ERA0~5,13~15` | `ERA_BODY -> $ERA0~2` |
| APS | `aps_left/right`（结构） | 无 | 渲染 `aps_l/r_base -> aps_l/r_launcher` + 结构 `aps_l/aps_r` + **`aps_radar` 四向雷达组** |
| 烟幕弹 | 单骨 | 单骨 | **左右双骨** |

---

## 推荐碰撞箱别名与倍率占位

本节用于给后续 `hitbox_display_name` 与 `hitbox_damage_factor` 配置提供一份直接可抄的参考表。

约定：

- "推荐别名"面向游戏内命中 debug 显示，优先保证短、直观、好读
- "伤害倍率"一列留空，按你的平衡方案手动填写
- ERA 别名只写部位，不保留编号语义；`ERA_UP` 组（车体上部的 3 块）具体叫"首上爆反"还是"车体爆反"请按模型上实际位置定
- `virtual` 为预留虚拟命中区，是否参与倍率配置自行决定

### 命中区

| bone | 推荐别名 | 伤害倍率 |
| --- | --- | --- |
| `vehicle_body` | 车体侧面 | 1.0 |
| `track` | 履带 | 0.5 |
| `Engine` | 发动机舱 | 1.3 |
| `Upper_front` | 首上 | 0.6 |
| `Lower_front` | 首下 | 0.8 |
| `Periscope` | 潜望镜 | 0.8 |
| `virtual` | （预留） | 无 |
| `turret` | 炮塔 | 0.6 |
| `top` | 炮塔顶部 | 1.0 |
| `turret_bellow` | 炮塔尾舱 | 1.3 |
| `turret_side` | 炮塔侧面 | 1.0 |
| `turret_barrel` | 主炮 | 0.4 |
| `turret_machine_gun_barrel` | 同轴机枪 | 0.4 |
| `turret_smoke_grenade_l_barrel` | 烟幕弹发射器（左） | 0.4 |
| `turret_smoke_grenade_r_barrel` | 烟幕弹发射器（右） | 0.4 |
| `commander_machine_gun` | 车长机枪 | 0.4 |
| `commander_machine_gun_barrel` | 车长机枪 | 0.4 |

### ERA 命中区

| bone | 推荐别名 | 伤害倍率 |
| --- | --- | --- |
| `ERA0` | 车体爆反 | 0.3 |
| `ERA1` | 车体爆反 | 0.3 |
| `ERA2` | 车体爆反 | 0.3 |
| `ERA3` | 炮塔爆反 | 0.3 |
| `ERA4` | 炮塔爆反 | 0.3 |
| `ERA5` | 炮塔爆反 | 0.3 |
| `ERA6` | 炮塔爆反 | 0.3 |

### APS 命中区

| bone | 推荐别名 | 伤害倍率 |
| --- | --- | --- |
| `aps_radar` | APS雷达 | 0.6 |
| `aps_radar_ne` | APS雷达 | 0.6 |
| `aps_radar_nw` | APS雷达 | 0.6 |
| `aps_radar_se` | APS雷达 | 0.6 |
| `aps_radar_sw` | APS雷达 | 0.6 |
| `aps_l` | APS拦截弹（左） | 0.4 |
| `aps_l_barrel` | APS拦截弹（左） | 0.4 |
| `aps_r` | APS拦截弹（右） | 0.4 |
| `aps_r_barrel` | APS拦截弹（右） | 0.4 |

---

## 重做 VT4 需要同步修改的配置清单

新模型骨骼名与旧配置的引用做了全量比对，以下按文件列出**必须改**的项。

### 1. `data/rvp/vehicles/vt4.json`（载具数据，改动最多）

| 旧引用 | 现状 | 改法 |
| --- | --- | --- |
| parts 里 `structure_bone: "aps_right"` / `"aps_left"` | 新结构模型无此骨 | 改为 `aps_r` / `aps_l` |
| `bone_modules` 键 `aps_sensor_right` / `aps_sensor_left`（APS 传感器挂骨） | 新结构模型无此二骨 | 挂到 `aps_radar`（四向雷达组）或其 ne/nw/se/sw 子骨上按朝向拆分；`launcher_part` 同步改 `aps_r` / `aps_l`，`facing_yaw` 按新朝向调 |
| `bone_modules` 键 `ecm_bone`（主动 ECM 挂骨） | 新结构模型没有 ECM 骨骼 | 改挂 `turret`，或改用无骨骼虚拟键 `__vehicle__`（参考其它车的 ECM 写法），或在新结构模型里补一根骨（动结构模型前先备份） |
| parts 里 `structure_bone: "turret_smoke_grenade"` | 新结构模型拆成左右双骨 | 拆成两个武器件分别挂 `turret_smoke_grenade_l_barrel` / `turret_smoke_grenade_r_barrel`（或先并到 `turret` 上跑通再拆） |
| `hitbox_damage_factor` / `hitbox_display_name` | 当前完全没有 | 按上文"推荐碰撞箱别名与倍率占位"表新建，倍率你填 |

### 2. `assets/rvp/animation_controllers/vt4_controller.json`（动画控制器）

| 旧引用 | 现状 | 改法 |
| --- | --- | --- |
| 负重轮绑定 `FZL0_L~FZL7_L` / `FZL0_R~FZL7_R` | 新渲染模型是单组 `FZL0~FZL7` | 照 `m1a2sep_controller.json` 的 FZL 绑定写法改成单组名 |
| `event_animations: turret_fire -> ["turret_shoot"]` | 新动画文件里该动画改名为 `cannon_shoot` | 改为 `turret_fire -> ["cannon_shoot"]` |
| 图中 `APS_L` / `APS_L_SHOT` / `APS_R` / `APS_R_SHOT` 节点 | 新动画文件里**没有**这些动画 | 补做 APS 发射/装填动画，或删掉这些节点 |
| 图中 `machine_gun` / `machine_gun_high` / `octagon` 相关节点 | 新动画文件里没有对应动画与骨骼 | 同上：补资源或删节点 |

### 3. `assets/rvp/scripts/vt4.js`（动画脚本）

- 引用骨骼 `machine_gun_rotator`（车长机枪管旋转）——新渲染模型无此骨。改为新链末端 `$weapon1_0_0`，或删掉这段逻辑（控制器 `"script": "rvp:vt4"` 一并考虑）。
  还要加入ERA渲染

### 4. `assets/rvp/display/vehicle/vt4.json`（载具 display，基本不用动）

- `special_bone_effects` 的 `turret_muzzle_flash`：新模型仍存在 ✓
- `track_config` 的 `tread_l_move` / `tread_r_move`：新动画文件里有 ✓；`module_length`（0.25）/`track_width`（3.0）按新模型履带节距视需要微调
- 引擎音效沿用 ztz99a，不动

### 5. 新资源内部的两处对齐问题（✅ 已于 2026-09-02 处理，原文件备份为 `*.bak_20260902`）

- ~~新动画 `cannon_shoot` 动画了骨骼 `octagon2`~~ → 已从动画中删除该轨，现 `cannon_shoot` 只驱动 `$weapon0_1` 与 `turret_muzzle_flash`
- ~~结构模型 `aps_r` 的子骨名为 `aps_l_barrel2`~~ → 已改名为 `aps_r_barrel`（全包仅此一处引用，无其它配置受影响）

### 6. 贴图

- `textures/entity/vt4.png` 文件日期还是 5 月的旧档——你说的重做贴图记得放入，并核对 UV 与新渲染模型匹配；`textures/slot/vt4.png` 图标视需要重画

### 不需要动的

- `data/rvp/weapons/vt4_*.json`（bea10/dtb10/dtc10/dtp10/gp125，纯弹药数据无骨骼引用）
- `data/rvp/recipe/`、`data/rvp/recipes/`
- `attributes` / `view_info` / `defense_stats`（无骨骼引用，数值是否重调由你定）
