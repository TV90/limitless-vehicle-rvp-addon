# LAVAD 模型层级结构梳理

本文档记录 `lavad` 当前在游戏目录中的渲染模型、结构模型层级关系，以及要为它补齐的配置文件清单。
LAVAD 定位：8×8 轮式突击车，**炮塔结构参照 CSSA5**（含转管机炮 + 防空导弹），**车体结构参照 LAV25**（8 轮、独立烟幕发射器）。
最贴近的现存模板是 `zbl08a`（同为 8×8 轮式 + 炮塔 + 反坦克导弹），下列脚本/控制器结论均以 zbl08a / cssa5 / lav25 实测为准。

## 文件位置

- 渲染模型（已存在）：
  `E:\client_ywzj - 副本\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\assets\rvp\models\bedrock\entity\lavad.json`
- 结构模型（已存在）：
  `E:\client_ywzj - 副本\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\data\rvp\models\bedrock\vehicle\lavad.structure.json`
- 武器配置（已存在）：
  `…\data\rvp\weapons\lavad_gau12.json`（GAU12 25mm 转管防空炮）
  `…\data\rvp\weapons\lavad_hydra70.json`（九头蛇-70 火箭巢）
  `…\data\rvp\weapons\lavad_fim92.json`（FIM-92K 红外格斗弹）
- 贴图（已存在）：`textures/entity/lavad.png`、`textures/slot/lavad.png`

> ⚠️ 重要纠正（相对上版梳理）：
> 1. **不存在 `data/…/models/bedrock/vehicle/lavad.animation.json` 这种"结构动画"文件**。全 rvp 包仅有 `j20a1` 一个车在 data 侧有结构动画，属特例；zbl08a 参考文档里写的"结构动画"行也是不准确的（zbl08a 实际没有该文件）。**不要新建 data 侧的动画 json。**
> 2. **除 cssa5 的雷达外，没有"独立的每辆车载具动画文件"**。车轮滚动、转向、炮塔随动全部写在 `animation_controllers/lavad_controller.json` 的 `bone_binding` 里；转管机炮的旋转写在 `scripts/lavad.js` 的脚本里。真正的动画 json 只承载两类东西（`static` 静止基姿态、`cannon_fire` 开火后坐+炮口焰事件）——**但这仅适用于 lav25 / zbl08a 这类自带炮口焰模型骨（`turret_muzzle_flash`）的车**。LAVAD 炮塔仿 **cssa5**：实测 cssa5 控制器**无 `loop_animations`/无 `static`、无 `event_animations`/无 `cannon_fire`**（GAU12 炮口焰走 ywzj_vehicle 武器侧通用 muzzle flash，不靠载具动画），故 **LAVAD 同样不写 `static`/`cannon_fire`**，详见第 3、4 点，不要套 zbl08a 的两段式。

---

## LAVAD

### 渲染模型层级

根骨骼：

- `lavad`

一级主分组：

- `lavad -> $body`
- `lavad -> $weapon0`（炮塔/武器站偏航，枢轴 `[-2.2647, 36.5189, -4.727]`）
- `lavad -> wheel0 ~ wheel7`（8 轮，左右成对，无 FZL 分组骨）

主炮/火箭链（GAU12 + HYDRA70 同轴）：

- `$weapon0 -> $weapon0_0`（炮管/火箭巢俯仰，枢轴 `[-3.4553, 42.7028, -11.1473]`）
- `$weapon0_0 -> $weapon0_1`（**转管机炮旋转骨**，GAU12 炮管，枢轴 `[-3.5979, 43.794, -11.1473]`）

防空导弹链（FIM-92）：

- `lavad -> $weapon1`（导弹发射架俯仰，枢轴 `[13.2811, 42.7251, -10.0655]`）

> 注意：LAVAD 渲染模型**没有** `turret_muzzle_flash` 骨骼。实测对照（已用脚本全量扫四个渲染模型）：只有 `lav25` / `zbl08a` 含该骨，`cssa5` **没有**——cssa5 渲染模型武器骨仅 `$weapon0/_0/_1`+`$weapon1~3`，其 `cssa5.animation.json` 也无 `cannon_fire`/`muzzle`/`flash` 任何引用。cssa5 的 GAU12 炮口焰由 ywzj_vehicle **武器开火通用 muzzle flash**（武器侧特效，与载具渲染模型无关）实现——故 **LAVAD 同样不需要给渲染模型补 `turret_muzzle_flash` 骨**，GAU12 炮口焰由 `lavad_gau12.json` 武器侧自带。若你偏好走 zbl08a 那种"模型骨 scale 炮口焰"路线，再额外补骨并让 `cannon_fire` 动画引用它。
> 注意：FIM-92 在渲染模型里只是单一 `$$weapon1` 骨，**没有** `missile0..n` 这类按枚消失的子骨。所以 cssa5 / zbl08a 那种"按剩余弹药隐藏单枚导弹"的逻辑对 LAVAD 不适用（除非你后续给模型加导弹子骨）。

### 结构模型层级

根骨骼：

- 无根包装骨，`vehicle_body` 与 `turret` 平级（与 zbl08a / lav25 一致）

车体命中区：

- `vehicle_body -> lower_front`
- `vehicle_body -> upper_front`
- `vehicle_body -> passenger`
- `vehicle_body -> wheel`
- `vehicle_body -> virtual`（仅物理骨骼，可作 `physics_only_bone`）

炮塔命中区：

- `turret -> turret_side`
- `turret -> turret_barrel`
- `turret -> smoke_barrel`
- `turret -> rocket`（HYDRA70，枢轴同 `turret`）
- `turret -> rocket_barrel`（枢轴同 `turret_barrel`）
- `turret -> missile`（FIM-92，枢轴同 `turret`）
- `turret -> missile_barrel`（枢轴 `[13.2811, 42.7251, -10.0655]`，对应渲染 `$$weapon1`）

### LAVAD 结构特点

- 主炮链标准：`$weapon0 -> $weapon0_0 -> $weapon0_1`，其中 `$weapon0_1` 为转管机炮旋转骨（仿 cssa5：`updateBones` 在 `power>0` 时按 `deltaTime*64` 累加绕 Z 轴旋转）。
- HYDRA70 与 GAU12 **同轴**：结构里 `rocket`/`rocket_barrel` 枢轴分别与 `turret`/`turret_barrel` 完全相同，渲染侧也只有一根 `$weapon0_0` 炮管骨。即火箭巢目前和机炮共用同一个俯仰/偏转骨。若 HYDRA70 本应是独立挂架，渲染模型需补骨并在 `vehicles/lavad.json` 里拆成独立 part。
- FIM-92 为单导轨：`$weapon1` 单独成链，随炮塔偏航 + 自身俯仰（`base: turret`）。
- 8 轮直接挂根（`wheel0~7`），左右成对；前轴 `wheel0/1`、第二节 `wheel2/3`、第三节 `wheel4/5`、后轴 `wheel6/7`。
- 含 `virtual` 全车包围盒（碰撞代理，可作 `physics_only_bone`，参照 lav25 的 `physics_only_bone: "virtual"`）。
- 渲染 `$weapon0` 与结构 `turret` 同枢轴 `[-2.2647, 36.5189, -4.727]`；`$weapon1` 与结构 `missile_barrel` 同枢轴 `[13.2811, 42.7251, -10.0655]`。

---

## 待补充文件清单（强制 7 个 + `lavad.animation.json` 可选省略；无 data 侧动画）

> 约定：下面 `rvp:` 前缀即指 `limitless_vehicle/rvp/` 包内对应路径。
> 控制器与脚本按**命名约定**绑定：`rvp:lavad` 实体 → `lavad_controller.json`（name=`lavad_controller`）+ `scripts/lavad.js`（controller 里 `"script": "rvp:lavad"`）。`vehicles/lavad.json` 无需显式声明 script/controller 字段（cssa5 即如此）。

### 1. `data/rvp/vehicles/lavad.json` —— 主配置（必须）
把武器站、命中区、物理骨骼绑起来。参照 lav25 的 `parts` 结构（车体类），炮塔部分参照 cssa5。需包含：
- `type`: `ywzj_vehicle:wheeled_vehicle`，`structure_model`: `rvp:vehicle/lavad`
- `physics_info.physics_only_bone`: `"virtual"`（仿 lav25）
- `hitbox_damage_factor` / `hitbox_display_name`：见下方占位表
- `parts`：
  - `turret`（weapon，structure_bone=`turret`，带 `seat_offset`/`rot_info`/`with_stabilizer` 等；`weapons` 列表里挂 `rvp:lavad_gau12` 作主炮）
  - `rocket`（weapon，**base:`turret`**，structure_bone=`rocket`，挂 `rvp:lavad_hydra70`，用 `{id, part_unit_id:"rocket"}`）
  - `missile`（weapon，**base:`turret`**，structure_bone=`missile`，`ammo_capacity` 仿 fim92 的 4，挂 `rvp:lavad_fim92`，用 `{id, part_unit_id:"missile"}`；fim92 已自带 `off_axis_stacks_with_station_rotation:true`）
  - `smoke_barrel`（generic，structure_bone=`smoke_barrel`）
  - `vehicle_body` / `lower_front` / `upper_front` / `passenger` / `wheel` / `turret_side` / `turret_barrel`（均 generic，structure_bone 同名）

### 2. `assets/rvp/scripts/lavad.js` —— 脚本动画（必须）
核心实现转管机炮旋转，仿 cssa5 的 `updateBones`：
```js
function updateBones(context) {
    const builder = createPoseBuilder();
    try {
        const prev = context.getFloat("gunBarrelRotation", 0) || 0;
        const dt = Math.max(0, Math.min(5, (context.currentTimeMillis() - context.lastRenderTime()) / 1000 * 20));
        const delta = context.getPower() > 0 ? (dt * 64) : 0;   // 开火时转管旋转
        const rot = (prev + delta) % 360;
        context.setFloat("gunBarrelRotation", rot);
        builder.setRotation("$weapon0_1", 0, 0, rot);            // GAU12 转管骨
    } catch (e) {}
    // FIM-92 为单导轨（$weapon1），无可按枚隐藏的子骨，无需 cssa5/zbl08a 那种导弹 hide 逻辑
    return builder;
}
```
（若后续给模型加了导弹子骨，再补 `hideBone` 逻辑，仿 cssa5 的 `missiles` 数组。）

### 3. `assets/rvp/animation_controllers/lavad_controller.json` —— 控制器（必须）
**仿 `cssa5_controller`（炮塔参照：转管 GAU12 + 防空导弹）**，车轮转向分组**仿 `lav25_controller`（车体参照）**。实测 cssa5_controller：`graph = additive(base: merge([bone_binding, script, switchable_animations scan_radar]))`，**无 `loop_animations`、无 `static`、无 `event_animations`/`cannon_fire`**。LAVAD 无雷达，连 `scan_radar` 也去掉。要点：
- `"script": "rvp:lavad"`
- **不写 `loop_animations` / 不写 `static`**（cssa5 控制器即无 loop static；LAVAD 炮塔仿 cssa5，不需要静止基姿态动画）
- **不写 `event_animations` / 不写 `cannon_fire`**（cssa5 控制器无 `turret_fire→cannon_fire`；GAU12 开火后坐/炮口焰走 ywzj_vehicle 武器侧通用 muzzle flash，与载具动画无关，LAVAD 同 cssa5）
- `graph`：`additive(base: merge([ bone_binding, script ]))`
  - `bone_binding.special_bindings`：
    - 8 轮全 `wheel_rotation`(axis x, param 0.8)
    - 转向**仿 lav25 轴分组（车体参照，区别于 cssa5 的 `wheel0/4`+`wheel1/5`）**：
      - `wheel0,wheel1` ← `steering_angle`(axis y, mult 1.0, ±30)
      - `wheel2,wheel3` ← `steering_angle`(axis y, mult 0.5, ±15)
      - `wheel4~7` 不单独绑转向（随车体）
  - `bone_binding.part_bindings`（炮塔仿 cssa5，无车长机枪/同轴武器）：
    - `$weapon0` → `turret`(y, invert)  ← 同时驱动 GAU12 与 HYDRA70 偏航
    - `$weapon0_0` → `turret`(x)  ← 同时驱动 GAU12 与 HYDRA70 俯仰
    - `$weapon1` → `missile`(x)  ← FIM-92 俯仰
  - `script`: `updateBones`

### 4. `assets/rvp/animations/bedrock/entity/lavad.animation.json` —— 动画（**LAVAD 不需要，可省略**）
**不要套 zbl08a / lav25 的 `static`+`cannon_fire` 两段**。实测：cssa5 动画仅 `radar_loop`（雷达），LAVAD 炮塔仿 cssa5 且无雷达 → 没有任何载具动画可被引用；LAVAD 控制器也不 loop 任何动画、无 `event_animation`。因此：
- **正确做法：不建 `lavad.animation.json`**（或建一个空 `{}` 仅占位，若框架强制要求实体必须有动画文件）。
- 绝不写 `cannon_fire`（GAU12 炮口焰由 `lavad_gau12.json` 武器侧 muzzle flash 提供，仿 cssa5）。
- 不引用 `turret_muzzle_flash`（LAVAD 渲染模型无此骨，仅 lav25 / zbl08a 有）。
- 不需要 `radar_loop`（LAVAD 无雷达）。

### 5. `assets/rvp/display/vehicle/lavad.json` —— 车辆展示模型（必须）
仿 `display/vehicle/cssa5.json` / `lav25.json`（UI/配方里用的展示模型）。

### 6–8. `assets/rvp/display/weapon/lavad_{gau12,hydra70,fim92}.json` —— 武器展示模型（必须）
仿 `display/weapon/cssa5_ahead.json` / `cssa5_hq13.json`，三把武器各一个手持/展示模型。

---

## 推荐碰撞箱别名与倍率占位

用于后续 `hitbox_display_name` 与 `hitbox_damage_factor` 配置，直接可抄。
`virtual` 为仅物理骨骼，不计入伤害命中区，故不入表。

约定：

- “推荐别名”面向游戏内命中 debug 显示，短、直观、好读
- “伤害倍率”一列留空，按你的平衡方案手动填写

| bone | 推荐别名 | 伤害倍率 |
| --- | --- | --- |
| `vehicle_body` | 车体 | 1.0 |
| `lower_front` | 首下 | 0.8 |
| `upper_front` | 首上 | 0.6 |
| `passenger` | 载员舱 | 1.3 |
| `wheel` | 车轮 | 0.4 |
| `turret` | 炮塔 | 0.8 |
|                  |              |  |
| `turret_barrel` | 机炮 | 0.4 |
| `smoke_barrel` | 烟幕弹发射器 | 0.4 |
| `rocket` | 火箭发射架 | 1 |
| `rocket_barrel` | 火箭发射管 | 1.3 |
| `missile` | 导弹发射架 | 1 |
| `missile_barrel` | 导弹发射管 | 1.3 |
