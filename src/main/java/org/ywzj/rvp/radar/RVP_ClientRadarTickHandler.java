package org.ywzj.rvp.radar;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamHelper;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.network.C2SRadarPowerToggle;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.BulletEntity;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 客户端玩家雷达探测近似（替代被删 {@code RadarUnitMixin#ywzj_rvp$tickDetect} 与
 * {@code RadarUnitMixin#ywzj_rvp$tickTargets}）。
 *
 * <p>本体 {@link RadarUnit#tickDetect()}（vanilla）对所有雷达一律使用
 * {@link Radar#detectTargets}，要求目标 yaw 与雷达扫描线（{@code yRot}）偏差
 * 在 {@code yRotSpeed/2} 内——对相控阵雷达（{@code scan_animation_mode == "phase"}，
 * 无机械旋转跟踪线）几乎探测不到目标。原 mixin 对 phase 雷达改用
 * {@link Radar#scanTargets}（只要求 yaw 在 [yRotMin,yRotMax] 内、xRot 在扇区内，
 * 不要求跟踪线），并为所有雷达补入 RVP 弹体（本体按 {@code getBoundingBox().getSize() < 1}
 * 过滤掉小体积弹体）。</p>
 *
 * <p>原 mixin 还 HEAD-cancel 了 {@code tickTargets}，用自己的接触保持寿命
 * （contactHoldTick / scanPeriodTick / 扫描周期）并清理出扇区/高度、死亡、
 * 不可探测的目标。本类以客户端 tick 事件复刻等价语义：每 tick
 * {@link #tickContactHold(RadarUnit)} 刷新仍在扫描范围内目标的接触时间戳（保活），
 * 移除出扇区/高度/死亡/不可探测目标——目标在扫描间隙不会瞬间消失。</p>
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
    /** 雷达开关状态快照：key = 车辆ID + ":" + 雷达ID，value = isOn()。 */
    private static final Map<String, Boolean> RADAR_POWER_SNAPSHOT = new HashMap<>();

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
        // 退出世界/回到主菜单时玩家实体被清空，getWeaponUnit() 内部会调用
        // getPlayer().getVehicle()，缺少此防护会 NPE 崩溃
        if (LocalVehiclePlayer.instance.getPlayer() == null) {
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
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
            RADAR_POWER_SNAPSHOT.clear();
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        if (LocalVehiclePlayer.instance.getPlayer() != weaponUnit.getOwner()) {
            return;
        }
        // 雷达开关状态同步：客户端 toggle 是本地方法，服务端 isOn() 恒为 true，需把
        // 实际开关状态补发给服务端（外置雷达共享、RADAR_SEARCH 告警等随开关停用）
        syncRadarPowerStates(vehicle);
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radar) || !radar.isOn()) {
                continue;
            }
            RadarUnitData data = radar.getData();
            RadarUnitDataExt ext = data instanceof RadarUnitDataExt radarExt ? radarExt : null;
            // 扫描后保活：每 tick 刷新仍在扫描范围内目标的接触时间戳（复刻原 mixin tickTargets 语义）
            tickContactHold(radar);
            boolean phaseMode = ext != null && "phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode());
            if (phaseMode) {
                if (shouldSkipScan(radar, ext)) {
                    continue;
                }
                scanPhaseRadar(radar);
            } else {
                // 非 phase：本体 tickDetect 已用 detectTargets 处理常规目标，这里只补 RVP 弹体（带跟踪线）
                List<Entity> entities = new ArrayList<>();
                RVP_RadarScanHelper.appendRvpAmmoTargets(radar, entities, true);
                for (Entity entity : entities) {
                    radar.detect(entity);
                }
            }
            // 雷达箔条判定（客户端）：锁定目标周围箔条超阈值 → 脱锁 + 目标禁锁期
            Entity locked = radar.getLockedEntity();
            if (locked != null && locked.isAlive()) {
                RVP_ChaffJamHelper.tryJamLock(vehicle, radar, locked, vehicle.level().getGameTime());
            }
        }
    }

    /**
     * 扫描后保活 + 过期清理（替代被删 {@code RadarUnitMixin#ywzj_rvp$tickTargets} 的
     * 客户端部分）：目标仍在扫描范围内（高度/方位限位/扇区）→ {@link RadarUnit#detect}
     * 刷新接触时间戳，即使扫描节流间隙也不消失；出范围/死亡/不可探测 → 移除。
     */
    private static void tickContactHold(RadarUnit radar) {
        float yMin = radar.getYRotMin();
        float yMax = radar.getYRotMax();
        float xRot = radar.getXRot();
        float sectorHalf = radar.getScanSectorAngle() / 2.0f;
        Iterator<Map.Entry<Integer, RadarUnit.DetectedObject>> it = radar.getDetectedEntities().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, RadarUnit.DetectedObject> entry = it.next();
            RadarUnit.DetectedObject detectedObject = entry.getValue();
            Entity targetEntity = detectedObject.entity;
            if (targetEntity == null || !targetEntity.isAlive()) {
                it.remove();
                continue;
            }
            if (targetEntity instanceof BulletEntity || targetEntity instanceof RVP_BulletEntity
                    || targetEntity instanceof RVP_BaseBullet bullet && !bullet.isRadarDetectableAmmo()) {
                it.remove();
                continue;
            }
            detectedObject.detectedPosition = targetEntity.getBoundingBox().getCenter();
            if (!RVP_RadarScanHelper.isWithinScanHeight(radar, detectedObject.detectedPosition)) {
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
            // 仍在扫描范围内：刷新接触时间戳保活
            radar.detect(targetEntity);
        }
    }

    /** phase 雷达全扇区扫描（替代原 mixin phase 分支，scanTargets 不要求跟踪线）。 */
    private static void scanPhaseRadar(RadarUnit radar) {
        Vec3 radarPos = radar.worldRadarPosition();
        List<Entity> entities = Radar.scanTargets(radar.getVehicle(), radarPos, radar.getMaxScanDistance(), entityPos -> {
            if (!RVP_RadarScanHelper.isWithinScanHeight(radar, entityPos)) {
                return false;
            }
            Vec2 aimRot = radar.aimRot(entityPos);
            return !(aimRot.y < radar.getYRotMin()) && !(aimRot.y > radar.getYRotMax())
                    && !(Math.abs(aimRot.x - radar.getXRot()) > radar.getScanSectorAngle() / 2.0f);
        });
        RVP_RadarScanHelper.filterUndetectableRvpAmmo(entities);
        RVP_RadarScanHelper.appendRvpAmmoTargets(radar, entities, false);
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

    /**
     * 将客户端雷达实际开关状态同步到服务端。
     *
     * <p>本体 {@link RadarUnit#toggle(Boolean)} 是纯客户端本地方法（只改客户端 {@code on} 字段，
     * 不发网络包），服务端 {@code isOn()} 恒为 true：关闭雷达后服务端仍认为雷达在扫描，
     * 外置雷达共享、RADAR_SEARCH 告警等链路不会随开关停用。此处每 tick 比对快照，状态
     * 变化时补发 {@link C2SRadarPowerToggle}，服务端对对应雷达执行同样的开关。非 mixin
     * 实现：不触碰本体字节码，覆盖所有客户端 toggle 入口（TOGGLE_RADAR 键、HMD 雷达模式等）。</p>
     */
    private static void syncRadarPowerStates(AbstractVehicle vehicle) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radar)) {
                continue;
            }
            String key = vehicle.getId() + ":" + radar.getId();
            boolean on = radar.isOn();
            Boolean cached = RADAR_POWER_SNAPSHOT.put(key, on);
            if (cached == null || cached == on) {
                continue;
            }
            RVP_Network.CHANNEL.sendToServer(new C2SRadarPowerToggle(vehicle.getId(), radar.getId(), on));
        }
    }
}
