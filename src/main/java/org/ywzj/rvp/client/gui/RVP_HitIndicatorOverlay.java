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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
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
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_HitIndicatorOverlay {

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

    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_HitIndicatorOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!RVP_ClientHitIndicatorState.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.level == null) {
            return;
        }
        render(event.getGuiGraphics(), mc, event.getPartialTick());
    }

    private static void render(GuiGraphics gg, Minecraft mc, float partialTick) {
        Entity entity = resolveEntity(mc);
        if (entity == null) {
            return;
        }
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
        // 展板：灰色 80% 不透明度底 + 黑边框
        gg.fill(x0, y0, x1, y1, COLOR_BACKGROUND);
        int border = Math.max(1, (int) (BORDER_PX / guiScale));
        gg.fill(x0, y0, x1, y0 + border, COLOR_BORDER);
        gg.fill(x0, y1 - border, x1, y1, COLOR_BORDER);
        gg.fill(x0, y0, x0 + border, y1, COLOR_BORDER);
        gg.fill(x1 - border, y0, x1, y1, COLOR_BORDER);

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
        gg.pose().translate(x0 + (int) (6 / guiScale), y0 + (int) (3 / guiScale), 0);
        gg.pose().scale(textScale, textScale, 1.0F);
        int lineY = 0;
        gg.drawString(font, Component.literal(title), 0, lineY, damageColor, true);
        gg.pose().popPose();
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

        // 恒定缩放：与受击角度、部件（炮塔/枪管）旋转都无关 —— 采用本体思路，
        // 按结构参考长度固定缩放并留出余量，任何姿态都不会超出面板（修复“越界 + 忽大忽小”）。
        // 本体公式：scale = 8 / structureLength * 10，这里按模型区宽度换算并留 15% 边距。
        // 超出面板的内容（弹体飞行、曳光、红线）由上方 enableScissor 统一裁剪。
        double[] ext = modelExtents(entity);
        double len;
        if (entity instanceof AbstractVehicle vehicle) {
            len = Math.max(vehicle.getStructureLength(), 0.5);
        } else {
            AABB bb = entity.getBoundingBox();
            len = Math.max(Math.max(bb.getXsize(), bb.getZsize()), 0.5);
        }
        float scale = (float) (modelAreaW / (len * 1.15));

        // TODO 临时调试日志（定位后删除）
        if (entity.tickCount % 40 == 0) {
            RVP_ClientHitIndicatorState.HitEvent ev0 = RVP_ClientHitIndicatorState.getEvents().isEmpty()
                    ? null : RVP_ClientHitIndicatorState.getEvents().get(0);
            String inc = ev0 == null ? "n/a" : String.format("%.2f,%.2f,%.2f",
                    ev0.incomingDir.x, ev0.incomingDir.y, ev0.incomingDir.z);
            String view = String.format("%.2f,%.2f,%.2f", viewVec.x, viewVec.y, viewVec.z);
            LOGGER.info(String.format("[RVP-HitUI] entity=%d guiScale=%.1f gui=%dx%d area=%dx%d modelXY=(%.0f,%.0f) "
                    + "center=(%.2f,%.2f,%.2f) len=%.2f scale=%.3f pitch=%.1f yaw=%.1f incoming=%s viewVec=%s",
                    entity.getId(), guiScale, guiW, guiH, modelAreaW, modelAreaH, modelX, modelY,
                    ext[3], ext[4], ext[5], len, scale, pitch, yaw, inc, view));
        }

        // 几何中心（ext[3..5]）已由 position() 计算，天然含载具朝向与部件旋转，
        // 直接把它拉到原点再旋转/缩放即严格居中于面板中心。
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
                dispatcher.render(finalEntity, 0, 0, 0, 0, 1.0F,
                        gg.pose(), gg.bufferSource(), 15728880);
                renderHitAnimation(gg, finalEntity, scale,
                        RVP_ClientHitIndicatorState.getEvents());
            });
            gg.flush();
            dispatcher.setRenderShadow(true);
        }
        gg.pose().popPose();
        } finally {
            gg.disableScissor();
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
     * 模型包围：半宽/半高/半深 + 几何中心（相对实体位置，方块）。
     * <p>用每个结构块的 {@code position()}（世界坐标，由 update() 每 tick 计算，
     * 已包含载具自身朝向与部件旋转）减去实体位置，得到相对实体的当前姿态块位置。
     * 车体块与部件块同源同基准，几何中心天然跟随载具朝向，渲染时只需把该中心拉到原点即可严格居中。</p>
     */
    private static double[] modelExtents(Entity entity) {
        if (entity instanceof AbstractVehicle vehicle) {
            List<VehicleCubeOBB> cubes = new ArrayList<>(vehicle.getVehicleCubeOBBs());
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                cubes.addAll(partUnit.getPartCubeOBBs());
            }
            if (!cubes.isEmpty()) {
                Vec3 origin = vehicle.position();
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                for (VehicleCubeOBB cube : cubes) {
                    Vec3 pos = cube.position; // 字段：世界坐标（update() 每 tick 计算）
                    if (pos == null) {
                        pos = cube.offset(); // 兜底：静态模型坐标
                    }
                    Vec3 p = pos.subtract(origin);
                    double hw = cube.width / 2.0, hh = cube.height / 2.0, hd = cube.depth / 2.0;
                    minX = Math.min(minX, p.x - hw);
                    maxX = Math.max(maxX, p.x + hw);
                    minY = Math.min(minY, p.y - hh);
                    maxY = Math.max(maxY, p.y + hh);
                    minZ = Math.min(minZ, p.z - hd);
                    maxZ = Math.max(maxZ, p.z + hd);
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
     * <ol>
     *   <li>飞行段（0~1s）：来袭弹体从命中点沿来袭方向 4 米外飞向命中点。
     *       有模型渲染武器模型，无模型渲染暖黄曳光。</li>
     *   <li>爆点段（1s~1.8s）：命中点小圆球，红 → 暗红（扩张 1.5 倍）→ 红 → 淡出消失。</li>
     *   <li>红线段：爆点消失后才留下红色细线（本体式 3D 红线），整条事件在命中 3s 后清空。</li>
     * </ol>
     */
    private static void renderHitAnimation(GuiGraphics gg, Entity entity, float scale,
                                          List<RVP_ClientHitIndicatorState.HitEvent> events) {
        PoseStack pose = gg.pose();
        Matrix4f matrix = pose.last().pose();
        long now = System.currentTimeMillis();
        for (RVP_ClientHitIndicatorState.HitEvent event : events) {
            long elapsed = now - event.hitTime;
            if (elapsed < 0) {
                continue;
            }
            // 命中点（模型空间 = 相对实体位置，与模型几何同一基准，穿过矩阵即落在模型表面）
            Vec3 hitModel = event.hitPosition.subtract(entity.position());
            Vec3 incoming = event.incomingDir;
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
                float radius;
                float cr, cg, cb;
                float r0 = 0.2f;   // 爆点圆盘半径（米）：×scale 约 2.4px，实心可见又不挡模型
                if (t < 0.3f) {
                    radius = r0;
                    cr = 1f; cg = 0.25f; cb = 0.1f;                          // 亮红
                } else if (t < 0.5f) {
                    float k = (t - 0.3f) / 0.2f;
                    radius = r0 * (1 + 0.5f * k);                            // 扩张至 1.5 倍
                    cr = 1f; cg = 0.25f + 0.5f * k; cb = 0.1f + 0.05f * k;  // 红 → 亮橙金，变化明显
                } else if (t < 0.7f) {
                    float k = (t - 0.5f) / 0.2f;
                    radius = r0 * (1.5f - 0.5f * k);                        // 缩回原大小
                    cr = 1f - 0.15f * k; cg = 0.75f - 0.5f * k; cb = 0.15f - 0.05f * k; // 橙金 → 回红
                } else {
                    float k = (t - 0.7f) / 0.3f;
                    radius = r0 * (1 - k);                                  // 淡出
                    cr = 0.85f + 0.15f * k; cg = 0.25f - 0.15f * k; cb = 0.1f; // 转亮淡红后整体变淡
                }
                fillCircle(gg, matrix, hitModel, radius * scale, cr, cg, cb, 0.9f * (1 - 0.5f * t));
            } else {
                // 红线段：本体式 3D 红线，从命中点沿来袭方向延伸 3 米
                renderRedLine(gg, hitModel, hitModel.add(incoming.scale(3)));
            }
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

    private static VehicleBedrockModel projectileModel(String weaponId) {
        resolveProjectileCache(weaponId);
        return cachedModel;
    }

    private static ResourceLocation projectileTexture(String weaponId) {
        resolveProjectileCache(weaponId);
        return cachedTexture;
    }

    /** 查询武器 display：有模型则缓存模型+纹理 */
    private static void resolveProjectileCache(String weaponId) {
        if (weaponId != null && weaponId.equals(eventCacheWeaponId)) {
            return;
        }
        eventCacheWeaponId = weaponId;
        cachedModel = null;
        cachedTexture = null;
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
                }
                // TODO 临时调试日志（定位后删除）
                LOGGER.info("[RVP-HitUI-proj] weapon={} found=true model={} baked={} tex={}",
                        weaponId, m != null, m != null && m.hasBakedModel(), cachedTexture);
            } else {
                // TODO 临时调试日志（定位后删除）
                LOGGER.info("[RVP-HitUI-proj] weapon={} found=false", weaponId);
            }
        } catch (Exception e) {
            // TODO 临时调试日志（定位后删除）
            LOGGER.info("[RVP-HitUI-proj] weapon={} error={}", weaponId, e.toString());
        }
    }

    /** 该命中事件对应弹药是否有模型 */
    private static boolean hasProjectileModel(RVP_ClientHitIndicatorState.HitEvent event) {
        resolveProjectileCache(event.weaponId);
        return cachedModel != null && cachedTexture != null;
    }
}
