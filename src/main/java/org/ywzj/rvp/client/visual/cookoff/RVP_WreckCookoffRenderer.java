package org.ywzj.rvp.client.visual.cookoff;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import org.ywzj.rvp.RVP_MOD;

/**
 * 用已有殉燃横条绘制连续火柱；受重力下坠并在接触点按恢复系数反弹的 RVP 火星
 * （{@code RVP_WreckSparkParticle}）由控制器独立发射，与火柱共用同一条出口轴。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckCookoffRenderer implements ResourceManagerReloadListener {
    /** 客户端资源重载入口，仅由客户端注册类加载。 */
    public static final RVP_WreckCookoffRenderer INSTANCE = new RVP_WreckCookoffRenderer();
    /** 新导入的 16 帧有色横条，不替换任何已有贴图。 */
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(RVP_MOD.MOD_ID,
            "textures/wreck_cookoff/hot_core_16x64.png");
    /** 原版发光透明材质：无面剔除、深度测试开启、只写颜色，不污染后续深度。 */
    private static final RenderType TYPE = RenderType.entityTranslucentEmissive(TEXTURE);
    /** 独立批次，避免结束其它模组正在使用的世界实体缓冲。 */
    private static final MultiBufferSource.BufferSource BUFFER = MultiBufferSource.immediate(new BufferBuilder(32768));
    /** 缺资源仅在每次重载报告一次。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 当前资源包中火柱贴图是否可用；缺失时仍生成原版粒子。 */
    private static boolean textureReady;

    private RVP_WreckCookoffRenderer() {}

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        textureReady = manager.getResource(TEXTURE).isPresent();
        // 调用本项目实例清理，重载后重新从现有车辆结构建立挂点。
        RVP_WreckCookoffController.clear();
        if (!textureReady) LOGGER.warn("[RVP 殉燃] 缺少 {}，暂仅发射原版火焰粒子", TEXTURE);
    }

    /** 世界退出立即清理，不等下一次进入游戏。 */
    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            // 调用控制器释放旧世界引用，避免跨维度渲染。
            RVP_WreckCookoffController.clear();
        }
    }

    /** 在天气阶段之后绘制，和现有持续烟共存。 */
    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || !textureReady) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        // 调用控制器的全局火柱预算，避免密集残骸导致无限几何绘制。
        var columns = RVP_WreckCookoffController.renderColumns();
        if (columns.isEmpty()) return;
        Vec3 camera = event.getCamera().getPosition();
        // AFTER_WEATHER 的 RenderSystem ModelView 已包含事件 PoseStack 的视图变换。
        // CPU 顶点只减相机位置，禁止再乘事件矩阵，否则相机旋转/摇晃会被应用两次。
        double time = minecraft.level.getGameTime() + event.getPartialTick();
        VertexConsumer consumer = BUFFER.getBuffer(TYPE);
        for (var column : columns) {
            var current = column.currentPose();
            if (current == null) continue;
            var previous = column.previousPose() == null ? current : column.previousPose();
            Vec3 origin = previous.position().lerp(current.position(), event.getPartialTick());
            // 火柱方向与火星采样方向解耦：舱盖柱恒为世界向上，不随 30° 圆锥的随机火星方向歪掉；
            // 调用本项目方向插值入口，对前后两帧柱向做插值
            Vec3 direction = RVP_WreckCookoffGeometry.interpolateDirection(
                    column.previousColumnDirection(), column.columnDirection(), event.getPartialTick());
            // 调用本项目柱长设置和分段绘制，亮芯与外层错相以形成连续喷流。
            double lifecycleScale = Math.max(0, Math.min(1, column.lifecycleScale()));
            if (lifecycleScale <= 1.0e-4) continue;
            double length = column.length() * lifecycleScale
                    * (0.94 + 0.06 * Math.sin(time * 0.22 + column.phase()));
            int sections = origin.distanceToSqr(camera) > 128 * 128 ? 6 : 12;
            // 调用本项目相机相对坐标入口，先用双精度相减，再交给着色器执行唯一一次视图变换。
            drawColumn(consumer, RVP_WreckCookoffGeometry.cameraRelative(origin, camera), direction, length,
                    column.width() * lifecycleScale, time, column.phase(), sections);
        }
        BUFFER.endBatch(TYPE);
    }

    /** 沿喷射轴连续铺重叠动画片；两组交叉面提供横向厚度。 */
    private static void drawColumn(VertexConsumer consumer, Vec3 origin, Vec3 axis,
                                   double length, double width, double time, double phase, int sections) {
        Vec3 side = axis.cross(origin.scale(-1)).normalize();
        if (side.lengthSqr() < 1.0e-8) side = axis.cross(new Vec3(1, 0, 0)).normalize();
        if (side.lengthSqr() < 1.0e-8) side = axis.cross(new Vec3(0, 0, 1)).normalize();
        Vec3 cross = axis.cross(side).normalize();
        for (int layer = 0; layer < 2; layer++) {
            for (int i = 0; i < sections; i++) {
                double t = (double) i / sections;
                double end = Math.min(1, (i + 1.5) / sections);
                double ripple = 1 + 0.12 * Math.sin(time * 0.35 - i * 0.8 + phase);
                double halfWidth = width * (1 - 0.65 * t) * ripple * (layer == 0 ? 0.65 : 0.34);
                // 第 3～8 帧维持明亮燃烧，顶端第 9～12 帧衰减；按段错开避免整柱闪灭。
                int frame = t > 0.8 ? 8 + Math.floorMod((int) (time * 0.4 + phase + i), 4)
                        : 2 + Math.floorMod((int) (time * 0.55 + phase - i + layer * 2), 6);
                float alpha = (float) ((layer == 0 ? 0.72 : 0.9) * (1 - 0.65 * t));
                Vec3 bottom = origin.add(axis.scale(length * t));
                Vec3 top = origin.add(axis.scale(length * end));
                // 调用本类面片绘制，每段保持贴图完整帧，避免横条跨帧混色。
                quad(consumer, bottom, top, side.scale(halfWidth), frame, alpha);
                quad(consumer, bottom, top, cross.scale(halfWidth), frame, alpha * 0.7f);
            }
        }
    }

    /** 单个喷焰片使用半像素内缩 UV，避免相邻动画帧污染透明边缘。 */
    private static void quad(VertexConsumer consumer, Vec3 bottom, Vec3 top,
                             Vec3 side, int frame, float alpha) {
        float u0 = (frame * 64 + 0.5f) / 1024;
        float u1 = ((frame + 1) * 64 - 0.5f) / 1024;
        // 调用统一顶点函数，纹理保持原色、光照全亮。
        vertex(consumer, bottom.subtract(side), u0, 1, alpha);
        vertex(consumer, bottom.add(side), u1, 1, alpha);
        vertex(consumer, top.add(side), u1, 0, alpha);
        vertex(consumer, top.subtract(side), u0, 0, alpha);
    }

    /** 写入标准实体发光透明材质需要的顶点属性。 */
    private static void vertex(VertexConsumer consumer, Vec3 point, float u, float v, float alpha) {
        // 直接提交相机相对世界坐标；不得使用接收 Matrix4f 的重载重复应用视图矩阵。
        consumer.vertex(point.x, point.y, point.z)
                .color(255, 255, 255, Mth.clamp((int) (alpha * 255), 0, 255)).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(0, 1, 0).endVertex();
    }
}
