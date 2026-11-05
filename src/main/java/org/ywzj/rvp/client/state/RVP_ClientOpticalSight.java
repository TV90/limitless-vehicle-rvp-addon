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
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.firecontrol.RVP_ManualSightInput;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.network.C2SOpticalSightCommand;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2COpticalSightState;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMath;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMotion;
import org.ywzj.rvp.vehicle.RVP_OpticalWeaponUnit;
import org.ywzj.vehicle.client.handler.FirstPersonHandler;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** 仅新版吊舱使用的统一输入、实际相机、射线和网络预测服务。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID)
public final class RVP_ClientOpticalSight {
    /** 一倍视野的度/输入量；对应旧 7 度/刻吊舱手感，之后与机械速度完全独立。 */
    private static final double INPUT_DEGREES = 0.065625;
    /** 当前显示会话所属站，按对象隔离换车换座。 */
    @Nullable private static RVP_OpticalWeaponUnit station;
    /** 连续相机角表示；完整姿态由机械轨迹决定。 */
    @Nullable private static RVP_OpticalSightMath.Angles previous;
    /** 尚未追上的完整请求姿态，不在每帧渲染时覆盖。 */
    @Nullable private static Quaterniond inputRotation;
    /** 每站单独的操作会话和有界预测历史，弱键不阻止实体回收。 */
    private static final Map<RVP_OpticalWeaponUnit, Link> LINKS = new WeakHashMap<>();

    private RVP_ClientOpticalSight() {}

    /** 只释放显示会话，机械运动和网络租约不因切换视图丢失。 */
    public static void clearView() { station = null; previous = null; inputRotation = null; }

    /** 原站保留既有焦点算法；新版连续控制只按类型接入。 */
    public static boolean usesContinuousInput(WeaponUnit unit) {
        return unit != null && unit.withFocusLocker() && (unit instanceof RVP_OpticalWeaponUnit
                || unit.xRotMax - unit.xSelfRot > 90 || unit.xRotMin - unit.xSelfRot < -90);
    }

    /** 获取同一条真实轨迹的本帧完整姿态，不读取上一帧目标或机械极点拆角。 */
    public static Quaterniond currentRotation(WeaponUnit unit) {
        if (unit instanceof RVP_OpticalWeaponUnit optical) return optical.viewRotation(Minecraft.getInstance().getFrameTime());
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        // 调用原站相机姿态回退；非新版站的行为保持原样。
        return RVP_OpticalSightMath.rotation(camera.getXRot(), camera.getYRot(), FirstPersonHandler.zRot);
    }

    /** 普通与焦点共用的倍率输入，鼠标位移不再乘帧间时间或机械追赶误差。 */
    public static double inputScale(WeaponUnit unit) {
        var mc = Minecraft.getInstance();
        // 调用已有最终视野换算，保留倍率渐变中的屏幕尺度。
        return INPUT_DEGREES * RVP_ClientConfig.opticalSightSensitivity()
                * RVP_FocusLockAim.sensitivityScale(VectorUtil.fov, mc.options.fov().get(), unit.getZoom());
    }

    /** 原有全自动抑制和半自动微调必须在新版提前消费输入时也执行。 */
    public static boolean inputSuppressed(WeaponUnit unit) {
        // 调用共用原火控资格，不能仅按模式名屏蔽无目标的玩家。
        return RVP_ManualSightInput.suppressed(unit);
    }

    /** 保留原半自动目标角增量记录；临时还原请求字段仅发生在同一客户端输入调用内。 */
    public static Quaterniond turn(RVP_OpticalWeaponUnit current, Quaterniondc source, double yaw, double pitch) {
        if (station != current) { clearView(); station = current; }
        float oldPitch = current.getXAimRot(), oldYaw = current.getYAimRot();
        // 调用本站请求换算，实际角只在逻辑刻由运动规划更新。
        Quaterniond result = current.turnInView(source, yaw, pitch);
        float nextPitch = current.getXAimRot(), nextYaw = current.getYAimRot();
        current.setXAimRot(oldPitch); current.setYAimRot(oldYaw);
        // 调用写入前的原有微调记录，不能在目标覆盖后把增量错误算成零。
        RVP_ManualSightInput.recordPitch(current, nextPitch, true);
        RVP_ManualSightInput.recordYaw(current, nextYaw, true);
        current.setXAimRot(nextPitch); current.setYAimRot(nextYaw);
        inputRotation = new Quaterniond(result);
        return result;
    }

    /** 普通吊舱按画面轴修改请求，不影响其他站或电视导弹。 */
    public static boolean tryHandleTurn(double yawInput, double pitchInput) {
        var mc = Minecraft.getInstance();
        var local = LocalVehiclePlayer.instance;
        if (!(local.getWeaponUnit() instanceof RVP_OpticalWeaponUnit current) || !ownsScope(current)
                || current.getFocusLockPos() != null || yawInput == 0 && pitchInput == 0) return false;
        if (!Double.isFinite(yawInput) || !Double.isFinite(pitchInput) || inputSuppressed(current)) return true;
        if (!local.mouseTurnedAfterScope) {
            // 调用实际姿态请求初始化，首次细小输入也累加，不再设固定鼠标死区。
            current.setXAimRot(current.getXRot()); current.setYAimRot(current.getYRot()); current.updateWorldAimVec();
            local.mouseTurnedAfterScope = true;
        }
        double scale = inputScale(current);
        Quaterniond source = station == current && inputRotation != null ? inputRotation : currentRotation(current);
        // 调用统一手动处理，锁存半自动偏置后再接续请求。
        turn(current, source, yawInput * scale, pitchInput * scale);
        local.playerLerpSteps = 0;
        return true;
    }

    /** 焦点切换只更新输入起点，不接管或改写实际显示镜头。 */
    public static void resetInput(RVP_OpticalWeaponUnit unit) { inputRotation = currentRotation(unit); }

    /** 持续累加的请求横轴与实际显示分开保存，渲染帧不能覆盖尚未追上的输入。 */
    public static Quaterniond inputRotation(RVP_OpticalWeaponUnit unit) {
        return station == unit && inputRotation != null ? new Quaterniond(inputRotation) : currentRotation(unit);
    }

    /** 保留旧调用签名；新版焦点不再拥有第二台相机。 */
    public static void acceptFocusRotation(WeaponUnit unit, Quaterniondc rotation, RVP_OpticalSightMath.Angles angles) {
        if (unit instanceof RVP_OpticalWeaponUnit) inputRotation = new Quaterniond(rotation);
    }

    /** 去除震屏前的吊舱真实观察射线，所有屏幕选点从同一挂点与同一姿态出发。 */
    @Nullable
    public static SightRay sampleRay(WeaponUnit unit, float partial) {
        if (!(unit instanceof RVP_OpticalWeaponUnit current) || !ownsScope(current)) return null;
        // 调用本体现有相机挂点，继续兼容 pivot、俯仰跟随和父级配置。
        return new SightRay(LocalVehiclePlayer.instance.cameraPosition(current, partial),
                RVP_OpticalSightMath.direction(current.viewRotation(partial)));
    }

    /** 同一份相机射线，坐标单位为方块，direction 为单位向量。 */
    public record SightRay(
            /** 本帧观瞄原点。 */ Vec3 origin,
            /** 本帧实际观瞄方向。 */ Vec3 direction) {}

    /** 本体光电选择仍调用 scopeAimPos，先把逻辑角对齐到同一帧真实视线。 */
    public static void prepareSelection(WeaponUnit unit) {
        if (!(unit instanceof RVP_OpticalWeaponUnit current) || !ownsScope(current)) return;
        // 调用真实姿态采样，只更新公开逻辑角，不创建第二台相机。
        var angles = RVP_OpticalSightMath.cameraAngles(new Quaternionf(currentRotation(current)), previous);
        var local = LocalVehiclePlayer.instance;
        local.cameraAimRotX = angles.pitch(); local.cameraAimRotY = angles.yaw(); local.cameraAimRotZ = angles.roll();
    }

    /** 资格门仅接受本地新版吊舱观瞄，打开菜单与电视会话不消费输入。 */
    private static boolean ownsScope(RVP_OpticalWeaponUnit unit) {
        var mc = Minecraft.getInstance(); var local = LocalVehiclePlayer.instance;
        return mc.player != null && mc.level != null && mc.screen == null && !mc.isPaused()
                && mc.options.getCameraType().isFirstPerson() && !RVP_ClientHitlState.isActive()
                && local.vehicle == unit.getVehicle() && local.viewType == LocalVehiclePlayer.ViewType.SCOPE
                && unit.getOwner() == mc.player && unit.getVehicle().level() == mc.level;
    }

    /** 统一实际相机早于焦点旧处理和震屏；渲染重复读取不会推进机械状态。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        var local = LocalVehiclePlayer.instance;
        if (!(local.getWeaponUnit() instanceof RVP_OpticalWeaponUnit current) || !ownsScope(current)
                || event.getCamera().getEntity() != Minecraft.getInstance().player) { clearView(); return; }
        if (station != current) { clearView(); station = current; }
        // 调用实际轨迹采样，普通与焦点相机完全同源。
        org.ywzj.rvp.client.debug.RVP_OpticalSightDebug.opticalCamera("OPTICAL_BEFORE", current, event);
        Quaterniond rotation = current.viewRotation((float) event.getPartialTick());
        previous = RVP_OpticalSightMath.cameraAngles(new Quaternionf(rotation), previous);
        event.setYaw(previous.yaw()); event.setPitch(previous.pitch()); event.setRoll(previous.roll());
        FirstPersonHandler.zRot = previous.roll();
        // 调用已有只读诊断，继续保留实机复验入口。
        org.ywzj.rvp.client.debug.RVP_OpticalSightDebug.opticalCamera("OPTICAL_AFTER", current, event);
    }

    /** 本地每刻最多发送一份绝对请求，历史上限三秒，不逐帧发包。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance(); var local = LocalVehiclePlayer.instance;
        LINKS.keySet().removeIf(unit -> unit.getVehicle().isRemoved() || unit.getVehicle().level() != mc.level);
        if (mc.player == null || mc.level == null || mc.isPaused()
                || !(local.getWeaponUnit() instanceof RVP_OpticalWeaponUnit current) || current.getOwner() != mc.player) return;
        var input = current.lastMotionInput();
        if (input == null) return;
        Link link = LINKS.computeIfAbsent(current, ignored -> new Link());
        long tick = mc.level.getGameTime();
        if (link.lastSentTick == tick) return;
        if (input.direction().equals(link.lastDirection) && java.util.Objects.equals(current.requestedPoint(), link.lastPoint)
                && current.motionPhase() == RVP_OpticalSightMotion.Phase.IDLE && tick - link.lastSentTick < 20) return;
        long sequence = ++link.sequence;
        link.history.addLast(new Prediction(sequence, input));
        while (link.history.size() > 64) link.history.removeFirst();
        link.lastDirection = input.direction(); link.lastPoint = current.requestedPoint(); link.lastSentTick = tick;
        // 调用专用请求通道，既有本体角度包不能覆盖已接管的服务器吊舱。
        RVP_Network.CHANNEL.sendToServer(new C2SOpticalSightCommand(mc.level.dimension().location(), current.getVehicle().getId(), current.getVehicle().getUUID(),
                current.getIndex(), link.session, sequence,
                !current.withStabilizer() && current.requestedPoint() == null
                        ? RVP_OpticalSightMotion.inverse(input.baseAfter(), input.direction()).normalize() : input.direction(), current.requestedPoint()));
    }

    /** 主线程接收权威状态，按实体身份、会话、确认序号隔离旧消息。 */
    public static void receive(S2COpticalSightState message) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(message.dimension())
                || !(mc.level.getEntity(message.vehicleId()) instanceof org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle)
                || !vehicle.getUUID().equals(message.vehicleUUID()) || message.stationIndex() < 0
                || message.stationIndex() >= vehicle.getPartUnits().size()
                || !(vehicle.getPartUnits().get(message.stationIndex()) instanceof RVP_OpticalWeaponUnit current)) return;
        // 先拒绝旧快照，再动请求和预测队列；只在恢复阶段去重会让旧请求仍然漏写。
        if (!current.acceptsSnapshot(message.tick())) return;
        Link link = LINKS.get(current);
        boolean local = current.getOwner() == mc.player && link != null;
        var replay = new ArrayList<RVP_OpticalSightMotion.Input>();
        if (local) {
            if (!link.session.equals(message.session()) || message.sequence() > link.sequence) return;
            while (!link.history.isEmpty() && link.history.peekFirst().sequence <= message.sequence()) link.history.removeFirst();
            for (Prediction prediction : link.history) replay.add(prediction.input);
        } else {
            // 远端也必须接续权威请求，不能在两份快照之间追逐过期的本体广播目标。
            Vec3 requested = message.direction();
            if (!message.stabilized() && message.point() == null) {
                // 关闭稳定器时保留服务端局部请求，不能受客户端机体显示延迟影响而改变相对方向。
                var base = org.ywzj.rvp.event.RVP_TurretTraverseHandler.sightBaseRotation(current, 1);
                requested = RVP_OpticalSightMotion.transform(new Quaterniond(base),
                        RVP_OpticalSightMotion.inverse(message.baseAfter(), requested)).normalize();
            }
            current.request(requested, message.point());
        }
        // 调用真实状态恢复和有界重放；同一显示校正同时作用于模型与相机。
        current.restoreMotion(message.state(), replay, message.tick(), message.accelerationSeconds(), local, mc.getFrameTime());
    }

    /** 每站本地控制租约；不使用静态实体 ID 拼接，避免换世界撞键。 */
    private static final class Link {
        /** 本次操作的身份。 */ private final UUID session = UUID.randomUUID();
        /** 已发出的最大序号。 */ private long sequence;
        /** 上一次发包的世界刻。 */ private long lastSentTick = Long.MIN_VALUE;
        /** 已发出的请求方向。 */ @Nullable private Vec3 lastDirection;
        /** 已发出的真实锁点。 */ @Nullable private Vec3 lastPoint;
        /** 等待权威确认的有界预测记录。 */ private final ArrayDeque<Prediction> history = new ArrayDeque<>();
    }

    /** 一份待确认输入，序号与本次执行过的运动输入绑定。 */
    private record Prediction(
            /** 输入序号。 */ long sequence,
            /** 固定逻辑刻的完整输入。 */ RVP_OpticalSightMotion.Input input) {}
}
