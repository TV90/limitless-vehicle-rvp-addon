package org.ywzj.rvp.sbw;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.compat.RVP_SuperbWarfareCompat;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.countermeasure.RVP_Decoy;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.countermeasure.server.RVP_CountermeasureRuntimeManager;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CMissileTrackAlert;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SBW 制导导弹威胁管理器（服务端）。
 *
 * <p>对齐 {@code RVP_CountermeasureEventHandler} 的注册与遍历范式：{@code @Mod.EventBusSubscriber}
 * 总在 Forge 总线注册，但处理方法首行即 {@link RVP_SuperbWarfareCompat#isLoaded()} 短路——卸载 SBW 时
 * 永不触碰 SBW 类，RVP 照常运行。</p>
 *
 * <p>每 {@link #SCAN_INTERVAL} tick 扫描世界内 SBW 的 {@code guideType=0}（红外热寻的）导弹：</p>
 * <ul>
 *     <li>锁定目标是 RVP 载具 → 发送红外告警（{@code S2CMissileTrackAlert.TYPE_IR}），复用现有告警 UI；</li>
 *     <li>目标处于 RVP 烟雾中 → 使其丢失锁定（烟雾致盲）；</li>
 *     <li>导弹 32 格 / 60° 锥内存在 RVP 干扰物（热焰弹 / 烟雾弹）→ 诱偏至干扰物；</li>
 *     <li>被锁定载具由 gunner 驾驶 → 复用 RVP 干扰物齐射入口自动抛洒热焰弹 / 烟雾弹。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_SbwThreatManager {

    /** 扫描间隔（tick）：与 RVP 自身导弹告警发送频率（4 tick）对齐。 */
    private static final int SCAN_INTERVAL = 4;
    /** 干扰物诱偏检测范围（米），对齐 SBW MissileProjectile.distractedByDecoy 的 32 格。 */
    private static final double DECOY_RANGE = 32.0;
    /** 干扰物诱偏锥半角（度），对齐 SBW SeekTool.seekLivingEntities 的 60。 */
    private static final double DECOY_HALF_ANGLE_DEG = 60.0;
    /** gunner 自动抛洒冷却（单位：扫描次数，每次扫描间隔 SCAN_INTERVAL tick）。 */
    private static final int AUTO_DEPLOY_COOLDOWN = 10;

    private RVP_SbwThreatManager() {
    }

    /** 每载具 gunner 自动抛洒冷却（剩余扫描次数）。 */
    private static final Map<Integer, Integer> autoDeployCd = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 卸载 SBW 时直接短路，永不加载 SBW 类
        if (!RVP_SuperbWarfareCompat.isLoaded()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // 本 tick 内被 SBW 红外导弹锁定的载具 id 集合（用于清理过期冷却条目）
        Set<Integer> lockedIds = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level, lockedIds);
        }
        // 清理已不再被锁定的载具的自动抛洒冷却，避免映射无限增长
        autoDeployCd.keySet().removeIf(id -> !lockedIds.contains(id));
    }

    private static void scanLevel(ServerLevel level, Set<Integer> lockedIds) {
        // 先快照 SBW guideType=0 导弹列表与 uuid->实体 索引，避免遍历中抛洒实体导致活列表膨胀
        List<Entity> sbwMissiles = new ArrayList<>();
        Map<String, Entity> uuidIndex = new HashMap<>();
        Set<AbstractVehicle> lockedVehicles = new HashSet<>();
        for (Entity entity : level.getEntities().getAll()) {
            uuidIndex.put(entity.getStringUUID(), entity);
            if (RVP_SuperbWarfareCompat.isSbwMissile(entity)
                    && RVP_SuperbWarfareCompat.getGuideType(entity) == 0) {
                sbwMissiles.add(entity);
            }
        }
        for (Entity missile : sbwMissiles) {
            String targetUuid = RVP_SuperbWarfareCompat.getTargetUuid(missile);
            if (targetUuid == null || targetUuid.equals("none")) {
                continue;
            }
            Entity target = uuidIndex.get(targetUuid);
            if (!(target instanceof AbstractVehicle vehicle)) {
                continue;
            }
            handleMissile(level, missile, vehicle);
            lockedVehicles.add(vehicle);
            lockedIds.add(vehicle.getId());
        }
        // 每台被锁定载具每扫描周期触发一次 gunner 自动抛洒（去重，避免多导弹重复触发）
        for (AbstractVehicle vehicle : lockedVehicles) {
            maybeAutoDeploy(vehicle);
        }
    }

    private static void handleMissile(ServerLevel level, Entity missile, AbstractVehicle vehicle) {
        // 红外告警：发给追踪该载具的玩家（复用 RVP 现有 S2CMissileTrackAlert 链路与告警 UI）
        RVP_Network.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CMissileTrackAlert(missile.getId(), vehicle.getId(), S2CMissileTrackAlert.TYPE_IR));

        // 烟雾致盲：目标处于 RVP 烟雾中 → 丢失锁定
        if (RVP_CountermeasureState.findSmokeContaining(vehicle) != null) {
            RVP_SuperbWarfareCompat.setTargetUuid(missile, "none");
            return;
        }

        // 干扰物诱偏：导弹附近存在 RVP 热焰弹 / 烟雾弹 → 偏航至干扰物
        Entity decoy = findNearbyDecoy(level, missile);
        if (decoy != null) {
            RVP_SuperbWarfareCompat.setDistracted(missile, true);
            RVP_SuperbWarfareCompat.setTargetUuid(missile, decoy.getStringUUID());
        }
    }

    /** gunner 被 SBW 红外导弹锁定时的自动抛洒：复用 RVP 干扰物齐射入口抛洒热焰弹 / 烟雾弹。 */
    private static void maybeAutoDeploy(AbstractVehicle vehicle) {
        // 仅 gunner 驾驶的载具（对齐本体 isGunnerDriven：driver 为 GunnerEntity）
        if (!(vehicle.getDriver() instanceof GunnerEntity)) {
            return;
        }
        int id = vehicle.getId();
        int cd = autoDeployCd.getOrDefault(id, 0);
        if (cd > 0) {
            autoDeployCd.put(id, cd - 1);
            return;
        }
        // 抛洒热焰弹（IR 对抗）与烟雾弹；fire 内部会检查载具是否配备对应对抗系统，无系统则安全跳过
        RVP_CountermeasureRuntimeManager.fire(vehicle, RVP_EnumCountermeasureType.FLARE);
        RVP_CountermeasureRuntimeManager.fire(vehicle, RVP_EnumCountermeasureType.SMOKE);
        autoDeployCd.put(id, AUTO_DEPLOY_COOLDOWN);
    }

    /** 在导弹 {@link #DECOY_RANGE} 格 / {@link #DECOY_HALF_ANGLE_DEG}° 锥内寻找 RVP 干扰物（热焰弹 / 烟雾弹）。 */
    private static Entity findNearbyDecoy(ServerLevel level, Entity missile) {
        Vec3 origin = missile.position();
        Vec3 forward = missile.getLookAngle().normalize();
        double rangeSq = DECOY_RANGE * DECOY_RANGE;
        double cosThreshold = Math.cos(Math.toRadians(DECOY_HALF_ANGLE_DEG));
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof RVP_Decoy decoy) || !entity.isAlive()) {
                continue;
            }
            RVP_EnumCountermeasureType type = decoy.rvp$decoyType();
            // 仅热焰弹（FLARE）与烟雾弹（SMOKE）对红外导弹生效；箔条（CHAFF）用于雷达制导，不在此列
            if (type != RVP_EnumCountermeasureType.FLARE && type != RVP_EnumCountermeasureType.SMOKE) {
                continue;
            }
            Vec3 to = entity.position().subtract(origin);
            double distSq = to.lengthSqr();
            if (distSq > rangeSq || distSq < 1.0E-6) {
                continue;
            }
            if (to.normalize().dot(forward) >= cosThreshold) {
                return entity;
            }
        }
        return null;
    }
}