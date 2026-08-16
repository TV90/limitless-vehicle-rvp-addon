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
        if (server.getTickCount() % SCAN_INTERVAL_TICK != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level);
        }
    }

    private static void scanLevel(ServerLevel level) {
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            if (vehicle.isDestroyed() || !vehicle.hasPower()) {
                continue;
            }
            if (!(vehicle.getDriver() instanceof GunnerEntity)) {
                continue;
            }
            scanGunnerRadars(vehicle);
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
