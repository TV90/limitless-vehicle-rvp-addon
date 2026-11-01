# RVP 声音距离切换与播放列表总览

核对日期：2026-11-01。按当前工作区 Java、模组资源和项目根 `limitless_vehicle/rvp/` 权威载具包整理；同日已按用户需求调整殉燃爆响播放方式、发射声兜底范围和默认爆炸近远切换（见 §2、§3）。

范围包括：默认武器发射、弹体划空与命中、默认爆炸、载具殉燃、温压与核爆、告警和常用辅助声音，以及权威载具包 `sounds.json` 中的武器声音。其他模组的资源覆盖、用户游戏目录中的包和本体引擎等通用声音不逐项展开。

## 1. 先区分三种“距离”

- **切换距离**：到这里换另一组音频，例如冷发射在超过 512 格时改用远音；不是把近音、远音一起播放。
- **最远可听距离**：声音随距离增大而变小，到范围边缘趋于无声；收到声音消息也不代表一定能听见。
- **触发距离**：进入这个距离才开始播放，例如炸弹来袭声在玩家距炸弹严格小于 8 格时触发；开始后仍按该声音自己的定位和衰减规则播放。

以下距离单位都是格。`d` 表示用于该项判定的听者距离；`R` 是默认爆炸事件的基础爆炸半径；`V = 基础爆炸半径 × 视觉倍率`；`s` 是 HBM 样式爆炸的视觉倍率。`max(a,b)` 表示取较大值，`clamp(x,a,b)` 表示把 x 限制在 a～b 之间。

## 2. 默认发射、殉燃和弹体声音

| 用途与条件 | 近远切换或触发条件 | 最远距离 / 实际限制 | 播放方式 |
| --- | --- | --- | --- |
| 轮式／履带式地面载具，火箭有效口径 ≥ 220 mm | 不分近远 | 普通定位约 64；配置相机相对声音时按其距离因子，见 §7 | 每次开火播放同一个文件 |
| 地面／水面载具冷发射导弹 | `d ≤ 512` 近音，`d > 512` 远音 | 2048 | 客户端本地播放；追踪范围外由服务端消息兜底；每次发射在对应组随机选 1 个；不叠加普通地面导弹声 |
| 地面／水面载具其他导弹 | 不分近远 | 2048 | 同上；每次发射随机选 1 个 |
| 固定翼／旋翼载具所有导弹 | 不分近远；包括对地导弹、落地飞机和机载冷发射 | 150 | 同上；每次发射随机选 1 个 |
| 殉燃新增弹药爆响 `ammopops` | 不分近远；与持续燃烧声同一套客户端本地跟随 | 约 `16 × view_info.sound_distance`（权威包多数车辆 112～128 格）；声音实例由 256 格内殉燃视觉驱动 | 每段 0.5 秒、随机变体，段间随机静默 1～3 秒；首段立即播放；随燃烧声一起开始/结束 |
| 殉燃原有持续燃烧声 | 不分近远 | 旁观者理论约 `16 × view_info.sound_distance`；声音实例仍由 256 格内殉燃视觉驱动 | 循环；50 tick（2.5 秒）淡入、淡出 |
| 履带载具开始殉燃的一次性殉爆声 | 不分近远 | 同上，使用该载具的 `sound_distance` | 每次真实殉燃只播放一次，3 个文件随机选 1 个 |
| 物理口径 < 45 mm 弹体划空 | 服务端计算真实飞行路径距玩家耳部 ≤ 8 | 8 是触发范围；所选声源的原版衰减半径为 16 | 同一弹体对同一玩家最多一次，6 个文件随机选 1 个 |
| 物理口径 < 45 mm 弹体命中方块 | 向命中点 32 格内玩家发送 | 32 | 11 个文件随机选 1 个；启用爆炸且半径 ≥ 1 时不播放此声 |
| 炸弹飞行哨音 | 玩家距炸弹严格 < 32 时首次触发 | 跟随声约 32；需要客户端已有炸弹实体 | 单次播放，落地时停止 |
| 炸弹临近命中的来袭轰鸣 | 玩家距炸弹严格 < 8，且玩家没有乘坐载具 | 8 是触发门，跟随声约 32；需要客户端已有炸弹实体 | 3 个文件随机选 1 个，单次播放，落地时停止 |

默认导弹发射声**优先在客户端本地播放**：本体开火包只发给追踪载具实体的玩家，这些客户端收到开火完成事件后按自己的相机距离选组并直接播放，零额外数据包。只有载具实体追踪范围外的听者才由服务端补发独立声音消息（按世界坐标播放同一组音频）：追踪范围取实体 `clientTrackingRange`（载具默认 32 区块 ≈ 512 格，下限 16 格）与服务端视距的较小值。追踪范围内不重复发包，因此不会叠音。

`ammopops` 与持续燃烧声共用同一套客户端本地 `VehicleSound`：真实残骸实例出现时一起开始，跟随残骸，距离取该载具 `view_info.sound_distance`。燃烧声是长样本循环 + 50 tick 淡入淡出；弹药爆响是每段 0.5 秒的短促爆裂，每段播完随机静默 1～3 秒再播下一段（变体在 `vehicle_ammo_pops` 事件的 4 个 `near` 文件里随机）。到期、提前移除残骸、离开追踪范围或退出世界时立即停掉当前段。纯客户端殉燃预览不发声。划空声排除射手、同发射载具乘员和旁观模式玩家。

发射声选择顺序仍为：武器表现文件 `sounds` 中存在任意非 `ywzj_vehicle` 声音 ID → 保留整套自定义声音；否则尝试适用的 RVP 默认声音；所选组全部缺失则保留原播放链。`ammopops` 不参与武器声音配置。详见 [武器声音管理](RVP武器声音管理.md)。

## 3. 当前默认爆炸声音

这一表对应 `rvp:mchr_explosion` 默认路径。炮弹爆炸和炮弹发射是两件事；这里的 3560 格不会自动改变开火声范围。

| 爆炸类别 | 近音 | 远音 / 中间档 | 更远档 | 最远可听距离 |
| --- | --- | --- | --- | --- |
| `MACHINEGUN` 且根级、效果口径均没有有效的 > 16 mm 值 | `d ≤ max(200,20R)` | 超过近音阈值后用远音 | 无 | 512 |
| `MACHINEGUN` 且根级 `caliber` 或显式 `effects_data.caliber` 任一有效值严格 > 16 mm，即炮弹音效档 | `d ≤ 200` | `200 < d ≤ 512`，用中间档 far | `512 < d ≤ 3560`，用更远档 very_far | 3560 |
| 火箭弹 `ROCKET` | `d ≤ 512` | `512 < d ≤ 3560` 用远音 | 无 | 3560 |
| 导弹 `MISSILE` | 同上 | 同上 | 无 | 3560 |
| 航弹 `BOMB` | 同上 | 同上 | 无 | 3560 |
| 完整载具击毁爆炸 | `d ≤ 512` | `512 < d ≤ 3560` | 无 | 3560 |

最远距离判断先执行。火箭弹、导弹、航弹与完整载具击毁爆炸使用**固定 512 格近音边界**，不再按 `max(200,20R)` 随爆炸半径扩大近音范围；只有机枪档保留 `max(200,20R)`。未归入上述武器类型时，基础半径 ≥ 8 采用导弹档，否则采用火箭档。

每次爆炸在选中的声音组内随机选一个样本，当前各类都只播放 1 层主爆音。24 格内立即播放；超过 24 格时，等待 `ceil(d / 17.15)` tick，即按约 343 格／秒计算声音抵达时间。近远档和等待时间在接收事件时按玩家位置计算一次，等待期间移动不会重新选择。

显式配置 `detonate_data.explosion_data.explosion_sound` 时，该声音 ID 替换档案里的近、中、远音，但最远距离与音高规则仍采用相应档案。这里的爆炸声音覆盖规则独立于武器表现文件的发射声音优先级。

默认爆炸事件广播范围为 8192 格，这是消息覆盖范围；实际声音仍受本表 512／3560 格限制。现有较早技术文档中的火箭、导弹、航弹 1536 格表述与当前代码不同，当前值为 **3560 格**；完整载具击毁爆炸同样为 3560 格。

## 4. 温压、HBM 样式爆炸和核爆

HBM 样式指项目的 `shell`、`bomb`、`nuclear` 特效路径，其声音规则独立于上一节。

| 路径 | 近远切换 | 声音距离限制 | 播放顺序 |
| --- | --- | --- | --- |
| 温压 `rvp:thermobaric` | `d ≤ max(24,4V)` 近音，否则远音 | 播放器不再做距离音量衰减，也没有独立最远声音常量；受事件 `broadcast_range`（默认 8192）及效果实例存活时间限制 | 近音／远音二选一；主音之后 3～6 tick（0.15～0.30 秒）追加尾音 |
| HBM 样式 `shell` 回退 | 设 `D = clamp(200s,60,600)`；`d ≤ 0.4D` 近音，否则远音 | 仅 `d ≤ D` 播放；默认 `s=1` 时切换 80、最远 200 | 近音／远音二选一 |
| HBM 样式 `bomb` 回退 | 设 `D = clamp(350s,80,800)`；`d ≤ 0.4D` 近音，否则远音 | 仅 `d ≤ D` 播放；默认 `s=1` 时切换 140、最远 350 | 近音／远音二选一 |
| 核爆 `nuclear` | 不分近远 | 当前没有独立声音截止距离；同维度广播，播放音量 10000，对默认 16 格样本的声音引擎理论范围约 160000 格，仍受效果实例寿命限制 | 播放单个 `nuclear_explosion.ogg` |

温压沿用 24 格内立即播放、外部按 343 格／秒延迟；客户端 `thermobaricSoundVolume` 控制音量，0 为关闭。效果实例结束会关闭尚未抵达的主音和尾音，因此“默认广播 8192 格”不能直接理解成温压声音保证覆盖 8192 格。

HBM 样式 `shell`／`bomb` 的延迟按 `floor(d / 8.575)` tick 计算；核爆按 `ceil(d / 17.15)` tick 计算并限制为最多 1200 tick（60 秒）。这些规则没有与默认爆炸统一。

## 5. 实际音频播放列表

下面 `001～003` 表示包含两端的全部编号，不代表一个文件名。未另写说明时，同一组每次随机选 1 个。第 §8 节附有 `sounds.json` 注册表的逐文件清单。

### 5.1 模组 `ywzj_rvp` 音频

这一表的目录相对于 `src/main/resources/assets/ywzj_rvp/sounds/`。

| 用途 | 目录 | 文件名 | 数量 |
| --- | --- | --- | --- |
| 地面大口径火箭发射 | `default/rocket_launch/large/` | `large_rocket_launch.ogg` | 1 |
| 冷发射近音 | `default/missile_launch/cold_launch/near/` | `cold_launch-001.ogg`～`cold_launch-003.ogg` | 3 |
| 冷发射远音 | `default/missile_launch/cold_launch/far/` | `cold_launch_far-001.ogg`～`cold_launch_far-003.ogg` | 3 |
| 地面／水面其他导弹发射 | `default/missile_launch/surface_to_air/` | `surface_to_air_missile_launch_1.ogg`～`surface_to_air_missile_launch_3.ogg` | 3 |
| 机载导弹发射 | `default/missile_launch/air2air/` | `air2air_missile_launch_1.ogg`～`air2air_missile_launch_3.ogg` | 3 |
| 殉燃弹药爆响近音 | `defaultexplosion/vehicle_exp/ammopops/near/` | `ammo_crack-001.ogg`～`ammo_crack-004.ogg` | 4（每个 0.5 秒，按段随机、段间静默 1～3 秒） |
| 殉燃弹药爆响远音 | `defaultexplosion/vehicle_exp/ammopops/far/` | `ammo_crackers_far-001.ogg`～`ammo_crackers_far-003.ogg` | 3（当前播放路径已不引用） |
| 殉燃持续燃烧 | `defaultexplosion/vehicle_exp/burning/` | `vehicle_fire_burning.ogg` | 1 |
| 履带载具一次性殉爆 | `defaultexplosion/vehicle_exp/burst/` | `ammo_burst-001.ogg`～`ammo_burst-003.ogg` | 3 |
| 炮弹爆炸近音 | `defaultexplosion/cannon_exp/near/` | `cannon_def_explode_near.ogg`、`cannon_def_explode_near1.ogg`～`cannon_def_explode_near4.ogg` | 5 |
| 炮弹爆炸中间档 | `defaultexplosion/cannon_exp/far/` | `cannon_def_explode_far.ogg` | 1 |
| 炮弹爆炸更远档 | 同上 | `cannon_def_explode_very_far.ogg` | 1 |
| 火箭弹爆炸近音 | `defaultexplosion/rocket_exp/near/` | `exp_rocket_close_07.ogg`～`exp_rocket_close_09.ogg` | 3 |
| 火箭弹爆炸远音 | `defaultexplosion/rocket_exp/far/` | `exp_rocket_distant_07.ogg`、`exp_rocket_distant_08.ogg` | 2 |
| 导弹爆炸近音 | `defaultexplosion/missile_exp/near/` | `missile_def_explode_1.ogg`～`missile_def_explode_4.ogg` | 4 |
| 导弹爆炸远音 | `defaultexplosion/missile_exp/far/` | `missile_def_explode_distant_1.ogg`～`missile_def_explode_distant_9.ogg` | 9 |
| 航弹爆炸近音 | `defaultexplosion/bomb_exp/near/` | `def_bomb_close_1.ogg`～`def_bomb_close_3.ogg` | 3 |
| 航弹爆炸远音 | `defaultexplosion/bomb_exp/far/` | `def_bomb_dist_1.ogg`～`def_bomb_dist_3.ogg` | 3 |
| 载具击毁爆炸近音 | `defaultexplosion/vehicle_exp/near/` | `vehicle_explosion_near.ogg`、`vehicle_explosion_near1.ogg`～`vehicle_explosion_near4.ogg` | 5 |
| 载具击毁爆炸远音 | `defaultexplosion/vehicle_exp/far/` | `vehicle_explosion_far.ogg`、`vehicle_explosion_far1.ogg`～`vehicle_explosion_far3.ogg` | 4 |
| 小口径弹体划空 | `default/bullet/bullet_incoming/` | `bullet_incoming-001.ogg`～`bullet_incoming-006.ogg` | 6 |
| 小口径弹体方块命中 | `default/bullet/bullet_dirt/` | `bullet_dirt_01.ogg`～`bullet_dirt_11.ogg` | 11 |
| 小口径默认爆炸／HBM 样式 shell 近音 | `nuclear/` | `explosion_small_near1.ogg`～`explosion_small_near3.ogg` | 3 |
| 小口径默认爆炸／HBM 样式 shell 远音 | `nuclear/` | `explosion_small_far1.ogg`、`explosion_small_far2.ogg` | 2 |
| HBM 样式 bomb 近音／远音 | `nuclear/` | `explosion_large_near.ogg`／`explosion_large_far.ogg`，按距离二选一 | 各 1 |
| 核爆 | `nuclear/` | `nuclear_explosion.ogg` | 1 |
| 炸弹哨音 | `misc/` | `bomb_whistle.ogg` | 1 |
| 炸弹临近来袭 | `misc/` | `bombs_incoming_1.ogg`～`bombs_incoming_3.ogg` | 3 |
| 热焰弹／箔条抛洒 | `misc/` | `flare.ogg`／`chaff.ogg`，按干扰物类型选取 | 各 1 |
| 主动电子干扰 | `misc/` | `ecm_jammer.ogg` | 1 |
| 红外／电视制导来袭告警 | `misc/` | `ir_alert.ogg` | 1 |
| 激光照射告警 | `misc/` | `laser_alert.ogg` | 1 |
| 红外导引头锁定音 | `misc/` | `ir_track_alarm.ogg` | 1 |
| 快修扳手声 | `misc/` | `wrench1.ogg`～`wrench3.ogg` | 3 |

以上覆盖当前模组 `ywzj_rvp` 目录全部 **102 个 `.ogg`**，共享音频只计一次。地面大口径火箭、三类导弹发射、小口径划空和命中共 30 个文件通过 Java 内存声音描述直接读取，不要求在 `sounds.json` 里另列事件；殉燃弹药爆响的 4 个 `near` 文件由 `sounds.json` 的 `vehicle_ammo_pops` 事件引用，`far` 的 3 个文件当前没有任何播放路径引用。

### 5.2 温压音频

模组 `src/main/resources/assets/rvp/sounds/` 下另有 3 个文件：

| 声音事件 ID | 文件 | 作用 |
| --- | --- | --- |
| `rvp:thermobaric_near` | `thermobaric/near.ogg` | 近音主爆 |
| `rvp:thermobaric_far` | `thermobaric/far.ogg` | 远音主爆 |
| `rvp:thermobaric_tail` | `thermobaric/tail.ogg` | 主爆后的尾音 |

这些是温压默认预设值，可由 `near_sound`、`far_sound`、`tail_sound` 覆盖。

## 6. 告警、辅助声音与复用本体声音

| 声音 | 触发与播放 | 距离说明 |
| --- | --- | --- |
| `ir_alert` | 本地驾驶载具或玩家受到相应导弹跟踪时播放单次告警；主动雷达分支本身只登记提示 | 本地目标告警，不以导弹距离切换近远文件；骑乘时声音定位到相机附近 |
| `laser_alert` | 本地目标受到激光照射；两次提示音最少间隔 500 毫秒 | 同上 |
| `ir_track_alarm` | 红外导引头完成有效锁定后循环；脱锁、切武器、离车、退出世界时停止 | 本地锁定音，骑乘时相机相对定位；武器 `lock_tone_sound` 可覆盖 |
| `flare`／`chaff` | 每轮抛洒分别播放对应类型声音 | 不分近远；原版音量 1，默认样本下约 16 格 |
| `ecm_jammer` | 主动电子干扰激活、维持时按管理器触发 | 不分近远；原版音量 1，约 16 格 |
| `maintenance_wrench` | 快修过程中每 25 tick（约 1.25 秒）播放一次随机扳手声 | 不分近远；原版音量 1，约 16 格 |
| 武器 `sounds.charge` | 蓄力开始等武器流程调用 | 原版音量 1，默认样本下约 16 格；文件由武器配置选择 |
| 鱼雷入水声 | `effects_data.water_entry_sound` 配置；空值或找不到声音事件时静默跳过 | 不分近远；原版音量 1，实际范围还取决于所选声音事件的样本距离参数 |
| 烟雾弹发射／展开 | 复用本体 `smoke_grenade_launch`／`smoke_grenade_explosion` | 音量 1；本项目没有额外近远切换规则 |
| 热焰弹拖尾 | 复用本体 `DECOY_FLARE_TAIL`，客户端单次播放 | 音量 0.8；本项目没有额外近远切换规则 |
| 原版通用爆炸回退 | `RVP_VehicleExplosion` 的小／中／大／核样式分支均播放 `minecraft:entity.generic.explode` | 半径 ≤2／≤8／≤32／>32 时分别使用音量 2／4／8／14；默认 16 格样本下理论范围约 32／64／128／224 格，不切换不同音频组 |

表中约 16 格等值按当前默认 16 格样本距离计算。资源包更换样本距离参数会改变原版声音引擎的范围，不应把这些辅助声音统一理解成 150 或 2048 格。

## 7. 自定义发射声与相机位置的影响

`audio_info.camera_relative_fire_sound_distance = k > 0` 时，普通自定义开火声和默认大口径火箭使用本体跟随声音：骑乘者的声源被放到相机约 8 格内；旁观者按距离因子缩放声源距离，默认样本下理论范围约 `16k`。未开启时，普通开火音量 4，默认理论范围约 64 格。

配置 `sounds.sight_fire` 后，本车的普通 `fire` 和观瞄 `sight_fire` 音轨同时起播，一条静音；切换视角只切换可听音轨，不重新从头播放。未配置 k 时使用 k=4，即旁观者默认约 64 格。这个切换依据是**视角**，不是远近。

新增导弹声音在开启相机相对配置时，只让骑乘者沿用相机附近定位；旁观者仍使用真实声源坐标和 150／2048 格范围，不额外乘 k。新增导弹按听者相机位置选近远组（本地播放与兜底消息同一规则）；默认爆炸和温压在接收事件时按玩家位置选组；`ammopops` 与燃烧声同为跟随声，会经过本体的相机相对位置计算。

另外，未由 RVP 默认发射声接管的普通开火声，还有一个距离音量处理器：64 格内不额外压低，64～500 格按平方曲线降低音量，超过 500 格取消播放。这个 500 格是额外的取消门，不会把原本约 64 格的普通定位声扩成 500 格；实际还要看原版声音衰减、样本参数、是否匹配登记坐标以及相机相对定位。

## 8. 声音事件注册表逐文件清单

“声音事件 ID”是配置引用的名字，例如 `ywzj_rvp:vehicle_explosion_near`；它可以对应多段 `.ogg`，默认在组内随机选择。“登记在注册表”只表示可供引用，不表示当前每辆车都正在使用。

下面逐项读取两份模组 `sounds.json` 和项目根权威载具包 `sounds.json`，检查本次磁盘上的引用文件。未列入 JSON 的 37 个直接播放文件见 §5.1。

### 模组内置 ywzj_rvp（30 个声音组）

文件目录：`src/main/resources/assets/ywzj_rvp/sounds/`。表内路径均从这个目录起算。

| 声音事件 ID | 实际文件 | 当前文件检查 |
| --- | --- | --- |
| `ywzj_rvp:nuclear_explosion` | `nuclear/nuclear_explosion.ogg` | 全部存在 |
| `ywzj_rvp:explosion_large_near` | `nuclear/explosion_large_near.ogg` | 全部存在 |
| `ywzj_rvp:explosion_large_far` | `nuclear/explosion_large_far.ogg` | 全部存在 |
| `ywzj_rvp:explosion_small_near` | `nuclear/explosion_small_near1.ogg`<br>`nuclear/explosion_small_near2.ogg`<br>`nuclear/explosion_small_near3.ogg` | 全部存在 |
| `ywzj_rvp:explosion_small_far` | `nuclear/explosion_small_far1.ogg`<br>`nuclear/explosion_small_far2.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_machinegun_near` | `nuclear/explosion_small_near1.ogg`<br>`nuclear/explosion_small_near2.ogg`<br>`nuclear/explosion_small_near3.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_machinegun_far` | `nuclear/explosion_small_far1.ogg`<br>`nuclear/explosion_small_far2.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_cannon_near` | `defaultexplosion/cannon_exp/near/cannon_def_explode_near.ogg`<br>`defaultexplosion/cannon_exp/near/cannon_def_explode_near1.ogg`<br>`defaultexplosion/cannon_exp/near/cannon_def_explode_near2.ogg`<br>`defaultexplosion/cannon_exp/near/cannon_def_explode_near3.ogg`<br>`defaultexplosion/cannon_exp/near/cannon_def_explode_near4.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_cannon_far` | `defaultexplosion/cannon_exp/far/cannon_def_explode_far.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_cannon_very_far` | `defaultexplosion/cannon_exp/far/cannon_def_explode_very_far.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_rocket_near` | `defaultexplosion/rocket_exp/near/exp_rocket_close_07.ogg`<br>`defaultexplosion/rocket_exp/near/exp_rocket_close_08.ogg`<br>`defaultexplosion/rocket_exp/near/exp_rocket_close_09.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_rocket_far` | `defaultexplosion/rocket_exp/far/exp_rocket_distant_07.ogg`<br>`defaultexplosion/rocket_exp/far/exp_rocket_distant_08.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_missile_near` | `defaultexplosion/missile_exp/near/missile_def_explode_1.ogg`<br>`defaultexplosion/missile_exp/near/missile_def_explode_2.ogg`<br>`defaultexplosion/missile_exp/near/missile_def_explode_3.ogg`<br>`defaultexplosion/missile_exp/near/missile_def_explode_4.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_missile_far` | `defaultexplosion/missile_exp/far/missile_def_explode_distant_1.ogg`<br>`defaultexplosion/missile_exp/far/missile_def_explode_distant_2.ogg`<br>`defaultexplosion/missile_exp/far/missile_def_explode_distant_3.ogg`<br>`defaultexplosion/missile_exp/far/missile_def_explode_distant_4.ogg`<br>`defaultexplosion/missile_exp/far/missile_def_explode_distant_5.ogg`<br>`defaultexplosion/missile_exp/far/missile_def_explode_distant_6.ogg`<br>`defaultexplosion/missile_exp/far/missile_def_explode_distant_7.ogg`<br>`defaultexplosion/missile_exp/far/missile_def_explode_distant_8.ogg`<br>`defaultexplosion/missile_exp/far/missile_def_explode_distant_9.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_bomb_near` | `defaultexplosion/bomb_exp/near/def_bomb_close_1.ogg`<br>`defaultexplosion/bomb_exp/near/def_bomb_close_2.ogg`<br>`defaultexplosion/bomb_exp/near/def_bomb_close_3.ogg` | 全部存在 |
| `ywzj_rvp:mchr_explosion_bomb_far` | `defaultexplosion/bomb_exp/far/def_bomb_dist_1.ogg`<br>`defaultexplosion/bomb_exp/far/def_bomb_dist_2.ogg`<br>`defaultexplosion/bomb_exp/far/def_bomb_dist_3.ogg` | 全部存在 |
| `ywzj_rvp:vehicle_explosion_near` | `defaultexplosion/vehicle_exp/near/vehicle_explosion_near.ogg`<br>`defaultexplosion/vehicle_exp/near/vehicle_explosion_near1.ogg`<br>`defaultexplosion/vehicle_exp/near/vehicle_explosion_near2.ogg`<br>`defaultexplosion/vehicle_exp/near/vehicle_explosion_near3.ogg`<br>`defaultexplosion/vehicle_exp/near/vehicle_explosion_near4.ogg` | 全部存在 |
| `ywzj_rvp:vehicle_explosion_far` | `defaultexplosion/vehicle_exp/far/vehicle_explosion_far.ogg`<br>`defaultexplosion/vehicle_exp/far/vehicle_explosion_far1.ogg`<br>`defaultexplosion/vehicle_exp/far/vehicle_explosion_far2.ogg`<br>`defaultexplosion/vehicle_exp/far/vehicle_explosion_far3.ogg` | 全部存在 |
| `ywzj_rvp:vehicle_ammo_burst` | `defaultexplosion/vehicle_exp/burst/ammo_burst-001.ogg`<br>`defaultexplosion/vehicle_exp/burst/ammo_burst-002.ogg`<br>`defaultexplosion/vehicle_exp/burst/ammo_burst-003.ogg` | 全部存在 |
| `ywzj_rvp:vehicle_fire_burning` | `defaultexplosion/vehicle_exp/burning/vehicle_fire_burning.ogg` | 全部存在 |
| `ywzj_rvp:vehicle_ammo_pops` | `defaultexplosion/vehicle_exp/ammopops/near/ammo_crack-001.ogg`<br>`defaultexplosion/vehicle_exp/ammopops/near/ammo_crack-002.ogg`<br>`defaultexplosion/vehicle_exp/ammopops/near/ammo_crack-003.ogg`<br>`defaultexplosion/vehicle_exp/ammopops/near/ammo_crack-004.ogg` | 全部存在 |
| `ywzj_rvp:countermeasure_flare` | `misc/flare.ogg` | 全部存在 |
| `ywzj_rvp:countermeasure_chaff` | `misc/chaff.ogg` | 全部存在 |
| `ywzj_rvp:ir_alert` | `misc/ir_alert.ogg` | 全部存在 |
| `ywzj_rvp:laser_alert` | `misc/laser_alert.ogg` | 全部存在 |
| `ywzj_rvp:ecm_jammer` | `misc/ecm_jammer.ogg` | 全部存在 |
| `ywzj_rvp:ir_track_alarm` | `misc/ir_track_alarm.ogg` | 全部存在 |
| `ywzj_rvp:bomb_whistle` | `misc/bomb_whistle.ogg` | 全部存在 |
| `ywzj_rvp:bombs_incoming` | `misc/bombs_incoming_1.ogg`<br>`misc/bombs_incoming_2.ogg`<br>`misc/bombs_incoming_3.ogg` | 全部存在 |
| `ywzj_rvp:maintenance_wrench` | `misc/wrench1.ogg`<br>`misc/wrench2.ogg`<br>`misc/wrench3.ogg` | 全部存在 |

### 模组内置 rvp 温压声音（3 个声音组）

文件目录：`src/main/resources/assets/rvp/sounds/`。表内路径均从这个目录起算。

| 声音事件 ID | 实际文件 | 当前文件检查 |
| --- | --- | --- |
| `rvp:thermobaric_near` | `thermobaric/near.ogg` | 全部存在 |
| `rvp:thermobaric_far` | `thermobaric/far.ogg` | 全部存在 |
| `rvp:thermobaric_tail` | `thermobaric/tail.ogg` | 全部存在 |

### 权威载具包 rvp 武器声音（15 个声音组）

文件目录：`limitless_vehicle/rvp/assets/rvp/sounds/`。表内路径均从这个目录起算。

| 声音事件 ID | 实际文件 | 当前文件检查 |
| --- | --- | --- |
| `rvp:gsh_30_1_shot` | `weapon/auto_cannon/gsh_30_1_001.ogg`<br>`weapon/auto_cannon/gsh_30_1_002.ogg`<br>`weapon/auto_cannon/gsh_30_1_003.ogg` | 全部存在 |
| `rvp:missile_start_med` | `weapon/missile/missile_start_med_001.ogg` **缺失**<br>`weapon/missile/missile_start_med_002.ogg` **缺失**<br>`weapon/missile/missile_start_med_003.ogg` **缺失** | 3 个缺失 |
| `rvp:railgun_charge` | `weapon/railgun_snd.ogg` | 全部存在 |
| `rvp:railgun_fire` | `weapon/railgun_f_snd.ogg` | 全部存在 |
| `rvp:m61a1_snd` | `weapon/auto_cannon/m61a1_snd.ogg` | 全部存在 |
| `rvp:zbl08a_ru_30_shot` | `weapon/auto_cannon/ru_30_snd.ogg` | 全部存在 |
| `rvp:nato_25_snd` | `weapon/auto_cannon/nato_25_snd.ogg` | 全部存在 |
| `rvp:kda_35_snd` | `weapon/kda_35_snd_2.ogg` | 全部存在 |
| `rvp:laser_shot` | `weapon/laser/laser_shot_snd.ogg` | 全部存在 |
| `rvp:114514` | `weapon/114514.ogg` | 全部存在 |
| `rvp:2s38_snd` | `weapon/2s38_snd.ogg` | 全部存在 |
| `rvp:ru_125_fire_snd` | `weapon/ru_125_fire_snd.ogg` | 全部存在 |
| `rvp:tank_atgm_fire_snd` | `weapon/tank_atgm_fire_snd.ogg` | 全部存在 |
| `rvp:nato_120_snd` | `weapon/nato_120_snd.ogg` | 全部存在 |
| `rvp:tank_120_atgm_snd` | `weapon/tank_120_atgm_snd.ogg` | 全部存在 |



## 9. 核对结果与代码入口

模组内置两套命名空间合计 105 个 `.ogg`，全部纳入上述播放列表；新增导弹、殉燃和小口径直读音频也已核对。权威载具包的 `rvp:missile_start_med` 仍列了 3 个音频引用，但当前权威副本未找到对应文件，详见 §8；这里只记录现状，未改动资源或配置。

若武器引用这条自定义声音，即使文件缺失，仍属于武器明确配置的非本体声音；按当前规则会保留自定义配置，不会因此自动改播 RVP 默认导弹声。载具包另有 `weapon/tank_atgm_snd.ogg` 文件，但没有列入该包 `sounds.json`，不计入已注册播放组。

主要代码入口：

- [未追踪载具的发射声兜底消息](../../src/main/java/org/ywzj/rvp/network/S2CVehicleSound.java)
- [发射声兜底发送范围（实体追踪范围外）](../../src/main/java/org/ywzj/rvp/weapon/sound/RVP_VehicleSoundService.java)
- [导弹发射声本地播放、选组与兜底接收](../../src/main/java/org/ywzj/rvp/client/RVP_ClientVehicleSound.java)
- [默认火箭与自定义声音优先级](../../src/main/java/org/ywzj/rvp/client/RVP_ClientWeaponFireSound.java)
- [默认爆炸距离与音效类别](../../src/main/java/org/ywzj/rvp/client/visual/RVP_DefaultExplosionSoundController.java)
- [炮弹音效口径门槛](../../src/main/java/org/ywzj/rvp/weapon/visual/RVP_DefaultExplosionVisualService.java)
- [小口径弹体触发条件](../../src/main/java/org/ywzj/rvp/weapon/sound/RVP_SmallCaliberSoundPolicy.java)
- [殉燃燃烧声与弹药爆响（同一套本地跟随）](../../src/main/java/org/ywzj/rvp/client/visual/cookoff/RVP_WreckCookoffController.java)
- [温压选组与播放时序](../../src/main/java/org/ywzj/rvp/client/visual/thermobaric/RVP_ThermobaricSoundController.java)
- [HBM 样式 shell／bomb 回退](../../src/main/java/org/ywzj/rvp/client/nuclear/RVP_ExplosionVisualManager.java)
- [核爆声音](../../src/main/java/org/ywzj/rvp/client/nuclear/RVP_NuclearVisualManager.java)
- [炸弹飞行哨音与来袭声](../../src/main/java/org/ywzj/rvp/entity/projectile/RVP_BombEntity.java)
- [普通开火距离音量处理](../../src/main/java/org/ywzj/rvp/client/RVP_VehicleFireSoundAttenuator.java)

本文为当前实现快照；后续更改距离、音频组或播放入口时，应同步更新对应行。
