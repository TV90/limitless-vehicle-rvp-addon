# 空爆白磷弹示例

- `airburst_white_phosphorus_bomb.json` 放到载具包 `data/rvp/weapons/`，作为近地引信母弹。
- `white_phosphorus_pellet.json` 同样放到 `data/rvp/weapons/`，资源 ID 必须与母弹的 `weapon_id` 一致。
- 母弹不配置 `visual_effect_data`，只保留本体 `VehicleExplosion`；子体不启用 `explosion_data`。
- `wind_data.direction_mode: parent_facing_reverse` 在释放瞬间读取母弹当前旋转朝向并取反，不使用母弹最初发射方向。
- 子体配置正数 `deployment_horizontal_half_life_ticks` 后，圆锥/母弹继承形成的初始 X/Z 按半衰期衰减，风偏作为独立速度贡献随后叠加；0 保持旧总速度链路。
- 母弹 payload 可用 `inherit_parent_horizontal_velocity: true` 只继承母弹 X/Z，并由 `velocity_scale` 控制继承比例，不缩放圆锥 `launch_speed`。
- `wind_data.turbulence` 与 `turbulence_frequency` 分别控制服务端权威的平滑游移幅度和转向频率；客户端不另算弹道。
- 尾迹每 Tick 只保留上一主体位置，不沿单 Tick 运动段插值补线；当前 schema 不再包含 `trail_spacing`。
- 尾迹出生尺寸继承对应主体经 `body_flicker` 计算后的实际尺寸；当前 schema 不再包含独立的 `trail_start_scale`。
- 白磷粒子数据 ID 保持为 `rvp:white_phosphorus`，由非 JSON 粒子 Provider 直接绑定模组内置资源 `assets/ywzj_rvp/textures/nuclear/particle_base.png`。数据 ID 命名空间与贴图资产命名空间相互独立；白磷相关资产统一位于 `assets/ywzj_rvp`，不需要粒子 JSON、图集追加文件、复制贴图或载具结构模型。
