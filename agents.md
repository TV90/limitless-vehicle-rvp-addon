- 本项目为`ywzj_vehicle`的Submod`limitless-vehicle-rvp-addon`,均在Gradle,Minecraft 1.20.1-Forge下开发
- docs目录下为项目文档，入口见 docs/README.md；docs/plan 下放计划文档
- 命名时武器名称要使用AntiRadiation、TVMissile这种正规写法，并且如GPS等缩写不要写成Gps
- 代码中必须包含清晰的中文注释，每个使用的字段也必须注释，在方法中调用其他本项目(`limitless-vehicle-rvp-addon`)以及本体(`ywzj_vehicle`)的方法时必须注释调用目的
- 验证编译时在项目根目录直接运行 `./gradlew build` 即可
- 服务端冒烟：`./gradlew runServer` 后台启动后**每 10 秒轮询一次日志**，出现 `Done (Xs)!` 即通过；
  结论以日志为准（用户可能手动关服）；grep 日志要加 `-a`（中文 UTF-8）；历史噪音基线见
  `docs/调试与修复规范.md` §5.1，只看新增错误

**`ywzj_vehicle` 本体源码** 在 `"填入你的本体源码绝对路径"` 下

**不要改** `ywzj_vehicle` 本体源码；新逻辑写在 `limitless-vehicle-rvp-addon`。

**禁止硬编码武器 ID**：不得在实体/渲染类里用 `weaponId.getPath().equals("某武器名")` 区分弹种；用 `fire_data.canister_count` 等 JSON 字段。弹体须用 RVP 类型化 `EntityRenderer`（不可直接注册本体 `BulletEntityRenderer`），绘制逻辑见 `VehicleProjectileRenderLogic`。

**禁止旧版 JSON 迁移**：`RVP_WeaponTypes` 等加载器只接受当前 schema；勿写 `legacy*`、`migrate*` 或旧键别名。改历史 JSON 用 `scripts/`，不要塞进 Java。

**Bedrock 模型**：只在载具包 `assets/rvp/models/bedrock/` 与 display JSON 的 `model` 配置；禁止在 Java 里维护模型 ID 白名单（已删除 `RVP_BedrockModels`）。

**Bedrock 模型**：只在载具包 `assets/rvp/models/bedrock/` 与 display JSON 的 `model` 配置；禁止在 Java 里维护模型 ID 白名单（已删除 `RVP_BedrockModels`）。

**贴图与资源类替换铁律（2026-09-22 事故后订立）**：凡是涉及**贴图、音效、模型等任何资源文件的写入/替换/覆盖**（无论目标在 `src/main/resources`、载具包还是 run 目录），动手前**必须先确认目标路径是否已有现有资源**——用 `find`/`ls` 检查全相关目录（含 `src/main/resources` 与载具包两处命名空间）确认目标不存在后，还须**向用户说明并获确认**才可创建或覆盖；已存在的资源**一律不得直接覆盖**，只能询问用户处理方式（保留 / 另名新写 / 用户自行替换）。搜索现有资源时禁止只查部分目录——搜索范围必须覆盖 mod 源码资源与载具包资源两处命名空间。资源文件是用户手工劳动成果，误覆盖可能无法恢复（不在 git、不进回收站）。

**结构模型修改铁律（2026-08-16 事故后订立）**：任何对载具**结构模型**（`data/rvp/models/bedrock/vehicle/*.structure.json`，含骨层级 / 骨枢轴 pivot / cube 几何 / 父级关系）的修改，**必须先向用户请示，获确认后才可动手；且动手前必须先备份原文件**（复制为 `*.structure.json.bak` 或 `*.structure.原始时间戳.json`），改动完成后再核对。禁止擅自重构骨层级、移动骨枢轴或批量改写 cube——结构模型是资产，牵一发动全身，误改会导致整车模型/发射点/受击盒全乱。宁可只做 JSON 配置层/Java 代码层方案，也不要直接动结构模型骨结构。

**`RVP_*Data` JavaDoc**：每个 `@SerializedName` 字段须有与 `RVP_FireData` 同级的说明（单位、默认、生效条件）；规范见仓库 `.cursor/skills/mcheli-rvp-port/data-class-javadoc.md`。

**Mixin 使用纪律（非必要禁止，默认拒绝）**：
- 默认禁止新增任何 Mixin 类或注入点；论证责任在提出方，未给出充分理由前一律拒绝。
- 新增 Mixin 前必须逐级排除替代方案（有任一可行即不得用 Mixin）：
  1. JSON 数据字段（`RVP_*Data` 数据模型可表达的行为，优先在数据层配置）
  2. 继承/接口/组合（`ywzj_vehicle` 的可覆写方法、接口实现、`DistExecutor` 分端）
  3. Forge 事件总线（`MinecraftForge`/`ModBus`，含渲染/刻/Tick/网络注册事件）
  4. 网络包 + 独立管理类（S2C/C2S 包 + `RVP_*` 独立系统）
  5. `@Accessor`/`@Invoker` 访问器（仅需读私有字段/调 protected 方法时，放 `org.ywzj.rvp.accessor`，不新建 mixin 类）
- 只有确需修改本体/原版字节码且上述全不可行时才允许 Mixin，且：
  - 改动面必须最小化：只允许最小 `@Inject`（HEAD/RETURN 转发），**禁止 `@Overwrite` 整方法、禁止整段 `@Redirect`**；确需改写核心逻辑时先向用户提出非 Mixin 替代。
  - Mixin 只做转发/最小拦截，业务逻辑一律放非 `mixin` 包的辅助类，禁止把共享状态放进 Mixin 类。
  - 新增前先检查现有 Mixin 是否已覆盖/可合并，避免重复注入或叠加改动同一方法。
- 新 Mixin（含新注入点、`@Accessor` 扩展）必须先向用户说明：目标类/方法、注入方式、为何无可替代，**经用户确认后才可编写**。
- 公共数组（`mixins`）的 Mixin 会让目标类在服务端变 dirty → Forge 重算字节码帧 → 目标类方法中若有 `@OnlyIn(CLIENT)` 类型引用（`LocalVehiclePlayer`、`Minecraft`、客户端粒子等）会触发服务端加载崩溃。
- Mixin 及任何共享/公共代码中**禁止直接引用 `@OnlyIn(CLIENT)` 类型**；必须经 `org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess` 桥接（服务端 NOOP、客户端真实现），新增桥接方法见 `RVP_IClientActions`。
- 公共数组 Mixin 的目标类必须是双端安全的；纯客户端行为放 `client` 数组。

**Git 提交策略（2026-11-06 订立）**：只提交**代码**（`src/` 下 Java 源码及必要的构建配置）；**载具包**（`limitless_vehicle/` 资产，仅
limitless_vehicle/rvp/assets/rvp/sounds.json
limitless_vehicle/rvp/assets/rvp/sounds
limitless_vehicle/rvp/assets/rvp/display/weapon
limitless_vehicle/rvp/data/rvp/vehicles
limitless_vehicle/rvp/data/rvp/gunner
limitless_vehicle/rvp/data/rvp/fire_support_profiles
limitless_vehicle/rvp/data/rvp/weapons
纳入git管理）其他的一律不纳入git管理。提交前核对 `git status` 暂存清单，禁止 `git add .` 误带载具包其他资产。

**载具包权威副本铁律（2026-09-29 订立，run 目录误诊事故后；2026-10-08 迁移修订；2026-10-12 再修订：权威包/同步包合并）**：载具包**唯一权威副本 = 项目根 `limitless_vehicle/`**（**开发/修改/新增载具与武器配置一律在此进行**；git 仅跟踪其 `data/rvp/weapons/` 白名单，其余资产不进 git；用户游戏目录由用户自行同步、保持最新）。原 `limitless_vehicle_authoritative/` **自 2026-10-12 起废止**——不再是权威副本、内容停更，其磁盘目录的处置（删除/归档）由用户决定，排查与开发一律不再参考它。`run/**`（run/server、run/client_1 等）下的副本是**冒烟测试沙盒**，全是陈年旧包——**排查、对比、诊断、修复一律只看项目根 `limitless_vehicle/`**，禁止把 run 目录当部署现状、禁止从它取载具数据下结论、也不需要"同步 run 目录"。"数据过期"类结论必须先核对权威副本本身。

**Git 拉取询问铁律（2026-10-12 订立）**：任何会引入远端提交的操作（`git pull`/`pull --rebase`/`merge`/`fetch` 后的 rebase/checkout 合并等），**若涉及 JSON 文件的改动**（`limitless_vehicle/**` 及其它任何 `.json` 路径），必须先向用户列出将拉取的提交清单与受影响的 JSON 文件，**经用户确认后**才能执行；纯 Java/文档等非 JSON 改动的拉取不受此限。原因：载具包 JSON 与资源类 JSON 常有用户本地手改与多窗口并行改动，自动合并/检出可能静默覆盖用户工作。

**删除操作铁律（2026-10-12 订立，rm 误用风险事故后）**：**任何情况下都禁止执行真正的删除命令**。Windows 环境下 shell 的转义规则（引号、`\\`、通配符展开、含空格或中文的路径）极易被用错，一旦展开成预期之外的路径，会造成**不可逆的批量数据丢失**。因此：

- **禁止的写法（无例外）**：`rm`、`rm -rf`、`rmdir`、`del`、`erase`、`rd /s /q`、`Remove-Item`、`Clear-Content`、`find … -delete`、`shutil.rmtree()`、`os.remove()`、`os.unlink()`、`unlink`、`truncate`，以及任何自写的"循环删除脚本"；**即使只删自己刚创建的临时文件、构建产物、日志也不例外**。
- **唯一允许的"删除"= 逻辑删除（移动到回收站）**：项目根目录（`ywzj_rvp/`）下的回收站目录 **`_recycle_bin/`** 已建好；任何需要"删掉"的东西，一律用**移动**代替删除。
- **移动规则**：目标 = `_recycle_bin/<YYYYMMDD_HHMMSS>/<原相对路径>`，**保留原目录结构**（例：`_recycle_bin/20261012_035500/limitless_vehicle/rvp/assets/rvp/textures/entity/xxx.png`）；同名冲突加 `__1`、`__2` 后缀；移动整个目录用一次 `mv 源 目标`，不要先建空目录再逐文件搬。
- **逐条记录**：每次移动都必须在 `_recycle_bin/MOVE_LOG.md` **追加**一行：`时间 | 原路径 | 移动后路径 | 原因/来源任务`。回收站**永不自动清理**，**由用户手动删除**。
- **动用户既有文件前必须先列清单 + 等确认**：只允许自主移动「本次任务中由自己创建的文件」与「用户点名要求删除的文件」；其余（尤其是贴图/模型/音效等美术资产、`*.structure.json`）必须先逐条列出完整路径、说明风险，**经用户明确确认后**才可移动。
- **回收站不进版本控制、不进载具包**：`_recycle_bin/` 已加入项目根 `.gitignore`；**禁止**把它放进 `limitless_vehicle*/`、`run/**`，**禁止**同步到用户游戏目录；包校验脚本（`scripts/check_pack_case.py` 等）必须忽略它。
- **批量移动**：一次不超过 10 个，每批做完回读校验；任一步失败立即停止并报告，不得"跳过继续"。
- 若确实要"真正释放空间"，**由用户自己在文件管理器或终端里做**；AI 只负责把文件搬进回收站并给出清单。

**新增武器清单（2026-10-14 订立）**：加一把机载武器 = **2~3 个文件** ——① `limitless_vehicle/rvp/data/rvp/weapons/<id>.json`（本体数据；子母弹**再加**一发子弹药 JSON，母弹 `submunition_data.releases[].payloads[].weapon_id` 指向它）；② `limitless_vehicle/rvp/assets/rvp/display/weapon/<id>.json`（`type: ywzj_rvp:weapon` + `bedrock_backend: rvp` + `model`/`texture` + `sounds.fire`/`sounds.reload`；同类武器可直接照抄）；③ 挂到载具上：挂架车走 `rvp_custom_mounts` 条目（配 `model`/`texture`/`ammo_slot`），炮塔/机炮车走站 `weapons` 数组。⚠️ **武器显示名走 JSON 内 `name`/`name_CN`，不需要写 lang**。⚠️ **子弹药不需要 display 文件**（`<id>_sub` / `_m73` 这类挂 `rvp_weapon` 的子体只被母弹引用）。


**履带 / 悬挂相关改动的强制前置（2026-10-20 订立）**：任何涉及 `track` 部件、`suspension` 部件、`suspensions` 分侧、`render_bone`、履带采样 / 链板 / 负重轮 的改动，**动手前必须先完整读一遍 `docs/教训_履带悬挂左右映射_20261020.md`，并跑完该文档 §6 的自检脚本（输出必须全部 OK）**。原因：**不同载具的轮组编号方式各不相同**（本批 t90m/ztz100/t84bm 是「右轮 1-6 / 左轮 7-12」对「右臂 1-6 / 左臂 9-14」，差两个号是因为右侧最后一对主动轮、左侧第一对诱导轮**没有算进负重轮**）⇒ 左右与轮↔臂的对应都是**几何属性而非编号属性**，**只能逐台读模型、禁止按编号恒等映射、更禁止套固定偏移**；曾因「按编号恒等映射 + 只看一侧就验收」导致 t90m / ztz100 / t84bm 左侧负重轮错位、左侧履带少一个采样点，而右侧完全正常，极难从现象反推。

**同一条链上的硬要求**：
- 改 `limitless_vehicle/rvp/` 下的包文件前，**必须先向用户给出变更清单并取得同意**；动手前先把待改文件复制到 `limitless_vehicle/rvp/_bak/<原名>.bak_<YYYYMMDD_HHMMSS>_<原因>`（包目录不进 git，没有备份就无法回滚）。
- 改完同步三处：开发源 / `build/rvp-pack/` / 测试客户端 `limitless_vehicle/rvp/`，并 md5 回读一致（`run/**` 不在此列）。
- 每条改动都能落到具体 file:line 依据；「另一侧正常」不能作为验收依据，必须左右都校验。
