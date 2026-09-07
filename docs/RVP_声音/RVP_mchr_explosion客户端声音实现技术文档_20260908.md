# `rvp:mchr_explosion` 客户端声音实现技术文档

> 基线日期：2026-09-08  
> 基线范围：当前工作区（包含尚未提交的代码与声音资源修改）  
> 适用项目：`limitless-vehicle-rvp-addon` / Minecraft 1.20.1 Forge

## 1. 文档目的

本文记录 RVP 内置默认爆炸事件 `rvp:mchr_explosion` 的现有声音实现，覆盖：

- 服务端发布与客户端播放链路；
- MACHINEGUN、ROCKET、MISSILE、BOMB 的声音分流；
- 近音、远音、声速延迟和距离衰减；
- `SoundEvent` ID、`sounds.json` 路径和 OGG 文件路径之间的关系；
- `defaultexplosion/` 当前素材状态；
- 响度和空间听感的调节方法；
- 当前工作区中已经确认的实现与测试不一致。

本文只描述和审计当前实现，不把尚未实施的压力波、混响尾声或移动声源方案描述成现有功能。

## 2. 当前结论

当前默认爆炸声音已经改为**服务端发布爆炸事件、每个客户端根据自身听者距离独立驱动播放**。服务端不再为该默认路径补播 `GENERIC_EXPLODE`，从而避免服务端声音与客户端近远音重复。

当前实现具备：

- 按类型化 `RVP_EnumWeaponKind` 分流音色，不硬编码武器 ID；
- 近音和远音两套事件；
- 24 格内即时反馈，24 格外按声速延迟；
- 固定爆心的单声道三维定位；
- 线性距离衰减与最大可听距离；
- 确定性素材变体和轻微音高抖动；
- 1～3 层的响度叠加参数。

当前没有实现：

- 爆炸压力波抵达玩家身体时的双耳低频层；
- 环境反射或混响尾声；
- 从爆炸侧穿过玩家再向另一侧远去的移动声源；
- 播放等待期间根据玩家移动重新计算距离、近远音或抵达时间。

## 3. 运行链路

```text
RVP_BaseBullet.triggerExplosion
        │
        ├─ 判断 HBM / visual_effect_data 是否接管或屏蔽默认视觉
        │
        └─ RVP_DefaultExplosionVisualService.spawn
                │
                ├─ 写入爆心、爆炸半径、seed、startGameTime、sound=true
                ├─ 把 RVP_EnumWeaponKind 编码为 {"weapon_kind":"..."}
                └─ S2CVisualEffectEvent（effectType = rvp:mchr_explosion）
                        │
                        └─ RVP_DefaultExplosionEffectFactory
                                │
                                ├─ 生成客户端爆炸粒子
                                ├─ 解码 weapon_kind
                                └─ RVP_DefaultExplosionEffectInstance
                                        │
                                        └─ RVP_DefaultExplosionSoundController
                                                ├─ 记录客户端听者距离
                                                ├─ 选择武器声音档案
                                                ├─ 选择 near / far
                                                ├─ 等待声波抵达 tick
                                                └─ 在固定爆心播放空间声音
```

关键源码：

- `src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java`
- `src/main/java/org/ywzj/rvp/weapon/visual/RVP_DefaultExplosionVisualService.java`
- `src/main/java/org/ywzj/rvp/weapon/visual/RVP_DefaultExplosionEventData.java`
- `src/main/java/org/ywzj/rvp/client/visual/RVP_DefaultExplosionEffectFactory.java`
- `src/main/java/org/ywzj/rvp/client/visual/RVP_DefaultExplosionEffectInstance.java`
- `src/main/java/org/ywzj/rvp/client/visual/RVP_DefaultExplosionSoundController.java`

### 3.1 服务端职责

`RVP_DefaultExplosionVisualService.spawn` 创建一次 `RVP_VisualEffectEvent`：

- `effectType`：`rvp:mchr_explosion`；
- `preset`：`rvp:default`；
- `canonicalPresetDataJson`：包含类型化 `weapon_kind`；
- `position`：服务端权威爆心；
- `baseExplosionRadius`：已解析后的最终爆炸半径；
- `broadcastRange`：1536 格；
- `seed`：服务端随机种子；
- `startGameTime`：事件起始世界时间；
- `sound`：`true`。

事件仅发送给同维度、爆心 1536 格内的客户端。声音本身不在服务端播放。

### 3.2 客户端职责

客户端工厂收到事件后：

1. 生成默认 MCHR 风格爆炸粒子；
2. 从事件 JSON 解码 `weapon_kind`；
3. 使用 `startGameTime` 计算网络传输已经经过的 tick；
4. 创建 `RVP_DefaultExplosionSoundController`；
5. 由控制器按本地玩家距离独立选择声音并等待声波抵达。

迟到的网络事件会通过 `initialAge` 补帧：如果声波按理论时间已经抵达，客户端会立即播放，而不是再完整等待一次。

## 4. 武器类型分流与当前参数

当前 `SoundProfile` 构造参数顺序为：

```text
nearSound, farSound, basePitch, maximumRange, loudnessLayers
```

| 武器类型 | 近音事件 | 远音事件 | 基础音高 | 最大距离 | 响度层数 |
|---|---|---|---:|---:|---:|
| MACHINEGUN | `mchr_explosion_machinegun_near` | `mchr_explosion_machinegun_far` | 1.00 | 512 格 | 1 |
| ROCKET | `mchr_explosion_rocket_near` | `mchr_explosion_rocket_far` | 0.98 | 1536 格 | 1 |
| MISSILE | `mchr_explosion_missile_near` | `mchr_explosion_missile_far` | 0.90 | 1536 格 | 1 |
| BOMB | `mchr_explosion_bomb_near` | `mchr_explosion_bomb_far` | 0.80 | 1536 格 | 1 |

分流直接使用 `RVP_EnumWeaponKind`：

- `MACHINEGUN` → MACHINEGUN；
- `ROCKET` → ROCKET；
- `MISSILE` → MISSILE；
- `BOMB` → BOMB；
- 其他类型在爆炸半径不小于 8 时回退为 MISSILE，否则回退为 ROCKET；
- 空类型也会安全进入上述半径回退，不会按具体武器资源 ID 判断。

因此，`rvp:mchr_explosion` **能够按武器行为类型播放不同种类音效**，且符合项目禁止硬编码武器 ID 的要求。

## 5. 时间、近远音与距离衰减

### 5.1 声速延迟

当前常量：

```text
SPEED_OF_SOUND_BLOCKS_PER_TICK = 17.15
IMMEDIATE_SOUND_DISTANCE = 24
```

计算规则：

```text
distance <= 24：arrivalTick = 0
distance > 24：arrivalTick = ceil(distance / 17.15)
```

17.15 格/tick 等价于 343 格/秒。示例：

| 距离 | 延迟 tick | 约合时间 |
|---:|---:|---:|
| 24 格 | 0 | 立即 |
| 128 格 | 8 | 0.40 秒 |
| 200 格 | 12 | 0.60 秒 |
| 512 格 | 30 | 1.50 秒 |
| 1536 格 | 90 | 4.50 秒 |

24 格内的即时播放是游戏反馈折中，并非严格物理模拟。

### 5.2 近音和远音

当前近音阈值为：

```text
nearDistance = max(200, explosionRadius × 20)
```

玩家在阈值内使用 `nearSound`，超过阈值使用 `farSound`。例如：

| 爆炸半径 | 近音范围 |
|---:|---:|
| 2 | 200 格 |
| 5 | 200 格 |
| 10 | 200 格 |
| 20 | 400 格 |

需要注意，听者距离和近远音只在控制器构造时计算一次。玩家在声波等待期间移动，不会触发重新选择。

### 5.3 线性距离衰减

`SpatialExplosionSound` 使用：

```java
attenuation = SoundInstance.Attenuation.LINEAR;
relative = false;
```

声源坐标固定为服务端权威爆心，因此声音会随玩家与爆心距离线性衰减，并保留方向信息。近似可理解为：

```text
距离响度系数 ≈ max(0, 1 - listenerDistance / maximumRange)
```

实际结果还会受以下因素影响：

- Minecraft“敌对生物”声音分类音量；
- 系统音量和输出设备；
- OpenAL/HRTF 实现；
- OGG 素材自身峰值、平均响度与频谱；
- 同时播放的叠加层数。

控制器设置：

```text
volume = max(1, maximumRange / 16)
```

这里的高 `volume` 主要用于把 Minecraft 的默认 16 格衰减范围扩大到 `maximumRange`；最终单声源增益仍会被声音引擎钳制。因此，继续把该值增大通常只会让衰减更慢、传播更远，并不能可靠提升爆心附近的峰值响度。

超过 `maximumRange` 的客户端在控制器构造时直接跳过播放。

### 5.4 当前范围下的近似衰减

ROCKET、MISSILE、BOMB 当前最大距离均为 1536 格：

| 距离 | 近似保留响度 |
|---:|---:|
| 100 格 | 93.5% |
| 300 格 | 80.5% |
| 600 格 | 60.9% |
| 1000 格 | 34.9% |
| 1536 格 | 0% |

MACHINEGUN 当前最大距离为 512 格：

| 距离 | 近似保留响度 |
|---:|---:|
| 100 格 | 80.5% |
| 300 格 | 41.4% |
| 512 格 | 0% |

## 6. `path` 和声音资源映射

这里需要区分三层名称。

### 6.1 视觉事件类型

```text
rvp:mchr_explosion
```

这是 RVP 网络视觉领域事件类型，用于让客户端选择 `RVP_DefaultExplosionEffectFactory`。它不是声音文件路径。

### 6.2 声音事件 ID

控制器中的 `nearSound` 和 `farSound` 是声音事件 ID。例如：

```text
ywzj_rvp:mchr_explosion_rocket_near
```

`RVP_MOD.modLocation("mchr_explosion_rocket_near")` 会为字符串补上本 Mod 的 `ywzj_rvp` namespace。对应事件同时在 `RVP_Sounds` 中注册。

### 6.3 `sounds.json` 中的素材 path

`assets/ywzj_rvp/sounds.json` 的事件键：

```json
"mchr_explosion_rocket_near": {
  "sounds": [
    "ywzj_rvp:defaultexplosion/rocket_exp/near/exp_rocket_close_07"
  ]
}
```

素材 path 控制声音事件最终读取哪个 OGG。解析规则为：

```text
namespace:path
       ↓
assets/<namespace>/sounds/<path>.ogg
```

上述示例对应：

```text
assets/ywzj_rvp/sounds/defaultexplosion/rocket_exp/near/exp_rocket_close_07.ogg
```

规则要点：

- `sounds.json` 中省略 `.ogg` 扩展名；
- namespace 必须与资源目录一致，此处是 `ywzj_rvp`；
- path 从 `sounds/` 下开始计算；
- 文件和目录保持小写，避免跨平台资源加载失败；
- 一个声音事件可以列出多个 path，Minecraft 会从中随机选择一个变体；
- `near` 和 `far` 是项目约定的事件分组，不会由 Minecraft 自动推导。

## 7. 当前素材清单

`assets/ywzj_rvp/sounds/defaultexplosion/` 当前共有 18 个 OGG，全部已经验证为：

- 单声道（1 channel）；
- 48,000 Hz；
- 路径和文件名为小写。

| 分类 | near 数量 | far 数量 | 合计 |
|---|---:|---:|---:|
| ROCKET | 3 | 2 | 5 |
| MISSILE | 4 | 3 | 7 |
| BOMB | 3 | 3 | 6 |
| 合计 | 10 | 8 | 18 |

MACHINEGUN 当前没有使用 `defaultexplosion/machinegun_exp/`，而是复用：

- `nuclear/explosion_small_near1～3`；
- `nuclear/explosion_small_far1～2`。

### 7.1 ROCKET 素材响度状态

ROCKET 的 5 个 OGG 已在当前工作区直接提升 `+6 dB`，文件名、单声道和 48 kHz 采样率保持不变。转换后探测到的峰值均为 `0.0 dB`。

这意味着素材已触及数字满刻度，继续直接提升存在明显削波和失真风险。后续若还嫌“不够响”，优先调整平均响度、低频能量和动态，而不是继续无条件增加峰值。

## 8. 响度调节入口

| 调节点 | 位置 | 作用 | 风险 |
|---|---|---|---|
| 素材增益 | OGG 文件 | 直接改变样本响度 | 超过 0 dB 易削波 |
| `loudnessLayers` | `SoundProfile` 第 5 个参数 | 同步播放相同爆音，翻倍理论约 +6 dB | 占用更多声源、易削波、并发爆炸时更混浊 |
| `maximumRange` | `SoundProfile` 第 4 个参数 | 改变最大距离和线性衰减斜率 | 不是独立近场增益；范围过大会让远处过响 |
| `basePitch` | `SoundProfile` 第 3 个参数 | 改变音高和播放速度 | 太低会拖沓，太高会削弱厚重感 |
| `CLOSE_SOUND_DISTANCE` | 控制器常量 | 改变 near 素材最低覆盖范围 | 不改变单个素材本身响度 |
| `radius × 20` | `resolveNearSoundDistance` | 大爆炸扩大 near 素材范围 | near 素材传播过远时可能不自然 |

当前四类 `loudnessLayers` 均为 1。由于 ROCKET 素材已经直接增加 6 dB，ROCKET 保持 1 层可以避免“素材 +6 dB，再双层约 +6 dB”形成接近 +12 dB 的重复增强。

如果以后启用双层，建议最多使用 2 层并进行多人、多爆炸并发测试。构造器会把输入限制在 1～3 层。

## 9. 单声道、左右定位与 HRTF

三维固定点声源应使用单声道素材。立体声 OGG 通常不能作为普通世界坐标声源正确进行左右定位，因此将 `defaultexplosion/` 转为单声道是正确处理。

单声道并不等于双耳等响。爆炸位于玩家正左侧时：

- 未开启“定向音频”时，OpenAL 可能主要通过左右音量平移表示方向，右耳会很弱；
- 开启 Minecraft“音乐和声音 → 定向音频”后，HRTF 会提供更自然的双耳时间差、头部遮蔽和频谱差异；
- 当前声源固定在爆心，不会从左侧穿过玩家移动到右侧，所以不会出现明显的“左耳先听到、再从右耳远去”。

人头尺度的真实双耳到达时间差通常不足 1 毫秒，而 Minecraft 一个 tick 是 50 毫秒。若要明显的左右穿越感，需要额外设计电影化移动冲击波声层，不能依靠当前固定爆心声音自然产生。

## 10. 当前基线中的已知不一致

### 10.1 距离只采样一次

客户端在收到事件时记录：

- 玩家到爆心距离；
- near / far 选择；
- 声波抵达 tick；
- 是否超过最大范围。

等待数秒期间即使玩家高速飞行，上述值也不会重算。对于 1536 格、最长约 4.5 秒的延迟声音，这可能产生可感知误差。

## 11. 后续改善建议

### 11.1 推荐的真实感分层

在保留当前固定爆心主爆音的基础上，可分三层设计：

1. **直达爆炸声**：当前单声道、固定爆心、线性衰减声音，负责方位；
2. **压力冲击层**：声波抵达时在听者位置播放短促低频，双耳均可感知；
3. **环境尾声**：延迟若干 tick，在玩家周围或后方播放较弱反射与轰鸣。

这比继续单纯拉高主爆音更容易获得“有冲击、有空间、仍能判断方向”的效果。

### 11.2 电影化左右穿越

若明确追求“爆炸从左耳进入，穿过后从右耳远去”，可新增独立的客户端 `AbstractTickableSoundInstance`，只移动一段短促冲击波或呼啸声：

```text
爆炸方向一侧 → 玩家中心 → 相反方向
```

建议持续 0.2～0.4 秒。主爆炸录音仍固定在真实爆心，否则整段爆炸主体和尾声一起横穿会显得不自然。此方案可以通过独立客户端声音类实现，不需要 Mixin。

### 11.3 素材响度规范

后续批量制作素材时建议同时记录：

- 单声道、48 kHz、Vorbis；
- sample peak / true peak；
- integrated LUFS；
- near 与 far 的平均响度差；
- 是否存在硬削波；
- 耳机 HRTF 和普通立体声两种模式试听结果。

ROCKET 当前峰值已达 0 dB。若继续处理，建议先回留至少约 1 dB 峰值余量，再用压缩、限制器或频谱塑形提高感知响度。

## 12. 验证清单

### 12.1 资源检查

- `sounds.json` 事件键与 Java `SoundProfile` ID 一致；
- `sounds.json` 中的 namespace 为 `ywzj_rvp`；
- 每条 path 均能映射到真实 `.ogg`；
- 目录和文件名全部小写；
- 三维定位素材为单声道；
- 无遗漏的 near / far 变体。

### 12.2 游戏内检查

- MACHINEGUN、ROCKET、MISSILE、BOMB 分别触发正确音色；
- near / far 阈值两侧没有明显断裂或异常响度跳变；
- 24 格内立即播放，远距离可感知声速延迟；
- 最大范围外不会播放；
- 左、右、前、后、上、下方向定位正常；
- 分别在“定向音频”开和关时试听；
- 连续爆炸和多人战斗时无严重削波、爆音或声源抢占；
- 切换世界或实例关闭后，未抵达声音不会错误补播。

### 12.3 构建检查

项目规定的完整构建命令：

```powershell
./gradlew build
```

## 13. 相关文件索引

| 文件 | 职责 |
|---|---|
| `src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java` | 爆炸触发、默认视觉屏蔽矩阵、默认事件发布入口 |
| `src/main/java/org/ywzj/rvp/weapon/visual/RVP_DefaultExplosionVisualService.java` | 服务端构造并广播 `rvp:mchr_explosion` |
| `src/main/java/org/ywzj/rvp/weapon/visual/RVP_DefaultExplosionEventData.java` | `weapon_kind` 编解码与安全回退 |
| `src/main/java/org/ywzj/rvp/server/visual/RVP_NetworkVisualEventPublisher.java` | 按维度和范围发送事件 |
| `src/main/java/org/ywzj/rvp/client/visual/RVP_DefaultExplosionEffectFactory.java` | 客户端粒子生成、武器类型解码、声音实例创建 |
| `src/main/java/org/ywzj/rvp/client/visual/RVP_DefaultExplosionEffectInstance.java` | 延迟声音生命周期推进 |
| `src/main/java/org/ywzj/rvp/client/visual/RVP_DefaultExplosionSoundController.java` | 武器分流、近远选择、声速延迟、衰减和响度参数 |
| `src/main/java/org/ywzj/rvp/all/RVP_Sounds.java` | Forge `SoundEvent` 注册 |
| `src/main/resources/assets/ywzj_rvp/sounds.json` | 声音事件到素材 path 的映射 |
| `src/main/resources/assets/ywzj_rvp/sounds/defaultexplosion/` | ROCKET、MISSILE、BOMB 的默认爆炸 OGG |
| `src/test/java/org/ywzj/rvp/client/visual/RVP_DefaultExplosionSoundControllerTest.java` | 声速、近远阈值、武器分流和响度层数测试 |

