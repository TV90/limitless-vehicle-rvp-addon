package org.ywzj.rvp.dircm;

import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

/**
 * DIRCM 干扰判定公共工具：哪些制导类型<b>可被干扰</b>。
 *
 * <p>所有 RVP 导弹/火箭/炸弹（{@code RVP_BaseBullet} 子类）都能<b>触发</b> DIRCM
 * （占通道），但只有下列制导类型在被照射时会被干扰（丢制导 + 强制偏转）：</p>
 * <ul>
 *   <li>{@link RVP_EnumGuidanceType#IR} 红外被动</li>
 *   <li>{@link RVP_EnumGuidanceType#AIR} 红外空对空</li>
 *   <li>{@link RVP_EnumGuidanceType#HITL_TV} 人在回路（电视指令）</li>
 *   <li>{@link RVP_EnumGuidanceType#HITL_CLOS_TV} 人在回路（瞄准线电视）</li>
 * </ul>
 *
 * <p><b>ARH/SARH 主动/半主动雷达导引头不受 DIRCM 影响</b>——DIRCM 是定向红外对抗，
 * 激光致盲红外导引头，对雷达导引头无效。注意与 {@code RVP_JammingDeviceType.interferesWith}
 * （光电干扰机干扰 SACLOS / 电磁干扰机干扰 ARH/SARH）相互独立。</p>
 */
public final class RVP_DircmGuidanceSupport {

    private RVP_DircmGuidanceSupport() {
    }

    /** 该制导类型是否可被 DIRCM 干扰。 */
    public static boolean canJam(RVP_EnumGuidanceType guidanceType) {
        if (guidanceType == null) {
            return false;
        }
        return guidanceType == RVP_EnumGuidanceType.IR
                || guidanceType == RVP_EnumGuidanceType.AIR
                || guidanceType == RVP_EnumGuidanceType.HITL_TV
                || guidanceType == RVP_EnumGuidanceType.HITL_CLOS_TV;
    }
}