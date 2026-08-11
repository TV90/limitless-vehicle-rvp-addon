package org.ywzj.rvp.weapon.damage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CRvpHitIndicator;
import org.ywzj.vehicle.api.event.HitVehicleEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * RVP 命中提示的补发出口：把"被命中载具参数决定 UI"扩展到所有伤害源。
 *
 * <p>本体 {@code DamageSystem.hurt} 对任意 Projectile（本体弹药、原版弓箭、RVP 弹体……）命中载具时
 * 都会 post {@link HitVehicleEvent}，但只有 RVP 弹体自己会发 RVP 命中包 —— 本体武器/弓箭命中
 * RVP 载具时客户端只收到本体命中提示，无法切换到 RVP 展板 UI。本监听器对这些非 RVP 伤害源
 * 命中 {@code hit_indicator_rvp=true} 的载具时，向射手补发 {@link S2CRvpHitIndicator}，实现
 * 与武器无关的"目标载具参数决定 UI"语义。</p>
 *
 * <p>去重：RVP 弹体直击/近炸/爆炸波及结算伤害期间，{@link RVP_BaseBullet} 会用
 * {@link #enterRvpDamage()}/{@link #exitRvpDamage()} 标记（深度计数，支持嵌套伤害结算），
 * 监听器在该窗口内直接跳过 —— RVP 弹体自身已经按骨骼/爆炸语义发过 RVP 命中包。</p>
 *
 * <p>优先级用 LOWEST：在本体 {@code AllEvents.onHitVehicle}（NORMAL）之后执行，保证本体
 * ServerHitVehicleEvent 先发、RVP 包后发，客户端最后处理 RVP 包并清空本体 events（二选一）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_HitVehicleListener {

    /** RVP 弹体伤害结算窗口深度计数（>0 = 正在结算 RVP 弹体伤害，监听器应跳过）。 */
    private static final ThreadLocal<Integer> RVP_DAMAGE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private RVP_HitVehicleListener() {}

    public static void enterRvpDamage() {
        RVP_DAMAGE_DEPTH.set(RVP_DAMAGE_DEPTH.get() + 1);
    }

    public static void exitRvpDamage() {
        RVP_DAMAGE_DEPTH.set(Math.max(0, RVP_DAMAGE_DEPTH.get() - 1));
    }

    public static boolean inRvpDamage() {
        return RVP_DAMAGE_DEPTH.get() > 0;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHitVehicle(HitVehicleEvent event) {
        // RVP 弹体自身已发 RVP 命中包（直击/近炸/爆炸波及），跳过避免双份
        if (inRvpDamage()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerPlayer shooter = server.getPlayerList().getPlayer(event.shooterUuid);
        if (shooter == null) {
            return;
        }
        Level level = shooter.level();
        if (level.isClientSide()) {
            return;
        }
        if (!(level.getEntity(event.entityId) instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!RVP_VehicleHitboxFactorManager.INSTANCE.isHitIndicatorRvpEnabled(vehicle)) {
            return;
        }
        Vec3 hitPos = event.hitPosition;
        if (hitPos == null) {
            return;
        }
        Vec3 hv = event.hitVector;
        Vec3 dir = hv != null && hv.lengthSqr() > 1.0E-6 ? hv.normalize() : Vec3.ZERO;
        // 从本体 OBB 表面命中点反推 RVP 命中箱骨骼（用命中点两侧 0.75 米短线段跨过骨骼 OBB 表面）；
        // 未配置命中箱骨块（倍率骨骼）时 hitBoneName 为 null，resolveHitboxDisplayName 回退为载具名。
        String boneDisp = "";
        if (dir != Vec3.ZERO) {
            RVP_VehicleHitboxFactorManager.HitboxDamageResult res =
                    RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDamage(
                            vehicle, hitPos.subtract(dir.scale(0.75)), hitPos.add(dir.scale(0.75)));
            if (res != null) {
                boneDisp = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDisplayName(
                        vehicle, res.hitBoneName());
            }
        }
        // 非 RVP 伤害源没有 weaponId（客户端回退曳光渲染）；弹药名留空
        RVP_Network.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> shooter),
                S2CRvpHitIndicator.create(
                        vehicle.getId(), hitPos, dir, event.damage, boneDisp, "", null,
                        vehicle.position(),
                        vehicle.getDisplayId().toString(),
                        // 非 RVP 伤害源（本体武器/原版弓箭等）无爆炸语义：不扩散爆炸圈
                        0f));
    }
}
