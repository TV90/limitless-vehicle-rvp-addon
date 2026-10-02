package org.ywzj.rvp.weapon.gps;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawner;
import org.ywzj.rvp.util.RVP_AimPointResolver;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileSavedData;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileState;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [RVP] GPS RADAR 模式在途改靶上行（2026-10-02，模式参数化）：射者处于 RADAR 模式且雷达
 * 确认硬锁目标时，按各弹自身武器的 {@code radar_update_interval_second}（默认 5 秒）周期
 * 把锁目标当前位置写为已发射弹的 GPS 目标点——<b>直接改变在飞弹的落点</b>（鹰击-20 反舰
 * 弹道导弹"锁定即打、打后持续修正"）。
 *
 * <p>范围与门禁：</p>
 * <ul>
 *   <li>只更新<b>发射时处于 RADAR 模式且成功标记</b>的弹（{@code radarUplinkTargeting}，
 *       {@code RVP_ProjectileSpawner} 置位），不误伤其它模式/其它武器发射的 GPS 弹；</li>
 *   <li>目标位置每轮都过 <b>25 格离地门</b>（{@code RVP_ProjectileSpawner.RADAR_UPLINK_MAX_HEIGHT_ABOVE_GROUND}）：
 *       锁目标爬高（如舰载机起飞）超过门限即停止更新，各弹保持最后坐标，重新压低后自动恢复；</li>
 *   <li>覆盖实体态与虚拟中段（超视距）弹：实体态 {@code setGuidanceTargetPos} 直写（GPS 源
 *       每 tick 现读），虚拟态 {@code RVP_VirtualMissileState.updateTargetPosition} 替换
 *       snapshot 目标（积分器每 tick 现读）——两态同源，恢复实体无轨迹折线；</li>
 *   <li>更新写"修正真值"，不经 {@code setTargetPos}（不重掷 GPS CEP 散布）。</li>
 * </ul>
 *
 * <p>锁丢失/目标死亡：停更（各弹保持最后坐标）；重新锁定自动恢复。周期游标为运行时表，
 * 键为弹体 entityId / 虚拟 flightUuid，超限整体清空（代价仅一轮立即更新，无正确性影响）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_GpsRadarUplinkManager {

    /** 扫描周期（tick）：5 秒级改靶精度足够，扫描本身很轻（每玩家一次雷达锁解析 + 一次实体查询）。 */
    private static final int SCAN_INTERVAL_TICK = 10;
    /** 实体态弹扫描半径（格），与 RVP_RemoteAmmoSyncService 的在飞弹查询同量级。 */
    private static final double SCAN_RANGE = 6144.0;
    /** 周期游标容量上限：超过即整体清空（全部弹下一轮立即更新一次，随后恢复正常节奏）。 */
    private static final int STAMP_MAP_MAX = 1024;

    /** 实体态弹上次改靶 gameTime（键 = 弹体 entityId）。 */
    private static final Map<Integer, Long> ENTITY_LAST_UPDATE = new HashMap<>();
    /** 虚拟态弹上次改靶 gameTime（键 = flightUuid）。 */
    private static final Map<UUID, Long> VIRTUAL_LAST_UPDATE = new HashMap<>();
    private static int tickCounter;

    private RVP_GpsRadarUplinkManager() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % SCAN_INTERVAL_TICK != 0) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 仅 RADAR 模式的射手参与（模式为服务端权威状态）
            if (!GPSTargetManager.isMode(player, RVP_ClientGPSState.Mode.RADAR)) {
                continue;
            }
            WeaponUnit weaponUnit = RVP_GpsModeSupport.operatedWeaponUnit(player);
            if (weaponUnit == null) {
                continue;
            }
            Entity lockTarget = RVP_RadarRoleHelper.getConfirmedRFHardLockedEntity(weaponUnit);
            if (lockTarget == null || !lockTarget.isAlive()) {
                continue;
            }
            Vec3 targetPos = RVP_AimPointResolver.resolveLargestObbCenter(lockTarget);
            // 25 格离地门：与发射标记同一道门——目标爬高超过门限即停更，各弹保持最后坐标
            if (!(player.level() instanceof ServerLevel level)
                    || aboveGround(level, targetPos) > RVP_ProjectileSpawner.RADAR_UPLINK_MAX_HEIGHT_ABOVE_GROUND) {
                continue;
            }
            updateEntityMissiles(player, level, targetPos);
            updateVirtualMissiles(player, level, targetPos);
        }
    }

    /** 实体态：遍历射者名下带 {@code radarUplinkTargeting} 标记的 GPS 弹，按各自间隔直写目标点。 */
    private static void updateEntityMissiles(ServerPlayer player, ServerLevel level, Vec3 targetPos) {
        long gameTime = level.getGameTime();
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle v ? v : null;
        for (RVP_BaseBullet bullet : level.getEntitiesOfClass(RVP_BaseBullet.class,
                player.getBoundingBox().inflate(SCAN_RANGE),
                bullet -> bullet != null && bullet.isAlive() && !bullet.isRemoved()
                        && bullet.radarUplinkTargeting)) {
            if (!isOwnedBy(bullet, player, playerVehicle)) {
                continue;
            }
            // 仅 GPS 制导弹参与（防御：标记只在 GPS 弹上打，此处再核一道）
            if (bullet.getRvpData() == null || !bullet.getRvpData().usesGuidanceType(RVP_EnumGuidanceType.GPS)) {
                continue;
            }
            float intervalSecond = org.ywzj.rvp.weapon.data.RVP_GuidanceDataGPS
                    .resolveRadarUpdateIntervalSecond(bullet.getRvpData().getGuidanceData());
            long intervalTicks = Math.max(1L, (long) (intervalSecond * 20.0f));
            long last = ENTITY_LAST_UPDATE.getOrDefault(bullet.getId(), Long.MIN_VALUE);
            if (gameTime - last < intervalTicks) {
                continue;
            }
            // 修正真值直写：不经 setTargetPos（避免重掷 GPS CEP 散布），GPS 制导源下一 tick 即读
            bullet.setGuidanceTargetPos(targetPos);
            if (ENTITY_LAST_UPDATE.size() >= STAMP_MAP_MAX) {
                ENTITY_LAST_UPDATE.clear();
            }
            ENTITY_LAST_UPDATE.put(bullet.getId(), gameTime);
        }
    }

    /** 虚拟中段：遍历各维度 SavedData，按 owner/shooterVehicle + 上行标记匹配后替换 snapshot 目标。 */
    private static void updateVirtualMissiles(ServerPlayer player, ServerLevel playerLevel, Vec3 targetPos) {
        long gameTime = playerLevel.getGameTime();
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle v ? v : null;
        UUID ownerUuid = player.getUUID();
        UUID vehicleUuid = playerVehicle == null ? null : playerVehicle.getUUID();
        for (ServerLevel level : player.server.getAllLevels()) {
            RVP_VirtualMissileSavedData savedData = RVP_VirtualMissileSavedData.get(level);
            boolean dirty = false;
            for (RVP_VirtualMissileState state : savedData.states()) {
                if (!state.radarUplinkTargeting || !state.dimension.equals(playerLevel.dimension().location())) {
                    continue;
                }
                boolean owned = (ownerUuid != null && ownerUuid.equals(state.ownerUuid))
                        || (vehicleUuid != null && vehicleUuid.equals(state.shooterVehicleUuid));
                if (!owned) {
                    continue;
                }
                long intervalTicks = Math.max(1L, (long) (state.radarUplinkIntervalSecond * 20.0f));
                long last = VIRTUAL_LAST_UPDATE.getOrDefault(state.flightUuid, Long.MIN_VALUE);
                if (gameTime - last < intervalTicks) {
                    continue;
                }
                state.updateTargetPosition(targetPos);
                if (VIRTUAL_LAST_UPDATE.size() >= STAMP_MAP_MAX) {
                    VIRTUAL_LAST_UPDATE.clear();
                }
                VIRTUAL_LAST_UPDATE.put(state.flightUuid, gameTime);
                dirty = true;
            }
            if (dirty) {
                savedData.setDirty();
            }
        }
    }

    /** 己方判定，与 RVP_RemoteAmmoSyncService.classify 同源：发射者为本玩家或本玩家载具。 */
    private static boolean isOwnedBy(RVP_BaseBullet bullet, ServerPlayer player, AbstractVehicle playerVehicle) {
        return bullet.getOwner() == player
                || (playerVehicle != null && bullet.getShooterVehicle() == playerVehicle);
    }

    /** 目标点离地高度：目标 Y − 所处水平坐标的地表高度（MOTION_BLOCKING，含建筑；水面目标离水≈小值）。 */
    private static double aboveGround(ServerLevel level, Vec3 pos) {
        double groundY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(pos.x), (int) Math.floor(pos.z));
        return pos.y - groundY;
    }
}
