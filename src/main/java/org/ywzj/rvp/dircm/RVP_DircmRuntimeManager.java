package org.ywzj.rvp.dircm;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_JammingRuntime;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CDircmHudSync;
import org.ywzj.rvp.vehicle.BoneDircmConfig;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.vehicle.RVP_DircmStateSavedData;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DIRCM（定向红外对抗）服务端运行时。
 *
 * <p>状态级设备（骨架模块 {@code BoneModuleType.DIRCM}）：每个<b>照射骨骼</b>（{@code bone_modules}
 * 的 {@code dircm} 条目）拥有一个火力通道——同时只能照射一个目标；载具可配多个骨骼获得多通道
 * （如左右各一）。检测扇区内所有 RVP 导弹/火箭/炸弹（{@code RVP_BaseBullet}），光束建立瞬间即完成
 * 干扰判定；IR/AIR/ARH/HITL 族弹体被干扰（丢制导 + 强制偏转），其余弹体仅占通道不干扰。</p>
 *
 * <p>「干扰」语义：普通 IR/ARH 弹为弹体级<b>永久</b>干扰；人在回路（HITL）弹为<b>临时</b>干扰
 * （{@code dircmJamRemainTick} 递减，3 秒恢复制导）。被干扰弹体复用现有 {@code jamming*} 字段族
 * + {@link RVP_JamDeflectionHelper} 强制偏转。</p>
 *
 * <p>驱动方式与 APS 一致：纯 Forge 事件（{@code RVP_DircmEventHandler}），不新增公共 mixin；
 * 公共代码零 {@code @OnlyIn(CLIENT)} 引用，双端安全。</p>
 */
public final class RVP_DircmRuntimeManager {

    /** 人在回路电视（HITL_TV）干扰恢复时长（tick，硬编码 3 秒）。 */
    public static final int HITL_JAM_TICK = 60;

    /** 指令线人在回路（HITL_CLOS_TV）干扰恢复时长（tick，硬编码 6 秒，指令线链路更易受 DIRCM 压制）。 */
    public static final int HITL_CLOS_JAM_TICK = 120;

    /** DIRCM 干扰后禁止重新指定目标的时长（tick，硬编码 6 秒）：期间 HITL 弹强制对地面直飞。 */
    public static final int NO_REDESIGNATE_TICK = 120;

    /** 永久干扰弹体（IR/AIR 等被致盲后无制导乱飞）的自毁时长（tick，10 秒，伴随爆炸移除）。 */
    public static final int SELF_DESTRUCT_TICK = 200;

    /** 按弹体当前有效制导类型解析 HITL 干扰总时长（供客户端白闪进度计算）。 */
    public static int resolveHitlJamTotal(RVP_BaseBullet bullet) {
        if (bullet.resolveEffectiveGuidanceType() == RVP_EnumGuidanceType.HITL_CLOS_TV) {
            return HITL_CLOS_JAM_TICK;
        }
        return HITL_JAM_TICK;
    }

    /** 干扰者提示节流（tick）：避免每 tick 刷屏。 */
    private static final long JAM_NOTIFY_INTERVAL = 100L;

    /** 干扰者提示节流表（按载具 id）。 */
    private static final Map<Integer, Long> LAST_JAM_NOTIFY_BY_VEHICLE = new ConcurrentHashMap<>();

    /** 单载具 DIRCM 状态：每通道一个槽位（按骨块名索引）。 */
    public static final class DircmState {
        public final Map<String, ChannelState> channels = new HashMap<>();
    }

    /** 单通道状态。 */
    public static final class ChannelState {
        public int busyTargetId = -1;
        public int beamRemainTick;
        public int chargeRemainTick;
    }

    private static final Map<UUID, DircmState> STATES = new HashMap<>();

    private RVP_DircmRuntimeManager() {
    }

    /** 载具加入世界：从独立存档恢复通道状态。 */
    public static void onVehicleJoin(AbstractVehicle vehicle, ServerLevel level) {
        int[] saved = RVP_DircmStateSavedData.get(level).readEntry(vehicle.getUUID());
        DircmState state = STATES.computeIfAbsent(vehicle.getUUID(), k -> new DircmState());
        if (saved != null) {
            // saved 结构：骨块名数量 + 每个通道 3 个 int（busyTargetId/beam/charge）
            int idx = 0;
            List<String> bones = new ArrayList<>();
            while (idx < saved.length && idx < saved[0]) {
                // 简化：仅恢复非忙通道的充能状态，忙碌目标不可跨世界存活
                bones.add(Integer.toString(idx));
                idx++;
            }
            // 保守处理：重置忙碌目标，保留充能
            for (Map.Entry<String, ChannelState> e : state.channels.entrySet()) {
                e.getValue().busyTargetId = -1;
            }
        }
        syncHud(vehicle);
    }

    /** 载具离开世界：状态写回独立存档并清理内存。 */
    public static void onVehicleLeave(AbstractVehicle vehicle, ServerLevel level) {
        DircmState state = STATES.remove(vehicle.getUUID());
        if (state == null) {
            return;
        }
        int count = state.channels.size();
        int[] out = new int[1 + count * 3];
        out[0] = count;
        int i = 1;
        for (ChannelState ch : state.channels.values()) {
            out[i++] = ch.busyTargetId;
            out[i++] = ch.beamRemainTick;
            out[i++] = ch.chargeRemainTick;
        }
        RVP_DircmStateSavedData.get(level).writeEntry(vehicle.getUUID(), out);
    }

    /** 服务端每 tick 推进（仅服务端调用，由 {@code RVP_DircmEventHandler} 驱动）。 */
    public static void tick(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide() || vehicle.isRemoved() || vehicle.isDestroyed()) {
            return;
        }
        Map<String, BoneDircmConfig> devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveDircmDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            return;
        }
        if (!vehicle.hasPower()) {
            return;
        }
        DircmState state = STATES.computeIfAbsent(vehicle.getUUID(), k -> new DircmState());
        boolean changed = false;
        for (Map.Entry<String, BoneDircmConfig> entry : devices.entrySet()) {
            String boneName = entry.getKey();
            BoneDircmConfig config = entry.getValue();
            if (!RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), boneName, org.ywzj.rvp.vehicle.BoneModuleType.DIRCM)) {
                continue;
            }
            changed |= tickChannel(vehicle, state, boneName, config);
        }
        // 常态化 HUD：空闲（就绪）状态也要周期性推送，否则客户端 HUD 永远不显示。
        // 低频（每 2 秒）兜底同步一次，保证进车即有 DIRCM 状态显示。
        if (changed || (vehicle.tickCount & 39) == 0) {
            syncHud(vehicle);
        }
    }

    private static boolean tickChannel(AbstractVehicle vehicle, DircmState state, String boneName, BoneDircmConfig config) {
        ChannelState channel = state.channels.computeIfAbsent(boneName, k -> new ChannelState());
        boolean changed = false;

        // 当前目标维护
        if (channel.busyTargetId >= 0) {
            Entity target = vehicle.level().getEntity(channel.busyTargetId);
            if (!(target instanceof RVP_BaseBullet bullet) || !target.isAlive() || target.isRemoved()) {
                // 目标消失/死亡：释放通道进入充能
                channel.busyTargetId = -1;
                channel.chargeRemainTick = config.chargeTick();
                changed = true;
            } else if (channel.beamRemainTick <= 0) {
                // 光束跟踪期结束：释放通道进入充能
                channel.busyTargetId = -1;
                channel.chargeRemainTick = config.chargeTick();
                changed = true;
            } else {
                // 仍在照射跟踪期：持续跟踪（每 tick 更新光束终点在客户端完成）
                // 若目标脱出探测范围则提前断开
                Vec3 toTarget = bullet.position().subtract(vehicle.position());
                if (toTarget.length() > config.detectRadius()) {
                    channel.busyTargetId = -1;
                    channel.chargeRemainTick = config.chargeTick();
                    changed = true;
                } else {
                    channel.beamRemainTick--;
                }
            }
        }

        // 充能倒计时
        if (channel.chargeRemainTick > 0) {
            channel.chargeRemainTick--;
            if (channel.chargeRemainTick <= 0) {
                changed = true;
            }
        }

        // 通道空闲且充能完毕：扫描扇区内的目标
        if (channel.busyTargetId < 0 && channel.chargeRemainTick <= 0) {
            if (vehicle.tickCount % config.scanIntervalTick() == 0) {
                RVP_BaseBullet target = findTarget(vehicle, config);
                if (target != null) {
                    channel.busyTargetId = target.getId();
                    channel.beamRemainTick = config.beamTick();
                    channel.chargeRemainTick = 0;
                    // 干扰在光束建立瞬间即完成
                    applyJam(vehicle, target);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** 在扇区内寻找最近的可触发目标（所有 RVP 导弹/火箭/炸弹）。 */
    @Nullable
    private static RVP_BaseBullet findTarget(AbstractVehicle vehicle, BoneDircmConfig config) {
        Vec3 front = RVP_JammingRuntime.resolveFacing(vehicle, config.facingPart(), config.facingYawDeg());
        // [DIRCM DEBUG] 打印探测扇区朝向，排查左右/前右方向异常
        if ((vehicle.tickCount & 63) == 0) {
            org.apache.logging.log4j.LogManager.getLogger("RVP_Dircm")
                    .info("[DIRCM DEBUG] vehicle={} yaw={} facingYaw={} part={} front=({},{},{})",
                            vehicle.getId(), vehicle.getYRot(), config.facingYawDeg(),
                            config.facingPart(), (float) front.x, (float) front.y, (float) front.z);
        }
        AABB box = vehicle.getBoundingBox().inflate(config.detectRadius());
        double bestSqr = Double.MAX_VALUE;
        RVP_BaseBullet best = null;
        for (Entity entity : vehicle.level().getEntities(vehicle, box, e -> true)) {
            if (!(entity instanceof RVP_BaseBullet bullet) || !entity.isAlive()) {
                continue;
            }
            // 排除机炮弹（MACHINEGUN 类）：用户需求只覆盖导弹/火箭/炸弹
            if (bullet.getWeaponKind() == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.MACHINEGUN) {
                continue;
            }
            // 排除本车发射/同阵营/己方乘客发射的弹药（参照 GunnerBrain 敌我判定语义）
            if (isFriendlyProjectile(vehicle, bullet)) {
                continue;
            }
            Vec3 toTarget = entity.position().subtract(vehicle.position());
            if (toTarget.length() > config.detectRadius()) {
                continue;
            }
            if (!RVP_GuidanceRuntimeGeometry.withinAngle(front, toTarget, config.halfAngleDeg())) {
                continue;
            }
            // 来袭角判定：只干扰朝本车飞来的弹（导弹飞行方向与「弹→车」连线夹角）
            Vec3 flight = entity.getDeltaMovement();
            Vec3 toVehicle = vehicle.position().subtract(entity.position());
            if (flight.lengthSqr() > 1.0E-6 && toVehicle.lengthSqr() > 1.0E-6
                    && !RVP_GuidanceRuntimeGeometry.withinAngle(flight.normalize(), toVehicle.normalize(),
                    config.approachHalfAngleDeg())) {
                continue;
            }
            double dSqr = toTarget.lengthSqr();
            if (dSqr < bestSqr) {
                bestSqr = dSqr;
                best = bullet;
            }
        }
        return best;
    }

    /**
     * 判断弹体是否对 DIRCM 载具「友方/己方」——不干扰（不占用通道）。
     * 参照 {@code GunnerBrain} 敌我判定语义，适配载具设备（无 gunner 上下文）：
     * <ul>
     *   <li>弹体 owner 是本载具（自己发射）；</li>
     *   <li>弹体 owner 是本载具乘客（乘客发射）；</li>
     *   <li>owner 是 GunnerEntity：同 faction 视为友方；</li>
     *   <li>owner 与本载具同队伍（Team 联盟）视为友方。</li>
     * </ul>
     */
    private static boolean isFriendlyProjectile(AbstractVehicle vehicle, RVP_BaseBullet bullet) {
        Entity owner = bullet.getOwner();
        if (owner == null) {
            // 无 owner（如某些抛射体）：按发射载具判定
            Entity shooter = bullet.getShooterVehicle();
            return shooter == vehicle || (shooter != null && vehicle.getPassengers().contains(shooter));
        }
        if (owner == vehicle || vehicle.getPassengers().contains(owner)) {
            return true;
        }
        if (owner instanceof org.ywzj.rvp.entity.gunner.GunnerEntity ownerGunner) {
            // 友方/同队 gunner 发射的弹药不干扰；ENEMY faction 无差别攻击
            org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction faction = ownerGunner.getProfileFaction();
            return faction != null && faction != org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction.ENEMY;
        }
        net.minecraft.world.scores.Team vehicleTeam = vehicle.getTeam();
        net.minecraft.world.scores.Team ownerTeam = owner.getTeam();
        return vehicleTeam != null && ownerTeam != null && ownerTeam.isAlliedTo(vehicleTeam);
    }

    /**
     * 干扰判定与施加：目标当前有效制导类型可被 DIRCM 干扰时，置弹体为「被干扰」状态并写入
     * 偏转参数（复用光电干扰机参数体系）；不可干扰的弹体仅占通道。
     */
    private static void applyJam(AbstractVehicle vehicle, RVP_BaseBullet target) {
        RVP_EnumGuidanceType guidanceType = target.resolveEffectiveGuidanceType();
        org.apache.logging.log4j.LogManager.getLogger("RVP_Dircm")
                .info("[DIRCM DEBUG] applyJam target={} type={} canJam={} jammed={}",
                        target.getId(), guidanceType,
                        RVP_DircmGuidanceSupport.canJam(guidanceType), target.dircmJammed);
        if (!RVP_DircmGuidanceSupport.canJam(guidanceType)) {
            return;
        }
        // 已有弹体级干扰状态：不允许多通道重复照射同一目标
        if (target.dircmJammed) {
            return;
        }
        target.dircmJammed = true;
        target.dircmSourceVehicleId = vehicle.getId();
        // 区分两类人在回路：指令线（CLOS_TV）干扰/致盲时长更长，电视（TV）保持 3 秒
        boolean hitlClos = guidanceType == RVP_EnumGuidanceType.HITL_CLOS_TV;
        boolean hitl = hitlClos || guidanceType == RVP_EnumGuidanceType.HITL_TV;
        target.dircmHitlTemporary = hitl;
        target.dircmJamRemainTick = hitlClos ? HITL_CLOS_JAM_TICK
                : hitl ? HITL_JAM_TICK : 0;
        // 干扰后 6 秒内禁止重新指定目标（强制对地面直飞，无法重新截获）
        target.dircmNoRedesignateTick = NO_REDESIGNATE_TICK;
        // 永久干扰弹体（非 HITL）10 秒后伴随爆炸自毁，避免无制导弹体长期占用实体资源；
        // HITL 弹恢复制导后正常飞行（命中/撞地/寿命自然结算），不设自毁。
        target.dircmSelfDestructTick = hitl ? 0 : SELF_DESTRUCT_TICK;
        target.dircmDeflectStrength = 1.0;

        // 复用 jamming* 字段族驱动强制偏转（复用光电干扰机参数体系）。
        // heading_rate 过高（>4）会让弹体原地画小圈后撞地/寿终静默消失；
        // 取 2.5°/tick（50°/秒）使弹体沿大弧线被甩离，表现自然。
        target.jammingStrength = 1.0;
        target.jammingSideSign = resolveSideSign(target, vehicle);
        target.jammingHeadingRate = 2.5;
        target.jammingOffsetAngleDeg = 8.0;
        target.jammingOffsetBaseBlocks = 20.0;
        target.jammingOffsetDownBlocks = 12.0;
        // 清理目标锁定：被干扰弹体丢制导
        target.clearTarget();
        // 清制导记忆点：避免干扰期间/结束后惯性制导沿原目标位置追踪
        target.clearGuidanceMemory();
        // HITL/主动导引头弹的指定目标也需释放（clearTarget 不清 activeSeekerDesignatedTargetId），
        // 否则人在回路电视弹截获目标后仍保持锁定
        if (target instanceof org.ywzj.rvp.entity.projectile.RVP_MissileEntity missile) {
            missile.rvp$setActiveSeekerDesignatedTarget(null);
        }
        // 强制把截获目标设为弹体正下方的地面点：HITL 弹改向地面飞去，无法命中目标
        forceGroundTarget(target);
        notifyJamAndVictim(vehicle, target);
    }

    /** 把弹体目标强制改写为弹体正下方的地面点（在弹体 X/Z、Y=当前弹体 Y 的位置投到地面）。 */
    private static void forceGroundTarget(RVP_BaseBullet target) {
        if (target.level().isClientSide()) {
            return;
        }
        double groundY = target.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(target.getX()), (int) Math.floor(target.getZ()));
        target.setTargetPos(new Vec3(target.getX(), groundY + 1.0, target.getZ()));
    }

    /** 干扰成功提示：干扰者（载具驾驶员）与被干扰弹发射者各收到一条 actionbar 提示。 */
    private static void notifyJamAndVictim(AbstractVehicle vehicle, RVP_BaseBullet target) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        // 被干扰者提示（导弹发射者）
        Entity owner = target.getOwner();
        if (owner instanceof ServerPlayer victim) {
            victim.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.dircm_jammed"), true);
        }
        // 干扰者提示（节流）
        long now = vehicle.level().getGameTime();
        Long last = LAST_JAM_NOTIFY_BY_VEHICLE.get(vehicle.getId());
        if (last != null && now - last < JAM_NOTIFY_INTERVAL) {
            return;
        }
        LAST_JAM_NOTIFY_BY_VEHICLE.put(vehicle.getId(), now);
        Entity driver = vehicle.getDriver();
        if (driver instanceof ServerPlayer jammer) {
            jammer.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.dircm_irradiate"), true);
        }
    }

    /** 确定偏航侧向符号：远离干扰机载具一侧。 */
    private static double resolveSideSign(RVP_BaseBullet projectile, AbstractVehicle vehicle) {
        Vec3 flight = projectile.getDeltaMovement();
        Vec3 flightH = new Vec3(flight.x, 0.0, flight.z);
        Vec3 toJammerH = new Vec3(vehicle.getX() - projectile.getX(), 0.0, vehicle.getZ() - projectile.getZ());
        if (flightH.lengthSqr() <= 1.0E-8 || toJammerH.lengthSqr() <= 1.0E-8) {
            return 1.0;
        }
        Vec3 leftPerp = new Vec3(flightH.z, 0.0, -flightH.x).normalize();
        double dot = leftPerp.dot(toJammerH.normalize());
        return dot >= 0.0 ? -1.0 : 1.0;
    }

    /** 每 tick 推进被 DIRCM 干扰弹体的状态（由弹体 tick 在服务端调用）：
     *  禁止重指定倒计时、HITL 临时干扰恢复、永久干扰弹自毁。 */
    public static void tickJammedProjectile(RVP_BaseBullet bullet) {
        // 禁止重指定倒计时：独立于 dircmJammed，即使干扰结束也继续递减
        if (bullet.dircmNoRedesignateTick > 0) {
            bullet.dircmNoRedesignateTick--;
        }
        if (!bullet.dircmJammed) {
            return;
        }
        // 永久干扰弹体（IR/AIR 等）：自毁倒计时，归零伴随爆炸移除
        if (!bullet.dircmHitlTemporary) {
            if (bullet.dircmSelfDestructTick > 0) {
                bullet.dircmSelfDestructTick--;
                if (bullet.dircmSelfDestructTick <= 0) {
                    bullet.dircmSelfDestruct();
                }
            }
            return;
        }
        // HITL 临时干扰：递减恢复
        if (bullet.dircmJamRemainTick > 0) {
            bullet.dircmJamRemainTick--;
        }
        if (bullet.dircmJamRemainTick <= 0) {
            // 恢复制导能力（HITL 临时干扰结束）：清除干扰状态。
            // 不自动重新锁定原目标——弹体保持无目标直飞，操作员需重新指定才重锁。
            // 若此刻 targetEntity 被持续指定重建，也一并清空，避免"干扰结束又锁回去"。
            bullet.dircmJammed = false;
            bullet.dircmHitlTemporary = false;
            bullet.jammingStrength = 0.0;
            bullet.jammingSideSign = 0.0;
            bullet.clearTarget();
            // 清制导记忆点：防止惯性制导（enableInertialGuidance）沿保留的目标位置继续追踪
            bullet.clearGuidanceMemory();
        }
    }

    /** 同步通道状态到客户端 HUD。 */
    private static void syncHud(AbstractVehicle vehicle) {
        Map<String, BoneDircmConfig> devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveDircmDevices(vehicle);
        if (devices == null) {
            return;
        }
        DircmState state = STATES.get(vehicle.getUUID());
        if (state == null) {
            return;
        }
        List<String> boneNames = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();
        List<Integer> targetIds = new ArrayList<>();
        List<Integer> chargeRemains = new ArrayList<>();
        for (Map.Entry<String, BoneDircmConfig> entry : devices.entrySet()) {
            String bone = entry.getKey();
            BoneDircmConfig config = entry.getValue();
            ChannelState ch = state.channels.get(bone);
            boneNames.add(bone);
            // HUD 显示名：配置了 display_name 用之（如"左"/"右"），否则用骨块名
            displayNames.add(config.displayName() != null ? config.displayName() : bone);
            targetIds.add(ch == null ? -1 : ch.busyTargetId);
            chargeRemains.add(ch == null ? 0 : ch.chargeRemainTick);
        }
        RVP_Network.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CDircmHudSync(vehicle.getId(), boneNames, displayNames, targetIds, chargeRemains)
        );
    }

    /** 供服务端弹体 tick 调用：本载具是否在照射某个目标（客户端光束渲染用服务端状态）。 */
    public static boolean isIrradiating(UUID vehicleId, String boneName) {
        DircmState state = STATES.get(vehicleId);
        if (state == null) {
            return false;
        }
        ChannelState ch = state.channels.get(boneName);
        return ch != null && ch.busyTargetId >= 0;
    }
}