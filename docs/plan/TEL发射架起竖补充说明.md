# TEL发射架起竖补充说明

> 日期：2026-07-14
> 范围：补充说明 TEL 发射架在仅需“平放/起竖”时的最简骨骼方案，以及骨骼旋转速度如何与展开/收回时间保持一致。

## 1. 最简骨骼方案

如果某辆 TEL：

- 不需要炮塔水平旋转
- 不需要独立底座
- 只需要“平放 -> 起竖 -> 发射 -> 收回”

那么结构模型可以只保留一个真正负责起竖的骨骼：

```text
vehicle_root
└── launcher_pitch
    └── 发射筒 / 导轨 / 导弹挂点
```

说明：

- `launcher_pitch` 是唯一需要在逻辑层旋转的骨骼；
- `launcher_pitch.pivot` 必须放在真实铰接点；
- 发射架相关 OBB、导弹挂点、发射点都应挂在 `launcher_pitch` 这一支下面；
- 这样在起竖时，模型、碰撞箱、发射轴会一起变化。

如果后续有些载具还需要底座层或左右旋转，再拆成：

```text
launcher_base
└── launcher_pitch
    └── launcher_rail
```

但对于固定朝前的 TEL，第一版不必强制做三层。

## 2. 为什么不能只做动画

如果只是让客户端动画看起来起竖，而没有在逻辑层真正修改对应结构骨骼的 `rotation`，就会出现：

- 模型看起来起竖了，但发射轴还是平射；
- 模型起竖了，但碰撞箱还是平着；
- 外观与射击判定不一致。

因此起竖功能的关键不是“有动画”，而是：

> 让 `launcher_pitch` 在逻辑层真正参与结构变换。

## 3. 旋转速度如何与展开/收回速度一致

这一点不建议再单独配置一套“骨骼转速”，否则很容易出现：

- 动画已经播完，但逻辑骨骼还没转到位；
- 逻辑已经允许发射，但模型还没完全展开；
- 碰撞箱和发射轴切换时机与外观不一致。

推荐规则：

> 骨骼旋转进度直接复用 Launcher Deploy 状态机进度。

即：

- `DEPLOYING` 阶段  
  `progress = deployTick / deploy_time_tick`
- `RETRACTING` 阶段  
  `progress = retractTick / retract_time_tick`

再用这个进度去插值当前俯仰角：

```text
currentPitch = lerp(stowed_pitch, deployed_pitch, progress)
```

收回时则反向插值：

```text
currentPitch = lerp(deployed_pitch, stowed_pitch, progress)
```

这样：

1. 展开耗时 40 tick，骨骼就一定在 40 tick 内从平放转到目标角度；
2. 收回耗时 20 tick，骨骼就一定在 20 tick 内回到初始角度；
3. 发射门禁、碰撞箱姿态、发射轴方向、外观动画都绑定在同一套时间轴上。

## 4. 第一版建议参数

第一版建议只配这几个参数：

```json
"rvp_launcher_deploy": {
  "enable": true,
  "pitch_group": "launcher_pitch",
  "stowed_pitch": 0.0,
  "deployed_pitch": 88.0,
  "deploy_time_tick": 40,
  "retract_time_tick": 20
}
```

不建议第一版加入：

- `pitch_speed_deg_per_tick`
- 单独的逻辑旋转曲线
- 与动画时长脱钩的独立骨骼速度

原因很简单：第一版应先保证“看见的、打出来的、撞到的”是同一套姿态。

## 5. 实现约束总结

对“固定朝前、只做起竖”的 TEL，推荐约束如下：

1. 只保留一个 `launcher_pitch`
2. 所有发射点和相关 OBB 都挂在它下面
3. 起竖角由状态机进度直接驱动
4. 不单独配置骨骼转速
5. 发射门禁只在 `OPEN` 状态放行

这套方案最简单，也最不容易出现模型、碰撞箱、发射轴不同步的问题。
