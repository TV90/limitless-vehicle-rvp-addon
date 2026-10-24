package org.ywzj.rvp.client.visual;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveCloudField.Cloud;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 威尔逊凝结云、贴地冲击环及低矮反射前沿的自绘几何，不依赖粒子生成距离。 */
final class RVP_WilsonMachRenderer {
    /** 复用现有云团贴图，不创建或覆盖资源。 */
    private static final ResourceLocation CLOUD = ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "textures/nuclear/particle_base.png");
    /** 复用现有核心闪光贴图。 */
    private static final ResourceLocation FLARE = ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "textures/nuclear/flare.png");
    /** 环形网格分段数，独立于距离。 */
    private static final int SEGMENTS = 96;

    private RVP_WilsonMachRenderer() { }

    static void render(RVP_WilsonMachEffectInstance effect, RenderLevelStageEvent event, boolean thermal) {
        // 调用实例时间线，将自定义时长映射回标准 220 tick 曲线。
        float age = effect.timeline(event.getPartialTick());
        if (age >= 220) { return; }
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        projection.m22(-(10000.0F + 0.05F) / (10000.0F - 0.05F));
        projection.m32(-(2 * 10000.0F * 0.05F) / (10000.0F - 0.05F));
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        try {
            view.setIdentity();
            view.mulPose(Axis.XP.rotationDegrees(event.getCamera().getXRot()));
            view.mulPose(Axis.YP.rotationDegrees(event.getCamera().getYRot() + 180));
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            drawFront(effect, camera, age, thermal);
            drawClouds(effect, camera, age, event.getCamera().rotation(), thermal);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            view.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static void drawFront(RVP_WilsonMachEffectInstance effect, Vec3 camera, float age, boolean thermal) {
        if (effect.profile.hitClass() == RVP_ShockwaveProfile.HitClass.AIR || age >= 130) { return; }
        float expansion = 1 - (float) Math.exp(-age / 24);
        float fade = Mth.clamp(age / 4, 0, 1) * (1 - age / 130);
        boolean buried = effect.profile.hitClass() == RVP_ShockwaveProfile.HitClass.BURIED;
        float color = thermal ? 1 : buried ? 0.45F : 0.95F;
        float width = buried ? 0.25F : 0.09F;
        float height = Mth.clamp(effect.radius * (0.15F + 0.65F * effect.profile.solidity()), 2, 24)
                * Mth.clamp((age - 8) / 12, 0, 1) * fade;
        Vec3 center = effect.center().subtract(camera);
        double y = effect.profile.groundY() + 0.05 - camera.y;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int segment = 0; segment < SEGMENTS; segment++) {
            double a = segment * Math.PI * 2 / SEGMENTS;
            double b = (segment + 1) * Math.PI * 2 / SEGMENTS;
            // 调用公共八方向插值，同时驱动亮环和竖直冲击前沿。
            double ra = effect.profile.radiusAt(a) * expansion;
            double rb = effect.profile.radiusAt(b) * expansion;
            double ax = Math.cos(a), az = Math.sin(a), bx = Math.cos(b), bz = Math.sin(b);
            float alpha = fade * (buried ? 0.22F : 0.4F);
            vertex(builder, center.x + ax * ra, y, center.z + az * ra, color, 0);
            vertex(builder, center.x + bx * rb, y, center.z + bz * rb, color, 0);
            vertex(builder, center.x + bx * rb * (1 - width), y, center.z + bz * rb * (1 - width), color, alpha);
            vertex(builder, center.x + ax * ra * (1 - width), y, center.z + az * ra * (1 - width), color, alpha);
            // 近地明亮、顶部透明的低矮墙体，只是马赫杆外观近似。
            vertex(builder, center.x + ax * ra * 0.94, y, center.z + az * ra * 0.94, color, alpha);
            vertex(builder, center.x + bx * rb * 0.94, y, center.z + bz * rb * 0.94, color, alpha);
            vertex(builder, center.x + bx * rb * 0.92, y + height, center.z + bz * rb * 0.92, color, 0);
            vertex(builder, center.x + ax * ra * 0.92, y + height, center.z + az * ra * 0.92, color, 0);
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private static void drawClouds(RVP_WilsonMachEffectInstance effect, Vec3 camera, float age,
            Quaternionf rotation, boolean thermal) {
        float partial = Mth.clamp(age - effect.age(), 0, 1);
        List<Cloud> visible = new ArrayList<>(effect.clouds.size());
        for (RVP_WilsonMachEffectInstance.CloudBirth birth : effect.clouds) {
            if (birth.tick() <= age && !birth.cloud().dead) { visible.add(birth.cloud()); }
        }
        // 调用共享云团的插值坐标，半透明云团由远到近绘制。
        visible.sort(Comparator.comparingDouble((Cloud cloud) -> distanceSquared(cloud, effect, camera, partial)).reversed());
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, CLOUD);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float[] rgb = new float[3];
        for (Cloud cloud : visible) {
            // 调用共享的尺寸、颜色、透明度与位置插值，复用核爆云团算法。
            cloud.writeColor(effect, partial, rgb);
            float brightness = thermal ? 1 : 0.55F + (rgb[0] + rgb[1] + rgb[2]) * 0.15F;
            float alpha = cloud.alpha(effect) * Mth.clamp(age / 8, 0, 1) * 0.65F;
            billboard(builder, cloud.renderX(effect, partial) - camera.x, cloud.renderY(effect, partial) - camera.y,
                    cloud.renderZ(effect, partial) - camera.z, cloud.scale(effect), brightness, alpha, rotation);
        }
        BufferUploader.drawWithShader(builder.end());
        if (effect.event.flash() && age < 8) {
            // 核心闪光遵守现有客户端闪光强度配置。
            float alpha = (1 - age / 8) * RVP_ClientConfig.getThermobaricFlashIntensity();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            RenderSystem.setShaderTexture(0, FLARE);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            Vec3 center = effect.center().subtract(camera);
            billboard(builder, center.x, center.y + effect.radius * 0.08, center.z,
                    effect.radius * 0.45F, 1, alpha, rotation);
            BufferUploader.drawWithShader(builder.end());
        }
    }

    private static double distanceSquared(Cloud cloud, RVP_WilsonMachEffectInstance effect, Vec3 camera, float partial) {
        // 调用共享坐标插值，排序与实际绘制使用同一位置。
        double x = cloud.renderX(effect, partial) - camera.x;
        double y = cloud.renderY(effect, partial) - camera.y;
        double z = cloud.renderZ(effect, partial) - camera.z;
        return x * x + y * y + z * z;
    }

    private static void vertex(BufferBuilder builder, double x, double y, double z, float color, float alpha) {
        builder.vertex(x, y, z).color(color, color, color, alpha).endVertex();
    }

    /** 相机朝向四边形；先用 double 相减，再转 float，避免远处坐标丢精度。 */
    private static void billboard(BufferBuilder builder, double x, double y, double z, float size,
            float color, float alpha, Quaternionf rotation) {
        for (int index = 0; index < 4; index++) {
            float right = index == 1 || index == 2 ? 1 : -1;
            float up = index >= 2 ? 1 : -1;
            Vector3f offset = new Vector3f(right * size, up * size, 0).rotate(rotation);
            builder.vertex(x + offset.x, y + offset.y, z + offset.z)
                    .uv((right + 1) / 2, (1 - up) / 2).color(color, color, color, alpha).endVertex();
        }
    }
}
