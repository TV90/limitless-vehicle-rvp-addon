# 空爆白磷弹示例

- `airburst_white_phosphorus_bomb.json` 放到载具包 `data/rvp/weapons/`，作为近地引信母弹。
- `white_phosphorus_pellet.json` 同样放到 `data/rvp/weapons/`，资源 ID 必须与母弹的 `weapon_id` 一致。
- 母弹不配置 `visual_effect_data`，只保留本体 `VehicleExplosion`；子体不启用 `explosion_data`。
- `wind_data.direction_mode: parent_facing_reverse` 在释放瞬间读取母弹当前旋转朝向并取反，不使用母弹最初发射方向。
- 固定世界水平风向可写成 `north:<角度>`：北方 `-Z` 为 0°、顺时针为正，因此 `north:+90` 向东 `+X`，`north:-50` 为北偏西 50°。固定模式对首发弹体和子弹药都有效。
- 子体配置正数 `deployment_horizontal_half_life_ticks` 后，圆锥/母弹继承形成的初始 X/Z 按半衰期衰减，风偏作为独立速度贡献随后叠加；0 保持旧总速度链路。
- 母弹 payload 可用 `inherit_parent_horizontal_velocity: true` 只继承母弹 X/Z，并由 `velocity_scale` 控制继承比例，不缩放圆锥 `launch_speed`。
- 展开速度满意但下落过快时，可保持 `launch_speed` 和半角不变，用正 Y 的 `payloads_velocity` 抵消部分圆锥下坠分量；示例 `+0.30` 仍保证 72° 圆锥最外圈从生成开始向下。
- `wind_data.turbulence` 与 `turbulence_frequency` 分别控制服务端权威的平滑游移幅度和转向频率；客户端不另算弹道。
- 尾迹每 Tick 只保留上一主体位置，不沿单 Tick 运动段插值补线；当前 schema 不再包含 `trail_spacing`。
- 尾迹出生尺寸继承对应主体经 `body_flicker` 计算后的实际尺寸；当前 schema 不再包含独立的 `trail_start_scale`。
- `trail_lifetime_start_on_landing` 默认 false，尾迹按原行为从出生 Tick 立即消耗 `trail_lifetime_ticks`。设为 true 后，同一子体的尾迹在飞行阶段冻结年龄，子体落地、碰撞结束、被移除或客户端停止追踪后才统一开始计时；该模式不提高出生率，但会保留整段飞行尾迹并提高峰值粒子存量。
- 主体颜色在自身寿命内从 `body_color` 线性渐变到 `body_end_color`；示例使用橙色 `#FF8A1F` 到红色 `#FF2400`，再叠加 `body_flicker` 按配置间隔更新的随机尺寸差异形成红橙闪烁。未配置末端颜色时保持原有单色主体。
- `body_start_scale` 控制主体的较小出生尺寸，默认 0 表示关闭并直接使用本次 `body_flicker` 目标尺寸。正值会在主体寿命内沿平滑曲线长到目标，实际出生值不会超过目标；寿命为 1 Tick 时自动关闭生长，至少 2 Tick 才能看到变化。该字段不增加粒子数量，尾迹仍继承目标尺寸。
- `body_horizontal_flicker` 以格为单位，在 X、Z 两轴分别生成 `[-value, +value]` 的独立随机目标偏移；主体从上一实际偏移沿平滑曲线移动到目标，尾迹继承逐 Tick 的实际平滑位置。该视觉字段不改变 Y、服务端弹道或毁伤，静止判定仍使用权威弹体位置。
- `body_flicker_interval_ticks` 控制随机尺寸和 X/Z 目标点多久刷新一次，也就是水平偏移完成一次平滑移动所用的 Tick 数；示例 4 Tick 约每秒到达 5 个目标点。值为 1 时直接到达新目标。它不降低主体或尾迹生成频率，也不减慢单个主体内部的红橙年龄渐变。
- `body_sample_interval_ticks` 独立控制主体粒子多久生成一次；示例 1 Tick 约每秒生成 20 次。它不会降低近距离尾迹的逐 Tick 采样密度，也不改变 `body_flicker_interval_ticks` 的随机目标刷新节奏。
- 白磷粒子数据 ID 保持为 `rvp:white_phosphorus`，由非 JSON 粒子 Provider 直接绑定模组内置资源 `assets/ywzj_rvp/textures/nuclear/particle_base.png`。数据 ID 命名空间与贴图资产命名空间相互独立；白磷相关资产统一位于 `assets/ywzj_rvp`，不需要粒子 JSON、图集追加文件、复制贴图或载具结构模型。
