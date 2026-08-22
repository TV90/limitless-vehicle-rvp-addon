package org.ywzj.rvp.radar;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.List;

/**
 * B7 雷达行为近似（替代被删 {@code RadarUnitMixin} 中"炮手服务端扫描"部分）。
 *
 * <p>本体 {@link RadarUnit} 服务端扫描（{@code tickScan}，每 20 tick）只负责
 * RADAR_SEARCH 告警；玩家雷达的目标探测由客户端 {@code tickDetect} 完成并通过
 * DETECT 网络包回写服务端。但没有客户端驾驶的"炮手（{@link GunnerEntity}）"载具
 * 没有来源填充服务端雷达探测表，导致服务端雷达目标（导弹制导校验/武器锁定校验依赖
 * {@link RadarUnit#getDetectedEntities()}）为空。本服务在服务端低频（每 4 tick）扫描
 * 炮手驾驶载具的雷达，把目标写入本体雷达探测表。</p>
 *
 * <p>RVP 弹体：本体 {@link Radar#scanTargets} 的 {@code getBoundingBox().getSize() < 1}
 * 过滤会排除小体积弹体，且 {@code Entity.getBoundingBox()} 为 final 无法用虚拟箱
 * 覆写（原 {@code RadarSignatureMixin} 是 mixin @Redirect，不可复用）——因此 RVP 弹体
 * 一律由 {@link RVP_RadarScanHelper#appendRvpAmmoTargets} 按"最大探测距离 × 信号尺寸"
 * 缩放直接补入雷达探测表，等效实现原 mixin 的 RVP 弹体探测/锁定能力。</p>
 *
 * <p>已知近似：原 mixin 每 tick 扫描，本服务每 4 tick（本体接触保持最短 100ms 可覆盖）；</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RadarScanService {

    private static final int SCAN_INTERVAL_TICK = 4;
    /** 服务端接触保活间隔（tick）：本体 tickTargets 对 phase 雷达（yRotSpeed=0）寿命仅
     *  100ms（2 tick，除法溢出取 Math.max(...,100)），服务端任何超过 2 tick 的扫描间隔
     *  都会导致探测表在两次扫描之间被清空 → 目标"扫描出来瞬间消失/扫不出"。 */
    private static final int CONTACT_HOLD_INTERVAL_TICK = 2;

    private RVP_RadarScanService() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        int tick = server.getTickCount();
        for (ServerLevel level : server.getAllLevels()) {
            // 接触保活：高频刷新已探测条目，防本体 100ms 寿命把服务端表清空
            if (tick % CONTACT_HOLD_INTERVAL_TICK == 0) {
                tickAllContactHold(level);
            }
            if (tick % SCAN_INTERVAL_TICK == 0) {
                scanLevel(level);
            }
        }
    }

    /**
     * 服务端接触保活：对所有载具开启的雷达，刷新仍在扫描体积内目标的接触时间戳
     * （复刻客户端 {@link RVP_ClientRadarTickHandler#tickContactHold} 语义），
     * 移除出高度/方位限位/扇区或死亡的目标——服务端探测表在低频扫描间隙不再瞬间清空。
     * 只遍历各雷达已探测条目（每雷达条目数远小于全实体数），不触发新扫描，开销可忽略。
     */
    private static void tickAllContactHold(ServerLevel level) {
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle vehicle)
                    || vehicle.isDestroyed() || !vehicle.hasPower()) {
                continue;
            }
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (!(partUnit instanceof RadarUnit radar) || !radar.isOn()) {
                    continue;
                }
                tickRadarContactHold(radar);
            }
        }
    }

    /** 单颗雷达接触保活：保活扫描体积内目标、移除出体积/死亡目标（迭代器语义与客户端
     *  {@link RVP_ClientRadarTickHandler#tickContactHold} 一致）。 */
    private static void tickRadarContactHold(RadarUnit radar) {
        float yMin = radar.getYRotMin();
        float yMax = radar.getYRotMax();
        float xRot = radar.getXRot();
        float sectorHalf = radar.getScanSectorAngle() / 2.0f;
        Vec3 radarPos = radar.worldRadarPosition();
        double maxDistSq = radar.getMaxScanDistance() * radar.getMaxScanDistance();
        java.util.Iterator<java.util.Map.Entry<Integer, RadarUnit.DetectedObject>> it =
                radar.getDetectedEntities().entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Integer, RadarUnit.DetectedObject> entry = it.next();
            RadarUnit.DetectedObject detectedObject = entry.getValue();
            Entity targetEntity = detectedObject.entity;
            if (targetEntity == null || !targetEntity.isAlive()) {
                it.remove();
                continue;
            }
            detectedObject.detectedPosition = targetEntity.getBoundingBox().getCenter();
            if (!RVP_RadarScanHelper.isWithinScanHeight(radar, detectedObject.detectedPosition)) {
                it.remove();
                continue;
            }
            // 超出最大扫描距离：移除（防飞出雷达范围的敌机持续显示/保持锁定）
            if (detectedObject.detectedPosition.distanceToSqr(radarPos) > maxDistSq) {
                it.remove();
                continue;
            }
            Vec2 aimRot = radar.aimRot(detectedObject.detectedPosition);
            float y = RVP_RadarScanHelper.normalizeYawForLimits((float) aimRot.y, yMin, yMax);
            if (!RVP_RadarScanHelper.isYawWithin(y, yMin, yMax)
                    || Math.abs(aimRot.x - xRot) > sectorHalf) {
                it.remove();
                continue;
            }
            // 仍在扫描体积内：刷新接触时间戳保活（防本体 100ms 寿命清空）
            radar.detect(targetEntity);
        }
    }

    /** 玩家驾驶载具的服务端补扫间隔（tick）：1 秒一次，仅用于喂饱服务端探测表
     * （远程可见性同步/制导校验等服务端链路读服务端表），显示仍由客户端自扫。
     * 表内条目由 {@link #tickAllContactHold} 高频保活，补扫只负责发现新目标。 */
    private static final int PLAYER_SCAN_INTERVAL_TICK = 20;

    private static void scanLevel(ServerLevel level) {
        int serverTick = level.getServer().getTickCount();
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            if (vehicle.isDestroyed() || !vehicle.hasPower()) {
                continue;
            }
            if (vehicle.getDriver() instanceof GunnerEntity) {
                // 炮手驾驶：无客户端回写来源，服务端低频全量扫描（既有行为）
                scanGunnerRadars(vehicle);
            } else if (vehicle.getDriver() instanceof net.minecraft.server.level.ServerPlayer) {
                // 玩家驾驶：客户端 DETECT 回写会把所有探测写进 getMainRadarUnit() 一颗雷达的表，
                // 多雷达载具（如 ps1sm 搜索+跟踪双雷达）的搜索雷达服务端表恒空 →
                // 远程可见性同步（读服务端表）在 >1024 格外漏掉搜索雷达独占的目标。
                // 此处按载具错相节流补扫，把目标写回各自雷达的服务端探测表。
                if ((serverTick + vehicle.getId()) % PLAYER_SCAN_INTERVAL_TICK != 0) {
                    continue;
                }
                scanGunnerRadars(vehicle);
            }
        }
    }

    private static void scanGunnerRadars(AbstractVehicle vehicle) {
        if (!(vehicle.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        Iterable<Entity> allEntities = serverLevel.getEntities().getAll();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radar) || !radar.isOn()) {
                continue;
            }
            Vec3 radarPos = radar.worldRadarPosition();
            float yRotSpeed = radar.getYRotSpeed();
            float sectorHalf = radar.getScanSectorAngle() / 2.0f;
            float yMin = radar.getYRotMin();
            float yMax = radar.getYRotMax();
            List<Entity> targets = RVP_RadarScanHelper.scanRadarArea(allEntities, vehicle, radarPos, radar.getMaxScanDistance(), entityPos -> {
                if (!RVP_RadarScanHelper.isWithinScanHeight(radar, entityPos)) {
                    return false;
                }
                Vec2 aimRot = radar.aimRot(entityPos);
                float y = RVP_RadarScanHelper.normalizeYawForLimits((float) aimRot.y, yMin, yMax);
                if (!RVP_RadarScanHelper.isYawWithin(y, yMin, yMax)) {
                    return false;
                }
                if (yRotSpeed > 0f && Math.abs(y - radar.getYRot()) > yRotSpeed / 2.0f) {
                    return false;
                }
                return !(Math.abs(aimRot.x - radar.getXRot()) > sectorHalf);
            });
            RVP_RadarScanHelper.filterUndetectableRvpAmmo(targets);
            RVP_RadarScanHelper.appendRvpAmmoTargets(radar, targets, allEntities, yRotSpeed > 0f);
            // 干扰物雷达可扫描性：热焰弹不入表、箔条入表（可被扫描显示）
            RVP_RadarScanHelper.filterRadarInvisibleDecoys(targets);
            RVP_RadarScanHelper.appendRadarVisibleChaffDecoys(radar, targets, allEntities);
            for (Entity target : targets) {
                radar.detect(target);
            }
        }
    }
}
