package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DistantHorizonsCompatBootstrap;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * 焦点锁定的客户端输入与逐帧观瞄服务。
 *
 * <p>只消费已有焦点，不改变 R 键的雷达/光电/GPS 分流。鼠标只更新请求锁点，
 * 炮塔实际旋转和网络同步仍由本体 tickFireControl 完成。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientFocusLock {
    /** 保留本体焦点分支在一倍视野下的输入尺度，单位为度/输入量。 */
    private static final double INPUT_DEGREES = 0.33D;
    /** 各站仍有效的真实点/未知距离方向状态；弱键不阻止已卸载载具回收。 */
    private static final Map<WeaponUnit, RVP_FocusLockAim> LOCKS = new WeakHashMap<>();
    /** 当前显示会话使用的瞄准状态；打开菜单等只释放显示，不丢失锁点的命中语义。 */
    @Nullable
    private static RVP_FocusLockAim aim;
    /** 当前会话所属的武器站对象，换座/换车/换世界后不复用。 */
    @Nullable
    private static WeaponUnit station;
    /** 最近一帧实际渲染的观瞄位置，鼠标选点与玩家看到的画面使用同一观察原点。 */
    @Nullable
    private static Vec3 renderedOrigin;
    /** 上一帧独立观瞄的方位角，单位为度，解锁时用于平滑回交。 */
    private static float renderedYaw;
    /** 上一帧独立观瞄的俯仰角，单位为度。 */
    private static float renderedPitch;
    /** 是否已经显示过当前焦点镜头；仅输入过但尚未渲染时不进行解锁过渡。 */
    private static boolean renderedFocus;
    /** 解锁回交时相对于本体镜头的剩余方位偏移，单位为度。 */
    private static double handoffYaw;
    /** 解锁回交时相对于本体镜头的剩余俯仰偏移，单位为度。 */
    private static double handoffPitch;
    /** 上次相机计算的单调时间，单位为纳秒；暂停/换上下文时清零。 */
    private static long lastCameraNanos;
    /** 唯一未完成的远景查询，连续拖动不会向后台堆积过期任务。 */
    @Nullable
    private static CompletableFuture<Vec3> terrainQuery;
    /** 后台查询所属站，用对象身份与世界核对结果，完成后释放引用。 */
    @Nullable
    private static WeaponUnit terrainStation;
    /** 后台查询所属状态，重新按 R 后即使坐标相同也不能接收旧结果。 */
    @Nullable
    private static RVP_FocusLockAim terrainAim;
    /** 后台查询捕获的输入代次，后续鼠标移动会让该结果过期。 */
    private static long terrainRevision;
    /** 最近发出过查询的状态，防止未命中时逐刻扫描相同射线。 */
    @Nullable
    private static RVP_FocusLockAim lastQueriedAim;
    /** 最近发出过查询的输入代次；观察点移动不会触发反复重查。 */
    private static long lastQueriedRevision;

    /** 工具类不允许实例化。 */
    private RVP_ClientFocusLock() {}

    /** 由已有 R 键入口在焦点切换完成后调用，首次选点即保留真实命中/未知距离之别。 */
    public static void onFocusLockChanged(WeaponUnit changedStation) {
        if (changedStation.getFocusLockPos() == null) {
            LOCKS.remove(changedStation);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // 调用现有资格解析，实体锁定等其他 R 键用途不进入此处。
        if (scopeStation(minecraft) != changedStation) {
            return;
        }
        Vec3 origin = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 direction = new Vec3(minecraft.gameRenderer.getMainCamera().getLookVector());
        // 调用有限性检查，客户端首帧未就绪时不替换本体状态。
        if (!RVP_FocusLockAim.isFinite(origin) || !RVP_FocusLockAim.isFinite(direction)) {
            return;
        }
        RVP_FocusLockAim newAim = new RVP_FocusLockAim();
        // 调用统一射线，首次按 R 与后续拖动使用相同距离与真实命中判定。
        newAim.acceptRay(origin, direction, RVP_FocusLockRaycast.loadedSurface(changedStation, origin, direction));
        LOCKS.put(changedStation, newAim);
        // 调用本体写入接口保留焦点开关；未知距离的位置仅供原有火控对准方向。
        changedStation.setFocusLockPos(newAim.target());
        // 调用会话同步，后台远景选点由后续客户端 Tick 发起，不阻塞 R 键响应。
        synchronize(changedStation, origin);
    }

    /** 让 GPS 等选点共享当前焦点方向，未知距离时不得从旧的位置代理反算近距离视差。 */
    @Nullable
    public static Vec3 lockDirection(WeaponUnit requestedStation, Vec3 origin) {
        RVP_FocusLockAim stored = LOCKS.get(requestedStation);
        if (stored != null && Objects.equals(stored.target(), requestedStation.getFocusLockPos())) {
            // 调用类型化方向解析，真实点补偿视差，未知距离则保持世界方向。
            return stored.direction(origin);
        }
        Vec3 target = requestedStation.getFocusLockPos();
        // 调用坐标检查，直接由其他系统提供的显式世界点继续按原有几何解析。
        return RVP_FocusLockAim.isFinite(target) && RVP_FocusLockAim.isFinite(origin)
                ? target.subtract(origin).normalize() : null;
    }

    /** 复用已有鼠标入口，只有观瞄中已经建立焦点时才消费输入。 */
    public static boolean tryHandleTurn(double yawInput, double pitchInput) {
        Minecraft minecraft = Minecraft.getInstance();
        // 调用上下文解析，电视导弹、菜单、第三人称和其他座位不进入焦点操控。
        WeaponUnit currentStation = scopeStation(minecraft);
        if (currentStation == null || currentStation.getFocusLockPos() == null) {
            return false;
        }
        // 调用本体观瞄位置入口作首帧回退，已渲染后始终使用真实画面原点。
        Vec3 origin = station == currentStation && renderedOrigin != null ? renderedOrigin
                : LocalVehiclePlayer.instance.cameraPosition(currentStation, minecraft.getFrameTime());
        // 调用有限性检查，世界/相机初始化未完成时不进行无效碰撞检测。
        if (!RVP_FocusLockAim.isFinite(origin)) {
            return true;
        }
        // 调用会话同步，避免外部重新锁点后继承上一个目标的方向。
        synchronize(currentStation, origin);
        if (!Double.isFinite(yawInput) || !Double.isFinite(pitchInput)) {
            return true;
        }
        // 调用视野比例解析，跟随本体已经平滑过的最终显示倍率，不再叠加倍率滤波。
        double scale = RVP_FocusLockAim.sensitivityScale(VectorUtil.fov,
                minecraft.options.fov().get(), currentStation.getZoom());
        // 调用独立瞄准状态累加输入；同一游戏刻内多次调用不会被炮口旧角度覆盖。
        Vec3 requestedTarget = aim.turn(origin, yawInput * INPUT_DEGREES * scale,
                pitchInput * INPUT_DEGREES * scale);
        if (requestedTarget == null || (yawInput == 0.0D && pitchInput == 0.0D)) {
            return true;
        }
        Vec3 direction = aim.direction(origin);
        // 调用统一射线与类型化状态，拖出地形后改锁方向，不继承旧墙面/视距末端的假距离。
        aim.acceptRay(origin, direction, RVP_FocusLockRaycast.loadedSurface(currentStation, origin, direction));
        // 调用本体公开写入接口，下一游戏刻仍按原有方向机限速追踪并同步服务端。
        currentStation.setFocusLockPos(aim.target());
        LocalVehiclePlayer.instance.mouseTurnedAfterScope = true;
        LocalVehiclePlayer.instance.playerLerpSteps = 0.0F;
        return true;
    }

    /** 在本体相机设置后更新观瞄方向，早于正常优先级的爆炸震屏等效果。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        // 调用上下文解析，只在本地操作者自己的第一人称观瞄中接管镜头。
        WeaponUnit currentStation = scopeStation(minecraft);
        if (currentStation == null || event.getCamera().getEntity() != minecraft.player) {
            clear();
            return;
        }
        if (currentStation.getFocusLockPos() == null) {
            // 调用解锁回交，炮塔尚未追上时避免镜头瞬间跳回实际炮口。
            applyHandoff(event, currentStation);
            return;
        }
        Vec3 origin = event.getCamera().getPosition();
        // 调用有限性检查，防止无效相机坐标进入锁点状态。
        if (!RVP_FocusLockAim.isFinite(origin)) {
            clear();
            return;
        }
        // 调用会话同步与世界方向解析，每帧按插值后的实际观瞄位置指向同一世界锁点。
        synchronize(currentStation, origin);
        Vec3 direction = aim.direction(origin);
        if (direction == null) {
            return;
        }
        renderedOrigin = origin;
        renderedYaw = aim.yaw();
        renderedPitch = (float) -Math.toDegrees(Math.atan2(direction.y,
                Math.hypot(direction.x, direction.z)));
        renderedFocus = true;
        handoffYaw = handoffPitch = 0.0D;
        lastCameraNanos = System.nanoTime();
        event.setYaw(renderedYaw);
        event.setPitch(renderedPitch);
        // 不改炮口、弹着点或本体相机逻辑状态；后续事件仍可叠加原有横滚和震屏。
    }

    /** 世界卸载、退出观瞄或换座后及时释放会话，不等待下一次鼠标移动。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            // 清理已解锁或旧世界的状态；暂停/切观瞄时仍保留有效焦点的真实命中语义。
            LOCKS.entrySet().removeIf(entry -> entry.getKey().getVehicle().level() != minecraft.level
                    || entry.getKey().getFocusLockPos() == null);
            // 调用后台结果收取，仅在客户端线程校验并写回，绝不等待未完成查询。
            finishTerrainQuery(minecraft);
            // 调用方向代理刷新与远景查询调度，渲染帧只读方向，不改炮塔真实姿态。
            updateUnknownDistance(minecraft);
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            // 调用上下文解析，以对象身份隔离上一辆车或上一世界的渲染原点。
            WeaponUnit currentStation = scopeStation(minecraft);
            if (currentStation == null || currentStation != station) {
                clear();
            }
        }
    }

    /** 清空客户端会话；不清除本体存储的焦点，也不改变其他锁定通道。 */
    public static void clear() {
        // 只释放显示会话；真实点/未知距离类型随仍有效的本体焦点保留，防止暂停后再次误判。
        aim = null;
        station = null;
        renderedOrigin = null;
        renderedFocus = false;
        renderedYaw = renderedPitch = 0.0F;
        handoffYaw = handoffPitch = 0.0D;
        lastCameraNanos = 0L;
    }

    /** 读取有效观瞄武器站；焦点为空时也允许同一站执行解锁回交。 */
    @Nullable
    private static WeaponUnit scopeStation(Minecraft minecraft) {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        // 调用 HITL 状态门，确保电视导弹会话始终拥有更高输入和相机优先级。
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null
                || minecraft.isPaused() || RVP_ClientHitlState.isActive()
                || !minecraft.options.getCameraType().isFirstPerson()
                || local == null || local.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
            return null;
        }
        // 调用本体座位解析，防止沿用已经离开的武器站或其他玩家的武器站。
        WeaponUnit currentStation = local.getWeaponUnit();
        if (currentStation == null || !currentStation.withFocusLocker()
                || currentStation.getVehicle() != local.vehicle
                || currentStation.getVehicle().level() != minecraft.level
                || currentStation.getOwner() != minecraft.player) {
            return null;
        }
        // 调用有限性检查，非法外部焦点交还原逻辑，不污染新会话。
        if (currentStation.getFocusLockPos() != null
                && !RVP_FocusLockAim.isFinite(currentStation.getFocusLockPos())) {
            return null;
        }
        return currentStation;
    }

    /** 接收本体初始/外部焦点，持续拖动时则保留独立累加状态。 */
    private static void synchronize(WeaponUnit currentStation, Vec3 origin) {
        Vec3 target = currentStation.getFocusLockPos();
        if (station != currentStation) {
            clear();
            station = currentStation;
        }
        RVP_FocusLockAim stored = LOCKS.get(currentStation);
        if (stored == null || !Objects.equals(stored.target(), target)) {
            stored = new RVP_FocusLockAim();
            // 调用显式世界点接收，其他系统直接写入的已知坐标不能被重新射线判成未知距离。
            stored.acceptTarget(origin, target);
            LOCKS.put(currentStation, stored);
        }
        aim = stored;
    }

    /** 每刻移动未知距离的位置代理，镜头始终使用保存的世界方向，不追逐近处空气点。 */
    private static void updateUnknownDistance(Minecraft minecraft) {
        // 调用资格解析，电视导弹与非观瞄画面不进入本地焦点更新。
        WeaponUnit currentStation = scopeStation(minecraft);
        if (currentStation == null || currentStation.getFocusLockPos() == null) {
            return;
        }
        // 调用本体当前观瞄位置，代理刷新不使用过时渲染原点。
        Vec3 origin = LocalVehiclePlayer.instance.cameraPosition(currentStation, 1.0F);
        synchronize(currentStation, origin);
        if (!aim.isDirectionLocked()) {
            return;
        }
        // 调用代理平移，只更新原有火控需要的位置，不改变原始选点射线和输入代次。
        currentStation.setFocusLockPos(aim.rebase(origin));
        // 调用可选能力检查，无 DH 或已有在途查询时不创建额外任务。
        if (terrainQuery != null || !RVP_DistantHorizonsCompatBootstrap.canRaycastFocusTerrain()
                || (lastQueriedAim == aim && lastQueriedRevision == aim.revision())) {
            return;
        }
        Vec3 queryOrigin = aim.rayOrigin();
        Vec3 queryDirection = aim.direction(queryOrigin);
        if (queryOrigin == null || queryDirection == null) {
            return;
        }
        terrainStation = currentStation;
        terrainAim = lastQueriedAim = aim;
        terrainRevision = lastQueriedRevision = aim.revision();
        // 调用异步远景射线，扫描和缓存读取不运行在画面线程，也不产生原版区块加载请求。
        terrainQuery = RVP_DistantHorizonsCompatBootstrap.raycastFocusTerrain(minecraft.level,
                queryOrigin, queryDirection, RVP_FocusLockRaycast.TERRAIN_DISTANCE);
    }

    /** 收取完成结果，拒绝换世界、换锁点或继续移动鼠标之前发出的旧请求。 */
    private static void finishTerrainQuery(Minecraft minecraft) {
        if (terrainQuery == null || !terrainQuery.isDone()) {
            return;
        }
        Vec3 result = null;
        try {
            result = terrainQuery.getNow(null);
        } catch (RuntimeException unavailable) {
            // 可选数据查询失败按未命中处理，不中断客户端 Tick。
        }
        WeaponUnit requestedStation = terrainStation;
        RVP_FocusLockAim requestedAim = terrainAim;
        terrainQuery = null;
        terrainStation = null;
        terrainAim = null;
        if (requestedStation == null || requestedAim == null
                || requestedStation.getVehicle().level() != minecraft.level
                || LOCKS.get(requestedStation) != requestedAim
                || !Objects.equals(requestedStation.getFocusLockPos(), requestedAim.target())) {
            return;
        }
        // 调用代次校验与真实命中接收，只有仍对应当前输入的前方地形才升级为固定世界点。
        if (requestedAim.acceptTerrainTarget(terrainRevision, result)) {
            requestedStation.setFocusLockPos(requestedAim.target());
        }
    }

    /** 在同一观瞄站解锁时，以 50 毫秒时间常数回交本体镜头，不影响机械转速。 */
    private static void applyHandoff(ViewportEvent.ComputeCameraAngles event, WeaponUnit currentStation) {
        if (station != currentStation) {
            clear();
            return;
        }
        long now = System.nanoTime();
        if (renderedFocus) {
            // 调用角度环绕，跨正负 180 度解锁时选择最短回交方向。
            handoffYaw = RVP_FocusLockAim.wrapDegrees(renderedYaw - event.getYaw());
            handoffPitch = renderedPitch - event.getPitch();
            renderedFocus = false;
            renderedOrigin = null;
            aim = null;
        }
        double elapsedSeconds = lastCameraNanos == 0L ? 0.0D : (now - lastCameraNanos) / 1.0E9D;
        lastCameraNanos = now;
        // 调用按秒衰减，30/60/144/240 帧下具有相同的回交速度。
        double retention = RVP_FocusLockAim.handoffRetention(elapsedSeconds);
        handoffYaw *= retention;
        handoffPitch *= retention;
        if (Math.abs(handoffYaw) + Math.abs(handoffPitch) < 1.0E-4D) {
            clear();
            return;
        }
        event.setYaw((float) (event.getYaw() + handoffYaw));
        event.setPitch((float) (event.getPitch() + handoffPitch));
    }
}
