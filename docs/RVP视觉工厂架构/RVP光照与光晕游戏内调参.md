# RVP 光照与光晕游戏内调参

日期：2026-10-31。最新爆炸独立广域光晕、20倍照亮亮度/5倍照亮范围，以及整盏短光随衰减倍率伸缩，见 [爆炸广域光晕与短光时间线](../plan/RVP爆炸广域光晕与短光时间线调参_20261031.md)。此前记录：[多尺度光晕](../plan/RVP多尺度光晕与独立亮度实现_20261031.md)、[范围分离](../plan/RVP光晕照亮范围分离与调参扩展_20261031.md)。

光晕可选原曲线、线性、快降后保持、高亮保持后淡出。爆炸默认开启光晕，按设定亮度保持5刻，再用70刻平滑归零；保持、淡出、亮度和范围均能在F8中单独调整。淡出过程中光晕半径同时向中心收缩（`haloShrink`，爆炸默认0.1），照亮范围保持不变；同时新增独立的天空散射通道，把整片夜空染亮后向爆心收拢（`skyPeakBrightness` 1 / `skyHoldTicks` 2 / `skyFadeTicks` 38 / `skyContract` 1，时间线与光晕独立）。函数、分段时间与验证见 [光晕亮度衰减曲线](../plan/RVP光晕亮度衰减曲线_20261031.md)。

光晕使用四种尺寸叠加，形成贴近亮焰的亮边和向周围消散的辉光。三类光源的照亮半径与光晕半径独立；爆炸广域光晕还具有独立亮度和保持/淡出时长。爆炸照亮默认1.6秒、基础衰减系数0.3、亮度倍率5、照亮半径倍率20。`decayScale` 调节照亮寿命，默认爆炸光晕另用5刻保持加70刻淡出。无需修改武器 JSON 或重新编译即可对比效果。

发动机当前默认：照亮亮度倍率2、照亮半径倍率5、光晕半径倍率5、广域橙晕亮度0.4、外围橙焰亮度5。炮口当前默认：亮度倍率4、照亮半径倍率1.2、光晕半径倍率0。爆炸光晕半径倍率默认1，中心最亮、向外变暗，淡出时按 `haloShrink`（默认1）向中心收缩。这些默认值在启动与 `reset` 后直接生效；已保存的显式覆盖仍优先。

这些是客户端指令，单机和联机都能使用，不需要服务端管理员权限。只影响自己的画面，不改变伤害、发动机燃烧时间或 Minecraft 的方块光等级。修改在下一显示帧生效，已存在的光源也会响应；重连、换维度保留，**完全重启游戏后恢复默认**。

## 1. 先用这些命令

按顺序单独执行，每次改一个参数便于对照。发动机预览位于准星附近，持续两秒；朝天空检查喷焰和外晕，朝墙面检查受光。需要时重新执行预览。

```text
/rvplight help
/rvplight status engine
/rvplight engine 0.3 300
/rvplight set engine haloIntensity 8
/rvplight engine 0.3 300
/rvplight set engine worldHaloIntensity 4
/rvplight set bloom nearStrength 1
/rvplight set bloom farStrength 2
/rvplight engine 0.3 300
/rvplight set engine radiusScale 2
/rvplight set engine haloRadiusScale 0.5
/rvplight engine 0.3 300
/rvplight reset engine
/rvplight reset bloom
```

上例原口径为 300 mm，两种范围的基础半径均为 `20+300×0.1=50` 格，当前默认照亮半径与光晕半径均为250格。`radiusScale 2` 后只把照亮半径改为100格，`haloRadiusScale 0.5` 单独把光晕半径改为25格；喷焰本身仍长3格、最大直径0.9格。

## 2. 指令形式

| 指令 | 用途 |
| --- | --- |
| `/rvplight` 或 `/rvplight status` | 活动光数、实际画质开关、最终环境光总倍率、最终观察距离和近景/DH 输入状态 |
| `/rvplight help` | 中文用法和常用示例 |
| `/rvplight status <组> [参数]` | 查看当前值、默认值、范围、中文含义及是否有临时覆盖 |
| `/rvplight set <组> [参数]` | 不填数值时只查询，便于先看范围 |
| `/rvplight set <组> <参数> <数值>` | 修改一项；支持 Tab 补全，非法数值不会覆盖原值 |
| `/rvplight reset [组] [参数]` | 恢复全部、一组或单项的原始设置，下一帧生效 |
| `/rvplight copy` | 将当前覆盖值复制成可重放的命令清单，首行包含 reset；可保存到笔记，下次逐条执行 |
| `/rvplight preview [半径]` | 1.6秒无伤害爆炸光预览；半径输入范围0.01～8192格，使用 explosion 组两种独立倍率及衰减 |
| `/rvplight engine [喷焰缩放] [毫米口径]` | 保留原有两秒发动机预览，默认缩放 0.3、口径 0 |
| `/rvplight clear` | 清除当前光和预览，保留调参值；真实燃烧中的发动机会继续登记 |

`reset` 只清临时调参。总开关和光晕开关没有覆盖时，重新尊重 `ywzj_rvp-lighting-client.toml` 中原有选择；其余参数恢复下表所列代码默认值。不会写任何配置文件。

## 3. 参数分组

### global：环境照明总设置

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `enabled` | 沿用客户端 `screenLight` | 0 或 1 | 环境受光总开关；喷焰保持原先独立的显示行为 |
| `brightness` | 1 | 0～256 | 在客户端 `intensity` 基础上再乘此倍率；所有环境光、烟雾和爆炸/炮口光晕一起变化，发动机喷焰自身仍独立 |
| `distanceScale` | 1 | 0.0001～128 | 在客户端 `maxDistance` 上乘倍率，最终限制为1～32768格；喷焰与天空散射不受此倍率限制 |
| `skyDistance` | 2048 | 0～32768 格 | 天空散射**专用距离预算**；0 表示沿用普通预算（`maxDistance`+可见半径）。只放宽/收紧天空染色，不影响照亮、光晕与核心 |
| `smokeBrightness` | 1 | 0～256 | 额外调节烟雾及炮口特殊贴图受光；不改变墙面和地面受光 |
| `ambientEnabled` | 1 | 0或1 | 按环境明暗适应灯光；0恢复原效果，核心、贴身光团和发动机喷焰始终保持自身亮度 |
| `ambientWorldWeight` | 0.7 | 0～1 | 各光源附近游戏光照的权重，剩余为照明前画面亮度；当地数据不可用时只参考画面，不加载区块 |
| `ambientMinStrength` | 0.1 | 0～1 | 最亮环境最低保留的灯光输入比例，默认10%；暗处仍保留100%，不改变范围和寿命 |
| `ambientCurve` | 1 | 0.1～4 | 明暗过渡指数；大于1更晚减弱，小于1更早减弱，暗端0.2、亮端0.8不变 |

环境适应同时调整世界表面、普通粒子烟、体积尾烟、外围扩散光晕及天空闪光。它先把每盏灯附近的天空光/方块光与当前像素附近的原画面明暗混合，再只给灯光计算输入乘一次倍率；并不是把整张画面或烟雾底色变暗。白热核心、贴身发光团和发动机喷焰主体保持原亮度。

F8在“环境光与光晕总设置”中提供相同四项，支持现有预设、复制、重置。可用 `/rvplight set global ambientEnabled 0` 临时关闭以对照；恢复用 `/rvplight reset global ambientEnabled`。实现与自动回归说明见[环境亮度自适应](../plan/RVP光源环境亮度自适应_20261101.md)。

### explosion / muzzle / engine：三类光源

以下参数三个组都支持，数值乘在既有口径规则、颜色和亮度上。

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `brightness` | explosion为5，muzzle为4，engine为2 | 0～256 | 只调该类照亮亮度；爆炸广域光晕另用独立亮度，炮口光晕沿用炮口光强，发动机喷焰另有独立亮度 |
| `radiusScale` | explosion为20，engine为5，muzzle为1.2 | 0～1024 | 只调照亮半径，最终0～8192格；0关闭方块、实体和烟雾受光范围，保留光晕 |
| `haloRadiusScale` | engine为5，explosion为35，muzzle为0 | 0～1024 | 只调光晕半径，最终0～8192格；0关闭该类光晕，发动机白热喷焰保留 |
| `red` / `green` / `blue` | 1 | 0～16 | 环境光红/绿/蓝分量倍率，最终颜色限制为0～1；爆炸原有变色继续生效 |
| `surface` | 1 | 0～16 | 方块、实体和烟雾受光权重倍率，不再被旧权重1的上限截断；不改变光晕 |

只有 `explosion` 和 `muzzle` 另外支持：

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `decayScale` | 1 | 0～64 | 调整照亮寿命与淡出；曲线0～2的光晕同步，曲线3使用自己的显式时长。最短1刻，0按0.05慢速淡出 |

两种范围都从未经调参的基础半径独立计算，实际半径为 `min(8192, 基础半径×对应倍率)`，不会逐帧累乘。发动机基础半径仍由口径计算为20～500格；这个旧基础上限不限制调参后的范围。两种范围均不改变喷焰几何长宽、燃烧阶段或二脉冲包络。`red/green/blue` 调环境光颜色及爆炸/炮口光晕颜色，喷焰自身继续使用现有橙色色比。

例如只保留爆炸光晕：执行 `/rvplight set explosion radiusScale 0`，或把该组 `brightness` 设为0；只保留炮口照亮：`/rvplight set muzzle haloRadiusScale 0`，目前默认已是这个设置。两种范围同时为0时暂不提交该光源，但未过期条目保留，恢复参数可继续按原年龄显示。爆炸照亮和广域光晕的亮度分别控制；同一次爆炸共用出生年龄，光晕可以独立选择亮度曲线和结束时间。

爆炸基础寿命32刻、基础衰减指数0.3；炮口基础寿命按类型为2或3刻、基础指数1.6。实际寿命为 `max(1刻, 基础寿命/max(0.05, decayScale))`。爆炸 `decayScale=0.5` 时整盏光源3.2秒，设为2时0.8秒；0时按0.05计算，爆炸最长32秒，避免无限残留。

照亮和光晕共同使用原始出生年龄；原曲线在最后20%照亮寿命加一段平滑归零。其它曲线只替换光晕的亮度变化；爆炸默认先高亮保持，再独立平滑归零。低帧率或高帧率不改变游戏刻寿命；迟包扣除已过去的年龄；调参不重置出生时钟。效果包装器等待照亮与光晕均结束，延长光晕不会被原32刻提前取消。

> **爆炸光源只在空中起爆时生成。** 服务端发布爆炸事件时用原版高度图（`MOTION_BLOCKING`，树叶与水面同样算地面）算出**爆心离地高度**，作为事实写进事件载荷的 `clearance` 字段（单位格，可为负）；客户端用本机门槛 `global airBurstClearance`（默认 **100 格**，可在 `config/ywzj_rvp-lighting-client.toml` 或 `/rvplight set global airBurstClearance` 调整，**0 表示不限制**）比较：`离地高度 ≥ 门槛` 才生成完整光源（照亮 + 光晕 + 白热核心 + 天空染色），否则只保留粒子、烟雾与声音。门槛为 0 时贴地爆炸、洞穴内爆炸也会建灯。旧载荷没有 `clearance` 时回退旧的 `air_burst` 布尔（缺失按“空中起爆”）；温压、威尔逊等其它视觉工厂的附加光仍走各自事件，不受这条规则约束。

### 光晕亮度曲线：global / explosion / muzzle / engine

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `haloDecayCurve` | explosion为3，engine为1，其它0 | 整数 0 / 1 / 2 / 3 | 0原有；1线性；2快降后保持；3高亮保持后淡出。全局显式设置可统一覆盖来源默认 |
| `haloPeakHoldTicks` | explosion为5，其它0 | 0～40刻 | 保持设定亮度的时间，仅曲线3生效 |
| `haloFadeOutTicks` | explosion为70，其它0 | 0～640刻 | 保持后独立淡出时间，仅曲线3生效；0自动使用照亮剩余寿命 |
| `haloDropFraction` | 0.001 | 0.001～0.5 | 快速降亮阶段占总寿命的比例，仅曲线2生效 |
| `haloLowBrightness` | 0.1 | 0～1 | 保持段相对初始亮度的比例，默认10%，仅曲线2生效 |
| `haloHoldUntil` | 0.99 | 0.5～0.99 | 低亮保持到的寿命位置，默认99%；剩余时间平滑消失，仅曲线2生效 |
| `haloShrink` | explosion为0.1，muzzle为0 | 0～1 | 光晕随淡出向中心收缩的强度：0保持固定半径，1在亮度归零时半径收到中心；只缩光晕半径，不改变照亮半径、亮度、寿命或喷焰；持续光（发动机、干扰机）不参与 |

```text
/rvplight set explosion haloRadiusScale 1
/rvplight set explosion haloDecayCurve 3
/rvplight set explosion worldHaloIntensity 16
/rvplight set explosion haloPeakHoldTicks 3
/rvplight set explosion haloFadeOutTicks 29
/rvplight preview
```

默认爆炸光晕先保持5刻（0.25秒）全亮，再用70刻（3.5秒）平滑归零。修改 `worldHaloIntensity` 调峰值，修改 `haloPeakHoldTicks` 调保持时间，修改 `haloFadeOutTicks` 调淡出时间，照亮地面/墙面/烟雾的时间均不受影响。光晕可比照亮更早或更晚结束。`haloDecayCurve 1` 切换线性，`2` 为快降后保持低亮，`0` 为原曲线；全局显式设置和来源覆盖仍可使用。

光晕默认还会在淡出时**向中心收缩**，形成光亮由外向内收拢、最后从中心熄灭的观感。采样时实际半径为 `静态光晕半径 × (1 - haloShrink × (1 - 光晕淡出比例))`：满亮保持段（爆炸前3刻）不收缩；爆炸在17.5刻（淡出比例正好0.5）时半径减半，最后随亮度一起归零。这项只改光晕半径，照亮地面/墙面的范围与亮度、整盏光源的寿命都不变，喷焰几何也不受影响。想对照差异可各预览一次 `/rvplight set explosion haloShrink 0` 与默认值；`0.5` 时只收到四分之三。由于收缩同时让光晕的屏幕占比变小，末尾几刻也更容易让位给其它光源的八灯预算。

### 天空散射：整片夜空被闪亮

天空像素没有深度也没有表面法线，原本不接收任何表面受光，只能靠光晕在屏幕上留一个圆盘。为此新增独立通道：**只对没有深度的天空像素**按“视线与爆心方向的夹角”染色，因此能做到整片夜空同时变亮、随后平滑变暗并向爆心收拢。

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `skyPeakBrightness` | explosion为1，muzzle为0 | 0～8 | 天空峰值亮度；0关闭。与 `brightness`、`worldHaloIntensity` 三者独立，不占用八灯预算、不进入光晕提取 |
| `skyHoldTicks` | explosion为2 | 0～40 刻 | 天空**自己**的峰值保持刻数，与光晕的 `haloPeakHoldTicks` 完全独立 |
| `skyFadeTicks` | explosion为38 | 0～640 刻 | 天空**自己**的淡出刻数；天空寿命 = 保持 + 淡出，整段不跟随光晕曲线 |
| `skyContract` | explosion为1 | 0～1 | 天空亮区随淡出向爆心收拢的幅度；0只整体变暗，1在熄灭时只留爆心方向。只改方向分布 |

采样公式（天空时间线与光晕、照亮都无关）：

```text
天空淡出比例 = peakHoldFade(年龄; skyHoldTicks, skyFadeTicks)   // 先保持，再三次平滑降到0
强度 = skyPeakBrightness × 天空淡出比例
宽度 = 1 - skyContract × (1 - 天空淡出比例)
```

着色器内为 `亮度 × (0.45 + 0.55 × exp(-k × (1 - cosθ)))`，其中 `k` 由宽度决定，宽度为1时 `k=0.8`（整片覆盖，背向爆心的天空也有约56%亮度），宽度为0时 `k=6`（只剩爆心方向）。因此观感是：**爆炸瞬间整片天空统一泛亮 → 按 `skyHoldTicks` 保持 → 在 `skyFadeTicks` 内平滑变暗、亮区同时向爆心收拢 → 熄灭**。

```text
/rvplight set explosion skyPeakBrightness 0.8
/rvplight set explosion skyHoldTicks 3
/rvplight set explosion skyFadeTicks 29
/rvplight set explosion skyContract 0.6
/rvplight preview 8
```

要点：

- 天空染色**与照亮半径、照亮亮度、光晕半径都无关**：把 `radiusScale`、`haloRadiusScale`、`worldHaloIntensity` 全部设为0，夜空仍然会闪；只有把 `skyPeakBrightness` 也设为0，该光源才会完全不再提交。
- **天空不跟随光晕时间线**：把 `haloPeakHoldTicks`/`haloFadeOutTicks`/`haloDecayCurve` 改成任何值，天空的保持与淡出都只认 `skyHoldTicks`/`skyFadeTicks`。
- **天空可以比照亮和光晕活得更久**：整盏光源的寿命取 `max(照亮, 光晕, 保持+淡出)`，所以把 `skyFadeTicks` 调长不会在照亮结束时被截断；反之把 `decayScale` 调快也不会提前熄灭天空。
- **爆心在相机背后也会生效**：整片天空的亮度来自大气散射，不会因为转头而消失（光晕圆盘仍然需要光心在屏幕内）。
- 白天的天空背景很亮，`composite` 的可加余量自动变小，所以同一参数白天几乎无感、夜晚非常显著，不需要按昼夜分支。
- 天空有**独立距离预算** `skyDistance`（`global` 组，默认 2048 格，0～32768，0 表示沿用普通预算），因此高空空爆不会再被地面光照的距离预算挡掉；地面照亮仍受 `maxDistance` 约束。同帧多次爆炸先求和再压缩，极端齐爆会接近全白。
- 第一版**不做遮挡判断**：地下或山体后的爆炸同样会染亮天空。若需要，可后续加“光心被前景挡住则不染天空”的开关。
- 只有 `explosion` 与 `muzzle` 支持这几项天空参数，`engine`（持续燃烧）与 `global` 不参与；持续光恒不写天空，因此不会出现导弹全程把夜空染红。

> **新增 uniform 必须同时写进描述 JSON。** Minecraft 只按 `shaders/core/<程序>.json` 的 `uniforms` 列表创建 Uniform，不反射显卡程序里的活动 uniform；漏写时 `ShaderInstance.safeGetUniform` 会返回一个空的 no-op 对象，`.set(...)` **静默无效且不报错**，表现为“参数在、画面没变化”（本次天空散射首版即因此失效：16 个 `LightSky*` uniform 只写进了 `.fsh`）。该约束由 `RVP_ShaderUniformDeclarationTest` 静态校验，覆盖全部 6 个生产程序的双向一致性。

#### 排查：某次爆炸没有染亮天空

天空项涉及“事件到达 → 光源登记 → 预算/视锥裁剪 → 采样 → 上传显卡”五段，任何一段断掉都表现为“没反应”。用下面这条客户端指令开启追踪，复现后直接发送生成的日志文件即可定位段落：

```text
/rvplight skydebug on       # 开启；聊天栏显示本次日志文件的绝对路径
/rvplight skydebug status   # 查看开关、文件路径与当前 skyPeakBrightness / skyContract
/rvplight skydebug off      # 关闭并提示本次记录所在文件
```

日志写入**独立文件** `logs/rvp-sky-scatter.log`（游戏目录下），**不写 `latest.log`**：UTF-8 无 BOM、每行立即刷新（崩溃也不丢最近的行）、每次开启追加一段会话头（时间、相机、画质与调参快照）；单文件超过 4 MiB 时旧内容转存为 `rvp-sky-scatter.log.old`，只保留一代。帧摘要与剔除原因合并节流到 2 条/秒；关闭后不产生任何格式化或写盘开销。日志目录不可写时只会在命令状态里提示，不会抛异常影响渲染。各行的判读方式：

| 日志行 | 含义 | 缺失/异常时的结论 |
| --- | --- | --- |
| `事件到达 类型=… flash=… 位置=… 客户端年龄=…` | 服务端视觉事件真的到了本客户端 | 完全没有这一行：事件被上游屏蔽（HBM 接管、其它视觉工厂已发布、`suppress_rvp_default_explosion`）或超出广播范围，与光源无关 |
| `光源登记 handle=… 天空目标=…` | 光源登记成功（handle 非 0）且调参表给出的目标强度 | `handle=0`：事件迟到超过整盏光源寿命（默认 32 刻）或参数无效 |
| `本帧 跟踪=… 提交=… 天空灯=… 模式=…` | 本帧真正提交给显卡的光源数 | `提交=0` 且下面没有剔除原因：整帧被跳过，见 `本帧跳过：…` |
| `提交 EXPLOSION … sky=… spread=…` | 该灯**采样后**的天空强度与收拢宽度 | `sky=0`：淡出已结束或 `skyPeakBrightness=0`；`sky>0` 而画面不亮：问题在显卡侧 |
| `剔除 … 原因=超出距离预算 …` | 空爆最常见的失败原因：天空灯用的是 `skyDistance`（默认2048），地面灯用 `maxDistance`（默认5012）+ 可见半径 | 调大 `skyDistance`（`/rvplight set global skyDistance`），或接受该爆炸距离外不染天空 |
| `剔除 … 原因=八灯预算不足 …` | 同帧光源超过 8 盏，该灯排序靠后被截断 | 弹幕/子母弹齐爆时正常，可减少同时爆炸数或接受优先近处光源 |
| `剔除 … 原因=没有任何可见贡献` | 照亮、光晕、天空三者都为 0 | 检查 `skyPeakBrightness`、`brightness`、`radiusScale` |
| `本帧跳过：…` | 整帧被跳过（热成像、着色器未加载、深度不可用、世界切换等） | 按提示处理；热成像下彩色光整层关闭属预期 |
| `着色器 uniform 检查：… 已声明/缺失` | 描述 JSON 是否声明了天空 uniform | 报“缺失”说明资源未同步，天空项会静默失效 |


环境照亮仍使用原有淡出，四层模糊光晕共同处理已经采样的亮度，不再重复乘原曲线。发动机预览的喷焰本体仍按原方式淡出，模糊光晕与广域橙晕使用新曲线。真实发动机、光电干扰机是持续光，继续跟随真实燃烧或开关状态，持续开启期间保持发光。

### 爆炸白热核心（圆形高亮）

爆炸炸点现在有一个**圆形白热核心**：复用喷焰那套“核心 + 外围光团”的 HDR 自发光结构，但形状是圆球投影而不是定向喷流。它走独立的加色通道，**不被 0.9 曝光上限压住**，所以中心可以达到纯白，外围保留橙色光团；与表面照亮、光晕、天空染色三者互相独立。

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `coreIntensity` | explosion 40，engine 60000 | 0～60000 | 白热核心的 HDR 峰值；设为0只关闭核心（可只保留外围光团，但核心半径仍须大于0） |
| `coreHaloIntensity` | explosion 40 | 0～60000 | **核心外围光团**的独立亮度（与喷焰 `haloIntensity` 互不影响）；0关闭光团，核心照常显示 |
| `coreRadius` | explosion 0.8 格 | 0～64 | **参考当量**（爆炸半径4格→基础半径12格）下**出生时的最大**核心半径；0关闭整条自发光 |
| `coreRadiusScale` | explosion 1 | 0～8 | 核心半径的**当量倍率**：实际半径再乘 `基础半径/12`，0 表示不随当量变化；越大当量影响越夸张 |
| `coreShrink` | explosion 0.3 | 0～1 | 半径随寿命**收缩**的幅度：`r(t) = 出生半径 × (1 - coreShrink × 寿命进度)`，0保持固定、1缩到中心（保留5%下限） |
| `coreHaloRadius` | explosion 10 格 | 0～128 | 参考当量下**出生时最大**的外围光团半径；着色器另有“不小于核心半径3.5倍”的下限 |
| `coreHaloScale` | explosion 1 | 0～8 | 光团半径的**当量缩放**，与 `coreRadiusScale` 独立，可只放大光团而不动核心 |

尺寸公式（`基础半径 = min(32, 4 + 2×爆炸半径)`，参考值 12 对应爆炸半径 4 格）：

```text
收缩系数 = max(0.05, 1 - coreShrink × 寿命进度)      // 出生时最大，随时间向中心收
核心半径 = coreRadius    × coreRadiusScale × (基础半径 / 12) × 收缩系数
光团半径 = coreHaloRadius × coreHaloScale  × (基础半径 / 12) × 收缩系数
亮度     = 峰值 × 光晕淡出比例
```

例如 `coreRadius 0.8 / coreRadiusScale 1 / coreShrink 0.5`：爆炸半径 4 格时出生 0.8 格、寿命终点 0.4 格；半径 8 格（基础 20）出生 1.33 格；半径 ≥14 格（基础封顶 32）出生 2.13 格。把 `coreRadiusScale` 改成 0 就等于“所有当量同尺寸”，改成 2 则翻倍。

行为要点：

- **亮度跟随光晕淡出**：与光晕共用同一出生年龄和淡出曲线（默认5刻保持+70刻淡出），曲线3时前0.25秒满亮。
- **半径从最大开始收缩**：火球一边变暗一边收向中心；收缩按归一化寿命进度计算，`decayScale` 改变寿命时收缩节奏同步拉伸；核心与外围光团一起缩。
- **与其它通道完全独立**：把 `brightness`、`radiusScale`、`haloRadiusScale`、`worldHaloIntensity`、`skyPeakBrightness` 全部设为0，只要 `coreRadius` 与 `coreRadiusScale` 都大于0、且 `coreIntensity`（或 `coreHaloIntensity`）大于0，光源仍会通过预算并绘制白热核心。
- **遮挡与距离**：沿用既有“光心/当前像素深度”判断，墙后不绘制；远距离由“至少若干像素”的最小覆盖补足，不会因半个像素而闪烁。
- 颜色沿用光源颜色（暖白→橙红），并随年龄压绿蓝，因此熄灭前的核心偏橙。

```text
/rvplight set explosion coreIntensity 60000
/rvplight set explosion coreRadius 1.2
/rvplight set explosion coreRadiusScale 1.5
/rvplight set explosion coreShrink 0.6
/rvplight set explosion coreHaloRadius 5
/rvplight set explosion coreHaloScale 2
/rvplight set explosion coreHaloIntensity 18
/rvplight preview 8
```

注意：这是**屏幕空间圆形核心**，不做真实三维火焰几何；要喷流形状请用发动机喷焰通道（`rocket_plume_ranges`）。F8 面板「爆炸照亮、光晕与天空」里同样可以调这七项，单位分别是“亮度（HDR）”“格”“倍”。

`engine` 与 `explosion` 只有 `coreIntensity` 是共用的；喷焰的外围橙焰仍用 `haloIntensity`，爆炸的核心光团用 `coreHaloIntensity`，两者单独调不会互相牵制：

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `coreIntensity` | engine 60000，explosion 40 | 0～60000 | 白热核心显示前的高亮数值，即 HDR 辐射亮度；原有阶段和燃尽倍率继续相乘。发动机设为 0 会关闭整条喷焰及它的光晕；爆炸设为 0 只关核心 |
| `haloIntensity` | engine 5 | 0～60000 | 只控制**喷焰外围橙焰**的显示前亮度；0 关闭外围橙焰，白热核心仍可进入模糊光晕，广域橙晕不变。爆炸不支持该参数，请用 `coreHaloIntensity` |

核心是局部发光体，`engine brightness` 是附近地面、墙面和烟雾的受光强度，两者可分别调。高亮数值经现有显示映射后会饱和，所以把核心从 60000 改为 30000，肉眼亮度可能差别很小；需要明显对比可试 10、1、0.1 等低值。

`explosion` 和 `engine` 共同支持：

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `worldHaloIntensity` | explosion为0.6，engine为0.4 | 0～60000 | 爆炸广域光晕或发动机广域橙晕的独立亮度。爆炸设为0只关闭光晕，不改变照亮；发动机设为0只关闭广域橙晕，喷焰和近层模糊保留 |

例如执行 `/rvplight set explosion worldHaloIntensity 8` 把爆炸广域光晕变暗，照亮仍按默认5倍光强和20倍照亮半径显示。`reset explosion` 恢复照亮亮度5、照亮半径倍率20、广域光晕亮度0.6、光晕半径倍率35，默认保持5刻再用70刻淡出；存在全局显式时间/曲线覆盖时继续继承它。`copy` 导出的指令保留实际覆盖关系。

### bloom：四层模糊光晕

| 参数 | 默认 | 范围 | 中文含义 |
| --- | --- | --- | --- |
| `enabled` | 沿用客户端 `bloom` | 0 或 1 | 同时开关模糊光晕与发动机广域橙晕，白热喷焰保留 |
| `inputPeak` | 12 | 0.00001～60000 | 喷焰送进模糊前的软压缩亮度上限；不改变核心直接显示、独立广域橙晕或爆炸/炮口光晕输入 |
| `blurScale` | 1 | 0～32 | 每层高斯柔化采样间距的倍率；0关闭额外高斯柔化，缩小和回升必需的连续过滤仍保留 |
| `nearStrength` | 1 | 0～256 | 二分之一、四分之一尺寸两层的强度倍率，控制贴近亮焰的亮边；0关闭这两层，不补偿远层亮度 |
| `farStrength` | 1 | 0～256 | 八分之一、十六分之一尺寸两层的强度倍率，控制向周围消散的辉光；0关闭这两层，不补偿近层亮度 |

`bloom blurScale` 调屏幕上的柔化范围，三类光源的 `haloRadiusScale` 调按世界格数投影的独立光晕范围，`radiusScale` 只调照亮范围。光晕边缘还会有后处理柔化，不能把半径理解为最终屏幕上绝对没有任何亮度的硬边界。发动机 `haloRadiusScale 0` 同时关闭其四层模糊来源和广域橙晕，白热喷焰仍显示。原有亮部提取门槛0.08、平滑过渡0.04和最终合成强度2.0保持不变。

四层基础权重为 `0.4、0.3、0.2、0.1`，默认合计为一。实际权重为前两层乘 `nearStrength`、后两层乘 `farStrength`。关闭近层时，远层不会自动接过其亮度，便于逐层对照。所有层只过滤 RVP 自己的发光贡献，雪地、天空和界面文字不作为输入。

要减弱喷口周围的圆形橙光、突出整条亮焰的扩散，可先试 `engine worldHaloIntensity 4`、`bloom nearStrength 1`、`bloom farStrength 2`。这是外观调参起点，不代表所有距离和光影组合都已实机定稿。

### 距离与数量预算一览（当前值）

| 预算 | 当前值 | 作用范围 | 出处 |
| --- | --- | --- | --- |
| `maxDistance` | **5012 格**（16～32768，TOML） | 地面照亮/光晕/核心的基准观察距离 | `config/ywzj_rvp-lighting-client.toml` |
| `distanceScale` | 1（0.0001～128） | 乘在上面的倍率，最终 1～32768 | `/rvplight set global distanceScale` |
| 单盏地面光允许距离 | `maxDistance + visibleRadius` | `visibleRadius` 取照亮/光晕/核心中仍有效的一路 | `RVP_ScreenLightBudget` |
| `skyDistance` | **2048 格**（0～32768） | 天空染色专用；0 = 沿用上面的普通预算 | `/rvplight set global skyDistance` |
| `airBurstClearance` | **100 格**（0～32768，允许小数） | 爆炸屏幕光源的离地高度门槛；0 = 不限制（贴地爆炸也建灯） | `/rvplight set global airBurstClearance` / TOML `airBurstClearance` |
| 单灯半径上限 | 8192 格 | 照亮/光晕半径调参后的硬上限 | `RVP_LightRuntimeTuning.MAX_TUNED_RADIUS` |
| 每帧灯数 / 跟踪条目 | 8 / 128 | 超出按“近处优先 + 覆盖评分”截断，或淘汰最旧短光 | `RVP_ScreenLightBudget` / `RVP_ScreenLightManager` |
| 遮挡几何 | 每灯 64³（±32 格） | 32 格之外没有方块遮挡判定 | `RVP_LightGeometryPass` |
| 视觉事件广播 | **8192 格** | 服务端把爆炸等视觉/声音事件发给玩家的范围；JSON `broadcast_range` 默认同值 | `RVP_VisualEffectData` / `RVP_DefaultExplosionVisualService` |
| 爆炸声音传播 | 炮弹·火箭·导弹·航弹·载具击毁 **3560 格**；机枪 512 | 火箭／导弹／航弹／载具击毁统一 512 格近远切换；超过上限不播放；按声速延迟 | `RVP_DefaultExplosionSoundController` |
| 喷焰 / 体积尾烟 | 无距离上限 | 仅视锥裁剪 + 可见 128 喷焰 / 2048 烟段 | `RVP_RocketPlumeParameters` / `RVP_RocketTrailParameters` |
| 冲击波 | 启用门槛 `radius ≥ 8`；半径上限 2048 格；广播 ≥8192 | 自动冲击波 | `RVP_AutomaticShockwaveService` / `RVP_ShockwaveRuntimeTuning` |
| 客户端效果实例 | 24 个活动 / 4 个复杂 | 超出时关闭最旧效果（会连带取消其光源） | `RVP_ClientVisualEffectDispatcher` |

## 4. 实现与验证入口

- `light/RVP_LightRuntimeTuning.java`：唯一参数表、有限数校验、不可变临时覆盖和分组重置，不引用客户端类型。
- `client/light/RVP_ScreenLightDebugCommands.java`：复用现有客户端命令，参数节点由上面的定义生成。
- `client/light/RVP_ScreenLightManager.java`：从原始光源预设每帧重新采样，调参后再裁剪和排序；不反复累计倍率，不重置年龄。
- `client/light/RVP_RocketPlumeManager.java`：采样时应用独立光晕半径，保持喷焰长宽、强度包络和独立预算。
- `client/light/RVP_RocketPlumePass.java`：分别上传核心、外围橙焰和广域橙晕亮度；可见发光覆盖不受外围亮度串改。
- `client/light/RVP_ScreenLightPass.java`：执行四层提取、缩小、柔化和加权回升。
- `client/light/RVP_ScreenFrameResources.java`：将照亮结果与光晕来源分开保存，分配四种尺寸各两张光晕纹理，窗口变化和资源重载统一释放。
- `light/RVP_BloomParameters.java`：共同尺寸规则和固定基础能量分配。
- `light/RVP_TransientLightTimeline.java`：整盏爆炸/炮口光源的倍率寿命、末段平滑和精确结束时刻。
- `client/light/RVP_SmokeLightPass.java`：对已有烟雾受光输入增加单独的亮度倍率。
- `config/RVP_LightingConfig.java`：保持原有客户端配置作为基础，运行时叠加开关、亮度和距离覆盖。

以上 Java 路径均相对 `src/main/java/org/ywzj/rvp/`。最新生产屏幕光使用另名 `screen_light_halo_timeline`，每盏光分别上传照亮强度和光晕强度；继续使用 `rocket_plume_ranges` 和 `screen_light_bloom_multiscale`，所有旧资源完整保留。烟雾受光与空气散射使用当前照亮强度，因此也跟随整盏光源的实际寿命。

自动化验证覆盖：爆炸20/5/16默认值与分路亮度、三类独立范围和预算、短光即时调参和原始年龄、独立高亮保持/淡出、两路精确清理、迟包与显式取消、有限0倍率、末段显卡合成平滑、原指令兼容和越界拒绝。相关140项测试和生产着色程序显卡回读均通过；完整构建1111项测试有1项既有t84bm玻片配置校验失败，见 [本次验证记录](../plan/RVP光晕亮度衰减曲线_20261031.md#验证)。

2026-10-31 此前广域光晕与短光时间线版本的完整构建为 **1060项测试，0失败、0错误、0跳过**，记录见 [爆炸广域光晕与短光时间线](../plan/RVP爆炸广域光晕与短光时间线调参_20261031.md)。诊断脚本只写 `build/` 下的产物，不改资源源文件。

实机仍需对照原版、DH、光影、DH+光影四种组合，使用真实爆炸、炮口和发动机检查画面。调参不绕过原有热成像和兼容性限制；`/rvplight status` 继续报告实际输入状态。




