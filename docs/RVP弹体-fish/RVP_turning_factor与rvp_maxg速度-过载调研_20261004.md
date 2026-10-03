# RVP `turning_factor` 与 `rvp_maxg`：速度—可用过载调研

> 调研日期：2026-10-04  
> 范围：`limitless-vehicle-rvp-addon` 当前源码、`ywzj_vehicle_fish` 本体常量、当前载具包 `limitless_vehicle/rvp/data/rvp/weapons/`。  
> 结论先行：当前载具包实际仍以旧版 `turning_factor` 方向插值为主；`rvp_maxg` 已在代码中实现但当前载具包没有实际配置。两者不能把数值直接当作同一种“最大 G”理解。

## 1. 当前运行状态

### 1.1 数据包扫描结果

对 `limitless_vehicle/rvp/data/rvp/weapons/` 根目录 JSON 扫描，排除 `_backup/`：

| 项 | 当前结果 |
| --- | ---: |
| 有效武器 JSON 总数 | 152 |
| 配置 `turning_factor` 的 JSON 数 | 82 |
| 配置 `rvp_maxg` 的 JSON 数 | 0 |
| 配置 `rvp_aero_steering` 的 JSON 数 | 0 |
| 当前 `turning_factor` 的非默认值 | `0.08 / 0.10 / 0.12 / 0.15 / 0.20 / 0.24 / 0.28 / 1.0` |

因此，当前载具包没有启用新的气动转向开关，也没有用 `rvp_maxg` 覆盖任何一枚弹。代码中的气动模型是可选路径，不能根据方案文档中的“建议默认值”推断现网已经打开；当前权威默认值是 `RVP_ProjectileData.rvpAeroSteering = false`。

### 1.2 代码入口

| 位置 | 当前职责 |
| --- | --- |
| `RVP_TrajectorySteeringMath.applyTurningFactor` | 旧版逐 Tick 方向混合：`normalize((1-f)u + f d) × speed` |
| `RVP_BallisticTrajectoryMath.applyLegacyMaxGSteering` | 旧版 `rvp_maxg`：用速度变化弦长限制单 Tick 转角 |
| `RVP_BallisticTrajectoryMath.applyConfiguredSteering` | 关闭气动模型时选择 `rvp_maxg` 或 `turning_factor`；`rvp_maxg != null` 优先 |
| `RVP_AeroSteeringModel` | `rvp_aero_steering=true` 时，按设计 G、动压和转角上限统一求解 |
| `RVP_GuidanceRuntimeMath.applyResolvedAeroSteering` | 实体制导的收口；开关关闭时保留旧行为，开启时调用统一气动求解器 |
| `RVP_VirtualTrajectoryInputFactory` / 虚拟积分器 | 虚拟中段读取相同的 `turning_factor`、`rvp_maxg` 和气动限制，保证实体态/虚拟态口径一致 |

本体只读常量来源为 `ywzj_vehicle_fish` 的 `PhysicsHelper` 与 `PhysicsEngine`：

```text
TICKS_PER_SECOND = 20
GRAVITY = 9.8
PhysicsEngine.G = GRAVITY / TICKS_PER_SECOND² = 9.8 / 400 = 0.0245 格/Tick²
```

这里的“G”是代码使用的离散速度变化单位，不是连续时间仿真中的完整气动力载荷测量。

## 2. 当前 `turning_factor` 实现

### 2.1 数据解析与默认值

`projectile_data.turning_factor` 是按飞行 Tick 区间配置的 `0～1` 浮点值：

1. `RVP_ProjectileData.resolveTurningFactor(flightTick)` 找到当前 Tick 命中的区间。
2. 有限值被钳制到 `[0, 1]`。
3. 区间未命中或整张表缺失时返回 `null`。
4. 实体制导运行时把 `null` 回退为 `0.5f`；因此“未配置”与“显式配置 0”不是同一语义。
5. `f = 1` 是瞬时方向切换语义；`f = 0` 是禁止由该参数转向。

### 2.2 旧版方向插值公式

令：

- `u`：当前速度单位方向；
- `d`：制导层给出的期望单位方向；
- `v = |velocity|`：当前速率，单位格/Tick；
- `f = turning_factor`。

代码执行：

```text
b = (1 - f) · u + f · d
velocity_next = normalize(b) · v
```

因此有两个关键性质：

- 总速率被保持不变，转向只改速度方向；
- 同一个 `f` 对同一个方向夹角产生近似固定的角度步长，但速度越高，等效横向速度变化越大。

这不是“固定最大 G”模型。它的等效过载随当前速度近似线性增加。

## 3. `turning_factor` 的等效过载折算

### 3.1 统一折算口径

为了和 `rvp_maxg` 的离散弦长限制比较，取制导指令与当前速度方向相差 90° 的情况。代码中的折算为：

```text
θ₉₀(f) = atan2(f, 1 - f)
Δv₉₀ = 2 · v · sin(θ₉₀ / 2)
G_tf(v, f) = Δv₉₀ / PhysicsEngine.G
           = 2 · v · sin(atan2(f, 1-f) / 2) / 0.0245
```

这里的 `G_tf` 是“90° 指令下、单 Tick 速度弦长对应的等效 G”，不是所有目标夹角下都会实际达到的 G。目标夹角小于 90° 时，实际转角和实际离散 G 会相应降低。

### 3.2 每个因子的速度系数

`G_tf(v, f) = K(f) × v`。下表给出当前载具包实际出现的因子，以及代码折算使用的 90° 指令角：

| `f` | `θ₉₀` | `K(f)`（G / 格·Tick⁻¹） | 语义 |
| ---: | ---: | ---: | --- |
| 0.08 | 4.970° | 3.539 | 低转向 |
| 0.10 | 6.340° | 4.514 | 低转向 |
| 0.12 | 7.765° | 5.528 | 常规偏低 |
| 0.15 | 10.008° | 7.120 | 当前常见值 |
| 0.20 | 14.036° | 9.974 | 较强机动 |
| 0.24 | 17.526° | 12.436 | 强机动 |
| 0.28 | 21.251° | 15.052 | 很强机动 |
| 0.50 | 45.000° | 31.239 | 未命中区间时的运行时回退值 |
| 1.00 | 90.000° | 不适用 | 瞬时转向豁免，不应折成有限 G |

### 3.3 速度对应等效过载表

单位：速度为格/Tick，单元为 90° 指令下的等效 G。表中 `f = 0.50` 是区间未命中时的运行时回退，不代表当前 82 个配置文件都显式写了 0.50。

| 速度 `v` | `f=.08` | `f=.10` | `f=.12` | `f=.15` | `f=.20` | `f=.24` | `f=.28` | `f=.50` |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1.0 | 3.5 | 4.5 | 5.5 | 7.1 | 10.0 | 12.4 | 15.1 | 31.2 |
| 2.0 | 7.1 | 9.0 | 11.1 | 14.2 | 19.9 | 24.9 | 30.1 | 62.5 |
| 3.0 | 10.6 | 13.5 | 16.6 | 21.4 | 29.9 | 37.3 | 45.2 | 93.7 |
| 3.5 | 12.4 | 15.8 | 19.3 | 24.9 | 34.9 | 43.5 | 52.7 | 109.3 |
| 4.0 | 14.2 | 18.1 | 22.1 | 28.5 | 39.9 | 49.7 | 60.2 | 125.0 |
| 4.5 | 15.9 | 20.3 | 24.9 | 32.0 | 44.9 | 56.0 | 67.7 | 140.6 |
| 5.0 | 17.7 | 22.6 | 27.6 | 35.6 | 49.9 | 62.2 | 75.3 | 156.2 |
| 5.5 | 19.5 | 24.8 | 30.4 | 39.2 | 54.9 | 68.4 | 82.8 | 171.8 |
| 6.0 | 21.2 | 27.1 | 33.2 | 42.7 | 59.8 | 74.6 | 90.3 | 187.4 |
| 6.5 | 23.0 | 29.3 | 35.9 | 46.3 | 64.8 | 80.8 | 97.8 | 203.1 |
| 8.0 | 28.3 | 36.1 | 44.2 | 57.0 | 79.8 | 99.5 | 120.4 | 249.9 |
| 10.0 | 35.4 | 45.1 | 55.3 | 71.2 | 99.7 | 124.4 | 150.5 | 312.4 |
| 20.0 | 70.8 | 90.3 | 110.6 | 142.4 | 199.5 | 248.7 | 301.0 | 624.8 |

直观例子：常见的 `f = 0.15` 在 `v = 6.0` 时约等效 `42.7 G`，但在 `v = 3.0` 时只有约 `21.4 G`；速度翻倍，等效 G 也翻倍。反过来，`rvp_maxg = 20` 在旧实现中无论速度为 3、6 还是 10，饱和转向时都只允许 `20 G` 的速度弦长变化。

## 4. 与 `rvp_maxg` 的对比

### 4.1 当前气动开关关闭时：两条旧路径

当前 `rvp_aero_steering` 默认是 `false`，因此实际运行规则如下：

| 对比项 | `turning_factor` | `rvp_maxg` |
| --- | --- | --- |
| 输入含义 | 单 Tick 方向混合比例 `f` | 单 Tick 最大法向速度变化，单位 G |
| 方向算法 | `normalize((1-f)u + f d)` | 计算最大弦长对应转角，再做球面插值 |
| 饱和 G 与速度关系 | 约 `K(f) × v`，随速度线性增加 | 饱和时约等于配置值，速度基本不改变 G 上限 |
| 单 Tick 最大转角 | 由 `f` 与当前指令夹角共同决定；90°指令时为 `atan2(f,1-f)` | `2 asin(clamp(rvp_maxg×0.0245/(2v),0,1))`，速度越高角度越小 |
| 速度长度 | 严格保持 | 严格保持 |
| 动压/高度 | 不看速度平方动压，不看高度密度 | 关闭气动模型时也不看动压/高度 |
| 转弯能量代价 | 没有诱导阻力结算 | 没有诱导阻力结算 |
| 配置优先级 | 只有未配置 `rvp_maxg` 时使用 | `rvp_maxg != null` 时优先，哪怕值为 0 |
| 典型效果 | 高速弹的等效 G 会异常变大，但角度手感相对固定 | G 量级固定，但高速弹每 Tick 的角度响应会变小 |

`rvp_maxg` 覆盖规则在实体态、虚拟态和线导直控入口均保持一致；同时配置两个字段时，不能用 `turning_factor` 继续增强已由 `rvp_maxg` 接管的转向。

### 4.2 直观对照：`f = 0.15` 与两个显式 G 值

下表仍使用 90° 指令，只用于比较趋势；`rvp_maxg` 列是旧实现的 G 上限。

| 速度 `v` | `turning_factor=.15` 等效 G | `rvp_maxg=20` | `rvp_maxg=35` |
| ---: | ---: | ---: | ---: |
| 1.0 | 7.1 | 20 | 35 |
| 2.0 | 14.2 | 20 | 35 |
| 3.0 | 21.4 | 20 | 35 |
| 4.0 | 28.5 | 20 | 35 |
| 5.0 | 35.6 | 20 | 35 |
| 6.0 | 42.7 | 20 | 35 |
| 8.0 | 57.0 | 20 | 35 |
| 10.0 | 71.2 | 20 | 35 |
| 20.0 | 142.4 | 20 | 35 |

这张表说明：如果希望把某枚 `f = 0.15` 的弹限制在约 35 G，不能只看它低速阶段；在 `v = 6` 时同一配置已经约 42.7 G，在 `v = 20` 时约 142.4 G。显式 `rvp_maxg` 才能给出与速度无关的旧版 G 上限。

## 5. 气动转向开启后的统一实现

虽然当前载具包没有启用 `rvp_aero_steering`，代码已经实现了另一条可选路径。打开后，两种配置先统一得到“设计点过载”，再按当前速度和高度减载。

### 5.1 设计点过载

```text
G_design = rvp_maxg                         （显式 rvp_maxg 优先）
G_design = G_tf(v_ref, turning_factor)      （未配置 rvp_maxg 时）
```

`v_ref` 的解析顺序是：

```text
rvp_ref_speed → max_speed → 武器初速 → 3.0 格/Tick
```

因此 `turning_factor = 0.15` 不是一个固定的“42.7 G”；只有在 `v_ref = 6.0` 时它的设计点等效 G 才是 42.7。`rvp_ref_speed = 0` 是显式关闭动压减载，随后 `qp = 1`。

### 5.2 速度/高度对应的可用过载

气动路径使用：

```text
qp(v,h) = clamp(densityFactor(h) × (v / v_ref)², 0.05, 1.0)
G_avail(v,h) = G_design × qp(v,h)
θ_aero = 2 × asin(clamp(G_avail × 0.0245 / (2v), 0, 1))
```

含义是：

- 低于设计速度时按速度平方减载，但最低保留 `5%` 动压因子；
- 达到或超过设计速度时不再增加设计 G；
- 高度密度倍率还会继续降低可用 G；
- `rvp_maxg` 在此路径不再是“任意速度恒定 G”，而是设计动压点的 G 上限；
- `turning_factor` 与 `rvp_maxg` 在同一 `G_design` 下会得到相同的动压变化曲线，前提是两者在设计点折算为同一个 G；
- `turning_factor >= 1` 且未显式配置 `rvp_maxg` 时保留瞬时转向豁免；显式 `rvp_maxg` 会覆盖该豁免。

例如，某弹 `v_ref = 6.0`、海平面密度 `densityFactor = 1`、`turning_factor = 0.15`：

| 当前速度 | `qp` | 气动路径 `G_avail` |
| ---: | ---: | ---: |
| 1.0 | 0.050 | 2.1 G |
| 2.0 | 0.111 | 4.7 G |
| 3.0 | 0.250 | 10.7 G |
| 4.0 | 0.444 | 19.0 G |
| 5.0 | 0.694 | 29.7 G |
| 6.0 | 1.000 | 42.7 G |
| 8.0 | 1.000 | 42.7 G |

这张气动表与第 3 节旧路径表不是同一运行模式：第 3 节的 `f=.15, v=3` 是旧插值约 `21.4 G`；气动路径把 `v_ref=6` 作为设计点后，同一速度只有约 `10.7 G`。

## 6. 当前载具包因子分布与解释

当前根目录武器 JSON 中的 `turning_factor` 值统计如下：

| 因子 | 文件数 | 以 `v_ref = 6.0` 计算的设计点等效 G |
| ---: | ---: | ---: |
| 0.08 | 1 | 21.2 |
| 0.10 | 8 | 27.1 |
| 0.12 | 12 | 33.2 |
| 0.15 | 25 | 42.7 |
| 0.20 | 17 | 59.8 |
| 0.24 | 7 | 74.6 |
| 0.28 | 8 | 90.3 |
| 1.00 | 4 | 瞬时转向豁免 |

这里的 `v_ref = 6.0` 只是横向比较基准；每枚弹的实际设计点应按 `rvp_ref_speed → max_speed → 武器初速 → 3.0` 重新取值。例如同样是 `f = 0.15`：

- `max_speed = 3.0` 时设计点约 `21.4 G`；
- `max_speed = 6.0` 时设计点约 `42.7 G`；
- `max_speed = 20.0` 时设计点约 `142.4 G`。

因此，对现有 JSON 做数值平衡时，不能只按 `turning_factor` 横向比较，还必须同时看该弹的有效参考速度。

## 7. 调参结论

1. **当前现网不应把 `turning_factor` 值直接当固定 G。** 例如 `0.15` 的实际旧路径等效 G 是 `7.120 × 当前速度`。
2. **若要固定过载上限，使用 `rvp_maxg`。** 在气动开关关闭时，它提供旧版恒定离散 G 上限，并覆盖同时存在的 `turning_factor`。
3. **若要让转向同时受速度/高度影响，打开 `rvp_aero_steering`。** 此时 `rvp_maxg` 和 `turning_factor` 都表示设计动压点能力，不再是旧版的“任意速度恒定 G”。
4. **从 `turning_factor` 迁移到 `rvp_maxg` 时必须先选定设计速度。** 推荐换算：

   ```text
   rvp_maxg ≈ 2 × v_design × sin(atan2(f,1-f)/2) / 0.0245
   ```

   这只能保证在 `v_design` 附近对齐；低速和高速的旧行为仍然不同。

5. **不要给瞬转弹同时加 `rvp_maxg`。** 当前 `f = 1` 的四枚弹依赖瞬时转向语义；显式 G 会使其进入有限 G 路径。
6. **区间表改变的是每个 Tick 的设计能力。** 如果某枚弹按飞行 Tick 切换 `f`，旧路径会立即改变角度步长，气动路径也会在下一次限制快照中改变 `G_design`。
7. **诱导阻力不是可用过载的一部分。** 气动路径中的 `rvp_induced_drag × λ² × v` 是后续运动阶段的速度损失，不能把掉速再反推为 G 上限。

## 8. 复核来源

### 本项目源码

- `src/main/java/org/ywzj/rvp/guidance/trajectorymath/util/RVP_TrajectorySteeringMath.java`：旧版 `turning_factor` 方向混合。
- `src/main/java/org/ywzj/rvp/guidance/trajectorymath/util/RVP_BallisticTrajectoryMath.java`：旧版 `rvp_maxg` 弦长转角、配置优先级与转弯半径。
- `src/main/java/org/ywzj/rvp/guidance/trajectorymath/util/RVP_AeroSteeringModel.java`：`turning_factor` 等效 G、动压因子、`G_avail` 和统一转角求解。
- `src/main/java/org/ywzj/rvp/guidance/trajectorymath/util/RVP_AeroSteeringLimits.java`：实体态/虚拟态共享的气动限制快照。
- `src/main/java/org/ywzj/rvp/weapon/data/RVP_ProjectileData.java`：JSON 字段默认值、`turning_factor` 区间解析、参考速度和高度密度解析。
- `src/main/java/org/ywzj/rvp/guidance/RVP_GuidanceRuntimeMath.java`：实体制导收口及旧/新路径选择。
- `src/test/java/org/ywzj/rvp/guidance/trajectorymath/util/RVP_AeroSteeringModelTest.java`：设计表、`f=.15` 在 `v_ref=6` 时约 `42.7 G`、显式 G 优先和回退行为的单元测试。

### 本体只读常量

- `D:\WgameProject\ywzj_vehicle_fish\src\main\java\org\ywzj\vehicle\util\PhysicsHelper.java`：20 Tick/s、9.8 重力常量。
- `D:\WgameProject\ywzj_vehicle_fish\src\main\java\org\ywzj\vehicle\vehicle\PhysicsEngine.java`：`PhysicsEngine.G` 的换算。

### 数据扫描

- `limitless_vehicle/rvp/data/rvp/weapons/*.json`：当前载具包武器配置；本报告统计排除了 `_backup/`。

