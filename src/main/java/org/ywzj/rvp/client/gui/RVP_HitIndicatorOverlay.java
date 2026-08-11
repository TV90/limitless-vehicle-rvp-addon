package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.client.state.RVP_ClientHitIndicatorState;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RVP 命中提示：被击中载具后，在屏幕右上角展板内渲染载具 3D 模型 + 受击部位红色射线 +
 * 百分比伤害 + 骨骼显示别名。
 * 取代本体动作栏 HBX 命中消息；是否启用由被命中载具 JSON 顶层 {@code hit_indicator_rvp}（默认 true）
 * 控制，与服务端发包一致（未启用时走本体命中提示，二选一）。
 * <p>展板与模型尺寸均按屏幕实际分辨率（像素）计算，再换算回 GUI 坐标 —— 不受“界面尺寸”
 * （GUI 缩放）影响，只随分辨率自适应。</p>
 * <p>渲染通道：实现 {@link IGuiOverlay} 并注册在雷达 overlay 之后（{@link RVP_OverlayRegistry}），
 * 与雷达/RWR 走同一条已验证的 overlay 渲染管线；不依赖 {@code RenderGuiEvent.Post}
 * （当前环境该 Forge 事件不派发，旧实现导致展板完全不渲染）。</p>
 */
public final class RVP_HitIndicatorOverlay implements IGuiOverlay {

    /** 展板宽/高占屏幕实际分辨率的比例 */
    private static final double PANEL_W_FRAC = 0.32;
    private static final double PANEL_H_FRAC = 0.306;
    /** 展板距屏幕右/上边缘的像素边距 */
    private static final int MARGIN_PX = 12;
    /** 文字物理字号（像素高度） */
    private static final int TEXT_HEIGHT_PX = 36;
    /**
     * 固定展示俯仰角（度）：模型总是以该俯仰展示（旋转角 = DISPLAY_PITCH + 180）。
     * 取轻微俯视：不随命中点高度变化，任何受击部位都是同一正常姿态（接近平视、略看到车顶）。
     */
    private static final double DISPLAY_PITCH = -5.0;
    /**
     * 模型在 GUI 空间中的 z 偏移：GUI 正交投影近平面在 z=0，模型绕自身中心旋转后
     * 有一半块落在 z<0（近平面之外）会被硬裁剪 —— 整体平移到正 z 处，
     * 让所有面都落在近远平面之间（正交投影下 z 不影响 xy 大小，纯深度偏移）。
     */
    private static final double GUI_MODEL_Z = 100.0;
    private static final int BORDER_PX = 2;
    private static final int COLOR_BACKGROUND = 0xE0606060;
    private static final int COLOR_BORDER = 0xFF000000;
    private static final int COLOR_BONE = 0xFF40E8FF;
    private static final int COLOR_DAMAGE_CRITICAL = 0xFFFF5555;
    private static final int COLOR_DAMAGE_HURT = 0xFFFFD060;
    private static final int COLOR_DAMAGE_HIT = 0xFFFFFFFF;
    /** 爆炸基础红圈末段半径（米）：r0 × (0.5 + 1.6)，爆炸扩散圈以此为起点继续外扩 */
    private static final float EXPLOSION_BURST_END_RADIUS = 0.7f * (0.5f + 1.6f);
    /** 直击爆点末段半径（米）：r0=0.2 扩张到 1.5 倍，带爆炸的直击命中扩散圈以此为起点 */
    private static final float DIRECT_BURST_END_RADIUS = 0.2f * 1.5f;
    /** 爆炸圈最大倍率：扩散圈至多为基础圈末半径的 5 倍 */
    private static final float EXPLOSION_RING_MAX_MULTIPLIER = 5f;
    /** 爆炸半径达到该值时扩散圈最大（方块/米），随爆炸半径线性插值 */
    private static final float EXPLOSION_RING_MAX_RADIUS = 20f;
    /** 弹药模型后拉距离回退值（米）：优先读烘焙模型弹头最前点自动计算；读不到/异常时用此固定值 */
    private static final float PROJECTILE_BACK_FALLBACK = 3.0f;

    /** 展板模型"状态回放延迟"（毫秒）：展板渲染时动画脚本的爆反状态查询回到 1 秒前，
     *  刚被摧毁的爆反骨块在展板里晚 1 秒消失；世界渲染仍实时（立即消失）。 */
    private static final long RENDER_DELAY_MS = 1000L;

    /**
     * 爆点小圆球（GUI 空间实心圆盘，永远面向屏幕）：
     * POSITION_COLOR 顶点格式必须配 position_color shader（entityTranslucent 期望纹理/光照
     * attribute，与 POSITION_COLOR 不匹配会导致圆盘不可见），半透明输出到主缓冲区。
     */
    private static final RenderType HIT_CIRCLE = RenderType.create("rvp_hit_circle",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLE_FAN,
            256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(
                            GameRenderer::getPositionColorShader))
                    .setTransparencyState(new RenderStateShard.TransparencyStateShard(
                            "rvp_hit_circle_transparency",
                            () -> {
                                RenderSystem.enableBlend();
                                RenderSystem.defaultBlendFunc();
                            },
                            RenderSystem::disableBlend))
                    .setCullState(new RenderStateShard.CullStateShard(false))
                    // 519 = GL_ALWAYS：圆盘永远通过深度测试，避免被 GUI z=100 的模型遮挡而显得“靠内”
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("rvp_hit_circle_no_depth", 519))
                    .createCompositeState(true));

    /**
     * 展板底色/边框填充：GL_ALWAYS 深度测试 + 写深度。
     * 命中展板绘制时，任何先前已画到帧缓冲的内容（雷达/RWR 的 XX°、XX m 文字等）
     * 都会被底色彻底盖住；展板内标题文字与模型在填充之后绘制，正常显示在底色之上。
     */
    private static final RenderType PANEL_BG = RenderType.create("rvp_panel_bg",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
            512, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(
                            GameRenderer::getPositionColorShader))
                    .setTransparencyState(new RenderStateShard.TransparencyStateShard(
                            "rvp_panel_bg_transparency",
                            () -> {
                                RenderSystem.enableBlend();
                                RenderSystem.defaultBlendFunc();
                            },
                            RenderSystem::disableBlend))
                    .setCullState(new RenderStateShard.CullStateShard(false))
                    // 519 = GL_ALWAYS：底色永远通过深度测试，盖住之前绘制的内容
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("rvp_panel_bg_always", 519))
                    .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, true))
                    .createCompositeState(true));

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 爆反动画时序检查节流日志时间戳 */
    private static long lastEraBurnCheck = Long.MIN_VALUE;
    /** 渲染入口节流日志时间戳 */
    private static long lastEntryLog = Long.MIN_VALUE;
    /** 实际绘制节流日志时间戳 */
    private static long lastDrawLog = Long.MIN_VALUE;

    /**
     * IGuiOverlay 渲染入口：与雷达/RWR 同一条注册通道（{@link RVP_OverlayRegistry} 注册于
     * RegisterGuiOverlaysEvent，已验证稳定触发）。Forge 总线的 {@code RenderGuiEvent.Post} /
     * {@code ScreenEvent.Render.Post} 在当前环境不派发，故不再依赖事件，改用 overlay 直调。
     */
    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
                       int screenWidth, int screenHeight) {
        long now = System.currentTimeMillis();
        if (now - lastEntryLog > 2000) {
            lastEntryLog = now;
            LOGGER.info("[RVP-HitUI] OVERLAY render fired: active={} events={} screen={}",
                    RVP_ClientHitIndicatorState.isActive(),
                    RVP_ClientHitIndicatorState.getEvents().size(),
                    Minecraft.getInstance().screen != null
                            ? Minecraft.getInstance().screen.getClass().getSimpleName() : "null");
        }
        renderGui(guiGraphics, partialTick);
    }

    private static void renderGui(GuiGraphics guiGraphics, float partialTick) {
        if (!RVP_ClientHitIndicatorState.isActive()) {
            return;
        }
        // RVP 命中提示活动期间持续压制本体命中提示（兜底）：
        // 本体 ServerHitVehicleEvent 由 DamageSystem 在服务端发送，若因顺序/延迟晚于 RVP 包
        // 到达客户端，本体事件源会残留导致“退化成原版命中”，这里每帧清空一次。
        org.ywzj.vehicle.client.gui.VehicleHitIndicatorOverlay.events.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEraBurnCheck > 1000) {
            lastEraBurnCheck = now;
            LOGGER.info("[RVP-HitUI] render active: entity={} events={}",
                    RVP_ClientHitIndicatorState.getEntityId(),
                    RVP_ClientHitIndicatorState.getEvents().size());
        }
        // 先把所有延迟缓冲的 HUD 内容（雷达/RWR 的 XX°、XX m 文字等）落进帧缓冲，
        // 再绘制展板：展板随后绘制时自然盖在它们之上。否则这些文字会因 MultiBufferSource
        // 按 RenderType 分桶、文字缓冲排在填充缓冲之后，而画在展板底色之上。
        guiGraphics.flush();
        renderPanel(guiGraphics, mc, partialTick);
    }

    private static void renderPanel(GuiGraphics gg, Minecraft mc, float partialTick) {
        Entity entity = resolveEntity(mc);
        if (entity == null) {
            return;
        }
        long dbgNow = System.currentTimeMillis();
        if (dbgNow - lastDrawLog > 2000) {
            lastDrawLog = dbgNow;
            LOGGER.info("[RVP-HitUI] DRAW panel: entity={} type={} events={}",
                    entity.getId(),
                    entity.getType().toString(),
                    RVP_ClientHitIndicatorState.getEvents().size());
        }

        // 渲染基准临时对齐到命中时刻位置：超视距克隆实体/高速移动目标的当前客户端位置与
        // 命中时刻位置有偏差，渲染期间平移到 atHit，避免命中特效相对模型偏移出展示框。
        Vec3 savedEntityPos = null;
        List<RVP_ClientHitIndicatorState.HitEvent> evts = RVP_ClientHitIndicatorState.getEvents();
        if (!evts.isEmpty()) {
            Vec3 atHit = evts.get(0).entityPosAtHit;
            if (atHit != null && atHit.distanceToSqr(entity.position()) > 1.0E-6) {
                savedEntityPos = entity.position();
                entity.setPos(atHit);
            }
        }
        try {
        var window = mc.getWindow();
        double guiScale = window.getGuiScale();
        int guiW = window.getGuiScaledWidth();
        int guiH = window.getGuiScaledHeight();

        // 展板尺寸按屏幕实际分辨率（像素）固定比例计算，再除以 guiScale 转成 GUI 坐标：
        // 无论“界面尺寸”怎么缩放，展板/模型的物理大小都不变，只随分辨率自适应。
        int panelW = Math.max(60, (int) Math.round(window.getScreenWidth() * PANEL_W_FRAC / guiScale));
        int panelH = Math.max(40, (int) Math.round(window.getScreenHeight() * PANEL_H_FRAC / guiScale));
        int margin = Math.max(2, (int) (MARGIN_PX / guiScale));
        int x0 = guiW - margin - panelW;
        int y0 = margin;
        int x1 = x0 + panelW;
        int y1 = y0 + panelH;

        // 展示框裁剪：框内正常渲染，任何超出面板的内容（模型、弹体、曳光、红线）一律裁掉。
        // GuiGraphics.enableScissor 会把 GUI 坐标换算成物理像素，3D 模型缓冲提交时同样生效。
        gg.enableScissor(x0, y0, x1, y1);
        try {
        // 展板：灰色不透明底 + 黑边框（GL_ALWAYS 填充，盖住先前绘制的 HUD 文字）
        fillBg(gg, x0, y0, x1, y1, COLOR_BACKGROUND);
        int border = Math.max(1, (int) (BORDER_PX / guiScale));
        fillBg(gg, x0, y0, x1, y0 + border, COLOR_BORDER);
        fillBg(gg, x0, y1 - border, x1, y1, COLOR_BORDER);
        fillBg(gg, x0, y0, x0 + border, y1, COLOR_BORDER);
        fillBg(gg, x1 - border, y0, x1, y1, COLOR_BORDER);
        // 展板底先单独落帧缓冲：GL_ALWAYS 底色盖住先前已绘制的 HUD 文字（雷达/RWR 的 XX°、XX m 等）。
        // 不能等帧末统一 flush —— MultiBufferSource 各 RenderType 分桶刷新顺序不保证，
        // 底色/文字/模型若同一批刷新，模型可能先于底色落帧而被 GL_ALWAYS 底色盖住（模型消失）。
        gg.flush();

        // 文字区（展板顶部）：按固定像素字号绘制，物理大小不受界面尺寸影响
        Font font = mc.font;
        // 最大生命：载具用 ContainerCraft 自带的 getMaxHealth（非 LivingEntity），生物用 LivingEntity 的
        float maxHealth = entity instanceof AbstractVehicle vehicle ? vehicle.getMaxHealth()
                : entity instanceof LivingEntity living ? living.getMaxHealth() : 0f;
        float damagePercent = maxHealth > 0
                ? RVP_ClientHitIndicatorState.getDamage() / maxHealth * 100f : 0f;
        int damageColor = damagePercent >= 50f ? COLOR_DAMAGE_CRITICAL
                : damagePercent >= 25f ? COLOR_DAMAGE_HURT : COLOR_DAMAGE_HIT;
        String bone = RVP_ClientHitIndicatorState.getBoneDisplayName();
        // 文案：直击命中显示“命中XX骨骼 -x%”；非直击爆炸（骨骼名为空）显示“爆炸 -x%”
        String title = bone.isEmpty()
                ? String.format("爆炸 -%.1f%%", damagePercent)
                : String.format("命中%s -%.1f%%", bone, damagePercent);
        float textScale = (float) (TEXT_HEIGHT_PX / 9.0 / guiScale);
        gg.pose().pushPose();
        // 文案：展板顶部水平居中（参考本体：文案在模型上方居中）
        gg.pose().translate(x0 + (float) panelW / 2, y0 + (int) (3 / guiScale), 0);
        gg.pose().scale(textScale, textScale, 1.0F);
        int lineY = 0;
        gg.drawCenteredString(font, Component.literal(title), 0, lineY, damageColor);
        gg.pose().popPose();
        // 标题文字单独落帧缓冲：盖在展板底色之上（层级：雷达文字 < 展板底 < 标题文字 < 模型）
        gg.flush();
        int textBlockGui = (int) Math.ceil((lineY + 9) * textScale);

        // 模型区：文字区下方，锚点居中
        int modelTop = y0 + (int) (3 / guiScale) + textBlockGui + (int) (4 / guiScale);
        int modelAreaW = Math.max(8, panelW - (int) (8 / guiScale) * 2);
        int modelAreaH = Math.max(8, y1 - modelTop - (int) (4 / guiScale));
        double modelX = x0 + (int) (8 / guiScale) + (double) modelAreaW / 2;
        double modelY = modelTop + (double) modelAreaH / 2;

        Vec3 viewVec = RVP_ClientHitIndicatorState.getHitPosition().subtract(entity.position());
        // yaw 跟随受击水平方向（绕 Y 旋转不改变模型投影大小）
        float yaw = (float) Math.toDegrees(Math.atan2(viewVec.x, viewVec.z));
        // 展示俯仰固定为轻微俯视（约 5°），不随命中点高度变化：
        // 打任何部位都是同一正常姿态（接近平视、略看到车顶），避免动态 pitch 造成的夸张俯视/仰视。
        float pitch = (float) DISPLAY_PITCH;

        // 恒定缩放：与受击角度、部件（炮塔/枪管）旋转都无关 —— 按模型实际包围盒适配面板，
        // 细长载具（长度长但宽高小）与短粗载具（长度短但宽高占比大）统一完整显示，
        // 不再出现短粗载具被放大到超框的情况。水平取长/宽较大半轴、垂直取全高，各留 30% 边距。
        double[] ext = modelExtents(entity, partialTick);
        double halfH = Math.max(ext[0], ext[2]);          // 水平方向较大半轴（长或宽）
        double fullV = Math.max(ext[1] * 2, 0.5);         // 垂直全高（含保底）
        if (halfH <= 0) {
            halfH = 0.25;
        }
        float scaleW = (float) (modelAreaW / (halfH * 2 * 1.3));
        float scaleH = (float) (modelAreaH / (fullV * 1.3));
        float scale = Math.max(0.1f, Math.min(scaleW, scaleH));

        // 几何中心（ext[3..5]）直接把它拉到原点再旋转/缩放即居中于面板中心。
        // modelExtents 已用与位置无关的静态几何（offset + 当前朝向）计算，任何距离、
        // 任何实体类型（本地 tick / 广播克隆）都不会被滞后的世界坐标污染。
        double cx = ext[3], cy = ext[4], cz = ext[5];

        gg.pose().pushPose();
        {
            gg.pose().translate(modelX, modelY, (float) GUI_MODEL_Z);
            gg.pose().rotateAround(Axis.XP.rotationDegrees(pitch + 180), 0, 0, 0);
            gg.pose().rotateAround(Axis.YP.rotationDegrees(yaw), 0, 0, 0);
            gg.pose().mulPoseMatrix((new Matrix4f()).scaling(scale, scale, -scale));
            gg.pose().translate(-cx, -cy, -cz);

            Lighting.setupForEntityInInventory();
            EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            dispatcher.setRenderShadow(false);
            Entity finalEntity = entity;
            RenderSystem.runAsFancy(() -> {
                try {
                    // 展板"状态回放延迟"：渲染期间动画脚本的爆反状态查询回到 0.5 秒前，
                    // 刚被摧毁的爆反骨块在展板里晚 0.5 秒消失；渲染结束立即清除，世界渲染不受影响。
                    RVP_ClientBoneModuleState.setRenderDelay(RENDER_DELAY_MS);
                    try {
                        dispatcher.render(finalEntity, 0, 0, 0, 0, 1.0F,
                                gg.pose(), gg.bufferSource(), 15728880);
                    } finally {
                        RVP_ClientBoneModuleState.clearRenderDelay();
                    }
                    renderHitAnimation(gg, finalEntity, scale,
                            RVP_ClientHitIndicatorState.getEvents());
                } catch (Exception e) {
                    LOGGER.error("[RVP-HitUI] model render error entity={} err={}", finalEntity, e.toString());
                }
            });
            gg.flush();
            dispatcher.setRenderShadow(true);
        }
        gg.pose().popPose();
        } finally {
            gg.disableScissor();
        }
        } finally {
            if (savedEntityPos != null) {
                entity.setPos(savedEntityPos);
            }
        }
        Lighting.setupFor3DItems();
    }

    private static Entity resolveEntity(Minecraft mc) {
        int entityId = RVP_ClientHitIndicatorState.getEntityId();
        Entity entity = mc.level.getEntity(entityId);
        if (entity != null) {
            return entity;
        }
        if (LocalVehiclePlayer.instance == null) {
            return null;
        }
        LocalVehiclePlayer.ServerEntity serverEntity = LocalVehiclePlayer.instance.serverEntities.get(entityId);
        return serverEntity == null ? null : serverEntity.entity;
    }

    /**
     * 模型包围：半宽/半高/半深 + 几何中心（方块）。
     * <p>一律使用结构块静态局部坐标 {@code offset()}（与实体位置无关），再绕
     * {@code centerOffset} 应用载具当前朝向 {@code rotYXZ}，得到与命中动画同一基准的
     * 模型空间点 —— 不再依赖 {@code cube.position}（每 tick 由 update() 刷新的世界坐标）：
     * 广播克隆体（超视距）不参与 tick，其 {@code position()} 是创建时的陈旧世界坐标，
     * 用它算居中会把滞后的世界位置污染进平移量（实测数百方块），把模型推出展示框
     * （超距不渲染的根因）。任何距离 / 任何实体类型（本地 tick / 广播克隆）都稳定。</p>
     */
    private static double[] modelExtents(Entity entity, float partialTick) {
        if (entity instanceof AbstractVehicle vehicle) {
            List<VehicleCubeOBB> cubes = new ArrayList<>(vehicle.getVehicleCubeOBBs());
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                cubes.addAll(partUnit.getPartCubeOBBs());
            }
            if (!cubes.isEmpty()) {
                Vec3 centerOffset = vehicle.centerOffset == null ? Vec3.ZERO : vehicle.centerOffset;
                Quaternionf rot = vehicle.rotYXZ(partialTick);
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                for (VehicleCubeOBB cube : cubes) {
                    Vec3 off = cube.offset() == null ? Vec3.ZERO : cube.offset();
                    Vector3f rel = off.subtract(centerOffset).toVector3f();
                    rot.transform(rel);
                    double px = centerOffset.x + rel.x;
                    double py = centerOffset.y + rel.y;
                    double pz = centerOffset.z + rel.z;
                    double hw = cube.width / 2.0, hh = cube.height / 2.0, hd = cube.depth / 2.0;
                    minX = Math.min(minX, px - hw);
                    maxX = Math.max(maxX, px + hw);
                    minY = Math.min(minY, py - hh);
                    maxY = Math.max(maxY, py + hh);
                    minZ = Math.min(minZ, pz - hd);
                    maxZ = Math.max(maxZ, pz + hd);
                }
                double cx = (minX + maxX) / 2.0, cy = (minY + maxY) / 2.0, cz = (minZ + maxZ) / 2.0;
                return new double[]{
                        Math.max(maxX - cx, cx - minX), Math.max(maxY - cy, cy - minY),
                        Math.max(maxZ - cz, cz - minZ), cx, cy, cz};
            }
            // 结构块缺失时退化为结构参考长度 + AABB 兜底
            AABB bb = entity.getBoundingBox();
            double length = vehicle.getStructureLength();
            if (length > 0.01) {
                Vec3 c = bb.getCenter().subtract(entity.position());
                return new double[]{length / 2.0, Math.max((bb.maxY - bb.minY) / 2.0, length * 0.12), length / 2.0,
                        c.x, c.y, c.z};
            }
        }
        // 非载具实体（生物等）：用 AABB 半尺寸
        AABB bb = entity.getBoundingBox();
        Vec3 center = bb.getCenter().subtract(entity.position());
        return new double[]{(bb.maxX - bb.minX) / 2.0,
                Math.max((bb.maxY - bb.minY) / 2.0, 0.1),
                (bb.maxZ - bb.minZ) / 2.0, center.x, center.y, center.z};
    }

    /**
     * 命中过程动画（每帧在 3D 变换链内调用）：
     * <p>命中点与来袭方向都用“相对实体位置”的模型空间坐标（与模型几何同一基准），
     * 直接穿过当前面板变换矩阵 —— 与本体命中红线完全一致，任何入射角度方向都正确。
     * 注意：不能在此处再叠加载具自身旋转（rotYXZ），该旋转由 VehicleRender 渲染模型时
     * 在内部 push/pop 应用，这里的矩阵里并不包含它。</p>
     * <p>直击事件（骨骼名非空）与爆炸事件（骨骼名为空）走不同动画：
     * <ol>
     *   <li>直击：弹体/曳光飞向命中点 → 小爆点 → 本体式 3D 红线。</li>
     *   <li>爆炸：弹体/曳光飞向离车体一定距离的爆炸中心（同一载具已有直击时不重复渲染弹体）
     *       → 爆炸中心大扩散红圈 → 破片飞溅到车体。</li>
     * </ol></p>
     */
    private static void renderHitAnimation(GuiGraphics gg, Entity entity, float scale,
                                          List<RVP_ClientHitIndicatorState.HitEvent> events) {
        PoseStack pose = gg.pose();
        Matrix4f matrix = pose.last().pose();
        long now = System.currentTimeMillis();
        // 该载具是否有直击事件：有直击时爆炸事件不再渲染弹体模型（直击+爆炸只渲染直击那一次）
        boolean hasDirectHit = false;
        for (RVP_ClientHitIndicatorState.HitEvent e : events) {
            if (!e.boneDisplayName.isEmpty()) {
                hasDirectHit = true;
                break;
            }
        }
        for (RVP_ClientHitIndicatorState.HitEvent event : events) {
            long elapsed = now - event.hitTime;
            if (elapsed < 0) {
                continue;
            }
            // 命中点/爆炸中心（模型空间 = 相对实体位置，与模型几何同一基准）
            Vec3 hitModel = event.hitPosition.subtract(entity.position());
            Vec3 incoming = event.incomingDir;
            if (event.boneDisplayName.isEmpty()) {
                renderExplosionAnimation(gg, matrix, event, hitModel, incoming, elapsed, scale, hasDirectHit);
            } else {
                renderDirectAnimation(gg, matrix, event, hitModel, incoming, elapsed, scale);
            }
        }
    }

    /**
     * 该次命中是否击毁了爆反：任一被摧毁爆反骨块的销毁时刻与命中时刻相近（同一服务器 tick
     * 发出的状态包与命中包到达客户端时间接近）即视为本次命中击毁爆反。
     */
    private static boolean destroyedEraAtHit(int entityId, long hitTime) {
        for (String boneName : RVP_ClientBoneModuleState.getInactiveEraBones(entityId)) {
            long destroyTime = RVP_ClientBoneModuleState.getEraDestroyTime(entityId, boneName);
            if (destroyTime >= 0 && Math.abs(destroyTime - hitTime) <= 1500L) {
                return true;
            }
        }
        return false;
    }

    /** 直击命中动画：飞行段（弹体/曳光飞向命中点）→ 小爆点 → 红线 */
    private static void renderDirectAnimation(GuiGraphics gg, Matrix4f matrix,
                                              RVP_ClientHitIndicatorState.HitEvent event,
                                              Vec3 hitModel, Vec3 incoming, long elapsed, float scale) {
        if (elapsed < RVP_ClientHitIndicatorState.FLY_MS) {
            // 飞行段：弹体从命中点沿来袭方向 4 米外飞向命中点
            float t = (float) elapsed / (float) RVP_ClientHitIndicatorState.FLY_MS;
            Vec3 curModel = hitModel.add(incoming.scale(RVP_ClientHitIndicatorState.START_DIST * (1 - t)));
            if (hasProjectileModel(event)) {
                renderProjectileModel(gg, curModel, incoming);
            } else {
                renderTracer(gg, matrix, curModel, incoming);
            }
        } else if (elapsed < RVP_ClientHitIndicatorState.FLY_MS + RVP_ClientHitIndicatorState.BURST_MS) {
            // 爆点段：命中点小圆球颜色/半径随时间变化（投影到屏幕的实心圆盘）
            float t = (float) (elapsed - RVP_ClientHitIndicatorState.FLY_MS)
                    / (float) RVP_ClientHitIndicatorState.BURST_MS;
            // 击毁爆反的命中：命中球比平时更大且发黑（模拟爆反爆炸的黑红冲击）
            boolean eraHit = destroyedEraAtHit(event.entityId, event.hitTime);
            float sizeMul = eraHit ? 2.0f : 1f;
            float dark = eraHit ? 0.3f : 1f;
            float radius;
            float cr, cg, cb;
            float r0 = 0.2f;   // 爆点圆盘半径（米）：×scale 约 2.4px，实心可见又不挡模型
            if (t < 0.3f) {
                radius = r0 * sizeMul;
                cr = 1f * dark; cg = 0.25f * dark; cb = 0.1f;                          // 亮红
            } else if (t < 0.5f) {
                float k = (t - 0.3f) / 0.2f;
                radius = r0 * sizeMul * (1 + 0.5f * k);                            // 扩张至 1.5 倍
                cr = 1f * dark; cg = (0.25f + 0.5f * k) * dark; cb = (0.1f + 0.05f * k) * dark; // 红 → 亮橙金
            } else if (t < 0.7f) {
                float k = (t - 0.5f) / 0.2f;
                radius = r0 * sizeMul * (1.5f - 0.5f * k);                        // 缩回原大小
                cr = (1f - 0.15f * k) * dark; cg = (0.75f - 0.5f * k) * dark; cb = (0.15f - 0.05f * k) * dark; // 橙金 → 回红
            } else {
                float k = (t - 0.7f) / 0.3f;
                radius = r0 * sizeMul * (1 - k);                                  // 淡出
                cr = (0.85f + 0.15f * k) * dark; cg = (0.25f - 0.15f * k) * dark; cb = 0.1f; // 转亮淡红后整体变淡
            }
            fillCircle(gg, matrix, hitModel, radius * scale, cr, cg, cb, 0.9f * (1 - 0.5f * t));
        } else {
            // 红线段：本体式 3D 红线，从命中点沿来袭方向延伸 3 米
            renderRedLine(gg, hitModel, hitModel.add(incoming.scale(3)));
            // 直击 HE 等带爆炸的弹药：命中点处同样扩散爆炸圈（随爆炸半径扩大，最多 5 倍）
            if (event.explosionRadius > 0f) {
                long fragStart = RVP_ClientHitIndicatorState.FLY_MS + RVP_ClientHitIndicatorState.BURST_MS;
                long fragEnd = fragStart + RVP_ClientHitIndicatorState.FRAG_MS;
                if (elapsed >= fragStart && elapsed < fragEnd) {
                    renderExplosionDiffusionRing(gg, matrix, hitModel, elapsed - fragStart, scale,
                            event.explosionRadius, DIRECT_BURST_END_RADIUS);
                }
            }
        }
    }

    /**
     * 爆炸命中动画（非直击）：
     * <ol>
     *   <li>飞行段：弹体/曳光飞向爆炸中心（爆炸中心离车体一定距离，不在车体上）；
     *       同一载具已有直击事件时不重复渲染弹体模型。</li>
     *   <li>爆点段：爆炸中心处较大的扩散红圈（明显大于直击爆点），迅速扩张并淡出。</li>
     *   <li>破片段：从爆炸中心飞溅几条破片到车体方向，随推进淡出。</li>
     * </ol>
     */
    private static void renderExplosionAnimation(GuiGraphics gg, Matrix4f matrix,
                                                 RVP_ClientHitIndicatorState.HitEvent event,
                                                 Vec3 boomCenter, Vec3 incoming, long elapsed, float scale,
                                                 boolean hasDirectHit) {
        long flyEnd = RVP_ClientHitIndicatorState.FLY_MS;
        long burstEnd = flyEnd + RVP_ClientHitIndicatorState.BURST_MS;
        if (elapsed < flyEnd) {
            // 飞行段：弹体从爆炸中心沿来袭方向 4 米外飞向爆炸中心
            if (!hasDirectHit) {
                float t = (float) elapsed / (float) flyEnd;
                Vec3 cur = boomCenter.add(incoming.scale(RVP_ClientHitIndicatorState.START_DIST * (1 - t)));
                if (hasProjectileModel(event)) {
                    renderProjectileModel(gg, cur, incoming);
                } else {
                    renderTracer(gg, matrix, cur, incoming);
                }
            }
        } else if (elapsed < burstEnd) {
            // 爆点段：爆炸中心较大的扩散红圈
            float t = (float) (elapsed - flyEnd) / (float) RVP_ClientHitIndicatorState.BURST_MS;
            float r0 = 0.7f;                           // 爆炸红圈基础半径（米）：×scale 约 8px，扩张到约 18px，醒目
            float radius = r0 * (0.5f + 1.6f * t);     // 由小迅速扩张
            float cr = 1f, cg = 0.85f - 0.5f * t, cb = 0.15f - 0.1f * t; // 红 → 亮橙 → 变淡
            fillCircle(gg, matrix, boomCenter, radius * scale, cr, cg, cb, 0.95f * (1f - t));
        } else {
            // 破片段：从爆炸中心飞溅破片到车体
            long fragEnd = burstEnd + RVP_ClientHitIndicatorState.FRAG_MS;
            if (elapsed < fragEnd) {
                renderFragments(gg, matrix, boomCenter, incoming, elapsed - burstEnd);
                // 爆炸扩散圈：基础红圈结束后继续向外扩散，最大为基础圈末半径的 5 倍（爆炸半径 20 封顶）
                renderExplosionDiffusionRing(gg, matrix, boomCenter, elapsed - burstEnd, scale,
                        event.explosionRadius, EXPLOSION_BURST_END_RADIUS);
            }
        }
    }

    /**
     * 爆炸扩散圈：基础爆炸圈结束后继续向外扩散并淡出（冲击波持续扩散效果）。
     * <p>最大半径 = 起点半径 ×5（爆炸半径 20 封顶，随爆炸半径线性插值）：
     * 爆炸范围越大扩散圈越大；{@code explosionRadius <= 0}（无爆炸语义）时跳过。</p>
     */
    private static void renderExplosionDiffusionRing(GuiGraphics gg, Matrix4f matrix, Vec3 center,
                                                     long diffuseElapsed, float scale,
                                                     float explosionRadius, float startRadius) {
        if (explosionRadius <= 0f) {
            return;
        }
        float t = (float) diffuseElapsed / (float) RVP_ClientHitIndicatorState.FRAG_MS;
        if (t < 0f || t > 1f) {
            return;
        }
        // 爆炸范围 0 → 20 线性放大，达到 20 时扩散圈最大（起点半径 ×5）
        float diffFactor = Math.min(1f, explosionRadius / EXPLOSION_RING_MAX_RADIUS);
        float maxRadius = startRadius * (1f + (EXPLOSION_RING_MAX_MULTIPLIER - 1f) * diffFactor);
        // 缓出扩张：先快后慢，视觉上像冲击波持续扩散
        float ease = 1f - (1f - t) * (1f - t);
        float radius = startRadius + (maxRadius - startRadius) * ease;
        float cr = 1f, cg = 0.55f - 0.25f * t, cb = 0.1f;
        float alpha = 0.7f * (1f - t);
        fillCircle(gg, matrix, center, radius * scale, cr, cg, cb, alpha);
    }

    /**
     * 破片飞溅：5 条从爆炸中心射向车体中心方向（带小偏角）的短线，随时间推进并淡出。
     * 车体中心近似模型空间原点；破片射程按“爆炸中心到车体距离 ×1.1”计算，最终落在车体附近。
     */
    private static void renderFragments(GuiGraphics gg, Matrix4f matrix, Vec3 boomCenter, Vec3 incoming,
                                        long fragElapsed) {
        float t = (float) fragElapsed / (float) RVP_ClientHitIndicatorState.FRAG_MS;
        if (t < 0f || t > 1f) {
            return;
        }
        // 指向车体中心（模型空间原点）的方向 = 来袭方向的相反方向
        Vec3 toCar = incoming.scale(-1);
        if (toCar.lengthSqr() < 1.0E-6) {
            toCar = new Vec3(0, 0, 1);
        }
        Vec3 dirBase = toCar.normalize();
        float range = (float) Math.max(boomCenter.length() * 1.1, 0.6);
        // 5 条破片：水平/垂直各带小偏角，模拟飞溅散布
        float[] offYaw = {-0.26f, 0.26f, -0.10f, 0.10f, 0.0f};
        float[] offPitch = {0.12f, -0.14f, 0.32f, -0.34f, 0.02f};
        VertexConsumer vc = gg.bufferSource().getBuffer(RenderType.lines());
        for (int i = 0; i < 5; i++) {
            Vector3f d = new Vector3f((float) dirBase.x, (float) dirBase.y, (float) dirBase.z);
            d.rotate(new Quaternionf().rotateY(offYaw[i]).rotateX(offPitch[i]));
            Vec3 fragPos = boomCenter.add(new Vec3(d.x, d.y, d.z).scale(t * range));
            // 飞行轨迹线（爆炸中心 → 当前破片位置，暗橙）
            vc.vertex(matrix, (float) boomCenter.x, (float) boomCenter.y, (float) boomCenter.z)
                    .color(1.0f, 0.55f, 0.15f, 0.55f).normal(0, 1, -100).endVertex();
            vc.vertex(matrix, (float) fragPos.x, (float) fragPos.y, (float) fragPos.z)
                    .color(1.0f, 0.55f, 0.15f, 0.55f).normal(0, 1, -100).endVertex();
            // 破片本体（短亮线）
            vc.vertex(matrix, (float) fragPos.x, (float) fragPos.y, (float) fragPos.z)
                    .color(1.0f, 0.75f, 0.25f, 1f).normal(0, 1, -100).endVertex();
            vc.vertex(matrix, (float) (fragPos.x + d.x * 0.45f), (float) (fragPos.y + d.y * 0.45f),
                    (float) (fragPos.z + d.z * 0.45f))
                    .color(1.0f, 0.75f, 0.25f, 1f).normal(0, 1, -100).endVertex();
        }
    }

    /** 红色弹道线（本体式）：3D 空间直接画，穿过面板变换矩阵，方向与模型姿态一致 */
    private static void renderRedLine(GuiGraphics gg, Vec3 start, Vec3 end) {
        PoseStack pose = gg.pose();
        Matrix4f matrix = pose.last().pose();
        VertexConsumer vc = gg.bufferSource().getBuffer(RenderType.lines());
        float off = 0.01f;
        for (int i = -1; i <= 2; i++) {
            for (int j = -1; j <= 2; j++) {
                vc.vertex(matrix, (float) start.x + off * i, (float) start.y + off * j, (float) start.z)
                        .color(1.0f, 0.0f, 0.0f, 1.0f).normal(0, 1, -100).endVertex();
                vc.vertex(matrix, (float) end.x + off * i, (float) end.y + off * j, (float) end.z)
                        .color(1.0f, 0.0f, 0.0f, 1.0f).normal(0, 1, -100).endVertex();
            }
        }
    }

    /** 曳光：暖黄拖尾 + 弹头白亮段（沿来袭方向，杜绝竖直十字线） */
    private static void renderTracer(GuiGraphics gg, Matrix4f matrix, Vec3 head, Vec3 dir) {
        VertexConsumer vc = gg.bufferSource().getBuffer(RenderType.lines());
        Vec3 tail = head.add(dir.scale(1.5));
        Vec3 tip = head.subtract(dir.scale(0.3));
        // 拖尾（暖黄）：向来袭方向（弹药来处）延伸
        vc.vertex(matrix, (float) head.x, (float) head.y, (float) head.z)
                .color(1.0f, 0.85f, 0.3f, 1.0f).normal(0, 1, -100).endVertex();
        vc.vertex(matrix, (float) tail.x, (float) tail.y, (float) tail.z)
                .color(1.0f, 0.85f, 0.3f, 1.0f).normal(0, 1, -100).endVertex();
        // 弹头白亮段：沿飞行方向（来袭方向的反向）前探
        vc.vertex(matrix, (float) head.x, (float) head.y, (float) head.z)
                .color(1.0f, 1.0f, 1.0f, 1.0f).normal(0, 1, -100).endVertex();
        vc.vertex(matrix, (float) tip.x, (float) tip.y, (float) tip.z)
                .color(1.0f, 1.0f, 1.0f, 1.0f).normal(0, 1, -100).endVertex();
    }

    /** 展板底色/边框：用 {@link #PANEL_BG}（GL_ALWAYS）绘制实心四边形，必盖住先前内容 */
    private static void fillBg(GuiGraphics gg, int x0, int y0, int x1, int y1, int color) {
        Matrix4f m = gg.pose().last().pose();
        VertexConsumer vc = gg.bufferSource().getBuffer(PANEL_BG);
        float a = (float) (color >> 24 & 255) / 255.0F;
        float r = (float) (color >> 16 & 255) / 255.0F;
        float g = (float) (color >> 8 & 255) / 255.0F;
        float b = (float) (color & 255) / 255.0F;
        vc.vertex(m, (float) x0, (float) y0, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(m, (float) x0, (float) y1, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(m, (float) x1, (float) y1, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(m, (float) x1, (float) y0, 0.0F).color(r, g, b, a).endVertex();
    }

    /**
     * GUI 空间实心圆盘（永远面向屏幕）：把模型坐标经当前矩阵投影到 GUI 坐标，再画三角扇圆。
     * 半径单位为像素（调用方已用 scale 换算）。
     */
    private static void fillCircle(GuiGraphics gg, Matrix4f matrix, Vec3 modelPos, float radiusPx,
                                   float cr, float cg, float cb, float alpha) {
        if (radiusPx <= 0.01f) {
            return;
        }
        Vector4f v = new Vector4f((float) modelPos.x, (float) modelPos.y, (float) modelPos.z, 1.0f);
        matrix.transform(v);
        if (Math.abs(v.w) < 1.0E-6) {
            return;
        }
        float cx = v.x / v.w;
        float cy = v.y / v.w;
        int seg = 20;
        VertexConsumer vc = gg.bufferSource().getBuffer(HIT_CIRCLE);
        Matrix4f identity = new Matrix4f();
        // TRIANGLE_FAN：中心 + 圆周顶点序列
        vc.vertex(identity, cx, cy, 0).color(cr, cg, cb, alpha).endVertex();
        for (int i = 0; i <= seg; i++) {
            float ang = (float) (i * 2 * Math.PI / seg);
            vc.vertex(identity, cx + radiusPx * (float) Math.cos(ang), cy + radiusPx * (float) Math.sin(ang), 0)
                    .color(cr, cg, cb, alpha).endVertex();
        }
    }

    /** 渲染来袭弹体模型（有模型的弹药，如导弹/火箭）：模型 +Z 轴对齐来袭方向 */
    private static void renderProjectileModel(GuiGraphics gg, Vec3 pos, Vec3 dir) {
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(pos.x, pos.y, pos.z);
        Vector3f dirV = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        float len = dirV.length();
        if (len > 1.0E-4) {
            dirV.normalize();
            // 链内 z 是镜像（scale z=-1），取反使模型前端朝飞行方向
            pose.mulPose(new Quaternionf().rotateTo(new Vector3f(0, 0, -1), dirV));
            // 弹头一般不在模型原点：旋转后沿模型局部 +Z（= 飞行反方向）后拉弹头前点距离，
            // 让弹头指向命中点，避免命中瞬间弹药模型整体"穿模"进命中点/载具。
            pose.translate(0, 0, cachedBackOffset);
        }
        VehicleBedrockModel model = projectileModel(eventCacheWeaponId);
        ResourceLocation tex = projectileTexture(eventCacheWeaponId);
        if (model != null && tex != null) {
            model.renderToBuffer(pose, gg.bufferSource(), tex, 15728880);
        }
        pose.popPose();
    }

    private static String eventCacheWeaponId;
    private static VehicleBedrockModel cachedModel;
    private static ResourceLocation cachedTexture;
    /** 当前缓存弹药的弹头前点后拉距离（米） */
    private static float cachedBackOffset = PROJECTILE_BACK_FALLBACK;

    private static VehicleBedrockModel projectileModel(String weaponId) {
        resolveProjectileCache(weaponId);
        return cachedModel;
    }

    private static ResourceLocation projectileTexture(String weaponId) {
        resolveProjectileCache(weaponId);
        return cachedTexture;
    }

    /** 查询武器 display：有模型则缓存模型+纹理+弹头后拉距离 */
    private static void resolveProjectileCache(String weaponId) {
        if (weaponId != null && weaponId.equals(eventCacheWeaponId)) {
            return;
        }
        eventCacheWeaponId = weaponId;
        cachedModel = null;
        cachedTexture = null;
        cachedBackOffset = PROJECTILE_BACK_FALLBACK;
        if (weaponId == null || weaponId.isEmpty()) {
            return;
        }
        try {
            Optional<BaseDisplay> display = ClientAssetsManager.INSTANCE
                    .getWeaponDisplay(ResourceLocation.parse(weaponId));
            if (display.isPresent()) {
                VehicleBedrockModel m = display.get().getModel();
                if (m != null && m.hasBakedModel()) {
                    cachedModel = m;
                    cachedTexture = display.get().getTexture();
                    cachedBackOffset = computeProjectileBackOffset(m);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 计算弹药模型弹头最前点距模型原点的距离（米）：模型局部 -Z 方向即飞行前向
     * （渲染时 rotateTo 把局部 -Z 对齐来袭方向），弹头即包围盒最小 Z；渲染时沿
     * 飞行反方向后拉该距离，让弹头贴住命中点。读作者声明的渲染包围盒
     * （{@link com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel#getRenderBoundingBox}），
     * 读不到/异常回退 {@link #PROJECTILE_BACK_FALLBACK}。
     */
    private static float computeProjectileBackOffset(VehicleBedrockModel model) {
        if (model == null || !model.hasBakedModel()) {
            return PROJECTILE_BACK_FALLBACK;
        }
        try {
            AABB bounds = model.getBakedModel().getRenderBoundingBox();
            double nose = -bounds.minZ;
            if (nose > 0 && nose < 128) {
                return (float) nose;
            }
        } catch (Exception ignored) {
        }
        return PROJECTILE_BACK_FALLBACK;
    }

    /** 该命中事件对应弹药是否有模型 */
    private static boolean hasProjectileModel(RVP_ClientHitIndicatorState.HitEvent event) {
        resolveProjectileCache(event.weaponId);
        return cachedModel != null && cachedTexture != null;
    }
}
