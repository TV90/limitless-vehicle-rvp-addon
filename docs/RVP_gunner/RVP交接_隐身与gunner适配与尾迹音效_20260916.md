# RVP 交接文档：分角度 RCS 隐身 / gunner 适配 / ARH 相位 / 尾迹与音效（2026-09-16）

> **用途**：上下文交接。新窗口从此文档恢复全部背景；末节附新窗口开场提示词。
> **当前状态**：在 `289ffa94` 之上追加 **gunner 本地锁俯仰射界门修复 + `/rvpdebug gunnerlock` 诊断**
> （根因/验证见 `docs/RVP_gunner/RVP_gunner本地锁俯仰门修复_20260916.md`），已提交并双端同步；
> 工作区干净（载具包照例不进 git）。

---

## 一、本窗口会话主线（按提交顺序，`git log --oneline -32` 可复核）

### A. 尾迹系统（HBM 风格移植 + 五轮实机调参）
- `5359e5ce` 移植主体：`RVP_RocketFlameParticle`（TRAIL 空中尾迹 / WASH 地面烟浪双模式），
  `rvp_rocket_flame` 风格 + `missile_native_trail_ground_wash` 字段；复用 `textures/nuclear/particle_base.png`。
- 五轮调参迭代（`41d4acf4`、`d7141aac`、`9cb89ce5`、`5b2681f0` 等）：彩色噪点修复（烟相位 R=G=B 中性灰）、
  半宽曲线收窄、大发散只归属 WASH、深度状态修复（depthMask(false) + 层间 α 分摊）、烟浪趋白/压扁/6→8 粒。
- **当前定版形态**：TRAIL = sin³ 感知柱状（出生半宽 0.5~0.8、末端 1.8~2.1×scale、寿命 45~65t、
  3 层抖动 quad、层间 α 1/3）；WASH = 贴地烟浪（8 粒/tick、0.3→3.0×scale 趋白压扁、浮升随寿命衰减）。

### B. ARH 主动弹四段相位 + 组网智能拦截（gunner 隐身与拦截体系）
- `c31171d9`→`0cb44c27`→`3148e402`→`c30c60bb`：组网智能拦截
  （`RVP_GunnerEngagementNet`：限位硬禁 60t + 排斥窗 100~200t 随距离滑动；
  **读取点在 `findCiwsTarget`**——炮车选来袭导弹实际走 CIWS 层，`findBestTarget` 层是辅助）；
  enemy 档案补 `player` 类型；创造模式无条件免攻击。
- **ARH 相位四段**（`606e1293` 内）：纯中继 → 干扰判定段（原定开机距离起，箔条判定 + MSL 告警，TWS 保留）
  → 主动段（原定 × combined 隐身缩减后的真开机距离）→ 全程每候选 effectiveRange。
- `d3f765dd`/`b2059555`(远端)：gunner 丢目标时同步清全部雷达锁（后被远端重构迁入 `RVP_GunnerRadarActions.maintainLocalLock`）。

### C. 分角度 RCS 雷达隐身（本窗口核心，`606e1293`→`2eb00aa1`→`289ffa94`）
- 载具 JSON：`rvp_radar_rcs_factor: [front, side, rear]`（sin³ 段内插值，CURVE_POWER=1.5 类内常量可调）
  + 弹舱部件 `open_radar_rcs_multiplier`（逐弹舱开启增幅连乘，**连乘后封顶 1**）。
- 三条消费链：①客户端雷达探测表后过滤（mechanical/phase 统一，豁免锁定目标与 RVP 弹体）；
  ②ARH 主动雷达导引头获取距离（`scanRadarTarget` 每候选 effectiveRange；**AIR 主动红外不受影响**）；
  ③gunner 索敌感知距离（`passesAspectPerception`，仅索敌收集、跟踪保持不查）。
- **中继落锁也吃隐身因子**（`892e95a9`）：修复"RWR 显示被锁定但 gunner 不开火"的两链不对称。
- **已锁定目标出烧穿范围**：跟踪保持 5 秒（100t 宽限）后脱锁（`61f9cc78`，烧穿语义定版）。
- gunner 感知语义：`resolveSearchRadius = max(profile 基础半径, 雷达 max_scan_distance)`，
  隐身因子单次乘算、无二次衰减。

### D. 其它
- `2526d60c` 尾焰客户端门控修复（IR 音/尾焰失联根因：客户端 rvpData 恒 null 的恒假门控）。
- `4326a35b` LauncherDeploy 状态机双端共享 HashMap CME 崩溃修复（按端拆表）。
- `3d74f94b` 二级脉冲支持 GPS 制导（9M723 一级助推 + 二级接力，双阈值 100t/速度 10）。
- `0abdf6b4` 猫吸引载具彩蛋（`RVP_CatVehicleAttractionService`，按用户要求**无文档**，源码即说明）。
- IR 锁定音大离轴行为定版：HMS 语义（出云台圈 1 秒宽限即脱锁），离轴圈绘制基准已与判定统一（`37c91309`）。

---

## 二、当前数值定版（载具包 JSON，已同步 4 副本）

| 载具 | rvp_radar_rcs_factor [F/S/R] | 弹舱 open_radar_rcs_multiplier |
|---|---|---|
| J-20A | 0.08 / 0.35 / 0.18 | pl10_bay = 2（隐身化侧弹舱）、pl15_bay = 6（主弹舱） |
| F-22A | 0.1 / 0.35 / 0.14 | arm_bay = 6、irm_bay = 6 |
| Su-57 | 0.12 / 0.35 / 0.21 | kh38_bay = 6、kh58_bay = 6 |

- ARH 相位：原定开机 256 格起箔条判定 + MSL 告警；真开机 = 256 × combined；MSL 告警闸门已前移。
- 9M723：sin³ 尾迹 scale 1.0/step 2.0、launch_boost 1.6、二级脉冲 GPS 弹道（一级 100t 弱推 + 二级接力）。
- 数值沿革：三机因子 初版 0.05~0.07 档（过强）→ 削弱 0.12~0.18 档（过狠）→ 定版中间档（本表）；
  弹舱增幅初版 4（配合 0.05 档过强）→ 3（配合中间档因子）→ **6（当前，配合中间档因子）**。

---

## 三、关键文件/类速查

| 主题 | 位置 |
|---|---|
| 分角度 RCS 缓存/计算 | `org.ywzj.rvp.radar.RVP_AspectRcs`（CURVE_POWER=1.5，封顶 1 在 combinedFactor 尾部） |
| 客户端探测表后过滤 + 锁定目标烧穿宽限 | `org.ywzj.rvp.radar.RVP_ClientRadarTickHandler.applyAspectRcsFilter`（LOCKED_TRACK_GRACE_TICKS=100） |
| ARH 相位/告警闸门 | `RVP_MissileEntity.tickActiveSeekerTargetManagement` / `tickRwrMissileLaunchWarn` |
| ARH 获取距离 | `RVP_RuntimeSeekerSupport.scanRadarTarget`（effectiveRange = 扫描半径 × combined） |
| 组网交战侧表 | `org.ywzj.rvp.entity.gunner.ai.RVP_GunnerEngagementNet`（HARD_LOCK_TICKS=60；读取点 findCiwsTarget + findBestTarget 导弹层） |
| gunner 锁/开火门诊断 | `org.ywzj.rvp.entity.gunner.ai.RVP_GunnerLockDebug`（`/rvpdebug gunnerlock`，logs/rvp_gunner_lock_debug.log） |
| 本地锁俯仰门修复 | `org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerRadarActions`（2026-09-16：删 aimRot.x 俯仰判定，详见 `docs/RVP_gunner/RVP_gunner本地锁俯仰门修复_20260916.md`） |
| gunner 索敌隐身门 | `GunnerTargeting.passesAspectPerception` + `findCiwsTarget` 两池 |
| 尾迹/烟浪粒子 | `org.ywzj.rvp.client.particle.RVP_RocketFlameParticle`（CURVE 常量在 trailQuadSize/applyTrailCurve） |
| 尾迹字段解析 | `RVP_EffectsData`（missile_native_trail_* 全系） |
| IR 锁定音 | `RVP_ClientSeekerTone`（挂载具 id；IR_TRACK_ALARM 回退） |
| 中继落锁隐身门 | `GunnerExternalRadarController.findRelayScanTarget`（effectiveRange） |

## 四、待实机验证清单

- [ ] gunner 本地锁俯仰门修复（2026-09-16 追加）：Su-57 开弹舱贴脸掠顶 Buk → RADAR_LOCK + 5 秒后导弹；
      远距接近 SENSE REJECT 仍在（RCS 无回归）；cssa5/ps1sm 对空回归；诊断 `/rvpdebug gunnerlock on`；
      详见 `docs/RVP_gunner/RVP_gunner本地锁俯仰门修复_20260916.md`；
- [ ] "只锁定不攻击"第二轮修复（同日 §六）：创造保护最终矩阵（方案A）——创造步行永不挨打；
      创造+困难驾驶 f14a_iriaf 被锁定攻击（5 秒纪律后导弹）；创造+非困难驾驶受保护；
      生存步行/驾驶都被打；低空（<25 格 AGL）可被锁打；若仍不攻击看 SENSE REJECT reason /
      AI NO_TARGET 的 profile 与 difficulty；
- [ ] 乘员随载具隐身（同日 §七）：生存模式开隐身战机，AI gunner 探测/锁定距离收缩到
      索敌半径×方位因子（有中继 3500×0.12≈420 格内），拉远即脱；AA 车对 AI 隐身机行为对称；
      步行玩家/导弹拦截不受影响；
- [ ] 搜索中继/火控中继分离（同日 §八）：带 96L6 的 Buk 对隐身目标远距只有 "S400" 搜索告警
      + 炮口跟转（无锁定告警/无导弹）；进入烧穿距离（1500×因子，正面≈180 格）→ "BUK" 锁定
      告警 + 5 秒后导弹；IRIST TADS 中继保持远距锁定开火；非隐身目标行为不变；
- [ ] 弹药分角度雷达信号（2026-09-17 §九）：AKF98A/风暴阴影（j20a 同款 0.08/0.35/0.18）
      迎头突防时雷达/中继快照/拦截距离明显短于侧掠；9M723/kh38 等改 [1,1,1] 后按雷达标称
      距离探测（旧 5/6 倍增透取消，属定版平衡变化）；机炮弹与红外弹锁定不变；
- [ ] 三机隐身强度手感（当前中间档：正面接近 ARH 截获距离 = 原定 × 0.08~0.18；gunner 感知同比例）；
- [ ] 弹舱 6 倍增幅：开主弹舱投 PL-15 立即"变亮"（封顶 1）；只开 pl10_bay（×2）几乎不暴露；
- [ ] ARH 相位：原定开机距离即响 MSL 告警 + 箔条可断中继；真开机距离随隐身缩短；
- [ ] 组网拦截：三车分弹幕、60t 硬禁、排斥窗滑动、烧穿宽限 5 秒；
- [ ] IR 锁定音第三人称持续可闻；大离轴圈内心持续锁定（离轴圈基准修复）；
- [ ] 尾迹全套观感（柱状尾迹/趋白压扁烟浪/发射段加粗 launch_boost 1.6）；
- [ ] 猫彩蛋（RVP_CatVehicleAttractionService，无文档按用户要求保密）；
- [ ] gunner AI/雷达、本体弹、AIR 弹、告警、步行玩家行为回归不变。

## 五、已知边界与坑

1. 分角度 RCS 仅客户端探测表 + ARH 获取距离 + gunner 索敌三条链生效；**gunner 服务端探测表、
   RADAR_SEARCH 告警、本体 MissileEntity 不适用**（文档边界）；
2. gunner AI 感知门（96 × 因子）远小于雷达中继距离——中继锁了 AI 也未必开火（"锁定仅是告警"为
   外置中继路径的既有设计，非 bug）；
3. phase 雷达烧穿宽限到期断锁后，本体自动重捕（20t 冷却）可能重新咬住 → "时隐时现"循环（可接受，已记录）；
4. `off_axis_stacks_with_station_rotation=false`（PL-10 等缺省）时离轴圈锚定机头轴——圈随字段走、与判定同源；
5. 猫彩蛋与"增加小彩蛋"提交信息为保密约定，勿在文档/提交中展开；
6. github 镜像偶发网络失败（连接重置/超时），重试 `git push github master` 即可。

## 六、未决事项

- 无阻塞性未决项。可选后续：分角度 RCS 曲线若需逐机定制可升级为角度表；gunner AI 感知如需
  5 秒烧穿宽限（对齐玩家雷达）可加计时器；多喷口尾迹（HBM 大弹 3~4 条平行尾迹）未做。

## 七、新窗口开场提示词（复制即用）

> 项目：D:\ywzj\ywzj\ywzj_rvp（RVP，Limitless Vehicle 的 Submod；本体 D:\ywzj\ywzj\ywzj_vehicle 只读）。
> 规范：agents.md 与 docs/调试与修复规范.md。
> 交接：先读 docs/RVP_gunner/RVP交接_隐身与gunner适配与尾迹音效_20260916.md，恢复上一窗口全部背景。
> 状态速览：分角度 RCS 隐身（rvp_radar_rcs_factor + 弹舱 open_radar_rcs_multiplier，封顶 1）、
> ARH 四段相位（箔条判定段/告警前移/隐身缩减开机距离）、组网智能拦截（硬禁 60t + 排斥窗滑动）、
> gunner 索敌隐身适配、尾迹 HBM 风格（sin³ 缓动）、IR 锁定音挂载具修复、LauncherDeploy CME 修复
> 均已完成并推送（HEAD=289ffa94，gitee/github 同步）；载具包 JSON（含三机 RCS 数值与 6 倍弹舱增幅）
> 在本地与 4 份 run 副本，不进 git。待办：实机验证清单见交接文档 §四。
