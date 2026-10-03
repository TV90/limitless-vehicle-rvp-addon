# RVP 冷发射兼容说明

> 当前实现状态：已实现。本文同时记录 2026-10-04 修复的 GPS PRESET 冷发射制导接管规则。

本文档说明 `RVP` 侧对本体 `WeaponUnit` 冷发射字段的兼容方式。

## 适用范围

- 仅 `rvp:missile`
- 不影响 `rvp:bomb`、`rvp:rocket`、`rvp:machinegun`
- 不需要修改 `ywzj_vehicle` 本体源码

## 字段位置

这两个字段写在载具 `WeaponUnit` 配置里，而不是武器 JSON：

- `cold_launch_time_tick`
- `cold_launch_velocity`

示例：

```json
{
  "id": "inner_agm_left",
  "type": "ywzj_vehicle:weapon",
  "cold_launch_time_tick": 12,
  "cold_launch_velocity": [0.0, -1.2, 0.0],
  "weapons": [
    "rvp:some_missile"
  ]
}
```

## 字段语义

| 字段 | 说明 |
| --- | --- |
| `cold_launch_time_tick` | 冷发射持续时间，单位 tick。导弹在这段时间内不会点火。 |
| `cold_launch_velocity` | 冷发射速度向量，使用 `WeaponUnit` 本地坐标：`x` 右、`y` 上、`z` 前。典型弹舱下抛可写成 `[0, -1, 0]`。 |

## RVP 运行规则

1. `RVP` 读取“实际发射的那个 `WeaponUnit`”上的 `cold_launch_*`。
2. 冷发射阶段内，导弹每 tick 继承载具当前速度，再叠加 `cold_launch_velocity` 对应的本地轴向速度。
3. 若同时配置了 `projectile_data.ignition_delay_tick`，发动机实际点火时刻取：

```text
max(cold_launch_time_tick, ignition_delay_tick)
```

也就是：

- `cold_launch_time_tick` 阶段：按冷发射速度弹出
- 若 `ignition_delay_tick` 更长：冷发射结束后继续无推力滑出，直到延迟结束再点火

4. GPS 导弹启用 PRESET（`guidance_data.preset_cruise_altitude > 0`）时，若
   `cold_launch_time_tick >= ignition_delay_tick`，冷发射与点火延迟存在重叠窗口：
   - 当前 Tick 已成功计算出 PRESET 制导速度后，运动层保留该速度，不再用冷发射竖直速度覆盖；
   - 尚未获得有效目标或尚未写入制导速度时，仍使用原来的载具速度 + 冷发射速度；
   - 冷发射结束后进入正常发动机推进，推力沿接管后的弹体朝向施加。

   该规则只改变“冷发射运动覆盖制导结果”的冲突，不改变冷发射字段的本地坐标语义，也不按武器 ID
   分支。冷发射时长短于点火延迟的导弹继续沿用原有冷发射行为。

### 典型对照

| 弹体 | `cold_launch_time_tick` | `ignition_delay_tick` | 结果 |
| --- | ---: | ---: | --- |
| YJ-20（`052d_yj20`） | 20 | 20 | PRESET 制导在冷发射末段接管速度，避免发射后持续竖直上冲 |
| 9M723 伊斯坎德尔-M（`9k720_9m723`） | 10 | 20 | 冷发射先完成，之后的点火等待段按原逻辑接受制导速度 |

上述现象的根因不是 GPS 目标解析失败，而是冷发射运动阶段在同一 Tick 的制导计算之后覆盖了制导速度。
插件侧通过 `RVP_BaseBullet` 的 Tick 瞬态标记与 `RVP_ProjectileMotion` 的接管判定修复，未修改
`ywzj_vehicle` 本体。

## 与旧行为的区别

旧的 `RVP` 仅支持 `projectile_data.ignition_delay_tick`，没有真正读取本体 `WeaponUnit` 的 `cold_launch_time_tick / cold_launch_velocity`。

现在改完后：

- 可以直接复用本体弹舱/挂架冷发射参数
- 子挂架、可变挂架会按实际发射位读取自己的冷发射配置
- 客户端会同步冷发射参数，不再只在服务端生效
