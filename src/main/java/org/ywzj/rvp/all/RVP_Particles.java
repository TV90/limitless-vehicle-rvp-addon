package org.ywzj.rvp.all;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** RVP 载具包数据命名空间下的通用粒子类型注册表。 */
public final class RVP_Particles {

    /**
     * 粒子类型延迟注册器；保持载具包 schema 使用的 {@code rvp} 数据 ID，
     * 该注册命名空间与实际贴图所在的 {@code assets/ywzj_rvp} 相互独立。
     */
    private static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, "rvp");

    /** 白磷主体与尾迹共用的简单粒子类型。 */
    public static final RegistryObject<SimpleParticleType> WHITE_PHOSPHORUS =
            PARTICLES.register(RVP_ParticleIds.WHITE_PHOSPHORUS.getPath(),
                    () -> new SimpleParticleType(false));

    /** MCHR 风格默认爆炸：翻滚灰黄大烟。 */
    public static final RegistryObject<SimpleParticleType> MCHR_SMOKE =
            PARTICLES.register(RVP_ParticleIds.MCHR_SMOKE.getPath(),
                    () -> new SimpleParticleType(false));

    /** MCHR 风格默认爆炸：曳光火星。 */
    public static final RegistryObject<SimpleParticleType> MCHR_FLARE =
            PARTICLES.register(RVP_ParticleIds.MCHR_FLARE.getPath(),
                    () -> new SimpleParticleType(false));

    private RVP_Particles() {}

    public static void register(IEventBus eventBus) {
        PARTICLES.register(eventBus);
    }
}
