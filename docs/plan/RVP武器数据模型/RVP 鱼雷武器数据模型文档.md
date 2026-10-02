注：表格中的字段名称为java类中使用的驼峰命名法，在json中需使用全小写+下划线连接的格式

注：本武器速度单位均为格/tick（20 tick = 1 秒，1 格 = 1 米），距离单位为格；如 `waterSpeed: 1.5` 即水中巡航 30 格/秒。

> 配套设计文档：`docs/plan/RVP鱼雷武器实现方案_20261002.md`（调研结论、实现方案、注册清单、验证计划；**截至 2026-10-02 尚未实施**，本文档为设计定稿）。

# RVP_TorpedoData

鱼雷数据模型，JSON键 `torpedo_data`，仅适用于 `type: "rvp:torpedo"` 武器。

鱼雷是水中航行的直航武器：出管段按普通弹道飞行（复用 `projectile_data` 字段），入水后切换为**定速直航**（独立于 `constant_speed` 峰值回填体系），垂直速度逐 tick 衰减自然摆平到水平（深度保持），直至命中/近炸/寿终自爆。首版为非制导直航（无转向），制导能力为后续扩展。

| RVP_TorpedoData字段 | 解释 | 类型 | 默认值 |
| ------------------- | ---- | ---- | ------ |
| waterSpeed | 水中巡航速度（格/tick）。有效入水过渡完成后维持的恒定速率，即鱼雷的真实航速指标（1.5 ≈ 30 米/秒，接近真实重型鱼雷）。水中速率与此值完全绑定，不受空气段 `max_speed`/`constant_speed` 影响 | float | 1.5 |
| waterEntryLerpTick | 入水过渡时长（tick）。从入水瞬间的实际速率线性过渡到 `waterSpeed`（模拟入水减速与螺旋桨起转），过渡期间方向保持不变。0 = 入水立即定速 | int | 10 |
| depthDamping | 水中垂直速度每 tick 保留比例：每 tick 对 Y 分量乘该值后重新归一化到巡航速率，鱼雷俯仰自然摆平到水平直航（被动深度保持）。`1.0` = 不衰减（俯仰保持入水角，配合 `projectile_data.gravity_in_water` 可模拟下沉/上浮）；取值范围 (0, 1] | float | 0.8 |
| waterEntryMinDepth | 有效入水深度（格）。实体中心上方该距离处仍为水方块才判定"完全入水"——防止贴水皮反复切换状态（浪区/水面掠飞抖动）。入水/出水的状态切换以该判定为准 | float | 0.5 |
| requireAimWater | 发射门：`true` 时发射瞬间服务端沿武器站瞄准方向做射线检测，命中流体方块才允许发射，否则拒绝且**不耗弹**并向射手提示（"未瞄准水面"）。防止瞄着陆地/舰桥误发。默认关闭（飞机空投、甲板直射不受限） | boolean | false |
| requireAimWaterRange | `requireAimWater` 的瞄准线检测最大距离（格），该距离内命中流体方块即放行 | float | 150 |

## 运动状态机

```
出管(空中) ──入水边沿──▶ 入水过渡 ──lerp完成──▶ 水中巡航 ──出水边沿──▶ 出管(空中)
    │                        │                     │
    └──────── 命中 / 近炸 / delay延时 / 寿终自爆 ◀────┘
```

- **出管段（空中）**：完全复用 `projectile_data` 弹道（初速、重力、阻力、继承载具速度、姿态跟速），与普通火箭弹无异。
- **入水判定**：`isInWater()` 上一 tick 对比 + `waterEntryMinDepth` 消抖。由干转湿即触发过渡。
- **入水过渡**：速率向 `waterSpeed` 线性收敛 `waterEntryLerpTick` tick。
- **水中巡航**：方向保持（直航）、速率恒为 `waterSpeed`、Y 分量每 tick 乘 `depthDamping` 后归一化（摆平）；姿态跟随速度方向（雷头朝前）。
- **出水**：回到空中弹道（受 `gravity` 下坠），再次入水再次过渡——状态天然可循环。

## 与 projectile_data 的分工

鱼雷复用 `projectile_data` 的**出管段**字段，水中动力学由 `torpedo_data` 全权接管：

| 复用字段 | 鱼雷语义 |
| -------- | -------- |
| velocity | 出管初速（格/tick），空中段有效 |
| gravity / drag | 空中段重力/线性阻力（水中不使用） |
| inherit_vehicle_velocity | 继承发射船速度（动对动初速合成） |
| rotate_to_motion | 姿态跟随速度方向（空中与水中均生效） |
| max_speed / min_speed | 空中段速率钳制 |
| self_destruct_distance | 距离上限补充（水中段与空中段均计） |

**不要配**的字段（对 `rvp:torpedo` 无意义或被覆盖）：`constant_speed`（水中速度基准由 waterSpeed 接管，配了会在空中段引入无关的峰值回填）、`has_rocket_engine`（鱼雷为螺旋桨巡航模型，无推力/燃烧/二次阻力/尾焰）、`turning_factor`/`rvp_maxg`（首版无制导无转向）、`gravity_in_water`/`drag_in_water`（水中动力学已由 torpedo_data 表达，仅 depthDamping=1.0 时 gravity_in_water 才参与）。

## 航程与寿命

- 顶层 `life`（总寿命 tick）为鱼雷主航程限制：**水中航程 ≈ life × waterSpeed**（例：`life: 3400` @ `water_speed: 1.5` ≈ 5100 格）。
- `fuse_data.detonate_on_life_end: true` 时寿终自爆（推荐开启，避免哑雷漂在水中）。

# 引信与爆炸

## 引信（fuse_data 全复用，零新字段）

| 引信能力 | 配置方式 | 鱼雷语义 |
| -------- | -------- | -------- |
| 碰炸（触发引信） | `collision_data.direct_damage` + `detonate_data.explosion_data.explode: true` | 直击命中即爆；鱼雷不需要 `delay_tick`（该字段是**定时引信**：飞行 N tick 后在当前位置引爆，非出管解保——配了会导致鱼雷升空段/入水瞬间自炸） |
| 感应引信（近炸） | `fuse_data.proximity_radius`（>0 启用） | 水下可用——近炸候选收集与流体无关，可对水面舰船水下段起爆；`proximity_fuse_tick` 解保、`proximity_fuse_height` 保护语义照常 |
| 寿终自爆 | `fuse_data.detonate_on_life_end` | life 耗尽在水中自爆（保险机制，防哑雷漂水面，推荐开启） |

近炸已知边界：贴近水底的目标可能被近炸的近地面保护门（基于离地高度）误杀不触发——当前无水下载具目标，仅记录。

## RVP_Explosion 新增字段（`detonate_data.explosion_data`）

水下爆炸独立威力（MCH `ExplosionInWater` 语义对齐）：爆心处于水中时使用水下组参数，否则使用常规组。

| RVP_Explosion字段 | 解释 | 类型 | 默认值 |
| ----------------- | ---- | ---- | ------ |
| damageInWater | 爆心在水下时的爆炸伤害。null = 复用 `damage` | Float | null |
| radiusInWater | 爆心在水下时的爆炸杀伤半径（格）。null = 复用 `radius` | Float | null |

适用位置：直击引爆、近炸引爆、寿终自爆——全部经 `triggerExplosion` 按爆心 `isInWater()` 选值。对实体（舰船/乘员）伤害不受水衰减（本体爆炸伤害无流体检测）；`explosion_damage_factor`（对载具倍率）在水下组同样生效。

**水下爆炸视觉（2026-10-02 增补，MCHR `effectExplosionInWater` 同款）**：爆心在水方块中的 RVP 默认爆炸，客户端把整套闪光/烟/碎屑/火星替换为水柱效果——爆心球域气泡上涌 + 水面 splash 水柱（近中心高、边缘低，重力回落成水冠）+ 水面白浪泡沫圈；爆心在水面以上保持原视觉。深水爆炸（水体顶面超出爆心上方 radius×2）只冒气泡不上抛水柱。水花规模随爆炸 `radius`/`density` 缩放，全部 force 生成远距可见。

**方块破坏边界**：原版水的爆炸抗性为 100，爆炸射线进入水体即被吸收——**水下爆炸基本炸不动固体方块**（原版固有行为，本项目原样继承）。鱼雷 `destroy_block` 建议 `false`；若需"水下爆破破坏地形"属本体侧行为变更，不在 RVP 范围。

# 特效（effects_data 扩展字段）

仅 `rvp:torpedo` 消费；入水花与水中尾迹由**客户端本地判定**入水状态生成，零新增网络包。

| RVP_EffectsData新增字段 | 解释 | 类型 | 默认值 |
| ----------------------- | ---- | ---- | ------ |
| waterTrailParticle | 水中尾迹粒子 id（原版粒子命名空间全名），水中巡航段每 tick 沿弹后生成。**经 `addAlwaysParticle` force 生成，不受原版 32 格粒子生成距离剔除限制，远距可见**（2026-10-02 用户实测修正）。粒子尺寸决定远距观感：`minecraft:bubble` 近距细腻但太小，`minecraft:cloud` 白浪团更醒目（现役鱼雷 JSON 实配）。空串 = 关闭 | String | "minecraft:bubble" |
| waterTrailCount | 水中尾迹每 tick 粒子数量 | int | 2 |
| waterEntryParticle | 入水水花粒子 id，入水瞬间在入水点生成一簇 | String | "minecraft:splash" |
| waterEntrySound | 入水音效 SoundEvent id，空串 = 无声。推荐 "minecraft:entity.player.splash" | String | "" |

尾焰与发射烟：鱼雷不配 `has_rocket_engine`/`missile_native_trail_*`；出管段空中烟迹用现有 `trajectory_particle`（默认 `minecraft:cloud`），入水后自动停用（水中尾迹通道接管）。

# 与其他系统的联动

| 联动系统 | 说明 |
| -------- | ---- |
| 雷达探测 | 鱼雷发射后对雷达**不可见**：雷达扫描的离地高度门（`scan_min_height`，默认 25，基准高度图含水面）天然滤掉水下目标——与真实鱼雷不被雷达探测一致，属预期；发射段空中窗口按 `misc_data.ammo_radar_rcs_factor` 正常可见。反鱼雷预警/声呐属未来独立探测概念，现有雷达参数无法表达 |
| 告警 | 被动告警（RWR/LWR）不触发——鱼雷无辐射源 |
| 命中链 | 直击/近炸走 RVP 统一命中链（含巨型载具 §48 分节盲区补筛），对船命中无需额外配置 |
| 瞄准点 | 载具目标的锁定框/导引头取最大 OBB 中心（§46 resolver）——首版非制导不依赖锁定，机炮反鱼雷的提前量解算不受影响 |
| 敌我 | 自弹排除（不伤发射船）与 IFF 规则同 RVP 通用弹体 |
| 发射平台 | 船载具武器站照常挂载：载具 JSON 武器站配武器 id + 扇面限位（侧舷/舰艏 yaw 限位、小俯仰限位），无发射深度限制 |
| 协议 | 无新增网络包，`PROTOCOL 21` 不变（入水状态双端各自判定） |

# JSON配置示例

533mm 直航反舰鱼雷（触发引信版）：

```json
{
  "type": "rvp:torpedo",
  "name": "Type533 Torpedo",
  "name_CN": "533毫米直航鱼雷",
  "velocity": 3.0,
  "damage": 60,
  "life": 3400,
  "shoot_interval": 40,
  "max_capacity": 6,
  "reload": { "time": 200, "ammo": "ywzj_vehicle:ammo_missile" },

  "torpedo_data": {
    "water_speed": 1.5,
    "water_entry_lerp_tick": 10,
    "depth_damping": 0.8,
    "water_entry_min_depth": 0.5,
    "require_aim_water": false,
    "require_aim_water_range": 150
  },

  "projectile_data": {
    "gravity": -0.02,
    "drag": 0.0,
    "inherit_vehicle_velocity": true,
    "rotate_to_motion": true,
    "max_speed": 3.5
  },

  "fire_data": { "spread": 0.5, "fire_mode": "SEMI_AUTO", "require_lock": false },

  "fuse_data": {
    "delay_tick": 0,
    "proximity_radius": 0,
    "detonate_on_life_end": true
  },

  "collision_data": { "direct_damage": 60 },

  "effects_data": {
    "trajectory_particle": "minecraft:cloud",
    "water_trail_particle": "minecraft:bubble",
    "water_trail_count": 2,
    "water_entry_particle": "minecraft:splash",
    "water_entry_sound": "minecraft:entity.player.splash"
  },

  "detonate_data": {
    "explosion_data": {
      "explode": true,
      "damage": 2400,
      "radius": 8,
      "damage_in_water": 3000,
      "radius_in_water": 10,
      "destroy_block": false
    }
  },

  "misc_data": { "ammo_radar_rcs_factor": [1, 1, 1] }
}
```

324mm 轻型反潜鱼雷（感应引信版）差异段（其余同上）：

```json
  "torpedo_data": { "water_speed": 2.0, "depth_damping": 0.7 },
  "fuse_data": { "delay_tick": 0, "proximity_radius": 4, "proximity_fuse_tick": 15, "detonate_on_life_end": true },
  "detonate_data": { "explosion_data": { "explode": true, "damage": 1200, "radius": 6, "destroy_block": false } }
```

字段语义速记：`velocity`/`damage`（顶层）= 出管初速/直击伤害；`explosion_data.damage` = 爆炸伤害；`delay_tick` 是定时引信（飞行 N tick 后自爆）——碰炸鱼雷必须保持 0；水中航程 = `life` × `water_speed`。
