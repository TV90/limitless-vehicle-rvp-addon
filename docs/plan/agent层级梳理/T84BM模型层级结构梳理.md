# T84BM 模型层级结构梳理

本文档用于记录 `T84BM` 的渲染模型、结构模型层级关系，便于后续配置动画、武器站、履带、ERA、负重轮、机枪等逻辑。T84BM 整体结构类似于 `T90M`，差异点见文末。

## 文件位置

### T84BM（开发目录 run/client_1）

- 渲染模型：
  `D:\ywzj\ywzj\ywzj_rvp\run\client_1\limitless_vehicle\rvp\assets\rvp\models\bedrock\entity\t84bm.json`
- 结构模型：
  `D:\ywzj\ywzj\ywzj_rvp\run\client_1\limitless_vehicle\rvp\data\rvp\models\bedrock\vehicle\t84bm.structure.json`
- 动画文件：
  `D:\ywzj\ywzj\ywzj_rvp\run\client_1\limitless_vehicle\rvp\assets\rvp\animations\bedrock\entity\t84bm.animation.json`
- 实体贴图：
  `D:\ywzj\ywzj\ywzj_rvp\run\client_1\limitless_vehicle\rvp\assets\rvp\textures\entity\t84bm.png`
- 槽位贴图：
  `D:\ywzj\ywzj\ywzj_rvp\run\client_1\limitless_vehicle\rvp\assets\rvp\textures\slot\t84bm.png`

> 注意：当前 `run/server`、`run/client_2` 与游戏目录
> `E:\client_ywzj - 副本\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp`
> 尚未完整同步模型/贴图文件（缺结构模型、动画、贴图等），详见《待补充文件清单》。

---

## 渲染模型层级

根骨骼：

- `t84`

一级主分组：

- `t84 -> $body`
- `t84 -> $weapon0`
- `t84 -> FZL`
- `t84 -> LVDAI`

车体链：

- `$body -> shoushang_era`（首上爆反分组）
- `shoushang_era -> $ERA4`
- `shoushang_era -> $ERA5`
- `shoushang_era -> $ERA6`
- `shoushang_era -> $ERA7`
- `$body -> cemian_era`（侧面爆反分组）
- `cemian_era -> $ERA11`
- `cemian_era -> $ERA12`
- `cemian_era -> $ERA13`
- `cemian_era -> $ERA8`
- `cemian_era -> $ERA9`
- `cemian_era -> $ERA10`

炮塔链：

- `$weapon0 -> $weapon0_0`
- `$weapon0_0 -> $weapon0_1`
- `$weapon0 -> guanmiao`（观瞄）

车长机枪链：

- `$weapon0 -> $weapon1`
- `$weapon1 -> $weapon1_0`

炮塔 ERA 分组：

- `$weapon0 -> paota_era`
- `paota_era -> $ERA0`
- `paota_era -> $ERA1`
- `paota_era -> $ERA2`
- `paota_era -> $ERA3`

负重轮链：

- `t84 -> FZL`
- `FZL -> FZL0`
- `FZL -> FZL1`
- `FZL -> FZL2`
- `FZL -> FZL3`
- `FZL -> FZL4`
- `FZL -> FZL5`
- `FZL -> FZL6`
- `FZL -> FZL7`
- `FZL -> FZL8`
- `FZL -> FZL9`
- `FZL -> FZL10`
- `FZL -> FZL11`
- `FZL -> FZL12`

履带链：

- `LVDAI -> tread_r_0 ~ tread_r_86`（共 87 段）

### 渲染模型动画键

动画文件包含 4 个动画：

- `tread_r_move`：履带运动
- `cannon_fire`：主炮开火
- `machingun_fire`：机枪开火（注意拼写为 `machingun_fire`，非 `machinegun_fire`）
- `static`：静态位姿

---

## 结构模型层级

根骨骼：

- `bone`

一级主分组：

- `bone -> vehicle_body`
- `bone -> turret`

车体命中区：

- `vehicle_body -> ERA_SIDE`
- `ERA_SIDE -> ERA8`
- `ERA_SIDE -> ERA9`
- `ERA_SIDE -> ERA10`
- `ERA_SIDE -> ERA11`
- `ERA_SIDE -> ERA12`
- `ERA_SIDE -> ERA13`
- `vehicle_body -> ERA_UP`
- `ERA_UP -> ERA4`
- `ERA_UP -> ERA5`
- `ERA_UP -> ERA6`
- `ERA_UP -> ERA7`
- `vehicle_body -> track`
- `vehicle_body -> Engine`
- `vehicle_body -> Upper_front`
- `vehicle_body -> Lower_front`
- `vehicle_body -> Periscope`
- `vehicle_body -> virtual`（物理骨骼，对应 `physics_info.physics_only_bone: "virtual"`）

炮塔命中区：

- `turret -> turret_era`
- `turret_era -> ERA0`
- `turret_era -> ERA1`
- `turret_era -> ERA2`
- `turret_era -> ERA3`
- `turret -> top`
- `turret -> turret_bellow`
- `turret -> turret_side`
- `turret -> turret_barrel`
- `turret_barrel -> turret_machine_gun_barrel`（同轴机枪挂在主炮下）
- `turret -> turret_smoke_grenade_barrel`
- `turret -> commander_machine_gun`
- `commander_machine_gun -> commander_machine_gun_barrel`

### ERA 编号映射关系

结构模型与渲染模型的 ERA 编号一一对应（同号同部位）：

| 部位 | 结构模型骨块 | 渲染模型骨骼 |
| --- | --- | --- |
| 炮塔爆反 | `ERA0` ~ `ERA3`（`turret_era`） | `$ERA0` ~ `$ERA3`（`paota_era`） |
| 首上爆反 | `ERA4` ~ `ERA7`（`ERA_UP`） | `$ERA4` ~ `$ERA7`（`shoushang_era`） |
| 车体侧爆反 | `ERA8` ~ `ERA13`（`ERA_SIDE`） | `$ERA8` ~ `$ERA13`（`cemian_era`） |

---

## T84BM 结构特点

- 主炮链同样是标准 `weapon0 -> weapon0_0 -> weapon0_1`
- 车长机枪链完整（`weapon1 -> weapon1_0`），可单独做运动和开火动画
- ERA 按「炮塔 / 首上 / 侧面」三组拆分，各 4 / 4 / 6 块，便于细粒度失效显示
- **同轴机枪（`turret_machine_gun_barrel`）挂在主炮 `turret_barrel` 下**，与 T90M 直接挂 `turret` 不同，主炮俯仰时会跟随
- `FZL` 对应负重轮组（13 个：`FZL0` ~ `FZL12`），不是烟幕弹组
- 履带链较长，为 `tread_r_0` ~ `tread_r_86`（87 段）
- 炮塔附属块较多：`top`、`turret_bellow`、`turret_side`、`turret_era` 分组
- 存在 `virtual` 物理骨骼，车辆主配置中 `physics_only_bone` 需指向它

---

## 与 T90M 的差异

| 项目 | T90M | T84BM |
| --- | --- | --- |
| 渲染根骨骼 | `T90M` | `t84` |
| 负重轮数量 | `FZL0` ~ `FZL7`（8 个） | `FZL0` ~ `FZL12`（13 个） |
| 履带段数 | `tread_r_0` ~ `tread_r_76`（77 段） | `tread_r_0` ~ `tread_r_86`（87 段） |
| ERA 分组 | 车体侧 `ERA0-5`、炮塔 `ERA6-12`、首上 `ERA13-15` | 炮塔 `ERA0-3`、首上 `ERA4-7`、侧面 `ERA8-13` |
| 同轴机枪挂点 | 直接挂 `turret` | 挂在 `turret_barrel` 下 |
| 炮塔 ERA 结构 | 无 `turret_era` 中间分组，直接挂 `turret` | 有 `turret_era` 中间分组 |
| 观瞄骨骼 | 观瞄骨骼集成于车长机枪 | `$weapon0 -> guanmiao` |
| 机枪开火动画键 | `machinegun_fire` | `machingun_fire`（拼写不同） |

---

## 推荐碰撞箱别名与倍率占位

本节用于给后续 `hitbox_display_name` 与 `hitbox_damage_factor` 配置提供参考表。

约定：

- “推荐别名”面向游戏内命中 debug 显示，优先保证短、直观、好读
- “伤害倍率”一列**暂时留空**，后续按平衡方案手动填写
- ERA 统一只写部位，不在别名中保留编号语义

| bone | 推荐别名 | 伤害倍率 |
| --- | --- | --- |
| `vehicle_body` | 车体侧面 | 1.0 |
| `track` | 履带 | 0.4 |
| `Engine` | 发动机舱 | 1.3 |
| `Upper_front` | 首上 | 0.65 |
| `Lower_front` | 首下 | 0.8 |
| `Periscope` | 潜望镜 | 0.8 |
| `virtual` | （物理骨骼，不配置） |  |
| `turret` | 炮塔正面 | 0.6 |
| `top` | 炮塔顶部 | 1.0 |
| `turret_bellow` | 炮塔尾舱 | 1.3 |
| `turret_side` | 炮塔侧面 | 1.0 |
| `turret_barrel` | 主炮 | 0.4 |
| `turret_machine_gun_barrel` | 同轴机枪 | 0.4 |
| `turret_smoke_grenade_barrel` | 烟幕弹发射器 | 0.4 |
| `commander_machine_gun` | 武器站 | 0.4 |
| `commander_machine_gun_barrel` | 武器站 | 0.4 |
| `ERA0` | 炮塔爆反 | 0.2 |
| `ERA1` | 炮塔爆反 | 0.2 |
| `ERA2` | 炮塔爆反 | 0.2 |
| `ERA3` | 炮塔爆反 | 0.2 |
| `ERA4` | 首上爆反 | 0.2 |
| `ERA5` | 首上爆反 | 0.2 |
| `ERA6` | 首上爆反 | 0.2 |
| `ERA7` | 首上爆反 | 0.2 |
| `ERA8` | 车体侧爆反 | 0.3 |
| `ERA9` | 车体侧爆反 | 0.45 |
| `ERA10` | 车体侧爆反 | 0.65 |
| `ERA11` | 车体侧爆反 | 0.3 |
| `ERA12` | 车体侧爆反 | 0.45 |
| `ERA13` | 车体侧爆反 | 0.65 |

---

## 待补充文件清单

T84BM 现在只有模型/动画/贴图，还缺以下 RVP 配套文件（以 T90M 为模板）：

1. **车辆主配置**：`data\rvp\vehicles\t84bm.json` ✅
   - 复制 `t90m.json` 改名为 `t84bm.json`，调整：`structure_model` 指向 `rvp:vehicle/t84bm`、`hitbox_damage_factor` / `hitbox_display_name` 按本表填写（倍率留空）、`bone_modules` 的骨块名按 T84BM 的 ERA 分布（`ERA0`~`ERA13`）配置、`physics_only_bone: "virtual"`、`turret_machine_gun_barrel` 仍作为武器挂点（挂在主炮下）等
2. **display 配置**：`assets\rvp\display\vehicle\t84bm.json` ✅
   - 复制 `t90m.json`，改 `model` 为 `rvp:entity/t84bm`、`texture`/`slot_texture` 为 `t84bm`、`animations` 为 `rvp:entity/t84bm.animation`、`animation_controller` 为 `rvp:t84bm_controller`、`track_config` 沿用（左右履带均 `tread_r_move`）
3. **动画控制器**：`assets\rvp\animation_controllers\t84bm_controller.json` ✅
   - 复制 `t90m_controller.json`，脚本指向 `rvp:t84bm`，`special_bindings` 负重轮列表改为 `FZL0` ~ `FZL12`（13 个），注意机枪开火事件动画键用 `machingun_fire`；并新增 `guanmiao` 部分绑定（`guanmiao → commander_machine_gun`，绕自身骨骼 y 旋转），实现机枪手转动机枪时观瞄镜与机枪各自绕自身枢轴旋转
4. **JS 脚本**：`assets\rvp\scripts\t84bm.js` ✅
   - 复制 `t90m.js`，把 ERA 隐藏逻辑改为 T84BM 的 14 块（`$ERA0` ~ `$ERA13` 对应 `ERA0` ~ `ERA13`）
5. **合成配方**：`data\rvp\recipes\t84bm.json`（可选）
6. **语言条目**：`lang\zh_cn.json` / `lang\en_us.json` 增加 `entity.rvp.t84bm` 与 `rvp.t84bm` ✅
7. **文件分发**：将渲染模型、结构模型、动画、贴图、槽位图同步到 `run/server`、`run/client_2` 及游戏目录 `E:\client_ywzj - 副本\...\limitless_vehicle\rvp` ⏳
8. **武器**：AP组：t84bm_3bm42/t84bm_3bk12   HE组：t84bm_3of26
   组内改装工具二选一，组外按F切换，写法参考T90M ✅（含 display 声音配置）
9. **特殊骨骼**：virtual（纯物理），ERA0-13爆反，除此外，ERA1和ERA3还要承担光电干扰机效果 ✅

已按 T90M 方案完成的额外项：
- 主炮观瞄：BB `(9,42,22)` 减炮塔枢轴 `(0,27.0973,0)` 除 16 → `optical_sight_offset [0.5625, 0.93141875, 1.375]`（OPERATOR 类型随 xTurnGroup 旋转）
- 机枪观瞄：BB `(-12,50,12)` 以渲染模型 `guanmiao` 骨骼枢轴 `(12.4054,42.2098,6.9971)` 为基准 → `optical_sight_offset [-1.5253375, 0.4868875, 0.31268125]` + `rvp_optical_sight_pivot [12.4054,42.2098,6.9971]`（新增 RVP 参数，见《RVP包新增参数字段说明.md》3.1 节）
- 倒车速度 `max_speed_backward 0.25` = 前进 `0.5` 的一半
- 方向机 `y_rot_speed 2.0`（40 度/秒）、高低机 `x_rot_speed 0.25`（5 度/秒）
