package org.ywzj.rvp.all;

import net.minecraft.resources.ResourceLocation;

/** 不依赖 Forge 注册器的 RVP 粒子数据 ID，供配置门控与注册表共用。 */
public final class RVP_ParticleIds {

    /** 白磷粒子稳定数据 ID；必须与载具包 {@code particle_type} 保持一致。 */
    public static final ResourceLocation WHITE_PHOSPHORUS =
            ResourceLocation.fromNamespaceAndPath("rvp", "white_phosphorus");

    /** MCHR 风格默认爆炸：翻滚灰黄大烟（vanilla big_smoke_0..11 帧）。 */
    public static final ResourceLocation MCHR_SMOKE =
            ResourceLocation.fromNamespaceAndPath("rvp", "mchr_smoke");

    /** MCHR 风格默认爆炸：曳光火星（nuclear/flare.png 光斑，拖烟语义见粒子类）。 */
    public static final ResourceLocation MCHR_FLARE =
            ResourceLocation.fromNamespaceAndPath("rvp", "mchr_flare");

    private RVP_ParticleIds() {
    }
}
