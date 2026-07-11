# RVP 冷发射兼容说明

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

## 与旧行为的区别

旧的 `RVP` 仅支持 `projectile_data.ignition_delay_tick`，没有真正读取本体 `WeaponUnit` 的 `cold_launch_time_tick / cold_launch_velocity`。

现在改完后：

- 可以直接复用本体弹舱/挂架冷发射参数
- 子挂架、可变挂架会按实际发射位读取自己的冷发射配置
- 客户端会同步冷发射参数，不再只在服务端生效
