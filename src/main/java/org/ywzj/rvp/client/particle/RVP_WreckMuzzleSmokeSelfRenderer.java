package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 炮口白烟自绘管理器：白烟脱离 ParticleEngine 批次的正解（2026-10-07 三轮实机迭代定稿）。
 *
 * <p>背景：白烟与殉燃黑烟分属不同自定义 ParticleRenderType 批次，1.20.1 ParticleEngine 对
 * 自定义批不做距离排序、批间先后与距离无关——引擎内写深度则层间自剔（烟稀透背景）+ 剔穿
 * 后画载具切片，不写深度则黑烟批后画整批盖住白烟。本管理器把白烟挪到
 * {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES} 阶段（<b>恒晚于黑烟批</b>）自绘：
 * 按相机距离<b>降序排序</b>（远先画，alpha 混合近盖远）、<b>深度只测不写</b>——
 * 层间叠加正确增浓、载具按 alpha 混合软遮挡、白烟恒在黑烟之前。</p>
 *
 * <p>贴图按静态贴图索引分组绘制（组内共用排序序）；组间顺序与距离无关，但白烟同色系观感
 * 影响小。粒子的 tick 与 billboard 数学全部复用本体基类实现。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckMuzzleSmokeSelfRenderer {

    /** 自绘炮口烟活动列表；全部只在客户端线程访问。 */
    private static final List<RVP_WreckMuzzleSmokeParticle> ACTIVE = new ArrayList<>();
    /** 列表安全上限，防止极端齐射场景客户端状态无限增长（超限丢弃最老粒子）。 */
    private static final int MAX_ACTIVE = 2048;
    /** 列表当前绑定的客户端世界，用于检测世界切换并整体清理。 */
    private static ClientLevel activeLevel;

    private RVP_WreckMuzzleSmokeSelfRenderer() {
    }

    /**
     * 加入一枚炮口白烟粒子（由延迟炮口烟发射器在炮口实时位置调用）。
     *
     * @param level        客户端世界
     * @param position     粒子出生位置
     * @param velocity     粒子初速度，单位格/tick
     * @param size         初始面片尺寸，单位格
     * @param alpha        初始透明度
     * @param lifetime     生命周期，单位 tick
     * @param textureIndex 贴图变体索引
     * @param updraft      目标竖直上浮速度，单位格/tick
     * @param spread       水平摆动速度增量，单位格/tick
     * @param grey         灰度，0 为黑色，1 为白色
     */
    public static void spawn(ClientLevel level, net.minecraft.world.phys.Vec3 position,
                             net.minecraft.world.phys.Vec3 velocity,
                             float size, float alpha, int lifetime, int textureIndex,
                             double updraft, double spread, float grey) {
        add(new RVP_WreckMuzzleSmokeParticle(level, position, velocity, size, alpha, lifetime,
                textureIndex, updraft, spread, grey));
    }

    /** 加入自绘列表；超出预算时丢弃最早粒子。 */
    private static void add(RVP_WreckMuzzleSmokeParticle particle) {
        while (ACTIVE.size() >= MAX_ACTIVE) {
            ACTIVE.remove(0);
        }
        ACTIVE.add(particle);
    }

    /**
     * 客户端 Tick 末尾推进全部自绘粒子（对齐 ParticleEngine 的 tick 时机；世界切换即清空）。
     *
     * @param event 客户端 Tick 事件
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            ACTIVE.clear();
            activeLevel = null;
            return;
        }
        if (level != activeLevel) {
            // 世界/维度切换：粒子坐标是旧世界的，整体丢弃。
            ACTIVE.clear();
            activeLevel = level;
        }
        Iterator<RVP_WreckMuzzleSmokeParticle> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            RVP_WreckMuzzleSmokeParticle particle = iterator.next();
            particle.tick();
            if (particle.isGone()) {
                iterator.remove();
            }
        }
    }

    /**
     * AFTER_PARTICLES 阶段自绘全部炮口烟：按距离降序排序、深度只测不写、按贴图分组提交。
     *
     * @param event 关卡渲染阶段事件（此时黑烟等 ParticleEngine 粒子已绘制完毕）
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        Camera camera = event.getCamera();
        float partialTick = event.getPartialTick();
        // 镜像 ParticleEngine.render 的视图语义（字节码实证：引擎把 LevelRenderer 的 poseStack
        // ——含相机旋转——push 进 ModelViewStack 再绘制，结束 popPose 恢复 identity）。本阶段
        // 引擎已 pop，自绘必须同样补上相机旋转，否则烟顶点（相机相对坐标）缺视图变换、
        // 位置随相机朝向错乱（2026-10-07 实机"出烟位置变了"根因）。
        PoseStack poseStack = event.getPoseStack();
        PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        modelViewStack.mulPoseMatrix(poseStack.last().pose());
        RenderSystem.applyModelViewMatrix();
        // 距离降序（远先画）：alpha 混合下近处烟正确覆盖远处烟，多块叠加自然增浓。
        List<RVP_WreckMuzzleSmokeParticle> sorted = new ArrayList<>(ACTIVE);
        sorted.sort(Comparator.comparingDouble((RVP_WreckMuzzleSmokeParticle particle) ->
                particle.distanceSqrTo(camera)).reversed());

        // 镜像粒子引擎的渲染状态：粒子 shader + 深度测试 + 光照层 + alpha 混合 + 深度只测不写。
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        int textureCount = RVP_WreckMuzzleSmokeParticle.textureCount();
        try {
            for (int textureIndex = 0; textureIndex < textureCount; textureIndex++) {
                boolean batchOpen = false;
                for (RVP_WreckMuzzleSmokeParticle particle : sorted) {
                    if (particle.textureIndex() != textureIndex) {
                        continue;
                    }
                    if (!batchOpen) {
                        RenderSystem.setShaderTexture(0, RVP_WreckFlameParticle.textureLocation(textureIndex));
                        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                        batchOpen = true;
                    }
                    // 调用基类 billboard 绘制：相机相对坐标 + 面片尺寸/roll 旋转/光照由基类统一处理。
                    particle.render(builder, camera, partialTick);
                }
                if (batchOpen) {
                    tesselator.end();
                }
            }
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    /**
     * 热成像重画入口（2026-10-08）：由 {@link RVP_ThermalParticleChannel} 在 thermal_buffer
     * 绑定状态下调用（与温压/HBM 自绘几何 renderThermal 同模式——自绘烟脱离了 ParticleEngine，
     * 热通道的引擎批次捞取与登记表两条路径都覆盖不到，此前热成像视角下炮口烟不进热缓冲、
     * 被实体热源"覆盖"）。完全自包含：自行补视图（pushPose+eventPose，identity 起点单次变换）、
     * 自设状态（粒子 shader + 热成像白热混合 + 深度只测不写 + 光照层）、按与主画面相同的
     * 距离降序排序后按贴图分组重画；RenderTarget 的还原由通道 finally 兜底，本方法不碰。
     * 白烟 RGB 亮色经白热混合（RGB 亮度叠加、alpha over 累积）写入热缓冲——烟越浓越热，
     * 热成像下正确遮挡后方实体热源。
     *
     * @param event 关卡渲染阶段事件（AFTER_PARTICLES，热缓冲已绑定）
     */
    public static void renderThermal(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Camera camera = event.getCamera();
        float partialTick = event.getPartialTick();
        List<RVP_WreckMuzzleSmokeParticle> sorted = new ArrayList<>(ACTIVE);
        sorted.sort(Comparator.comparingDouble((RVP_WreckMuzzleSmokeParticle particle) ->
                particle.distanceSqrTo(camera)).reversed());

        PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        try {
            modelViewStack.mulPoseMatrix(event.getPoseStack().last().pose());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShader(GameRenderer::getParticleShader);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            // 热成像白热混合（与 RVP_ThermalParticleChannel.renderIntoThermalBuffer 同款）：
            // RGB 亮度叠加饱和到白（越浓越热），alpha over 累积不衰减（显形强度不缩水）
            RenderSystem.blendFuncSeparate(
                    com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
                    com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(false);
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder builder = tesselator.getBuilder();
            int textureCount = RVP_WreckMuzzleSmokeParticle.textureCount();
            for (int textureIndex = 0; textureIndex < textureCount; textureIndex++) {
                boolean batchOpen = false;
                for (RVP_WreckMuzzleSmokeParticle particle : sorted) {
                    if (particle.textureIndex() != textureIndex) {
                        continue;
                    }
                    if (!batchOpen) {
                        RenderSystem.setShaderTexture(0, RVP_WreckFlameParticle.textureLocation(textureIndex));
                        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                        batchOpen = true;
                    }
                    // 调用基类 billboard 绘制：几何与主画面完全一致，目标换成 thermal_buffer
                    particle.render(builder, camera, partialTick);
                }
                if (batchOpen) {
                    tesselator.end();
                }
            }
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
        }
    }
}
