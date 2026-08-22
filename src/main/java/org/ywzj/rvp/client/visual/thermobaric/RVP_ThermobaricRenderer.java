package org.ywzj.rvp.client.visual.thermobaric;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.ywzj.rvp.RVP_MOD;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 只负责绘制温压火球、压力波、凝结云、贴地尘环和后燃烟云。 */
public final class RVP_ThermobaricRenderer {
    /** 纯白贴图用作球壳 */
    private static final ResourceLocation WHITE_TEXTURE =
            RVP_MOD.modLocation("textures/white/white.png");

    /** 凝结云墙球壳数量。 */
    private static final int PRESSURE_SHELL_LAYERS = 2;
    /** 凝结云墙各层在墙体总厚度内的归一化径向偏移。 */
    private static final float[] PRESSURE_SHELL_OFFSETS = {-0.5F, 0.5F};
    /** 凝结云墙各层基础透明度。 */
    private static final float[] PRESSURE_SHELL_ALPHAS = {0.85F, 0.85F};

    /** 半透明云团绘制时使用的临时排序表。 */
    private static final List<RenderCloud> SORTED_CLOUDS = new ArrayList<>();
    /** 半透明云团绘制数据对象池，避免压力波存续期间每帧产生数百个短命对象。 */
    private static final List<RenderCloud> RENDER_CLOUD_POOL = new ArrayList<>();
    /** 半透明云团按相机距离由远到近排序。 */
    private static final Comparator<RenderCloud> FAR_TO_NEAR =
            Comparator.comparingDouble(RenderCloud::distanceSquared).reversed();
    /** 当前批次已经从对象池取用的绘制数据数量。 */
    private static int renderCloudPoolIndex;

    private RVP_ThermobaricRenderer() {
    }

    /** 在世界半透明阶段提交一个温压实例的四层几何。 */
    public static void render(RVP_ThermobaricEffectInstance effect, RenderLevelStageEvent event) {
        float visualAge = effect.age() + event.getPartialTick();
        if (visualAge >= effect.duration()) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();
        // 调用温压预设的类型化 LOD，以爆心到相机距离一次性解析本实例当前帧的粒子保留比例。
        float particleRatio = effect.preset().thermobaricLod().resolveParticleRatio(
                effect.center().distanceToSqr(cameraPosition));
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        modelView.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // 凝结云使用标准透明混合并最先提交，避免近爆心观察时位于外层的透明云片覆盖后续火球等特效。
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        if (effect.preset().showCondensationCloud()
                || effect.preset().showCondensationCloudParticles()) {
            renderCondensationCloud(effect, visualAge, cameraPosition, particleRatio);
        }

        // 压力波、尘环和烟云使用标准透明混合，保持与世界几何的深度关系。
        if (effect.preset().showPressureWave()) {
            renderPressureWave(effect, visualAge, cameraPosition);
        }
        if (effect.preset().showDustRing()) {
            renderDustRing(effect, visualAge, cameraPosition, particleRatio);
        }
        if (effect.preset().showCloud()) {
            renderClouds(effect, visualAge, cameraPosition, particleRatio);
        }

        // 主火球使用加色混合，让多团火焰云共同形成短时白橙色高亮核心。
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        if (effect.preset().showCore()) {
            renderFireball(effect, visualAge, cameraPosition, particleRatio);
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
    }

    private static void renderFireball(RVP_ThermobaricEffectInstance effect, float age,
            Vec3 camera, float particleRatio) {
        StageWindow window = resolveStageWindow(
                effect.preset().coreStartTick(), effect.preset().coreFullTick(),
                effect.preset().coreFadeDurationTicks(), effect.duration());
        if (!window.contains(age)) {
            return;
        }
        boolean dynamicBudget = effect.dynamicParticleBudgetEnabled();
        int coverageLimitedCount = effect.fireballClouds().size();
        if (dynamicBudget) {
            // 火球在 full tick 后冻结面积需求，避免淡出阶段为了补偿扩散而生成新粒子。
            float budgetAge = RVP_ThermobaricParticleBudget.resolveFrozenBudgetAge(
                    age, window.fullTick());
            float budgetFormationProgress = window.formationProgress(budgetAge);
            float budgetCoreRadius = resolveFireballCoreRadius(effect.visualRadius(),
                    budgetFormationProgress, 0.0F);
            double geometryArea = RVP_ThermobaricParticleBudget.resolveSphereArea(
                    budgetCoreRadius, 1.0F);
            float textureCoverage = RVP_ThermobaricParticleCoverage.effectiveCoverage();
            coverageLimitedCount = RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                    effect.fireballClouds().size(), geometryArea,
                    RVP_ThermobaricParticleBudget.FIREBALL_OVERLAP_FACTOR,
                    index -> RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(
                            resolveFireballCloudHalfSize(effect, window,
                                    effect.fireballClouds().get(index), budgetAge),
                            textureCoverage));
        }
        // 调用实验预算门面；关闭时门面严格复用既有完整列表 LOD 数量算法。
        int fireballCloudCount = RVP_ThermobaricParticleBudget.resolveRenderCount(
                dynamicBudget, effect.fireballClouds().size(), particleRatio,
                coverageLimitedCount);
        int coreLayerCount = RVP_ThermobaricParticleBudget.resolveRenderCount(
                dynamicBudget, effect.dynamicCoreLayerCapacity(), particleRatio,
                effect.dynamicCoreLayerCapacity());
        if (fireballCloudCount <= 0 && coreLayerCount <= 0) {
            return;
        }
        float formationProgress = window.formationProgress(age);
        float fadeProgress = window.fadeProgress(age);
        float expansion = easeOutCubic(formationProgress);
        float alpha = Mth.clamp(formationProgress * 1.8F, 0.0F, 1.0F) * window.fadeAlpha(age);
        // 【世界坐标生成位置·温压火球】以服务端爆心为基准，仅向上偏移视觉半径的 0.12 倍。
        Vec3 center = effect.center().add(0.0D, effect.visualRadius() * 0.12D, 0.0D);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.setShaderTexture(0, RVP_ThermobaricParticleCoverage.PARTICLE_TEXTURE);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        for (int cloudIndex = 0; cloudIndex < fireballCloudCount; cloudIndex++) {
            // 使用事件种子生成列表的稳定前缀，切换 LOD 时只增减确定的火球云团。
            RVP_ThermobaricEffectInstance.FireballCloud cloud =
                    effect.fireballClouds().get(cloudIndex);
            float cloudStart = window.startTick()
                    + cloud.phase() * window.formationDuration() * 0.38F;
            if (age < cloudStart) {
                continue;
            }
            float localProgress = Mth.clamp((age - cloudStart)
                    / Math.max(1.0F, window.fullTick() - cloudStart), 0.0F, 1.0F);
            float localExpansion = easeOutCubic(localProgress);
            float contraction = 1.0F - fadeProgress * 0.12F;
            float offsetScale = effect.visualRadius() * (0.18F + localExpansion * 0.72F)
                    * contraction;
            float x = (float) (center.x + cloud.offsetX() * offsetScale - camera.x);
            float y = (float) (center.y + cloud.offsetY() * offsetScale - camera.y);
            float z = (float) (center.z + cloud.offsetZ() * offsetScale - camera.z);
            float size = effect.visualRadius() * cloud.sizeFactor()
                    * (0.9F + localExpansion * 1.35F) * (1.0F - fadeProgress * 0.08F);
            int color = mixColor(effect.preset().coreColor(), effect.preset().flameColor(),
                    Mth.clamp(localProgress * 1.25F, 0.0F, 1.0F));
            float radialFactor = Mth.sqrt(cloud.offsetX() * cloud.offsetX()
                    + cloud.offsetY() * cloud.offsetY() + cloud.offsetZ() * cloud.offsetZ());
            float grayProgress = outerToInnerGrayProgress(fadeProgress, radialFactor);
            color = mixColor(color, grayscaleColor(color), grayProgress);
            writeParticleBillboard(builder, x, y, z, size, cloud.rotation(), color,
                    alpha * Mth.clamp(localProgress * 2.0F, 0.0F, 1.0F)
                            * (0.58F + cloud.sizeFactor()));
        }

        float centerX = (float) (center.x - camera.x);
        float centerY = (float) (center.y - camera.y);
        float centerZ = (float) (center.z - camera.z);
        float coreRadius = resolveFireballCoreRadius(effect.visualRadius(),
                formationProgress, fadeProgress);
        if (coreLayerCount >= 1) {
            writeParticleBillboard(builder, centerX, centerY, centerZ, coreRadius, 0.0F,
                    fadeToGray(effect.preset().flameColor(), fadeProgress, 1.0F), alpha * 0.72F);
        }
        if (coreLayerCount >= 2) {
            writeParticleBillboard(builder, centerX, centerY, centerZ, coreRadius * 0.62F, 0.7F,
                    fadeToGray(effect.preset().coreColor(), fadeProgress, 0.55F), alpha);
        }
        if (coreLayerCount >= 3) {
            writeParticleBillboard(builder, centerX, centerY, centerZ, coreRadius * 0.28F, 1.4F,
                    fadeToGray(0xFFF5DC, fadeProgress, 0.15F), alpha);
        }
        BufferUploader.drawWithShader(builder.end());
    }

    /** 返回火球核心在指定成形和淡出进度下的实际外包络半径。 */
    static float resolveFireballCoreRadius(float visualRadius, float formationProgress,
            float fadeProgress) {
        float expansion = easeOutCubic(Mth.clamp(formationProgress, 0.0F, 1.0F));
        return visualRadius * (0.28F + expansion * 0.86F)
                * (1.0F - Mth.clamp(fadeProgress, 0.0F, 1.0F) * 0.08F);
    }

    /** 返回火球候选云团在预算采样时刻的实际 billboard 半边长。 */
    private static float resolveFireballCloudHalfSize(RVP_ThermobaricEffectInstance effect,
            StageWindow window, RVP_ThermobaricEffectInstance.FireballCloud cloud,
            float budgetAge) {
        float cloudStart = window.startTick()
                + cloud.phase() * window.formationDuration() * 0.38F;
        if (budgetAge < cloudStart) {
            return 0.0F;
        }
        float localProgress = Mth.clamp((budgetAge - cloudStart)
                / Math.max(1.0F, window.fullTick() - cloudStart), 0.0F, 1.0F);
        float localExpansion = easeOutCubic(localProgress);
        return effect.visualRadius() * cloud.sizeFactor()
                * (0.9F + localExpansion * 1.35F);
    }

    private static void renderPressureWave(RVP_ThermobaricEffectInstance effect,
                                           float age, Vec3 camera) {
        StageWindow window = resolveStageWindow(effect.preset().pressureWaveStartTick(),
                effect.preset().pressureWaveFullTick(),
                effect.preset().pressureWaveEndTick()
                        - effect.preset().pressureWaveFullTick(), effect.duration());
        if (!window.contains(age)) return;

        float formationProgress = window.formationProgress(age);
        float fadeProgress = window.fadeProgress(age);
        float fadeAlpha = resolvePressureWaveFadeAlpha(age, window.fullTick(),
                effect.preset().pressureWaveEndTick(),
                effect.preset().pressureWaveFadeSpeedFactor());
        float radius = effect.visualRadius() * effect.preset().pressureRadiusFactor()
                * (easeOutCubic(formationProgress)
                + effect.pressureWaveFadeSpread() * fadeProgress);
        if (radius <= 0.0F || effect.preset().pressureRings() <= 0
                || effect.preset().pressureSegments() <= 0) {
            return;
        }

        // 【世界坐标生成位置·压力波】球壳严格以服务端权威爆心为球心。
        Vec3 center = effect.center();
        float cx = (float)(center.x - camera.x);
        float cy = (float)(center.y - camera.y);
        float cz = (float)(center.z - camera.z);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();

        // 光学压力波在 full_tick 后继续随机幅度地向外扩张，并按预设倍率决定是否线性变淡。
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        writeSphere(builder, cx, cy, cz, radius, 0xFFF8EA, fadeAlpha * 0.08F,
                effect.preset().pressureRings(), effect.preset().pressureSegments());
        BufferUploader.drawWithShader(builder.end());
    }

    /**
     * 绘制独立于光学压力波的凝结云墙。
     * 云墙从 start_tick 到 full_tick 匀速扩张，之后保持相同径向速度继续外扩并按配置倍率线性变淡；
     * 同时按配置倍率从球体 Y 轴最高点向下连续裁切，派生阶段结束时半径为完整成形半径两倍。
     */
    private static void renderCondensationCloud(RVP_ThermobaricEffectInstance effect,
            float age, Vec3 camera, float particleRatio) {
        StageWindow window = resolveStageWindow(effect.preset().pressureWaveStartTick(),
                effect.preset().pressureWaveFullTick(),
                effect.preset().pressureWaveEndTick()
                        - effect.preset().pressureWaveFullTick(), effect.duration());
        if (!window.contains(age)) {
            return;
        }

        float formationProgress = window.formationProgress(age);
        float radialProgress = resolvePressureWaveRadialProgress(age,
                effect.preset().pressureWaveStartTick(),
                effect.preset().pressureWaveFullTick());
        float fadeAlpha = resolvePressureWaveFadeAlpha(age, window.fullTick(),
                effect.preset().pressureWaveEndTick(),
                effect.preset().pressureWaveFadeSpeedFactor());
        float cutProgress = resolveCondensationCutProgress(age,
                effect.preset().pressureWaveFullTick(), effect.preset().pressureWaveEndTick(),
                effect.preset().condensationCloudCutSpeedFactor());
        float radius = effect.visualRadius() * effect.preset().pressureRadiusFactor()
                * radialProgress;
        float wallThickness = effect.visualRadius()
                * Mth.lerp(formationProgress, 1.25F, 0.12F);
        float particleWallThickness = resolveCondensationParticleWallThickness(
                effect.visualRadius(), formationProgress,
                effect.preset().condensationCloudParticleSpawnThicknessFactor());
        int cloudColor = mixColor(0xFFFFFF, 0xD8DEE1, formationProgress);

        // 【世界坐标生成位置·凝结云】球壳和粒子共用服务端权威爆心及压力波半径。
        Vec3 center = effect.center();
        float cx = (float) (center.x - camera.x);
        float cy = (float) (center.y - camera.y);
        float cz = (float) (center.z - camera.z);
        float highestY = (float) center.y + radius + wallThickness * 0.5F;
        float lowestY = (float) center.y - radius - wallThickness * 0.5F;
        float maximumVisibleY = Mth.lerp(cutProgress, highestY, lowestY);
        float particleHighestY = (float) center.y + radius + particleWallThickness * 0.5F;
        float particleLowestY = (float) center.y - radius - particleWallThickness * 0.5F;
        float particleMaximumVisibleY = Mth.lerp(
                cutProgress, particleHighestY, particleLowestY);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();

        if (effect.preset().showCondensationCloud()
                && effect.preset().pressureRings() > 0
                && effect.preset().pressureSegments() > 0) {
            // 两层纯白贴图球壳随 wallThickness 靠拢，直接表达凝结云墙厚度的线性收缩。
            RenderSystem.setShaderTexture(0, WHITE_TEXTURE);
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int layer = 0; layer < PRESSURE_SHELL_LAYERS; layer++) {
                float shellRadius = Math.max(0.01F,
                        radius + PRESSURE_SHELL_OFFSETS[layer] * wallThickness);
                writeTexturedSphereBelow(builder, cx, cy, cz, shellRadius,
                        maximumVisibleY - (float) camera.y, cloudColor,
                        PRESSURE_SHELL_ALPHAS[layer] * fadeAlpha,
                        effect.preset().pressureRings(),
                        effect.preset().pressureSegments());
            }
            BufferUploader.drawWithShader(builder.end());
        }

        if (!effect.preset().showCondensationCloudParticles() || particleRatio <= 0.0F) {
            return;
        }

        // 粒子凝结云与可选球壳共用径向曲线、淡出速度倍率和自顶向下裁切边界。
        float halfThickness = particleWallThickness * 0.5F;
        beginSortedParticleClouds();
        List<RVP_ThermobaricEffectInstance.PressureSmoke> smokes = effect.pressureSmokeParticles();
        boolean dynamicBudget = effect.dynamicParticleBudgetEnabled();
        int coverageLimitedCount = smokes.size();
        if (dynamicBudget) {
            // 粒子凝结云按当前未被自顶向下裁掉的球面面积持续重算，full tick 后仍跟随外扩。
            double geometryArea = RVP_ThermobaricParticleBudget.resolveSphereArea(radius,
                    1.0F - cutProgress);
            float textureCoverage = RVP_ThermobaricParticleCoverage.effectiveCoverage();
            coverageLimitedCount = RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                    smokes.size(), geometryArea,
                    RVP_ThermobaricParticleBudget.CONDENSATION_OVERLAP_FACTOR,
                    index -> resolveCondensationParticleEffectiveArea(effect,
                            smokes.get(index), formationProgress, radius, halfThickness,
                            particleMaximumVisibleY, textureCoverage));
        }
        int renderCount = RVP_ThermobaricParticleBudget.resolveRenderCount(
                dynamicBudget, smokes.size(), particleRatio, coverageLimitedCount);
        for (int i = 0; i < renderCount; i++) {
            RVP_ThermobaricEffectInstance.PressureSmoke smoke = smokes.get(i);
            float pr = Math.max(0.0f, radius + smoke.radialOffset() * halfThickness);
            double x = center.x + smoke.directionX() * pr;
            double y = center.y + smoke.directionY() * pr;
            double z = center.z + smoke.directionZ() * pr;
            if (y > particleMaximumVisibleY) {
                continue;
            }
            float size = effect.visualRadius() * smoke.sizeFactor()
                    * (1.1F + formationProgress * 1.6F)
                    * effect.preset().condensationCloudParticleScale();
            float particleAlpha = 0.42F + formationProgress * 0.15F;
            addSortedParticleCloud(x, y, z, size, smoke.rotation(), cloudColor,
                    particleAlpha * fadeAlpha,
                    camera.distanceToSqr(x, y, z));
        }
        renderSortedParticleClouds(camera);
    }

    /** 返回一个凝结云候选粒子在当前球面和裁切边界下的实际有效面积。 */
    private static double resolveCondensationParticleEffectiveArea(
            RVP_ThermobaricEffectInstance effect,
            RVP_ThermobaricEffectInstance.PressureSmoke smoke,
            float formationProgress, float radius, float halfThickness,
            float maximumVisibleY, float textureCoverage) {
        float particleRadius = Math.max(0.0F,
                radius + smoke.radialOffset() * halfThickness);
        double particleY = effect.center().y + smoke.directionY() * particleRadius;
        if (particleY > maximumVisibleY) {
            return 0.0D;
        }
        float halfSize = effect.visualRadius() * smoke.sizeFactor()
                * (1.1F + formationProgress * 1.6F)
                * effect.preset().condensationCloudParticleScale();
        return RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(
                halfSize, textureCoverage);
    }

    /** 返回三个尘环带在当前帧需要覆盖的总环带面积。 */
    static double resolveDustGeometryArea(float radius, float size) {
        double area = 0.0D;
        for (int band = -1; band <= 1; band++) {
            float bandRadius = Math.max(0.0F, radius + band * size * 0.72F);
            float halfSize = size * (band == 0 ? 1.25F : 1.0F);
            area += RVP_ThermobaricParticleBudget.resolveRingBandArea(
                    bandRadius, halfSize);
        }
        return area;
    }

    /** 返回一个尘环环段实际绘制的三张 billboard 的有效面积总和。 */
    private static double resolveDustSegmentEffectiveArea(
            RVP_ThermobaricEffectInstance effect, int segmentIndex,
            float radius, float size, float sampledMaximumRadius,
            float textureCoverage) {
        if (segmentIndex < 0) {
            return 0.0D;
        }
        double effectiveArea = 0.0D;
        for (int band = -1; band <= 1; band++) {
            float bandRadius = Math.max(0.0F, radius + band * size * 0.72F);
            float groundProgress = sampledMaximumRadius <= 1.0E-4F
                    ? 0.0F : bandRadius / sampledMaximumRadius;
            if (!Float.isFinite(effect.dustGroundHeight(segmentIndex, groundProgress))) {
                continue;
            }
            float halfSize = size * (band == 0 ? 1.25F : 1.0F);
            effectiveArea += RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(
                    halfSize, textureCoverage);
        }
        return effectiveArea;
    }

    private static void renderDustRing(RVP_ThermobaricEffectInstance effect,
            float age, Vec3 camera, float particleRatio) {
        int startTick = effect.preset().dustRingStartTick();
        int fullTick = effect.preset().dustRingFullTick();
        float endTick = Math.min(effect.preset().dustRingEndTick(), effect.duration());
        if (fullTick <= startTick || age < startTick || age >= endTick) {
            return;
        }
        float formationProgress = Mth.clamp((age - startTick) / (fullTick - startTick),
                0.0F, 1.0F);
        float radialProgress = resolveDustRadialProgress(age, startTick, fullTick);
        float maximumRadius = effect.visualRadius() * effect.preset().dustRadiusFactor();
        float radius = maximumRadius * radialProgress;
        float alpha = Mth.clamp(formationProgress * 2.0F, 0.0F, 1.0F)
                * resolveDustFadeAlpha(age, fullTick, endTick) * 0.82F;
        float lifetimeProgress = Mth.clamp((age - startTick)
                / Math.max(1.0F, endTick - startTick), 0.0F, 1.0F);
        // 尘环从生成的第一帧起线性变细，以持续收窄的白色环带表现能量衰减。
        float size = effect.visualRadius() * resolveDustThicknessFactor(lifetimeProgress);
        float sampledMaximumRadius = RVP_ThermobaricEffectInstance.resolveDustGroundSampleRadius(
                effect.visualRadius(), effect.preset().dustRadiusFactor());
        int segmentCount = effect.dustSegmentCount();
        boolean dynamicBudget = effect.dynamicParticleBudgetEnabled();
        int coverageLimitedCount = segmentCount;
        if (dynamicBudget) {
            // 三条环带分别计算圆周展开面积，full tick 后继续使用实际外扩半径与收窄尺寸。
            double geometryArea = resolveDustGeometryArea(radius, size);
            float textureCoverage = RVP_ThermobaricParticleCoverage.effectiveCoverage();
            coverageLimitedCount = RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                    segmentCount, geometryArea,
                    RVP_ThermobaricParticleBudget.DUST_OVERLAP_FACTOR,
                    renderIndex -> resolveDustSegmentEffectiveArea(effect,
                            effect.progressiveDustSegmentIndex(renderIndex), radius, size,
                            sampledMaximumRadius, textureCoverage));
        }
        int renderSegmentCount = RVP_ThermobaricParticleBudget.resolveRenderCount(
                dynamicBudget, segmentCount, particleRatio, coverageLimitedCount);
        if (renderSegmentCount <= 0) {
            return;
        }
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.setShaderTexture(0, RVP_ThermobaricParticleCoverage.PARTICLE_TEXTURE);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int renderIndex = 0; renderIndex < renderSegmentCount; renderIndex++) {
            // 实验开启时读取实例的稳定渐进顺序；关闭时继续调用原有均匀选段算法。
            int index = dynamicBudget
                    ? effect.progressiveDustSegmentIndex(renderIndex)
                    : RVP_ThermobaricParticleLod.resolveEvenlySpacedIndex(
                            renderIndex, renderSegmentCount, segmentCount);
            float angle = Mth.TWO_PI * index / segmentCount;
            for (int band = -1; band <= 1; band++) {
                float bandRadius = Math.max(0.0F, radius + band * size * 0.72F);
                float groundProgress = sampledMaximumRadius <= 1.0E-4F
                        ? 0.0F : bandRadius / sampledMaximumRadius;
                float groundHeight = effect.dustGroundHeight(index, groundProgress);
                if (!Float.isFinite(groundHeight)) {
                    continue;
                }
                // 【世界坐标生成位置·贴地尘环】X/Z 由爆心水平投影和当前环半径决定，Y 来自地表碰撞面采样。
                float x = (float) (effect.center().x + Mth.cos(angle) * bandRadius - camera.x);
                // 方块团粒子的中心至少抬高一个半尺寸，保证其下缘不会进入地表。
                float y = groundHeight - (float) camera.y
                        + size * (1.05F + Math.abs(band) * 0.08F);
                float z = (float) (effect.center().z + Mth.sin(angle) * bandRadius - camera.z);
                int color = band == 0 ? 0xFFFFFF : (band < 0 ? 0xE8ECEF : 0xF7F5EE);
                float bandAlpha = alpha * (band == 0 ? 1.0F : 0.68F);
                writeParticleBillboard(builder, x, y, z,
                        size * (band == 0 ? 1.25F : 1.0F), angle + band * 0.47F,
                        color, bandAlpha);
            }
        }
        BufferUploader.drawWithShader(builder.end());
    }

    /**
     * 绘制单个温压实例的后期云团。
     *
     * @param effect 提供爆心、尺寸、预设和确定性云团参数的温压实例
     * @param age 当前实例的插值后视觉年龄（tick）
     * @param camera 当前相机的世界坐标，用于透明排序与相机相对坐标换算
     */
    private static void renderClouds(RVP_ThermobaricEffectInstance effect,
            float age, Vec3 camera, float particleRatio) {
        // window：限定后期云团从开始、开始消散到淡出结束的有效时间窗。
        StageWindow window = resolveStageWindow(effect.preset().cloudStartTick(),
                effect.preset().cloudFullTick(), effect.preset().cloudFadeDurationTicks(),
                effect.duration());
        if (!window.contains(age)) {
            return;
        }
        List<RVP_ThermobaricEffectInstance.Cloud> clouds = effect.clouds();
        boolean dynamicBudget = effect.dynamicParticleBudgetEnabled();
        RVP_ThermobaricCloudLink.AnchorPair cloudLink = effect.cloudLink();
        int coverageLimitedCount = clouds.size();
        if (dynamicBudget) {
            // 后燃云在 full tick 采样其最大视向轮廓并冻结需求，淡出期仍继续原有运动与扩散。
            coverageLimitedCount = resolveCloudCoverageLimitedCount(effect, window, age,
                    camera, clouds, cloudLink);
        }
        int renderCloudCount = RVP_ThermobaricParticleBudget.resolveRenderCount(
                dynamicBudget, clouds.size(), particleRatio, coverageLimitedCount);
        if (renderCloudCount <= 0) {
            return;
        }
        if (cloudLink != null) {
            // 有连接关系时至少保留中心与上升层两个固定锚点，避免低比例 LOD 破坏权威连续性要求。
            renderCloudCount = Math.min(clouds.size(), Math.max(2, renderCloudCount));
        }
        // animationProgress：从 cloud_start_tick 到淡出结束持续推进，只驱动上升、翻滚、卷吸与平流。
        float animationProgress = window.lifetimeProgress(age);
        // fadeProgress：只从 cloud_full_tick 开始推进，用于控制烟云扩散与透明度消散。
        float fadeProgress = window.fadeProgress(age);
        // 缓出函数映射，让云团在开始消散时维持体量，末期快速消失。
        float shapedFade = 1 - (1 - fadeProgress) * (1 - fadeProgress);
        // colorChangeProgress：按预设的绝对变色时段统一控制全部后期云团由火焰色过渡到烟色。
        float colorChangeProgress = resolveColorTransitionProgress(age,
                effect.preset().cloudColorChangeStartTick(),
                effect.preset().cloudColorChangeEndTick());
        // 调用温压渲染批次初始化方法，清空上帧排序结果并复用已有云片对象。
        beginSortedParticleClouds();
        CloudRenderState centerLinkState = null;
        CloudRenderState updraftLinkState = null;
        // cloudIndex：保持实例生成时的原始索引，供固定连接锚点取回同一云团。
        for (int cloudIndex = 0; cloudIndex < clouds.size(); cloudIndex++) {
            if (!isCloudIndexSelected(cloudIndex, renderCloudCount, clouds.size(), cloudLink)) {
                continue;
            }
            RVP_ThermobaricEffectInstance.Cloud cloud = clouds.get(cloudIndex);
            // 调用共享云团姿态计算，确保基础云团和派生连接链读取完全相同的运动与贴地结果。
            CloudRenderState state = resolveCloudRenderState(effect, cloud,
                    animationProgress, shapedFade, colorChangeProgress, camera);
            // 调用统一云片收集方法，把本团参数放入对象池并留待排序后批量绘制。
            addSortedParticleCloud(state.x(), state.y(), state.z(), state.size(),
                    state.rotation(), state.color(), state.alpha(), state.distanceSquared());
            if (cloudLink != null && cloudIndex == cloudLink.centerIndex()) {
                centerLinkState = state;
            }
            if (cloudLink != null && cloudIndex == cloudLink.updraftIndex()) {
                updraftLinkState = state;
            }
        }
        if (cloudLink != null && centerLinkState != null && updraftLinkState != null) {
            // 调用温压连接链生成逻辑，用当前帧真实端点间隙补齐高倍率下可能出现的视觉断层。
            addCloudLink(effect, cloudLink, centerLinkState, updraftLinkState,
                    animationProgress, shapedFade, colorChangeProgress, camera,
                    renderCloudCount);
        }
        // 调用统一云片提交方法，完成距离排序并一次性绘制本实例的所有后期云团。
        renderSortedParticleClouds(camera);
    }

    /** 按 full tick 之前的实际三层云团包络计算覆盖面积受限数量。 */
    private static int resolveCloudCoverageLimitedCount(
            RVP_ThermobaricEffectInstance effect, StageWindow window, float age,
            Vec3 camera, List<RVP_ThermobaricEffectInstance.Cloud> clouds,
            RVP_ThermobaricCloudLink.AnchorPair cloudLink) {
        if (clouds.isEmpty()) {
            return 0;
        }
        float budgetAge = RVP_ThermobaricParticleBudget.resolveFrozenBudgetAge(
                age, window.fullTick());
        float animationProgress = window.lifetimeProgress(budgetAge);
        float colorChangeProgress = resolveColorTransitionProgress(budgetAge,
                effect.preset().cloudColorChangeStartTick(),
                effect.preset().cloudColorChangeEndTick());
        List<CloudRenderState> budgetStates = new ArrayList<>(clouds.size());
        double horizontalRadius = 0.0D;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (RVP_ThermobaricEffectInstance.Cloud cloud : clouds) {
            // 调用共享姿态计算，以预算采样时刻的真实尺寸和运动位置建立云团包络。
            CloudRenderState state = resolveCloudRenderState(effect, cloud,
                    animationProgress, 0.0F, colorChangeProgress, camera);
            budgetStates.add(state);
            double offsetX = state.x() - effect.center().x;
            double offsetZ = state.z() - effect.center().z;
            horizontalRadius = Math.max(horizontalRadius,
                    Math.sqrt(offsetX * offsetX + offsetZ * offsetZ) + state.size());
            minimumY = Math.min(minimumY, state.y() - state.size());
            maximumY = Math.max(maximumY, state.y() + state.size());
        }
        double verticalHalfExtent = (maximumY - minimumY) * 0.5D;
        double geometryArea = RVP_ThermobaricParticleBudget.resolveCloudSilhouetteArea(
                horizontalRadius, verticalHalfExtent);
        float textureCoverage = RVP_ThermobaricParticleCoverage.effectiveCoverage();
        if (cloudLink == null) {
            return RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                    clouds.size(), geometryArea,
                    RVP_ThermobaricParticleBudget.CLOUD_OVERLAP_FACTOR,
                    index -> RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(
                            budgetStates.get(index).size(), textureCoverage));
        }
        return RVP_ThermobaricParticleBudget.resolveAnchoredCoverageLimitedCount(
                clouds.size(), geometryArea,
                RVP_ThermobaricParticleBudget.CLOUD_OVERLAP_FACTOR,
                cloudLink.centerIndex(), cloudLink.updraftIndex(),
                index -> RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(
                        budgetStates.get(index).size(), textureCoverage));
    }

    /**
     * 计算一个基础后燃云团的当前只读世界姿态；本方法不推进实例时间，也不提交 GPU 数据。
     */
    private static CloudRenderState resolveCloudRenderState(RVP_ThermobaricEffectInstance effect,
            RVP_ThermobaricEffectInstance.Cloud cloud, float animationProgress,
            float shapedFade, float colorChangeProgress, Vec3 camera) {
        // 调用温压自有云团运动曲线，以完整生命周期时钟连续推进短时低矮翻滚轨迹。
        // cloud_full_tick 不传入运动曲线，确保它只决定淡出开始时刻。
        RVP_ThermobaricCloudMotion.Motion motion = RVP_ThermobaricCloudMotion.resolve(
                cloud, animationProgress, effect.preset().cloudRiseSpeedFactor(),
                effect.preset().cloudRollSpeedFactor());
        float particleFadeProgress = resolveOuterToInnerCloudFadeProgress(
                shapedFade, cloud.radialFactor());
        float curledAngle = cloud.angle() + motion.angleOffset();
        float diffusionDistance = effect.visualRadius()
                * (0.10F + cloud.radialNoiseFactor() * 2.20F) * particleFadeProgress;
        float horizontalRadius = effect.visualRadius() * effect.preset().cloudRadiusFactor()
                * motion.radialFactor() + diffusionDistance;
        float outwardX = Mth.cos(curledAngle);
        float outwardZ = Mth.sin(curledAngle);
        float driftProgress = Mth.clamp(animationProgress
                * effect.preset().cloudRollSpeedFactor(), 0.0F, 1.0F);
        float driftScale = effect.visualRadius() * driftProgress;
        double x = effect.center().x + outwardX * horizontalRadius
                + (outwardX * cloud.outwardDrift() - outwardZ * cloud.tangentialDrift())
                * driftScale;
        //粒子尺寸
        float size = effect.visualRadius() * cloud.sizeFactor()
                * (0.72F + animationProgress * 1.38F)
                * motion.scaleMultiplier()
                * (1.0F + particleFadeProgress * 0.72F);
        float riseHeight = resolveCloudRiseHeight(effect.visualRadius(),
                effect.preset().cloudRiseFactor(), motion.riseFactor());
        float verticalOffset = effect.visualRadius() * cloud.spawnHeightFactor() + riseHeight;
        double y = effect.cloudOriginY() + verticalOffset;
        if (effect.cloudGroundAnchored()) {
            double groundSafeY = effect.cloudOriginY() + size * 0.95F;
            y = Math.max(y + size * 0.95F, groundSafeY);
        }
        double z = effect.center().z + outwardZ * horizontalRadius
                + (outwardZ * cloud.outwardDrift() + outwardX * cloud.tangentialDrift())
                * driftScale;
        float alpha = Mth.clamp(animationProgress * 8.0F, 0.0F, 1.0F)
                * (1.0F - particleFadeProgress) * 0.72F;
        int color = mixColor(effect.preset().flameColor(), effect.preset().smokeColor(),
                colorChangeProgress);
        color = scaleColor(color, cloud.brightnessFactor());
        return new CloudRenderState(x, y, z, size, cloud.rotation(), color, alpha,
                camera.distanceToSqr(x, y, z));
    }

    /** 把爆心覆盖层与中心上升层之间的实际间隙补为一条连续的派生粒子链。 */
    private static void addCloudLink(RVP_ThermobaricEffectInstance effect,
            RVP_ThermobaricCloudLink.AnchorPair cloudLink, CloudRenderState start,
            CloudRenderState end, float animationProgress, float shapedFade,
            float colorChangeProgress, Vec3 camera, int renderedBaseCloudCount) {
        double deltaX = end.x() - start.x();
        double deltaY = end.y() - start.y();
        double deltaZ = end.z() - start.z();
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        // 调用连接粒子预算计算，额外粒子最多占基础烟云数的四分之一且绝不超过 64 个。
        int particleLimit = RVP_ThermobaricCloudLink.resolveParticleLimit(
                renderedBaseCloudCount);
        // 调用连接布局计算，根据真实端点间隙决定粒子数量与保证连续覆盖所需的尺寸。
        RVP_ThermobaricCloudLink.Layout layout = RVP_ThermobaricCloudLink.resolveLayout(
                distance, start.size(), end.size(), effect.visualRadius(), particleLimit);
        if (layout.particleCount() == 0) {
            return;
        }
        float linkFadeProgress = resolveOuterToInnerCloudFadeProgress(shapedFade, 0.0F);
        float alpha = Mth.clamp(animationProgress * 8.0F, 0.0F, 1.0F)
                * (1.0F - linkFadeProgress) * 0.72F;
        int baseColor = mixColor(effect.preset().flameColor(), effect.preset().smokeColor(),
                colorChangeProgress);
        for (int particleIndex = 0; particleIndex < layout.particleCount(); particleIndex++) {
            double progress = layout.progress(particleIndex);
            double x = start.x() + deltaX * progress;
            double y = start.y() + deltaY * progress;
            double z = start.z() + deltaZ * progress;
            // 调用连接粒子稳定随机函数，只扰动贴图旋转与亮度，不改变保证连续性的空间位置。
            float rotation = RVP_ThermobaricCloudLink.resolveRotation(
                    cloudLink.visualSeed(), particleIndex);
            int color = scaleColor(baseColor, RVP_ThermobaricCloudLink.resolveBrightness(
                    cloudLink.visualSeed(), particleIndex));
            addSortedParticleCloud(x, y, z, layout.halfSize(particleIndex), rotation,
                    color, alpha, camera.distanceToSqr(x, y, z));
        }
    }

    /**
     * 选择后燃基础云团的稳定前缀，并以固定锚点替换前缀尾部候选，保证数量不超预算且连接端点可见。
     */
    static boolean isCloudIndexSelected(int cloudIndex, int renderCount, int generatedCount,
            RVP_ThermobaricCloudLink.AnchorPair cloudLink) {
        if (cloudIndex < 0 || cloudIndex >= generatedCount || renderCount <= 0) {
            return false;
        }
        if (renderCount >= generatedCount || cloudLink == null) {
            return cloudIndex < renderCount;
        }
        int centerIndex = cloudLink.centerIndex();
        int updraftIndex = cloudLink.updraftIndex();
        if (cloudIndex == centerIndex || cloudIndex == updraftIndex) {
            return renderCount >= 2;
        }
        int rankWithoutAnchors = cloudIndex;
        if (centerIndex < cloudIndex) {
            rankWithoutAnchors--;
        }
        if (updraftIndex < cloudIndex) {
            rankWithoutAnchors--;
        }
        return rankWithoutAnchors < Math.max(0, renderCount - 2);
    }

    /** 开始一个新的半透明云片批次并从对象池头部重新取用绘制数据。 */
    private static void beginSortedParticleClouds() {
        SORTED_CLOUDS.clear();
        renderCloudPoolIndex = 0;
    }

    /** 把一个云片写入可复用对象池，供当前批次统一排序和提交。 */
    private static void addSortedParticleCloud(double x, double y, double z, float size,
            float rotation, int color, float alpha, double distanceSquared) {
        RenderCloud cloud;
        if (renderCloudPoolIndex < RENDER_CLOUD_POOL.size()) {
            cloud = RENDER_CLOUD_POOL.get(renderCloudPoolIndex);
        } else {
            cloud = new RenderCloud();
            RENDER_CLOUD_POOL.add(cloud);
        }
        cloud.set(x, y, z, size, rotation, color, alpha, distanceSquared);
        SORTED_CLOUDS.add(cloud);
        renderCloudPoolIndex++;
    }

    private static void renderSortedParticleClouds(Vec3 camera) {
        if (SORTED_CLOUDS.isEmpty()) {
            return;
        }
        SORTED_CLOUDS.sort(FAR_TO_NEAR);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.setShaderTexture(0, RVP_ThermobaricParticleCoverage.PARTICLE_TEXTURE);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (RenderCloud cloud : SORTED_CLOUDS) {
            writeParticleBillboard(builder,
                    (float) (cloud.x() - camera.x),
                    (float) (cloud.y() - camera.y),
                    (float) (cloud.z() - camera.z),
                    cloud.size(), cloud.rotation(), cloud.color(), cloud.alpha());
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private static StageWindow resolveStageWindow(int configuredStartTick,
            int configuredFullTick, int configuredFadeDurationTicks, int effectEndTick) {
        int normalizedFullTick = Math.max(configuredStartTick, configuredFullTick);
        float startTick = Mth.clamp(configuredStartTick, 0, Math.max(0, effectEndTick));
        float fullTick = Mth.clamp(normalizedFullTick,
                0, Math.max(0, effectEndTick));
        float endTick = Math.min((long) normalizedFullTick + configuredFadeDurationTicks,
                Math.max(0, effectEndTick));
        return new StageWindow(startTick, fullTick, endTick);
    }

    private static void writeSphere(BufferBuilder builder, float centerX, float centerY, float centerZ,
            float radius, int color, float alpha, int ringCount, int segmentCount) {
        if (radius <= 0.0F || ringCount <= 0 || segmentCount <= 0) {
            return;
        }
        for (int ring = 0; ring < ringCount; ring++) {
            float theta0 = Mth.PI * ring / ringCount;
            float theta1 = Mth.PI * (ring + 1) / ringCount;
            for (int segment = 0; segment < segmentCount; segment++) {
                float phi0 = Mth.TWO_PI * segment / segmentCount;
                float phi1 = Mth.TWO_PI * (segment + 1) / segmentCount;
                writeSphereVertex(builder, centerX, centerY, centerZ, radius, theta0, phi0, color, alpha);
                writeSphereVertex(builder, centerX, centerY, centerZ, radius, theta1, phi0, color, alpha);
                writeSphereVertex(builder, centerX, centerY, centerZ, radius, theta1, phi1, color, alpha);
                writeSphereVertex(builder, centerX, centerY, centerZ, radius, theta0, phi1, color, alpha);
            }
        }
    }

    private static void writeSphereVertex(BufferBuilder builder, float centerX, float centerY, float centerZ,
            float radius, float theta, float phi, int color, float alpha) {
        float sinTheta = Mth.sin(theta);
        builder.vertex(
                        centerX + sinTheta * Mth.cos(phi) * radius,
                        centerY + Mth.cos(theta) * radius,
                        centerZ + sinTheta * Mth.sin(phi) * radius)
                .color(red(color), green(color), blue(color), alpha)
                .endVertex();
    }

    /**
     * 使用球面 UV 写入裁切线以下的一层四边形球壳。
     * 裁切线从球顶向球底下降时，剩余几何会连续缩短，避免用整圈跳变模拟凝结云消失。
     */
    private static void writeTexturedSphereBelow(BufferBuilder builder, float centerX, float centerY,
            float centerZ, float radius, float maximumVisibleY, int color, float alpha,
            int ringCount, int segmentCount) {
        if (radius <= 0.0F || ringCount <= 0 || segmentCount <= 0) {
            return;
        }
        float normalizedCutoff = Mth.clamp((maximumVisibleY - centerY) / radius, -1.0F, 1.0F);
        float minimumTheta = (float) Math.acos(normalizedCutoff);
        for (int ring = 0; ring < ringCount; ring++) {
            float theta0 = Mth.lerp(ring / (float) ringCount, minimumTheta, Mth.PI);
            float theta1 = Mth.lerp((ring + 1) / (float) ringCount, minimumTheta, Mth.PI);
            float v0 = 0.1F + 0.8F * theta0 / Mth.PI;
            float v1 = 0.1F + 0.8F * theta1 / Mth.PI;
            for (int segment = 0; segment < segmentCount; segment++) {
                float phi0 = Mth.TWO_PI * segment / segmentCount;
                float phi1 = Mth.TWO_PI * (segment + 1) / segmentCount;
                float u0 = 0.1F + 0.8F * segment / (float) segmentCount;
                float u1 = 0.1F + 0.8F * (segment + 1) / (float) segmentCount;
                writeTexturedSphereVertex(builder, centerX, centerY, centerZ, radius,
                        theta0, phi0, u0, v0, color, alpha);
                writeTexturedSphereVertex(builder, centerX, centerY, centerZ, radius,
                        theta1, phi0, u0, v1, color, alpha);
                writeTexturedSphereVertex(builder, centerX, centerY, centerZ, radius,
                        theta1, phi1, u1, v1, color, alpha);
                writeTexturedSphereVertex(builder, centerX, centerY, centerZ, radius,
                        theta0, phi1, u1, v0, color, alpha);
            }
        }
    }

    /** 按球面坐标和 UV 坐标写入一个纹理球壳顶点。 */
    private static void writeTexturedSphereVertex(BufferBuilder builder, float centerX,
            float centerY, float centerZ, float radius, float theta, float phi,
            float u, float v, int color, float alpha) {
        float sinTheta = Mth.sin(theta);
        builder.vertex(
                        centerX + sinTheta * Mth.cos(phi) * radius,
                        centerY + Mth.cos(theta) * radius,
                        centerZ + sinTheta * Mth.sin(phi) * radius)
                .uv(u, v)
                .color(red(color), green(color), blue(color), alpha)
                .endVertex();
    }

    /** 写入一个始终面向相机、可在相机平面内旋转的方块团粒子。 */
    private static void writeParticleBillboard(BufferBuilder builder, float x, float y, float z,
            float halfSize, float rotation, int color, float alpha) {
        float lookX = -x;
        float lookY = -y;
        float lookZ = -z;
        float length = Mth.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        if (length < 1.0E-4F) {
            lookX = 0.0F;
            lookY = 0.0F;
            lookZ = 1.0F;
        } else {
            lookX /= length;
            lookY /= length;
            lookZ /= length;
        }
        float rightX = -lookZ;
        float rightZ = lookX;
        float rightLength = Mth.sqrt(rightX * rightX + rightZ * rightZ);
        if (rightLength < 1.0E-4F) {
            rightX = 1.0F;
            rightZ = 0.0F;
        } else {
            rightX /= rightLength;
            rightZ /= rightLength;
        }
        float upX = -rightZ * lookY;
        float upY = rightZ * lookX - rightX * lookZ;
        float upZ = rightX * lookY;
        float cosine = Mth.cos(rotation);
        float sine = Mth.sin(rotation);
        float rotatedRightX = rightX * cosine + upX * sine;
        float rotatedRightY = upY * sine;
        float rotatedRightZ = rightZ * cosine + upZ * sine;
        float rotatedUpX = upX * cosine - rightX * sine;
        float rotatedUpY = upY * cosine;
        float rotatedUpZ = upZ * cosine - rightZ * sine;
        float rightHalfX = rotatedRightX * halfSize;
        float rightHalfY = rotatedRightY * halfSize;
        float rightHalfZ = rotatedRightZ * halfSize;
        float upHalfX = rotatedUpX * halfSize;
        float upHalfY = rotatedUpY * halfSize;
        float upHalfZ = rotatedUpZ * halfSize;
        float red = red(color);
        float green = green(color);
        float blue = blue(color);
        builder.vertex(x - rightHalfX - upHalfX, y - rightHalfY - upHalfY,
                        z - rightHalfZ - upHalfZ)
                .uv(0.0F, 1.0F).color(red, green, blue, alpha).endVertex();
        builder.vertex(x - rightHalfX + upHalfX, y - rightHalfY + upHalfY,
                        z - rightHalfZ + upHalfZ)
                .uv(0.0F, 0.0F).color(red, green, blue, alpha).endVertex();
        builder.vertex(x + rightHalfX + upHalfX, y + rightHalfY + upHalfY,
                        z + rightHalfZ + upHalfZ)
                .uv(1.0F, 0.0F).color(red, green, blue, alpha).endVertex();
        builder.vertex(x + rightHalfX - upHalfX, y + rightHalfY - upHalfY,
                        z + rightHalfZ - upHalfZ)
                .uv(1.0F, 1.0F).color(red, green, blue, alpha).endVertex();
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    /** 按两个绝对 tick 计算颜色过渡；相同时从该 tick 起立即使用结束颜色。 */
    static float resolveColorTransitionProgress(float age, int startTick, int endTick) {
        if (age < startTick) {
            return 0.0F;
        }
        if (endTick <= startTick) {
            return 1.0F;
        }
        return Mth.clamp((age - startTick) / (endTick - startTick), 0.0F, 1.0F);
    }

    /**
     * 把归一化上升进度按 {@code cloud_rise_factor} 换算为世界高度；
     * 配置倍率本身不做上限钳制，因此 {@code 5.0} 严格表示最高五倍视觉半径。
     */
    static float resolveCloudRiseHeight(float visualRadius, float cloudRiseFactor,
            float riseFactor) {
        double height = (double) visualRadius * Math.max(0.0F, cloudRiseFactor)
                * Mth.clamp(riseFactor, 0.0F, 1.0F);
        return height >= Float.MAX_VALUE ? Float.MAX_VALUE : (float) height;
    }

    /**
     * 按稳定径向层级计算淡出进度：最外层从淡出期起点开始，越靠内开始得越晚，
     * 每个云团从自己的开始点到淡出结束都保持线性扩散和变淡。
     */
    static float resolveOuterToInnerCloudFadeProgress(float fadeProgress, float radialFactor) {
        float normalizedFade = Mth.clamp(fadeProgress, 0.0F, 1.0F);
        float outerRank = Mth.clamp(radialFactor / 1.10F, 0.0F, 1.0F);
        float startProgress = (1.0F - outerRank) * 0.72F;
        return Mth.clamp((normalizedFade - startProgress)
                / Math.max(1.0E-4F, 1.0F - startProgress), 0.0F, 1.0F);
    }

    private static int mixColor(int from, int to, float progress) {
        int red = Math.round(Mth.lerp(progress, (from >> 16) & 0xFF, (to >> 16) & 0xFF));
        int green = Math.round(Mth.lerp(progress, (from >> 8) & 0xFF, (to >> 8) & 0xFF));
        int blue = Math.round(Mth.lerp(progress, from & 0xFF, to & 0xFF));
        return (red << 16) | (green << 8) | blue;
    }

    /** 按随机倍率调整 RGB 亮度，并保持每个分量在合法范围内。 */
    private static int scaleColor(int color, float factor) {
        int scaledRed = Mth.clamp(Math.round(((color >> 16) & 0xFF) * factor), 0, 255);
        int scaledGreen = Mth.clamp(Math.round(((color >> 8) & 0xFF) * factor), 0, 255);
        int scaledBlue = Mth.clamp(Math.round((color & 0xFF) * factor), 0, 255);
        return (scaledRed << 16) | (scaledGreen << 8) | scaledBlue;
    }

    /**
     * 计算火球淡出时某一径向层的灰化进度：外层立即开始，越靠近中心开始得越晚，
     * 所有层都在淡出结束时完成线性灰化。
     */
    static float outerToInnerGrayProgress(float fadeProgress, float radialFactor) {
        float normalizedFade = Mth.clamp(fadeProgress, 0.0F, 1.0F);
        float normalizedRadius = Mth.clamp(radialFactor, 0.0F, 1.0F);
        float startProgress = (1.0F - normalizedRadius) * 0.65F;
        return Mth.clamp((normalizedFade - startProgress)
                / Math.max(1.0E-4F, 1.0F - startProgress), 0.0F, 1.0F);
    }

    /** 按径向层把原火焰颜色线性过渡为等亮度灰色。 */
    private static int fadeToGray(int color, float fadeProgress, float radialFactor) {
        return mixColor(color, grayscaleColor(color),
                outerToInnerGrayProgress(fadeProgress, radialFactor));
    }

    /** 把 RGB 颜色转换为保持感知亮度的中性灰色。 */
    private static int grayscaleColor(int color) {
        int gray = Mth.clamp(Math.round(((color >> 16) & 0xFF) * 0.2126F
                + ((color >> 8) & 0xFF) * 0.7152F
                + (color & 0xFF) * 0.0722F), 0, 255);
        return (gray << 16) | (gray << 8) | gray;
    }

    /** 返回尘环从生成到消失期间线性递减的半宽倍率。 */
    static float resolveDustThicknessFactor(float lifetimeProgress) {
        return Mth.lerp(Mth.clamp(lifetimeProgress, 0.0F, 1.0F), 0.29F, 0.055F);
    }

    /**
     * 把尘环年龄换算为不分段的线性径向进度；full tick 为 {@code 1}，派生结束 tick 为 {@code 2}。
     */
    static float resolveDustRadialProgress(float age, int startTick, int fullTick) {
        if (fullTick <= startTick) {
            return 0.0F;
        }
        return Mth.clamp((age - startTick) / (fullTick - startTick), 0.0F, 2.0F);
    }

    /** full tick 后立即开始线性淡出；显式总寿命可提前截断淡出窗口。 */
    static float resolveDustFadeAlpha(float age, float fullTick, float endTick) {
        if (age <= fullTick) {
            return 1.0F;
        }
        if (endTick <= fullTick) {
            return 0.0F;
        }
        return 1.0F - Mth.clamp((age - fullTick) / (endTick - fullTick), 0.0F, 1.0F);
    }

    /**
     * 把压力波年龄换算为不分段的线性径向进度；full tick 为 {@code 1}，派生结束 tick 为 {@code 2}。
     */
    static float resolvePressureWaveRadialProgress(float age, int startTick, int fullTick) {
        if (fullTick <= startTick) {
            return 0.0F;
        }
        return Mth.clamp((age - startTick) / (fullTick - startTick), 0.0F, 2.0F);
    }

    /**
     * 计算粒子凝结云墙厚度：配置倍率只缩放 start tick 厚度，full tick 收敛回原有厚度。
     */
    static float resolveCondensationParticleWallThickness(float visualRadius,
            float formationProgress, float spawnThicknessFactor) {
        if (!Float.isFinite(visualRadius) || visualRadius <= 0.0F
                || !Float.isFinite(spawnThicknessFactor) || spawnThicknessFactor < 0.0F) {
            return 0.0F;
        }
        float progress = Mth.clamp(formationProgress, 0.0F, 1.0F);
        if (progress <= 0.0F) {
            return visualRadius * 1.25F * spawnThicknessFactor;
        }
        if (progress >= 1.0F) {
            // full tick 直接返回原有端点，避免 lerp 浮点消差留下倍率相关的微小误差。
            return visualRadius * 0.12F;
        }
        return visualRadius * Mth.lerp(progress,
                1.25F * spawnThicknessFactor, 0.12F);
    }

    /**
     * 计算压力波与凝结云的派生阶段透明度；速度倍率为 0 时不启用线性淡出。
     */
    static float resolvePressureWaveFadeAlpha(float age, float fullTick, float endTick,
            float fadeSpeedFactor) {
        if (age <= fullTick || !Float.isFinite(fadeSpeedFactor) || fadeSpeedFactor <= 0.0F) {
            return 1.0F;
        }
        if (endTick <= fullTick) {
            return 0.0F;
        }
        float postFullProgress = Mth.clamp((age - fullTick) / (endTick - fullTick),
                0.0F, 1.0F);
        return 1.0F - Mth.clamp(postFullProgress * fadeSpeedFactor, 0.0F, 1.0F);
    }

    /**
     * 计算凝结云在完整成形后的自顶向下裁切进度；倍率只改变裁切速度，不改变生命周期。
     */
    static float resolveCondensationCutProgress(float age, float fullTick, float endTick,
            float cutSpeedFactor) {
        if (age <= fullTick || endTick <= fullTick || !Float.isFinite(cutSpeedFactor)
                || cutSpeedFactor <= 0.0F) {
            return 0.0F;
        }
        float postFullProgress = Mth.clamp((age - fullTick) / (endTick - fullTick),
                0.0F, 1.0F);
        return Mth.clamp(postFullProgress * cutSpeedFactor, 0.0F, 1.0F);
    }

    private static float red(int color) {
        return ((color >> 16) & 0xFF) / 255.0F;
    }

    private static float green(int color) {
        return ((color >> 8) & 0xFF) / 255.0F;
    }

    private static float blue(int color) {
        return (color & 0xFF) / 255.0F;
    }

    /**
     * 一个阶段的“开始、到达阶段切换点、消失”时间窗。
     *
     * @param startTick 相对整个效果起点的绝对开始 tick
     * @param fullTick 相对整个效果起点的绝对阶段切换 tick；对后燃烟云始终表示开始消散，
     *                 实验预算开启时还表示基础粒子数达到完整容量
     * @param endTick 阶段切换后经过淡出持续时间得到的绝对结束 tick
     */
    private record StageWindow(float startTick, float fullTick, float endTick) {
        private boolean contains(float age) {
            return endTick > startTick && age >= startTick && age < endTick;
        }

        private float formationProgress(float age) {
            return Mth.clamp((age - startTick) / Math.max(1.0F, fullTick - startTick),
                    0.0F, 1.0F);
        }

        private float fadeAlpha(float age) {
            if (age <= fullTick) {
                return 1.0F;
            }
            return 1.0F - Mth.clamp((age - fullTick) / Math.max(1.0F, endTick - fullTick),
                    0.0F, 1.0F);
        }

        private float fadeProgress(float age) {
            return 1.0F - fadeAlpha(age);
        }

        private float lifetimeProgress(float age) {
            return Mth.clamp((age - startTick) / Math.max(1.0F, endTick - startTick),
                    0.0F, 1.0F);
        }

        private float formationDuration() {
            return Math.max(0.0F, fullTick - startTick);
        }

    }

    /**
     * 单个基础后燃云团在当前渲染帧的只读姿态。
     *
     * @param x 云团中心世界 X 坐标
     * @param y 云团中心世界 Y 坐标
     * @param z 云团中心世界 Z 坐标
     * @param size billboard 半边长
     * @param rotation billboard 在相机平面内的旋转弧度
     * @param color 当前 RGB 颜色
     * @param alpha 当前透明度
     * @param distanceSquared 到相机的距离平方
     */
    private record CloudRenderState(double x, double y, double z, float size,
                                    float rotation, int color, float alpha,
                                    double distanceSquared) {
    }

    /** 单帧半透明云团的可复用绘制数据。 */
    private static final class RenderCloud {
        /** 云片世界 X 坐标。 */
        private double x;
        /** 云片世界 Y 坐标。 */
        private double y;
        /** 云片世界 Z 坐标。 */
        private double z;
        /** 云片半边长。 */
        private float size;
        /** 云片在相机平面内的旋转弧度。 */
        private float rotation;
        /** 云片 RGB 颜色。 */
        private int color;
        /** 云片透明度。 */
        private float alpha;
        /** 云片到相机的距离平方。 */
        private double distanceSquared;

        private void set(double x, double y, double z, float size, float rotation,
                int color, float alpha, double distanceSquared) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
            this.rotation = rotation;
            this.color = color;
            this.alpha = alpha;
            this.distanceSquared = distanceSquared;
        }

        private double x() {
            return x;
        }

        private double y() {
            return y;
        }

        private double z() {
            return z;
        }

        private float size() {
            return size;
        }

        private float rotation() {
            return rotation;
        }

        private int color() {
            return color;
        }

        private float alpha() {
            return alpha;
        }

        private double distanceSquared() {
            return distanceSquared;
        }
    }
}
