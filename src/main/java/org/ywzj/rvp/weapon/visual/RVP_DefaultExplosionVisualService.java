package org.ywzj.rvp.weapon.visual;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.server.visual.RVP_NetworkVisualEventPublisher;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/**
 * RVP 内置默认爆炸视觉（MCHR 风格）服务端发布端。
 *
 * <p>把爆心 + 最终半径包装为公共领域事件（{@code effectType = rvp:mchr_explosion}）经
 * {@link RVP_NetworkVisualEventPublisher} 广播，客户端 {@code RVP_DefaultExplosionFactory}
 * 按 {@code baseExplosionRadius} + {@code seed} 内置推导全部粒子数值（不开放 JSON）。</p>
 *
 * <p>音效由同一事件在客户端按武器类型、爆炸半径和听者距离驱动；服务端不再补播
 * {@code GENERIC_EXPLODE}，避免与客户端近远音重复。</p>
 */
public final class RVP_DefaultExplosionVisualService {

    /** 默认爆炸视觉事件类型（客户端工厂按此注册）。 */
    public static final ResourceLocation EFFECT_TYPE =
            ResourceLocation.fromNamespaceAndPath("rvp", "mchr_explosion");

    private static final RVP_NetworkVisualEventPublisher PUBLISHER = new RVP_NetworkVisualEventPublisher();

    private RVP_DefaultExplosionVisualService() {
    }

    /**
     * 发布一次默认爆炸视觉与声音事件。水中爆炸的水花由客户端按爆心流体状态自行判定。
     *
     * @param level 服务端世界
     * @param pos 服务端权威爆心
     * @param radius 已解析引信覆盖后的最终爆炸半径，单位格
     * @param weaponKind RVP 弹体行为类型，用于客户端选择默认爆炸音色
     */
    public static void spawn(ServerLevel level, Vec3 pos, float radius, RVP_EnumWeaponKind weaponKind) {
        RVP_VisualEffectEvent event = new RVP_VisualEffectEvent(
                EFFECT_TYPE,
                ResourceLocation.fromNamespaceAndPath("rvp", "default"),
                // 调用 RVP 默认爆炸事件编码器，把类型化武器分类写入受限公共载荷。
                RVP_DefaultExplosionEventData.encode(weaponKind),
                level.dimension(),
                pos,
                radius,
                1.0f,
                1.0f,
                -1,
                1536.0,
                level.random.nextLong(),
                level.getGameTime(),
                // sound=true：由 rvp:mchr_explosion 客户端实例按声速延迟播放近音或远音
                true,
                true,
                false,
                false);
        // 调用 RVP 网络视觉发布器，把同一份视觉与声音事件发送给广播范围内的客户端。
        PUBLISHER.publish(level, event);
        // 水中状态不进入事件参数：客户端按服务端权威 position 处的流体状态自行判定水花。
    }
}
