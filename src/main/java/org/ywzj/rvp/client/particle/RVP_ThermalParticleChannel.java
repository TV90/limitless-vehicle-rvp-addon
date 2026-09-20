package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.BaseAshSmokeParticle;
import net.minecraft.client.particle.CampfireSmokeParticle;
import net.minecraft.client.particle.FlameParticle;
import net.minecraft.client.particle.LargeSmokeParticle;
import net.minecraft.client.particle.SmokeParticle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.client.particle.SmokeCloudParticle;
import org.ywzj.vehicle.client.shader.ThermalHandler;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * [RVP] RVP 自定义粒子的热成像通道（2026-09-19 用户需求：火箭尾迹烟 / MCHR 爆炸烟 /
 * 视觉工厂粒子在本体热成像视角下发白）。
 *
 * <p><b>本体热成像原理</b>（{@code ThermalHandler} + {@code thermal.fsh}）：白热感来自
 * "被画进 PostChain 的 {@code thermal_buffer}"——AFTER_ENTITIES 把 512 格内实体全亮重画
 * 进去、AFTER_PARTICLES 只把本体 {@code SmokeCloudParticle} 重画进去、AFTER_LEVEL 由
 * thermal.fsh 按 {@code heat = 0.4 + 0.6×luma(颜色)} 按 alpha 混合成白热画面；
 * <b>未画进该 buffer 的粒子一律呈冷背景</b>——RVP 全部自定义粒子因此热成像下不发白。</p>
 *
 * <p><b>本通道（零 Mixin）</b>：RVP 粒子构造时向弱引用登记表自登记（粒子引擎持有强引用，
 * 引擎移除后弱条目随 GC 自动回收）；热成像激活时在 {@code AFTER_PARTICLES}（与本体烟通道
 * 同一阶段——该阶段 ModelViewStack 为 identity，须自行乘 event pose 补视图，见方法注释）
 * 反射读取本体私有静态 {@code thermalChain}（mod 类字段名不混淆，失败只告警一次并静默停用），
 * 把登记粒子按各自 {@code getRenderType()} 分组重画进 {@code thermal_buffer}——与本体烟
 * 同一条白热路径。渲染状态切换镜像本体 {@code ThermalHandler.renderSmokeCloudParticles}
 * （粒子 shader + 光照层 + 深度只测不写 + fabulous 模式还原 particlesTarget）。</p>
 *
 * <p>RVP 电视导引 {@code RVP_EnumVideoMode.THERMAL} 同样经本体
 * {@code ThermalHandler.setActive} 激活，本通道自动覆盖观瞄与 HITL 两种热成像视角。
 * 白热亮度由 thermal.fsh 按粒子颜色 luma 折算（灰白烟 → 高亮白热、橙焰 → 次亮），
 * 粒子本体颜色无需任何改动。热成像关闭时 {@code isActive} 短路，零开销零画面变化。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ThermalParticleChannel {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 热成像可视距离（与本体 ThermalHandler.THERMAL_RANGE 一致，超出不画进热缓冲）。 */
    private static final double THERMAL_RANGE = 512.0D;
    private static final double THERMAL_RANGE_SQ = THERMAL_RANGE * THERMAL_RANGE;

    /**
     * 热成像参与粒子登记表（弱引用集合）：键为粒子引擎正在持有的活粒子，引擎移除后
     * 条目随 GC 自动回收；渲染时再按 {@code isAlive()} 过滤回收前的死引用。
     * 仅客户端线程访问（构造与遍历都在客户端），无并发。
     */
    private static final Set<Particle> THERMAL_PARTICLES =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** 反射取本体 thermalChain 的失败标记：只告警一次，之后本通道静默停用（本体热成像不受影响）。 */
    private static boolean reflectionBroken = false;

    private RVP_ThermalParticleChannel() {
    }

    /**
     * 粒子自登记入口（在各 RVP 粒子构造器中调用）：把本粒子登记为热成像可见热源。
     *
     * @param particle RVP 自定义粒子（火箭尾迹烟 / MCHR 爆炸烟 / 曳光 / 白磷烟等热烟类）
     */
    public static void register(Particle particle) {
        THERMAL_PARTICLES.add(particle);
    }

    /**
     * AFTER_PARTICLES 阶段重画：与本体 {@code ThermalHandler} 完全同款阶段——该阶段
     * {@code ModelViewStack} 仍为 identity（vanilla 在粒子渲染之后才把视图矩阵乘上
     * ModelViewStack 供云/天气用，尾部还原），本通道自行 {@code mulPoseMatrix(eventPose)}
     * 补视图恰为单次变换，位置正确。
     *
     * <p><b>不可用 AFTER_WEATHER</b>（2026-09-19 实机错位根因）：该阶段 ModelViewStack
     * 已含视图矩阵（云/天气渲染需要），再乘 eventPose 即双重视图，粒子方位被转过头
     * （实机表现：热成像下爆炸烟出现在偏离爆点的方位上、与主画面烟一左一右两份）。</p>
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || !ThermalHandler.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || reflectionBroken) {
            return;
        }
        PostChain chain = resolveThermalChain();
        if (chain == null) {
            return;
        }
        RenderTarget thermalBuffer = chain.getTempTarget("thermal_buffer");
        if (thermalBuffer == null) {
            return;
        }
        List<Particle> visible = collectVisibleParticles(event);
        visible.addAll(collectVanillaThermalParticles(event, mc));
        if (visible.isEmpty()) {
            return;
        }
        renderIntoThermalBuffer(mc, thermalBuffer, event, visible);
    }

    /**
     * 反射读取本体 {@code ThermalHandler.thermalChain} 私有静态字段（每帧读取，自动跟随
     * 本体重建/resize 换新实例；mod 类字段不混淆，名字稳定）。失败只告警一次并停用本通道。
     */
    private static PostChain resolveThermalChain() {
        try {
            return ObfuscationReflectionHelper.getPrivateValue(ThermalHandler.class, null, "thermalChain");
        } catch (Throwable t) {
            reflectionBroken = true;
            LOGGER.warn("[RVP][ThermalParticle] 无法反射本体 ThermalHandler.thermalChain，"
                    + "RVP 粒子热成像通道停用（本体热成像不受影响）", t);
            return null;
        }
    }

    /** 收集本帧可见的登记粒子（存活 + 512 格内 + 视锥内），死引用随弱表自动淘汰。 */
    private static List<Particle> collectVisibleParticles(RenderLevelStageEvent event) {
        Vec3 cameraPos = event.getCamera().getPosition();
        List<Particle> visible = null;
        for (Particle particle : THERMAL_PARTICLES) {
            if (particle == null || !particle.isAlive()) {
                continue;
            }
            if (particle.getBoundingBox().getCenter().distanceToSqr(cameraPos) > THERMAL_RANGE_SQ) {
                continue;
            }
            if (event.getFrustum() != null && particle.shouldCull()
                    && !event.getFrustum().isVisible(particle.getBoundingBox())) {
                continue;
            }
            if (visible == null) {
                visible = new ArrayList<>();
            }
            visible.add(particle);
        }
        return visible == null ? new ArrayList<>() : visible;
    }

    /**
     * 从引擎 translucent 批收集白名单内的原版粒子（effects_data 常配的
     * {@code CAMPFIRE_SIGNAL_SMOKE} / {@code smoke} / {@code flame} / {@code large_smoke}
     * 等轨迹与尾迹粒子——原版类无法构造器自登记，改从引擎批次侧捞取）。
     *
     * <p>批次访问用<b>运行时反射</b>调用本体 accessor mixin 注入的 {@code getParticles()}
     * （public 方法，mod 自定义名不参与混淆；本体 {@code ThermalHandler} 强依赖同一方法，
     * 运行时必然存在）。<b>不走编译期 import</b>——本体的 mixin 包只在本体 dev 构建的
     * {@code -all} jar 里，发布 jar 的编译 classpath 不含它（2026-09-20 其它开发者
     * 编译失败反馈），反射失败走 reflectionBroken 静默降级（仅失去原版烟火粒子路径）。
     * 白名单：{@link SmokeParticle} / {@link LargeSmokeParticle} /
     * {@link CampfireSmokeParticle} / {@link BaseAshSmokeParticle} / {@link FlameParticle}
     * （烟火类热源，符合热成像语义）。本体 {@code SmokeCloudParticle} 由本体烟通道自己
     * 重画，此处跳过防双重提亮；RVP 登记表成员走各自 RenderType 分组，不在 translucent 批，
     * 无需排除。</p>
     */
    private static List<Particle> collectVanillaThermalParticles(RenderLevelStageEvent event, Minecraft mc) {
        Map<?, ?> batches;
        try {
            Method accessor = mc.particleEngine.getClass().getMethod("getParticles");
            batches = (Map<?, ?>) accessor.invoke(mc.particleEngine);
        } catch (Throwable t) {
            // 本体 accessor 缺失（版本不匹配等）仅失去原版粒子热成像，不影响登记表路径
            if (!reflectionBroken) {
                LOGGER.warn("[RVP][ThermalParticle] 读取引擎粒子批次失败，原版烟火类热成像停用", t);
                reflectionBroken = true;
            }
            return new ArrayList<>();
        }
        Queue<Particle> translucent = null;
        for (Map.Entry<?, ?> entry : batches.entrySet()) {
            if (entry.getKey() == ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT) {
                Object value = entry.getValue();
                if (value instanceof Queue<?> queue) {
                    translucent = (Queue<Particle>) queue;
                }
                break;
            }
        }
        if (translucent == null || translucent.isEmpty()) {
            return new ArrayList<>();
        }
        Vec3 cameraPos = event.getCamera().getPosition();
        List<Particle> result = new ArrayList<>();
        for (Particle particle : translucent) {
            if (particle == null || !particle.isAlive()
                    || particle instanceof SmokeCloudParticle
                    || !(particle instanceof SmokeParticle
                        || particle instanceof LargeSmokeParticle
                        || particle instanceof CampfireSmokeParticle
                        || particle instanceof BaseAshSmokeParticle
                        || particle instanceof FlameParticle)) {
                continue;
            }
            if (particle.getBoundingBox().getCenter().distanceToSqr(cameraPos) > THERMAL_RANGE_SQ) {
                continue;
            }
            if (event.getFrustum() != null && particle.shouldCull()
                    && !event.getFrustum().isVisible(particle.getBoundingBox())) {
                continue;
            }
            result.add(particle);
        }
        return result;
    }

    /**
     * 把可见粒子按各自 RenderType 分组重画进 thermal_buffer。渲染状态切换镜像本体
     * {@code ThermalHandler.renderSmokeCloudParticles}（含 fabulous 模式还原 particlesTarget），
     * 差异仅在于：粒子来源为 RVP 自登记弱表而非本体 mixin accessor 的引擎批次表。
     */
    private static void renderIntoThermalBuffer(Minecraft mc, RenderTarget thermalBuffer,
                                                RenderLevelStageEvent event, List<Particle> visible) {
        // 目的：记录 fabulous（着色器透明）模式下的粒子渲染目标，画完还原（与本体同款）
        RenderTarget previousTarget = Minecraft.useShaderTransparency()
                ? mc.levelRenderer.getParticlesTarget() : mc.getMainRenderTarget();
        PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        try {
            modelViewStack.mulPoseMatrix(event.getPoseStack().last().pose());
            RenderSystem.applyModelViewMatrix();
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            RenderSystem.enableDepthTest();
            RenderSystem.setShader(GameRenderer::getParticleShader);
            thermalBuffer.bindWrite(true);
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(false);

            // 目的：按 RenderType 分组（自定义类型均为单例，IdentityHashMap 足够）逐组
            // begin→render→end——与粒子引擎渲染同构，复用各类型自己的贴图绑定与混合设置
            Map<ParticleRenderType, List<Particle>> groups = new IdentityHashMap<>();
            for (Particle particle : visible) {
                groups.computeIfAbsent(particle.getRenderType(), k -> new ArrayList<>()).add(particle);
            }
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder builder = tesselator.getBuilder();
            for (Map.Entry<ParticleRenderType, List<Particle>> entry : groups.entrySet()) {
                entry.getKey().begin(builder, mc.getTextureManager());
                // 目的：白热显形提亮（2026-09-20 用户反馈"不够白"）——begin 后统一覆盖混合与
                // 深度状态（覆盖各 RenderType 自设的 blendFunc/depthMask）：
                // ① RGB = src.rgb×src.a + dst（亮度叠加）——重叠烟/多层火焰亮度快速饱和到白，
                //   符合"越浓越热"的热成像语义（原先 alpha 混合下烟重叠仍半透明灰暗）；
                // ② alpha = src.a + dst.a×(1-src.a)（over 累积）——原先 SRC_ALPHA 同时当
                //   alpha 方程源因子导致 dst.a=src.a² 平方衰减（烟 vertex alpha 0.3~0.7 写入
                //   后只剩 0.09~0.49，显形强度减半以上），over 累积不衰减；
                // ③ 深度只测不写——MCHR 烟 RenderType begin 自带 depthMask(true)，thermal_buffer
                //   的深度是本体温道 copyDepthFrom 的主画面深度，写脏会让后画粒子被前画深度剔除。
                RenderSystem.depthMask(false);
                RenderSystem.blendFuncSeparate(
                        GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                for (Particle particle : entry.getValue()) {
                    // 调用本体/本项目的公共渲染出口：粒子自身 render 复用正常渲染的全部
                    // 视觉逻辑（LOD/抖动插值/颜色曲线），只是目标换成 thermal_buffer
                    particle.render(builder, event.getCamera(), event.getPartialTick());
                }
                entry.getKey().end(tesselator);
            }

            // 目的：自绘几何类视觉特效重画进同一热缓冲——火球 additive → 白热核心，
            // 云层 alpha 混合 → 白热显形；两类渲染器完全自包含（setIdentity+相机旋转、
            // 自管混合/深度/shader/投影），热缓冲仍在绑定时调用，状态还原由其自身与
            // 下方 finally 共同兜底（2026-09-20 用户需求）
            org.ywzj.rvp.client.visual.RVP_ClientVisualEffectDispatcher.renderThermal(event);
            org.ywzj.rvp.client.nuclear.RVP_ExplosionVisualManager.renderThermal(event);
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            if (previousTarget != null) {
                previousTarget.bindWrite(true);
            } else {
                mc.getMainRenderTarget().bindWrite(true);
            }
        }
    }
}
