package org.ywzj.rvp.weapon.visual;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.server.visual.RVP_NetworkVisualEventPublisher;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/**
 * RVP 内置默认爆炸视觉（MCHR 风格）服务端发布端。
 *
 * <p>把爆心 + 最终半径包装为公共领域事件（{@code effectType = rvp:mchr_explosion}）经
 * {@link RVP_NetworkVisualEventPublisher} 广播，客户端 {@code RVP_DefaultExplosionFactory}
 * 按 {@code baseExplosionRadius} + {@code seed} 内置推导全部粒子数值（不开放 JSON）。</p>
 *
 * <p>音效：本体分级爆炸音随 {@code ServerVehicleExplosion} 视觉包一起被抑制门面取消
 * （负半径标记会取消整个客户端 {@code effect()}），因此本服务端按本体同款分级音量补播
 * {@code GENERIC_EXPLODE}（≤2→2 / ≤8→4 / ≤32→8 / >32→14）。</p>
 */
public final class RVP_DefaultExplosionVisualService {

    /** 默认爆炸视觉事件类型（客户端工厂按此注册）。 */
    public static final ResourceLocation EFFECT_TYPE =
            ResourceLocation.fromNamespaceAndPath("rvp", "mchr_explosion");

    private static final RVP_NetworkVisualEventPublisher PUBLISHER = new RVP_NetworkVisualEventPublisher();

    private RVP_DefaultExplosionVisualService() {
    }

    /**
     * 发布一次默认爆炸视觉 + 分级爆炸音。水中爆炸的水花由客户端按爆心流体状态自行判定。
     */
    public static void spawn(ServerLevel level, Vec3 pos, float radius) {
        RVP_VisualEffectEvent event = new RVP_VisualEffectEvent(
                EFFECT_TYPE,
                ResourceLocation.fromNamespaceAndPath("rvp", "default"),
                "{}",
                level.dimension(),
                pos,
                radius,
                1.0f,
                1.0f,
                -1,
                1536.0,
                level.random.nextLong(),
                level.getGameTime(),
                // sound=false：音效由本服务端按本体分级音量补播，客户端工厂不重复播放
                false,
                true,
                false,
                false);
        PUBLISHER.publish(level, event);
        playTieredExplosionSound(level, pos, radius);
        // water 标记由事件 seed 通道外的工厂逻辑消费：客户端按 position 处的流体自行判定亦可，
        // 这里显式携带（encode 进 presetData 不必要），以广播事件为准时客户端用同一判定兜底。
    }

    /** 按本体 {@code VehicleExplosion.effect} 的分级音量补播爆炸音（视觉被抑制，音效保持本体手感）。 */
    private static void playTieredExplosionSound(ServerLevel level, Vec3 pos, float radius) {
        float volume = radius <= 2f ? 2.0f : radius <= 8f ? 4.0f : radius <= 32f ? 8.0f : 14.0f;
        level.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, volume, 1.0f);
    }
}
