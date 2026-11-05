package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.vehicle.RVP_OpticalWeaponUnit;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.control.InputHandler;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.nio.file.Path;

/** 只观察吊舱输入与画面，不设置任何角度；默认关闭，使用 /rvpopticaldiag 控制。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID)
public final class RVP_OpticalSightDebug {
    /** 独立日志相对当前游戏目录的路径。 */
    private static final String LOG_NAME = "logs/rvp_optical_sight_debug.log";
    /** 最长四分之一秒刷新一次；off 和断线会立即刷盘。 */
    private static final long FLUSH_INTERVAL_NANOS = 250_000_000L;
    /** null 表示从未开启；不保存配置，重启客户端恢复默认关闭。 */
    @Nullable private static RVP_OpticalSightLogFile file;
    /** 本次会话内的鼠标输入编号。 */
    private static long inputSequence;
    /** 本次会话内的渲染帧编号；同帧的各个相机阶段使用同一编号。 */
    private static long frame;
    /** 上次刷新时间，使用单调时钟。 */
    private static long lastFlushNanos;
    /** 最近记录配置的观瞄站，用对象身份识别换座和换车。 */
    @Nullable private static WeaponUnit observedStation;
    /** 最近一次环境摘要；发生变化才写入，离开观瞄也能留证。 */
    private static String lastContext = "";

    /** 工具类不允许实例化。 */
    private RVP_OpticalSightDebug() {}

    /** 关闭时只检查引用，不创建路径、构造快照或查询相机。 */
    private static boolean enabled() { return file != null && file.isOpen(); }

    /** 注册本地客户端命令，单机和联机均不需要服务器管理员权限。 */
    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        // 调用同一命令构造入口，补全与测试使用实际注册的命令树。
        event.getDispatcher().register(build());
    }

    /** 构造命令树；单独构造不启动日志或读取 Minecraft 单例。 */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("rvpopticaldiag")
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("on").executes(context -> {
                    if (!enabled()) {
                        file = new RVP_OpticalSightLogFile(path());
                        inputSequence = frame = lastFlushNanos = 0L;
                        observedStation = null;
                        lastContext = "";
                        // 调用专用文件开启，版本与代码来源便于核对实机是否载入了当前构建。
                        file.open("mod=" + RVP_MOD.MOD_ID
                                + " version=" + RVP_MOD.class.getPackage().getImplementationVersion()
                                + " code_source=" + RVP_MOD.class.getProtectionDomain().getCodeSource().getLocation()
                                + " angles=degrees positions=blocks input=handlePlayerTurn_sensitivity_adjusted_not_pixels"
                                + " input_model=constrained_sight_v4 scope_exit_reset=aircraft_default_v1");
                    }
                    return status(context.getSource());
                }))
                .then(Commands.literal("off").executes(context -> {
                    if (file != null) file.close("command_off");
                    observedStation = null;
                    return status(context.getSource());
                }))
                .then(Commands.literal("mark")
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context -> {
                            if (!enabled()) {
                                context.getSource().sendFailure(Component.literal("[吊舱诊断] 请先执行 /rvpopticaldiag on"));
                                return 0;
                            }
                            // 调用日志标记，玩家可注明当前准备做的鼠标动作，不改变操控或焦点。
                            write("MARK", "text=" + StringArgumentType.getString(context, "text"));
                            file.flush();
                            context.getSource().sendSuccess(() -> Component.literal("[吊舱诊断] 已记录标记"), false);
                            return 1;
                        })));
    }

    /** 向玩家显示实际开启/错误状态和日志绝对路径。 */
    private static int status(CommandSourceStack source) {
        String error = file == null ? null : file.failure();
        String message = "[吊舱诊断] " + (enabled() ? "记录中" : "已关闭（默认）")
                + " | " + path().toAbsolutePath()
                + " | /rvpopticaldiag on|off|status|mark <说明>";
        if (error != null) {
            source.sendFailure(Component.literal(message + " | 写入失败：" + error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    /** 仅在指令实际使用路径时解析当前游戏目录，不硬编码测试客户端盘符。 */
    private static Path path() { return FMLPaths.GAMEDIR.get().resolve(LOG_NAME); }

    /** 已有鼠标桥的入口观测；返回 0 表示未开启或当前不是观瞄，不消费任何输入。 */
    public static long beforeInput(double yawInput, double pitchInput) {
        if (!enabled()) return 0L;
        WeaponUnit unit = scopeStation();
        if (unit == null || RVP_ClientHitlState.isActive()) return 0L;
        long id = ++inputSequence;
        // 调用纯只读快照，输入单位是本体收到的灵敏度处理后数值，不声称为原始像素。
        write("INPUT", "input_id=" + id + " yaw_input=" + yawInput + " pitch_input=" + pitchInput + snapshot(unit));
        return id;
    }

    /** 记录已有桥是否消费输入；交回本体时明确标为尚待本体处理，不伪称已经处理完。 */
    public static void afterInput(long id, String route) {
        if (!enabled() || id == 0L) return;
        WeaponUnit unit = scopeStation();
        write("INPUT_ROUTE", "input_id=" + id + " route=" + route
                + (unit == null ? " station=none" : snapshot(unit)));
    }

    /** 记录焦点输入真正使用的原点、倍率比例和碰撞检测前请求点，不重新计算或修改锁点。 */
    public static void focusRequest(WeaponUnit unit, Vec3 origin, double sensitivityScale, @Nullable Vec3 requested) {
        if (!enabled()) return;
        write("FOCUS_REQUEST", "origin=" + origin + " sensitivity_scale=" + sensitivityScale
                + " requested_before_raycast=" + requested + snapshot(unit));
    }

    /** 只在显式开启诊断时记录飞机退出观瞄的默认姿态，不额外读取或修改相机状态。 */
    public static void aircraftViewReset(boolean toOperator, float pitch, float yaw, float roll) {
        if (!enabled()) return;
        write("AIRCRAFT_SCOPE_EXIT", "target=" + (toOperator ? "OPERATOR" : "THIRD_PERSON")
                + " default_pitch=" + pitch + " default_yaw=" + yaw + " default_roll=" + roll);
    }

    /** 已有吊舱相机事件的前后观测，便于区分本体相机和连续姿态接管造成的变化。 */
    public static void opticalCamera(String stage, WeaponUnit unit, ViewportEvent.ComputeCameraAngles event) {
        if (!enabled()) return;
        write(stage, cameraAngles(event) + snapshot(unit));
    }

    /** 在原有相机处理和普通优先级震屏之后记录事件角度；不修改事件。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (!enabled()) return;
        WeaponUnit unit = scopeStation();
        if (unit == null || event.getCamera().getEntity() != Minecraft.getInstance().player) return;
        write("CAMERA_LOWEST", cameraAngles(event) + snapshot(unit));
    }

    /** 为同一帧分配编号，并在渲染结束时读取已经应用到 Camera 对象的实际视线。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!enabled()) return;
        if (event.phase == TickEvent.Phase.START) { frame++; return; }
        WeaponUnit unit = scopeStation();
        if (unit == null) return;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var look = camera.getLookVector();
        var up = camera.getUpVector();
        write("RENDER_END", "camera_object_pitch=" + camera.getXRot() + " camera_object_yaw=" + camera.getYRot()
                + " camera_object_look=" + vector(look.x, look.y, look.z)
                + " camera_object_up_without_event_roll=" + vector(up.x, up.y, up.z)
                + " camera_position=" + camera.getPosition() + snapshot(unit));
    }

    /** 记录每个逻辑刻结束时的目标角和实际角，并在换站/模式变化时写入配置与环境。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!enabled() || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        WeaponUnit unit = scopeStation();
        String context = "dimension=" + (minecraft.level == null ? "none" : minecraft.level.dimension().location())
                + " view=" + (local == null ? "none" : local.viewType)
                + " camera_type=" + minecraft.options.getCameraType()
                + " screen=" + (minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName())
                + " paused=" + minecraft.isPaused() + " hitl=" + RVP_ClientHitlState.isActive()
                + " station=" + (unit == null ? "none" : unit.getVehicle().getId() + ":" + unit.getIndex());
        if (!context.equals(lastContext)) {
            write("CONTEXT", context);
            lastContext = context;
        }
        if (unit != observedStation) {
            observedStation = unit;
            if (unit != null) {
                write("CONFIG", "station_class=" + unit.getClass().getName() + " optical_subclass="
                        + (unit instanceof RVP_OpticalWeaponUnit)
                        + " pitch_limits=" + unit.xRotMin + "," + unit.xRotMax
                        + " yaw_limits=" + unit.yRotMin + "," + unit.yRotMax
                        + " self_angles=" + unit.xSelfRot + "," + unit.ySelfRot
                        + " barrel_angles=" + unit.xBarrelSelfRot + "," + unit.yBarrelSelfRot
                        + " speeds=" + unit.getXRotSpeed() + "," + unit.getYRotSpeed()
                        + " stabilizer=" + unit.withStabilizer() + " focus_locker=" + unit.withFocusLocker()
                        + " sight_type=" + unit.getOpticalSightType() + snapshot(unit));
            }
        }
        if (unit != null) write("TICK_END", snapshot(unit));
        long now = System.nanoTime();
        if (now - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
            file.flush();
            lastFlushNanos = now;
        }
    }

    /** 离开服务器自动结束记录，下次进入世界不会继续开启诊断。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (file != null) file.close("logout");
        observedStation = null;
        lastContext = "";
    }

    /** 只读取得本地观瞄站；兼容原站，以便日志发现未更新载具部件类型的情况。 */
    @Nullable
    private static WeaponUnit scopeStation() {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (Minecraft.getInstance().player == null || local == null || local.vehicle == null
                || local.viewType != LocalVehiclePlayer.ViewType.SCOPE) return null;
        return local.getWeaponUnit();
    }

    /** 读取已有角度/方向，不调用可能改变连续角记忆的焦点方向解析，也不写任何游戏状态。 */
    private static String snapshot(WeaponUnit unit) {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        var vehicle = unit.getVehicle();
        Vec3 target = unit.getFocusLockPos();
        var base = unit.baseRot();
        return " tick=" + vehicle.level().getGameTime() + " vehicle=" + vehicle.getVehicleId()
                + " entity=" + vehicle.getId() + " station=" + unit.getId() + " station_index=" + unit.getIndex()
                + " actual_pitch=" + unit.getXRot() + " actual_yaw=" + unit.getYRot()
                + (unit instanceof RVP_OpticalWeaponUnit optical ? " motion_phase=" + optical.motionPhase()
                    + " accepted_sequence=" + optical.acceptedSequence() + " acceleration_seconds=" + optical.accelerationSeconds() : "")
                + " aim_pitch=" + unit.getXAimRot() + " aim_yaw=" + unit.getYAimRot()
                + " previous_pitch=" + unit.xRotO + " previous_yaw=" + unit.yRotO
                + " actual_world_direction=" + unit.worldVec()
                + " aim_world_direction=" + unit.worldVec(unit.getXAimRot(), unit.getYAimRot())
                + " base_rotation_xyzw=" + base.x + "," + base.y + "," + base.z + "," + base.w
                + " vehicle_angles=" + vehicle.getXRot() + "," + vehicle.getYRot() + "," + vehicle.getZRot()
                + " vehicle_position=" + vehicle.position()
                + " logical_camera=" + local.cameraAimRotX + "," + local.cameraAimRotY + "," + local.cameraAimRotZ
                + " mouse_turned=" + local.mouseTurnedAfterScope + " lerp_steps=" + local.playerLerpSteps
                + " free_camera=" + InputHandler.freeCamera + " focus=" + target
                + " zoom=" + unit.getZoom() + " fov=" + VectorUtil.fov;
    }

    /** 明确画面右/上方向，便于对照鼠标左右/上下；Camera 本身不包含事件附加的横滚。 */
    private static String cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(-event.getYaw()))
                .rotateX(Math.toRadians(event.getPitch())).rotateZ(Math.toRadians(event.getRoll()));
        Vector3d right = rotation.transform(new Vector3d(-1, 0, 0));
        Vector3d up = rotation.transform(new Vector3d(0, 1, 0));
        Vector3d forward = rotation.transform(new Vector3d(0, 0, 1));
        return "partial=" + event.getPartialTick() + " event_pitch=" + event.getPitch()
                + " event_yaw=" + event.getYaw() + " event_roll=" + event.getRoll()
                + " screen_right=" + vector(right.x, right.y, right.z)
                + " screen_up=" + vector(up.x, up.y, up.z)
                + " screen_forward=" + vector(forward.x, forward.y, forward.z);
    }

    /** 保留方向分量的完整数值，避免 JOML 默认三位小数格式掩盖竖直附近的小量。 */
    private static String vector(double x, double y, double z) { return "(" + x + "," + y + "," + z + ")"; }

    /** 各阶段统一带渲染帧和最近输入编号，关闭后不写其他日志。 */
    private static void write(String stage, String details) {
        if (enabled()) file.write(stage, "frame=" + frame + " last_input=" + inputSequence + " " + details);
    }
}
