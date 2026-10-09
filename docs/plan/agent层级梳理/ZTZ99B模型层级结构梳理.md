# ZTZ99B 模型层级结构梳理

本文档用于记录 ZTZ99B 当前在载具包中的渲染模型、结构模型层级关系，便于后续配置载具数据、动画控制器、武器站、履带、ERA、负重轮、二号位机枪/激光武器站等逻辑。文末附 **"需要补充的文件清单"** 与 `ztz99b.json` 关键段草案。

> 渲染模型 / 结构模型 / 动画 / 贴图 四类资源已就位（本 car 无 APS）。
> 骨骼树可用 `scripts/parse_bone_tree.py <模型json路径>` 随时重新生成。

## 文件位置

### 已就位（4 类资源，无需再补）

| 类型 | 路径 |
| --- | --- |
| 渲染模型 | `limitless_vehicle\rvp\assets\rvp\models\bedrock\entity\ztz99b.json` |
| 结构模型 | `limitless_vehicle\rvp\data\rvp\models\bedrock\vehicle\ztz99b.structure.json` |
| 动画文件 | `limitless_vehicle\rvp\assets\rvp\animations\bedrock\entity\ztz99b.animation.json` |
| 车身贴图 | `limitless_vehicle\rvp\assets\rvp\textures\entity\ztz99b.png` |
| 槽位贴图 | `limitless_vehicle\rvp\assets\rvp\textures\slot\ztz99b.png` |

### 待补充　→　**✅ 2026-10-07 全部建完并已同步 4 条链路**

| # | 文件 | 说明 | 状态 |
| --- | --- | --- | --- |
| 1 | `data\rvp\vehicles\ztz99b.json` | **载具主数据**（核心，改动最多） | ✅ 已建 |
| 2 | `assets\rvp\display\vehicle\ztz99b.json` | 载具 display（模型/贴图/动画/履带/音效） | ✅ 已建 |
| 3 | `assets\rvp\animation_controllers\ztz99b_controller.json` | 动画控制器（`part_bindings` 绑定渲染骨） | ✅ 已建 |
| 4 | `assets\rvp\scripts\ztz99b.js` | 动画脚本（ERA 隐藏 + 炮管受损隐藏） | ✅ 已建 |
| 5 | `data\rvp\recipes\ztz99b.json` | 配方 | ✅ 已建 |
| 6 | `assets\rvp\lang\zh_cn.json` / `en_us.json` | 增加 `entity.rvp.ztz99b` 与 `rvp.ztz99b` 条目 | ✅ 已插入（CRLF 字节插入） |

> 同步范围：开发源 + `run/client_1|2|server` + **E 盘整合客户端**（共 5 条链路，每条 10 个 ztz99b 文件 + 2 处 lang 键）；`check_pack_case.py` 全链路 **0 问题**。

> **武器侧不需要新建任何文件**：一号位照搬 VT4、二号位用 `rvp:mbt_127` + `rvp:ztz100_laserweapon`，三者的武器数据与 `display/weapon` 均已在包内（已逐项核实）。

---

## 渲染模型层级

identifier：`geometry.unknown`，共 **116** 根骨骼，根骨骼：

- `ZTZ99B`

一级主分组：

- `ZTZ99B -> weapon0`（**辅助定位组，不参与炮塔动画**）
- `ZTZ99B -> fzl`
- `ZTZ99B -> $body`
- `ZTZ99B -> lvdai`

`weapon0` 内部（⚠️ 注意 `weapon0` / `$weapon0_base` 均为辅助定位组）：

- `weapon0 -> $weapon0`（**实际炮塔偏航骨**）
- `$weapon0 -> $weapon0_base`（**辅助定位组，不用**）
- `$weapon0_base -> $weapon2`（二号位激光武器站）
- `$weapon0_base -> PAOTA_R -> $ERA3 ~ $ERA7`（5 个）
- `$weapon0_base -> PAOTA_L -> $ERA8 ~ $ERA12`（5 个）
- `$weapon0_base -> PAOTA_TOP -> $ERA13`、`$ERA14`

主炮链：

- `$weapon0_base -> $weapon0_0`（炮塔俯仰）
- `$weapon0_0 -> $weapon0_1`
- `$weapon0_1 -> $weapon0_1_hurt`（炮管受损骨，BARREL 模块失效时由 js 隐藏）
- `$weapon0_1_hurt -> turret_muzzle_flash`（5 个 cube，炮口特效挂点）

车顶机枪链：

- `$weapon0_base -> weapon1`（**辅助定位组，不参与动画**）
- `weapon1 -> $weapon1`（**实际机枪偏航骨**）
- `$weapon1 -> $weapon1_0`（机枪俯仰）
- `$weapon1_0 -> $weapon1_1`（枪管，`machinegun_fire` 动画驱动此骨）

负重轮链：

- `ZTZ99B -> fzl`
- `fzl -> $track_roller0 ~ $track_roller7`（8 个，`$` 前缀）

车体 ERA：

- `ZTZ99B -> $body`
- `$body -> $ERA0 ~ $ERA2`（3 个）

履带链：

- `ZTZ99B -> lvdai -> tread_r_0 ~ tread_r_73`（**74 段，单组；模型内没有 `tread_l_*`**）

### 渲染模型关键 pivot

| 骨骼 | pivot | 说明 |
| --- | --- | --- |
| `ZTZ99B` | `[0, 0, 0]` | 根 |
| `weapon0` | `[-0.7132, 27.8126, 0.6438]` | 辅助组，等同炮塔枢轴 |
| `$weapon0` | `[-0.7132, 27.81207, 0.61976]` | **实际偏航骨**（与上一行差 <0.03，可视为同一枢轴） |
| `$weapon0_base` | `[-0.7132, 27.8126, 0.6438]` | 辅助组 |
| `$weapon0_0` | `[0, 33.3311, 22.3707]` | 俯仰轴 |
| `$weapon0_1` | `[0, 33.3311, 22.3707]` | 炮管 |
| `turret_muzzle_flash` | `[0.0003, 33.4691, 112.1015]` | 炮口特效 |
| `weapon1` | `[14.0089, 40.6168, -21.7487]` | 辅助组 |
| `$weapon1` | `[14.0089, 40.6168, -21.7487]` | **实际机枪偏航骨** |
| `$weapon1_0` | `[14.0607, 52.9797, -21.6566]` | 机枪俯仰轴 |
| `$weapon1_1` | `[14.0612, 52.9267, -13.515]` | 枪管 |
| `$weapon2` | `[-14.947, 45.5367, -18.4436]` | 二号位激光武器站 |
| `fzl` / `$body` / `lvdai` | `[0, 0, 0]` | 分组骨 |

> ⚠️ **本渲染模型的几何体绝大多数是 `poly_mesh`（雕刻网格），不是 `cubes`** —— 116 根骨里只有 `turret_muzzle_flash` 带 cube。
> 因此**不能靠"cube 坐标"去反查渲染骨的空间方位**（查出来必为空）；要判定某块几何的实际位置，改用**结构模型**（`*.structure.json`，那里是 cube 碰撞体、带坐标）。

### 动画文件现有动画（`ztz99b.animation.json`，共 4 个）

| 动画名 | loop | 驱动的骨骼 | 用途 |
| --- | --- | --- | --- |
| `tread_r_move` | — | `tread_r_0 ~ 73` | 履带 |
| `cannon_fire` | — | `$weapon0_1`、`turret_muzzle_flash` | 主炮开火（对照 ZTZ100 的 `cannon_fire`） |
| `machinegun_fire` | — | `$weapon1_1` | 机枪开火（⚠️ 拼写是 `machinegun_fire`，**不是** ZTZ100 的 `machingun_fire`，配置时逐字照抄本车拼写） |
| `static` | true | `turret_muzzle_flash` | 常驻 |

> ⚠️ 本车动画文件**没有** `tread_l_move`，与 `lvdai` 下只有 `tread_r_*` 相互印证：**履带只有单组**。`display.track_config` 的左右两条都指向 `tread_r_move`（详见配置清单 §2）。

---

## 结构模型层级

共 **39** 根骨骼（2026-10-07 新增零 cube 幽灵骨 `turret_muzzle_flash`，parent=`turret`，用于飞头时排除炮口焰），根骨骼：

- `main_structure`（1 个 cube，主物理 cube，不进命中箱）
- `bone`

一级主分组：

- `bone -> vehicle_body`
- `bone -> turret_base`（**辅助定位组，不用**）
- `turret -> turret_muzzle_flash`（**零 cube 幽灵骨**，2026-10-07 新增：只为"飞头时排除炮口焰"服务，不可命中/不渲染）

车体命中区：

- `vehicle_body -> ERA_UP -> ERA0 ~ ERA2`（各 2 个 cube）
- `vehicle_body -> track`（2 个 cube）
- `vehicle_body -> Engine`
- `vehicle_body -> Upper_front`（3 个 cube）
- `vehicle_body -> Lower_front`
- `vehicle_body -> Periscope`

炮塔命中区（**实际以 `turret` 为主，`turret_base` 是辅助组**）：

- `turret_base -> turret`（1 个 cube，pivot `[-0.7132, 27.8126, 0.6438]`）
- `turret -> ERA_PAOTA2 -> ERA3 ~ ERA12`（10 个）
- `turret -> top`
- `turret -> ERA13`、`ERA14`
- `turret -> turret_bellow`（尾舱，pivot z = −37）
- `turret -> turret_side`（2 个 cube）
- `turret -> turret_barrel`（pivot `[0, 33.3311, 22.3707]`）
- `turret_barrel -> turret_machine_gun_barrel`（**同轴机枪**，pivot `[6.25, 30.8311, 22.3707]`）
- `turret -> turret_smoke_grenade_r_barrel`（pivot `[24.95, 35.6375, 3.7625]`）
- `turret -> turret_smoke_grenade_l_barrel`（pivot `[-19.8, 42.6375, 3.7625]`）
- `turret -> weapon2 -> weapon2_barrel`（**二号位激光**，pivot `[-14.947, 45.5367, -18.4436]`）
- `turret -> weapon1 -> weapon1_barrel`（**二号位机枪**，pivot `[14.0089, 40.6168, -21.7487]`）

---

## ZTZ99B 结构特点

1. **两处"辅助定位组"必须绕开**（本车最容易踩的坑）：

   | 部位 | 辅助组（不要用） | 实际使用 |
   | --- | --- | --- |
   | 渲染·主炮站 | `weapon0`、`$weapon0_base` | **`$weapon0`**（偏航）/ `$weapon0_0`（俯仰） |
   | 渲染·机枪站 | `weapon1` | **`$weapon1`**（偏航）/ `$weapon1_0`（俯仰） |
   | 结构·炮塔 | `turret_base` | **`turret`** |

   ⇒ `animation_controller.part_bindings` 里 `bone` 必须填 `$weapon0` / `$weapon0_0` / `$weapon1` / `$weapon1_0` / `$weapon2`，**不能填 `weapon0` / `weapon1` / `$weapon0_base`**；`ztz99b.json` 的 `parts[].structure_bone` 主炮站填 `turret`（不是 `turret_base`）。

2. **渲染骨带 `$`、结构骨不带**：渲染是 `$weapon0` / `$weapon1` / `$weapon2` / `$ERA0~14` / `$track_roller0~7`；结构是 `turret` / `weapon1` / `weapon2` / `ERA0~14`。两套名字一一对应，配 `hitbox_*` 与 js 时注意区分。

3. **履带只有单组**：`lvdai -> tread_r_0 ~ tread_r_73`（74 段），**模型中没有 `tread_l_*`**，动画也没有 `tread_l_move`。

4. **负重轮 8 个**，命名 `$track_roller0 ~ $track_roller7`（**与 VT4 的 `FZL0~7` 命名不同**，控制器 `special_bindings.bones` 要照本车写）。

5. **ERA 三组共 15 块**：
   - 车体：渲染 `$body -> $ERA0~2` / 结构 `vehicle_body -> ERA_UP -> ERA0~2`
   - 炮塔左右：渲染 `PAOTA_R -> $ERA3~7`、`PAOTA_L -> $ERA8~12` / 结构 `turret -> ERA_PAOTA2 -> ERA3~12`（**结构里 10 块同挂一根父骨**，渲染分成 R/L 两组）
   - 炮塔顶部：渲染 `PAOTA_TOP -> $ERA13/$ERA14` / 结构 `turret -> ERA13/ERA14`

6. **二号位有两个独立武器站**：`$weapon1`（机枪，主站）+ `$weapon2`（激光）。两者都挂在炮塔内，但**不在同一条父链上**（`$weapon1` 的父是 `$weapon0_base`，`$weapon2` 也是 `$weapon0_base`），可各自独立偏航。

7. **无 APS**：结构模型与渲染模型里都**没有** `aps_*` 骨，`ztz99b.json` 不要写 APS 相关 parts / `bone_modules`（对照 ZTZ100 时务必删掉这部分）。

8. **同轴机枪无独立渲染骨**：结构模型有 `turret_machine_gun_barrel`，但渲染模型里同轴机枪没有单独骨骼（它随 `$weapon0` 动）。因此 `parts[turret_machine_gun]` 只需 `structure_bone`，**不需要**在 `part_bindings` 里绑定。

---

## 与 VT4 / ZTZ100 的差异对照

| 项 | VT4 | ZTZ100 | ZTZ99B |
| --- | --- | --- | --- |
| 渲染根骨 | `VT4A1` | （见 ZTZ100 文档） | `ZTZ99B` |
| 渲染骨总数 | 177 | — | **116** |
| 结构骨总数 | 36 | — | **39**（原 38 + 2026-10-07 加的幽灵骨 `turret_muzzle_flash`） |
| 主炮偏航骨 | `$weapon0` | `$weapon0` | `$weapon0`（辅助组 `weapon0` / `$weapon0_base`） |
| 二号位机枪 | `$weapon1`（加特林 `$weapon1_0_0`） | `$weapon1` | **`$weapon1`**（普通枪管 `$weapon1_1`） |
| 二号位激光 | 无 | `$weapon4` → part `commander_laser`（结构骨 `laser_weapon`） | **`$weapon2`**（结构骨 `weapon2`） |
| 负重轮命名 | `FZL0~7` | `$track_roller0~7` | **`$track_roller0~7`**（同 ZTZ100） |
| 履带 | 双侧独立 `lvdai_r` / `lvdai_l` 各 72 段 | — | **单组 `lvdai -> tread_r_0~73`（74 段）** |
| ERA | 车体 `$ERA0~2` + 炮塔 `$ERA3~6` | `$ERA0~5` | **车体 `$ERA0~2` + 炮塔 `$ERA3~12` + 顶部 `$ERA13~14`（共 15）** |
| APS | 有（含四向雷达组） | 有（含四向雷达组） | **无** |
| 烟幕弹 | 左右双骨 | 左右双骨 | 左右双骨 |
| 动画脚本抓手 | 加特林转管 `$weapon1_0_0` | 无转管 | **无转管**（照 ZTZ100 的 js 精简版即可） |
| 开火动画名 | `cannon_shoot` | `cannon_fire` / `machingun_fire` | **`cannon_fire` / `machinegun_fire`** |
| tab_index（display） | 305 | 309 | **建议 311**（305~310 已占用） |

---

## 观瞄偏移计算

RVP 的 `optical_sight_offset` / `operator_view_offset` 口径为 **`(观瞄点 − 该武器站枢轴) ÷ 16`**（单位：格）。

> ⚠️ **坐标系换算（关键）**：Blockbench 里的 `origin` 与导出 json 的 `pivot` **x 轴反号**（y / z 相同）。实测三例：
>
> | 骨骼 | bbmodel `origin` | 导出 json `pivot` |
> | --- | --- | --- |
> | `weapon0` / `$weapon0` | `+0.7132` | `−0.7132` |
> | `$weapon1` | `−14.0089` | `+14.0089` |
> | `$weapon2` | `+14.947` | `−14.947` |
>
> ⇒ 在 BB 界面量到的观瞄点，写进配置前 **x 要取反**（RVP 读的是导出坐标）。

**该"必须取反"已用两条硬证据钉死（2026-10-07）：**

#### 证据 A：`.bbmodel` ↔ 导出 json 逐骨比对（本车实测）

| 骨骼 | `ztz99b.bbmodel` 的 `groups[].origin`（=BB 界面显示值） | 导出 `ztz99b.json` 的 `pivot` |
| --- | --- | --- |
| `weapon0` | `+0.7132` | `−0.7132` |
| `$weapon0` | `+0.7132` | `−0.7132` |
| `$weapon1` | `−14.0089` | `+14.0089` |
| `$weapon1_0` | `−14.0607` | `+14.0607` |
| `$weapon2` | `+14.947` | `−14.947` |
| `turret_muzzle_flash` | `−0.0003` | `+0.0003` |

⇒ **只有 x 反号，y / z 完全一致**（`$weapon1` 的 y=40.6168、z=−21.7487 两侧逐位相同）。
⇒ 且与用户实机所见吻合："我这里显示的是 `$weapon1` 枢轴为 **−14**"（BB 显示 `−14.0089`）。

#### 证据 B：`optical_sight_offset` 活在**导出空间**（ZTZ100 反推）

`ztz100.json` 二号位 `commander_machine_gun`：`optical_sight_offset = [−0.0033875, 0.3957375, 0.38153125]`（= 其 `operator_view_offset`）。
导出模型 `$weapon1` pivot = `[0.0542, 43.1682, −21.1045]`：

| 假设 | 观瞄点 = 枢轴 + 16×offset |
| --- | --- |
| **导出空间（枢轴取导出值）** | **`(0.0000, 49.5, −15.0)`** ← 干净整数，x 正好居中 0 ✅ |
| BB 空间（枢轴 x 不反号） | `(−0.1084, 49.5, −15.0)` ← 不干净 ❌ |

⇒ 配置里的偏移**是**在导出空间里算的 ⇒ BB 读数必须先反号。

#### 证据 C：本车根骨上的"标记点"

导出 `ztz99b.json` 里根骨 `ZTZ99B` 的 pivot = **`[17.5, 53, −17]`**，正是用户在 BB 界面读到的二号位观瞄点 **`(−17.5, 53, −17)`** 反号后的样子 ⇒ 与证据 A/B 完全自洽。

---

### 一号位（主炮站，`$weapon0` 枢轴）✅ 已定

- 观瞄坐标（**BB 界面量测**，用户 2026-10-07 确认）：`(11, 46, 16)` → 导出坐标 **`(−11, 46, 16)`**
- 枢轴（导出）：`$weapon0 = (−0.7132, 27.8131, 0.6197)`
- 差值：`(−10.2868, 18.1869, 15.3803)`
- **`optical_sight_offset` = `(−0.6429, 1.1367, 0.9613)`** ← 采用此值

> 被排除的对照：`(0.7321, 1.1367, 0.9613)` —— 那个是把 `11` 当成导出坐标（**未反号**）算出来的；而 `11` 是 BB 界面读数，故排除。

### 二号位（`$weapon1` 为主武器站）

- 观瞄坐标（BB 界面量测）：`(−17.5, 53, −17)` → 导出坐标 **`(17.5, 53, −17)`**
- 枢轴（导出）：`$weapon1 = (14.0089, 40.6168, −21.7487)`
- 差值：`(3.4911, 12.3832, 4.7487)`
- **`optical_sight_offset` = `(0.2182, 0.7740, 0.2968)`**

（量级与 VT4 `[0.375, 0.5, 0.1875]`、ZTZ100 `[−0.0034, 0.3957, 0.3815]` 一致 ✓）

## 推荐碰撞箱别名与倍率占位

本节用于给 `hitbox_display_name` / `hitbox_display_name_CN` 与 `hitbox_damage_factor` 提供可直接抄写的参考表。倍率列留空，按平衡方案填。

⚠️ 服务端恒用英文名（`hitbox_display_name`），中文另建 `hitbox_display_name_CN`，**两表键集合与顺序必须一致**。

### 车体命中区

| bone | 英文名（建议） | 中文名（建议） | 伤害倍率 |
| --- | --- | --- | --- |
| `vehicle_body` | Hull Side | 车体侧面 | 1.0 |
| `track` | Track | 履带 | 0.5 |
| `Engine` | Engine Deck | 发动机舱 | 1.3 |
| `Upper_front` | Upper Front Plate | 首上 | 0.6 |
| `Lower_front` | Lower Front Plate | 首下 | 0.8 |
| `Periscope` | Periscope | 潜望镜 | 0.8 |

### 炮塔命中区

| bone | 英文名（建议） | 中文名（建议） | 伤害倍率 |
| --- | --- | --- | --- |
| `turret` | Turret | 炮塔 | 0.6 |
| `top` | Turret Roof | 炮塔顶部 | 1.0 |
| `turret_bellow` | Turret Bustle | 炮塔尾舱 | 1.3 |
| `turret_side` | Turret Side | 炮塔侧面 | 1.0 |
| `turret_barrel` | Main Gun | 主炮 | 0.4 |
| `turret_machine_gun_barrel` | Coaxial MG | 同轴机枪 | 0.4 |
| `turret_smoke_grenade_l_barrel` | Smoke Launcher (L) | 烟幕弹发射器（左） | 0.4 |
| `turret_smoke_grenade_r_barrel` | Smoke Launcher (R) | 烟幕弹发射器（右） | 0.4 |
| `weapon1` | Commander MG | 车长机枪 | 0.4 |
| `weapon1_barrel` | Commander MG | 车长机枪 | 0.4 |
| `weapon2` | Laser Weapon Station | 激光武器站 | 0.4 |
| `weapon2_barrel` | Laser Weapon Station | 激光武器站 | 0.4 |

### ERA 命中区

| bone | 英文名（建议） | 中文名（建议） | 伤害倍率 |
| --- | --- | --- | --- |
| `ERA0` / `ERA1` / `ERA2` | Hull ERA | 车体爆反 | 0.3 |
| `ERA3`/4/5/8/9/10 | Turret ERA | 炮塔爆反 | 0.3 |
| `ERA13` / `ERA14` | Turret Roof ERA | 炮塔顶部爆反 | 0.8 |
| ERA6/11 | Turret Side ERA | 炮塔侧爆反 | 0.8 |
| ERA7/12 | Turret Side ERA | 炮塔侧爆反 | 0.7 |

> `ERA_UP` / `ERA_PAOTA2` 是**分组骨**（无 cube），按现有载具惯例不进命中箱表。

**分组已用结构模型坐标交叉验证**（渲染模型是 `poly_mesh`、读不到坐标 ⇒ 改用结构模型的 cube 中心；此项仅为校验，**不属于配置内容**）：

| 分组 | X 中心 | Y 中心 | Z 中心 | 实际方位 |
| --- | --- | --- | --- | --- |
| `ERA3`↔`ERA8` | ∓13.5 | 31.6 | +25.7 | 炮塔前部 |
| `ERA4`↔`ERA9` | ∓13.5 | 39.1 | +25.7 | 炮塔前部（更高一档） |
| `ERA5`↔`ERA10` | ∓22.8 | 35.1 | +11.8 | 炮塔前侧 |
| **`ERA6`↔`ERA11`** | ∓28.3 | 36.6 | −7.3 | **炮塔侧面（中部）** |
| **`ERA7`↔`ERA12`** | ∓28.3 | 36.6 | −31.1 | **炮塔侧后（尾舱侧）** |
| **`ERA13`/`ERA14`** | 0 | 43.2 | +5.2 / −24.3 | **炮塔顶部（前 / 后）** |
| `ERA0`/`1`/`2` | −19.5 / 0 / +19.5 | 21.2 | +45.5 | 车体首上 |

⇒ 左右**完美镜像**（配对 Y/Z 差 0.00），分组与模型实际方位吻合 ✅

⚠️ 生成 `ztz99b.json` 时两点注意：

1. 上表 `ERA3/4/5/8/9/10` 是**合并写法**，生成时要**拆成 10 个独立键**（`hitbox_display_name` 与 `hitbox_display_name_CN` 各 10 条，**键集合与顺序必须一致**）。
2. `ERA6/11` 与 `ERA7/12` 用了**同一个显示名**"炮塔侧爆反"，但倍率不同（0.8 / 0.7）—— 功能没问题，只是游戏内命中 debug 时这两组看起来一样；想区分可写成"炮塔侧爆反（中）/（后）"。

---

## 需要补充的配置清单

### 1. `data/rvp/vehicles/ztz99b.json`（**新建，核心**）

蓝本：`vt4.json`（一号位武器）+ `ztz100.json`（二号位双武器站），**删掉全部 APS 相关内容**。

| 项 | 取法 |
| --- | --- |
| `type` | `ywzj_vehicle:tracked_vehicle` |
| `structure_model` | `rvp:vehicle/ztz99b` |
| `hide_passenger` | `true`（照 VT4 / ZTZ100） |
| `defense_stats` / `armor_min_damage` / `attributes` / `physics_info` / `energy_info` / `view_info` / `countermeasure` | 照 VT4 抄，数值按 ZTZ99B 车重微调 |
| `parts[0] turret`（weapon） | `structure_bone: "turret"`；`optical_sight_offset: [-0.6429, 1.1367, 0.9613]`；`optical_sight_type: "crt"`（照 VT4）；`weapons` = AP 组 + HE 组 + 副武器（见下） |
| `parts[1] turret_machine_gun` | `is_seat: false`；`structure_bone: "turret_machine_gun_barrel"` |
| `parts[2]/[3] turret_smoke_grenade_l/r` | `is_seat: false`；`structure_bone: "turret_smoke_grenade_l_barrel"` / `_r_barrel` |
| `parts[4] commander_machine_gun`（weapon） | `structure_bone: "weapon1"`；`weapons: ["rvp:mbt_127", {"part_unit_id": "commander_laser"}]`；观瞄偏移见下 |
| `parts[5] commander_laser`（weapon） | `is_seat: false`；`structure_bone: "weapon2"`；`weapons: ["rvp:ztz100_laserweapon"]` |
| generic parts | `vehicle_body` / `track` / `Engine` / `Upper_front` / `Lower_front` / `Periscope` / `top` / `turret_bellow` / `turret_side` / `ERA0~ERA14`，逐个 `is_seat: false` + `structure_bone` |
| `hitbox_display_name` / `_CN` / `hitbox_damage_factor` | 按上节表新建 |
| **不要写** | `aps_l` / `aps_r` / `aps_radar_*` / APS 的 `bone_modules` |

**一号位武器（照搬 VT4）**：

```jsonc
"weapons": [
  { "ids": ["rvp:vt4_dtc10", "rvp:vt4_gp125", "rvp:vt4_bts8"], "save_id": "ztz99b_main_gun_ap", "modding_only_multi": true },
  { "ids": ["rvp:vt4_dtb10", "rvp:vt4_dtp10", "rvp:vt4_bea10"], "save_id": "ztz99b_main_gun_he", "merge_into_previous_slot": true },
  { "id": "rvp:mbt_762", "part_unit_id": "turret_machine_gun", "secondary": true }
]
```

> 若希望弹药名带上本车编号，可另建 `ztz99b_*` 武器数据；但**先跑通用 VT4 的即可**（用户口径："武器先照搬 VT4 的武器"）。

**二号位观瞄**：

```jsonc
// parts[4] commander_machine_gun
"optical_sight_offset": [0.2182, 0.7740, 0.2968],    // BB 读数 −17.5 → 导出 +17.5 后的结果（已确认）
"operator_view_offset": [0, 0.7, -0.5],              // 照 VT4 的 commander 值起步
"optical_sight_type": "crt",
"operator_on_weapon_unit": false
```

### 2. `assets/rvp/display/vehicle/ztz99b.json`（**新建**）

照 `vt4.json` 的 display 抄，逐项替换：

| 字段 | 值 |
| --- | --- |
| `type` | `ywzj_rvp:tracked_vehicle` |
| `model` | `rvp:entity/ztz99b` |
| `texture` | `rvp:textures/entity/ztz99b.png` |
| `slot_texture` | `rvp:textures/slot/ztz99b.png` |
| `animations` | `rvp:entity/ztz99b.animation` |
| `animation_controller` | `rvp:ztz99b_controller` |
| `track_config.left_track` / `right_track` | **都填 `tread_r_move`** —— 已核实 `ztz100` 与 `t90m` 都是左右同填 `tread_r_move`（它们同样只有这一个履带动画），本车情形完全一致 |
| `track_config.module_length` / `track_width` | 0.25 / 3.0 起步，按新模型履带节距与车宽微调 |
| `sounds.engine_*` | 建议沿用 `ywzj_vehicle:ztz99a_engine_*`（与 VT4 一致，同为 99 系列动力包；如你有 ZTZ99B 专用音效再换） |
| `special_bone_effects` | `turret_muzzle_flash` + `ywzj_vehicle:textures/bedrock/effect/muzzle_flash_1.png` |
| `tab_index` | **312** ⚠️ 修正（原计划 311 已被 `j10cp.json` 占用；实测已占 300/302–311/320/321/332–336/340/601） |

### 3. `assets/rvp/animation_controllers/ztz99b_controller.json`（**新建**）

以 `ztz100_controller.json` 为蓝本，改三处：

| 项 | 改为 |
| --- | --- |
| `name` | `ztz99b_controller` |
| `script` | `rvp:ztz99b` |
| `special_bindings[0].bones` | `["$track_roller0", …, "$track_roller7"]`（照 ZTZ100，本车同名） |
| `event_animations` | `turret_fire: ["cannon_fire"]`、`commander_machine_gun_fire: ["machinegun_fire"]`（⚠️ 本车拼写 `machinegun_fire`） |
| `part_bindings` | `$weapon0`(y) / `$weapon0_0`(x) → part `turret`；`$weapon1`(y) / `$weapon1_0`(x) → part `commander_machine_gun`；**`$weapon2`(y) → part `commander_laser`**；**删除** ZTZ100 里的 4 条 `aps_*` 绑定 |

### 4. `assets/rvp/scripts/ztz99b.js`　✅ **已创建（2026-10-07）**

- **路径**：`limitless_vehicle\rvp\assets\rvp\scripts\ztz99b.js`（LF 行尾，1912 字节，与包内其它 js 一致）
- **蓝本**：`ztz100.js`（改 ERA 数量 6 → 15）；**不带** `vt4.js` 的加特林转管段（本车无转管）
- **风格决策**：ERA 按包内既有惯例**逐行展开 15 条**（`bmpt72.js` / `m1a2sep.js` / `t84bm.js` / `vt4.js` / `ztz100.js` 全部是逐行写法，**包内无循环先例**），不采用 for 循环。
- **结构**：`try { rvp_isEraActive("ERA0"~"ERA14") → hideBone("$ERA0"~"$ERA14") } catch {}`，随后独立的 `rvp_isModuleActive("turret_barrel","barrel") → hideBone("$weapon0_1_hurt")`。

**落地后已交叉校验（脚本引用 ↔ 模型实测）**：

| 检查项 | 结果 |
| --- | --- |
| `hideBone` 目标（渲染骨）共 16 个（`$ERA0~14` + `$weapon0_1_hurt`） | **全部命中**渲染模型 ✅ |
| `rvp_isEraActive` 键（结构骨）共 15 个（`ERA0~14`） | **全部命中**结构模型 ✅ |
| `rvp_isModuleActive("turret_barrel", "barrel")` | `turret_barrel` **命中**结构模型 ✅ |

> ⚠️ 键与参不可混：`rvp_isEraActive` / `rvp_isModuleActive` 的第一参是**结构骨名（无 `$`）**，`hideBone` 的参数是**渲染骨名（带 `$`）**。
> ⚠️ 脚本生效前提：控制器 `animation_controllers/ztz99b_controller.json` 里必须挂 `"script": "rvp:ztz99b"`（清单 #3，待建）——**只建 js 不挂控制器 = 不执行**。
> ⚠️ **ERA 显隐还依赖 parts 侧配置**：命中箱表里 `ERA0~ERA14` 每根骨都要有条目（本车已列全 15 条），否则 `rvp_isEraActive` 查不到状态、骨块永不隐藏。

### 5. `data/rvp/recipes/ztz99b.json`（**新建**）

照 `recipes/vt4.json` 或 `recipes/ztz100.json` 抄，改产出的 `YwzjVehicleId` 为 `rvp:ztz99b`。

### 6. lang 条目（**改 2 个现有文件**）

```jsonc
// assets/rvp/lang/zh_cn.json
"entity.rvp.ztz99b": "ZTZ99B 主战坦克",
"rvp.ztz99b": "ZTZ99B 主战坦克",

// assets/rvp/lang/en_us.json
"entity.rvp.ztz99b": "ZTZ99B Main Battle Tank",
"rvp.ztz99b": "ZTZ99B",
```

> ⚠️ `assets/rvp/lang/*.json` 是 **CRLF** 文件，**必须用 python 按字节插入**，不能用会转 LF 的编辑器改写。

### 不需要新建 / 不需要动的

- `data/rvp/weapons/`、`assets/rvp/display/weapon/`：一号位用 `vt4_*`、二号位用 `mbt_127` + `ztz100_laserweapon`，**全部已存在**
- `assets/rvp/models/bedrock/entity/ztz99b.json`（渲染）、`data/rvp/models/bedrock/vehicle/ztz99b.structure.json`（结构）、`ztz99b.animation.json`、两张贴图：**已就位**
- `data/rvp/ui_presets/`：**不需要**（VT4 / ZTZ100 都没有；本车无雷达）
- `assets/rvp/models/bedrock/entity/ztz99b - 副本.bbmodel`：**可删**（残留副本）

---

## 已确认 / 待确认

**已确认（2026-10-07）**

| # | 事项 | 结论 |
| --- | --- | --- |
| 1 | 二号位观瞄枢轴 | 用 **`$weapon1`**；BB 与导出 x 反号，观瞄点取 `+17.5` ⇒ **`(0.2182, 0.7740, 0.2968)`** |
| 2 | 履带配法 | 照 **ztz100 / t90m**：`left_track` 与 `right_track` 都填 `tread_r_move` |
| 3 | 一号位武器 | **共用 `rvp:vt4_*`**，不新建 `ztz99b_*` 弹药副本 |
| 4 | 发动机音效 | 沿用 `ywzj_vehicle:ztz99a_engine_*` |
| 5 | `tab_index` | **312**（原定 311，实测已被 `j10cp.json` 占用 ⇒ 顺延） |
| 6 | 碰撞箱名称与倍率 | 已由用户给出；ERA 三档分组（`3/4/5/8/9/10` = 0.3、`6/11` = 0.8、`7/12` = 0.7、`13/14` = 0.8）经结构模型坐标验证与方位吻合 |
| **7** | **一号位观瞄 x 符号** | ✅ **已定 = `−0.6429`**（BB 读数 `11` → 导出 `−11`）。依据：证据 A（`.bbmodel` 逐骨比对，仅 x 反号）+ 证据 B（ZTZ100 反推证明配置偏移活在**导出空间**）+ 证据 C（本车根骨标记 `ZTZ99B` = `[17.5,53,−17]`）。被排除值 `+0.7321` |
| 8 | 二号位观瞄偏移 | 已确认 `(0.2182, 0.7740, 0.2968)`（用户口径：`$weapon1` 为主武器站） |

**待确认：无**（原"一号位观瞄 x 符号"一项已由用户 2026-10-07「我的 11 是 BB 界面读的」+ 上述三条证据闭合）

---

## 通用经验（本车踩到、后续新车都要用）

⚠️⚠️ **观瞄点（及一切 BB 界面读到的坐标）写进 RVP 配置前，x 必须取反。**

- 判据（可复用）：在 BB 量到 `(x_bb, y, z)` ⇒ 配置里用 **`x = −x_bb`、`y`/`z` 不变**。
- 反推自检：把配置的 `optical_sight_offset × 16` 加到**导出模型**的骨骼 `pivot` 上，应落在**干净/整数化**的观瞄点上；加错空间会得到 `x` 带零头的脏数（见 §观瞄偏移计算 证据 B）。
- 两侧坐标必须同空间：要么都用导出值，要么都反号后再相减 —— **不能一半一半**（这正是最初 `+0.7321` 那个错值的来源）。

---

## 落地记录（2026-10-07 全部建完）

### 新建/修改的 7 处（开发源，已同步 run×3 + E 盘）

| 文件 | 关键内容 |
| --- | --- |
| `data/rvp/vehicles/ztz99b.json` | 30 个 parts（6 武器站 + 24 generic）、`bone_modules` 19 键、命中箱三表各 **33 键**；**无 APS / 无 ECM**（本车结构模型里没有这两种骨）；`physics_info` **不写** `physics_only_bone:"virtual"`（要真碰撞箱） |
| `assets/rvp/display/vehicle/ztz99b.json` | `left/right_track` 同填 `tread_r_move`；音效 `ztz99a_engine_*`；`tab_index 312`（**CRLF 文件**） |
| `assets/rvp/animation_controllers/ztz99b_controller.json` | `script:"rvp:ztz99b"`；`event_animations` 用本车拼写 `cannon_fire`/`machinegun_fire`；`part_bindings` 5 条（`$weapon0`/`$weapon0_0`/`$weapon1`/`$weapon1_0`/`$weapon2`）；`special_bindings` = `$track_roller0~7` |
| `assets/rvp/scripts/ztz99b.js` | ERA0~14 逐行 15 条 + `$weapon0_1_hurt` |
| `data/rvp/recipes/ztz99b.json` | `vehicle_printing` → `YwzjVehicleId = rvp:ztz99b` |
| `assets/rvp/lang/zh_cn.json` / `en_us.json` | `entity.rvp.ztz99b` + `rvp.ztz99b`（**CRLF 字节插入**，未整体覆盖） |

### 关键参数取值（一号位照 VT4，二号位照 ZTZ100，删 APS/ECM）

| 项 | 取值 | 来源 |
| --- | --- | --- |
| `type` / `structure_model` | `ywzj_vehicle:tracked_vehicle` / `rvp:vehicle/ztz99b` | — |
| `mass` / `friction` / `max_health` / `armor_min_damage` | 54000 / 108000 / 1200 / 15 | VT4（质量按更重车体上调）|
| 一号位 `weapons` | AP组 `[vt4_dtc10, vt4_gp125, vt4_bts8]` + HE组 `[vt4_dtb10, vt4_dtp10, vt4_bea10]`（并槽）+ 副武器 `rvp:mbt_762` | VT4 |
| 二号位 `weapons` | `["rvp:mbt_127", {"part_unit_id":"commander_laser"}]`；激光站 `["rvp:ztz100_laserweapon"]` | ZTZ100 |
| 一号位观瞄 | `[-0.6429, 1.1367, 0.9613]` | 本文档定稿 |
| 二号位观瞄 | `[0.2182, 0.7740, 0.2968]`；`operator_view_offset` 照 VT4 commander `[0, 0.7, -0.5]` | 本文档定稿 |
| 座位 | parts[0]`turret`=一号位、parts[4]`commander_machine_gun`=二号位 | `parts` 顺序 |

### 交叉校验结果（脚本全跑，**全部通过**）

- `parts[].structure_bone` 30/30 命中结构模型；`bone_modules` 19 键全命中
- 三张命中表 **33 键、键集合与顺序完全一致**；结构模型 39 骨中除 `main_structure`/`bone`/`turret_base`/`ERA_UP`/`ERA_PAOTA2`/`turret_muzzle_flash`（后两者是零 cube 分组/幽灵骨）外全部进表
- 控制器 `part_bindings.bone`(5) + `special_bindings.bones`(8) 全在渲染模型；`part` 引用全部有效
- 控制器用到的动画 `cannon_fire`/`machinegun_fire`/`static` 逐字命中动画文件；`display` 指向的 model/texture/slot_texture/动画文件**全部存在**
- 武器 9 个 id 的**数据 + display 全部存在**；`rvp_follow_parent_only_part_unit_ids` 15 项全部为有效 part
- `check_pack_case.py`：**5 条链路 0 问题**

### ⚠️ 进游戏后需要实测确认的 3 项（蓝本照抄、本车未单独量）

1. **二号位 `seat_offset`** 直接照抄 VT4 commander 的 `[-0.01715625, 1.70740625, 3.75e-05]`（VT4/ZTZ100 两个站都用同一值）⇒ 坐上去看座位落点是否合理，需要的话按 `bb/16` 重量。
2. **`physics_info.mass/friction`（54000/108000）** 与 `energy_info.engine_particle_offsets`（照抄 VT4 `[1.288,1.47,-5.39]`）按手感/排烟位置微调。
3. **`track_config.module_length/track_width`（0.25/3.0）** 按履带节距与车宽微调。
