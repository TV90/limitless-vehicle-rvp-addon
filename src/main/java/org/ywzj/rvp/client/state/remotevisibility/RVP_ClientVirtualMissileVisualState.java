package org.ywzj.rvp.client.state.remotevisibility;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.client.light.RVP_RocketPlumeManager;
import org.ywzj.rvp.client.light.RVP_ScreenLightManager;
import org.ywzj.rvp.client.render.RVP_VirtualMissileModelRuntime;
import org.ywzj.rvp.client.state.remotevisibility.RVP_VirtualMissileVisualTimeline.Pose;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.Entry;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.DepartureReason;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 独立的只供显示缓存；对象不加入 ClientLevel、不写 serverEntities、不运行任何弹体更新。
 * 雷达目标与模型代理即使同 UUID，也始终是不同实例。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientVirtualMissileVisualState {
    /** 当前世界的独立视觉对象，数量由完整集合和十刻交接上限约束。 */
    private static final Map<UUID, Visual> VISUALS = new HashMap<>();
    /** 会话门属于网络连接，换维度不解除会话绑定。 */
    private static final RVP_VirtualMissileVisualTimeline.ReceiptGate RECEIPTS =
            new RVP_VirtualMissileVisualTimeline.ReceiptGate();
    /** 当前客户端世界对象；同名维度重新进入也不能复用。 */
    private static ClientLevel level;
    /** 当前连接对象身份，重连后才允许绑定新服务器会话。 */
    private static Object connection;
    /** 独立客户端动画刻；单人暂停时不推进，不用服务端世界时钟推算延迟。 */
    private static long animationTick;
    /** 累计超时删除次数，仅诊断时汇总。 */
    private static long timedOut;
    /** 本地真实弹优先导致的跳过次数，仅诊断时汇总。 */
    private static long duplicateSkips;

    private RVP_ClientVirtualMissileVisualState() { }

    /** 主线程完整集合提交，先判断世界与会话，较新同刻包可以更新和撤销。 */
    public static void accept(S2CVirtualMissileVisualSnapshot message) {
        Minecraft minecraft = Minecraft.getInstance();
        ensureWorld(minecraft);
        if (level == null || !RECEIPTS.accept(message, level.dimension().location())) return;
        Set<UUID> incoming = new HashSet<>();
        for (Entry entry : message.entries()) {
            incoming.add(entry.flightUuid());
            Visual visual = VISUALS.get(entry.flightUuid());
            if (visual != null && (visual.entity.getId() != entry.entityId()
                    || !entry.weaponId().equals(visual.entity.getWeaponId()) || visual.timeline.restored())) {
                remove(entry.flightUuid());
                visual = null;
            }
            if (visual == null) {
                // 调用类型化构造器只建立武器身份；禁止 initFromWeapon、readSpawnData、实体 tick 和入世。
                RVP_MissileEntity proxy = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, entry.weaponId());
                proxy.setUUID(entry.flightUuid());
                proxy.setId(entry.entityId());
                proxy.noPhysics = true;
                proxy.setNoGravity(true);
                proxy.setSilent(true);
                visual = new Visual(proxy, new RVP_VirtualMissileVisualTimeline(entry, message.serverGameTime(), animationTick));
                VISUALS.put(entry.flightUuid(), visual);
            } else {
                if (visual.timeline.stale(animationTick)) {
                    // 调用尾烟断链入口，断流后不连接到过期位置；已有烟保留消散。
                    stopEffects(visual, true);
                }
                visual.timeline.accept(entry, message.serverGameTime(), animationTick);
                if (visual.timeline.breaksTrail()) stopTrail(visual);
            }
            applyPose(visual, visual.timeline.sample(animationTick));
            // 活动虚拟快照记录当前旧远程版本；恢复后只有后续真实 readData 才允许接管。
            rememberRemoteVersion(visual);
            if (visual.timeline.motorPhase(animationTick) == S2CVirtualMissileVisualSnapshot.MotorPhase.IDLE)
                stopEffects(visual, true);
        }
        for (var departure : message.departures()) {
            Visual visual = VISUALS.get(departure.flightUuid());
            if (visual == null || visual.entity.getId() != departure.entityId()) continue;
            if (departure.reason() == DepartureReason.RESTORED) {
                // 真实来源尚未抵达时只保留最终模型，捕获远程更新时间，旧雷达代理不能冒充接管。
                visual.timeline.restore(departure.finalSample(), message.serverGameTime(), animationTick);
                applyPose(visual, visual.timeline.sample(animationTick));
                stopEffects(visual, true);
            } else remove(departure.flightUuid());
        }
        for (Visual visual : List.copyOf(VISUALS.values())) {
            if (!incoming.contains(visual.entity.getUUID()) && !visual.timeline.restored()) remove(visual.entity.getUUID());
        }
        // 连续恢复/再虚拟化也不能让交接等待无限累积，优先保留本次完整集合。
        if (VISUALS.size() > S2CVirtualMissileVisualSnapshot.MAX_ENTRIES * 2) {
            for (Visual visual : List.copyOf(VISUALS.values())) {
                if (VISUALS.size() <= S2CVirtualMissileVisualSnapshot.MAX_ENTRIES * 2) break;
                if (visual.timeline.restored()) remove(visual.entity.getUUID());
            }
        }
    }

    /** 每客户端刻推进一次缓存时钟；从不调用显示对象的实体 Tick。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        ensureWorld(minecraft);
        if (level == null || minecraft.isPaused()) return;
        animationTick++;
        for (Visual visual : List.copyOf(VISUALS.values())) {
            if (visual.timeline.expired(animationTick)
                    || visual.timeline.restored() && (localReal(visual) != null || realRemoteReady(visual))) {
                if (visual.timeline.expired(animationTick)) timedOut++;
                remove(visual.entity.getUUID());
            } else if (!visual.timeline.effectsFresh(animationTick)) stopEffects(visual, true);
            else if (visual.timeline.stale(animationTick)) stopTrail(visual);
        }
        // 调用现有客户端状态诊断开关，默认关闭；只每十秒汇总，不逐弹逐帧刷屏。
        if (RVP_DebugFlags.CLIENT_STATE.isEnabled() && animationTick % 200 == 0)
            LogUtils.getLogger().info("[RVP][VirtualVisual] proxies={} duplicateSkips={} timeouts={}",
                    VISUALS.size(), duplicateSkips, timedOut);
    }

    /** 返回当前唯一应由手动入口绘制的来源，已有真实实体优先，虚拟等待可被更新后的真实克隆接管。 */
    public static List<Visual> renderSources(float partialTick) {
        ensureWorld(Minecraft.getInstance());
        List<Visual> result = new ArrayList<>();
        for (Visual visual : List.copyOf(VISUALS.values())) {
            if (visual.timeline.expired(animationTick)) continue;
            if (localReal(visual) != null) { duplicateSkips++; continue; }
            if (visual.timeline.restored() && realRemoteReady(visual)) {
                remove(visual.entity.getUUID());
                continue;
            }
            // 所有绘制和效果在这一份姿态上读取，目标代理保持自己的接收态坐标。
            applyPose(visual, visual.timeline.sample(frameTime(partialTick)));
            result.add(visual);
        }
        return result;
    }

    /** 真实远程循环按 UUID 和会话 ID 去重，不能因整数编号相同而吞掉另一枚弹。 */
    public static boolean takesPrecedence(Entity entity) {
        Visual visual = VISUALS.get(entity.getUUID());
        return visual != null && visual.entity.getId() == entity.getId()
                && !visual.timeline.expired(animationTick)
                && !(visual.timeline.restored() && realRemoteReady(visual));
    }

    /** 供尾烟缓存配对身份，只返回视觉授权，不向目标表暴露可锁对象。 */
    public static boolean hasIdentity(int entityId, UUID uuid) {
        Visual visual = VISUALS.get(uuid);
        return visual != null && visual.entity.getId() == entityId && !visual.timeline.restored()
                && !visual.timeline.expired(animationTick);
    }

    /** 雷达代理自己的更新不算真实来源抵达；只登记版本，不接管或修改雷达对象。 */
    public static void noteRadarUpdate(RVP_MissileEntity target) {
        Visual visual = VISUALS.get(target.getUUID());
        if (visual != null && visual.entity.getId() == target.getId() && !visual.timeline.restored()) {
            visual.handoffRemoteEntity = target;
            visual.handoffRemoteRevision = target.getRemoteVisualUpdateRevision();
        }
    }

    /** 归属必须以对象引用确认，不能凭同 UUID 把雷达代理或真实实体认成显示对象。 */
    @Nullable
    public static Visual owned(Entity entity) {
        Visual visual = VISUALS.get(entity.getUUID());
        return visual != null && visual.entity == entity ? visual : null;
    }

    /** 已有目标查询统一模型中心；本方法不创建、枚举或授权任何新目标。 */
    @Nullable
    public static Vec3 targetRenderCenter(Entity target, float partialTick) {
        if (target.level() != level) return null;
        Visual visual = VISUALS.get(target.getUUID());
        if (visual == null || visual.entity.getId() != target.getId() || visual.timeline.expired(animationTick)) return null;
        Entity local = localReal(visual);
        Vec3 origin = local == null ? visual.timeline.sample(frameTime(partialTick)).position()
                : local.getPosition(partialTick);
        Entity dimensions = local == null ? visual.entity : local;
        return origin.add(dimensions.getBoundingBox().getCenter().subtract(dimensions.position()));
    }

    /** 光效查询与尾烟发射共用不随暂停推进的客户端动画刻。 */
    public static long animationTick() { return animationTick; }

    /** 仅既有电视会话按编号读取已授权显示对象；不加入世界或雷达目标表，不建立新操控权限。 */
    @Nullable
    public static RVP_MissileEntity controlVisual(int entityId, float partialTick) {
        ensureWorld(Minecraft.getInstance());
        for (Visual visual : VISUALS.values()) {
            if (visual.entity.getId() != entityId || visual.timeline.expired(animationTick)) continue;
            applyPose(visual, visual.timeline.sample(frameTime(partialTick)));
            // 只填充原来从生成包读取的镜头/HUD 参数，不运行弹体 Tick。
            visual.entity.initializeVirtualDisplayControls();
            return visual.entity;
        }
        return null;
    }

    /** 授权按水平距离，远平面按实际三维距离；高空导弹不能在 8192 格球面被误裁掉。 */
    public static double farPlaneDistance(Vec3 camera, float partialTick) {
        ensureWorld(Minecraft.getInstance());
        double distance = 0;
        for (Visual visual : VISUALS.values()) {
            if (!visual.timeline.expired(animationTick)) distance = Math.max(distance,
                    visual.timeline.sample(frameTime(partialTick)).position().distanceTo(camera));
        }
        return distance;
    }

    /** 世界卸载只清代理，连接的会话与最高序号仍保留。 */
    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() == level) clearWorld();
    }

    /** 连接结束后才允许绑定下一次服务器运行身份。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearWorld(); RECEIPTS.disconnect(); connection = null;
    }

    /** 同维度换世界也清空；连接变化与世界变化分开处理。 */
    private static void ensureWorld(Minecraft minecraft) {
        Object currentConnection = minecraft.getConnection() == null ? null : minecraft.getConnection().getConnection();
        // 换世界期间临时没有玩家/监听器不等于断线，会话只能在真实新连接或 LoggingOut 时解除。
        if (currentConnection != null && connection != currentConnection) {
            clearWorld(); RECEIPTS.disconnect(); connection = currentConnection;
        }
        if (level != minecraft.level) { clearWorld(); level = minecraft.level; }
    }

    /** 模型与喷口共享的姿态，仅修改本缓存持有的专用实例。 */
    private static void applyPose(Visual visual, Pose pose) {
        RVP_MissileEntity entity = visual.entity;
        entity.setPos(pose.position());
        entity.xo = entity.xOld = pose.position().x;
        entity.yo = entity.yOld = pose.position().y;
        entity.zo = entity.zOld = pose.position().z;
        entity.setYRot(pose.yaw()); entity.yRotO = pose.yaw();
        entity.setXRot(pose.pitch()); entity.xRotO = pose.pitch();
        entity.setDeltaMovement(visual.timeline.latest().velocity());
        entity.tickCount = pose.flightTick();
        // 只还原制导阶段的显示事实，操作手 HUD 不因代理缺少实体 Tick 而误退回主段。
        entity.getGuidancePhaseState().restore(visual.timeline.latest().guidancePhase());
    }

    /** 正常世界实体必须同时匹配 ID、UUID 且存活。 */
    private static Entity localReal(Visual visual) {
        Entity local = level == null ? null : level.getEntity(visual.entity.getId());
        return local != null && local.isAlive() && local.getUUID().equals(visual.entity.getUUID()) ? local : null;
    }

    /** 查询既有远程记录，防止同编号不同 UUID 被误配。 */
    private static LocalVehiclePlayer.ServerEntity remote(Visual visual) {
        if (LocalVehiclePlayer.instance == null) return null;
        var remote = LocalVehiclePlayer.instance.serverEntities.get(visual.entity.getId());
        return remote != null && remote.entity != null && remote.entity.isAlive()
                && remote.entity.getUUID().equals(visual.entity.getUUID()) ? remote : null;
    }

    /** 恢复交接必须看到真实克隆后续更新，不能立即把旧雷达对象当成真实来源。 */
    private static boolean realRemoteReady(Visual visual) {
        var remote = remote(visual);
        return remote != null && remote.entity instanceof RVP_MissileEntity missile && !missile.isVirtualMidcourseTarget()
                && RVP_ClientRemoteAmmoVisualState.contains(level.dimension().location(), missile.getId())
                && (missile != visual.handoffRemoteEntity
                || missile.getRemoteVisualUpdateRevision() > visual.handoffRemoteRevision);
    }

    /** 记录旧远程对象及其 readData 版本；使用客户端刻会漏掉同刻到达的真实恢复更新。 */
    private static void rememberRemoteVersion(Visual visual) {
        var remote = remote(visual);
        visual.handoffRemoteEntity = remote == null ? null : remote.entity;
        visual.handoffRemoteRevision = remote != null && remote.entity instanceof RVP_MissileEntity missile
                ? missile.getRemoteVisualUpdateRevision() : 0;
    }

    /** 先从集合移除，再释放该实例自己的显示运行时；避免旧对象清理新来源灯光。 */
    private static void remove(UUID uuid) {
        Visual visual = VISUALS.remove(uuid);
        if (visual == null) return;
        stopEffects(visual, true);
        // 调用专用模型缓存释放，不触发实体离开世界或客户端爆炸。
        RVP_VirtualMissileModelRuntime.release(visual.entity);
    }

    /** 新来源已接管则不删同 UUID 持续光；否则显式清除，不依赖实体离开事件。 */
    private static void stopEffects(Visual visual, boolean trail) {
        // 巡航视觉优先于真实远程克隆；仅退出或明确恢复后允许另一来源保住同 UUID 的灯光。
        if (localReal(visual) != null || (VISUALS.get(visual.entity.getUUID()) != visual || visual.timeline.restored())
                && realRemoteReady(visual)) return;
        RVP_ScreenLightManager.INSTANCE.removePersistent(visual.entity.getUUID());
        RVP_RocketPlumeManager.INSTANCE.remove(visual.entity.getUUID());
        if (trail) stopTrail(visual);
    }

    /** 已排烟继续消散，只断开新路径；真实来源已接管时保持同一连续烟链。 */
    private static void stopTrail(Visual visual) {
        if (localReal(visual) != null || (VISUALS.get(visual.entity.getUUID()) != visual || visual.timeline.restored())
                && realRemoteReady(visual)) return;
        // 同时断开粒子补点队列和体积烟头，恢复收包不能跨断流空白补出一条长烟线。
        org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteAmmoVisualRenderer.stopTrailSource(
                visual.entity.getId(), visual.entity.getUUID());
    }

    /** 世界对象级释放，不清连接会话；同维度重开不能残留模型或动画。 */
    private static void clearWorld() {
        for (UUID uuid : List.copyOf(VISUALS.keySet())) remove(uuid);
        level = null; animationTick = 0;
    }

    /** 暂停时所有视觉调用读同一个刻，不随渲染帧数推进。 */
    private static double frameTime(float partialTick) {
        return animationTick + (Minecraft.getInstance().isPaused() ? 0 : Math.max(0, Math.min(1, partialTick)));
    }

    /** 显示对象与纯状态时间线的组合，业务代码不拿它作可交互目标。 */
    public static final class Visual {
        /** 不入世界的 RVP 类型化导弹，独占显示资源。 */
        private final RVP_MissileEntity entity;
        /** 只计算画面的权威样本时间线。 */
        private final RVP_VirtualMissileVisualTimeline timeline;
        /** 每游戏刻最多一次尾烟采样，多相机/多帧不能重复排烟。 */
        private long lastTrailTick = Long.MIN_VALUE;
        /** 虚拟态最后见到的远程对象，恢复时同编号不同对象也须核对 UUID。 */
        private Entity handoffRemoteEntity;
        /** 虚拟态最后见到的远程 readData 接收版本，同一个客户端刻也可以递增。 */
        private long handoffRemoteRevision;

        private Visual(RVP_MissileEntity entity, RVP_VirtualMissileVisualTimeline timeline) {
            this.entity = entity; this.timeline = timeline;
        }

        /** 模型/效果专用对象，调用方只能绘制，不能运行实体更新。 */
        public RVP_MissileEntity entity() { return entity; }
        /** 权威燃烧阶段和显示时序共用同一时间线。 */
        public RVP_VirtualMissileVisualTimeline timeline() { return timeline; }
        /** 发射资格与去重在进入旧尾烟设施之前完成。 */
        public boolean claimTrailTick() {
            if (!timeline.canEmitTrail(animationTick) || lastTrailTick == animationTick || Minecraft.getInstance().isPaused())
                return false;
            lastTrailTick = animationTick;
            return true;
        }
    }
}
