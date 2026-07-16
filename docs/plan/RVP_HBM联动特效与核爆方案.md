# RVP_HBM联动特效与核爆方案
> 日期：2026-07-16  
> 范围：仅讨论 `ywzj_rvp` 侧对高版本 `HBM-NTM-Rebirth` 的软依赖联动设计，不修改 `ywzj_vehicle`

## 1. 背景与目标

当前 RVP 已经具备两类基础能力：

1. 自身的 `detonate_data` / `effects_data` 落点效果链
2. 对 HBM 导弹实体的软依赖识别、雷达扫描与战术地图代理显示

接下来如果要把 1.7.10 时期 MCHR 对 HBM 的“核爆 / 大爆炸 / 小爆炸 / 破片 / 白磷 / 化学云”联动能力迁移到 RVP，高版本实现需要解决三个问题：

1. **必须保持软依赖**：没装 HBM 时，RVP 仍可正常启动
2. **只能改 RVP**：不能动 `ywzj_vehicle`
3. **不能照搬老式字符串拼装**：MCHR 的 `explosionType=hbmNT_Bomb_frag_WP` 可读性和可维护性都比较差

本方案目标是把这套能力整理成一组适合 RVP JSON 的结构化参数，并给出高版本 HBM 的推荐调用映射。

---

## 2. 老版本 MCHR 是怎么做的

参考：

- `D:\MCHR\MCH-Reforged\src\main\java\mcheli\MCH_HBMUtil.java`
- `D:\MCHR\MCH-Reforged\src\main\java\mcheli\weapon\MCH_WeaponInfo.java`
- `D:\MCHR\MCH-Reforged\src\main\java\mcheli\weapon\MCH_EntityBaseBullet.java`

### 2.1 软依赖方式

MCHR 通过 `Class.forName(...)` + `Method.invoke(...)` 反射调用 HBM 类，典型目标包括：

- 核爆实体
- 蘑菇云实体
- VNT 爆炸
- 爆炸粒子预设
- 白磷 / 氯气 / 破片

也就是说，**MCHR 的联动本质上就是一层 HBM bridge**，而不是编译期硬链接。

### 2.2 老 MCHR 的两条链

#### A. 核爆链

老 MCHR 主要吃这些参数：

- `nukeYield`
- `nukeEffectOnly`
- `effectYield`
- `nukeEffectScale`
- `enableNukeFlash`

行为大意：

- `nukeYield > 0` 且装了 HBM：
  - `nukeEffectOnly=false`：真实核爆 + 蘑菇云
  - `nukeEffectOnly=true`：只出蘑菇云特效

#### B. 普通 HBM 爆炸 / 特效链

老 MCHR 主要吃：

- `explosionType`
- `effectYield`
- `chemYield`

其中 `explosionType` 往往是多标签混写：

- `hbmNT`
- `_Bomb`
- `_Shell`
- `_frag`
- `_WP`

行为不是互斥，而是“核心爆炸 + 附加标签”：

- `hbmNT`：真实 HBM 爆炸核心
- `_Bomb`：叠加大型爆炸视觉
- `_Shell`：叠加小型炮弹爆炸视觉
- `_frag`：额外生成破片
- `_WP`：额外生成白磷 / 烟雾效果
- `chemYield > 0`：额外生成氯气云

---

## 3. 高版本 HBM 的对应实现

参考：

- `D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main\src\main\java\com\hbm\ntm\explosion\NuclearExplosionUtil.java`
- `D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main\src\main\java\com\hbm\ntm\entity\effect\NukeTorexEntity.java`
- `D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main\src\main\java\com\hbm\ntm\explosion\vnt\WeaponExplosionUtil.java`
- `D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main\src\main\java\com\hbm\ntm\explosion\ExplosionLarge.java`
- `D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main\src\main\java\com\hbm\ntm\particle\ParticleUtil.java`
- `D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main\src\main\java\com\hbm\ntm\artillery\LegacyArtilleryImpactExecutor.java`
- `D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main\src\main\java\com\hbm\ntm\player\HbmLivingProperties.java`
- `D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main\src\main\java\com\hbm\ntm\entity\effect\LegacyVentCloudEntity.java`

### 3.1 核爆链

高版本 HBM 更适合拆成两层理解：

#### 真核爆

推荐入口：

- `ExplosionNukeSmall`
- `NuclearExplosionUtil`

这条链负责：

- 真正的爆炸
- 伤害 / 冲击 / 破坏
- 部分附加粒子与后效

#### 纯视觉核爆

推荐入口：

- `NukeTorexEntity`

重要判断：

- 如果只是调用 `ParticleUtil.spawnNuclearBurstVisual(...)`，只能得到一个瞬时“亮一下”的视觉
- 如果想要接近 HBM 自身核爆那套完整体感，**应优先生成 `NukeTorexEntity`**

因此 RVP 方案里更合理的设计是：

- `effect_only`：只生成 `NukeTorexEntity`
- `full`：真核爆 + `NukeTorexEntity`

### 3.2 非核爆链

#### `hbmNT` 对应什么

最接近的现代核心是：

- `WeaponExplosionUtil`

如果想更贴近 HBM 导弹 / 炮弹那种“爆炸 + 碎块 + 粒子”的厚重感，也可以用：

- `ExplosionLarge`

建议区分：

- **只要真实爆炸核心**：优先 `WeaponExplosionUtil`
- **想保留 HBM 风格附加效果**：`ExplosionLarge`

#### `_Bomb` 对应什么

最接近的是大型纯视觉爆炸预设：

- `ParticleUtil.spawnLegacyExplosionLarge(...)`

#### `_Shell` 对应什么

最接近的是小型纯视觉炮弹爆炸预设：

- `ParticleUtil.spawnLegacyExplosionSmall(...)`

#### `_frag` 对应什么

现代应优先直接走正式破片实体：

- `ExplosionLarge.spawnShrapnels(...)`
- `ShrapnelEntity`

#### `_WP` 对应什么

不建议只刷一层粒子。

更合理的是组合：

- 冲击点视觉
- 持续云体
- 对生物附着白磷灼烧

可参考：

- `LegacyArtilleryImpactExecutor`
- `HbmLivingProperties.ensurePhosphorus(...)`

#### `chemYield / chlorine` 对应什么

高版本里更像是：

- `LegacyVentCloudEntity` 体系
- 氯气 / 烟雾类持续云体

因此它不应该只是一次性粒子，而更像一种“区域后效”。

---

## 4. 对 RVP 的参数设计建议

### 4.1 不建议复刻 MCHR 的 `explosionType`

不推荐这种写法：

```json
"explosionType": "hbmNT_Bomb_frag_WP"
```

原因：

1. 可读性差
2. 不利于后期扩展
3. 容易出现标签顺序、大小写、组合歧义

### 4.2 推荐改成结构化对象

建议在 `detonate_data` 下新增：

```json
"hbm_effect_data": {
  "enabled": true,
  "real_explosion": "vnt",
  "visual_preset": "bomb",
  "effect_yield": 12,
  "spawn_frag": true,
  "white_phosphorus": false,
  "chlorine_yield": 0,
  "destroy_block": true
}
```

### 4.3 字段建议表

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `enabled` | `boolean` | `false` | 总开关。`false` 时整段 `hbm_effect_data` 直接不生效，既不会调 HBM 真实爆炸，也不会放 HBM 风格视觉。`true` 后，下面各字段才参与解析。 |
| `real_explosion` | `string` | `none` | 控制“真实爆炸核心”类型。`none` = 不调用 HBM 真实爆炸，只允许纯视觉；`vnt` = 调用 HBM 常规高爆核心，适合大装药、钻地弹、重炮弹；`nuclear` = 调用 HBM 核爆核心，并可配合 `NukeTorexEntity` 生成蘑菇云。这个字段决定伤害、冲击、破坏等实体/方块层面的真实效果。 |
| `visual_preset` | `string` | `none` | 控制“额外视觉预设”，不等于真实伤害。`none` = 不额外附加预设；`shell` = 小型炮弹/榴弹风格视觉；`bomb` = 大型航弹/重爆炸视觉；`nuclear` = 只生成 HBM 蘑菇云视觉，不触发真实核爆核心。它可以和 `real_explosion` 叠加，也可以单独用作“假核弹”纯视觉。 |
| `visual_backend` | `string` | `auto` | 视觉后端选择。`auto` = 安装且兼容 HBM 时优先 HBM，接口缺失或未安装时回退 RVP；`hbm` = 只尝试 HBM，不回退；`rvp` = 强制使用 RVP 内置 `shell / bomb / nuclear` 视觉与音效。这个字段不控制真实爆炸核心。 |
| `visual_sound` | `boolean` | `true` | RVP 内置视觉音效总开关。`false` 时 `shell / bomb / nuclear` 的内置音效都不播放，但粒子仍正常显示。使用 HBM 后端时，HBM 自身是否播放音效由 HBM 实现决定。 |
| `suppress_native_explosion_effect` | `boolean` | `true` | 特殊视觉成功生成后，是否屏蔽 `ywzj_vehicle` 的普通爆炸烟云、闪光、爆炸声和震动，同时不再发送 RVP 自己的 vanilla explosion burst。只影响视觉与声音，不影响 `explosion_data` 的伤害、半径、方块破坏和 ERA 处理。若指定的 HBM 后端不可用且没有成功生成特殊视觉，则不会屏蔽普通爆炸，避免完全无特效。 |
| `visual_scale` | `float/int` | `1.0` | 纯视觉预设缩放系数。对 `shell / bomb` 会缩放爆烟、冲击波、碎屑和声效范围；对 `nuclear` 会缩放传给 HBM 蘑菇云的视觉规模。它不改变 RVP 或 HBM 的真实爆炸伤害。 |
| `visual_density` | `float/int` | `1.0` | 视觉粒子密度，范围 `0.1~1.0`。RVP 后端中，`nuclear` 只降低地面冲击尘云和凝结云数量，核心蘑菇帽、蘑菇柄和核爆环保持完整；`shell/bomb` 会降低云团与装饰碎屑数量，并适度放大保留下来的云团作视觉补偿。HBM 后端目前只把它传给支持密度接口的核爆蘑菇云，旧版 HBM 会自动忽略。 |
| `effect_yield` | `float/int` | `0` | 爆炸强度标量，单位不是现实吨当量，而是 HBM/RVP 内部用的效果规模参数。值越大，真实爆炸半径、附带视觉尺度、持续时间通常越强。对 `real_explosion = nuclear` 而言，它先决定核爆核心规模，再由代码把蘑菇云的视觉半径和高度额外放大，所以不是和真实半径 1:1 对应。对 `vnt` / `shell` / `bomb` 来说，它更多决定“炸得多重、看起来多大”。填 `0` 基本等于不开有效强度。 |
| `spawn_frag` | `boolean` | `false` | 是否额外生成 HBM 破片实体。`false` 时只有爆炸本体；`true` 时会追加破片杀伤，更适合防空破片战斗部、榴弹、预制破片弹。它会增加实体数量和计算量，不适合高频率小口径弹药滥用。 |
| `white_phosphorus` | `boolean` | `false` | 是否附加白磷后效。`false` 时不生成；`true` 时在爆点附加白磷灼烧/持续伤害思路。它属于“爆后持续效应”，不是瞬时爆炸强度本身，通常和 `visual_preset` 或 `vnt` 叠加使用。 |
| `chlorine_yield` | `float/int` | `0` | 氯气/毒云类持续云体强度。`0` = 不生成毒云；大于 `0` = 生成持续存在的化学云，数值越大通常意味着云体规模、持续时间或影响范围越强。它不是爆炸半径，而是化学后效规模，适合毒弹、化学航弹之类配置。 |
| `destroy_block` | `boolean` | `true` | 真实爆炸是否允许破坏方块。`true` = HBM 真实爆炸可改地形、炸建筑；`false` = 仍可保留爆炸伤害/视觉，但尽量不改地形，适合只想要打单位、不想把地图炸烂的玩法。这个字段主要作用于 `real_explosion`，如果只开 `visual_preset` 而没有真实爆炸核心，它基本没有实际效果。 |
| `nuclear_sound` | `boolean` | `true` | 只控制 RVP 内置 `nuclear` 的核爆长音效；它与 `visual_sound` 同时为 `true` 才播放。不会关闭 `shell/bomb` 音效。 |
| `nuclear_flash` | `boolean` | `true` | 是否启用 RVP 内置核爆白屏闪光，只对 `nuclear` 有效。 |
| `nuclear_shake` | `boolean` | `true` | 是否启用 RVP 内置核爆近距离受击式屏幕震动，只对 `nuclear` 有效。 |

### 字段组合关系

- `enabled = false` 时，其它字段全部视为未启用。
- `real_explosion` 决定“真炸不真炸”；`visual_preset` 决定“额外长什么样”。
- `visual_scale` 只管 `visual_preset` 的视觉大小，不管真实伤害范围。
- `visual_backend = rvp` 时，三种视觉预设都不加载 HBM 类；没有安装 HBM 也能工作。
- `visual_sound` 是内置视觉音效总开关，`nuclear_sound` 是核爆音效的第二层独立开关。
- `suppress_native_explosion_effect` 只在特殊视觉后端返回成功时生效；默认开启，单发弹药可显式写 `false` 恢复叠加本体爆炸视觉。
- `visual_density` 控制 RVP 内置三种视觉的粒子密度；降低它可以减少客户端模拟、排序与 billboard 提交开销。
- `effect_yield` 是强度主参数，优先影响 `real_explosion`，同时也会影响对应视觉规模。
- `spawn_frag`、`white_phosphorus`、`chlorine_yield` 都是附加后效，可以在同一发弹药上叠加。
- `destroy_block` 只对真实爆炸链有意义，对纯视觉链基本无效。

---

## 5. 当前核爆参数写法

当前实际实现没有单独新增 `hbm_nuke_data`，而是统一复用 `hbm_effect_data`。

核爆写法如下：

```json
"hbm_effect_data": {
  "enabled": true,
  "real_explosion": "nuclear",
  "effect_yield": 20,
  "destroy_block": true
}
```

### 5.1 `nuclear` 模式的实际行为

- `real_explosion = "nuclear"` 时，会走 HBM 的真实核爆核心。
- 同时 RVP 会单独创建 `NukeTorexEntity`，不再完全依赖 HBM 默认的整包 `spawnNuclear(...)`。
- 这样做的目的，是把“真实爆炸半径”和“蘑菇云外观”分开控制：
  - `effect_yield` 仍主要表示真实核爆规模。
  - 蘑菇云视觉半径会额外放大，避免小当量时云帽太矮、太紧。
  - 蘑菇云生成中心会额外上抬，避免出现“蘑菇帽卡在蘑菇柄中间”的观感。

### 5.2 机上视角可见性补偿

HBM 的大部分蘑菇云 cloudlets 是在客户端 `AFTER_LEVEL` 阶段的全局渲染里补画的。  
当玩家在载具上使用 CRT / thermal / TV 等后处理视角时，原始 HBM 云层有概率被后续主缓冲处理盖掉，表现为：

- 投弹载机上看不到蘑菇云
- 下机后又能看到蘑菇云

当前 RVP 客户端已经在 `AFTER_LEVEL` 末尾增加了一次 HBM 蘑菇云补绘，用来尽量保证：

- 机上视角也能看到蘑菇云
- 尤其是在 CRT / thermal / TV 等后处理激活时保持可见

---

## 6. 推荐的高版本映射表

| RVP 设计项 | 高版本 HBM 推荐入口 | 备注 |
| --- | --- | --- |
| `real_explosion = vnt` | `WeaponExplosionUtil` | 标准真实爆炸核心 |
| `visual_preset = shell` | `ParticleUtil.spawnExplosionSmall(...)` | 小型炮弹视觉，可由 `visual_scale` 缩放 |
| `visual_preset = bomb` | `ParticleUtil.spawnExplosionLarge(...)` | 大型爆炸视觉，可由 `visual_scale` 缩放 |
| `visual_preset = nuclear` | `NukeTorexEntity.createStandard(...)` | 纯视觉蘑菇云，不触发真实核爆核心 |
| `spawn_frag = true` | `ExplosionLarge.spawnShrapnels(...)` | 正式破片实体 |
| `white_phosphorus = true` | `LegacyArtilleryImpactExecutor` 思路 + `HbmLivingProperties.ensurePhosphorus(...)` | 需要组合实现 |
| `chlorine_yield > 0` | `LegacyVentCloudEntity` 系云体 | 持续区域后效 |
| `real_explosion = nuclear` | `NuclearExplosionUtil.spawnNuclearCore(...)` + `NukeTorexEntity.createStandard(...)` | 真核爆核心和蘑菇云拆分生成，便于单独调视觉高度与尺度 |

---

## 7. 建议的实现架构

### 7.1 只放在 `ywzj_rvp`

入口建议仍然放在：

- `RVP_BaseBullet`
- `RVP_DetonateData`
- `RVP_DetonateApplier`

可以新增：

- `RVP_HbmEffectData`
- `RVP_HbmNukeData`
- `RVP_HbmBridge`

其中 `RVP_HbmBridge` 负责：

1. 检查 `ModList.get().isLoaded("hbm_ntm_rebirth")`
2. 通过反射或软链接桥接调用 HBM 类
3. 对外暴露稳定接口，避免弹体逻辑层直接到处写 HBM 反射

### 7.2 推荐职责划分

#### `RVP_HbmBridge`

负责：

- 解析 HBM 是否存在
- 缓存 `Class` / `Method`
- 提供：
  - `spawnVisualShell(...)`
  - `spawnVisualBomb(...)`
  - `explodeVnt(...)`
  - `spawnShrapnels(...)`
  - `spawnChlorineCloud(...)`
  - `spawnWhitePhosphorus(...)`
  - `spawnNukeTorex(...)`
  - `triggerNuke(...)`

#### `RVP_DetonateApplier`

负责：

- 处理 RVP 原有的火焰、药水云、点燃、雷电、放置方块等落点效果
- 新增调度 `hbm_effect_data`

#### `RVP_BaseBullet`

负责：

- 在最终引信触发时决定：
  - 先走 HBM
  - 还是先走 RVP
  - 哪些链互斥，哪些链可叠加

---

## 8. 推荐的执行顺序

为了降低风险，建议分阶段做。

### 第一阶段：最小可用版

先支持：

1. `hbm_effect_data.enabled`
2. `real_explosion = vnt`
3. `visual_preset = shell`
4. `visual_preset = bomb`

这一阶段就已经够把：

- `hbm_NT`
- `hbm_bomb`
- `hbm_shell`

这三种最核心需求落下来。

### 第二阶段：附加效果版

再补：

1. `spawn_frag`
2. `white_phosphorus`
3. `chlorine_yield`

### 第三阶段：核爆版

最后再做：

1. `hbm_nuke_data`
2. `effect_only`
3. `full nuke`
4. 可选 flash / scale / shockwave 参数

---

## 9. JSON 示例

### 9.1 HBM 风格高爆弹

```json
"detonate_data": {
  "explosion_data": {
    "explode": false
  },
  "hbm_effect_data": {
    "enabled": true,
    "real_explosion": "vnt",
    "visual_preset": "bomb",
    "effect_yield": 10,
    "spawn_frag": false,
    "white_phosphorus": false,
    "chlorine_yield": 0,
    "destroy_block": true
  }
}
```

说明：

- 真实爆炸改由 HBM 负责
- 因此 `explosion_data.explode` 建议关掉，避免双爆炸

### 9.2 HBM 风格榴弹 / 炮弹

```json
"detonate_data": {
  "explosion_data": {
    "explode": false
  },
  "hbm_effect_data": {
    "enabled": true,
    "real_explosion": "vnt",
    "visual_preset": "shell",
    "effect_yield": 4,
    "spawn_frag": true,
    "destroy_block": false
  }
}
```

### 9.3 纯视觉核爆

```json
"detonate_data": {
  "explosion_data": {
    "explode": false
  },
  "hbm_effect_data": {
    "enabled": true,
    "real_explosion": "none",
    "visual_preset": "nuclear",
    "effect_yield": 20,
    "visual_scale": 1.0,
    "visual_density": 0.5,
    "destroy_block": false
  }
}
```

### 9.4 真实核爆

```json
"detonate_data": {
  "explosion_data": {
    "explode": false
  },
  "hbm_effect_data": {
    "enabled": true,
    "real_explosion": "nuclear",
    "effect_yield": 35,
    "destroy_block": true
  }
}
```

说明：

- 若想要真正的 HBM 核爆与蘑菇云，请使用 `real_explosion = "nuclear"`。
- 若只想要假核弹视觉，请使用 `visual_preset = "nuclear"`，并关闭常规 `explosion_data`。

---

## 10. 需要特别注意的风险

### 10.1 双重爆炸风险

这是最重要的一条。

如果：

- RVP 的 `explosion_data` 还在爆
- HBM 的 `WeaponExplosionUtil` / `NuclearExplosionUtil` 也在爆

那么最终表现大概率会变成：

- 伤害翻倍
- 破坏翻倍
- 粒子和音效叠加过量

所以后续实现必须先把“谁负责真实爆炸”这条门控写死。

### 10.2 白磷和氯气不适合只做一次性粒子

如果只是播放一个烟雾粒子，手感会非常假。

这两类效果更适合做成：

- 持续云体
- 持续状态伤害
- 区域滞留

### 10.3 核爆视觉不建议只用 `spawnNuclearBurstVisual`

它更像瞬时闪光，而不是完整蘑菇云表现。

如果用户想要“HBM 那味儿”，`NukeTorexEntity` 才是主角。

---

## 11. 结论

如果把这件事压成一句话，就是：

> **RVP 侧不去复刻 HBM，而是做一层结构化、可控、软依赖的 HBM bridge。**

推荐路线：

1. 先做 `hbm_effect_data`
2. 第一版只支持 `vnt / shell / bomb`
3. 再补 `frag / WP / chlorine`
4. 最后做 `hbm_nuke_data`

这样风险最低，也最符合 RVP 现在的工程边界。
