# RVP 导弹运动学配置建议表

> 状态：**体检 + 建议表（未修改任何 JSON）**。配套方案见 `docs/RVP弹体-fish/RVP制导转向气动化方案_最小JSON改动.md`。
> 数据来源：`limitless_vehicle/rvp/data/rvp/weapons/` 实际文件内容，逐字段读取，无推断值。
> 覆盖范围：`"type": "rvp:missile"` 共 71 个文件，排除 `_backup/` 下 4 个（`pl_10` / `pl_12_2` / `pl_15_2` / `yj_91_2`），**共 67 枚参与体检**。
> 常量：`PhysicsEngine.G = 9.8/400 = 0.0245 格/Tick²`（1 G）。

---

## 1. 判定规则

### 1.1 三个派生量

```text
① 助推加速度          a     = thrust / max(mass, 1e-6)                 [格/Tick²]
② 无上限平衡速度      v_eq  = sqrt(a / drag_coefficient)               [格/Tick]
                           （有 second_pulse 时另算二级：sqrt((2nd_thrust/mass)/drag_coefficient)）
③ 设计动压点等效过载  G_design = 2 · v_ref · sin(θ_tf/2) / PhysicsEngine.G   [G]
   其中 θ_tf = atan(f / (1−f))，f = turning_factor（离轴 90° 的最坏转角）
        v_ref = rvp_ref_speed → max_speed → 武器初速 velocity → 常量 3.0
```

`G_design` 就是 §4.4 的 `turning_factor → 等效设计过载` 折算，也是新气动模型给出的**设计点可用过载**（`qp = 1` 处）。

### 1.2 动压参考速度 `v_ref` 的取法：只防"高估"

| 偏差方向 | 后果 | 严重性 |
| --- | --- | --- |
| `v_ref` **低估** | `qp` 被 `min(…, 1.0)` 钳到 1.0 → 退化为常量 G，等同现状 | 无害 |
| `v_ref` **高估** | `qp` 长期 ≪ 1 → 机动被过度削弱 | **有害** |

所以判定只看一件事：**`max_speed` 是否明显高于该弹实际能飞到的速度（`v_eq`）**。判据 `v_eq ≥ max_speed` 视为可达、`v_ref` 可安全自动取 `max_speed`。

### 1.3 诱导阻力建议值

`Δv_ind = k_i · λ² · v`，与同 Tick 零升阻力 `Δv_drag = drag_coefficient · v²` 之比为 `k_i / (drag_coefficient · v)`。

| 条件 | 建议 `rvp_induced_drag` | 满舵时相对零升阻力 |
| --- | --- | --- |
| `drag_coefficient ≥ 0.01` | `2 × drag_coefficient` | ≈ +2/v（v=3.5 时 ≈ +57%） |
| `0.001 ≤ drag_coefficient < 0.01` | `3 × drag_coefficient` | ≈ +3/v（v=6 时 +50%，v=3 时 +100%） |
| `drag_coefficient < 0.001` | **填绝对值 `0.004`** | 倍数无效，必须给下限 |

> **更省事的替代**：把代码缺省从 `1 × drag_coefficient` 改为常量 `INDUCED_DRAG_DEFAULT_SCALE = 3.0`，则 62/67 枚零配置即可，只有下面 5 枚需要显式填写。

### 1.4 `rvp_maxg` 的填写原则：默认不填

`turning_factor` 折算已给出合理设计过载。**填 `rvp_maxg` 会覆盖折算值**，只在两种情况需要：

1. **`turning_factor` 缺失** → G 值回退到默认 `f = 0.5` → 折算 187 G 或 94 G，**严重失真，必须填**。
2. 希望主动压低某弹机动性。

### 1.5 `turning_factor = 1` 的豁免

`f = 1` 表示"无过载、瞬转"（线导直控弹手感依赖项）。新模型对其**自动豁免气动限制**，无需任何配置。

---

## 2. 现状体检总表（67 枚）

- `可达` = `v_eq ≥ max_speed`；`不可达(倍率)` 表示 `max_speed / v_eq`。
- `—` 表示该弹无动力（`has_rocket_engine` 缺失），走 `RVP_BaseBullet.tickBallisticMotion` 而非 `tickMissileMove`，`mass/thrust/motor_burn_time/drag_coefficient` **全部不生效**。
- 类别中带「?」者为按名称推断、需人工确认。

| # | 武器文件 | 类别 | v_ref 来源 | a | v_eq | max_speed 可达 | f | θ_tf | G_design | 体检结论 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 9k720_9m723 | 弹道 | max_speed 9.0 | 0.02 | 1.04（二级 5.19） | **不可达 ×1.73** | 0.15 | 10.01° | 64.1 | 名义速度飞不到，Q 被削弱 3× |
| 2 | ah64_agm179_arh | 反坦克 | max_speed 3.5 | 0.13 | 3.54 | 可达（边缘） | 0.28 | 21.25° | 52.7 | 正常 |
| 3 | ah64_agm179_ir | 反坦克 | max_speed 3.5 | 0.13 | 3.54 | 可达（边缘） | 0.28 | 21.25° | 52.7 | 正常 |
| 4 | ah64_agm179_laser | 反坦克 | max_speed 3.5 | 0.13 | 3.54 | 可达（边缘） | 0.28 | 21.25° | 52.7 | 正常 |
| 5 | ah64_agm179_mil | 反坦克 | max_speed 3.5 | 0.13 | 3.54 | 可达（边缘） | 0.28 | 21.25° | 52.7 | 正常 |
| 6 | agm_114 | 反坦克 | **兜底 3.0** | 0.60 | 10.95 | 无 max_speed | **缺（回退 0.5）** | 45.0° | **93.7** | ⚠ 等效 94 G，必须压 |
| 7 | aim_120 | 空空 | max_speed 6.0 | 1.25 | 20.40 | 可达 | **缺（回退 0.5）** | 45.0° | **187.4** | ⚠ 等效 187 G，必须压 |
| 8 | bmpt_9m120_1 | 反坦克 | max_speed 3.0 | 0.40 | 8.94 | 可达 | 0.24 | 17.53° | 37.3 | 正常 |
| 9 | bmpt_9m120_f | 反坦克 | max_speed 3.0 | 0.40 | 8.94 | 可达 | 0.24 | 17.53° | 37.3 | 正常 |
| 10 | bukm3_9m317ma | 防空 | max_speed 5.5 | 0.70 | 10.00 | 可达 | 0.10 | 6.34° | 24.8 | 正常 |
| 11 | cssa5_hq13 | 防空 | max_speed 6.0 | 0.70 | 15.28 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 12 | ea18g_agm65e | 空地 | max_speed 3.5 | 2.00 | 12.91 | 可达 | 0.20 | 14.04° | 34.9 | 正常 |
| 13 | ea18g_agm65f | 空地 | max_speed 3.5 | 2.00 | 12.91 | 可达 | 0.20 | 14.04° | 34.9 | 正常 |
| 14 | ea18g_agm88 | 反辐射 | max_speed 6.0 | 2.08 | 26.35 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 15 | ea18g_aim120d | 空空 | max_speed 6.0 | 1.42 | 26.60 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 16 | ea18g_aim260a | 空空 | max_speed 6.0 | 2.50 | 50.00 | 可达 | 0.12 | 7.77° | 33.2 | 正常 |
| 17 | f14a_iriaf_aim23b | 空空 | max_speed 5.0 | 0.83 | 16.67 | 可达 | 0.08 | 4.97° | 17.7 | 偏低但接近真实 |
| 18 | f14a_iriaf_r27r | 空空 | max_speed 6.0 | 2.08 | 26.35 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 19 | f14d_aim54c | 空空 | max_speed 6.0 | 0.13 | 6.45 | 可达（边缘） | 0.10 | 6.34° | 27.1 | 正常 |
| 20 | f14d_aim7p | 空空 | max_speed 6.0 | 2.08 | 26.35 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 21 | f14d_maodie_chunqiucang | 空空? | max_speed 6.0 | 0.13 | 6.45 | 可达（边缘） | 0.10 | 6.34° | 27.1 | 正常（数值同 AIM-54C） |
| 22 | f16_aim120d | 空空 | max_speed 6.0 | 1.42 | 26.60 | 可达 | 0.15 | 10.01° | 42.7 | 正常（§4.7 算例） |
| 23 | f16_aim260a | 空空 | max_speed 6.0 | 2.50 | 50.00 | 可达 | 0.12 | 7.77° | 33.2 | 正常 |
| 24 | f22a_aim120d | 空空 | max_speed 6.0 | 1.42 | 26.60 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 25 | f22a_aim260a | 空空 | max_speed 6.0 | 2.50 | 50.00 | 可达 | 0.12 | 7.77° | 33.2 | 正常 |
| 26 | f22a_aim9m | 空空 | max_speed 6.5 | 1.88 | 19.36 | 可达 | 0.20 | 14.04° | 64.8 | 偏高，可选压到 40 |
| 27 | fa18f_agm84hk | 反舰 | max_speed 2.5 | 0.25 | 6.74 | 可达 | 0.20 | 14.04° | 24.9 | 正常 |
| 28 | fa18f_brimstone | 反坦克 | max_speed 3.5 | 0.13 | 3.54 | 可达（边缘） | 0.15 | 10.01° | 24.9 | 正常 |
| 29 | irist_sl | 防空 | max_speed 4.5 | 0.70 | 15.28 | 可达 | 0.12 | 7.77° | 24.9 | 正常 |
| 30 | j16_akf98a | 空地 | max_speed 4.0 | 0.80 | 14.14 | 可达 | 0.15 | 10.01° | 28.5 | 正常 |
| 31 | j16_kd88 | 空地 | max_speed 2.5 | 0.25 | 6.74 | 可达 | 0.20 | 14.04° | 24.9 | 正常 |
| 32 | j16_kd88a | 空地 | max_speed 2.5 | 0.25 | 6.74 | 可达 | 0.20 | 14.04° | 24.9 | 正常 |
| 33 | j16_tl17 | 空地 | max_speed 3.5 | 0.25 | 6.74 | 可达 | 0.20 | 14.04° | 34.9 | 正常 |
| 34 | j16_yj80 | 反舰 | max_speed 4.5 | 4.00 | 26.97 | 可达 | 0.20 | 14.04° | 44.9 | 正常 |
| 35 | j16_yj91 | 反舰 | max_speed 5.0 | 0.60 | 34.64 | 可达 | 0.12 | 7.77° | 27.6 | 正常 |
| 36 | j16d_ld8a | 反辐射 | max_speed 6.5 | 2.50 | 22.36 | 可达 | 0.20 | 14.04° | 64.8 | 偏高，可选压 |
| 37 | j16d_ld10 | 反辐射 | max_speed 6.0 | 2.08 | 26.35 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 38 | j16d_pl10 | 空空 | max_speed 6.5 | 2.50 | 22.36 | 可达 | 0.20 | 14.04° | 64.8 | 偏高，可选压 |
| 39 | j20a_pl10 | 空空 | max_speed 6.5 | 2.50 | 22.36 | 可达 | 0.20 | 14.04° | 64.8 | 偏高，可选压 |
| 40 | j20a_pl15 | 空空 | max_speed 6.0 | 3.75 | 35.36 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 41 | kd_88a | 空地 | max_speed 2.5 | 0.25 | 6.74 | 可达 | 0.20 | 14.04° | 24.9 | 正常 |
| 42 | lav25_tow2b | 反坦克 | max_speed 3.5 | 10.00 | 44.72 | 可达 | **1.0** | 90° | 豁免 | 线导瞬转，保持现状 |
| 43 | lav25_tow2n | 反坦克 | max_speed 3.5 | 10.00 | 44.72 | 可达 | **1.0** | 90° | 豁免 | 线导瞬转，保持现状 |
| 44 | lavad_fim92 | 防空 | max_speed 5.5 | 1.88 | 19.36 | 可达 | 0.20 | 14.04° | 54.9 | 偏高，可选压 |
| 45 | m142_atacms | 弹道 | max_speed 20.0 | 72.93 | 402.6 | 可达 | 0.15 | 10.01° | **142.5** | ⚠ 等效 142 G，必须压 |
| 46 | m1a2sep_lahat | 反坦克 | max_speed 3.5 | — | — | **无动力** | 0.24 | 17.53° | 43.5 | ⚠ 疑缺 `has_rocket_engine` |
| 47 | mi28_kh_39 | 空地 | max_speed 2.0 | 0.40 | 8.94 | 可达 | 0.24 | 17.53° | 24.9 | 正常 |
| 48 | mi28_kh_39t | 空地 | max_speed 3.5 | 1.00 | 10.00 | 可达 | 0.28 | 21.25° | 52.7 | 正常 |
| 49 | pl_8 | 空空 | **兜底 3.0** | 1.00 | 14.14 | 无 max_speed | **缺（回退 0.5）** | 45.0° | **93.7** | ⚠ 等效 94 G，必须压 |
| 50 | pl_12 | 空空 | max_speed 6.0 | 2.08 | 26.35 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 51 | pl_15 | 空空 | max_speed 6.0 | 3.75 | 35.36 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 52 | ps1_95ya6m | 防空 | max_speed 6.0 | 2.67 | 27.60 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 53 | ps1_hermes | 多用途 | max_speed 6.0 | 0.70 | 15.28 | 可达 | 0.15 | 10.01° | 42.7 | 正常 |
| 54 | ps1_tkb1055 | 反坦克 | max_speed 3.5 | 0.40 | 8.94 | 可达 | 0.24 | 17.53° | 43.5 | 正常 |
| 55 | rafale_aasm_ir | 空地 | max_speed 3.5 | 1.00 | 13.48 | 可达 | 0.20 | 14.04° | 34.9 | 正常 |
| 56 | rafale_aasm_laser | 空地 | max_speed 3.5 | — | — | **无动力** | 0.24 | 17.53° | 43.5 | ⚠ 疑缺 `has_rocket_engine` |
| 57 | rafale_mica_em | 空空 | max_speed 6.0 | 2.92 | 20.40 | 可达 | 0.10 | 6.34° | 27.1 | 正常 |
| 58 | rafale_mica_ng | 空空 | max_speed 6.0 | 3.33 | 21.80 | 可达 | 0.10 | 6.34° | 27.1 | 正常 |
| 59 | rafale_storm_shadow | 巡航 | max_speed 4.0 | 0.80 | 14.14 | 可达 | 0.15 | 10.01° | 28.5 | 正常 |
| 60 | su57_kh38 | 空地 | max_speed 6.5 | 4.00 | 26.97 | 可达 | 0.20 | 14.04° | 64.8 | 偏高，可选压 |
| 61 | su57_kh58 | 反辐射 | max_speed 6.5 | 4.00 | 26.97 | 可达 | 0.28 | 21.25° | **97.8** | ⚠ 反辐射弹 98 G 不合理 |
| 62 | t90m_9m119m1 | 反坦克 | max_speed 4.0 | 0.47 | 9.70 | 可达 | 0.28 | 21.25° | **60.2** | ⚠ 炮射导弹 60 G 偏高 |
| 63 | ucav_switchblade | 巡飞弹 | max_speed 1.2 | 8.00 | 40.00 | 可达 | **1.0** | 90° | 豁免 | 瞬转，保持现状 |
| 64 | vt4_gp125 | 反坦克 | max_speed 4.0 | 0.47 | 9.70 | 可达 | 0.28 | 21.25° | **60.2** | ⚠ 炮射导弹 60 G 偏高 |
| 65 | yj_91 | 反舰/反辐射 | max_speed 5.0 | 0.60 | 34.64 | 可达 | 0.12 | 7.77° | 27.6 | 正常 |
| 66 | zbl08a_hj73e | 反坦克 | max_speed 3.5 | 10.00 | 44.72 | 可达 | **1.0** | 90° | 豁免 | 线导瞬转，保持现状 |
| 67 | zbl08a_hj73e2 | 反坦克 | max_speed 3.5 | 1.80 | 18.97 | 可达 | 0.24 | 17.53° | 43.5 | 正常 |

**统计**：仅 1 枚（`9k720_9m723`）`v_ref` 高估；4 枚 `f = 1` 自动豁免；**7 枚 G 值需要人工介入**（#6 #7 #45 #49 #61 #62 #64）。

---

## 3. 建议配置表（只列需要写 JSON 的）

### 3.1 必填：`rvp_maxg`（覆盖失真的等效过载）

| 武器 | 现 G_design | 建议 `rvp_maxg` | 理由 |
| --- | --- | --- | --- |
| `aim_120` | 187.4 | **35** | 缺 `turning_factor` 回退 0.5；AIM-120 实弹 ≈ 40 G 级 |
| `agm_114` | 93.7 | **8** | 缺 `turning_factor`；海尔法为亚音速反坦克弹 |
| `pl_8` | 93.7 | **35** | 缺 `turning_factor`；PL-8 为格斗弹 |
| `m142_atacms` | 142.5 | **5** | 弹道导弹，实际仅 3～5 G |

### 3.2 建议：压到合理量级（等效过载失真，但缺 `turning_factor` 之外的成因）

| 武器 | 现 G_design | 建议 `rvp_maxg` | 理由 |
| --- | --- | --- | --- |
| `su57_kh58` | 97.8 | **15** | 反辐射弹，大过载无意义且会毁掉末段弹道 |
| `t90m_9m119m1` | 60.2 | **20** | 炮射导弹，受炮管发射与激光驾束限制 |
| `vt4_gp125` | 60.2 | **20** | 同上 |
| `f22a_aim9m` / `j16d_ld8a` / `j16d_pl10` / `j20a_pl10` / `su57_kh38` / `lavad_fim92` | 54.9～64.8 | **40**（可选） | 均在 55～65 G，偏高但不离谱，按手感决定 |

### 3.3 建议：修正 `v_ref`（唯一一例高估）

| 武器 | 问题 | 建议 |
| --- | --- | --- |
| `9k720_9m723` | `max_speed = 9.0`，实际平衡速度仅 5.19（第二脉冲）→ `qp` 被压到 0.33 | `max_speed` 改为 `5.2`，**或**加 `"rvp_ref_speed": 5.2` |

### 3.4 建议：诱导阻力显式值

| 武器 | `drag_coefficient` | 倍数推算 | 实际表现 | 建议 `rvp_induced_drag` |
| --- | --- | --- | --- | --- |
| `m142_atacms` | 0.00045 | 0.00135 | 太弱，无手感 | **0.004** |
| `j16_yj91` / `yj_91` | 0.0005 | 0.0015 | 太弱 | **0.004** |
| `9k720_9m723` | 0.0155 | 0.0465 | 过强（满舵 +58% 已是极限） | **0.02** |
| `m1a2sep_lahat` / `rafale_aasm_laser` | 0.0001 / 0.0055 | — | 无动力，`drag_coefficient` 本就不参与 | 忽略或 `0` |

其余 62 枚：**若把代码缺省改为 `3.0 × drag_coefficient`，则全部无需填写**（`drag_coefficient ≥ 0.01` 的 8 枚会偏强，可个别降为 `2` 倍）。

### 3.5 建议：第二阶段 α 模型参数（可选，见方案 §15）

| 类别 | 建议 `rvp_alpha_max` | 建议 `rvp_alpha_tau` |
| --- | --- | --- |
| 空空 / 防空（G ≥ 25） | `20` | `3.0` |
| 反坦克 / 空地 / 巡航 | `15` | `4.0` |
| 弹道导弹 | `15` | `6.0` |
| `turning_factor = 1` 的 4 枚 | **不配**（豁免） | **不配** |

---

## 4. 三类必须单独处理的弹

### 4.1 `turning_factor` 缺失（3 枚）

`agm_114`、`aim_120`、`pl_8`。回退默认 `f = 0.5` 会折算出 94～187 G 的等效过载，是全表最严重的失真。

> 根因：`RVP_GuidanceRuntimeMath.resolveTurningFactor` 在区间未命中时返回 `0.5f`，折算链路无法区分"用户显式配了 0.5"与"用户没配"。
>
> 代码层可选修法：`resolveTurningFactor` 返回 `null` 时，新的气动求解器视为"未配置转向能力"→ 直接调用 `rvp_maxg`；未配 `rvp_maxg` 时禁止转向（比 187 G 安全）。

### 4.2 无动力弹（2 枚）

`m1a2sep_lahat`、`rafale_aasm_laser`：`projectile_data` 里**没有 `has_rocket_engine`**，因此：

- `usesPropulsion()` = false → 走 `RVP_BaseBullet.tickBallisticMotion`，**不走** `tickMissileMove`；
- `getResolvedDragCoefficient()` / `getResolvedMotorBurnTime()` / `getResolvedMass()` 全部返回 0 → **`mass`、`thrust`、`motor_burn_time`、`drag_coefficient` 是死配置**；
- 实际是"载机速度（`inherit_vehicle_velocity: true`）+ 制导转向 + 无重力无阻力滑翔"。

需人工确认这是有意设计还是漏配。若本意是动力弹，补 `"has_rocket_engine": true` 即可；若确为滑翔弹，建议把死掉的 4 个键删掉以免误导调参。

### 4.3 `turning_factor = 1`（4 枚）

`lav25_tow2b`、`lav25_tow2n`、`zbl08a_hj73e`、`ucav_switchblade`：语义是"无过载、瞬转"。新模型对其**自动豁免气动限制与诱导阻力**（`f ≥ 1` 分支），无需任何配置，也不要给它们填 `rvp_maxg`（会把瞬转压掉）。

---

## 5. 可直接照抄的配置

### 5.1 绝大多数弹（62 枚）——什么都不用填

```jsonc
// projectile_data 原样保留即可
// 代码缺省：rvp_aero_steering = true，v_ref = max_speed，k_i = 3 × drag_coefficient
```

### 5.2 四枚必填 `rvp_maxg`

```jsonc
// aim_120.json            → projectile_data 追加
"rvp_maxg": 35

// agm_114.json            → projectile_data 追加
"rvp_maxg": 8

// pl_8.json               → projectile_data 追加
"rvp_maxg": 35

// m142_atacms.json        → projectile_data 追加
"rvp_maxg": 5,
"rvp_induced_drag": 0.004
```

### 5.3 三枚建议调整

```jsonc
// 9k720_9m723.json        → projectile_data
"max_speed": 5.2,                 // 原 9.0，实际飞不到
"rvp_induced_drag": 0.02          // 原缺省 3×0.0155 = 0.0465 过强

// su57_kh58.json          → projectile_data
"rvp_maxg": 15

// t90m_9m119m1.json / vt4_gp125.json → projectile_data
"rvp_maxg": 20
```

### 5.4 两枚小阻力弹

```jsonc
// j16_yj91.json / yj_91.json → projectile_data
"rvp_induced_drag": 0.004
```

---

## 6. 复检方法

改完后按同一套公式复算，全部满足即通过：

```text
1. min(sqrt((thrust/mass)/drag_coefficient), max_speed) ≈ max_speed       或 rvp_ref_speed ≈ 实际可达速度
2. G_design = 2·v_ref·sin(atan(f/(1−f))/2) / 0.0245  落在该弹类别合理区间（见 §3.5 量级）
3. rvp_induced_drag / (drag_coefficient × 设计速度) 落在 0.3 ～ 1.5
4. turning_factor = 1 的弹未配 rvp_maxg
5. 无动力弹未依赖 mass/thrust/drag_coefficient 调参
```

## 7. 与方案文档的关系

| 项 | 位置 |
| --- | --- |
| 动压因子 / 等效过载 / 诱导阻力的公式与代码结构 | `RVP制导转向气动化方案_最小JSON改动.md` §4 |
| 引入攻角 α 的增量成本与影响范围 | 同上 §15 |
| 惯性与积分器现状（本文 §1.3 的 `a`、`v_eq` 推导依据） | 同上 §1.7 |
| 本体 `maxG × 动压因子` 参考实现 | `ywzj_vehicle/entity/weapon/MissileEntity.calculateSteeredRotation` |
