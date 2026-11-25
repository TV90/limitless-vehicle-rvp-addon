package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

import java.io.IOException;

/** 先画城市背景和真实实体，随后 ApricityUI 的毛玻璃才能采样到这两个图层。 */
final class RVP_WorkshopPreview {
    /** 背景纹理及原始宽高比，按屏幕边缘等比裁切。 */
    private static final ResourceLocation RAIN = new ResourceLocation(RVP_MOD.MOD_ID, "textures/gui/workshop_rain.png");
    /** 一列可拉伸的雾光和细横纹，叠在模型上、文字下。 */
    private static final ResourceLocation ATMOSPHERE = new ResourceLocation(RVP_MOD.MOD_ID, "dynamic/workshop_atmosphere");
    private static final double ASPECT = 16.0 / 9.0;
    /** 顶前主光与右前补光，让深色车漆也能读出轮廓与装甲接缝。 */
    private static final Vector3f KEY_LIGHT = new Vector3f(-0.53f, 0.80f, 0.45f).normalize();
    private static final Vector3f FILL_LIGHT = new Vector3f(0.66f, 0.43f, 0.62f).normalize();
    /** 默认从车头斜前方观察，模型局部 +Z 投向画面左侧。 */
    static final float FRONT_YAW = -34;
    /** 略高于水平线，仍能看到炮塔顶部。 */
    static final float FRONT_PITCH = 4;
    /** 轨道相机水平角与俯角，单位为度。 */
    private float yaw, pitch;
    /** 相对于自动取景的倍率。 */
    private float zoom;
    /** 平移按画面比例保存，调整窗口后仍对准同一区域。 */
    private float panX, panY;

    private final boolean fixedWing;
    /** 辅助载具独立按屏幕中心取景，复位时使用同一默认倍率。 */
    private final boolean auxiliary;
    /** 候选模型在默认视角的轮廓中心和投影宽高，只在切换候选时测量。 */
    private final Frame frame;

    RVP_WorkshopPreview(AbstractVehicle vehicle, boolean auxiliary) {
        fixedWing = vehicle instanceof FixedWingVehicle;
        this.auxiliary = auxiliary;
        reset();
        frame = auxiliary && vehicle != null ? measureFrame(vehicle, yaw, pitch) : null;
        var textures = Minecraft.getInstance().getTextureManager();
        if (textures.getTexture(RAIN, null) == null) textures.register(RAIN, new SimpleTexture(RAIN));
        if (!(textures.getTexture(ATMOSPHERE, null) instanceof Atmosphere)) textures.register(ATMOSPHERE, new Atmosphere());
    }

    /** 原图的解码、缩滤和调色交给资源重载的工作线程，不占用第一次打开界面的主线程。 */
    static NativeImage prepareBackdrop(ResourceManager resources) throws IOException {
        try (var input = resources.open(RAIN); NativeImage image = NativeImage.read(input)) {
            int width = image.getWidth(), height = image.getHeight();
            try (var reduced = new NativeImage(image.format(), Math.max(1, width / 11), Math.max(1, height / 11), false)) {
                image.resizeSubRectTo(0, 0, width, height, reduced);
                gradeBackdrop(reduced);
                var soft = new NativeImage(image.format(), width, height, false);
                reduced.resizeSubRectTo(0, 0, reduced.getWidth(), reduced.getHeight(), soft);
                return soft;
            }
        }
    }

    /** 资源重载的 apply 阶段在渲染线程上传；像素处理和采样方式沿用原效果。 */
    static void installBackdrop(NativeImage image) {
        var textures = Minecraft.getInstance().getTextureManager();
        if (image != null) {
            var texture = new DynamicTexture(image);
            texture.setFilter(true, false);
            texture.bind();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            textures.register(RAIN, texture);
        }
        textures.register(ATMOSPHERE, new Atmosphere());
    }

    /** 在缩小的远景上做一次调色，中央偏冷亮，边角和底部保留雨夜的暗紫色。 */
    private static void gradeBackdrop(NativeImage image) {
        int width = image.getWidth(), height = image.getHeight();
        for (int y = 0; y < height; y++) {
            float v = (y + 0.5f) / height;
            for (int x = 0; x < width; x++) {
                float u = (x + 0.5f) / width;
                float dx = (u - 0.51f) * 1.65f, dy = (v - 0.31f) * 0.85f;
                float edge = Mth.clamp(dx * dx + dy * dy, 0, 1);
                float glow = 0.22f * Math.max(0, 1 - 3.1f * edge) * (1 - v);
                int pixel = image.getPixelRGBA(x, y);
                int r = FastColor.ABGR32.red(pixel), g = FastColor.ABGR32.green(pixel), b = FastColor.ABGR32.blue(pixel);
                float grey = r * 0.24f + g * 0.61f + b * 0.15f;
                float shade = 0.13f + 0.46f * edge + 0.14f * v;
                int red = (int) Mth.lerp(glow, Mth.lerp(shade, Mth.lerp(0.28f, r, grey), 48), 199);
                int green = (int) Mth.lerp(glow, Mth.lerp(shade, Mth.lerp(0.28f, g, grey), 43), 221);
                int blue = (int) Mth.lerp(glow, Mth.lerp(shade, Mth.lerp(0.28f, b, grey), 55), 239);
                image.setPixelRGBA(x, y, FastColor.ABGR32.color(255, blue, green, red));
            }
        }
    }

    /** 仅在资源加载时烘出屏幕高度方向的变化，整屏显示只需绘制一个四边形。 */
    private static final class Atmosphere extends SimpleTexture {
        private Atmosphere() { super(ATMOSPHERE); }

        @Override
        protected TextureImage getTextureImage(ResourceManager resources) {
            var strip = new NativeImage(1, 768, false);
            for (int y = 0; y < strip.getHeight(); y++) {
                float v = (y + 0.5f) / strip.getHeight();
                float band = Math.max(0, 1 - Math.abs(v - 0.81f) / 0.26f);
                float mist = 0.23f * band * band * (3 - 2 * band);
                float line = y % 3 == 0 ? 0.048f : 0;
                float alpha = line + (1 - line) * mist;
                float fogMix = alpha == 0 ? 0 : (1 - line) * mist / alpha;
                strip.setPixelRGBA(0, y, FastColor.ABGR32.color((int) (255 * alpha),
                        (int) Mth.lerp(fogMix, 34, 117), (int) Mth.lerp(fogMix, 22, 100), (int) Mth.lerp(fogMix, 24, 103)));
            }
            return new TextureImage(new TextureMetadataSection(true, true), strip);
        }
    }

    void reset() {
        // 固定翼从机头侧下方取景，露出起落架与翼下挂架。
        yaw = fixedWing ? -48f : FRONT_YAW;
        pitch = fixedWing ? -14f : FRONT_PITCH;
        zoom = fixedWing && !auxiliary ? 1.12f : 1f;
        panX = panY = 0;
    }

    void zoom(double delta) {
        zoom = Mth.clamp(zoom * (float) Math.pow(1.12, delta), 0.35f, 3f);
    }

    void drag(int button, double dx, double dy, int width, int height) {
        if (button == 0) {
            yaw += (float) dx * 0.75f;
            pitch = Mth.clamp(pitch + (float) dy * 0.6f, -75f, 75f);
        } else {
            panX = Mth.clamp(panX + (float) dx / width, -0.45f, 0.45f);
            panY = Mth.clamp(panY + (float) dy / height, -0.45f, 0.45f);
        }
    }

    void render(GuiGraphics gui, AbstractVehicle vehicle, int width, int height) {
        int bgW = Math.max(width, (int) Math.ceil(height * ASPECT));
        int bgH = Math.max(height, (int) Math.ceil(width / ASPECT));
        gui.blit(RAIN, (width - bgW) / 2, (height - bgH) / 2, 0f, 0f, bgW, bgH, bgW, bgH);
        gui.fillGradient(0, 0, width, height, 0x08191b2b, 0x3a241c2a);
        gui.flush();
        if (vehicle != null) drawModel(gui, vehicle, width, height);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        gui.blit(ATMOSPHERE, 0, 0, 0f, 0f, width, height, width, height);
        RenderSystem.disableBlend();
    }

    /** center 是模型局部坐标，width、height 是默认视角的投影跨度，单位为格。 */
    private record Frame(Vec3 center, double width, double height) {}

    private static Frame measureFrame(AbstractVehicle vehicle, float yaw, float pitch) {
        var instance = vehicle.getVehicleModelInstance();
        if (instance == null) return null;
        // 对预览实体应用和实际绘制相同的初始姿态，包含高出的设备与伸出车身的旋翼。
        VehicleRender.applyAnimationPose(vehicle, 1f, instance, false);
        var view = Axis.XP.rotationDegrees(pitch).mul(Axis.YP.rotationDegrees(yaw));
        var min = new Vector3f(Float.POSITIVE_INFINITY);
        var max = new Vector3f(Float.NEGATIVE_INFINITY);
        var point = new Vector3f();
        for (var chunk : instance.baseModel().chunks()) {
            var transform = new Matrix4f().rotation(view);
            if (!chunk.isRootAttached()) {
                var bone = instance.getBone(chunk.attachBoneIndex());
                while (bone.visible && bone.parentIndex() >= 0) bone = instance.getBone(bone.parentIndex());
                if (!bone.visible) continue;
                // 取烘焙骨的完整变换，包含父骨、旋转中心与缩放。
                transform.mul(instance.getGlobalTransform(chunk.attachBoneIndex()));
            }
            for (float[] vertices : new float[][] {chunk.quads().positions(), chunk.vertices().positions()}) {
                for (int i = 0; i < vertices.length; i += 3) {
                    transform.transformPosition(vertices[i], vertices[i + 1], vertices[i + 2], point);
                    min.min(point);
                    max.max(point);
                }
            }
        }
        double width = max.x - min.x, height = max.y - min.y;
        if (!(width > 0 && height > 0)) return null;
        // 将投影中心还原到模型空间，拖动旋转后仍绕模型本身转动。
        var center = min.lerp(max, 0.5f).rotate(view.conjugate());
        return new Frame(new Vec3(center.x, center.y, center.z), width, height);
    }

    private void drawModel(GuiGraphics gui, AbstractVehicle vehicle, int width, int height) {
        var body = vehicle.getMainCubeOBB();
        double length = Math.max(1.5, vehicle.getStructureLength());
        double bodyHeight = body == null ? vehicle.getBbHeight() : body.getHeight();
        double fitWidth = frame == null ? length : frame.width();
        double fitHeight = frame == null ? Math.max(bodyHeight + length * 0.23, 1) : frame.height();
        // 辅助载具上下留出候选列表和底栏的空间，横向较宽的机翼按屏宽约束。
        double scale = Math.min(width * (auxiliary ? 0.32 : 0.85) / fitWidth,
                height * (auxiliary ? 0.27 : 0.75) / fitHeight);
        float pixels = (float) scale * zoom;
        var pivot = vehicle.centerOffset;
        var center = body == null ? pivot : body.offset();
        if (frame != null) center = frame.center();
        var pose = gui.pose();
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        pose.pushPose();
        try {
            double anchorX = auxiliary ? 0.5 : 0.607;
            double anchorY = auxiliary ? 0.5 : (fixedWing ? 0.59 : 0.633);
            pose.translate(width * (anchorX + panX), height * (anchorY + panY), 400);
            // GUI 的 Y 轴向下，只翻转位置矩阵；参与光照的模型法线仍然向上。
            pose.mulPoseMatrix(new Matrix4f().scaling(pixels, -pixels, pixels));
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.translate(-center.x, -center.y, -center.z);
            // 本体 renderer 绕 centerOffset 应用世界姿态。先乘其逆，停放朝向、玩家站位都不会改变默认视图。
            pose.rotateAround(vehicle.rotYXZ(1).conjugate(new Quaternionf()), (float) pivot.x, (float) pivot.y, (float) pivot.z);
            RenderSystem.setShaderLights(KEY_LIGHT, FILL_LIGHT);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            // 直接调用注册的实体渲染器绘制当前涂装和挂件，跳过世界投影、实体火焰以及调试调度器。
            RenderSystem.runAsFancy(() -> dispatcher.getRenderer(vehicle).render(vehicle, 0, 1f, pose, gui.bufferSource(), LightTexture.FULL_BRIGHT));
            gui.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
    }
}
