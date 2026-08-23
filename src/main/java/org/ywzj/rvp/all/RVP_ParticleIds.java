package org.ywzj.rvp.all;

import net.minecraft.resources.ResourceLocation;

/** 不依赖 Forge 注册器的 RVP 粒子数据 ID，供配置门控与注册表共用。 */
public final class RVP_ParticleIds {

    /** 白磷粒子稳定数据 ID；必须与载具包 {@code particle_type} 保持一致。 */
    public static final ResourceLocation WHITE_PHOSPHORUS =
            ResourceLocation.fromNamespaceAndPath("rvp", "white_phosphorus");

    private RVP_ParticleIds() {
    }
}
