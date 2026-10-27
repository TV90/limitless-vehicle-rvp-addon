package org.ywzj.rvp.event;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.data.RVP_DamageFactor;
import org.ywzj.rvp.weapon.effects.RVP_ExplosionDamageFactor;

import java.util.HashSet;
import java.util.Set;

/**
 * RVP 弹体爆炸对生物（含玩家）的倍率：复用 {@code explosion_damage_factor} 里的
 * {@code player}（玩家）与 {@code living}（非玩家生物）两个键。
 *
 * <p><b>2026-10-24</b>：原实现只处理 {@code Player}，{@code living} 键对非玩家生物完全无效。
 * 现扩展到全部 {@link LivingEntity}，由 {@link RVP_DamageFactor#getFactor} 自动分流。</p>
 *
 * <p><b>2026-10-25 关键修正：必须按 tick 去重。</b>本体 {@code VehicleExplosion.hurt} 对
 * LivingEntity 会<b>手动 post 一次</b> {@link LivingHurtEvent}（改完 amount 后再结算），而
 * {@code EntityUtil.hurt -> LivingEntity.hurt} 内部<b>又 post 一次</b>，两次都落在
 * {@link RVP_ExplosionDamageFactor} 窗口内、都会进本方法 —— 不去重会把倍率连乘两次。
 * 125mm 榴弹 {@code living=0.2} 连乘成 0.04，200 的爆炸只剩 <b>8</b>，正是用户实测
 * 「直击 -9 / 边缘 -7」的来源（边缘 8 × 距离衰减 0.875 ≈ 7）。</p>
 *
 * <p>载具不走本条：{@code AbstractVehicle}（→ {@code ContainerCraft}）继承 {@code Entity}
 * 而非 {@code LivingEntity}，本体爆炸路径对它直接 {@code EntityUtil.hurt} 且不手动 post，
 * 只 post 一次，载具侧倍率由 {@code RVP_VehicleHurtScalingHandler} 消费。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ExplosionPlayerDamageHandler {

    /**
     * 本 tick 已应用过武器侧爆炸倍率的实体 ID。
     *
     * <p>一次 {@code explode()} 同步跑完，两次 post 必定同 tick，所以按 tick 清空即可。
     * 同 tick 内两发独立爆炸命中同一目标时第二发会被跳过，但它本来也基本被原版无敌帧吞掉。</p>
     */
    private static final Set<Integer> APPLIED_THIS_TICK = new HashSet<>();

    private RVP_ExplosionPlayerDamageHandler() {}

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) {
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
        // 同 tick 的第二次 post（原版 LivingEntity.hurt 内部那次）不再乘：倍率已在上一次生效
        if (!APPLIED_THIS_TICK.add(target.getId())) {
            return;
        }
        // Player → player 键；其它 LivingEntity → living 键
        float factor = table.getFactor(target);
        if (factor == 1f) {
            return;
        }
        event.setAmount(Math.max(0f, event.getAmount() * factor));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !APPLIED_THIS_TICK.isEmpty()) {
            APPLIED_THIS_TICK.clear();
        }
    }
}
