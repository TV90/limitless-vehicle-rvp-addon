# 声明与致谢（Licenses, Notices & Acknowledgements）

本项目（YWZJ RVP，`limitless-vehicle-rvp-addon`）整体以 **GNU General Public License v3.0**
（`LICENSE`）分发，是 **Limitless Vehicle（ywzj_vehicle）** 的 Submod/addon。

---

## 1. 基座项目：Limitless Vehicle（ywzj_vehicle）

- **仓库/项目**：Limitless Vehicle（`ywzj_vehicle`）
- **许可**：GNU General Public License v3.0
- **作者**：YWZJ
- **关系**：RVP 是本体的 Submod/addon——载具、武器、弹道、渲染、雷达、网络等全部核心
  框架均由本体提供，RVP 的所有功能都建立在本体之上，离开本体无法运行。本项目与本体
  同许可（GPL-3.0）、同一作者体系。
- **分发说明**：运行 RVP 需同时安装本体；本体的版权与许可声明以其仓库
  `LICENSE.txt` / `README.md` 为准。

## 2. 第三方并入：HBM's Nuclear Tech Mod: Rebirth（HBM NTM Rebirth）

- **上游仓库**：<https://github.com/10MeV/HBM-NTM-Rebirth>
- **原始许可**：GNU Lesser General Public License v3.0（LGPL-3.0-only；仓库同时提供
  GPLv3 全文与 LGPLv3 附加条款）
- **版权**：归 HBM NTM Rebirth 原作者及贡献者所有
- **并入方式与范围**：
  - **代码移植**（按上游行为/数学重写为本项目结构，不含上游类引用）：
    - `org.ywzj.rvp.client.particle.RVP_RocketFlameParticle`
      （源自上游 `ParticleRocketFlame` / `ParticleSmokePlume` 的更新与渲染数学）
    - `org.ywzj.rvp.client.nuclear.RVP_NuclearVisualManager`
      （源自上游 Torex 核爆视觉行为）
    - `org.ywzj.rvp.client.nuclear.RVP_ExplosionVisualManager`
      （源自上游 `explosionSmall` / `explosionLarge` 视觉行为）
  - **素材复制**（SHA-256 与路径明细见 `docs/RVP_HBM临时资产迁移清单.md`）：
    - `assets/ywzj_rvp/textures/nuclear/particle_base.png`
    - `assets/ywzj_rvp/textures/nuclear/flare.png`（2026-09-20 于本项目内补全 alpha 通道）
    - `assets/ywzj_rvp/textures/nuclear/shockwave.png`
    - `assets/ywzj_rvp/sounds/nuclear/` 下的核爆/爆炸音效若干
- **许可说明**：LGPL-3.0 授权的库并入 GPL-3.0 程序、并随程序整体按 GPL-3.0 分发，是
  LGPLv3 第 3 条明确许可的合并方式；并入部分随本项目以 GPL-3.0 授权再分发。

## 3. 参考来源（非并入，独立实现）

本项目部分功能的代码实现参考以下项目；RVP 侧为独立实现，未复制上游代码，
但按透明与尊重原则声明来源：

- **SuperbWarfare**（GPL-3.0，作者 Atsuishio、Roki27、Light_Quanta 及其他贡献者）
  ——RVP 的**战术地图**功能在代码实现上参考了其战术地图的设计。
- **HBM's Nuclear Tech Mod（1.7.10 本体）**（<https://github.com/HbmMods/Hbm-s-Nuclear-Tech-GIT>，
  LGPL-3.0，HbmMods）
  ——RVP 的**火箭发动机特效**在代码实现上参考了本体的实现方式
  （与上文已并入的 NTM: Rebirth 为不同时期的上游项目）。

---

## 致谢（Acknowledgements）

- **Limitless Vehicle（ywzj_vehicle）**——感谢本体作者与贡献者打造的载具框架。
  没有本体就没有 RVP：从载具实体、武器系统到渲染管线，RVP 的每一行代码都
  站在本体的肩膀上。
- **MCheli Reforged**——RVP 中大量的武器系统均移植于MCheli Reforged模组。
  RVP 与 MCHR 同属一个作者体系，特此感谢项目中并肩的伙伴。
- **HBM's Nuclear Tech Mod（本体与 NTM: Rebirth）**——感谢上游项目的核爆/爆炸
  视觉设计与素材，以及火箭发动机特效的实现参照，为 RVP 的重火力表现提供了
  极佳的参照与起点。
- **SuperbWarfare**——本项目的战术地图在代码实现上参考SBW的战术地图，特此致谢。
- 感谢所有测试、反馈与支持本项目的玩家。

## 声明格式说明

第三方并入内容均满足各自许可对版权声明与许可文本保留的要求；本项目自带的修改
（如 flare.png 的 alpha 通道补全）不改变上游许可的适用。如上游权利人对并入方式
另有要求，将以善意原则协商处理。
