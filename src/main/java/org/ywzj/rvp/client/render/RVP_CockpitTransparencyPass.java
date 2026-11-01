package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.light.RVP_LightingCompatibility;

/** 玻璃在全部实体之后混合：正常玩家先写深度，玻璃在该阶段读写深度，不补画或隐藏乘员。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT)
public final class RVP_CockpitTransparencyPass {
    /** 帧内透明几何，数组和顶点接收器跨帧复用。 */
    private static final RVP_TransparentGeometry<RenderType> GEOMETRY = new RVP_TransparentGeometry<>();
    /** 与原版实体缓冲隔离，避免透明材质切换提前提交玻璃。 */
    private static final BufferBuilder BUFFER = new BufferBuilder(262144);
    /** 主世界接收窗口；界面预览/阴影/远景离屏目标不混入。 */
    private static boolean collecting;
    /** 捕获对应的客户端世界。 */
    private static ClientLevel level;
    /** 主画面的目标编号与投影，防止异投影或离屏几何被画回错误目标。 */
    private static int framebuffer;
    private static final Matrix4f projection = new Matrix4f();
    /** 顶点已包含 PoseStack 的变换，只保留当时额外的 RenderSystem 观察矩阵。 */
    private static final Matrix4f modelView = new Matrix4f();
    private static boolean hasModelView;

    private RVP_CockpitTransparencyPass() {}

    /** 暂停、切世界或异常跳过某渲染阶段时也不能把上一帧的玻璃留到下一帧。 */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        collecting = false;
        level = null;
        hasModelView = false;
        GEOMETRY.beginFrame();
    }

    /** 用现有 Forge 阶段实现时序，不增加本体或原版注入点。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onWorldStage(RenderLevelStageEvent event) {
        // 调用既有光影桥，阴影重画不得清空/消耗主画面队列。
        if (RVP_LightingCompatibility.shadowPass()) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            GEOMETRY.beginFrame();
            level = Minecraft.getInstance().level;
            projection.set(RenderSystem.getProjectionMatrix());
            framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            collecting = level != null;
            hasModelView = false;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            collecting = false;
            flush();
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            collecting = false;
            GEOMETRY.beginFrame();
        }
    }

    /** 包装 RVP 模型原出口；只截取明确标记的座舱材质，其余顶点原样转发。 */
    public static MultiBufferSource defer(MultiBufferSource source) {
        Minecraft mc = Minecraft.getInstance();
        if (!collecting || level != mc.level || RVP_LightingCompatibility.shadowPass()) return source;
        // 调用现有载具上下文，同世界可见代理也需要正确遮挡；界面/离屏仍由目标和投影隔离。
        var context = RVP_CockpitPassengerRenderContext.current();
        if (context == null || context.vehicle().level() != level
                || framebuffer != GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
                || !projection.equals(RenderSystem.getProjectionMatrix())) return source;
        if (hasModelView && !modelView.equals(RenderSystem.getModelViewMatrix())) return source;
        modelView.set(RenderSystem.getModelViewMatrix());
        hasModelView = true;
        return type -> {
            // 调用材质实例身份判定，避免根据名称或贴图猜测屏幕/玻璃。
            if (!RVP_RenderTypes.isCockpitTransparent(type)) return source.getBuffer(type);
            return GEOMETRY.capture(type, type.mode() == VertexFormat.Mode.QUADS ? 4 : 3);
        };
    }

    /** 实体和方块实体均已提交后，按面距绘制；同材质连续面只产生一次 draw。 */
    private static void flush() {
        if (GEOMETRY.faceCount() == 0 || level != Minecraft.getInstance().level) return;
        // 显式提交共享实体缓冲：仅把 Java 调用排在玩家之后，并不代表玩家已经画进颜色/深度。
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        var stack = RenderSystem.getModelViewStack();
        stack.pushPose();
        stack.last().pose().set(modelView);
        RenderSystem.applyModelViewMatrix();
        try {
            GEOMETRY.sortBackToFront();
            RenderType active = null;
            for (int i = 0; i < GEOMETRY.faceCount(); i++) {
                // 调用延后专用材质：实体都已提交，现在可以写深度而不吞掉尚未绘制的乘员。
                RenderType material = RVP_RenderTypes.cockpitComposite(GEOMETRY.materialAtSorted(i));
                if (material != active) {
                    if (active != null) active.end(BUFFER, RenderSystem.getVertexSorting());
                    BUFFER.begin(material.mode(), material.format());
                    active = material;
                }
                GEOMETRY.emitSorted(i, BUFFER);
            }
            if (active != null) active.end(BUFFER, RenderSystem.getVertexSorting());
        } finally {
            if (BUFFER.building()) BUFFER.end().release();
            GEOMETRY.beginFrame();
            stack.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }
}
