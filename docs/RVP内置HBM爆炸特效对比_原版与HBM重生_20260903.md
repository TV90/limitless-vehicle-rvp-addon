# RVP 内置 HBM bomb 爆炸特效 vs 原版 HBM / HBM 重生 对比调研

> 调研日期：2026-09-03
> 范围：仅代码调查与文档，未做任何代码改动（按用户要求）。
> 关注点：RVP 内置的 HBM 核爆/炸弹爆炸视觉，与原版 HBM、HBM 重生（NTM Rebirth）的差异，"为什么观感不太像"。

---

## 1. Mod 关系链

```
原版 HBM（Hbm's Nuclear Tech，1.7.10 老 mod，com/hbm）
        │  高版本移植重写（1.20.1）
        ▼
HBM NTM Rebirth（hbm_ntm_rebirth，com.hbm.ntm）
        │  RVP 从这里移植 / 桥接爆炸视觉实现
        ▼
RVP 内置 HBM 特效（ywzj_rvp，org.ywzj.rvp.weapon.effects / client.nuclear）
```

- **原版 HBM**：最老的实现，爆炸视觉由三部分组成 —— 冲击波（MukeWave）+ 烟柱（RocketFlame）+ 碎片（Debris，用 `WorldInAJar` 抓取世界真实方块拼成 `debrisSize³` 立体方块簇抛飞）。
  关键源：`com/hbm/particle/helper/ExplosionCreator.java`、`ParticleMukeWave.java`、`ParticleRocketFlame.java`、`ExplosionSmallCreator.java`。
- **HBM NTM Rebirth**：原版 HBM 的 1.20.1 重制版，包名改为 `com.hbm.ntm`。爆炸实现挪到：
  - `com/hbm/ntm/particle/ParticleUtil.java`（`spawnExplosionLarge` 等入口）
  - `com/hbm/ntm/client/particle/HbmParticleEffects.java`（客户端实际生成粒子）
  - `com/hbm/ntm/client/particle/LegacyDebrisParticle.java`（立体碎片粒子）
  - 并用 `sampleLegacyDebrisStates` 复刻了原版 `WorldInAJar` 的算法：从世界中心 2×2×2 起步，逐层向外扩张抓取真实方块，填满 `debrisSize³` 的 `BlockState[]`。
- **RVP 内置**：在 `RVP_HbmEffectBridge` / `RVP_HbmVisualService`（服务端调度）+ `RVP_ExplosionVisualManager`（客户端渲染）中**重新实现**了 HBM 的爆炸视觉。它不是抄原版 HBM，而是从 HBM 重生移植/桥接。通过反射优先调用 HBM 重生（若其已加载），否则回退到 RVP 自己的简化实现。

---

## 2. 涉及代码位置

| 角色 | 文件 |
| --- | --- |
| RVP 特效桥接（服务端） | `org.ywzj.rvp.weapon.effects.RVP_HbmEffectBridge` |
| RVP 特效发包（服务端） | `org.ywzj.rvp.weapon.effects.RVP_HbmVisualService` |
| RVP 特效数据 | `org.ywzj.rvp.weapon.data.RVP_HbmEffectData` |
| RVP 自研视觉渲染（客户端） | `org.ywzj.rvp.client.nuclear.RVP_ExplosionVisualManager` |
| HBM 重生 入口 | `com.hbm.ntm.particle.ParticleUtil`（含 `spawnExplosionLarge`） |
| HBM 重生 客户端 | `com.hbm.ntm.client.particle.HbmParticleEffects`（含 `spawnExplosionLarge` / `sampleLegacyDebrisStates`） |
| HBM 重生 立体碎片 | `com.hbm.ntm.client.particle.LegacyDebrisParticle` |

---

## 3. 三部分特效逐项对比

### 3.1 冲击波（Muke Wave）—— 基本一致

| 维度 | HBM 重生 | RVP 自研 | 结论 |
| --- | --- | --- | --- |
| 缩放 | `waveScale` 默认 65 | `clamp(65*scale, 8, 220)`（`RVP_HbmEffectBridge.bombWaveScale`） | 一致 |
| 扩散公式 | `1 - exp(-0.125 * t)` | `(1 - exp(age * -0.125)) * waveScale`（`RVP_ExplosionVisualManager.renderWaveTess`） | 一致 |
| 混合 | 加法混合 | 加法混合（`SRC_ALPHA, ONE`） | 一致 |
| 贴地四边形 | 中心 `y + 2.0` | 中心 `cy + 1.75`（BOMB only） | 基本一致 |

**结论：像素级还原，冲击波看不出差异。**

### 3.2 烟柱（Rocket Flame / Blast Cloud）—— 基本一致

| 维度 | HBM 重生 | RVP 自研 | 结论 |
| --- | --- | --- | --- |
| 层数 | BOMB 预设 10 层 | BOMB 预设 `layers = 10` | 一致 |
| 暗度/透明度/扩散 | `dark`、`alpha`、`spread` 公式 | `BlastCloud` 内 `dark/alpha/spread` 公式一致 | 一致 |
| 颜色/尺寸 | `layerAdd/layerScale/layerOffset` 随机层 | 同名字段、同随机逻辑 | 一致 |
| 寿命 | `70 + rand(20)` | `70 + rand(20)` | 一致 |

**结论：像素级还原，烟柱看不出差异。**

### 3.3 碎片（Debris）—— 这是"不像"的核心差异 ⚠️

**HBM 重生（`HbmParticleEffects.spawnExplosionLarge`，约 L961–L1014）：**
```java
for (int i = 0; i < debrisCount; i++) {
    if (debrisSize <= 0) continue;
    // 从世界真实方块抓取 debrisSize³ 立体簇
    BlockState[] debrisStates = sampleLegacyDebrisStates(
            level, x+offsetX, y+debrisVerticalOffset, z+offsetZ,
            debrisSize, debrisRetry, random);   // L1000-1002
    ...
    Particle particle = LegacyDebrisParticle.create(
            level, x, y, z, mx, motionY, mz, debrisStates, debrisSize); // L1009-1010
}
```
`sampleLegacyDebrisStates`（约 L1574）构造 `new BlockState[size*size*size]`，以世界中心 2×2×2 起步、逐层向外按 `debrisRetry` 次尝试抓取**真实存在的世界方块**填入 —— 即原版 `WorldInAJar` 的立体方块簇算法。**每个碎片是一个带真实纹理的 `debrisSize³` 立体块。**

**RVP 自研（`RVP_ExplosionVisualManager.spawnDebris`，约 L417–L438）：**
```java
BlockPos samplePos = BlockPos.containing(center.x, center.y - 0.1, center.z);
BlockState state = level.getBlockState(samplePos);          // 只取一个地表方块
... // 最多向下找 6 格直到非空
int particlesPerChunk = preset == BOMB ? 4 : 1;
for (int i = 0; i < debrisCount * particlesPerChunk; i++) {
    level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), // 单方块粒子
            center.x, center.y + 0.1, center.z,
            gaussian*horizontal, vertical, gaussian*horizontal);
}
```
- 只从地表采样 **1 个** `BlockState`；
- 每个碎片是 **`ParticleTypes.BLOCK` 单方块粒子**（vanilla 的方块碎屑），所有碎片共用同一纹理；
- **没有 `debrisSize³` 立体簇，没有真实方块纹理抓取。**

**结论：碎片是观感差异的唯一实质来源。** HBM 是"带真实世界纹理的实心方块立方体被抛飞"，RVP 自研是"地表同一种方块的小碎渣乱飞"。

---

## 4. 桥接机制 —— 为什么"有时像、有时不像"

`RVP_HbmEffectData.visual_backend` 默认 `"auto"`（`RVP_HbmEffectData.java:22`）。`RVP_HbmEffectBridge.applyVisualPresetWithBackend` 据此分支：

```java
case "rvp"  -> RVP_HbmVisualService.spawn(...)          // 强制自研（无立体碎片）
case "hbm"  -> modLoaded && applyVisualPreset(...)       // 强制 HBM 重生反射（有立体碎片）
default     -> { hbmApplied = modLoaded && applyVisualPreset(...);
                yield hbmApplied || RVP_HbmVisualService.spawn(...); }  // 先 HBM 后自研
```

关键：默认 `"auto"` 下，**只要 HBM 重生 mod（`hbm_ntm_rebirth`）在 classpath 加载**，RVP 就反射调用 `ParticleUtil.spawnExplosionLarge`，并传入 `bombDebrisSize(visualScale)`（默认 16，`RVP_HbmEffectBridge.bombDebrisSize` L269）。重生据此生成 `16³` 的真实纹理立体碎片 —— **此时 RVP 的炸弹爆炸与原版 HBM 一致、有立体碎片**。

只有一种情况会回退到 RVP 自研（无立体碎片）：
- **HBM 重生 mod 未加载、且 `visual_backend` 不是显式 `"hbm"`**（即纯 RVP 运行、或桥接反射失败）。

所以"观感不太像"发生在一个很具体的场景：**没有把 HBM NTM Rebirth 一起加载、只跑 RVP 时**。一旦同跑 HBM 重生，桥接会把爆炸视觉整体交给重生，差异消失。

---

## 5. 音效对比

| 维度 | HBM 重生 | RVP 自研 |
| --- | --- | --- |
| 距离延迟 | 按 `distance / (17.15*0.5)` 延迟播放（音速延迟） | `LEGACY_SPEED_OF_SOUND = 17.15*0.5`，`soundDelayTicks = distance / speed`（`RVP_ExplosionVisualManager` L55/L383） |
| 音域 | `350 * sqrt(scale)` | BOMB `clamp(350*sqrt(scale), 80, 1200)`（`RVP_ExplosionVisualManager` L380） |
| 近/远音 | `playExplosionLarge`（近/远两版） | `RVP_Sounds.EXPLOSION_LARGE_NEAR / _FAR` |

**结论：音效参数与机制一致，无差异。**

---

## 6. 总结："不像"的根因

1. 冲击波、烟柱、音效：**像素级一致**，不是差异来源。
2. **唯一实质差异在碎片**：RVP 自研只用 `ParticleTypes.BLOCK` 单方块粒子，缺失 HBM 的 `debrisSize³` 真实世界方块立体簇（`LegacyDebrisParticle` + `sampleLegacyDebrisStates`）。
3. 但 RVP 通过 `RVP_HbmEffectBridge` 在 HBM 重生存在时**整体桥接**到重生实现，因此差异仅在"未加载 HBM 重生"时出现。

一句话：**RVP 自己画的爆炸（冲击波+烟柱）已经和 HBM 几乎一模一样，缺的只是那团"被炸飞的真实泥土/石头立方体"；而只要同开 HBM 重生，RVP 会直接借用重生的完整实现，连这团立体碎片也有了。**

---

## 7. 边界说明（本次未改代码）

- 以上为现状调查与对比描述，按用户要求本次**未修改任何源码**。
- 若后续希望"纯 RVP 不依赖 HBM 重生也长出立体碎片"，可行方向（待另行批示）：
  - 在 `RVP_ExplosionVisualManager.spawnDebris` 中按 `debrisSize` 抓取世界真实方块、自绘 `debrisSize³` 立体簇（复刻 `sampleLegacyDebrisStates` + `LegacyDebrisParticle` 思路）；
  - 或在 `RVP_HbmEffectData` 中提供"强制自研立体碎片"开关。
  - 这两个方向都只触及客户端视觉、不改伤害/世界破坏逻辑，风险可控。
