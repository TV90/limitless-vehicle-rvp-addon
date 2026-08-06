package org.ywzj.rvp.radar;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端玩家雷达探测近似（替代被删 {@code RadarUnitMixin#ywzj_rvp$tickDetect}）。
 *
 * <p>本体 {@link RadarUnit#tickDetect()}（vanilla）对所有雷达一律使用
 * {@link Radar#detectTargets}，要求目标 yaw 与雷达扫描线（{@code yRot}）偏差
 * 在 {@code yRotSpeed/2} 内——对相控阵雷达（{@code scan_animation_mode == "phase"}，
 * 无机械旋转跟踪线）几乎探测不到目标。原 mixin 对 phase 雷达改用
 * {@link Radar#scanTargets}（只要求 yaw 在 [yRotMin,yRotMax] 内、xRot 在扇区内，
 * 不要求跟踪线），并为所有雷达补入 RVP 弹体（本体按 {@code getBoundingBox().getSize() < 1}
 * 过滤掉小体积弹体）。</p>
 *
 * <p>实体 {@code RadarUnit} 为纯 Forge 服务端启动的"带毒"目标不可再 mixin，本类以
 * 客户端 tick 事件在玩家驾驶载具上执行等价逻辑；本体 vanilla {@code tickDetect} 无法
 * 取消，仍会每 tick 执行（其 DETECT 网络包回写循环会带上本类 detect 的目标，延迟一
 * tick）。非 phase 雷达本体已处理常规目标，本类仅补 RVP 弹体。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientRadarTickHandler {

    private static final Map<String, Integer> SCAN_SKIP_COUNTER = new HashMap<>();
    private static int lastVehicleId = Integer.MIN_VALUE;

    private RVP_ClientRadarTickHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (LocalVehiclePlayer.instance == null) {
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.getVehicle();
        if (vehicle == null || vehicle.level().isClientSide() == false) {
            return;
        }
        if (vehicle.isDestroyed() || !vehicle.hasPower()) {
            return;
        }
        // 炮手驾驶的载具由服务端 RVP_RadarScanService 扫描，本类只管玩家驾驶
        if (vehicle.getDriver() instanceof GunnerEntity) {
            return;
        }
        if (vehicle.getId() != lastVehicleId) {
            lastVehicleId = vehicle.getId();
            SCAN_SKIP_COUNTER.clear();
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        if (LocalVehiclePlayer.instance.getPlayer() != weaponUnit.getOwner()) {
            return;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radar) || !radar.isOn()) {
                continue;
            }
            tickDetect(radar);
        }
    }

    private static void tickDetect(RadarUnit radar) {
        RadarUnitData data = radar.getData();
        RadarUnitDataExt ext = data instanceof RadarUnitDataExt radarExt ? radarExt : null;
        boolean phaseMode = ext != null && "phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode());
        if (phaseMode && shouldSkipScan(radar, ext)) {
            return;
        }
        List<Entity> entities;
        if (phaseMode) {
            Vec3 radarPos = radar.worldRadarPosition();
            entities = Radar.scanTargets(radar.getVehicle(), radarPos, radar.getMaxScanDistance(), entityPos -> {
                if (!RVP_RadarScanHelper.isWithinScanHeight(radar, entityPos)) {
                    return false;
                }
                Vec2 aimRot = radar.aimRot(entityPos);
                return !(aimRot.y < radar.getYRotMin()) && !(aimRot.y > radar.getYRotMax())
                        && !(Math.abs(aimRot.x - radar.getXRot()) > radar.getScanSectorAngle() / 2.0f);
            });
            RVP_RadarScanHelper.filterUndetectableRvpAmmo(entities);
            RVP_RadarScanHelper.appendRvpAmmoTargets(radar, entities, false);
        } else {
            // 非 phase：本体 tickDetect 已用 detectTargets 处理常规目标，这里只补 RVP 弹体（带跟踪线）
            entities = new ArrayList<>();
            RVP_RadarScanHelper.appendRvpAmmoTargets(radar, entities, true);
        }
        for (Entity entity : entities) {
            radar.detect(entity);
        }
    }

    /** 复刻原 mixin {@code shouldSkipScan}：phase 雷达按 scanPeriodTick 节流扫描。 */
    private static boolean shouldSkipScan(RadarUnit radar, RadarUnitDataExt ext) {
        int period = 1;
        int p = ext.ywzj_rvp$getScanPeriodTick();
        if (p > 0) {
            period = p;
        }
        String key = radar.getId();
        int counter = SCAN_SKIP_COUNTER.merge(key, 1, Integer::sum);
        if (counter < period) {
            return true;
        }
        SCAN_SKIP_COUNTER.put(key, 0);
        return false;
    }
}
