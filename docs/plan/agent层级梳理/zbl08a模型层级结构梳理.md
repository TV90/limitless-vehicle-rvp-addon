# ZBL-08A 模型层级结构梳理

本文档用于记录 `zbl08a` 当前在游戏目录中的渲染模型、结构模型层级关系，便于后续配置动画、武器站、车轮、导弹、机枪等逻辑。

## 文件位置

- 渲染模型：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\assets\rvp\models\bedrock\entity\zbl08a.json`
- 结构模型：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\data\rvp\models\bedrock\vehicle\zbl08a.structure.json`
- 实体动画：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\assets\rvp\animations\bedrock\entity\zbl08a.animation.json`
- 结构动画：
  `E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\data\rvp\models\bedrock\vehicle\zbl08a.animation.json`

---

## ZBL-08A

### 渲染模型层级

根骨骼：

- `ZBL08A`

一级主分组：

- `ZBL08A -> $body`
- `ZBL08A -> $weapon0`
- `ZBL08A -> wheel0`
- `ZBL08A -> wheel1`
- `ZBL08A -> wheel2`
- `ZBL08A -> wheel3`
- `ZBL08A -> wheel4`
- `ZBL08A -> wheel5`
- `ZBL08A -> wheel6`
- `ZBL08A -> wheel7`

主炮链：

- `$weapon0 -> $weapon0_0`
- `$weapon0_0 -> $weapon0_1`
- `$weapon0_1 -> turret_muzzle_flash`

车长机枪链：

- `$weapon0 -> $weapon1`
- `$weapon1 -> $weapon1_0`

导弹发射架链：

- `$weapon0 -> $weapon0_2`
- `$weapon0_2 -> $weapon0_2_missile1`
- `$weapon0_2 -> $weapon0_2_missile2`

### 结构模型层级

根骨骼：

- 无根包裹骨，`vehicle_body` 与 `turret` 平级

一级主分组：

- `vehicle_body`
- `turret`

车体命中区：

- `vehicle_body -> lower_front`
- `vehicle_body -> upper_front`
- `vehicle_body -> passenger`
- `vehicle_body -> wheel`
- `vehicle_body -> virtual`（为仅物理骨骼）

炮塔命中区：

- `turret -> top`
- `turret -> turret_side`
- `turret -> turret_barrel`
- `turret -> smoke_barrel`
- `turret -> missile`
- `turret -> missile_barrel`
- `turret -> commander_machine_gun`
- `commander_machine_gun -> commander_machine_gun_barrel`

### ZBL-08A 结构特点

- 主炮链标准：`$weapon0 -> $weapon0_0 -> $weapon0_1`，且带独立炮口焰骨骼 `turret_muzzle_flash`
- 车长机枪单独成链：`$weapon1 -> $weapon1_0`，可独立做俯仰、开火后坐
- 导弹发射架单独成链：`$weapon0_2 -> $weapon0_2_missile1 / $weapon0_2_missile2`，两枚导弹，挂在炮塔下随炮塔旋转
- 4 轴 8 轮直接挂在根骨骼下（`wheel0 ~ wheel7`），无 FZL 分组骨，左右成对（`wheel0/1` 为最前轴，`wheel6/7` 为最后轴）
- 结构模型无 `turret_machine_gun` / `turret_smoke_grenade` 骨，烟幕弹发射器为 `smoke_barrel`
- 结构模型含 `virtual` 全车包围盒（碰撞代理，可作 `physics_only_bone`）
- 渲染 `$weapon0` 与结构 `turret` 同枢轴 `[0, 35.54, -0.01]`

---

## 推荐碰撞箱别名与倍率占位

本节用于给后续 `hitbox_display_name` 与 `hitbox_damage_factor` 配置提供一份直接可抄的参考表。

约定：

- “推荐别名”面向游戏内命中 debug 显示，优先保证短、直观、好读
- “伤害倍率”一列暂时留空，后续按你的平衡方案手动填写

| bone | 推荐别名 | 伤害倍率 |
| --- | --- | --- |
| `vehicle_body` | 车体 | 1.0 |
| `lower_front` | 首下 | 0.8 |
| `upper_front` | 首上 | 0.6 |
| `passenger` | 载员舱 | 1.3 |
| `wheel` | 车轮 | 0.4 |
| `turret` | 炮塔 | 0.6 |
| `top` | 炮塔顶部 | 1.0 |
| `turret_side` | 炮塔侧后 | 1.0 |
| `turret_barrel` | 机炮 | 0.4 |
| `smoke_barrel` | 烟幕弹发射器 | 0.8 |
| `missile` | 导弹发射架 | 1 |
| `missile_barrel` | 导弹发射管 | 1.3 |
| `commander_machine_gun` | 武器站 | 0.4 |
| `commander_machine_gun_barrel` | 武器站 | 0.4 |
