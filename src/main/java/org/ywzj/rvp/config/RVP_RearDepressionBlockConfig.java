package org.ywzj.rvp.config;

import com.google.gson.annotations.SerializedName;

/**
 * 车尾分段无俯角配置（载具 JSON 武器站字段 {@code rvp_rear_depression_block}）。
 *
 * <p>本体坐标约定：{@code xRot} 负值 = 仰角（向上），正值 = 俯角（向下），
 * {@code x_rot_max} 是俯角上限。炮塔局部偏航偏离车尾轴线（180°）不超过
 * {@code half_angle_deg} 时，把本站 {@code x_rot_max} 压到 0°（炮管最低只能水平），
 * 避免高耸的发动机舱被炮管压穿；再往外 {@code blend_deg} 的余量段内线性过渡回
 * 原 {@code x_rot_max}。字段缺失 = 功能关闭，本站俯仰限位不变。</p>
 */
public class RVP_RearDepressionBlockConfig {

    /** 无俯角核心半角（度），以车尾轴线为中心。 */
    @SerializedName("half_angle_deg")
    private float halfAngleDeg = 28.0f;

    /** 从无俯角过渡回原限位的余量角（度）；0 表示在核心边界直接切换。 */
    @SerializedName("blend_deg")
    private float blendDeg = 8.0f;

    public float halfAngleDeg() {
        return Math.max(0.0f, Math.min(180.0f, halfAngleDeg));
    }

    public float blendDeg() {
        return Math.max(0.0f, blendDeg);
    }
}
