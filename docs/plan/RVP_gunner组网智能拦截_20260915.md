# RVP Gunner 组网智能拦截方案（2026-09-15）

> 状态：已实现，待实机验收。
> 需求：① gunner 攻击步行玩家按游戏模式区分（生存/冒险攻击、创造/旁观永不攻击）；
> ② 同 faction 多个 gunner 组网智能拦截——某 gunner 射击目标后，窗口内其它同 faction
> gunner 优先选其它目标（降权非禁选），弹幕自动分配到不同来袭导弹。

## 一、现状调查结论

- **无跨 gunner 协调**：`findBestTarget` 可拦截导弹层直接取全局最近，多台同 faction
  防空车面对一波来袭会全部锁同一枚最近的导弹，其余来袭无人管。
- **已有的弱机制**：CIWS 层单 gunner 私有 100t 目标冷却（`setCiwsTargetCooldown`）；
  对空导弹"同一目标 5 秒只打一发"（`lastAirEngagedTargetId`，只影响"不打"、不影响选择）。
- **档案缺口**：`enemy.json` 的 `target_types` 没有 `"player"` 类型（只有 `vehicle:player`
  = 玩家驾驶的载具），enemy 档案 gunner 不打步行玩家。
- **创造保护旧语义**：`isProtectedCreativePlayer` = 旁观者保护 + "创造且非困难难度"保护，
  困难难度下创造玩家会被打。

## 二、实现

### 1. 步行玩家攻击规则
- `GunnerTargeting.isProtectedCreativePlayer`：创造模式**无条件保护**（移除难度例外），
  旁观者不变——生存/冒险攻击、创造/旁观永不攻击；
- `enemy.json` target_types 加入 `"player"`（`["vehicle:player","player","neutral","vehicle:friendly_gunner"]`）。

### 2. 组网交战网络 `RVP_GunnerEngagementNet`（新类）
- 静态 side-table（仿 `RVP_ChaffJamState`）：键 = (维度, faction, 目标 UUID)，
  值 = 最后交战 gameTime；`markEngaged` / `isRecentlyEngaged` / `onServerTick` 懒清理
  （滞留 >1200t 删除，挂在 `RVP_CountermeasureEventHandler` 服务端 tick）；
- **写入**：`GunnerBrain.tickCombat` 发射成功后 `markEngaged`（记录所有被射击目标）；
- **读取**：`findBestTarget` 可拦截导弹层两池逻辑——
  fresh 池（窗口内未被任何同 faction gunner 交战）非空则只在其中选最优；
  **fresh 池空（全部已交战）→ 忽略降权照常选择**（降权非禁选，仍有弹的 gunner 继续打）；
- **配置**：`GunnerProfile.engagement_net_cooldown_tick`（默认 100，钳制 [0,1200]），
  各档案 JSON 可覆盖，`/reload` 生效；按 faction 分键——FRIENDLY/TEAM/ENEMY 各自内部
  组网，跨 faction 互不影响；按维度分键——跨维度无干扰。

## 三、语义推演（用户案例：α β γ 同 faction，窗口 100t，来袭 5 弹 ABCDE）

| 轮次 | 状态 | 行为 |
|---|---|---|
| 第一轮 | ABCDE 均未交战 | α 选 A、β 选 B、γ 选 C（最近最优；每枚发射即 markEngaged） |
| 第二轮（窗口内） | ABC 已交战，D/E fresh | 三车只能从 D/E 中选 |
| 末轮（全交战） | fresh 池空 | 忽略降权按评分继续打（仍有弹的 gunner 不闲着） |
| 窗口过后 | 恢复可选 | 100t 后目标可被再次选择 |

窗口只影响**选择优先级**，不改变"同一空中目标 5 秒只打一发"的既有不打纪律。

## 三B、窗口滑动与性能/时序定版（2026-09-15 二次迭代）

- **窗口随距离滑动**（替代固定 100t）：最低 = 档案 `engagement_net_cooldown_tick`
  （默认 100），最高 = 2 × 最低（默认 200），以交战时射手载具与目标的距离线性滑动
  （0 → 最低，≥384 格 → 最高）。侧表改存"降权截止 tick"（ChaffJamState 同款），窗口
  在记账时烘进截止值，查询端只判过期。档案字段语义 = 最低窗口；0 = 关闭组网。
- **记账点扩为两处**：①开始跟踪新目标时（`tickTargeting`，覆盖"已选中但延迟开火"
  ——导弹冷却/对空持锁期间其它 gunner 也不重复选它；同目标周期性重扫描不刷新）；
  ②实际发射时（刷新截止 tick）。
- **TPS 影响评估（Q1）**：记账 = 发射/换目标事件级 O(1) 写；查询 = 每 gunner 扫描周期
  （默认 10t）O(候选数) 哈希查表；清理 = 每服务端 tick 对"≤200t 内交战目标数"量级
  （通常 <100 条）的小 Map removeIf——与既有 `RVP_ChaffJamState` 同款同量级，无可测量
  开销。gunner 链路真正的成本（collectTargetEntities O(已加载实体) 遍历）未被放大。
- **同时 tick 竞态（Q2）**：服务端实体串行 tick——α 的完整流水线（扫描→开火→记账）
  在同一 tick 内完成后才轮到 β，β 扫描时必见 A 已入网，故同 tick 三车撞同一目标
  不可能发生。唯一空隙是"已选中但延迟开火"（冷却/持锁），已由记账点①覆盖。

## 三C、实机回归：组网未生效根因修复（2026-09-15 三次迭代）

- **根因（写错了一半）**：炮车选来袭导弹的真实路径是 `tickTargeting` 每 tick 优先走的
  `findCiwsTarget`（CIWS 层，1000 格），命中即返回、根本到不了 `findBestTarget` 的导弹层
  ——而组网查询只接在后者，`findCiwsTarget` 只查各 gunner 私有冷却，α 打完 A 后 β/γ 照样
  返回 A → 观感"没生效"。**修复**：`findCiwsTarget` 加组网两池（fresh 选最近，空则回退
  全候选；查询无需档案参数——窗口已烘进侧表截止 tick，组网关闭时无记账自然恒 false）。
- **射击间隔 100t**：非阻碍（三车冷却互相独立，首发无冷却）；
- **导弹先后来袭**：单候选时 fresh 池必然为空 → 回退照打，是正确行为非 bug；
- **可拦截范围确认**：`isInterceptableRvpProjectile` = 导弹 ∪ 炸弹 ∪ 火箭弹（RVP 与本体
  实体均含），三类共享组网降权。

## 三D、凝结云飘走修复（2026-09-15 实机反馈）

WASH 垂直运动 = 初始 vy 0.05 + 每 tick 尺寸增量浮升（≈0.028/tick），一生累计上升
约 4~5 格，趋白期云已飘离地面。修正：初始 vy 0.05 → 0.02；垂直分量随寿命衰减
（riseScale = 1 − 0.8×ageRatio，后期基本停止上升）——白云贴地摊开不再升空。

## 三E、限位窗口定版（2026-09-15 三次迭代：硬禁 + 排斥两级）

用户需求：单弹 A 被 α 交战后，即使 A 是 β/γ 唯一候选也不该立刻倾泻拦截弹——加
**60t 限位窗口（硬禁）**：交战后前 60t 其它同 faction gunner **完全不可选** A（交战者
本人不受限）；60t 后落入排斥窗（软降权，无替代才打）；排斥窗（100~200t 随距离）过后
恢复可选。β/γ 之一开火后重新记账 → 新的 60t 硬禁对其它车生效 → 单目标上形成
~60-100t 间隔的错峰拦截流。

实现：网表条目改记三元组（排斥截止 / 硬禁截止 / 交战者 UUID）；`markEngaged`（发射，
硬禁+排斥）、`markTracked`（跟踪，仅刷新排斥不续硬禁）、`isHardLockedFor`（对查询者
判断，交战者本人 false）。排除点：`findCiwsTarget` 候选、`findBestTarget` 可拦截层、
findBestTarget 末位兜底层（防单弹从兜底漏选）。

## 四、行为变更（记入调试规范 §4）

1. 创造玩家改为**无条件**免攻击（旧：困难难度下创造可被打）；
2. enemy 档案 gunner 开始攻击步行玩家（生存/冒险）；
3. 同 faction gunner 的可拦截导弹选择默认 100t 组网降权（`engagement_net_cooldown_tick: 0` 可关闭）。

## 五、验证

- 构建：BUILD SUCCESSFUL；冒烟：`Done (Xs)!` 判据（`server_smoke_gunner_net_20260915.log`）；
- 实机清单：
  - [ ] 两台以上同 faction 防空车面对多枚来袭弹，不再全体锁同一枚（弹幕分配到不同弹）；
  - [ ] 全部来袭弹都被打过一轮后，有弹的 gunner 继续拦截；
  - [ ] 窗口（5 秒）过后同一目标可被再次选择；
  - [ ] enemy 档案 gunner 攻击生存步行玩家；创造玩家步行/乘车接近均不被攻击；
  - [ ] 困难难度下创造玩家不再被攻击；
  - [ ] 单 gunner 场景、集火行为（载具/玩家层）不变。
