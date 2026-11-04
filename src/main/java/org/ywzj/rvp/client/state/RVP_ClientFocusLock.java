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
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

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
    /** 与本体焦点拖动相同的最低选点射线长度，单位为方块。 */
    private static final double RAY_DISTANCE = 1024.0D;
    /** 独立于炮塔实际姿态的世界锁点与鼠标请求。 */
    private static final RVP_FocusLockAim AIM = new RVP_FocusLockAim();
    /** 当前会话所属的武器站对象，换座/换车/换世界后不复用。 */
    @Nullable
    private static WeaponUnit station;
    /** 最近一次读入或写回本体的焦点，用于识别外部改点和重新锁定。 */
    @Nullable
    private static Vec3 acceptedTarget;
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

    /** 工具类不允许实例化。 */
    private RVP_ClientFocusLock() {}

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
        Vec3 requestedTarget = AIM.turn(origin, yawInput * INPUT_DEGREES * scale,
                pitchInput * INPUT_DEGREES * scale);
        if (requestedTarget == null || (yawInput == 0.0D && pitchInput == 0.0D)) {
            return true;
        }
        Vec3 direction = requestedTarget.subtract(origin).normalize();
        Vec3 end = origin.add(direction.scale(Math.max(RAY_DISTANCE, requestedTarget.distanceTo(origin))));
        // 调用本体碰撞射线保留原有方块/实体遮挡规则；未命中时保留请求点原有距离。
        Vec3 hit = VectorUtil.hitPosition(currentStation.getVehicle(), origin, end);
        if (RVP_FocusLockAim.isFinite(hit) && hit.distanceToSqr(end) > 1.0E-8D) {
            // 调用锁点接收，只有真实命中才改变距离，细小鼠标输入不作死区过滤。
            AIM.acceptTarget(origin, hit);
        }
        acceptedTarget = AIM.target();
        // 调用本体公开写入接口，下一游戏刻仍按原有方向机限速追踪并同步服务端。
        currentStation.setFocusLockPos(acceptedTarget);
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
        Vec3 direction = AIM.direction(origin);
        if (direction == null) {
            return;
        }
        renderedOrigin = origin;
        renderedYaw = AIM.yaw();
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
        if (event.phase == TickEvent.Phase.END) {
            // 调用上下文解析，以对象身份隔离上一辆车或上一世界的渲染原点。
            WeaponUnit currentStation = scopeStation(Minecraft.getInstance());
            if (currentStation == null || currentStation != station) {
                clear();
            }
        }
    }

    /** 清空客户端会话；不清除本体存储的焦点，也不改变其他锁定通道。 */
    public static void clear() {
        // 调用瞄准状态清理，避免同一对象在下一次观瞄沿用旧方向。
        AIM.clear();
        station = null;
        acceptedTarget = renderedOrigin = null;
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
        if (station != currentStation || !java.util.Objects.equals(acceptedTarget, target)) {
            if (station != currentStation) {
                clear();
            }
            station = currentStation;
            // 调用锁点状态清理和初始化，外部改点不会被上一次拖动的残余状态覆盖。
            AIM.clear();
            AIM.acceptTarget(origin, target);
            acceptedTarget = target;
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
            acceptedTarget = renderedOrigin = null;
            AIM.clear();
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
