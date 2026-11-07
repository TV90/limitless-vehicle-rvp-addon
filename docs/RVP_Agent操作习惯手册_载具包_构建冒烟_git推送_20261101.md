# RVP Agent 操作习惯手册：载具包 / 构建冒烟 / git 推送

> 生成：2026-11-01。来源：ZCode 会话工作区记忆（rvp-project-handoff-entry / rvp-concurrent-session-facts / rvp-user-investigation-style）浓缩，供后续 AI agent 学习。
>
> **本文不是会话交接文档。**每个会话的入口流程不变：先读 `docs/plan/` 下最新一份 `RVP会话交接_<主题>_<日期>.md`（唯一交接入口），配合仓库根 `agents.md`（skip-worktree 本地文件，git diff 看不到，必须直接读文件）与 `docs/调试与修复规范.md`（含功能丢失/降级记录表、噪音基线 §5.1）。
>
> 版本号/HEAD/测试基线演进很快，本文讲**惯例**；现状一律以 git log 与最新交接文档为准。

## 0. 项目格局（30 秒版）

- `D:\ywzj\ywzj\ywzj_rvp` = RVP（Limitless Vehicle 的 Submod），git 仓库根；`D:\ywzj\ywzj\ywzj_vehicle` = 本体，**只读**（只 pull 不改）。
- 本体远端：github `YWZJ-Studio/ywzj_vehicle`，分支 **1.20.1-dev（不是 master）**。
- RVP 远端：gitee origin（主）+ github 镜像（常滞后、间歇不通）。
- 版本格式 `<RVP版本>_beta_<本体版本>`（如 0.1.0b_beta_0.6.2），bump 位置 = `gradle.properties` 的 mod_version。版本线实录：0.0.3b_beta → 0.1.0_alpha → 0.1.0_beta → 0.1.0b。
- 本仓库常态 = **多窗口并发**：其它 agent 会话可能正在同时改同一工作区，任务提示词里"可能有其它 agent 并发修改"是真实风险。

## 1. 载具包（/rvp 数据包）

### 1.1 jar 与数据包双同步（换环境铁律）

- RVP jar **只含代码，不内嵌 /rvp 数据包**（用 installer 装会出现假安装 WARN）——换环境/换 jar 必须 **jar + 数据包双同步**。
- weapons/vehicles JSON 全在游戏目录 `limitless_vehicle/rvp/` 下；JSON-only 改动无需重 build、无需 bump 版本，但汇报里必须提醒用户**同步载具包到游戏目录**（jar 由用户自拷到客户端 mods）。
- "测试服"是僵尸待办（2026-08-07 起废弃）——验证 = runServer 冒烟 + 用户客户端实机，不要往任何 mods 目录 cp jar。

### 1.2 三副本职责与同步方向

| 副本 | 角色 | git |
|---|---|---|
| 仓库根 `limitless_vehicle/` | 日常工作副本（同步包） | `weapons/` 已跟踪（改动随 git 提交）；`vehicles/` 及其余资产被 .gitignore 拦截——**属正常，勿"修复"** |
| `limitless_vehicle_authoritative/` | 权威副本（定版快照，~794MB） | 不进 git |
| `run/**` 与游戏目录副本 | 沙盒/部署副本 | 不进 git，**不作诊断依据**（数据不全，曾因只同步了 vehicles 没同步 models 冒烟报 orElseThrow 噪音） |

- 改动永远落在 `limitless_vehicle/`；**定版后正向刷新权威包**（整目录镜像 + 逐文件零差异校验）。
- **严禁从权威包反向同步**——权威包可能停在旧版本（10-09 实证：差点抹掉 fishking 整批调参）。
- jar 产物分普通 jar 与 `-all.jar`（SBM 依赖经 jarJar 只嵌进 -all.jar，普通 jar 依赖本体提供）；部署给用户用哪种以最新交接文档为准，勿混淆。

### 1.3 vehicles / weapons JSON 编辑纪律

- **改武器组前先判三种合法形态**：①站点内 ids 弹种组（站点自有俯仰）；②单 id 组带 `part_unit_id` = 武器站代理（引用他站武器，合法保留）；③ids 组内再写 `part_unit_id`（把组挂到子站 → 触发"可改装"橙点 UI，删）。口诀：**弹种组永远放武器实际所在的站，主站只留代理**。
- **共享武器文件**（被多载具引用）动任何数值先陈述影响面（含各载具连带）等用户拍板（j16_yj80 改弹量被驳"J-16 又坏了"事故）。
- 装备型号/名称先 grep 权威包 `name_CN` 再落笔（RIM-161=标准-3、ESSM=RIM-162，混淆零容忍）。
- 批量 JSON 编辑首选 **dict 重排整文件**（`json.dumps(OrderedDict, indent=2)` + 回读断言）——混合 CRLF/LF 文件做字节级锚点插入反复失败；行内插入用末键（如 guidance_tick_range）正则锚定、勿 rfind 逗号（会落进 CRLF 行尾/数组）；块尾已有逗号勿再加（曾出 `},,`）。
- 领域惯例：loft 高抛三件套 = height/end/blend；二级动力"燃尽即点" = 触发速度设 999；`ammo_capacity: -1` = 站级取消覆盖回退武器级（本体既有机制，存疑先 grep 验证）。
- 新参数字段要登记进 `RVP包新增参数字段说明.md`（文档登记惯例）。

### 1.4 删除铁律（agents.md，愤怒级，适用全仓库）

- **一切真删除全禁**（rm/del/rmtree/find -delete，连自己刚建的临时文件/日志也禁）。
- 唯一允许 = 逻辑删除：移动到 `_recycle_bin/<YYYYMMDD_HHMMSS>/<原相对路径>`（保留目录结构，同名加 `__1`），并在 `_recycle_bin/MOVE_LOG.md` 追加一行（时间|原路径|新路径|原因）；批量 ≤10 个/批 + 回读校验。
- 动用户既有资产（贴图/模型/structure.json）必须先列清单等确认；回收站不进 git、不进载具包、不同步游戏目录；真正释放空间由用户手动做。

## 2. 构建与冒烟

### 2.1 构建

- `JAVA_HOME` 必须显式 `C:/Program Files/Java/jdk-21.0.11`（shell 默认 jre1.8 会炸 foojay）；本体构建同样 JDK21。
- 动手前先探 `.gradle/**/*.lck`（并发会话占锁迹象）；RVP 构建先试 `--offline`（正常可过）。
- 本体构建坑：0.6.1+ 的 ApricityUI 动态版本区间走 maven.neoforged.net，本机长超时 → Modrinth CDN 下 jar（与缓存既有 jar 比对 SHA1 做同源核验）+ `~/.gradle/init.d/local-apricity.gradle` 注入文件仓库后 `--offline` 构建；init 脚本留在机器上可复用。
- 判据：compileJava SUCCESSFUL + 测试 XML failures/errors = 0（基线随特性增长：10-09 时点 793、10-12 时点 822——以最新交接文档/上次构建实测为准）。
- **构建与提交之间必须 `&&` 阻断**：构建不过不提交。
- 每轮代码改动跑完整 `gradlew build` 出 jar（compileJava+test 结果对用户不可见，不是交付判据）；build/libs 出现旧同名 jar 混淆时 bump mod_version 区分批次。
- 网络协议号改动两端必须同版（成对部署）；新增 S2C 包会被架构测试拦 network 包的 client import——用 `DistExecutor.unsafeRunWhenOn` + 全限定名内联（无 import 语句）模式（S2CEngineDamageState 先例）。
- 调试日志必须走 `RVP_DebugFlags` + `/rvpdebug` 命令框架（新 flag 同步加进 ALL 列表，只加常量命令遍历不到），禁止常驻无条件日志类；debug 日志要记录判定上下文（逐项状态表），只记结果事件等于没记。

### 2.2 冒烟（runServer）

用户两次纠偏后定稿的姿势：

1. 后台启动，stdin 保活：`(sleep 120) | ./gradlew runServer`（防启动中段 EOF 自杀式干净退出）。
2. **数秒后读 `run/server/logs/latest.log` 的 `Done (` 行作判据**——不要轮询 stdout 重定向（会漏/延迟 Done 行），不要保活管道长轮询。
3. grep MC 日志**必须 `-a`**（中英混编 latest.log 会被 grep 判二进制 → 匹配静默不显示，"无输出"≠"无匹配"）。
4. 收尾：Done 后按 PID 杀进程，用全路径 `/c/Windows/System32/taskkill.exe //F`（Git Bash 裸 taskkill/tasklist 不在 PATH）；杀完用全路径 tasklist 复验 java 进程 = 0；25565 端口释放 = 干净（剩余 java 是 Gradle 守护进程，命令行不含 ywzj，非残留）。
5. **杀进程过滤器只许精确**：`%ywzj_rvp%`。`%ywzj%` 会命中用户客户端（路径含 client_ywzj）、`forge` 会命中 PyCharm JBR 的 java——两次差点误杀用户游戏。server JVM 唯一可靠特征 = azul JDK17 路径（`jdks/azul_systems__...` + `-Dforge.` 开头）；taskkill 前逐条打印命中进程完整命令行确认身份。
6. 冒烟"莫名早退"（30~45s 干净退出无 Done）：先查叠跑的多余 java / 上一轮没停的保活任务互抢 run 目录锁，全部清场后单次重跑；也可能是用户自己关的（以用户实时贴的 Done 行为准，过早关服 latest.log 无 Done ≠ 失败）。
7. 噪音基线对 `docs/调试与修复规范.md` §5.1 只看新增；已知沙盒噪音：gunner schema v2 ERROR（沙盒预期降级）、run 沙盒数据不全的 orElseThrow。
8. **runServer 测不到任何 client mixin**。client mixin 验收 = 确认已注册（mixins.json + 日志 grep mixin 名）+ APPLY 零失败 + runClient 真启动；**编译成功 ≠ 注入 apply**——未注册组件永远不会失败，绿灯是假的（被其它 AI 点名批评过，浪费 1h+）。
9. Mixin 注解语义拿不准就读框架源码（`Injector.checkTargetModifiers`）：handler 的 static 修饰符必须与**目标方法**一致——私有实例方法 ⇒ 非静态实例 handler。别猜，猜错会写进交接文档毒害下一个 AI。

## 3. git 提交与推送

### 3.1 提交纪律（多窗口并行环境）

- **bug 修复必须当轮 commit+push**——曾因未提交被并行窗口的 git 操作覆盖丢失，用户实测才发现。
- **并发活跃期提交必须 `git add src/`（显式含 untracked）**：`git add -u` 漏新文件两次事故，第二次真炸远端（新类 untracked 但被已跟踪文件引用 → 其它开发者拉取后编译断，7c9446f1 补交）。推前清点：`git status --short | grep '^??' | grep src/` = 0 才推；补交后核验新文件的 import 全部已跟踪（防连环缺文件二次打脸）。
- **`git add -A 大目录` 会把其它会话的 untracked 草稿/内嵌仓库误入库**（Meshy 插件自带 .git 被建成 gitlink 推送）——**提交后必须 `git show <hash> --stat` 核对**：提交信息声称的文件真的在提交里（"断点提交"事故两犯：配套文件漏 add / 整特性漏 add 但提交信息写全，任何人检出即丢特性）。
- 已推送历史发现误加：用新提交反向剔除（git rm --cached + 追加剔除提交），勿 rebase 已推送历史。
- 接手其它 AI 的提交先全量构建验真（有的会话不构建就提交，且会误提交 .tmp/编辑中间副本；其 compile_err.txt 可能是中途状态产物，勿直接当现状）。
- 提交配套改动必须整组 add（消费端+数据类+枚举+Runner 分开漏提交 = HEAD 单独检出编译不过）。
- `git commit --amend` 在 soft reset 混合状态下行为难预期——出问题先 `git log` + `git show --stat HEAD` + `git ls-files <目录>` 三件套拍事实再动。

### 3.2 推送与远端

- gitee 被拒 → `git pull --rebase origin master` → gitee 正常推；rebase 重写历史后 github 用 `git push --force-with-lease=refs/heads/master:<旧hash>`（旧内容已被新提交包含时安全）。
- GitHub Recv reset / 443 不通是常态：重试 3~5 次、间隔 8~10s。
- 并发会话占 git index 使 pull --rebase 报 "could not detach HEAD" → 重试即可；另一形态：origin 新增 tracked 文件与本地 ignored 同名文件冲突——`git diff --name-only --diff-filter=A <base> origin/master -c core.quotepath=false` 比对内容后处置。
- `agents.md` 是 skip-worktree 屏蔽文件，rebase 会被它挡，处理流程：`cp agents.md 备份 → git update-index --no-skip-worktree agents.md → git checkout -- agents.md → rebase → cp 回 → git update-index --skip-worktree agents.md`。
- **git 拉取涉 JSON 先列清单经用户确认**；pull/fetch 类操作先出计划申请授权再动手。
- 并发会话活跃期 = **对工作区零写入铁律**：评审/构建发现的问题只汇报不动手（连"顺手修复"也不行），等用户明确说"它那边完事了"再接手。
- 并发等价方案撞车（两 agent 同时落地同一修复）：重读对方成品，功能等价则**采纳不覆盖**（避免 edit war），接手方负责版本号 bump/构建收尾并如实告知用户采纳了谁的方案。
- 发行：Gitee 发行附件单文件上限 100MB；发行页更新长期滞后（停在旧版本时代），版本核对以 git log/提交为准。
- 用户会实时贴日志关键行纠偏（如贴 `Done (2.1s)` 证明冒烟早已通过）——流程挂起前先读 latest.log 核对判据，勿靠加长等待自证。

## 4. 横向纪律（写文件/脚本类，服务于上面三主题）

- **行尾逐文件校验**：CRLF/LF 改前后逐文件比对，勿按"docs 全 CRLF"假设（并发会话可能随时改变行尾现状）。
- **bash heredoc 传大段代码会静默损坏**（断言"成功"全为假象，文件实际未变）——大段代码/文档一律 Write 临时 .py 脚本执行，写后复核关键标识符计数与 diff 行数；"CR 计数不变" = 写入失败铁证。
- Python 3.6 环境限制：bytes literal 不能含中文（改 `'中文' in s.decode('utf-8')`）；Windows 原生 python 不认 `/d/...` MSYS 路径（须 `D:/...`）；无 `subprocess.capture_output` 参数。
- Bash shell 的 CWD 可能在会话中途静默重置到父目录——路径类报错先 cd 回仓库根用绝对路径复验，勿下"目录不存在"结论。
- 对刚发现的"数据差异"，报告/处置前先重读现值并标注来源——并发会话可能秒级刷新同一批文件（审计读数被覆盖实证）。
- 批量脚本逐文件 try/继续不中断；崩后查 `git status --porcelain` 的 D/M 归属，向用户通报"并发删除/修改"而不是静默跳过。
- 并发会话可能在做同一任务且产物逐字节一致——批量脚本对"已有"先 SKIP、比对一致才继续，不一致才是真冲突需上报。

---

## 附：记忆原件位置

ZCode 工作区记忆（更完整的原始沉淀，含大量事故案例）在：
`C:\Users\MECHREVO\.zcode\cli\memories\projects\ywzj-3e6d9bbc79a6d11a\memory\`

- `rvp-project-handoff-entry.md` — 项目状态与会话入口（最大，含逐批次记录）
- `rvp-concurrent-session-facts.md` — 并发会话环境事实
- `rvp-user-investigation-style.md` — 用户排查协作风格（46 条，"怎么跟用户配合"的规矩）
- `rvp-user-resume-context.md` — 求职简历素材（与工程无关）

如换工具/换机，可直接拷贝整个目录。另注意用户协作的通用要点：实测线索永远优先于 AI 的根因推理；量化归因前先核实代码里的真实公式与单位；移植/复刻必须逐字读原源码；用户问"为什么/是多少"时先回答不要动手改；调参类短指令直接执行并给新旧值对照。
