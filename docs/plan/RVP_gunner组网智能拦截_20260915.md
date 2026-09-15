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
