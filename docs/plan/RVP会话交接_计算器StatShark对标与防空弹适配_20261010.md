# RVP 会话交接：计算器 StatShark 对标+本体 0.6.1 接入+防空弹适配批（2026-10-10）

> 交接自：导弹计算器对标+本体更新+防空弹调参 会话。
> 本文件为唯一交接入口，配合 `agents.md`（skip-worktree 本地文件）与 `docs/调试与修复规范.md` 使用。

---

## 📋 新会话开场提示词（复制粘贴用）

```text
项目：D:\ywzj\ywzj\ywzj_rvp（RVP，Limitless Vehicle 的 Submod；本体 D:\ywzj\ywzj\ywzj_vehicle 只读，构建需 JAVA_HOME=JDK21）。
规范：agents.md 与 docs/调试与修复规范.md。
交接：先读 docs/plan/RVP会话交接_计算器StatShark对标与防空弹适配_20261010.md（唯一交接入口）。
必读三条：
①计算器在 blockbench_plugins/RVP鲨鱼网/（单文件 HTML），改完用 Edge 无头自检：
  msedge --headless=new --dump-dom 'file:///...html?selftest=1'，grep SELFTEST 看渲染像素统计；
②载具包 limitless_vehicle/ 与权威包 limitless_vehicle_authoritative/ 的 weapons 目录
  已于 2026-10-10 全量镜像一致；此后改弹参数仍只在同步包改，定版后再整目录镜像；
③并行会话可能活跃改 weapons 目录，改前先 git status 重探；
  冒烟=开发环境 ./gradlew runServer，判据读 run/server/logs/latest.log 的 Done 行
  （构建后数秒即出，勿轮询 gradle stdout 重定向）；
  实机问题排查才读用户副本客户端日志（E:\client_ywzj - 副本\...\logs\latest.log，
  jar 已是 10-10 配对版）——两套日志别混。
```

---

## 🔄 会话状态（截至交接）

- **HEAD = `f284f0f9`**；gitee 已推平（origin/master == HEAD 实证）；**github 落后多笔**（最后确认成功位 ~`9f41b614`~`c776c512` 一带，网络间歇——新会话推送前先 `git ls-remote github refs/heads/master` 查实位再补推，勿 force）。
- **构建**：BUILD SUCCESSFUL，测试 **795/0/0**（788 基线 + 分层极速 5 项 + 平滑插值 2 项）；最新 jar `build/libs/ywzj_rvp-1.20.1-0.6.0-all.jar`（含 ztz99b 诊断日志版）。
- **本体**：0.6.1（`73d7e86`：LAV-150+履带悬挂重构），RVP libs 已换 `ywzj_vehicle-1.20.1-0.6.1-all.jar`，冒烟 Done(2.142s/2.195s/2.233s 三轮确认)。
- **测试服 jar 未换**（`D:\ywzj\Forge-test-server` 仍是旧版）；用户**副本客户端已自行拷入 10-10 05:49 RVP jar + 0.6.1 本体**（配对组合 ✓，其 22:26 场次日志实测过概率飞头）。
- 权威包 weapons 已与同步包**全量镜像一致**（2026-10-10 用户授权，唯一差异=权威包遗留 `_tmp_aero_diff.txt` 草稿）；本体默认包新增 ztz99a 悬挂细化与 LAV-150，与本包无冲突。

## ✅ 本会话已完成（按批次，新→旧）

1. **`f284f0f9` 诊断合规化**：ztz99b 排查日志改走 `/rvpdebug` 框架——`RVP_DebugFlags.WRECK`（ALL 列表同步）+ `RVP_WreckDiag` 双通道（整车死亡部件三态表 detachable/destroyed/detached + 残件生成事件），`/rvpdebug flags wreck on` 开启，默认关零开销。**教训：RVP 加调试日志必须走 DebugFlags+rvpdebug 框架，不写常驻无条件日志类**（曾被用户点名批评）。
2. **`b715275c`→`7a41054d` 诊断初版**（被批后重做，过程稿）。半成品教训：VehiclePart 无 public getPartUnitId/getFlightTickCount，用 getPartUnit().getId()/tickCount。
3. **`9f41b614` 半自动修正恢复+调参**（纠正 `1f9c3e31` 误删）：95Ya6M/TKB-1055 re-enabled semi_correction，**临界阻尼零摆动**（0.15/0.775 与 0.2/0.895，ζ=1.0），wobble 4/1→0。**语义教训：SACLOS 的 semi_correction 是可用性本体（LBR 式乘波+弹簧修正），"压晃荡"=调参（wobble→0、ζ→1），删系统=阉割**。
4. **`7e07d7eb`+`a16e4632` 霹雳-15 高抛**：pl_15/j20a_pl15 加 loft 三件套 height 200/end 600/blend 600（顶点比目标高 200m，典型 2~2.4km 射击顶点≈中点，过中点俯冲）；PL-15 自带 ARH 开机 384m 与退坡窗零冲突。
5. **`983a7bee` 防空适配**：95Ya6M/MIM-146 增阻 ×2（Cd 0.041066/0.014654）+推力按新阻力重解维持 1s 达极速；TKB-1055 增推 ×1.3（1.88227）+减阻 ×0.7（0.0029169）+**关闭雷达近炸=仅 require_radar_lock false，近炸本体保留**（第一批误把近炸全关，用户怒斥后 `551f0e11` 恢复 radius 6.0/height 20/tick 20——**语义教训："关雷达近炸"=只解除雷达锁定前置**）。
6. **`551f0e11` 三弹机动性**：95Ya6M/MIM-146/TKB-1055 加 rvp_maxg 32/30/22G + rvp_turn_rate_limit 2.0/2.0/2.5°/tick（压 SACLOS/LBR 瞄准线 S 形过冲；小偏差小修正大偏差全过载由攻击角载荷 λ 自动实现）。
7. **`c776c512` 推力批**：79 枚导弹推力 ×1.1（SP 推力不随动）。
8. **`4d9f2302`+`c7e0b2c8` KD-88 系动力对齐鹰击-83K**：thrust 55.11/burn 300/mass 670/fuel 170/ignition 20t/二脉冲 0.688876×1800t 触发 10，保留自身极速 2.5 与制导引信。
9. **`7842917c` 分层极速节点值五档**（配合 `6e5030fa` 平滑化）：节点 (−64,0.8)→(128,0.9)→(256,1.05)→(512,1.15)→(768,1.3 平台)，29 弹 JSON 五档区间编码。
10. **`6e5030fa` 分层极速平滑化**：resolveAltitudeMaxSpeedFactor 改**节点线性插值**（每档值=该档上边界节点值，全曲线 C0 连续），29 弹 JSON 同步 1.15/1.3；计算器 maxSpeedFactorAt 同款。
11. **`727a6ebe` 分层极速初批**：29 对空弹四档（后经平滑化+节点值两轮迭代成上款）。
12. **`eccb85d4` 引导起始 14 弹三档**（1t×8/5t×2/10t×4）。
13. **`587632b4` 二脉冲触发**：赫尔墨斯×2 4→10、鹰击20 10→20（燃尽即点）。
14. **`4c67ae30` 本体 0.6.1 接入**（见下节）。
15. **计算器 StatShark 对标大批**（`8c453b22`→`c908a022` 五提交）：场景预设/目标 G 转弯+规避触发距离/曲线视图/多弹对比/命中判定进图例/时间轴续播/示例弹家族 3 枚/示例弹概率飞头诊断，修三潜伏 bug（Infinity 序列化、DEMO 制导未启用、速度剖面量纲）；详见 `8c453b22` 提交信息与计算器 README。
16. **`c516d9a3` 补提交**：171b2fe6 断点提交事故修复（分层极速特性代码曾漏 add 仅存工作区）。

## 🔧 本体 0.6.1 接入要点（新会话构建必读）

- **构建坑**：本体 0.6.1 依赖 ApricityUI 1.2.7+rhino 动态版本区间 → metadata 走 maven.neoforged.net（本机长时间超时）。**解法**：Modrinth CDN 下载 jar（先下 1.2.6 比对 SHA1 同源核验）+ `~/.gradle/init.d/local-apricity.gradle` 注入文件仓库（指向 ywzj_vehicle/mcmodsrepo）→ `--offline` 构建成功。**init 脚本留存可复用**。
- RVP libs 已换 `ywzj_vehicle-1.20.1-0.6.1-all.jar`（旧包归档 `libs/bak_ywzj_vehicle/`）。
- 本体 0.6.1 行为变化须知：**残件满血出生**（原继承当前血量）；**悬挂/履带件 75% 概率不飞+飞速 ×0.25**（VehiclePartSpawner 新随机）；PartUnit 新增 detach 状态 API；引擎动力衰减加大。TrackUnitData 改继承 PartUnitData（tf 语义在 rvp_maxg 存在时失效，见下）。
- **rvp_maxg 优先级语义**（源码实锤）：配置 rvp_maxg 后 designGs 直接用它、机头响应固定 0.5，**turning_factor 静默失效**（fishking 文档"显式 rvp_maxg 优先"）。两者不报错共存但 tf 成死字段——调机动性只动 rvp_maxg。

## 🐛 进行中：ztz99b 概率飞头调查（最高优先）

- **现象**（配对 jar 下复现）：击毁后炮塔残件概率不飞/直接飞/殉燃末才飞。
- **已排除**：本体 detach 链未变（炮塔类逐行同旧版）；ztz99b 数据侧无改动（turret 无显式 detachable 键=Pojo 默认 true ✓）；本体 75% 概率跳过只作用悬挂类部件而 ztz99b 全车 generic/weapon 型零悬挂件；击毁时刻客户端日志零异常。
- **已部署取证工具**：`RVP_WreckDiag`（`/rvpdebug flags wreck on` 开启）——整车死亡部件三态表+残件生成事件，latest.log 直接可读。
- **下一步**：用户打一轮（覆盖不飞/直接飞/殉燃末飞三种），读 `E:\client_ywzj - 副本\...\logs\latest.log` 的 `[RVP-DBG][Wreck]`/`[RVP-DBG][WreckSpawn]` 行对照观感定位；若服务端三态表显示 detachable=true+detached=false（应飞未飞）而客户端无异常 → 疑点转向本体 0.6.1 的 VehiclePart 满血出生/渲染层，考虑反馈本体作者。
- **注意**：dragonrise_reforge 包有 ztz99bh 变体（配方报错在案）——确认用户测试的是 rvp:ztz99b（RVP 包）而非第三方同型车。

## 📌 遗留待办

1. **测试服 jar 未换**：`D:\ywzj\Forge-test-server` 仍是旧版（本体 0.6.1+RVP 最新都未部署）；用户副本客户端已是 10-10 配对组合 ✓。
2. **github 补推**：gitee=HEAD，github 落后多笔（先 ls-remote 查实位）。
3. **实机验证清单**（攒了一波未验证）：分层极速平滑五档/碰炸保险 0/引导起始三档/二脉冲触发/KD-88 动力对齐/95Ya6+MIM-146 特调/半自动修正临界阻尼/霹雳-15 高抛 200/空空冷发射 10 站。
4. **本体 ztl11 用户未提交改动**：本体仓库 `ztl11.structure.json` 用户版已备份（仓库根 `ztl11.structure.json.userbak_20261010`）+ stash 双保险，ff 拉取后工作区为用户版（远端仅 +11 行，用户定稿自行合）。
5. 旧待办不变：9M723 二级过猛/AASM Cd 差异/HJ-73E 代差/j16_yj80 推力 24 倍差/ATGM 0.05~0.2s 起步（用户未表态）。

## 📚 本会话新增教训集（工作方式）

1. **断点提交检查**：提交后必须 `git show <hash> --stat` 核对提交信息声称的文件真的在提交里（171b2fe6 整特性漏 add 复发教训）。
2. **顶层 SyntaxError 静默杀脚本**：HTML 顶层 JS 语法错=整脚本无声死亡（UI 照常显示），页面内 `new Function(src)` 捕获定位；本轮 tooltip 多余右括号即此。
3. **冒烟判据=读 run/server/logs/latest.log**（数秒即 Done），勿轮询 gradle stdout 重定向（会漏 Done 行）；**禁用保活管道**（启动中段 stdin EOF 竞态干净退出看似成功）。
4. **debug 日志走 `/rvpdebug` 框架**：RVP_DebugFlags 注册+ALL 列表+isEnabled() 门控+`[RVP-DBG][区名]` 格式；**日志必须记判定上下文（逐部件状态表），只记结果事件等于没记**。
5. **SACLOS semi_correction 语义**：enabled=false=原始 SACLOS 全程手操（极难用）；压晃荡=调参（wobble→0、阻尼比 ζ→1 临界），保留乘波辅助。
6. **rvp_maxg 优先级**：配置后 tf 静默失效（响应固定 0.5、设计 G 用 maxg 值）；无报错共存但 tf 成死字段。
7. **碰炸保险字段**：`entity_collision_safe_tick`（JSON 可覆盖；代码默认导弹 3t/炸弹 20t/Gunner 垂发 10t 起）；与 delay_tick（定时自爆）两回事。
8. **"关闭雷达近炸"语义**：只解除 require_radar_lock 前置，近炸本体（radius/height/tick）保留。
9. **JSON 锚点正则**：武器 JSON 有整数 max_speed（5/6），锚点 `[0-9.]+` 不能要求带小数点；插入点自带行缩进，拼块勿再带（曾出 8 空格双缩进）；CRLF/LF 混合行尾用正则捕获组原样保留。
10. **JSON 往返**：`JSON.parse(JSON.stringify(x))` 会把 Infinity 序列化成 null——含 Infinity 的对象必须 structuredClone。
11. **并发同任务合流**：并行会话做同一任务时先 SKIP 已有（逐字节比对）再补差集，值一致无冲突即正常合流。

---

## 调试工具速查

- `/rvpdebug flags list|<name> on|off`——统一调试开关（含新增 `wreck`）；
- 计算器自检：Edge 无头 `?selftest=1` + grep SELFTEST；
- 构建：`JAVA_HOME='/c/Program Files/Java/jdk-21.0.11' ./gradlew build --offline`；
- 冒烟判据：读 `run/server/logs/latest.log` 的 `Done (Xs)!`（数秒即出，勿轮询重定向）；
- ztz99b 取证：读副本客户端 `versions/Optimized fps/logs/latest.log` 的 `[RVP-DBG][Wreck]` 段。
