# 双轴惯量与低动压机头限幅场景

固定初态，读取项目根 `limitless_vehicle/rvp/data/rvp/weapons/` 权威配置；不覆盖武器参数。

| 场景 | 武器 | 目的 |
| --- | --- | --- |
| `mica_low_static` | `ebrc_mica_ng` | 静止平台低速发射，约 63° 大离轴，观察机头建立攻角速度 |
| `mica_low_crossing` | `ebrc_mica_ng` | 目标 35 格/秒横穿，检查低速发射后的 PIP 与首次交会 |
| `mica_fast_crossing` | `ebrc_mica_ng` | 平台 80 格/秒，检查不同初速的 PIP |
| `mica_low_head_on` | `ebrc_mica_ng` | 目标以 30 格/秒迎头，检查接近时间变化 |
| `mica_em_low_static` | `rafale_mica_em` | 更低初速的大离轴发射 |
| `mica_em_low_crossing` | `rafale_mica_em` | 更低初速的横穿 PIP |
| `aim9m_low_crossing` | `f22a_aim9m` | 另一类制导武器的低速回归 |
| `aim9m_fast_crossing` | `f22a_aim9m` | 高初速及原制导门控下的回归 |

项目根使用仓库规定的 Java 17 环境：

```powershell
./gradlew projectileSimulation "-PprojectileScenario=src/test/resources/projectile-inertia-simulation" "-PprojectileOutput=build/projectile-inertia-simulation/after"
```

`RVP_ProjectileInertiaSimulationTest` 在默认 `build` 中自动读取全部 8 个场景，逐刻检查机头动压转角上限与真实角速度推进，并独立计算首次相对运动段最近距离，要求 <2 格。回放导出器仍保留一格目标停止球；它不会执行引信或伤害。物理条件、PIP 精度预算和前后对比见 [本轮实现与验证记录](../../../../docs/plan/RVP双轴惯量恢复与低动压机头限幅_20261103.md)。
