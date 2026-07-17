注：表格中的字段名称为java类中使用的帕斯卡命名法，在json中需使用全小写+下划线连接的格式

# RVP_GuidanceData

弹药制导数据模型

| RVP_GuidanceData公用字段    | 解释                                                         | 类型                                   | 默认值 |
| :-------------------------- | ------------------------------------------------------------ | -------------------------------------- | ------ |
| guidanceType                | 制导类型NONE/MCLOS/SACLOS/LOSBR/LH/SALH/TV/HITL_TV/HITL_CLOS_TV/ATV/IR/AIR/SARH/ARH/GPS/ARM | RVP_EnumGuidanceType                   | NONE   |
| guidanceTickRange           | 制导时间范围，null表示立即开始，永不结束                     | RVP_Range<Integer>                     | null   |
| guidanceTargetDistanceRange | 导弹跟踪时与制导目标点/记忆点的距离范围(格)，null表示不进行判断 | RVP_Range<Float>                       | null   |
| guidanceAltitudeRange       | 导弹跟踪时与制导目标点/记忆点离地高度下限(格)，null表示不进行判断，如参数为[[20,100]]时，只有离地高度高于20且低于100的才会被跟踪，参数为[[inf,10]]表示可跟踪离地10格以内目标的对地弹，参数为[[30,inf]]表示可跟踪高于地面30格目标的对空弹 | RVP_Range<Float>                       | null   |
| lockTargetDistanceRange     | 载具火控锁定时与制导目标点/记忆点的距离范围，null表示不启用  | RVP_Range<Float>                       | null   |
| lockAltitudeRange           | 载具火控锁定时与制导目标点/记忆点离地高度下限(格)，null表示不进行判断，如参数为[[20,100]]时，只有离地高度高于20且低于100的才会被锁定，参数为[[inf,10]]表示可锁定离地10格以内目标的对地弹，参数为[[30,inf]]表示可锁定高于地面30格目标的对空弹 | RVP_Range<Float>                       | null   |
| maxLockAngle                | 载具火控锁定时瞄准线与目标的最大角度                         | int                                    | 30     |
| maxGuidanceAngle            | 导弹跟踪时导引头最大锁定角度                                 | int                                    | 60     |
| scanIntervalTick            | 导弹跟踪时导引头扫描间隔tick，适用于主动类型导弹，null表示不主动扫描 | Integer                                | null   |
| maxHMDLockAngle             | 头瞄模式下载具火控锁定时的最大角度                           | int                                    | 10     |
| maxHMDOffAxisLockAngle      | 头瞄模式下载具火控锁定时的离轴最大角度                       | int                                    | 60     |
| predictTargetPos            | 是否启用比例制导                                             | boolean                                | false  |
| topAttackHeight             | 攻顶最大高度，null为不启用攻顶，可填负数（适用于潜射制导武器） | Float                                  | null   |
| lockAngleGate               | 载具火控锁定时角度门，即不同距离下载具瞄准线与制导实体的运动方向夹角范围，map中第一个参数为载机距目标的距离范围，第二个参数为此范围的角度门，map为null或某一个value为null表示不启用，如{"[[0,100]]": null, "[[100,inf]]": "[[0,30]]"}可表示一个100格内全向、100格外尾追的红外弹 | Map<RVP_Range<Float>,RVP_Range<Float>> | null   |
| guidanceAngleGate           | 导弹跟踪时角度门，即不同距离下弹药导引头角度与制导实体的运动方向夹角范围，map中第一个参数为载机距目标的距离范围，第二个参数为此范围的角度门，map为null或某一个value为null表示不启用，如{"[[0,100]]": null, "[[100,inf]]": "[[0,75],[105,180]]"}可表示一个100格内烧穿，100格外会吃39机动的雷达弹 | Map<RVP_Range<Float>,RVP_Range<Float>> | null   |
| angleGateLockOutTick        | 角度门脱锁所需tick，即不满足角度门时，指定tick后导弹才脱锁   | int                                    | 20     |
| signalSource                | 导弹制导信号源，FIBER(光纤)/RADIO(无线电)，无线电会被方块遮挡从而干扰制导 | String                                 | RADIO  |
| enableInertialGuidance      | 允许惯性制导，导弹脱锁后仍然会朝向记忆的最后一个目标点惯性前进 | boolean                                | false  |
| terminalGuidance            | 末端制导数据，null为不启用                                   | RVP_TerminalGuidanceData               | null   |



若要给RVP_EnumGuidanceType中的每个制导方式提供扩展的data，用于区分不同制导类型弹药的运动逻辑，可在代码中分别扩展RVP_GuidanceData并加入所需字段

如TV、HITL_TV、HITL_CLOS_TV模式下定义了RVP_GuidanceDataHITL数据模型，继承自RVP_GuidanceData

## RVP_GuidanceDataHITL

| RVP_GuidanceDataHITL特有字段 | 解释                                                         | 类型         | 默认值   |
| ---------------------------- | ------------------------------------------------------------ | ------------ | -------- |
| hITLMaxControlDist           | 人在回路的最大控制距离                                       | int          | 600      |
| hITLMaxControlTick           | 人在回路的最大控制Tick                                       | int          | 200      |
| hITLMaxLookOffset            | 导引头最大旋转角度，即HITL_TV模式下鼠标能拖动选点的最大位移角度 | int          | 30       |
| hITLVideoModes               | 可用画面模式，包括COLOR/MONO/THERMAL                         | List<String> | ["MONO"] |



## RVP_TerminalGuidanceData

末端制导数据模型

| RVP_TerminalGuidanceData字段 | 解释                                                         | 类型                                   | 默认值 |
| ---------------------------- | ------------------------------------------------------------ | -------------------------------------- | ------ |
| guidanceType                 | 制导类型NONE/ATV/AIR/ARH                                     | RVP_EnumGuidanceType                   | NONE   |
| guidanceStartTick            | 多少时间后切换为末端制导，null不启用                         | Integer                                | null   |
| guidanceStartDist            | 距目标多少距离后切换为末端制导，null不启用                   | Float                                  | null   |
| guidanceAltitudeRange        | 导弹跟踪时与制导目标点/记忆点离地高度下限(格)，null表示不进行判断，如参数为[[20,100]]时，只有离地高度高于20且低于100的才会被跟踪，参数为[[inf,10]]表示可跟踪离地10格以内目标的对地弹，参数为[[30,inf]]表示可跟踪高于地面30格目标的对空弹 | RVP_Range<Float>                       | null   |
| maxGuidanceAngle             | 导弹跟踪时导引头最大锁定角度                                 | int                                    | 60     |
| scanIntervalTick             | 导弹跟踪时导引头扫描间隔tick，适用于主动类型导弹，null表示不主动扫描 | Integer                                | null   |
| predictTargetPos             | 是否启用比例制导                                             | boolean                                | false  |
| topAttackHeight              | 攻顶最大高度，null为不启用攻顶，可填负数（适用于潜射制导武器） | Float                                  | null   |
| guidanceAngleGate            | 导弹跟踪时角度门，即不同距离下弹药导引头角度与制导实体的运动方向夹角范围，map中第一个参数为载机距目标的距离范围，第二个参数为此范围的角度门，map为null或某一个value为null表示不启用，如{"[[0,100]]": null, "[[100,inf]]": "[[0,75],[105,180]]"}可表示一个100格内烧穿，100格外会吃39机动的雷达弹 | Map<RVP_Range<Float>,RVP_Range<Float>> | null   |
| angleGateLockOutTick         | 角度门脱锁所需tick，即不满足角度门时，指定tick后导弹才脱锁   | int                                    | 20     |
| enableInertialGuidance       | 允许惯性制导，导弹脱锁后仍然会朝向记忆的最后一个目标点惯性前进 | boolean                                | false  |



# RVP_ShootData

开火侧数据模型

| RVP_ShootData公用字段 | 解释                                                         | 类型    | 默认值 |
| --------------------- | ------------------------------------------------------------ | ------- | ------ |
| maxOffAxisShootAngle  | 弹药离轴发射的最大角度，对于带有炮塔的载具而言，轴为炮塔指向位置，对于飞机和直升机而言，轴为载机指向位置，null为允许任何角度的离轴发射，如参数为20时，只允许离轴20度角发射 | Integer | null   |
| requireLock           | 是否需要锁定才能发射                                         | boolean | false  |



# RVP_ProjectileData

弹药运动学数据模型

| RVP_ProjectileData公用字段 | 解释                                                         | 类型                          | 默认值 |
| -------------------------- | ------------------------------------------------------------ | ----------------------------- | ------ |
| turningFactor              | 弹药过载，不为null时使用旧版MCHR的过载算法（0-1之间，推荐值0.05-0.2之间，1为无过载，弹药可锐角机动），该值为null时，启动max_g参数相关的过载算法。在map中，key为tick，value为过载参数，如{"[[0,20],[100,inf]]": 0.05, "[[20,100]]": 0.15}，表示在射出后20tick内和100tick之后过载值为0.05，其余时间内过载值为0.15 | Map<RVP_Range<Integer>,Float> | null   |



# RVP_MiscData

杂项数据模型

| RVP_MiscData公用字段 | 解释                                          | 类型                         | 默认值                                    |
| -------------------- | --------------------------------------------- | ---------------------------- | ----------------------------------------- |
| missileNameOnHud     | 不同距离下在屏幕hud上显示的字符串，null不显示 | Map<RVP_Range<Float>,String> | {"[[0,500]]": "MSL", "[[500,inf]]": null} |
| missileNameOnRadar   | 不同距离下在雷达hud上显示的字符串，null不显示 | Map<RVP_Range<Float>,String> | {"[[20,inf]]": "MSL"}                     |



# 附录

### RVP_Range

RVP_Range<T extends Comparable<T>> 此数据结构给可比较型数据提供了闭区间范围表示，以及它们的并集

json结构中

[[inf,inf]] 表示负无穷到正无穷，inf符号在区间的左边表示负无穷，在区间右边表示正无穷

[[inf,100],[200,500]] 表示负无穷到100并200到500

[[-10,600],[inf,500]] 表示-10到600并负无穷到500，实际为负无穷到600

若出现[[10,-10]]，即区间前面的数字大于后面的数字，为非法表示，需抛出异常

此类需提供 contains(T value) 成员方法，判断一个数值是否位于区间中