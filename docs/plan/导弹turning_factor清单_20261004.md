# 各导弹 turning_factor 清单（2026-10-04 03:38 整体调整 + 03:45 个别微调）


> 来源：`limitless_vehicle/rvp/data/rvp/weapons/*.json` 的 `projectile_data.turning_factor`。共 **82** 个文件（`rvp:missile` 76 + 炸弹类 6）。


## 调整规则（2026-10-04 03:38）


| 原值 | 新值 | 数量 | 说明 |
|---|---|---|---|
| 0.28 | **0.12** | 8 | 高机动档统一 → 0.12 |
| 0.24 | **0.12** | 7 | 同上 |
| 0.2 | **0.12** | 17（导弹 15 + 炸弹 2） | 同上 |
| 0.15 | **0.1** | 24 | 「其它全部调 0.1」 |
| 0.12 | **0.09** | 12（导弹 8 + 炸弹 4） | 降一档 |
| 0.1 | **0.1** | 7 → 6 | 不变 |
| 0.08 | **0.06** | 1 | `f14a_iriaf_aim23b`（思想90） |
| 1 | **1** | 4 | **保持**（SACLOS 线控/巡飞弹） |

**个别指定**：`pl_12` 0.15 → **0.12**；`f14d_aim54c` 0.1 → **0.06**。
**后续个别调整（03:45）**：`bukm3_9m317ma`（9M317MA 重型主动雷达弹）0.1 → **0.08**（新最低档）。
**注意**：调整前全包**没有 0.09 档**，故"原 0.09 → 0.08"为空操作。

## 语义（`RVP_GuidanceRuntimeMath` / `RVP_ProjectileData`）


- **按飞行 tick 配置的方向插值强度，0~1**；越大 ⇒ 越贴近目标方向 ⇒ 转得越快、转弯半径越小。区间未命中用运行时默认 **0.5**。
- ⚠️ **配了 `rvp_maxg` 会被覆盖为 1.0**（`:196`）—— 本包无弹配 `rvp_maxg` ⇒ 全部生效。
- 终端"已越过/极近"分支取 `max(turningFactor, 0.5)`（`:255`）。炸弹类区间 `[[5,inf]]`/`[[6,inf]]` ⇒ 投放后前 5~6 tick 不转向。

## 调整后分布


- **1** × 4
- **0.12** × 33
- **0.1** × 30
- **0.09** × 12
- **0.08** × 1
- **0.06** × 2

### turning_factor = 1（4 个）


| 文件 | 区间 | 类型 | 初速 | 最高速 | 名称 |
|---|---|---|---|---|---|
| `lav25_tow2b.json` | `[[1,inf]]` | missile | 1.5 | 3.5 | TOW-2B 半自动指令线制导反坦克导弹[攻顶] |
| `lav25_tow2n.json` | `[[1,inf]]` | missile | 1.5 | 3.5 | TOW-2N 半主动指令线反坦克导弹 |
| `ucav_switchblade.json` | `[[1,inf]]` | missile | 1.0 | 1.2 | 弹簧刀巡飞弹[空爆弹头] |
| `zbl08a_hj73e.json` | `[[1,inf]]` | missile | 1.5 | 3.5 | 红箭73E 半主动指令线反坦克导弹 |

### turning_factor = 0.12（33 个）


| 文件 | 区间 | 类型 | 初速 | 最高速 | 名称 |
|---|---|---|---|---|---|
| `ah64_agm179_arh.json` | `[[1,inf]]` | missile | 0.5 | 3.5 | AGM-179 MR 主动雷达制导空对地导弹 |
| `ah64_agm179_ir.json` | `[[1,inf]]` | missile | 0.5 | 3.5 | AGM-179 MR-IR 红外制导空对地导弹 |
| `ah64_agm179_laser.json` | `[[1,inf]]` | missile | 0.5 | 3.5 | AGM-179 MR 激光制导空对地导弹 |
| `ah64_agm179_mil.json` | `[[1,inf]]` | missile | 0.5 | 3.5 | AGM-179 TR 人在回路空对地导弹 |
| `bmpt_9m120_1.json` | `[[1,inf]]` | missile | 2.2 | 3.0 | 9M120-1 激光驾束反坦克导弹 |
| `bmpt_9m120_f.json` | `[[1,inf]]` | missile | 2.2 | 3.0 | 9M120F 驾束制导温压导弹 |
| `ea18g_agm65e.json` | `[[1,inf]]` | missile | 0.1 | 3.5 | AGM-65E 激光制导空对地导弹 |
| `ea18g_agm65f.json` | `[[1,inf]]` | missile | 0.1 | 3.5 | AGM-65F 红外制导空对地导弹 |
| `f16_gbu53.json` | `[[5,inf]]` | bomb | 0.1 | 10 | GBU-53 红外制导炸弹 |
| `f22a_aim9m.json` | `[[1,inf]]` | missile | 1.2 | 6.5 | AIM-9M 红外格斗弹 |
| `fa18f_agm84hk.json` | `[[1,inf]]` | missile | 1.0 | 2.5 | AGM-84H/K 人在回路电视制导导弹 |
| `j10c_gb3_ir.json` | `[[5,inf]]` | bomb | 0.1 | 10 | GB-3 红外制导炸弹 |
| `j16_kd88.json` | `[[1,inf]]` | missile | 1.0 | 2.5 | 空地88 人在回路电视制导导弹 |
| `j16_kd88a.json` | `[[1,inf]]` | missile | 1.0 | 2.5 | 空地88A 红外制导空对地导弹 |
| `j16_tl17.json` | `[[1,inf]]` | missile | 1.0 | 3.5 | 天龙-17 人在回路指令线制导导弹 |
| `j16_yj80.json` | `[[1,inf]]` | missile | 1.0 | 4.5 | 鹰击80 红外制导空对地导弹 |
| `j16d_ld8a.json` | `[[1,inf]]` | missile | 1.2 | 6.5 | 雷电-8 反辐射导弹 |
| `j16d_pl10.json` | `[[1,inf]]` | missile | 1.2 | 6.5 | 霹雳-10 红外格斗弹 |
| `j20a_pl10.json` | `[[1,inf]]` | missile | 1.2 | 6.5 | 霹雳-10 红外格斗弹 |
| `kd_88a.json` | `[[1,inf]]` | missile | 1.0 | 2.5 | 空地88A 红外制导空对地导弹 |
| `lavad_fim92.json` | `[[1,inf]]` | missile | 1.2 | 5.5 | FIM-92K 红外格斗弹 |
| `m1a2sep_lahat.json` | `[[1,inf]]` | missile | 0 | 3.5 | 拉哈特 激光制导反坦克导弹 |
| `mi28_kh_39.json` | `[[1,inf]]` | missile | 1.0 | 2.0 | KH-39 电视制导空对地导弹 |
| `mi28_kh_39t.json` | `[[1,inf]]` | missile | 0.5 | 3.5 | KH-39T 红外制导空对地导弹 |
| `pl_12.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | 霹雳-12 主动雷达弹 |
| `ps1_tkb1055.json` | `[[1,inf]]` | missile | 0.5 | 3.5 | TKB-1055 半主动指令线防空导弹 |
| `rafale_aasm_ir.json` | `[[1,inf]]` | missile | 0 | 3.5 | 铁锤-64 红外制导空对地导弹 |
| `rafale_aasm_laser.json` | `[[1,inf]]` | missile | 0 | 3.5 | 铁锤-54 激光制导空对地导弹 |
| `su57_kh38.json` | `[[1,inf]]` | missile | 0 | 6.5 | KH-38MT 红外制导空对地导弹 |
| `su57_kh58.json` | `[[1,inf]]` | missile | 0 | 6.5 | KH-58USHK 反辐射导弹 |
| `t90m_9m119m1.json` | `[[1,inf]]` | missile | 2.5 | 4.0 | 9M119M1 激光架束反坦克导弹 |
| `vt4_gp125.json` | `[[1,inf]]` | missile | 2.5 | 4.0 | GP-125 激光架束反坦克导弹 |
| `zbl08a_hj73e2.json` | `[[1,inf]]` | missile | 0.5 | 3.5 | 红箭73E2 激光架束反坦克导弹 |

### turning_factor = 0.1（30 个）


| 文件 | 区间 | 类型 | 初速 | 最高速 | 名称 |
|---|---|---|---|---|---|
| `052d_hq10.json` | `[[1,inf]]` | missile | 1.5 | 6.0 | 海红旗10 主动红外弹 |
| `052d_yj19.json` | `[[1,inf]]` | missile | 0.5 | 7.5 | 鹰击-19 主动雷达制导反舰导弹 |
| `052d_yj20.json` | `[[1,inf]]` | missile | 0.06 | 11.0 | 鹰击20 弹道导弹 |
| `9k720_9m723.json` | `[[1,inf]]` | missile | 0.06 | 9.0 | 9M723 伊斯坎德尔M 弹道导弹 |
| `cssa5_hq13.json` | `[[1,inf]]` | missile | 1.5 | 6.0 | 红旗13 主动红外弹 |
| `ddg51_essm.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | ESSM 先进海麻雀 半主动雷达弹 |
| `ddg51_rgm109.json` | `[[1,inf]]` | missile | 1 | 3.0 | RGM-109C 巡航导弹 |
| `ddg51_rgm84_port.json` | `[[1,inf]]` | missile | 1.5 | 3.5 | RGM-84 主动雷达制导反舰导弹（左舷） |
| `ddg51_rgm84_stbd.json` | `[[1,inf]]` | missile | 1.5 | 3.5 | RGM-84 主动雷达制导反舰导弹（右舷） |
| `ea18g_agm88.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | AGM-88E 反辐射导弹 |
| `ea18g_aim120d.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | AIM-120D 主动雷达弹 |
| `f14a_iriaf_r27r.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | R-27R 半主动雷达弹 |
| `f14d_aim7p.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | AIM-7P 半主动雷达弹 |
| `f14d_maodie_chunqiucang.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | 春秋肠 |
| `f16_aim120d.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | AIM-120D 主动雷达弹 |
| `f22a_aim120d.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | AIM-120D 主动雷达弹 |
| `fa18f_brimstone.json` | `[[1,inf]]` | missile | 0.1 | 3.5 | 硫磺石 激光/末端主动雷达双模空对地导弹 |
| `j16_akf98a.json` | `[[1,inf]]` | missile | 0 | 4.0 | AKF-98A GPS制导巡航导弹 |
| `j16d_ld10.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | 雷电-10 反辐射导弹 |
| `j20a_pl15.json` | `[[1,inf]]` | missile | 0.3 | 6.0 | 霹雳-15 双脉冲 主动雷达弹 |
| `m142_atacms.json` | `[[1,inf]]` | missile | 2.5 | 20.0 | MGM-140 ATACMS |
| `m1agds_mim146.json` | `[[1,inf]]` | missile | 0.5 | 6 | MIM-146 激光架束制导多用途导弹 |
| `mi28_9m127.json` | `[[1,inf]]` | missile | 0.5 | 4.5 | 9M127 激光架束反坦克导弹 |
| `mi28_hermes.json` | `[[1,inf]]` | missile | 0.5 | 5.0 | 赫尔墨斯 主动雷达制导空对地导弹 |
| `pl_15.json` | `[[1,inf]]` | missile | 0.3 | 6.0 | 霹雳-15 双脉冲 主动雷达弹 |
| `ps1_95ya6m.json` | `[[1,inf]]` | missile | 0.5 | 6 | 95Ya6M 半自动指令线防空导弹 |
| `ps1_hermes.json` | `[[1,inf]]` | missile | 1.5 | 6.0 | 赫尔墨斯1A 主动雷达弹 |
| `rafale_mica_em.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | 米卡-EM 主动雷达弹 |
| `rafale_mica_ng.json` | `[[1,inf]]` | missile | 0.3 | 6.0 | 米卡-NG 双脉冲 主动雷达弹 |
| `rafale_storm_shadow.json` | `[[1,inf]]` | missile | 0 | 4.0 | 风暴阴影 GPS制导巡航导弹 |

### turning_factor = 0.09（12 个）


| 文件 | 区间 | 类型 | 初速 | 最高速 | 名称 |
|---|---|---|---|---|---|
| `052d_hq9b.json` | `[[1,inf]]` | missile | 0.5 | 7.5 | 海红旗9B 重型主动雷达弹 |
| `ddg51_rim161.json` | `[[1,inf]]` | missile | 0.5 | 7.5 | RIM-161 重型主动雷达弹 |
| `ea18g_aim260a.json` | `[[1,inf]]` | missile | 0.3 | 6.0 | AIM-260A 双脉冲主动雷达弹 |
| `f14d_maodie_yeshenggounai.json` | `[[6,inf]]` | bomb | 0.1 | 3 | 伪装成野生狗奶的重磅炸弹 |
| `f14d_mk84.json` | `[[6,inf]]` | bomb | 0.1 | 3 | MK-84 低阻炸弹 |
| `f16_aim260a.json` | `[[1,inf]]` | missile | 0.3 | 6.0 | AIM-260A 双脉冲主动雷达弹 |
| `f18f_specialweapon.json` | `[[6,inf]]` | bomb | 0.1 | 3 | MK-114 514磅航空炸弹 |
| `f22a_aim260a.json` | `[[1,inf]]` | missile | 0.3 | 6.0 | AIM-260A 双脉冲主动雷达弹 |
| `irist_sl.json` | `[[1,inf]]` | missile | 0.1 | 4.5 | IRIS-T SL 主动红外制导防空导弹 |
| `j10c_gb3_laser.json` | `[[6,inf]]` | bomb | 0.1 | 3 | GB-3 激光制导炸弹 |
| `j16_yj91.json` | `[[1,inf]]` | missile | 0 | 5.0 | YJ-91A 反辐射导弹 |
| `yj_91.json` | `[[1,inf]]` | missile | 0 | 5.0 | YJ-91 反辐射导弹 |

### turning_factor = 0.08（1 个）


| 文件 | 区间 | 类型 | 初速 | 最高速 | 名称 |
|---|---|---|---|---|---|
| `bukm3_9m317ma.json` | `[[1,inf]]` | missile | 0.5 | 5.5 | 9M317MA 重型主动雷达弹 |

### turning_factor = 0.06（2 个）


| 文件 | 区间 | 类型 | 初速 | 最高速 | 名称 |
|---|---|---|---|---|---|
| `f14a_iriaf_aim23b.json` | `[[1,inf]]` | missile | 0.5 | 5.0 | 思想90 远程主动雷达弹 |
| `f14d_aim54c.json` | `[[1,inf]]` | missile | 0.5 | 6.0 | AIM-54C+ 远程主动雷达弹 |
