# RVP HBM 临时资产迁移清单

> 建立日期：2026-07-16  
> 来源仓库：`D:\ywzj\ywzj\HBM-NTM-Rebirth-main\HBM-NTM-Rebirth-main`  
> 上游项目：`10MeV/HBM-NTM-Rebirth`  
> 上游声明：`LGPL-3.0-only`，仓库同时提供 `LICENSE` 与 `LICENSE.LESSER`  
> ~~当前策略：下列资产仅作为功能开发和视觉对照用临时资产，全部标记为 `待替换`。~~
> **策略更新（2026-09-20 用户定版）：不再替换**——RVP 整体按 GPL-3.0 分发（`gradle.properties`
> 已改为 GPL-3.0，与根目录 LICENSE 一致），LGPL-3.0 资产/代码并入 GPL-3.0 项目随项目再分发
> 是 LGPLv3 §3 明确许可的合并方式。来源与许可声明见项目根 `THIRD_PARTY_NOTICES.md`，
> 下列资产继续保留，状态行仅作历史记录。

## 1. 许可证状态

- HBM 上游 `gradle.properties` 声明 `mod_license=LGPL-3.0-only`。
- HBM 上游 `README.md` 明确说明项目按 LGPLv3 分发，并以 `LICENSE`、`LICENSE.LESSER` 为准。
- ~~RVP 当前 `gradle.properties` 声明 `mod_license=All Rights Reserved`，但仓库根目录 `LICENSE` 内容是 GPLv3，二者存在冲突。~~
  **已解决（2026-09-20）**：`mod_license` 改为 `GNU General Public License v3.0`。
- 合规要求（继续生效）：保留 HBM 来源、许可证声明与修改说明——已由 `THIRD_PARTY_NOTICES.md`
  与各移植类头部声明满足；flare.png 于 2026-09-20 在本项目内补全 alpha 通道（修改说明见
  `scripts/fix_flare_alpha_20260920.py` 与 `docs/调试与修复规范.md` §4）。

## 2. 已复制资产

| 用途 | HBM 原始路径 | RVP 目标路径 | SHA-256 | 状态 |
| --- | --- | --- | --- | --- |
| 核爆、普通爆炸云团 billboard | `src/main/resources/assets/hbm_ntm_rebirth/textures/particle/particle_base.png` | `src/main/resources/assets/ywzj_rvp/textures/nuclear/particle_base.png` | `4102E5282A45DE86CC02C68C4EA18BFEA868F4FD621AEC038239BDB7A882207A` | 待替换 |
| 核爆初始 flare | `src/main/resources/assets/hbm_ntm_rebirth/textures/particle/flare.png` | `src/main/resources/assets/ywzj_rvp/textures/nuclear/flare.png` | `353FEB776F5E1E7CA2F036E95FB4C26442EBBD9366CAF6356756692C48CE1F50` | 待替换 |
| `bomb` 平面冲击波 | `src/main/resources/assets/hbm_ntm_rebirth/textures/particle/shockwave.png` | `src/main/resources/assets/ywzj_rvp/textures/nuclear/shockwave.png` | `FD40104F7ECB9BF11001EC96FD0A2E3A0C57806148CE44257F250833BEF43E29` | 待替换 |
| 核爆延迟音效 | `src/main/resources/assets/hbm_ntm_rebirth/sounds/weapon/nuclear_explosion.ogg` | `src/main/resources/assets/ywzj_rvp/sounds/nuclear/nuclear_explosion.ogg` | `C4F117C34D871D1D678DD864721AA4DC7988474E6D622FA178D44B3B7AA623F0` | 待替换 |
| `bomb` 近距离爆炸音效 | `src/main/resources/assets/hbm_ntm_rebirth/sounds/weapon/explosion_large_near.ogg` | `src/main/resources/assets/ywzj_rvp/sounds/nuclear/explosion_large_near.ogg` | `603D30D35D2127FA03EE5C949FE3E7517A06B9DCBEFAA6CF2D5637CC140C831F` | 待替换 |
| `bomb` 远距离爆炸音效 | `src/main/resources/assets/hbm_ntm_rebirth/sounds/weapon/explosion_large_far.ogg` | `src/main/resources/assets/ywzj_rvp/sounds/nuclear/explosion_large_far.ogg` | `2E7A0B95BFA5BE6F29535AE2158A96C75B87A549E11CE37F94453E033B38D9A0` | 待替换 |
| `shell` 近距离音效变体 1 | `src/main/resources/assets/hbm_ntm_rebirth/sounds/weapon/explosion_small_near1.ogg` | `src/main/resources/assets/ywzj_rvp/sounds/nuclear/explosion_small_near1.ogg` | `E6135F121C775AFAAE3AFEDBF578ED5296801FA960C15C400CD160FCF7179861` | 待替换 |
| `shell` 近距离音效变体 2 | `src/main/resources/assets/hbm_ntm_rebirth/sounds/weapon/explosion_small_near2.ogg` | `src/main/resources/assets/ywzj_rvp/sounds/nuclear/explosion_small_near2.ogg` | `2AF3AE36B192F68588DA3B066CAC44C13F82B519777C0E99C484D968E5E48385` | 待替换 |
| `shell` 近距离音效变体 3 | `src/main/resources/assets/hbm_ntm_rebirth/sounds/weapon/explosion_small_near3.ogg` | `src/main/resources/assets/ywzj_rvp/sounds/nuclear/explosion_small_near3.ogg` | `956ED783FFA1D6153099F8C36E5044256748AC1D514352DC20B5912A3F8E4AD3` | 待替换 |
| `shell` 远距离音效变体 1 | `src/main/resources/assets/hbm_ntm_rebirth/sounds/weapon/explosion_small_far1.ogg` | `src/main/resources/assets/ywzj_rvp/sounds/nuclear/explosion_small_far1.ogg` | `D628EB92ACA29EE9A27FFA93E383C07FFABC6B17B7EF2A2905BE534865D6A71E` | 待替换 |
| `shell` 远距离音效变体 2 | `src/main/resources/assets/hbm_ntm_rebirth/sounds/weapon/explosion_small_far2.ogg` | `src/main/resources/assets/ywzj_rvp/sounds/nuclear/explosion_small_far2.ogg` | `9EC9B44FBF4B0656790B9DAE60020375E8D40CF1A6D7230A59A016D16F7B90A7` | 待替换 |
| 球形冲击波 shader 描述 | `src/main/resources/assets/hbm_ntm_rebirth/shaders/core/warp_world.json` | `src/main/resources/assets/ywzj_rvp/shaders/core/warp_world.json` | `4367ECC62848DADC6F36ED890D28BD1A5CDA8C98C4A78C8E6F955AF7AC870762` | 已适配命名空间，待替换/重写 |
| 球形冲击波 vertex shader | `src/main/resources/assets/hbm_ntm_rebirth/shaders/core/warp_world.vsh` | `src/main/resources/assets/ywzj_rvp/shaders/core/warp_world.vsh` | `4CBD72CABCD9CF309A0A9E9904EEFE619F0432AC4CD9C1D348C753C857886CEC` | 已精简适配，待替换/重写 |
| 球形冲击波 fragment shader | `src/main/resources/assets/hbm_ntm_rebirth/shaders/core/warp_world.fsh` | `src/main/resources/assets/ywzj_rvp/shaders/core/warp_world.fsh` | `53A4BAF48EC04C45359B0E97F6A072E4F3D5C529423613E3E89B03DA8F0D7A65` | 已保留冲击波分支并精简其它材质分支，待替换/重写 |

## 3. 代码行为参考

以下 HBM 源码只作为行为参考，RVP 实现位于自身命名空间且不引用 HBM 类：

| HBM 参考源码 | RVP 对应实现 | 用途 |
| --- | --- | --- |
| `client/particle/HbmParticleEffects.java` | `client/nuclear/RVP_ExplosionVisualManager.java` | `shell/bomb` 粒子组成、尺寸、速度和音效距离 |
| `client/particle/ExplosionSmallParticle.java` | `RVP_ExplosionVisualManager.BlastCloud` | `shell` 火球颜色、透明度、寿命与扩张 |
| `client/particle/RocketFlameParticle.java` | `RVP_ExplosionVisualManager.BlastCloud` | `bomb` 多层火焰烟云 |
| `client/particle/MukeWaveParticle.java` | `RVP_ExplosionVisualManager.renderWave` | `bomb` 冲击波扩张曲线 |
| `client/sound/HbmDelayedSounds.java` | `RVP_ExplosionVisualManager.ExplosionEffect` | 声速延迟、近远音效切换 |
| `entity/effect/NukeTorexEntity.java`、`client/renderer/NukeTorexRenderer.java` | `client/nuclear/RVP_NuclearVisualManager.java` | 核爆蘑菇云、环流、地面尘云与 LOD |
| `client/render/HbmRenderEffects.java` | `client/nuclear/RVP_NuclearShockwaveRenderer.java` | 球形屏幕折射冲击波、球壳网格和淡出曲线 |

## 4. 替换顺序建议

1. 优先替换 7 个普通爆炸音频，保持事件名不变即可无代码替换。
2. 再替换 `nuclear_explosion.ogg`，注意保留长尾和远距离低频信息。
3. 替换 `particle_base.png` 时同时测试 `shell`、`bomb`、`nuclear`，它们共用该纹理。
4. 最后替换 `shockwave.png` 与 `flare.png`，检查透明边缘、mipmap 和 depth sorting。
