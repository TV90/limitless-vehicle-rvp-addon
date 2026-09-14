package org.ywzj.rvp.entity.gunner.behavior.action;

/**
 * Gunner 调用本体与 RVP 写操作的统一入口。
 *
 * <p>阶段 B 仍由 {@code GunnerBrain} 决定顺序；它只能通过本入口取得各领域动作适配器，
 * 为阶段 C 的意图仲裁保留稳定执行边界。</p>
 */
public final class RVP_GunnerActionGateway {

    /** 全局无状态动作网关。 */
    public static final RVP_GunnerActionGateway INSTANCE = new RVP_GunnerActionGateway();

    /** 载具移动控制适配器。 */
    private final RVP_GunnerMovementActions movement = new RVP_GunnerMovementActions();
    /** 制导控制源适配器。 */
    private final RVP_GunnerGuidanceActions guidance = new RVP_GunnerGuidanceActions();
    /** 武器瞄准与发射事务适配器。 */
    private final RVP_GunnerWeaponActions weapons = new RVP_GunnerWeaponActions(guidance);
    /** 本车及外置雷达适配器。 */
    private final RVP_GunnerRadarActions radar = new RVP_GunnerRadarActions();
    /** 干扰物与主动 ECM 适配器。 */
    private final RVP_GunnerDefenseActions defense = new RVP_GunnerDefenseActions(weapons);
    /** 司机载具补给适配器。 */
    private final RVP_GunnerSupplyActions supply = new RVP_GunnerSupplyActions();

    private RVP_GunnerActionGateway() {
    }

    /** 返回载具移动控制适配器。 */
    public RVP_GunnerMovementActions movement() {
        return movement;
    }

    /** 返回武器瞄准与发射事务适配器。 */
    public RVP_GunnerWeaponActions weapons() {
        return weapons;
    }

    /** 返回本车及外置雷达适配器。 */
    public RVP_GunnerRadarActions radar() {
        return radar;
    }

    /** 返回制导控制源适配器。 */
    public RVP_GunnerGuidanceActions guidance() {
        return guidance;
    }

    /** 返回干扰物与主动 ECM 适配器。 */
    public RVP_GunnerDefenseActions defense() {
        return defense;
    }

    /** 返回司机载具补给适配器。 */
    public RVP_GunnerSupplyActions supply() {
        return supply;
    }
}
