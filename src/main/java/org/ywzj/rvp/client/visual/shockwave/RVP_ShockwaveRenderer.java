package org.ywzj.rvp.client.visual.shockwave;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.shake.RVP_ShockwaveScreenShake;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Group.sphere;
import static org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Parameter.*;

/** 普通爆炸的球形空气冲击层；沿用尺寸时间和压薄曲线，核爆使用原独立画法。 */
public final class RVP_ShockwaveRenderer {
    /** 每个新波面只记录一次首帧，帮助核对现场相机参数，不输出聊天消息。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 核爆继续使用的原有折射资源，不修改也不用于普通空气层。 */
    private static final ResourceLocation SHADER = RVP_MOD.modLocation("warp_world");
    /** 既有事件参数的标准时间长度（tick）。 */
    private static final int LIFETIME = 220;
    /** 最多同时保留 8 个空气层，不生成粒子或逐帧查询地形。 */
    private static final List<Shockwave> ACTIVE = new ArrayList<>();
    /** 普通空气层的屏幕合成器；临时颜色与深度只保存在显存。 */
    private static final RVP_PressureWavePass VOLUME = new RVP_PressureWavePass();
    /** 核爆使用的既有折射着色器。 */
    private static ShaderInstance shader;
    /** 当前所属世界。 */
    private static ClientLevel activeLevel;
    /** 本帧地形使用的完整观察矩阵；Forge 1.20.1 的 AFTER_LEVEL 事件栈实际装的是投影矩阵。 */
    private static final Matrix4f FRAME_VIEW = new Matrix4f();
    /** 与上述观察矩阵配对的投影，包含视野变化和镜头晃动。 */
    private static final Matrix4f FRAME_PROJECTION = new Matrix4f();
    /** 本帧是否取得配套的世界矩阵和深度，结束阶段消费后立即清零。 */
    private static boolean frameReady;

    private RVP_ShockwaveRenderer() { }

    private static void ensureLevel() {
        if (activeLevel != Minecraft.getInstance().level) {
            // 调用统一清理入口，避免跨维度保留旧位置和旧画面。
            clear();
            activeLevel = Minecraft.getInstance().level;
        }
    }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), SHADER,
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL), loaded -> shader = loaded);
        // 调用独立合成器注册新增空气层程序，保留核爆原有资源及入口。
        VOLUME.registerShader(event);
    }

    /** 创建可取消的空气冲击层；尺寸、时间和会话快照仍来自同一正式事件。 */
    public static Shockwave spawn(double x, double y, double z, int initialAge, int baseDuration,
            float radius, RVP_ShockwaveRuntimeTuning.Snapshot tuning, long seed) {
        // 调用世界检查和寿命换算，保持既有扩张速度。
        ensureLevel();
        int duration = tuning.duration(sphere, baseDuration);
        if (initialAge >= duration || !Float.isFinite(radius) || radius <= 0) { return null; }
        if (ACTIVE.size() >= 8) { ACTIVE.remove(0); }
        Shockwave wave = new Shockwave(x, y, z, Math.max(0, initialAge), duration, baseDuration, tuning, seed);
        ACTIVE.add(wave);
        return wave;
    }

    /** 核爆取用原折射着色器。 */
    @javax.annotation.Nullable
    public static ShaderInstance shader() { return shader; }

    /** 取消单个实例。 */
    public static void remove(Shockwave wave) { ACTIVE.remove(wave); }

    public static void tick() {
        // 调用世界检查，只在客户端逻辑 tick 推进一次年龄。
        ensureLevel();
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            if (++ACTIVE.get(i).age >= ACTIVE.get(i).duration) { ACTIVE.remove(i); }
        }
    }

    /** 每帧按玩家而非第三人称相机的位置判定波面抵达；与空气层绘制共用半径和时钟。 */
    public static void updateScreenShake(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || activeLevel != minecraft.level) { return; }
        float partialTick = (float) event.getPartialTick();
        Vec3 playerPosition = minecraft.player.getPosition(partialTick);
        double now = minecraft.level.getGameTime() + event.getPartialTick();
        for (Shockwave wave : ACTIVE) {
            // 调用同一快照的年龄换算，调慢扩张、加速或修改曲线后仍与实际波面同步。
            float age = wave.standardAge(partialTick);
            if (wave.tuning.age(sphere, age) >= wave.tuning.get(sphere, lifetime)) { continue; }
            // 调用单波面抵达判定，只比较三维直线距离；墙体遮挡不影响震动。
            wave.screenShake.update(now, playerPosition.distanceTo(new Vec3(wave.x, wave.y, wave.z)),
                    wave.tuning.sphereRadius(age), wave.maximumRadius);
        }
    }

    public static void clear() {
        ACTIVE.clear();
        activeLevel = null;
        frameReady = false;
        // 调用合成器释放临时显存；退出世界后的清理也允许由非绘制线程转交。
        if (RenderSystem.isOnRenderThread()) { VOLUME.release(); }
        else { RenderSystem.recordRenderCall(VOLUME::release); }
    }

    /** 在后处理运行前保存本帧世界输入；由高优先级 Forge 事件调用，不修改本体的画面处理。 */
    public static void captureFrame(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) { return; }
        frameReady = false;
        // 调用深度失效入口，暂停、离开世界或无波面时不能遗留上一帧的遮挡。
        VOLUME.discardDepth();
        if (ACTIVE.isEmpty() || Minecraft.getInstance().level == null) { return; }
        // Forge 47.4.13 末尾传出的栈含投影，不能用来变换爆心；此处仍是真实世界观察矩阵。
        FRAME_VIEW.set(event.getPoseStack().last().pose());
        FRAME_PROJECTION.set(event.getProjectionMatrix());
        // 调用提前复制入口，保留过载等末端画面处理尚未改写的真实地形和实体遮挡。
        frameReady = VOLUME.captureDepth();
    }

    public static void render(RenderLevelStageEvent event) {
        // 完整世界合成后再读取背景，避免 Fabulous 的天气独立缓冲使折射只读到透明画面。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) { return; }
        boolean ready = frameReady;
        frameReady = false;
        if (!ready || ACTIVE.isEmpty() || Minecraft.getInstance().level == null) {
            // 调用失效入口，未能完成配对的世界输入不得留到下一帧。
            VOLUME.discardDepth();
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        // 每个爆炸最多一层宽厚雾和一层透明压力波前；活跃爆炸总量仍限制为 8 个。
        List<RVP_PressureWavePass.Wave> visible = new ArrayList<>(ACTIVE.size() * 2);
        for (Shockwave wave : ACTIVE) {
            // 调用与抵达判定共用的年龄换算，避免绘制与震动使用不同扩张时钟。
            float age = wave.standardAge(event.getPartialTick());
            // 调用同一快照的扩张、淡出与厚度压缩公式，避免出现两个不同的动画时钟。
            float fade = wave.tuning.alpha(sphere, age);
            float radius = wave.tuning.sphereRadius(age);
            float width = wave.tuning.sphereThickness(age);
            if (fade <= 0.0001F || radius <= 0.05F || width <= 0.0001F) { continue; }
            // 世界坐标先用 double 相减，再使用包含镜头摇晃的完整观察矩阵，避免远坐标抖动。
            Vector3f center = new Vector3f((float) (wave.x - camera.x), (float) (wave.y - camera.y),
                    (float) (wave.z - camera.z));
            FRAME_VIEW.transformPosition(center);
            // 调用保守投影范围计算；离屏不读取背景，也不运行逐像素体积积分。
            var bounds = RVP_PressureWaveGeometry.screenBounds(FRAME_PROJECTION,
                    center.x, center.y, center.z, radius);
            if (!wave.firstFrameLogged) {
                wave.firstFrameLogged = true;
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("RVP冲击波首帧: age={} radius={} fade={} center={} projectionScale=({}, {}) bounds={}",
                            age, radius, fade, center, FRAME_PROJECTION.m00(), FRAME_PROJECTION.m11(), bounds);
                    // 调用只读绘制状态记录，把矩阵裁剪与程序/深度/采样状态问题区分开。
                    VOLUME.logFrameState();
                }
            }
            if (bounds == null) { continue; }
            // 调用双层组装：先保留宽厚雾层，再复用同一绘图资源强化外侧透明波前。
            RVP_PressureWavePass.appendLayers(visible, new RVP_PressureWavePass.Wave(center, radius * (1 - width), radius,
                    wave.tuning.get(sphere, brightness), fade, wave.tuning.get(sphere, refraction),
                    wave.tuning.get(sphere, dispersion), age, bounds,
                    new Vector3f((float) (wave.x % 97), (float) (wave.y % 89), (float) (wave.z % 83))), wave.tuning, age);
        }
        // 调用体积合成器，用提前保存的本帧世界深度裁掉遮挡，末端画面处理不再影响波面是否可见。
        if (!visible.isEmpty()) { VOLUME.render(FRAME_PROJECTION, FRAME_VIEW, visible); }
        // 调用失效入口，所有波面都在屏幕外时也要结束本帧的深度使用期。
        else { VOLUME.discardDepth(); }
    }

    public static final class Shockwave {
        /** 爆心世界 X。 */
        private final double x;
        /** 爆心世界 Y，保留真实爆炸高度。 */
        private final double y;
        /** 爆心世界 Z。 */
        private final double z;
        /** 当前年龄（tick）。 */
        private int age;
        /** 是否已经记录本实例首帧；只控制调试日志，不参与播放和可见性判定。 */
        private boolean firstFrameLogged;
        /** 实际寿命（tick）。 */
        private final int duration;
        /** 事件基准时长（tick）。 */
        private final int baseDuration;
        /** 本次爆炸的固定参数快照。 */
        private final RVP_ShockwaveRuntimeTuning.Snapshot tuning;
        /** 波面实际寿命结束时的可达半径（格），包含扩张速度、曲线与 2048 格上限。 */
        private final float maximumRadius;
        /** 本波面独立的一次性抵达状态；自动追加事件无需启用旧的 shake 开关。 */
        private final RVP_ShockwaveScreenShake screenShake;

        private Shockwave(double x, double y, double z, int age, int duration, int baseDuration,
                RVP_ShockwaveRuntimeTuning.Snapshot tuning, long seed) {
            this.x = x; this.y = y; this.z = z;
            this.age = age; this.duration = duration; this.baseDuration = baseDuration;
            this.tuning = tuning;
            // 调用精确时间换算取得寿命末端，不能用向上取整后的回收 tick 夸大最大可达范围。
            maximumRadius = tuning.sphereRadius(tuning.get(sphere, lifetime) / tuning.age(sphere, 1));
            // 创建本波面的一次性反馈状态，复用通用执行器和爆炸强度设置。
            screenShake = new RVP_ShockwaveScreenShake(seed);
        }

        /** 把实际年龄换算为绘制与震动共用的标准年龄，单位游戏刻。 */
        private float standardAge(float partialTick) { return (age + partialTick) * LIFETIME / baseDuration; }
    }
}
