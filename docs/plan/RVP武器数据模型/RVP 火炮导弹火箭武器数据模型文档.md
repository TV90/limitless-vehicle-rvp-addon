注：表格中的字段名称为java类中使用的驼峰命名法，在json中需使用全小写+下划线连接的格式
注：`maxLockAngle` 是完整 FOV，需要运行时除以二；`maxGuidanceAngle` 和 `maxOffAxisLockAngle` 是相对轴线的单侧角度，不除以二

# RVP_GuidanceData

弹药制导数据模型

| RVP_GuidanceData公用字段 | 解释 | 类型 | 默认值 |
| :----------------------- | ------------------------------------------------------------ | -------------------------------------- | ------ |
| `predictTargetPosGain` | 比例导引增益系数。值越大，导弹对 LOS 转率和闭合速度的响应越积极。仅在 `predictTargetPos=true` 时生效。 | `float` | 3.0 |
| `maxLateralAccel` | PN 横向修正的限幅值。用于限制单 tick 横向修正过强导致的大幅甩尾、绕大弯、撞地或乱飞。仅在 `predictTargetPos=true` 时生效。 | `float` | 0 |
| `predictTargetPosStartTick` | 发射后从第多少 tick 开始施加 PN 修正。用于避免导弹低速、离架、刚点火阶段就被 PN 拉出过大偏转。仅在 `predictTargetPos=true` 时生效。 | `int` | 10 |
| guidanceType | 制导类型NONE/MCLOS/SALH/SACLOS/LBR/LBR/LH/TV/HITL_TV/HITL_CLOS_TV/ATV/IR/AIR/SARH/ARH/GPS/ARM | RVP_EnumGuidanceType | NONE |
| guidanceTickRange | 制导时间范围，null表示立即开始，永不结束 | RVP_Range<Integer> | null |
| guidanceTargetDistanceRange | 导弹跟踪时与制导目标点/记忆点的距离范围(格)，null表示不进行判断 | RVP_Range<Float> | null |
| guidanceAltitudeRange | 导弹跟踪时与制导目标点/记忆点离地高度下限(格)，null表示不进行判断，如参数为[[20,100]]时，只有离地高度高于20且低于100的才会被跟踪，参数为[[inf,10]]表示可跟踪离地10格以内目标的对地弹，参数为[[30,inf]]表示可跟踪高于地面30格目标的对空弹 | RVP_Range<Float> | null |
| lockTargetDistanceRange | 载具火控锁定时与制导目标点/记忆点的距离范围，null表示不启用 | RVP_Range<Float> | null |
| lockAltitudeRange | 载具火控锁定时与制导目标点/记忆点离地高度下限(格)，null表示不进行判断，如参数为[[20,100]]时，只有离地高度高于20且低于100的才会被锁定，参数为[[inf,10]]表示可锁定离地10格以内目标的对地弹，参数为[[30,inf]]表示可锁定高于地面30格目标的对空弹。下限为inf的导弹其导引头瞄准样式为空对地导弹头瞄圈，其余为空对空导弹头瞄圈 | RVP_Range<Float> | null |
| enableIRHMD                 | 是否拥有红外弹头瞄（头瞄目前仅适用于红外弹）                 | boolean                                | true |
| maxGuidanceAngle | 导弹跟踪时导引头最大锁定角度（发射后） | int | 60 |
| scanIntervalTick | 导弹跟踪时导引头扫描间隔tick，适用于发射后导引头需要自主扫描类型（ARH/AIR/ARM)导弹，null表示不主动扫描 | Integer | null |
| maxLockAngle                | 导弹导引头视场，在开启导引头扫描阶段适用，即开启导引头但未锁定目标的阶段。<br />在范围内的目标会被捕获<br />如果是红外弹且拥有头瞄，其还具有离轴搜索能力，相当于整合了原seek的fov参数<br />导引头头瞄离轴时，不要与武器站旋转角度进行角度叠加（这块逻辑已经写完，但需要迁移） | int | 5 |
| maxOffAxisLockAngle | 导引头锁定后的离轴最大角度，适用于锁定完毕后的锁定保活<br />原guide_head_max_angle参数功能整合到此处<br />若是拥有头瞄的红外弹，导引头视场最大在这个范围内离轴，超过角度会被钳制<br />不会与武器站旋转角度进行角度叠加（这块逻辑已经写完，但需要迁移） | int | 60 |
| predictTargetPos | 是否启用比例制导 | boolean | false |
| topAttackHeight | 攻顶最大高度，null为不启用攻顶，可填负数（适用于潜射制导武器） | Float | null |
| cruiseStartTick | 多少tick后，弹药进入巡航段，null为不启用巡航。激光架束，人在回路和指令线类制导武器不生效 | Integer | null |
| cruiseEndHorizontalDist | 水平方向距离目标点多少格后，弹药结束巡航阶段，进入末端俯冲 | float | 10 |
| cruiseGravityScale | 巡航段重力系数 | float | 1.0 |
| cruiseLevelingFactor | 巡航段自动改平强度 | float | 0.15 |
| lockAngleGate | 载具火控锁定时角度门，即不同距离下载具瞄准线与制导实体的运动方向夹角范围，map中第一个参数为载机距目标的距离范围，第二个参数为此范围的角度门，map为null或某一个value为null表示不启用，如{"[[0,100]]": null, "[[100,inf]]": "[[0,30]]"}可表示一个100格内全向、100格外尾追的红外弹 | Map<RVP_Range<Float>,RVP_Range<Float>> | null |
| guidanceAngleGate | 导弹跟踪时角度门，即不同距离下弹药导引头角度与制导实体的运动方向夹角范围，map中第一个参数为载机距目标的距离范围，第二个参数为此范围的角度门，map为null或某一个value为null表示不启用，如{"[[0,100]]": null, "[[100,inf]]": "[[0,75],[105,180]]"}可表示一个100格内烧穿，100格外会吃39机动的雷达弹 | Map<RVP_Range<Float>,RVP_Range<Float>> | null |
| angleGateLockOutTick | 角度门脱锁所需tick，即不满足角度门时，指定tick后导弹才脱锁 | int | 20 |
| activeRadarActivationRange | 主动类导引头开机距离,适用于ARH,AIR,ARM | int | 256 |
| enableInertialGuidance | 允许惯性制导，导弹脱锁后仍然会朝向记忆的最后一个目标点惯性前进 | boolean | false |
| terminalGuidance | 末端制导数据，null为不启用 | RVP_TerminalGuidanceData | null |



若要给RVP_EnumGuidanceType中的每个制导方式提供扩展的data，用于区分不同制导类型弹药的运动逻辑，可在代码中分别继承RVP_GuidanceData基类并加入所需字段，实际使用的是RVP_GuidanceData的子类，扩展的data将应用在各个制导方式单独的算法中

TV、HITL_TV、HITL_CLOS_TV模式下定义了RVP_GuidanceDataHITL数据模型，继承自RVP_GuidanceData



## RVP_InterferenceData

| RVP_InterferenceData | 解释                                                         | 类型 | 默认值 |
| -------------------- | ------------------------------------------------------------ | ---- | ------ |
| SeekerJamLimit       | 导弹导引头视场内的极限干扰物数量，超过这个数量后导弹会脱锁并转向最近的干扰物 | int  | 16     |
|                      |                                                              |      |        |









## RVP_GuidanceDataHITL

| RVP_GuidanceDataHITL特有字段 | 解释 | 类型 | 默认值 |
| ---------------------------- | ------------------------------------------------------------ | ------------ | -------- |
| hITLMaxTurnDegPerTick | 导引头每tick转动角度，参考武器站的方向机 | int | 2 |
| signalSource | 导弹制导信号源，FIBER(光纤)/RADIO(无线电)，无线电会被方块遮挡从而干扰制导 | String | RADIO |
| hITLMaxControlDist | 人在回路的最大控制距离 | int | 600 |
| hITLMaxControlTick | 人在回路的最大控制Tick | int | 200 |
| hITLMaxLookOffset | 导引头最大旋转角度，即HITL_TV模式下鼠标能拖动选点的最大位移角度 | int | 30 |
| hITLVideoModes | 可用画面模式，包括COLOR/MONO/THERMAL | List<String> | ["MONO"] |

GPS模式下定义了RVP_GuidanceDataGPS数据模型，继承自RVP_GuidanceData

## RVP_GuidanceDataGPS

| RVP_GuidanceDataGPS特有字段 | 解释                                                         | 类型  | 默认值 |
| --------------------------- | ------------------------------------------------------------ | ----- | ------ |
| gpsSpreadRadius             | GPS弹药打击散布半径（格），使用正态分布<br />即目前的gps_cep的逻辑 | float | 0      |



## RVP_GuidanceDataARM

| RVP_GuidanceDataARM特有字段 | 解释                                                         | 类型  | 默认值 |
| --------------------------- | ------------------------------------------------------------ | ----- | ------ |
| radiationPulseMemoryTick    | 导弹对雷达辐射脉冲的短时记忆 tick。即便辐射源瞬间停机或脉冲间歇，仍允许导弹在该时长内继续认为“最近一次辐射源有效”。用于避免 ARM 因脉冲雷达间歇发射而瞬时丢失引导。 | int   | 30     |
| armMemoryTick               | 导弹在彻底失去辐射源后，对最后一个有效辐射源位置/目标的持续记忆 tick。该阶段允许导弹继续朝最后记忆点飞行并尝试重新捕获。 | int   | 60     |
| armLockedEmitterBonus       | 对“已经被火控锁定/预选的辐射源”附加的优先级加权系数。值越高，ARM 越倾向继续攻击当前主目标，而不是被视场内新的辐射源轻易抢走。 | float | 1      |



## RVP_TerminalGuidanceData

末端制导数据模型

| RVP_TerminalGuidanceData字段 | 解释 | 类型 | 默认值 |
| ---------------------------- | ------------------------------------------------------------ | -------------------------------------- | ------ |
|  | 主动类导引头开机距离,适用于ARH,AIR,ARM | int | 256 |
| maxLockAngle | 导弹导引头视场，在开启导引头扫描阶段适用，即开启导引头但未锁定目标的阶段。<br />在范围内的目标会被捕获<br />如果是红外弹且拥有头瞄，其还具有离轴搜索能力，相当于整合了原seek的fov参数<br />导引头头瞄离轴时，不要与武器站旋转角度进行角度叠加（这块逻辑已经写完，但需要迁移） | int | 5 |
| guidanceTargetDistanceRange | 导弹跟踪时与制导目标点/记忆点的距离范围(格)，null表示不进行判断 | RVP_Range<Float> | null |
| guidanceType | 制导类型NONE/ATV/AIR/ARH/ARM | RVP_EnumGuidanceType | NONE |
| guidanceStartTick | 多少时间后切换为末端制导，null不启用 | Integer | null |
| guidanceStartDist | 距目标多少距离后切换为末端制导，null不启用 | Float | null |
| guidanceStartHorizontalDist | 距目标水平方向多少距离后切换为末端制导，null不启用 | Float | null |
| guidanceAltitudeRange | 导弹跟踪时与制导目标点/记忆点离地高度下限(格)，null表示不进行判断，如参数为[[20,100]]时，只有离地高度高于20且低于100的才会被跟踪，参数为[[inf,10]]表示可跟踪离地10格以内目标的对地弹，参数为[[30,inf]]表示可跟踪高于地面30格目标的对空弹 | RVP_Range<Float> | null |
| maxGuidanceAngle | 导弹跟踪时导引头最大锁定角度 | int | 60 |
| scanIntervalTick | 导弹跟踪时导引头扫描间隔tick，适用于主动类型导弹，null表示不主动扫描 | Integer | null |
| predictTargetPos | 是否启用比例制导 | boolean | false |
| topAttackHeight | 攻顶最大高度，null为不启用攻顶，可填负数（适用于潜射制导武器） | Float | null |
| guidanceAngleGate | 导弹跟踪时角度门，即不同距离下弹药导引头角度与制导实体的运动方向夹角范围，map中第一个参数为载机距目标的距离范围，第二个参数为此范围的角度门，map为null或某一个value为null表示不启用，如{"[[0,100]]": null, "[[100,inf]]": "[[0,75],[105,180]]"}可表示一个100格内烧穿，100格外会吃39机动的雷达弹 | Map<RVP_Range<Float>,RVP_Range<Float>> | null |
| angleGateLockOutTick | 角度门脱锁所需tick，即不满足角度门时，指定tick后导弹才脱锁 | int | 20 |
| enableInertialGuidance | 允许惯性制导，导弹脱锁后仍然会朝向记忆的最后一个目标点惯性前进 | boolean | false |




# RVP_FireData

开火侧数据模型

| RVP_FireData公用字段 | 解释 | 类型 | 默认值 |
| -------------------- | ------------------------------------------------------------ | ------- | ------ |
|                        |                                                              |                            |           |
| heatCount              | 单次成功开火增加的热量。仅当 `maxHeatCount > 0` 时生效。对应 JSON 写法为 `heat_count`。 | int                        | 0         |
| maxHeatCount           | 最大热量上限。大于 0 时启用过热机制；当前热量达到或超过该值后禁止继续开火，直到冷却到上限以下。对应 JSON 写法为 `max_heat_count`。 | int                        | 0         |
| overheatExtraHeat      | 达到过热上限时额外追加的惩罚热量，用于模拟 MCHR 中“过热后需要更久冷却”的锁死区。对应 JSON 写法为 `overheat_extra_heat`。 | int                        | 30        |
| fireMode | 开火模式，支持FULL_AUTO/SEMI_AUTO/BURST/CHARGE/MINIGUN/RAILGUN | RVP_EnumFireMode | FULL_AUTO |
| spread | 发射角度散布，为null时使用RVP_WeaponData父类的spread | Float | null      |
| burstCount | 点射模式每轮发射数量 | int | 3 |
| burstDelay | 点射模式轮间间隔毫秒 | int | 120 |
| chargeTick | 蓄力时间或转管爬升时间tick | int | 10 |
| chargeDecayTick | 蓄力时间或转管衰减时间tick | int | 2 |
| chargePowerScale | 蓄力伤害/初速放大倍率 | float | 1.0 |
| canisterCount | 单次开火子弹丸数量 | int | 1 |
| canisterType | 散布类型，0为位置散布，1为角度散布，2为角度散布并沿弹道前向错位以模拟时间散布 | int | 0 |
| canisterDistribution | 多弹丸分布方式 | RVP_EnumSpreadDistribution | UNIFORM |
| canisterShape | 多弹丸形状 | RVP_EnumSpreadShape | CIRCLE |
| canisterDiff | 多弹丸散布半径 | float | 0.3 |
| canisterBurstDelayTime | 子弹丸分批抛撒的延时tick | int | 0 |
| canisterBurstCount | 子弹丸分几批抛撒 | int | 1 |
| maxOffAxisShootAngle | 弹药离轴发射的最大角度，对于带有炮塔的载具而言，轴为炮塔指向位置，对于飞机和直升机而言，轴为载机指向位置，null为允许任何角度的离轴发射，如参数为20时，只允许离轴20度角发射 | Integer | null |
| requireLock | 是否需要锁定才能发射 | boolean | false |
|                        |                                                              |                            |           |
|                        |                                                              |                            |           |



# RVP_ProjectileData

弹药运动学数据模型

| RVP_ProjectileData公用字段 | 解释 | 类型 | 默认值 |
| -------------------------- | ------------------------------------------------------------ | ----------------------------- | ------ |
| velocity | 弹体初速/飞行速度，为null时使用RVP_WeaponData父类的velocity | Float | null |
| gravity | 空中每tick竖直加速度 | float | 0 |
| gravityInWater | 水中每tick竖直加速度 | float | 0 |
| drag | 空中水平阻力 | float | 0 |
| dragInWater | 水中水平阻力 | float | 0 |
| inheritVehicleVelocity | 是否继承载具速度 | boolean | false |
| constantSpeed | 是否保持恒定速度 | boolean | false |
| rotateToMotion | 是否让实体朝向跟随速度方向 | boolean | true |
| maxSpeed | 最大速度限制，`0`表示不限制 | float | 0 |
| minSpeed | 最小速度限制，`0`表示不限制 | float | 0 |
| turningFactor | 弹药过载，不为null时使用旧版MCHR的过载算法（0-1之间，推荐值0.05-0.2之间，1为无过载，弹药可锐角机动），该值为null时，启动max_g参数相关的过载算法。在map中，key为tick，value为过载参数，如{"[[0,20],[100,inf]]": 0.05, "[[20,100]]": 0.15}，表示在射出后20tick内和100tick之后过载值为0.05，其余时间内过载值为0.15 | Map<RVP_Range<Integer>,Float> | null |
| hasRocketEngine | 是否启用火箭发动机推力模型 | boolean | false |
| mass | 弹体质量 | float | 0 |
| dragCoefficient | 二次方阻力模型系数，每 tick下 Δv -= drag_coefficient * \|v\|² | float | 0 |
| thrust | 发动机推力 | float | 0 |
| motorBurnTime | 发动机燃烧时间tick | float | 0 |
| secondPulse | 是否启用双脉冲发动机 | boolean | false |
| secondPulseTriggerSpeed | 二次点火速度阈值，低于该速度启动第二脉冲 | float | 0 |
| secondPulseTriggerDistance | 二次点火距离阈值，与目标距离低于该值启动第二脉冲 | float | 0 |
| secondPulseThrust | 二次点火推力 | float0 | 0      |
| secondPulseBurnTime | 二次点火燃烧时间 | float | 0 |
| ignitionDelayTick | 点火延迟tick | int | 0      |
| altitudeDragFactor | 高空空气阻力倍率，该值为null时不启用，map中的key为弹体高度（为y轴坐标，不是离地高度），value为水平阻力倍率，该值为null时默认阻力为1.0。如{"[[inf,300]]": 0.98, "[[300,500]]": 1.0, "[[500,1000]]": 1.02, "[[1000,inf]]": 1.05}，表示不同高度下的阻力倍率（记为f），则最终导弹的阻力为drag * f | Map<RVP_Range<Float>,Float> | null |



# RVP_MiscData

杂项数据模型

| RVP_MiscData公用字段 | 解释 | 类型 | 默认值 |
| -------------------- | --------------------------------------------- | ---------------------------- | ----------------------------------------- |
| missileNameOnHud | 不同距离下在屏幕hud上显示的字符串，null不显示 | Map<RVP_Range<Float>,String> | {"[[0,500]]": "MSL", "[[500,inf]]": null} |
| missileNameOnRadar | 不同距离下在雷达hud上显示的字符串，null不显示 | Map<RVP_Range<Float>,String> | {"[[20,inf]]": "MSL"} |
| signalIntensityFactorOnRadar | 不同距离下弹药的雷达信号强度倍率，null不显示<br /><br />signature_size删除，转用这个替代signature_size | Map<RVP_Range<Float>,Float> | {"[[0,inf]]": "1.0"} |
| ArtilleryMap | 使用炮兵地图替代战术地图 | boolean | false |



# 附录

## 制导方式

**干扰措施，告警措施暂时没做，被XX克制仅作为暂时记录**

人工指令线制导(MCLOS)：需使用键盘上下左右箭头操作控制导弹舵面，类似战雷的AGM12B

被APS/烟雾弹/光电干扰机所克制

 

半自动指令线制导/拖线（SACLOS）：鼠标移动控制导弹舵面，体现为bf4的拖式飞弹

被APS/烟雾弹/光电干扰机所克制

 

激光驾束制导（LOSBR）：鼠标移动控制弹药前进方向，瞄准目标时只需要将准心对准目标即可，类似战雷里面的炮射导弹，不需要像人工指令线制导一样要观察导弹的尾焰

激光接收器置于导弹上，导弹发射时激光器对着目标指示照射，发射后的导弹在激光波束内飞行。当导弹偏离激光波束轴线时，接收器敏感偏离的大小和方位并形成误差信号，按导引规律形成控制指令来修正导弹的飞行。

被APS/DIRCM克制
会触发**激光告警**

 

激光制导（LH）：在瞄准的位置生成激光点，保持锁定，弹药会自动向激光点前进，弹药与激光点之间不能有方块间隔，

被APS/烟雾弹/DIRCM所克制
会触发**激光告警**

 

半主动激光制导（SALH）：在瞄准的位置生成激光点，保持锁定，弹药会自动向激光点前进。可在一定范围内自动锁定实体，并可与激光吊舱联动，实现自动追踪实体目标，弹药与激光点之间不能有方块间隔

被APS/烟雾弹/DIRCM/光电干扰机所克制

 

电视寻的/图像制导（TV）：

选择该武器时，自动切换至电视导引头视角（没有），选择地面或实体目标后，发射导弹，导弹自动向目标前进

如果存在地形障碍遮挡信号，则会导致导引头与载机之间的数据传输中断。直至通讯恢复前，载机都将无法接收图像，相应地导弹也无法接收指令。不过，由于导弹实际制导流程仍由导引头进行，因此即使与载机的数据传输中断，导弹仍能顺利攻击已锁定的目标。这意味着，在必要情况下载机在成功锁定目标后可以安全地利用地形障碍进行隐蔽，避免遭到敌方防空系统还击

被APS/烟雾弹/DIRCM所克制

 

人在回路电视制导（HITL_TV）：

选择该武器时，自动切换至电视导引头视角，允许玩家在弹药飞行路径中重新锁定目标，体现为bf2的TV弹

游戏中具备“人在回路”能力的系统一次仅能引导一枚弹药 ，因此我们将修正能力限制为只有最后离开挂架的导弹或炸弹才能重新指定目标

被APS/烟雾弹/DIRCM所克制

 

人在回路指令线电视制导（HITL_CLOS_TV）：

选择该武器时，自动切换至电视导引头视角，允许玩家在弹药飞行路径中鼠标移动控制导弹舵面，体现为bf4的TV弹

被APS/DIRCM所克制

 

主动电视制导（ATV）：

无需锁定即可发射，导弹自动依据图像寻找敌军目标并制导

被APS/热焰弹/烟雾弹/DIRCM所克制

 

红外制导（IR）：

锁定热源并导引向目标前进

被APS/热焰弹/红外线烟雾/DIRCM所克制

触发**导弹逼近告警**

 

主动红外制导（AIR）：

无需锁定即可发射，导弹自动寻找热源并制导

被APS/热焰弹/红外线烟雾/DIRCM所克制

 触发**导弹逼近告警**



半主动雷达制导（SARH）：

需使用机载雷达保持对目标的锁定

被APS/箔条/ECM所克制
触发**雷达告警**

 

主动雷达制导（ARH）：

导弹将使用自带的雷达导引头，自动追踪敌军

被APS/箔条/ECM所克制

 触发**雷达告警**



卫星制导（GPS）:

在视距内点击地面生成GPS目标点，也可在小地图上选择GPS目标点

被APS/ECM所克制

 

反辐射导弹/被动雷达制导（ARM）:

接收目标主动发出的雷达信号并进行制导

被APS/ECM所克制（被ECM克制体现为：若反辐射导弹视场内出现ECM目标，其立刻成为反辐射导弹打击最高优先级目标，但是同时大幅度提升反辐射导弹的CEP，使其有大概率打不准）




分段制导方式介绍：

初段：所有制导类型或无制导弹药

末端：ATV、AIR、ARH、ARM



## RVP_Range

RVP_Range<T extends Comparable<T>> 此数据结构给可比较型数据提供了闭区间范围表示，以及它们的并集

json结构中

[[inf,inf]] 表示负无穷到正无穷，inf符号在区间的左边表示负无穷，在区间右边表示正无穷

[[inf,100],[200,500]] 表示负无穷到100并200到500

[[-10,600],[inf,500]] 表示-10到600并负无穷到500，实际为负无穷到600

若出现[[10,-10]]，即区间前面的数字大于后面的数字，为非法表示，需抛出异常

此类需提供 contains(T value) 成员方法，判断一个数值是否位于区间中
