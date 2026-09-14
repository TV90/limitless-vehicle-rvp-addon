package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import com.maydaymemory.mae.control.runner.AnimationContext;
import com.maydaymemory.mae.control.runner.AnimationRunner;
import com.maydaymemory.mae.control.runner.LoopingState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.InternalAssets;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import static org.ywzj.vehicle.client.render.animation.util.PoseBlenders.BLENDER;

/**
 * Bedrock projectile draw for RVP {@link AmmoEntity} subclasses; fallback IDs match
 * {@link org.ywzj.vehicle.client.render.entity.weapon.MissileEntityRenderer} /
 * {@link org.ywzj.vehicle.client.render.entity.weapon.RocketEntityRenderer} /
 * {@link org.ywzj.vehicle.client.render.entity.weapon.AerialBombEntityRenderer}.
 */
public class RVP_BedrockProjectileEntityRenderer<T extends AmmoEntity> extends EntityRenderer<T> {

    private final ResourceLocation fallbackModel;
    private final ResourceLocation fallbackTexture;

    /**
     * [RVP] 尾焰动画 runner 缓存（客户端渲染线程访问）：每发弹客户端实例一个循环 runner，
     * 复用本体 {@code InternalAssets} 的火箭尾焰三件套（模型/动画/贴图，全局共享资源）。
     * 弱引用——弹体销毁后条目自动清理。不挂 RVP_BaseBullet 基类（避免公共类引客户端类型）。
     */
    private static final Map<RVP_BaseBullet, AnimationRunner> FLAME_RUNNERS = new WeakHashMap<>();
    /** 尾焰默认喷口偏移（实体空间，Z- 为弹尾）：未配置 engine_nozzle_offset 时的默认值。 */
    private static final Vec3 DEFAULT_NOZZLE_OFFSET = new Vec3(0.0D, 0.0D, -0.5D);
    /** 尾焰默认缩放：未配置 flame_scale 时的默认值（对标本体 PL-12：caliber 未配置钳制 200 → 200/1000）。 */
    private static final float DEFAULT_FLAME_SCALE = 0.2f;

    public RVP_BedrockProjectileEntityRenderer(EntityRendererProvider.Context context,
                                               ResourceLocation fallbackModel,
                                               ResourceLocation fallbackTexture) {
        super(context);
        this.fallbackModel = fallbackModel;
        this.fallbackTexture = fallbackTexture;
    }

    @Override
    public void render(T ammo, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        if (ammo instanceof RVP_BaseBullet projectile && projectile.isParticleProjectileVisual()) {
            return;
        }
        // 调用本项目通用弹体绘制逻辑：非粒子模式继续使用 display Bedrock 模型或类型 fallback。
        VehicleProjectileRenderLogic.renderBedrockProjectile(
                ammo, entityYaw, partialTick, poseStack, bufferSource, packedLight, fallbackModel, fallbackTexture);
        // [RVP] 目的：带火箭发动机的弹在燃烧期渲染尾焰动画（复用本体 InternalAssets 三件套，
        // 与本体 MissileEntity/RocketEntity 同款观感）；渲染器侧自建 runner，不依赖本体导弹基类。
        if (ammo instanceof RVP_BaseBullet projectile) {
            renderMotorFlame(projectile, entityYaw, partialTick, poseStack, bufferSource);
        }
    }

    /**
     * [RVP] 尾焰渲染：{@code has_rocket_engine} 且发动机燃烧中时，按本体
     * {@code RocketPropelledEntityRenderer} 同款变换（实体 yaw/xRot 旋转 → 喷口平移 →
     * 弹径缩放 → 全亮半透明渲染）绘制循环尾焰动画。
     */
    private void renderMotorFlame(RVP_BaseBullet projectile, float entityYaw, float partialTick,
                                  PoseStack poseStack, MultiBufferSource bufferSource) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null || !data.getProjectileData().hasRocketEngine() || !projectile.isMotorBurningNow()) {
            FLAME_RUNNERS.remove(projectile);
            return;
        }
        InternalAssets assets = ClientAssetsManager.INSTANCE.getInternalAssets();
        VehicleBedrockModel flameModel = assets.getRocketMotorFlameModel();
        BedrockAnimation flameAnimation = assets.getRocketMotorFlameAnimation();
        if (flameModel == null || flameAnimation == null) {
            return;
        }
        AnimationRunner runner = FLAME_RUNNERS.get(projectile);
        if (runner == null) {
            runner = new AnimationRunner(flameAnimation,
                    new AnimationContext(flameAnimation.getSpecifiedEndTimeS()));
            runner.setState(new LoopingState(System::nanoTime));
            FLAME_RUNNERS.put(projectile, runner);
        }
        runner.tick();
        flameModel.applyPose(BLENDER.blend(flameModel.getBindPose(), runner.evaluate()));
        poseStack.pushPose();
        try {
            Vec3 root = Vec3.ZERO;
            poseStack.rotateAround(Axis.YP.rotationDegrees(-entityYaw),
                    (float) root.x, (float) root.y, (float) root.z);
            poseStack.rotateAround(Axis.XP.rotationDegrees(Mth.lerp(partialTick, projectile.xRotO, projectile.getXRot())),
                    (float) root.x, (float) root.y, (float) root.z);
            // 目的：逐弹可调——喷口偏移与尾焰缩放优先取 projectile_data 配置，未配置走默认值
            float[] nozzle = data.getProjectileData().getEngineNozzleOffset();
            Float flameScale = data.getProjectileData().getFlameScale();
            if (nozzle != null) {
                poseStack.translate(nozzle[0], nozzle[1], nozzle[2]);
            } else {
                poseStack.translate(DEFAULT_NOZZLE_OFFSET.x, DEFAULT_NOZZLE_OFFSET.y, DEFAULT_NOZZLE_OFFSET.z);
            }
            float scale = flameScale != null && flameScale > 0f ? flameScale : DEFAULT_FLAME_SCALE;
            poseStack.scale(scale, scale, scale);
            flameModel.renderToBuffer(poseStack, bufferSource,
                    RenderType.entityTranslucent(InternalAssets.ROCKET_MOTOR_FLAME_TEXTURE),
                    BedrockModelRenderTypes.polyMeshCutout(InternalAssets.ROCKET_MOTOR_FLAME_TEXTURE),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } finally {
            poseStack.popPose();
            flameModel.applyPose(flameModel.getBindPose());
        }
    }

    @Override
    public boolean shouldRender(T entity, Frustum camera, double camX, double camY, double camZ) {
        if (entity instanceof RVP_MissileEntity && RVP_ClientHitlState.shouldHideActiveMissileVfx(entity)) {
            return false;
        }
        return true;
    }

    @Override
    protected int getBlockLightLevel(@NotNull T entity, @NotNull BlockPos blockPos) {
        return 15;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return null;
    }
}
