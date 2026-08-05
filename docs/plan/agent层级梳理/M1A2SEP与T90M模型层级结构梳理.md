# M1A2SEP 与 T90M 模型层级结构梳理

本文档用于记录 `M1A2SEP` 与 `T90M` 当前在游戏目录中的渲染模型、结构模型层级关系，便于后续配置动画、武器站、履带、ERA、负重轮、机枪等逻辑。

## 文件位置

### M1A2SEP

- 渲染模型：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\assets\rvp\models\bedrock\entity\m1a2sep.json`
- 结构模型：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\data\rvp\models\bedrock\vehicle\m1a2sep.structure.json`
- 动画文件：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\assets\rvp\models\bedrock\entity\m1a2sep.animation.json`

### T90M

- 渲染模型：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\assets\rvp\models\bedrock\entity\t90m.json`
- 结构模型：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\data\rvp\models\bedrock\vehicle\t90m.structure.json`
- 动画文件：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\assets\rvp\animations\bedrock\entity\t90m.animation.json`

---

## M1A2SEP

### 渲染模型层级

根骨骼：

- `M1A2`

一级主分组：

- `M1A2 -> $body`
- `M1A2 -> $weapon0`
- `M1A2 -> FZL`
- `M1A2 -> LVDAI`

车体链：

- `$body -> ERA`
- `ERA -> $ERA0`
- `ERA -> $ERA1`
- `ERA -> $ERA2`
- `ERA -> $ERA3`
- `ERA -> $ERA4`
- `ERA -> $ERA5`

炮塔链：

- `$weapon0 -> sign`
- `$weapon0 -> $weapon0_0`
- `$weapon0_0 -> $weapon0_1`

车长机枪链：

- `$weapon0 -> $weapon1`
- `$weapon1 -> $weapon1_0`
- `$weapon1_0 -> $weapon1_0_0`

负重轮链：

- `M1A2 -> FZL`
- `FZL -> FZL0`
- `FZL -> FZL1`
- `FZL -> FZL2`
- `FZL -> FZL3`
- `FZL -> FZL4`
- `FZL -> FZL5`
- `FZL -> FZL6`
- `FZL -> FZL7`
- `FZL -> FZL8`

履带链：

- `LVDAI -> tread_r_0 ~ tread_r_70`

### 结构模型层级

根骨骼：

- `bone`

一级主分组：

- `bone -> vehicle_body`
- `bone -> turret`

车体命中区：

- `vehicle_body -> ERA0`
- `vehicle_body -> ERA1`
- `vehicle_body -> ERA2`
- `vehicle_body -> ERA3`
- `vehicle_body -> ERA4`
- `vehicle_body -> ERA5`
- `vehicle_body -> track`
- `vehicle_body -> Engine`
- `vehicle_body -> Upper_front`
- `vehicle_body -> Lower_front`
- `vehicle_body -> Periscope`

炮塔命中区：

- `turret -> turret_r`
- `turret -> turret_back`
- `turret -> turret_top`
- `turret -> turret_l`
- `turret -> turret_barrel`
- `turret -> turret_machine_gun_barrel`
- `turret -> turret_smoke_grenade_barrel`
- `turret -> commander_machine_gun`
- `commander_machine_gun -> commander_machine_gun_barrel`

APS 结构骨骼：

- `turret -> aps_left`
- `aps_left -> aps_left_barrel`
- `turret -> aps_right`
- `aps_right -> aps_right_barrel`

### M1A2SEP 结构特点

- 炮塔主链非常标准：`$weapon0 -> $weapon0_0 -> $weapon0_1`
- 车长机枪也单独成链，后续可独立做俯仰、开火后坐
- 车体 ERA 在渲染模型与结构模型中都已经拆分
- `FZL` 对应负重轮组，不是烟幕弹组
- 当前 `FZL` 已经独立挂在根骨骼 `M1A2` 下，不再作为炮塔子骨骼
- 额外预留了 APS 相关骨骼，后续若做 APS 发射动画或发射位会比较方便
- 履带不是简单的轮子组，而是完整的履带段骨骼链

---

## T90M

### 渲染模型层级

根骨骼：

- `T90M`

一级主分组：

- `T90M -> $weapon0`
- `T90M -> $body`
- `T90M -> FZL`
- `T90M -> LVDAI`

主炮链：

- `$weapon0 -> $weapon0_0`
- `$weapon0_0 -> $weapon0_1`

车长机枪链：

- `$weapon0 -> $weapon1`
- `$weapon1 -> $weapon1_0`
- `$weapon1_0 -> $weapon1_0_0`

炮塔 ERA 分组：

- `$weapon0 -> ERA_PAOTA`
- `ERA_PAOTA -> $ERA6`
- `ERA_PAOTA -> $ERA7`
- `ERA_PAOTA -> $ERA8`
- `ERA_PAOTA -> $ERA9`
- `ERA_PAOTA -> $ERA10`
- `ERA_PAOTA -> $ERA11`
- `ERA_PAOTA -> $ERA12`

车体 ERA 分组：

- `$body -> ERA_CHETI`
- `ERA_CHETI -> $ERA0`
- `ERA_CHETI -> $ERA1`
- `ERA_CHETI -> $ERA2`
- `ERA_CHETI -> $ERA3`
- `ERA_CHETI -> $ERA4`
- `ERA_CHETI -> $ERA5`
- `ERA_CHETI -> $ERA13`
- `ERA_CHETI -> $ERA14`
- `ERA_CHETI -> $ERA15`

其它炮塔附属骨骼：

- `$weapon0 -> GESHAN`

负重轮链：

- `T90M -> FZL`
- `FZL -> FZL0`
- `FZL -> FZL1`
- `FZL -> FZL2`
- `FZL -> FZL3`
- `FZL -> FZL4`
- `FZL -> FZL5`
- `FZL -> FZL6`
- `FZL -> FZL7`

履带链：

- `LVDAI -> tread_r_0 ~ tread_r_76`

### 结构模型层级

根骨骼：

- `bone`

一级主分组：

- `bone -> vehicle_body`
- `bone -> turret`

车体命中区：

- `vehicle_body -> ERA_SIDE`
- `ERA_SIDE -> ERA0`
- `ERA_SIDE -> ERA1`
- `ERA_SIDE -> ERA2`
- `ERA_SIDE -> ERA3`
- `ERA_SIDE -> ERA4`
- `ERA_SIDE -> ERA5`
- `vehicle_body -> ERA_UP`
- `ERA_UP -> ERA13`
- `ERA_UP -> ERA14`
- `ERA_UP -> ERA15`
- `vehicle_body -> track`
- `vehicle_body -> Engine`
- `vehicle_body -> Upper_front`
- `vehicle_body -> Lower_front`
- `vehicle_body -> Periscope`

炮塔命中区：

- `turret -> top`
- `turret -> turret_bellow`
- `turret -> ERA12`
- `turret -> ERA11`
- `turret -> ERA10`
- `turret -> turret_side`
- `turret -> ERA6`
- `turret -> ERA7`
- `turret -> ERA8`
- `turret -> ERA9`
- `turret -> turret_barrel`
- `turret -> turret_machine_gun_barrel`
- `turret -> turret_smoke_grenade_barrel`
- `turret -> commander_machine_gun`
- `commander_machine_gun -> commander_machine_gun_barrel`

### T90M 结构特点

- 主炮链同样是标准的 `weapon0 -> weapon0_0 -> weapon0_1`
- 车长机枪链完整，可单独做运动和开火动画
- 炮塔 ERA 和车体 ERA 拆得更细，适合后续做更细粒度的失效显示
- `FZL` 对应负重轮组，不是烟幕弹组
- 炮塔附属块较多，例如 `top`、`turret_bellow`、`turret_side`
- 履带链比 M1A2SEP 更长，为 `tread_r_0 ~ tread_r_76`

---

## 两车对比结论

### 共性

- 都是典型的现代坦克层级：`root -> body / turret -> barrel / commander MG / roadwheel / tread`
- 主炮俯仰链都较标准，适合直接映射到 `turret` 与 `turret_barrel`
- 都有完整履带段骨骼链，不是单纯轮组
- 都已经做了 ERA 的渲染骨骼与结构骨骼拆分

### 差异

- `M1A2SEP` 额外预留了 APS 结构骨骼：`aps_left`、`aps_right`
- `T90M` 的 ERA 分层更细，炮塔附属结构更多
- `M1A2SEP` 的负重轮分组数量更多：`FZL0 ~ FZL8`
- `T90M` 的履带链更长：`tread_r_0 ~ tread_r_76`

### 对后续动画配置的意义

- 两车都可以按“车体不转、炮塔水平、主炮俯仰、车长机枪独立、履带单独动”的思路来配置
- `M1A2SEP` 后续若做 APS，更适合直接在现有骨骼上加逻辑
- `T90M` 后续若做受损显示、ERA 消失、炮塔附属件状态变化，会比 M1A2SEP 更容易细化

---

## 推荐碰撞箱别名与倍率占位

本节用于给后续 `hitbox_display_name` 与 `hitbox_damage_factor` 配置提供一份直接可抄的参考表。

约定：

- “推荐别名”面向游戏内命中 debug 显示，优先保证短、直观、好读
- “伤害倍率”一列暂时留空，后续按你的平衡方案手动填写
- ERA 按你的要求统一只写部位，不在别名中保留编号语义

### M1A2SEP

| bone | 推荐别名 | 伤害倍率 |
| --- | --- | --- |
| `vehicle_body` | 车体侧面 | 1.0 |
| `track` | 履带 | 0.5 |
| `Engine` | 发动机舱 | 1.3 |
| `Upper_front` | 首上 | 0.6 |
| `Lower_front` | 首下 | 0.5 |
| `Periscope` | 潜望镜 | 0.8 |
| `turret` | 炮塔 | 0.4 |
| `turret_r` | 炮塔右侧 | 1.0 |
| `turret_back` | 炮塔尾舱 | 1.3 |
| `turret_top` | 炮塔顶部 | 1.0 |
| `turret_l` | 炮塔左侧 | 1.0 |
| `turret_barrel` | 主炮 | 0.4 |
| `turret_machine_gun_barrel` | 同轴机枪 | 0.4 |
| `turret_smoke_grenade_barrel` | 烟幕弹发射器 | 0.4 |
| `commander_machine_gun` | 武器站 | 0.4 |
| `commander_machine_gun_barrel` | 武器站 | 0.4 |
| `ERA0` | 车体侧爆反 | 0.4 |
| `ERA1` | 车体侧爆反 | 0.4 |
| `ERA2` | 车体侧爆反 | 0.7 |
| `ERA3` | 车体侧爆反 | 0.7 |
| `ERA4` | 车体侧爆反 | 0.7 |
| `ERA5` | 车体侧爆反 | 0.7 |

### T90M

| bone | 推荐别名 | 伤害倍率 |
| --- | --- | --- |
| `vehicle_body` | 车体侧面 | 1.0 |
| `track` | 履带 | 0.5 |
| `Engine` | 发动机舱 | 1.3 |
| `Upper_front` | 首上 | 0.6 |
| `Lower_front` | 首下 | 0.8 |
| `Periscope` | 潜望镜 | 0.8 |
| `turret` | 炮塔 | 0.6 |
| `top` | 炮塔顶部 | 1.0 |
| `turret_bellow` | 炮塔尾舱 | 1.3 |
| `turret_side` | 炮塔侧面 | 1.0 |
| `turret_barrel` | 主炮 | 0.4 |
| `turret_machine_gun_barrel` | 同轴机枪 | 0.4 |
| `turret_smoke_grenade_barrel` | 烟幕弹发射器 | 0.4 |
| `commander_machine_gun` | 武器站 | 0.4 |
| `commander_machine_gun_barrel` | 武器站 | 0.4 |
| `ERA0` | 车体侧爆反 | 0.55 |
| `ERA1` | 车体侧爆反 | 0.55 |
| `ERA2` | 车体侧爆反 | 0.55 |
| `ERA3` | 车体侧爆反 | 0.55 |
| `ERA4` | 车体侧爆反 | 0.55 |
| `ERA5` | 车体侧爆反 | 0.55 |
| `ERA13` | 首上爆反 | 0.3 |
| `ERA14` | 首上爆反 | 0.3 |
| `ERA15` | 首上爆反 | 0.3 |
| `ERA6` | 炮塔爆反 | 0.3 |
| `ERA7` | 炮塔爆反 | 0.3 |
| `ERA8` | 炮塔爆反 | 0.3 |
| `ERA9` | 炮塔爆反 | 0.3 |
| `ERA10` | 炮塔侧爆反 | 0.65 |
| `ERA11` | 炮塔侧爆反 | 0.65 |
| `ERA12` | 炮塔后爆反 | 0.8 |
