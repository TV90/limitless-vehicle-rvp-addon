package org.ywzj.rvp.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CDamageNumber;
import org.ywzj.rvp.weapon.effects.RVP_ExplosionDamageFactor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生物头顶伤害飘字的服务端来源：把「RVP 载具武器对生物造成的伤害」按 tick 汇总后推送。
 *
 * <p>覆盖两条路径，靠同一个 {@link LivingHurtEvent} 收口（原版 {@code LivingEntity.hurt}
 * 内部对直击与爆炸都会 post）：</p>
 * <ul>
 *   <li><b>直击</b>：{@code RVP_BaseBullet.applyEntityHitDamage} → {@code EntityUtil.hurt}</li>
 *   <li><b>爆炸</b>：本体 {@code VehicleExplosion.hurt} → {@code EntityUtil.hurt}，
 *       用 {@link RVP_ExplosionDamageFactor#current()} 窗口区分是否 RVP 弹体的爆炸</li>
 * </ul>
 *
 * <p><b>为什么按 tick 合并</b>：125mm 榴弹命中是「直击 + 碰炸」两段，同一 tick 各 post 一次，
 * 不合并会叠出两个数字。合并后取<b>实际掉血</b>（血量差）而不是事件数值之和——原版无敌帧
 * 会把第二段只补差值，直接加事件数值会虚高（20 + 188 ⇒ 显示 208，实际掉 188）。</p>
 *
 * <p>载具不在处理范围内：{@code AbstractVehicle}（→ {@code ContainerCraft}）继承 {@code Entity}
 * 而非 {@code LivingEntity}，根本不会进入本事件；载具伤害另有命中展板。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DamageNumberHandler {

    /** 同 tick 合并窗口；每次 tick 末清空，强引用只存活一个 tick，不会泄漏。 */
    private static final Map<LivingEntity, Pending> PENDING = new HashMap<>();

    /** 飘字广播半径（格）。 */
    private static final double BROADCAST_RANGE = 96.0D;

    private RVP_DamageNumberHandler() {}

    private static final class Pending {
        private final LivingEntity target;
        /** 本 tick 第一次受伤前的血量（事件 post 时伤害尚未应用）。 */
        private final float healthBefore;
        private float summedAmount;

        private Pending(LivingEntity target) {
            this.target = target;
            this.healthBefore = target.getHealth();
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || event.getAmount() <= 0.0F) {
            return;
        }
        if (!isRvpWeaponDamage(event.getSource())) {
            return;
        }
        Pending pending = PENDING.get(target);
        if (pending == null) {
            pending = new Pending(target);
            PENDING.put(target, pending);
        }
        pending.summedAmount += event.getAmount();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        flush();
    }

    private static void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> batch = new ArrayList<>(PENDING.values());
        PENDING.clear();
        for (Pending pending : batch) {
            LivingEntity target = pending.target;
            if (!(target.level() instanceof ServerLevel level)) {
                continue;
            }
            // 实际掉血优先；极端情况下（目标被治疗、或血量未变但事件已计）退回事件数值之和
            float actual = pending.healthBefore - target.getHealth();
            float shown = actual > 0.0F ? actual : pending.summedAmount;
            if (shown <= 0.0F) {
                continue;
            }
            Vec3 pos = new Vec3(target.getX(),
                    target.getY() + target.getBbHeight() + 0.35D,
                    target.getZ());
            RVP_Network.CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                            pos.x, pos.y, pos.z, BROADCAST_RANGE, level.dimension())),
                    S2CDamageNumber.of(pos.x, pos.y, pos.z, shown));
        }
    }

    /**
     * 判定伤害是否来自 RVP 载具武器。
     *
     * <p>直击看直接来源实体是否为 {@link RVP_BaseBullet}；爆炸看是否为载具爆炸
     * （{@code ywzj_vehicle.explosion}）且处于 RVP 弹体的倍率窗口内（ThreadLocal，爆炸与事件
     * post 在同一服务端线程执行，可正常读取）。其它 mod 或原版的爆炸不在窗口内，不会误触发。</p>
     */
    private static boolean isRvpWeaponDamage(DamageSource source) {
        if (source.getDirectEntity() instanceof RVP_BaseBullet) {
            return true;
        }
        return "ywzj_vehicle.explosion".equals(source.getMsgId())
                && RVP_ExplosionDamageFactor.current() != null;
    }
}
