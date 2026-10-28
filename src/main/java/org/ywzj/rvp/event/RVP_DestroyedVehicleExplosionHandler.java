package org.ywzj.rvp.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.visual.RVP_AutomaticShockwaveService;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;
import org.ywzj.vehicle.api.event.VehicleAttackEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 把本体载具被击毁时的 {@code VehicleExplosion} 接入 RVP 自动球形冲击波。
 *
 * <p>本体的击毁爆炸没有公开“爆炸所属载具”字段：它先在
 * {@code AbstractVehicle.hurt()} 中调用 {@code setDestroyed()}，再发布
 * {@link ExplosionEvent.Start}。因此这里先记录本 tick 内进入载具伤害流程的候选车，
 * 再在爆炸事件中用“已击毁 + 爆心就是该车位置”确认具体的击毁爆炸。这样普通武器爆炸
 * 不会因为附近存在残骸而误触发。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DestroyedVehicleExplosionHandler {

    /** 当前服务端 tick 内进入载具伤害流程的候选载具，tick 末统一清理。 */
    private static final Map<UUID, AbstractVehicle> ATTACK_CANDIDATES = new HashMap<>();

    /** 爆心与载具位置的平方距离容差，覆盖浮点位置传递误差但不扩大判定范围。 */
    private static final double POSITION_TOLERANCE_SQUARED = 1.0E-8D;

    private RVP_DestroyedVehicleExplosionHandler() {
    }

    /**
     * 记录本体即将执行伤害结算的载具，供紧接着发布的击毁爆炸事件做精确匹配。
     *
     * @param event 本体在 {@code DamageSystem.hurt} 前发布的载具受击事件
     */
    @SubscribeEvent
    public static void onVehicleAttack(VehicleAttackEvent event) {
        // 调用本体事件接口取得受击载具，作为后续识别击毁爆炸的候选对象。
        AbstractVehicle vehicle = event.getVehicle();
        if (vehicle == null) {
            return;
        }
        // 调用本体世界接口确认只在服务端记录；客户端不会执行权威击毁爆炸。
        if (vehicle.level().isClientSide()) {
            return;
        }
        // 调用本体状态接口排除已经进入残骸态的载具，避免后续普通伤害留下候选。
        if (vehicle.isDestroyed()) {
            return;
        }
        // 调用本体实体接口读取稳定 UUID，按实体去重保存本 tick 的候选对象。
        ATTACK_CANDIDATES.put(vehicle.getUUID(), vehicle);
    }

    /**
     * 在本体爆炸开始时识别击毁爆炸，并追加不受 8 格门槛限制的自动球形冲击波。
     *
     * @param event 本体或 RVP 自维护爆炸发布的爆炸开始事件
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosionStart(ExplosionEvent.Start event) {
        // 调用 Forge 事件接口读取爆炸所属世界，只在服务端发布网络视觉事件。
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        // 调用本体爆炸接口读取权威爆心；击毁爆炸的爆心就是被击毁载具的实体位置。
        Explosion explosion = event.getExplosion();
        var explosionPosition = explosion.getPosition();
        AbstractVehicle destroyedVehicle = findDestroyedVehicle(explosionPosition, serverLevel);
        if (destroyedVehicle == null) {
            return;
        }

        // 调用本体载具接口读取主物理碰撞盒；击毁爆炸半径等于主 cube 纵向长度的一半。
        VehicleCubeOBB mainCube = destroyedVehicle.getMainCubeOBB();
        if (mainCube == null) {
            return;
        }
        float radius = (float) mainCube.depth / 2.0F;

        // 调用 RVP 载具击毁专用入口，复用地形采样和网络事件构造但跳过 radius >= 8 门槛。
        RVP_VisualEffectEvent automaticShockwave =
                RVP_AutomaticShockwaveService.prepareForVehicleDestruction(
                        serverLevel, explosionPosition, radius);
        // 调用 RVP 自动冲击波服务发布附加效果；击毁爆炸没有主 RVP 冲击波，因此不去重。
        RVP_AutomaticShockwaveService.publish(serverLevel, automaticShockwave, false);
    }

    /**
     * 在服务端 tick 末清理候选，防止非致命受击记录泄漏到下一个 tick。
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 清理本 tick 的候选表，避免下一个 tick 的普通爆炸复用旧状态。
        ATTACK_CANDIDATES.clear();
    }

    /**
     * 从候选表中找出与爆心完全对应、且刚刚进入残骸态的载具。
     *
     * @param explosionPosition 爆炸权威爆心
     * @param serverLevel       当前服务端世界
     * @return 匹配的击毁载具；没有匹配时返回 {@code null}
     */
    private static AbstractVehicle findDestroyedVehicle(Vec3 explosionPosition, ServerLevel serverLevel) {
        Iterator<Map.Entry<UUID, AbstractVehicle>> iterator = ATTACK_CANDIDATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, AbstractVehicle> entry = iterator.next();
            AbstractVehicle vehicle = entry.getValue();
            // 调用本体世界接口限定维度，避免跨维度候选参与当前爆炸匹配。
            if (vehicle.level() != serverLevel) {
                continue;
            }
            // 调用本体状态接口确认这是已经被击毁、即将执行残骸爆炸的载具。
            if (!vehicle.isDestroyed()) {
                continue;
            }
            // 调用本体位置接口核对爆心，避免普通爆炸命中附近残骸时误发冲击波。
            if (vehicle.position().distanceToSqr(explosionPosition) > POSITION_TOLERANCE_SQUARED) {
                continue;
            }
            // 调用本体实体接口移除已消费候选，保证同一次击毁爆炸不会重复追加。
            iterator.remove();
            return vehicle;
        }
        return null;
    }
}
