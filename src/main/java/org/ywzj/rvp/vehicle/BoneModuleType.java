package org.ywzj.rvp.vehicle;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 骨骼模块（Bone Module）类型：一个骨骼可同时挂多个模块，各模块独立判定失效。
 *
 * <p>当前架构落地两个维度的行为，由消费点（{@code RVP_VehicleHitboxFactorManager}、
 * 动画脚本等）按类型分派：</p>
 * <ul>
 *   <li>{@link #ERA}：失效后该骨块不再阻拦弹药（穿透）。</li>
 *   <li>{@link #TRACK}：失效后仍参与碰撞，仅动力损失。</li>
 *   <li>{@link #JAMMER}：干扰设备（消费点 {@code RVP_JammingRuntime}），失效后失去干扰能力。</li>
 *   <li>{@link #APS}：主动防护发射器（消费点 {@code RVP_ApsRuntimeManager}），失效后该侧扇区失去拦截能力。</li>
 *   <li>{@link #COUNTERMEASURE}：干扰物发射装置（消费点 {@code RVP_CountermeasureRuntimeManager}），
 *       载具干扰物配置的 {@code bone_modules} 全部失效后失去抛洒功能。</li>
 *   <li>{@link #DIRCM}：定向红外对抗照射设备（消费点 {@code RVP_DircmRuntimeManager}），
 *       失效后该通道失去激光照射能力。</li>
 *   <li>{@link #ECM_PASSIVE}：被动电子战防御措施（消费点 {@code RVP_EcmPassiveManager}），
 *       失效后不再在被敌对雷达照射时生成假目标。</li>
 *   <li>{@link #ECM_ACTIVE}：主动电子战——按键触发的持续干扰设备（消费点 {@code RVP_EcmActiveManager}）。</li>
 *   <li>{@link #MAINTENANCE}：快速维修——载具内按键触发的回血 + 模块渐进恢复
 *       （消费点 {@code RVP_MaintenanceRuntimeManager}；缺省挂虚拟骨 {@code __vehicle__}，永不可被击毁）。</li>
 *   <li>{@link #RADAR}：雷达部件（消费点 {@code RVP_RadarModuleEnforcer}）——模块失效后该骨对应的
 *       {@code RadarUnit} 被服务端强制 {@code toggle(false)} 关闭（清锁定目标），修好自动开机；
 *       不参与爆炸百分比破坏（只能直击打坏）。多雷达载具按雷达骨分粒度
 *       （如 cssa5/ps1sm 的 {@code lock_radar}/{@code scan_radar} 各自独立失效）。</li>
 *   <li>{@link #ENGINE}：引擎部件（消费点 {@code RVP_EnginePowerHandler}）——窗口内累计直击伤害
 *       分级削动力：超阈一档功率减半（受损）、超阈二档功率清零（瘫痪，进失效表可维修恢复）；
 *       不关发动机、不压 POWER（方向机/高低机照常）；两档均可被快修修复。</li>
 * </ul>
 */
public enum BoneModuleType {
    ERA,
    TRACK,
    JAMMER,
    APS,
    COUNTERMEASURE,
    DIRCM,
    ECM_PASSIVE,
    /** 主动电子战（ECM_ACTIVE）——按键触发的持续干扰设备（消费点 RVP_EcmActiveManager）。 */
    ECM_ACTIVE,
    /**
     * 快速维修（MAINTENANCE）——载具内按键触发的回血 + 模块渐进恢复能力
     * （消费点 {@code RVP_MaintenanceRuntimeManager}）。
     * 缺省经 {@code bone_modules} 挂在虚拟骨 {@code __vehicle__}（载具级能力、永不可被击毁）；
     * 绑定实体骨时可被直击打掉——模块失效后快修无法触发，维修能力即告失去。
     */
    MAINTENANCE,
    /**
     * 雷达部件（RADAR）——挂在雷达 PartUnit 的骨名下（骨名 = 雷达部件 id）。失效后由
     * {@code RVP_RadarModuleEnforcer} 强制关闭对应雷达并堵死三个自动/手动开机点；
     * 单发直毁路径被跳过（见 {@code tryDestroyBoneModules}），失效与否完全由"被命中"
     * 即毁的常规判定决定（每骨 min_damage 门槛照常）。
     */
    RADAR,
    /**
     * 引擎部件（ENGINE）——挂在引擎骨（如 {@code Engine}）名下，带 {@code engine} 子配置
     * （{@link BoneEngineConfig}）。失效不由单发 min_damage 直毁（消费点跳过），
     * 而由 {@code RVP_EngineDamageTable} 窗口累计伤害跨过重损阈值触发。
     */
    ENGINE,
    /**
     * 引擎重创档（ENGINE_DAMAGED）——累计直击伤害跨过受损阈值时写入失效表的"重创"标记，
     * 与 {@link #ENGINE}（瘫痪档）区分：失效后 {@code RVP_EnginePowerHandler} 判定受损档
     * （功率降低），随失效表持久化、维修面板识别为失效设备（可入维修顺序队列指定优先级）、
     * 快修按设备配额恢复（恢复时清该骨引擎累计）。继续累计跨过瘫痪阈值才消耗
     * {@link #ENGINE}（真正的瘫痪档，动力清零趴窝）。
     */
    ENGINE_DAMAGED,
    /**
     * 炮管受损档（BARREL_DAMAGED，2026-09-28 两档炮管）——累计直击伤害跨过受损阈值
     * （{@code threshold_light}）时写入失效表的"受损"标记，与 {@link #BARREL}（彻底损坏档）
     * 区分：失效后射击进入三选一（1/3 正常散布 ×10 / 1/3 哑火 + 30t 封锁 / 1/3 炸膛升级），
     * 随失效表持久化、维修面板红框置顶、可入维修顺序队列、快修/焊枪恢复（清炮管累计）。
     * 继续累计跨过彻底损坏阈值或炸膛才消耗 {@link #BARREL}。
     */
    BARREL_DAMAGED,
    /**
     * 炮管部件（BARREL）——挂在炮管骨名下（本体约定 {@code structure_bone + "_barrel"}，
     * 如 {@code turret_barrel}；同轴机枪站形态 {@code structure_bone} 本身即炮管骨）。
     * 失效不由单发 min_damage 直毁（消费点跳过），而由 {@code RVP_BarrelDamageTable}
     * 累计直击伤害跨过阈值触发（用户 2026-09-28 定版：单档，累计即坏，无衰减永久）。
     * 失效后整个炮管所在武器站禁止射击（含 RVP 自定义弹种切换绕过与 gunner AI 选弹），
     * 提示"炮管损坏，请先维修"；快修恢复（清炮管累计）。
     */
    BARREL;

    private static final BoneModuleType[] VALUES = values();

    /** 按配置/脚本中的名字（大小写不敏感）解析；未知名字返回 null。 */
    public static @Nullable BoneModuleType byName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (BoneModuleType type : VALUES) {
            if (type.name().equals(upper)) {
                return type;
            }
        }
        return null;
    }

    /** 是否为"可被爆炸百分比破坏"的模块（默认仅 ERA 参与爆炸破坏）。 */
    public boolean participatesInBlastDestruction() {
        return this == ERA;
    }
}
