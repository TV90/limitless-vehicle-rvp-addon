package org.ywzj.rvp.event;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.data.RVP_DamageFactor;
import org.ywzj.rvp.weapon.effects.RVP_ExplosionDamageFactor;

/**
 * RVP 弹体爆炸对玩家的倍率：复用 {@code explosion_damage_factor} 里已有的 {@code player} 键。
 *
 * <p>{@link RVP_DamageFactor} 天生就有 {@code player}（对玩家）与 {@code living}（对非玩家生物）
 * 两个键，{@code getFactor(Player)} 返回的就是 {@code player}；载具侧由
 * {@code RVP_VehicleHurtScalingHandler} 消费同一张表，因此本处理器只补上「玩家」这一条漏掉的路径，
 * <b>不引入任何新字段</b>。</p>
 *
 * <p>本体 {@code VehicleExplosion.hurt} 对 LivingEntity 先 post {@link LivingHurtEvent} 再按
 * {@code getAmount()} 结算（{@code VehicleExplosion:418}），这里只改事件数值：不改本体、不用 Mixin。
 * 仅 Player + 仅 RVP 弹体爆炸窗口（{@code RVP_ExplosionDamageFactor.current() != null}）生效，
 * 未配置 {@code player} 时恒 1.0（直接返回）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ExplosionPlayerDamageHandler {

    private RVP_ExplosionPlayerDamageHandler() {}

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        // 只处理本体载具爆炸（RVP 弹体的 triggerExplosion 全部走 VehicleExplosion）
        if (!"ywzj_vehicle.explosion".equals(event.getSource().getMsgId())) {
            return;
        }
        RVP_DamageFactor table = RVP_ExplosionDamageFactor.current();
        if (table == null) {
            // 窗口外 = 非 RVP 弹体爆炸，保持原值
            return;
        }
        float factor = table.getFactor(player);
        if (factor == 1f) {
            return;
        }
        event.setAmount(Math.max(0f, event.getAmount() * factor));
    }
}
