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
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureSystemData;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
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
 * 无机械旋转跟踪线）几乎探测不到目标。本类对 phase 雷达改用
 * {@link Radar#scanTargets} 语义的全扇区探测（只要求 yaw 在 [yRotMin,yRotMax] 内、
 * xRot 在扇区内，不要求跟踪线）并补入 RVP 弹体（本体按 {@code getBoundingBox().getSize() < 1}
 * 过滤掉小体积弹体）。2026-09-06 起未配置 phase 的雷达（mechanical/默认）RVP
 * <b>完全不干预</b>，保持纯本体行为（vanilla tickDetect/tickTargets）。</p>
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
 * tick）。非 phase 雷达本类完全不干预（纯本体行为）。</p>
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
            boolean phaseMode = ext != null && "phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode());
            if (phaseMode) {
                // 扫描后保活：每 tick 刷新仍在扫描范围内目标的接触时间戳（复刻原 mixin tickTargets 语义）
                tickContactHold(radar);
                if (shouldSkipScan(radar, ext)) {
                    continue;
                }
                scanPhaseRadar(radar);
            }
            // 未配置 phase 的雷达（mechanical/默认）= 纯本体行为：vanilla tickDetect/tickTargets 自行
            // 探测，RVP 不再补 RVP 弹体/接触保活（2026-09-06 用户决定删除 RVP 机械扫描支持，
            // 本体包/其他载具包的雷达不受 RVP 干预；scan_animation_mode 仅保留 phase 可选值）
            // 雷达箔条判定（客户端）：锁定目标周围箔条超阈值 → 脱锁 + 目标禁锁期（phase/非 phase 雷达都生效）
            Entity locked = radar.getLockedEntity();
            if (locked != null && locked.isAlive()) {
                RVP_ChaffJamHelper.tryJamLock(vehicle, radar, locked, vehicle.level().getGameTime());
            }
        }
        // 外置雷达（中继雷达）锁定箔条干扰：外置锁定的目标被箔条遮蔽时清除外置锁定
        tickExternalLockChaffJam(vehicle, weaponUnit);
    }

    /** 外置雷达锁定箔条判定（客户端）：外置锁定目标周围箔条超阈值 → 清除外置锁定请求并同步服务端。 */
    private static void tickExternalLockChaffJam(AbstractVehicle vehicle, WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return;
        }
        Entity extLocked = RVP_ExternalRadarLinkHelper.getClientLockedEntity(
                vehicle, vehicle.level().dimension().location());
        if (extLocked == null || !extLocked.isAlive()) {
            return;
        }
        RVP_CountermeasureSystemData chaff = RVP_ChaffJamHelper.resolveChaffConfig(extLocked);
        if (chaff == null) {
            return;
        }
        int count = RVP_ChaffJamHelper.countDecoysNear(
                extLocked, chaff.getRadarJamRadius(), org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType.CHAFF);
        if (count < chaff.getRadarJamCount()) {
            return;
        }
        // 清除客户端外置锁定请求与本地侧表，并发送 C2SClearExternalRadarLock 让服务端清除
        RVP_ExternalRadarLinkHelper.clearClientLockRequest(weaponUnit);
        org.ywzj.rvp.countermeasure.RVP_ChaffJamState.setCooldown(
                extLocked.getUUID(), vehicle.level().getGameTime(), chaff.getRadarJamCooldownTick());
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
        Vec3 radarPos = radar.worldRadarPosition();
        double maxDistSq = radar.getMaxScanDistance() * radar.getMaxScanDistance();
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
            // scan_vehicle_only：非载具目标（弹药/干扰物等）不保活，立即移除
            if (RVP_RadarScanHelper.isVehicleOnly(radar) && !(targetEntity instanceof AbstractVehicle)) {
                it.remove();
                continue;
            }
            detectedObject.detectedPosition = targetEntity.getBoundingBox().getCenter();
            if (!RVP_RadarScanHelper.isWithinScanHeight(radar, detectedObject.detectedPosition)) {
                it.remove();
                continue;
            }
            // 超出最大扫描距离：移除（否则保活会令飞出雷达范围的敌机持续显示/保持锁定）
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
            // 仍在扫描范围内：刷新接触时间戳保活
            radar.detect(targetEntity);
        }
    }

    /** phase 雷达全扇区扫描（替代原 mixin phase 分支，scanTargets 不要求跟踪线）。 */
    private static void scanPhaseRadar(RadarUnit radar) {
        if (!(radar.getVehicle().level() instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel)) {
            return;
        }
        // 与本体 Radar.getClientLevelEntities 一致：客户端扫描必须合并 serverEntities（超视距远程实体），
        // 否则 entitiesForRendering() 只含本地跟踪实体，视距外目标扫不到 → 雷达点/锁框消失
        Iterable<Entity> allEntities = mergedClientEntities(clientLevel);
        Vec3 radarPos = radar.worldRadarPosition();
        float yMin = radar.getYRotMin();
        float yMax = radar.getYRotMax();
        float sectorHalf = radar.getScanSectorAngle() / 2.0f;
        List<Entity> entities = RVP_RadarScanHelper.scanRadarArea(allEntities, radar.getVehicle(), radarPos,
                radar.getMaxScanDistance(), entityPos -> {
            if (!RVP_RadarScanHelper.isWithinScanHeight(radar, entityPos)) {
                return false;
            }
            Vec2 aimRot = radar.aimRot(entityPos);
            // 方位角必须归一化后判定：vecToRot 的 yaw 是 [-180,180]，
            // 搜索雷达常配 y_rot_min=0 / y_rot_max=360，原始负 yaw 会被误排（后半球丢失）。
            // 与 tickContactHold / RVP_RadarScanService 服务端扫描保持同一归一化语义。
            float y = RVP_RadarScanHelper.normalizeYawForLimits((float) aimRot.y, yMin, yMax);
            return RVP_RadarScanHelper.isYawWithin(y, yMin, yMax)
                    && !(Math.abs(aimRot.x - radar.getXRot()) > sectorHalf);
        });
        RVP_RadarScanHelper.filterUndetectableRvpAmmo(entities);
        // scan_vehicle_only：仅保留载具目标，跳过弹药/干扰物补入（它们都不是载具）
        if (RVP_RadarScanHelper.isVehicleOnly(radar)) {
            entities.removeIf(entity -> !(entity instanceof AbstractVehicle));
        } else {
            RVP_RadarScanHelper.appendRvpAmmoTargets(radar, entities, allEntities, false);
            // 干扰物雷达可扫描性：热焰弹不入表、箔条入表
            RVP_RadarScanHelper.filterRadarInvisibleDecoys(entities);
            RVP_RadarScanHelper.appendRadarVisibleChaffDecoys(radar, entities, allEntities);
        }
        for (Entity entity : entities) {
            radar.detect(entity);
        }
    }

    /** 客户端已加载实体 = 本地跟踪实体 ∪ serverEntities（去重），复刻本体 getClientLevelEntities 语义。 */
    private static Iterable<Entity> mergedClientEntities(net.minecraft.client.multiplayer.ClientLevel clientLevel) {
        List<Entity> merged = new ArrayList<>();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Entity entity : clientLevel.entitiesForRendering()) {
            merged.add(entity);
            ids.add(entity.getId());
        }
        for (LocalVehiclePlayer.ServerEntity serverEntity : LocalVehiclePlayer.instance.serverEntities.values()) {
            if (serverEntity == null || serverEntity.entity == null || !ids.add(serverEntity.entity.getId())) {
                continue;
            }
            merged.add(serverEntity.entity);
        }
        return merged;
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
