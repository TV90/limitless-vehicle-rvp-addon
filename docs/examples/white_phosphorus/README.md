# 空爆白磷弹示例

- `airburst_white_phosphorus_bomb.json` 放到载具包 `data/rvp/weapons/`，作为近地引信母弹。
- `white_phosphorus_pellet.json` 同样放到 `data/rvp/weapons/`，资源 ID 必须与母弹的 `weapon_id` 一致。
- 母弹不配置 `visual_effect_data`，只保留本体 `VehicleExplosion`；子体不启用 `explosion_data`。
- `wind_data.direction_mode: parent_facing_reverse` 在释放瞬间读取母弹当前旋转朝向并取反，不使用母弹最初发射方向。
- 白磷粒子数据 ID 保持为 `rvp:white_phosphorus`，由非 JSON 粒子 Provider 直接绑定模组内置资源 `assets/ywzj_rvp/textures/nuclear/particle_base.png`。数据 ID 命名空间与贴图资产命名空间相互独立；白磷相关资产统一位于 `assets/ywzj_rvp`，不需要粒子 JSON、图集追加文件、复制贴图或载具结构模型。
