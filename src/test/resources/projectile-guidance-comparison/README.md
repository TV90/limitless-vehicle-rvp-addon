# PIP / PPN 六类连续目标机动矩阵

`matrix.json` 只配置试验初态和目标运动，不覆盖武器参数；权威武器从项目根 `limitless_vehicle/rvp/data/rvp/weapons/` 读取。
本轮不修改游戏内制导律。

- 6 把武器：MICA-NG、FIM-92、9M317M、IRIS-T SL、AIM-120D、PL-15；后两把保留高抛配置。
- 每把 3 个原包络内的初始距离、3 个目标档位、左右镜像。
- 匀速横穿只生成一次，不把无关的时机重复计入样本：108 组。
- 横穿加速、横穿减速、变向匀速、变向加速、变向减速各增加 3 个时机：每类 324 组。
- 总计 **1728 组同条件配对、3456 次实体飞行**。机动时机为原 PIP 同条件匀速首次接近时间的 25%、60%、85%。

目标档位：初速 35 / 55 / 65 格每秒；加速末速 55 / 80 / 90；减速末速 20 / 25 / 30。
变向角 60° / -90° / 180°，分别在 60 / 40 / 60 Tick 内连续完成；镜像时初始横向速度和转角同时反号。
速度、航向在段连接处连续，位置由同一速度函数解析积分；最后一段后保持末速度和末航向。

运行（项目根，使用 `agents.md` 指定 Java 环境）：

```powershell
./gradlew build guidanceManeuverComparison
```

输出默认位于 `build/projectile-maneuver-comparison/`。`plan.json`、`scenarios/` 保存全部初态；
`pip.json`、`ppn.json` 保存原始武器快照、实际制导 class 指纹和逐场景结果；
`summary.json`、`paired-results.csv`、`report.md` 保存分类、逐武器及高抛/无高抛的对照。

PIP 基线从 Git 固定源码对象 `974ba042786f8f7b455f868a7400bf2a87734357` 提取到 `build/`，
单独编译并仅在 PIP 测试进程中优先加载；实际加载内容必须与编译输出的 SHA-256 文件内容指纹一致。
PPN 使用当前生产类。两者共用相同实体、机头惯量、气动、发动机、门控和目标脚本。
基线复现已与前一轮 14 个 PIP 回放逐帧完全一致。引用对象需要存在于 Git 历史中，缺失时任务直接失败，不使用替代实现。

单个场景也可复现：

```powershell
./gradlew projectileSimulation "-PprojectileGuidance=PIP" "-PprojectileScenario=build/projectile-maneuver-comparison/scenarios/场景ID.json" "-PprojectileOutput=build/single-pip"
./gradlew projectileSimulation "-PprojectileGuidance=PPN" "-PprojectileScenario=build/projectile-maneuver-comparison/scenarios/场景ID.json" "-PprojectileOutput=build/single-ppn"
```

批量评分从首次有效制导后识别首次接近，在距离开始回升后结束，不评分发射等待波动或绕圈后的再次接近。
超时、寿终、全程无制导和机动前已交会分别记录；主比较只使用双方完成首次接近且均已开始机动的共同集合。
普通工作台回放的停止球与批量评分器不同，不能拿停止球内距离代替批量首次交会评分。
工作台可直接显示生成回放中的实际轨迹帧；本轮没有扩展图形场景编辑器的机动参数面板。

测试源码、矩阵和脚本保留在开发仓库，不打入发行 JAR；原 PIP 基准仅在隔离的测试进程加载。
完整设计与归档图表见 [实施与数值对照](../../../../docs/RVP弹体-fish/RVPPIP与PPN六类连续机动交会误差对照_20261105.md)。
