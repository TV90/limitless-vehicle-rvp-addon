package org.ywzj.rvp.ecm;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.ecm.RVP_EcmIff;
import org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CEcmActiveHudSync;
import org.ywzj.rvp.vehicle.BoneEcmActiveConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.rvp.network.S2CEcmFakeLock;
import org.ywzj.rvp.network.S2CEcmDebug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 主动ECM（ECM_ACTIVE）服务端管理器（P1 最小骨架）。
 *
 * <p>职责：按键触发后的状态机推进（active / cooldown / armPriority）、
 * 音效广播、HUD 同步。完整干扰循环（弹药/载具/RWR/ARM）留到 P3-P5 实现，
 * P1 仅提供可编译的空实现避免阻塞。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_EcmActiveManager {

    /** 主循环节流（tick），与被动ECM对齐 4 tick。 */
    private static final int TICK_INTERVAL = 4;

    /** 载具实体 id → 运行状态。 */
    private static final Map<Integer, RVP_EcmActiveState> STATES = new HashMap<>();

    /** 主动ECM 假目标登记：载具实体 id → 假目标实体 id 列表（与被动 ECM 共享语义，tryDivertSeeker 会合并两者）。 */
    private static final Map<Integer, List<Integer>> ACTIVE_DECOY_IDS = new HashMap<>();

    /** 活动中的 ECM 信息（载具 + 配置 + 状态），供 tick 干扰循环使用。 */
    private record ActiveInfo(AbstractVehicle vehicle, BoneEcmActiveConfig config, RVP_EcmActiveState state) {}

    /** 干扰成功提示节流：载具id → 上次提示 gameTime，避免每 tick 刷屏。 */
    private static final Map<Integer, Long> LAST_JAM_NOTIFY = new HashMap<>();
    /** 干扰成功提示节流间隔（tick），与 DIRCM 对齐。 */
    private static final long JAM_NOTIFY_INTERVAL = 40;

    /** 调试日志开关：确认附近载具是否启动主动ECM、是否对弹药/载具产生干扰。 */
    private static final boolean DEBUG_ECM = true;
    /** 调试日志节流：每 N tick 打印一次总览。 */
    private static final int DEBUG_INTERVAL = 20;

    /** 主动ECM 通用调试日志：同时写 gameDir/logs/rvp_ecm_server.log（单客户端与服务端同目录）。 */
    private static void rvpEcmServerLog(String line) {
        if (!DEBUG_ECM) {
            return;
        }
        String full = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                + "][RVP-ECM] " + line;
        System.out.println(full);
        try {
            net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs").toFile().mkdirs();
            java.nio.file.Path f = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs/rvp_ecm_server.log");
            if (java.nio.file.Files.exists(f) && java.nio.file.Files.size(f) > 256L * 1024L) {
                java.nio.file.Files.delete(f);
            }
            java.nio.file.Files.write(f, (full + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 调试日志写失败不影响游戏
        }
    }

    private RVP_EcmActiveManager() {
    }

    /**
     * 供 GunnerBrain 自动触发（无玩家）：直接按载具触发主动ECM。
     *
     * @return 是否成功触发（冷却中或无配置则 false）
     */
    public static boolean tryFireForVehicle(AbstractVehicle vehicle) {
        if (vehicle == null || !vehicle.isAlive() || !(vehicle.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        BoneEcmActiveConfig config = resolveAliveConfig(vehicle);
        if (config == null) {
            return false;
        }
        RVP_EcmActiveState state = STATES.computeIfAbsent(vehicle.getId(), k -> new RVP_EcmActiveState());
        if (state.getCooldownRemainTicks() > 0 || state.getActiveRemainTicks() > 0) {
            return false;
        }
        state.setActiveRemainTicks(config.activeDurationTicks());
        state.setArmPriorityRemainTicks(config.armPriorityTicks());
        state.setCooldownRemainTicks(config.cooldownTicks());
        serverLevel.playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                RVP_Sounds.ECM_JAMMER.get(), SoundSource.NEUTRAL, 1.0F, 1.0F);
        spawnDecoys(serverLevel, vehicle, config);
        syncHud(vehicle, config, state);
        return true;
    }

    /**
     * 客户端按键触发入口（服务端校验后调用）。
     * 校验骨块存活与冷却，置状态并播放音效。
     */
    public static void onFire(ServerPlayer player, AbstractVehicle vehicle) {
        if (player == null || vehicle == null || !vehicle.isAlive()) {
            return;
        }
        BoneEcmActiveConfig config = resolveAliveConfig(vehicle);
        if (config == null) {
            return;
        }
        RVP_EcmActiveState state = STATES.computeIfAbsent(vehicle.getId(), k -> new RVP_EcmActiveState());
        // 冷却中不可再次触发
        if (state.getCooldownRemainTicks() > 0 || state.getActiveRemainTicks() > 0) {
            return;
        }
        // 进入主动状态
        state.setActiveRemainTicks(config.activeDurationTicks());
        state.setArmPriorityRemainTicks(config.armPriorityTicks());
        // 冷却与 active 同时计时（简化：释放即开始冷却，P1 保持与设计一致的持续+冷却语义）
        state.setCooldownRemainTicks(config.cooldownTicks());

        // 服务端广播音效（设计文档 §5：level.playSound）
        if (vehicle.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                    RVP_Sounds.ECM_JAMMER.get(), SoundSource.NEUTRAL, 1.0F, 1.0F);
        }

        // 生成 6 个假目标（P2：与被动ECM相同机制，硬编码散布半径）
        if (vehicle.level() instanceof ServerLevel serverLevel) {
            spawnDecoys(serverLevel, vehicle, config);
        }

        // 立即同步 HUD
        syncHud(vehicle, config, state);
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
        if (tick % TICK_INTERVAL != 0) {
            return;
        }
        // 状态推进、HUD 同步与主动干扰循环
        for (ServerLevel level : server.getAllLevels()) {
            List<AbstractVehicle> vehicles = new ArrayList<>();
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof AbstractVehicle v && v.isAlive()) {
                    vehicles.add(v);
                }
            }
            // 收集本维度内处于主动干扰状态的 ECM 载具
            List<ActiveInfo> activeInfos = new ArrayList<>();
            for (AbstractVehicle v : vehicles) {
                RVP_EcmActiveState st = STATES.get(v.getId());
                BoneEcmActiveConfig cfg = resolveAliveConfig(v);
                if (st != null && st.isActive() && cfg != null) {
                    activeInfos.add(new ActiveInfo(v, cfg, st));
                }
            }
            for (AbstractVehicle vehicle : vehicles) {
                // 仅对装备主动ECM的载具同步
                if (resolveAliveConfig(vehicle) == null && !STATES.containsKey(vehicle.getId())) {
                    continue;
                }
                // 推送 HUD
                RVP_EcmActiveState state = STATES.get(vehicle.getId());
                if (state != null) {
                    BoneEcmActiveConfig cfg = resolveAliveConfig(vehicle);
                    // 若配置已失效（骨块被打坏）则用最后状态同步
                    syncHud(vehicle, cfg, state);
                }
            }
            // 调试快照：向本维度每位玩家推送主动ECM 调试信息（单客户端走回环网络，按 DEBUG_ECM 开关）
            sendDebugSnapshots(level, activeInfos);

            // P3：弹药干扰（ARH/AIR 中继、SARH、HITL-radio、GPS）
            if (!activeInfos.isEmpty()) {
                if (DEBUG_ECM && tick % DEBUG_INTERVAL == 0) {
                    StringBuilder sb = new StringBuilder("[RVP-ECM][Active] dim=").append(level.dimension().location())
                            .append(" activeEcmCount=").append(activeInfos.size()).append(" :");
                    for (ActiveInfo ai : activeInfos) {
                        sb.append(" {veh=").append(ai.vehicle().getId())
                                .append(" @(").append((int) ai.vehicle().getX()).append(",").append((int) ai.vehicle().getZ()).append(")")
                                .append(" ammoR=").append(ai.config().ammoJamRadius())
                                .append(" vehR=").append(ai.config().vehicleJamRadius())
                                .append(" remain=").append(ai.state().getActiveRemainTicks())
                                .append("}");
                    }
                    System.out.println(sb);
                }
                tickActiveJamming(level, activeInfos);
                // P4：载具干扰（雷达脱锁）与 RWR 伪造
                tickVehicleJamming(level, activeInfos, vehicles);
                // RWR 伪造每 10 tick 一次（与被动ECM的 500ms 窗口对齐，避免过密）
                if (tick % 10 == 0) {
                    tickRwrFake(level, activeInfos, vehicles);
                }
            } else if (DEBUG_ECM && tick % DEBUG_INTERVAL == 0) {
                // 维度内无任何主动ECM活动时，打印一次载具总数以便排查（例如 gunner 载具是否装备 ECM）
                int total = vehicles.size();
                int equipped = 0;
                for (AbstractVehicle v : vehicles) {
                    if (resolveAliveConfig(v) != null) {
                        equipped++;
                    }
                }
                if (equipped > 0) {
                    System.out.println("[RVP-ECM][Active] dim=" + level.dimension().location()
                            + " noActiveEcm but equippedVehicles=" + equipped + "/" + total);
                }
            }
            // P5 的 ARM 优先级将在后续里程碑实现，此处先占位
        }
        tickStates(server);
    }

    private static void tickStates(MinecraftServer server) {
        Iterator<Map.Entry<Integer, RVP_EcmActiveState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, RVP_EcmActiveState> entry = it.next();
            RVP_EcmActiveState state = entry.getValue();
            if (state.getActiveRemainTicks() > 0) {
                state.setActiveRemainTicks(state.getActiveRemainTicks() - TICK_INTERVAL);
            }
            if (state.getCooldownRemainTicks() > 0) {
                state.setCooldownRemainTicks(state.getCooldownRemainTicks() - TICK_INTERVAL);
            }
            if (state.getArmPriorityRemainTicks() > 0) {
                state.setArmPriorityRemainTicks(state.getArmPriorityRemainTicks() - TICK_INTERVAL);
            }
            // 全部归零且载具已不存在则清理
            if (state.getActiveRemainTicks() <= 0 && state.getCooldownRemainTicks() <= 0 && state.getArmPriorityRemainTicks() <= 0) {
                AbstractVehicle owner = findVehicleAcrossLevels(server, entry.getKey());
                if (owner == null || !owner.isAlive() || resolveAliveConfig(owner) == null) {
                    // 清理残余假目标
                    if (owner != null && owner.level() instanceof ServerLevel sl) {
                        clearActiveDecoys(sl, entry.getKey());
                    } else {
                        ACTIVE_DECOY_IDS.remove(entry.getKey());
                    }
                    it.remove();
                } else if (state.getActiveRemainTicks() <= 0 && state.getCooldownRemainTicks() <= 0) {
                    // 就绪状态也保留以便 HUD 显示"就绪"，仅当长时间无操作可清理（P1 简化：保留）
                }
            }
        }
        // 顺带清理已失效/超时的主动假目标登记（避免幽灵 id 累积）
        for (ServerLevel level : server.getAllLevels()) {
            Iterator<Map.Entry<Integer, List<Integer>>> dit = ACTIVE_DECOY_IDS.entrySet().iterator();
            while (dit.hasNext()) {
                Map.Entry<Integer, List<Integer>> e = dit.next();
                // 触发 pruning：collect 会剔除已死亡/卸载的 id
                collectActiveDecoys(level, e.getKey());
                if (e.getValue().isEmpty()) {
                    // 若归属载具已不在 STATES 且假目标已空，则移除空列表
                    if (!STATES.containsKey(e.getKey())) {
                        dit.remove();
                    }
                }
            }
        }
    }

    /**
     * 弹药干扰循环（P3）：对范围内敌对导弹按制导类型施加干扰。
     * 每 TICK_INTERVAL 调用一次，刷新干扰剩余 tick。
     */
    private static void tickActiveJamming(ServerLevel level, List<ActiveInfo> activeInfos) {
        // 收集本维度内所有存活弹药
        List<RVP_BaseBullet> bullets = new ArrayList<>();
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof RVP_BaseBullet bullet && bullet.isAlive()) {
                bullets.add(bullet);
            }
        }
        if (bullets.isEmpty()) {
            return;
        }
        for (ActiveInfo info : activeInfos) {
            AbstractVehicle ecmVehicle = info.vehicle();
            BoneEcmActiveConfig cfg = info.config();
            double ammoRadius = cfg.ammoJamRadius();
            if (ammoRadius <= 0) {
                continue;
            }
            double ammoRadiusSqr = ammoRadius * ammoRadius;
            int inRange = 0;
            int jammed = 0;
            for (RVP_BaseBullet bullet : bullets) {
                // 敌我过滤：不干扰自身或同阵营发射的导弹
                Entity shooterVehicle = bullet.getShooterVehicle();
                if (shooterVehicle == ecmVehicle) {
                    continue;
                }
                if (shooterVehicle instanceof AbstractVehicle sv && RVP_EcmIff.areVehiclesFriendly(ecmVehicle, sv)) {
                    continue;
                }
                // 距离过滤
                double distSqr = bullet.position().distanceToSqr(ecmVehicle.position());
                if (distSqr > ammoRadiusSqr) {
                    continue;
                }
                inRange++;
                RVP_EnumGuidanceType type = bullet.resolveEffectiveGuidanceType();
                // 主动ECM干扰：不可干扰 IR 制导（红外导引头不受电子干扰），跳过
                if (type == RVP_EnumGuidanceType.IR) {
                    if (DEBUG_ECM && level.getGameTime() % DEBUG_INTERVAL == 0) {
                        System.out.println("[RVP-ECM][Ammo] veh=" + ecmVehicle.getId()
                                + " bullet=" + bullet.getId() + " type=IR 跳过(红外不受电子干扰)");
                    }
                    continue;
                }
                // 记录干扰前状态：首次进入干扰时向双方弹 actionbar 提示
                boolean wasJammed = bullet.ecmActiveJamRemainTick > 0;
                if (type == RVP_EnumGuidanceType.ARH || type == RVP_EnumGuidanceType.AIR) {
                    // 中继期才需阻断；直接置干扰 flag，由弹体自身判断中继期是否受影响
                    bullet.ecmActiveJamRemainTick = Math.max(bullet.ecmActiveJamRemainTick, 20);
                    // ARH 在中继期被干扰后禁止重获（AIR 可重获）
                    if (type == RVP_EnumGuidanceType.ARH && bullet instanceof RVP_MissileEntity me && !me.isAutonomousSeekerOn()) {
                        bullet.ecmActiveNoReacquire = true;
                    }
                } else if (type == RVP_EnumGuidanceType.SARH) {
                    bullet.ecmActiveJamRemainTick = Math.max(bullet.ecmActiveJamRemainTick, 20);
                } else if (type == RVP_EnumGuidanceType.HITL_TV || type == RVP_EnumGuidanceType.HITL_CLOS_TV) {
                    // 仅 RADIO 信号的 HITL 才受干扰
                    if (bullet instanceof RVP_MissileEntity me) {
                        // 通过反射式判断：若弹体 hitlSignalSource 为 RADIO 则阻断
                        // 由于字段私有，这里通过 guidanceType 判断已足够；RADIO/FIBER 区分在弹体内部 tick 时再判定
                        // 简化：对 HITL 类型直接置干扰，FIBER 会在弹体内被忽略
                        bullet.ecmActiveJamRemainTick = Math.max(bullet.ecmActiveJamRemainTick, 20);
                    }
                } else if (type == RVP_EnumGuidanceType.GPS) {
                    if (!bullet.ecmGpsOffsetApplied && bullet.getTargetPos() != null) {
                        Vec3 orig = bullet.getTargetPos();
                        double r = cfg.gpsOffsetMeters();
                        if (r > 0) {
                            double angle = level.random.nextDouble() * Math.PI * 2.0D;
                            double rad = Math.sqrt(level.random.nextDouble()) * r;
                            Vec3 offset = new Vec3(Math.cos(angle) * rad, 0.0D, Math.sin(angle) * rad);
                            bullet.setTargetPos(orig.add(offset));
                            bullet.ecmGpsOffsetApplied = true;
                        }
                    }
                } else if (type == RVP_EnumGuidanceType.ARM) {
                    // 反辐射导弹：被干扰期间记忆抖动（R8），由制导源根据 ecmActiveJamRemainTick 施加 ±7m 偏移
                    bullet.ecmActiveJamRemainTick = Math.max(bullet.ecmActiveJamRemainTick, 20);
                }
                // 首次被干扰：向干扰者（司机/乘员）与被干扰弹发射者弹 actionbar 提示（DIRCM 同款）
                if (!wasJammed && bullet.ecmActiveJamRemainTick > 0) {
                    notifyJamAndVictim(ecmVehicle, bullet);
                    if (DEBUG_ECM) {
                        System.out.println("[RVP-ECM][Ammo] veh=" + ecmVehicle.getId()
                                + " 干扰 bullet=" + bullet.getId() + " type=" + type
                                + " remain=" + bullet.ecmActiveJamRemainTick);
                    }
                }
                if (bullet.ecmActiveJamRemainTick > 0) {
                    jammed++;
                }
            }
            if (DEBUG_ECM && (inRange > 0 || jammed > 0)) {
                System.out.println("[RVP-ECM][Ammo] veh=" + ecmVehicle.getId()
                        + " inRange=" + inRange + " jammed=" + jammed
                        + " ammoR=" + ammoRadius);
            }
        }
    }

    /**
     * 干扰成功提示（DIRCM 同款 actionbar，不进聊天栏）：
     * 被干扰弹发射者收到"导弹已被主动ECM干扰"；干扰者收到"主动ECM正在干扰来袭导弹"（节流）。
     */
    private static void notifyJamAndVictim(AbstractVehicle ecmVehicle, RVP_BaseBullet target) {
        if (ecmVehicle.level().isClientSide()) {
            return;
        }
        // 被干扰者提示（导弹发射者，取 Projectile.getOwner()，兼容 Gunner/玩家）
        net.minecraft.world.entity.projectile.Projectile projectile = target;
        Entity owner = projectile.getOwner();
        if (owner instanceof ServerPlayer victim) {
            victim.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.ywzj_rvp.ecm_active_jammed"), true);
        }
        // 干扰者提示（节流）
        long now = ecmVehicle.level().getGameTime();
        Long last = LAST_JAM_NOTIFY.get(ecmVehicle.getId());
        if (last != null && now - last < JAM_NOTIFY_INTERVAL) {
            return;
        }
        LAST_JAM_NOTIFY.put(ecmVehicle.getId(), now);
        // 干扰者：司机或全部乘员
        for (Entity passenger : ecmVehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer jammer) {
                jammer.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.ywzj_rvp.ecm_active_irradiate"), true);
            }
        }
    }

    /**
     * 载具干扰循环（P4）：对范围内敌对载具的雷达执行脱锁 + 禁锁。
     */
    private static void tickVehicleJamming(ServerLevel level, List<ActiveInfo> activeInfos, List<AbstractVehicle> allVehicles) {
        long gameTime = level.getGameTime();
        for (ActiveInfo info : activeInfos) {
            AbstractVehicle ecmVehicle = info.vehicle();
            BoneEcmActiveConfig cfg = info.config();
            if (!cfg.radarUnlock()) {
                continue;
            }
            double radius = cfg.vehicleJamRadius();
            if (radius <= 0) {
                continue;
            }
            double radiusSqr = radius * radius;
            for (AbstractVehicle target : allVehicles) {
                if (target == ecmVehicle || !target.isAlive()) {
                    continue;
                }
                if (RVP_EcmIff.isNeutralRadarVehicle(target)) {
                    continue;
                }
                if (RVP_EcmIff.areVehiclesFriendly(ecmVehicle, target)) {
                    continue;
                }
                double distSqr = ecmVehicle.position().distanceToSqr(target.position());
                if (distSqr > radiusSqr) {
                    continue;
                }
                boolean anyUnlocked = false;
                // 清除目标载具上所有 RadarUnit 的锁定
                for (PartUnit<?> part : target.getPartUnits()) {
                    if (part instanceof RadarUnit radar) {
                        Entity locked = radar.getLockedEntity();
                        if (locked != null) {
                            radar.setLockedEntity(null);
                            anyUnlocked = true;
                        }
                    }
                }
                // 清除 WeaponUnit 的锁定与 pending/外部锁定
                for (PartUnit<?> part : target.getPartUnits()) {
                    if (part instanceof WeaponUnit wu) {
                        WeaponUnit root = wu.getRootParentWeaponUnit();
                        if (root == null) {
                            continue;
                        }
                        Entity lockedW = root.getLockedEntity();
                        if (lockedW != null) {
                            root.setLockedEntity(null);
                            anyUnlocked = true;
                        }
                        int pendingId = RVP_WeaponLockStateTable.getPendingRadarLockEntityId(root);
                        if (pendingId != -1) {
                            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(root);
                            anyUnlocked = true;
                        }
                        int extId = RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root);
                        if (extId != -1) {
                            RVP_WeaponLockStateTable.clearExternalRadarLockedEntityId(root);
                            anyUnlocked = true;
                        }
                    }
                }
                if (anyUnlocked) {
                    RVP_ChaffJamState.setCooldown(target.getUUID(), gameTime, 60);
                    if (DEBUG_ECM) {
                        System.out.println("[RVP-ECM][Vehicle] veh=" + ecmVehicle.getId()
                                + " 解除载具雷达锁定 target=" + target.getId()
                                + " vehR=" + radius);
                    }
                }
            }
        }
    }

    /**
     * RWR 伪造锁定循环（P4）：对范围内敌对载具周期性注入伪造 RADAR_LOCK 告警。
     * 无实体方案：直接发 S2CEcmFakeLock，客户端以递增负数 id 写入 WarningReceiver。
     */
    private static void tickRwrFake(ServerLevel level, List<ActiveInfo> activeInfos, List<AbstractVehicle> allVehicles) {
        for (ActiveInfo info : activeInfos) {
            AbstractVehicle ecmVehicle = info.vehicle();
            BoneEcmActiveConfig cfg = info.config();
            // RWR 伪造锁定半径：沿用载具干扰半径语义（陆地车 vehicle_jam_radius=0 则不产生该效果）
            double radius = cfg.vehicleJamRadius();
            if (radius <= 0) {
                continue;
            }
            double radiusSqr = radius * radius;
            for (AbstractVehicle target : allVehicles) {
                if (target == ecmVehicle || !target.isAlive()) {
                    continue;
                }
                if (RVP_EcmIff.isNeutralRadarVehicle(target)) {
                    if (DEBUG_ECM) {
                        rvpEcmServerLog("[RVP-ECM][RwrFake] veh=" + ecmVehicle.getId()
                                + " 跳过 target=" + target.getId() + " (中立无主)");
                    }
                    continue;
                }
                if (RVP_EcmIff.areVehiclesFriendly(ecmVehicle, target)) {
                    if (DEBUG_ECM) {
                        rvpEcmServerLog("[RVP-ECM][RwrFake] veh=" + ecmVehicle.getId()
                                + " 跳过 target=" + target.getId() + " (友方)");
                    }
                    continue;
                }
                double distSqr = ecmVehicle.position().distanceToSqr(target.position());
                if (distSqr > radiusSqr) {
                    continue;
                }
                if (DEBUG_ECM) {
                    rvpEcmServerLog("[RVP-ECM][RwrFake] veh=" + ecmVehicle.getId()
                            + " 向 target=" + target.getId() + " 注入伪造锁定 radius=" + (int) radius);
                }
                List<String> pool = cfg.fakeLockSources();
                if (pool == null || pool.isEmpty()) {
                    continue;
                }
                int min = cfg.fakeLockMin();
                int max = cfg.fakeLockMax();
                int count = min + (max > min ? level.random.nextInt(max - min + 1) : 0);
                List<String> chosen = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    String pick = pool.get(level.random.nextInt(pool.size()));
                    chosen.add(pick);
                }
                // 保证 count>1 时至少两种不同信号
                if (count > 1) {
                    java.util.Set<String> distinct = new java.util.HashSet<>(chosen);
                    if (distinct.size() == 1 && pool.size() > 1) {
                        String other;
                        do {
                            other = pool.get(level.random.nextInt(pool.size()));
                        } while (other.equals(chosen.get(0)));
                        chosen.set(1, other);
                    }
                }
                S2CEcmFakeLock msg = new S2CEcmFakeLock(target.getId(), chosen, cfg.fakeLockDurationTicks());
                for (Entity passenger : target.getPassengers()) {
                    if (passenger instanceof ServerPlayer sp) {
                        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), msg);
                    }
                }
                RVP_Network.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> target), msg);
            }
        }
    }

    // ===== 假目标生成（P2：与被动ECM相同机制，硬编码散布半径） =====

    /**
     * 生成主动ECM假目标（一次性按配置数量，硬编码半径）。
     */
    private static void spawnDecoys(ServerLevel level, AbstractVehicle owner, BoneEcmActiveConfig config) {
        // 先清理上一波残余
        clearActiveDecoys(level, owner.getId());
        List<Integer> ids = ACTIVE_DECOY_IDS.computeIfAbsent(owner.getId(), k -> new ArrayList<>());
        for (int i = 0; i < config.decoyCount(); i++) {
            spawnOneDecoy(level, owner, config, ids);
        }
    }

    /**
     * 生成单个假目标（逻辑与被动ECM一致：随机半径+已加载区块校验）。
     */
    private static void spawnOneDecoy(ServerLevel level, AbstractVehicle owner,
                                      BoneEcmActiveConfig config, List<Integer> idsOut) {
        var random = level.random;
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double radius = BoneEcmActiveConfig.DECOY_RADIUS_HARDCODED;
        double x = owner.getX();
        double z = owner.getZ();
        double y = owner.getY();
        boolean placed = false;
        for (int attempt = 0; attempt < 8 && !placed; attempt++) {
            double r = radius * Math.sqrt(random.nextDouble());
            x = owner.getX() + Math.cos(angle) * r;
            z = owner.getZ() + Math.sin(angle) * r;
            y = owner.getY() + (random.nextDouble() - 0.35D) * 30.0D;
            y = Math.max(level.getMinBuildHeight() + 8.0D, Math.min(level.getMaxBuildHeight() - 16.0D, y));
            if (level.hasChunkAt(net.minecraft.core.BlockPos.containing(x, y, z))) {
                placed = true;
            } else {
                radius = Math.max(8.0D, radius * 0.5D);
                angle = random.nextDouble() * Math.PI * 2.0D;
            }
        }
        if (!placed) {
            x = owner.getX();
            y = owner.getY();
            z = owner.getZ();
        }
        RVP_EcmDecoyEntity decoy = new RVP_EcmDecoyEntity(RVP_Entities.RVP_ECM_DECOY.get(), level);
        decoy.setPos(x, y, z);
        // 随机航向 + 固定漂移速度（硬编码 0.8~1.5，与被动一致）
        double heading = random.nextDouble() * Math.PI * 2.0D;
        double speed = 0.8D + random.nextDouble() * 0.7D;
        net.minecraft.world.phys.Vec3 drift = new net.minecraft.world.phys.Vec3(Math.sin(heading) * speed, 0.0D, Math.cos(heading) * speed);
        String nctr = config.randomNctrName(random);
        if (nctr == null || nctr.isBlank()) {
            nctr = "F15";
        }
        decoy.initDecoy(owner.getId(), nctr, Math.max(1, config.decoyLifetimeTicks()), drift);
        // 保持区块加载（与被动一致，非必需但一致性）
        EntityUtil.keepChunkLoaded(decoy, decoy.position());
        level.addFreshEntity(decoy);
        idsOut.add(decoy.getId());
    }

    private static void clearActiveDecoys(ServerLevel level, int ownerVehicleId) {
        List<Integer> ids = ACTIVE_DECOY_IDS.remove(ownerVehicleId);
        if (ids == null) {
            return;
        }
        for (int id : ids) {
            net.minecraft.world.entity.Entity entity = level.getEntity(id);
            if (entity instanceof RVP_EcmDecoyEntity decoy && decoy.isAlive()) {
                decoy.discard();
            }
        }
    }

    /**
     * 收集指定归属的主动假目标（存活的），顺带剔除幽灵 id。
     */
    public static List<RVP_EcmDecoyEntity> collectActiveDecoys(ServerLevel level, int ownerVehicleId) {
        List<RVP_EcmDecoyEntity> out = new ArrayList<>();
        List<Integer> ids = ACTIVE_DECOY_IDS.get(ownerVehicleId);
        if (ids == null) {
            return out;
        }
        Iterator<Integer> it = ids.iterator();
        while (it.hasNext()) {
            int id = it.next();
            net.minecraft.world.entity.Entity entity = level.getEntity(id);
            if (entity instanceof RVP_EcmDecoyEntity decoy && decoy.isAlive()) {
                out.add(decoy);
            } else {
                it.remove();
            }
        }
        return out;
    }

    /**
     * 供被动ECM的 tryDivertSeeker 合并时查询：获取指定归属的主动假目标列表（跨维度查找，简化：当前维度）。
     */
    public static List<RVP_EcmDecoyEntity> collectActiveDecoysForOwner(int ownerVehicleId) {
        // 遍历所有维度查找归属载具所在维度
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return List.of();
        }
        for (ServerLevel level : server.getAllLevels()) {
            net.minecraft.world.entity.Entity e = level.getEntity(ownerVehicleId);
            if (e instanceof AbstractVehicle) {
                return collectActiveDecoys(level, ownerVehicleId);
            }
        }
        return List.of();
    }

    /**
     * 是否处于 ARM 高优先级窗口（5 秒内成为最高优先级目标，R8）。
     */
    public static boolean isInArmPriority(int vehicleId) {
        RVP_EcmActiveState state = STATES.get(vehicleId);
        return state != null && state.isArmPriority();
    }

    /**
     * 获取本维度内处于主动干扰状态的 ECM 载具列表（供 ARM 伪脉冲注入使用）。
     */
    public static List<AbstractVehicle> getActiveEcmVehicles(ServerLevel level) {
        List<AbstractVehicle> out = new ArrayList<>();
        for (Map.Entry<Integer, RVP_EcmActiveState> entry : STATES.entrySet()) {
            if (!entry.getValue().isActive()) {
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (entity instanceof AbstractVehicle vehicle && vehicle.isAlive() && resolveAliveConfig(vehicle) != null) {
                out.add(vehicle);
            }
        }
        return out;
    }

    /** 取该载具第一个仍存活的 ECM_ACTIVE 骨块配置。 */
    @Nullable
    private static BoneEcmActiveConfig resolveAliveConfig(AbstractVehicle vehicle) {
        Map<String, BoneEcmActiveConfig> devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveEcmActiveDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, BoneEcmActiveConfig> entry : devices.entrySet()) {
            String boneName = entry.getKey();
            if (RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), boneName, BoneModuleType.ECM_ACTIVE)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 服务端 → 客户端：推送主动ECM HUD 状态（就绪/干扰中/冷却）。P1 空实现：仅同步基础 tick。 */
    private static void syncHud(AbstractVehicle vehicle, @Nullable BoneEcmActiveConfig config, RVP_EcmActiveState state) {
        // 即使 config 为 null（骨块刚被打坏）也尝试同步剩余状态，避免客户端残留
        int active = state.getActiveRemainTicks();
        int cooldown = state.getCooldownRemainTicks();
        int maxActive = config != null ? config.activeDurationTicks() : 200;
        int maxCooldown = config != null ? config.cooldownTicks() : 600;
        S2CEcmActiveHudSync msg = new S2CEcmActiveHudSync(vehicle.getId(), active, cooldown, maxActive, maxCooldown);
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer sp) {
                RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), msg);
            }
        }
        RVP_Network.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> vehicle), msg);
    }

    /**
     * 调试快照：向本维度每位玩家推送主动ECM 调试信息（F10 调试覆盖层读取）。
     * 单客户端模式下集成服务端走回环网络，同样可接收；由 {@link #DEBUG_ECM} 控制是否发送。
     */
    private static void sendDebugSnapshots(ServerLevel level, List<ActiveInfo> activeInfos) {
        if (!DEBUG_ECM) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            // 自身载具 ECM 状态
            boolean ownEquipped = false;
            boolean ownActive = false;
            int ownActiveRemain = 0;
            int ownCooldownRemain = 0;
            Entity veh = player.getVehicle();
            if (veh instanceof AbstractVehicle av) {
                BoneEcmActiveConfig cfg = resolveAliveConfig(av);
                if (cfg != null) {
                    ownEquipped = true;
                    RVP_EcmActiveState st = STATES.get(av.getId());
                    if (st != null) {
                        ownActive = st.isActive();
                        ownActiveRemain = st.getActiveRemainTicks();
                        ownCooldownRemain = st.getCooldownRemainTicks();
                    }
                }
            }
            // 本人发射、当前正被主动ECM 干扰的导弹数
            int myJammed = 0;
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof RVP_BaseBullet b && b.getOwner() == player && b.ecmActiveJamRemainTick > 0) {
                    myJammed++;
                }
            }
            // 附近正在放主动ECM 的载具清单
            List<S2CEcmDebug.Entry> entries = new ArrayList<>();
            for (ActiveInfo ai : activeInfos) {
                double d = ai.vehicle().position().distanceTo(player.position());
                entries.add(new S2CEcmDebug.Entry(
                        ai.vehicle().getId(), (int) d,
                        (int) ai.config().ammoJamRadius(), (int) ai.config().vehicleJamRadius(),
                        ai.state().getActiveRemainTicks()));
                if (entries.size() >= 24) {
                    break;
                }
            }
            S2CEcmDebug msg = new S2CEcmDebug(
                    ownEquipped, ownActive, ownActiveRemain, ownCooldownRemain, myJammed, entries);
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
        }
    }

    public static void onVehicleJoin(AbstractVehicle vehicle, ServerLevel level) {
        int[] saved = RVP_EcmActiveStateSavedData.get(level).readEntry(vehicle.getUUID());
        if (saved != null && saved.length == 3) {
            RVP_EcmActiveState state = new RVP_EcmActiveState();
            state.setActiveRemainTicks(saved[0]);
            state.setCooldownRemainTicks(saved[1]);
            state.setArmPriorityRemainTicks(saved[2]);
            if (state.getActiveRemainTicks() > 0 || state.getCooldownRemainTicks() > 0 || state.isArmPriority()) {
                STATES.put(vehicle.getId(), state);
            }
        }
    }

    public static void onVehicleLeave(AbstractVehicle vehicle, ServerLevel level) {
        RVP_EcmActiveState state = STATES.remove(vehicle.getId());
        if (state != null) {
            int[] out = new int[]{state.getActiveRemainTicks(), state.getCooldownRemainTicks(), state.getArmPriorityRemainTicks()};
            if (out[0] > 0 || out[1] > 0 || out[2] > 0) {
                RVP_EcmActiveStateSavedData.get(level).writeEntry(vehicle.getUUID(), out);
            } else {
                RVP_EcmActiveStateSavedData.get(level).writeEntry(vehicle.getUUID(), null);
            }
        }
        // 清理残余主动假目标（若载具被卸载时仍有存活假目标）
        clearActiveDecoys(level, vehicle.getId());
    }

    @Nullable
    private static AbstractVehicle findVehicleAcrossLevels(MinecraftServer server, int entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(entityId);
            if (e instanceof AbstractVehicle v) {
                return v;
            }
        }
        return null;
    }
}
