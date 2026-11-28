package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.InternalAssets;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.resource.BedrockModelLoader;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import static org.ywzj.vehicle.client.render.animation.util.PoseBlenders.BLENDER;

/**
 * Same draw rules as {@link org.ywzj.vehicle.client.render.entity.weapon} projectile renderers,
 * for {@link AmmoEntity} / {@link RVP_BulletEntity} (no cast to {@link org.ywzj.vehicle.entity.weapon.BulletEntity}).
 */
final class VehicleProjectileRenderLogic {

    private static final float PROJECTILE_RENDER_ROT_SMOOTH = 0.35F;
    private static final Map<AmmoEntity, RotationSmoother> ROTATION_SMOOTHERS = new WeakHashMap<>();

    private VehicleProjectileRenderLogic() {}

    static void renderBullet(RVP_BulletEntity bullet, float partialTicks, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight) {
        // 调用共用曳光绘制，机炮弹沿用既有口径和颜色。
        double flown = bullet.getPosition(partialTicks).distanceTo(bullet.getStartPos());
        drawTracer(bullet.getDeltaMovement(), poseStack, bufferSource, packedLight, flown,
                bullet.getTracerCaliber(), bullet.getTracerLengthScale(),
                bullet.getTracerR(), bullet.getTracerG(), bullet.getTracerB());
        // 原机枪型弹体只绘制曳光；固定火炮显式提供 display 时还需绘制模型。
        ResourceLocation weaponId = bullet.getWeaponId();
        if (weaponId != null && ClientAssetsManager.INSTANCE.getWeaponDisplay(weaponId)
                .map(display -> display.getModel() != null && display.getTexture() != null)
                .orElse(false)) {
            renderBedrockProjectile(bullet, bullet.getYRot(), partialTicks, poseStack, bufferSource,
                    packedLight, InternalAssets.BASIC_BULLET_MODEL, InternalAssets.BASIC_BULLET_TEXTURE);
        }
    }

    private static void drawTracer(Vec3 velocity, PoseStack poseStack,
                                   MultiBufferSource bufferSource, int packedLight, double flown,
                                   float caliber, float lengthScale, float red, float green, float blue) {
        if (lengthScale <= 0f || !(flown > 1.0)) {
            return;
        }
        double speed = velocity.length();
        if (!Double.isFinite(speed) || speed <= 1.0E-6) {
            return;
        }
        // 调用本项目弹体视觉口径入口：曳光宽度使用 tracer_caliber，不混用炮口烟/物理口径 caliber。
        float width = Math.min(0.04f * caliber / 7.62f, 0.2f);
        // 曳光长度 = 0.3 × 当前速度 × effects_data.tracer_length_scale，上限 = 已飞距离 × 0.8
        double trailLength = RVP_EffectsData.resolveTracerLength(speed, flown, lengthScale);
        BedrockModel model = BedrockModelLoader.getModel(InternalAssets.BASIC_BULLET_MODEL);
        poseStack.pushPose();
        try {
            // 模型局部 +Z 延伸到弹后方。近距实体和远距克隆统一用这一帧的飞行速度对齐。
            poseStack.mulPose(tracerRotation(velocity, speed));
            poseStack.translate(0, 0, trailLength / 2.0);
            poseStack.scale(width, width, (float) trailLength);
            RenderType type = RenderType.energySwirl(InternalAssets.BASIC_BULLET_TEXTURE, 15, 15);
            VertexConsumer builder = bufferSource.getBuffer(type);
            model.renderToBuffer(poseStack, builder, packedLight, OverlayTexture.NO_OVERLAY,
                    red, green, blue, 1f);
        } finally {
            poseStack.popPose();
        }
    }

    /** 从已验证的非零弹速计算尾迹旋转，让局部 +Z 指向速度反方向。 */
    static Quaternionf tracerRotation(Vec3 velocity, double speed) {
        return new Quaternionf().rotationTo(0, 0, 1,
                (float) (-velocity.x / speed), (float) (-velocity.y / speed), (float) (-velocity.z / speed));
    }

    static void renderBedrockProjectile(AmmoEntity ammo, float entityYaw, float partialTick, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight,
                                        ResourceLocation fallbackModel, ResourceLocation fallbackTexture) {
        if (ammo instanceof RVP_RocketEntity rocket) {
            // 调用弹药配置查询。仅无动力曲射弹同时配置粒径和长度时绘制曳光。
            var data = rocket.getResolvedWeaponConfig();
            if (data != null && !data.getProjectileData().hasRocketEngine()) {
                var effects = data.getEffectsData();
                if (effects.hasTracerCaliberOverride() && effects.hasTracerLengthScaleOverride()) {
                    // 曲射弹不保存机枪弹的出生点。以起飞帧速度估算初始曳光长度上限。
                    double flown = rocket.getDeltaMovement().length() * (rocket.tickCount + partialTick);
                    drawTracer(rocket.getDeltaMovement(), poseStack, bufferSource, packedLight, flown,
                            effects.getTracerCaliber(), effects.getTracerLengthScale(),
                            effects.getTracerR(), effects.getTracerG(), effects.getTracerB());
                }
            }
        }
        poseStack.pushPose();
        Vec3 root = Vec3.ZERO;
        // 调用专用显示归属门，虚拟显示已经统一插值，模型不能再比喷口/HUD 多平滑一次。
        boolean virtualVisual = org.ywzj.rvp.client.state.remotevisibility.RVP_ClientVirtualMissileVisualState.owned(ammo) != null;
        RotationSample sample = sampleProjectileRotation(ammo, entityYaw, partialTick, virtualVisual);
        poseStack.rotateAround(Axis.YP.rotationDegrees(-sample.yaw()),
                (float) root.x, (float) root.y, (float) root.z);
        poseStack.rotateAround(Axis.XP.rotationDegrees(sample.pitch()),
                (float) root.x, (float) root.y, (float) root.z);

        ResourceLocation texture = null;
        ResourceLocation weaponId = ammo.getWeaponId();
        VehicleBedrockModel ammoModel = null;
        // 保存当前 display，独立显示对象没有生成包，绘制层负责创建专用模型运行时。
        BaseDisplay currentDisplay = null;
        if (weaponId != null) {
            Optional<BaseDisplay> weaponDisplayOptional = ClientAssetsManager.INSTANCE.getWeaponDisplay(weaponId);
            if (weaponDisplayOptional.isPresent()) {
                BaseDisplay weaponDisplay = weaponDisplayOptional.get();
                currentDisplay = weaponDisplay;
                if (weaponDisplay.getModel() != null) {
                    ammoModel = weaponDisplay.getModel();
                }
                if (weaponDisplay.getTexture() != null) {
                    texture = weaponDisplay.getTexture();
                }
            }
        }
        if (texture == null) {
            texture = fallbackTexture;
        }

        // v2 烘焙路径（sbmv2）：与 AmmoEntityRenderer.render() 一致
        BakedModelInstance modelInstance = ammo.getModelInstance();
        // 调用虚拟弹显示模型缓存；资源暂缺时跳过，避免把 v2 模型按不完整的 v1 路径绘制。
        RVP_VirtualMissileModelRuntime.Runtime visualRuntime = virtualVisual
                ? RVP_VirtualMissileModelRuntime.resolve(ammo, currentDisplay) : null;
        if (virtualVisual && ammoModel != null && ammoModel.hasBakedModel() && visualRuntime == null) {
            poseStack.popPose();
            return;
        }
        if (visualRuntime != null) {
            // 调用独立动画更新，同一游戏刻多次绘制不重复推进，所有弹体使用各自的烘焙实例。
            visualRuntime.updatePose();
            try {
                ammoModel.renderToBuffer(visualRuntime.instance, poseStack, bufferSource, texture, packedLight);
            } finally {
                visualRuntime.instance.applyPose(visualRuntime.instance.getBindPose());
            }
        } else if (ammoModel != null && ammoModel.hasBakedModel() && modelInstance != null) {
            var runner = ammo.getAnimationRunner();
            if (runner != null) {
                runner.tick();
                modelInstance.applyPose(BLENDER.blend(modelInstance.getBindPose(), runner.evaluate()));
            }
            ammoModel.renderToBuffer(modelInstance, poseStack, bufferSource, texture, packedLight);
        } else if (ammoModel != null && ammoModel.hasBakedModel()) {
            // 机枪弹不预建弹体实例；按帧创建一次独立姿态，避免退回不兼容的 v1 网格路径。
            BakedModelInstance temporaryInstance = ammoModel.createBakedInstance();
            ammoModel.renderToBuffer(temporaryInstance, poseStack, bufferSource, texture, packedLight);
        } else {
            // fallback: v1 路径（无烘焙模型时，如 fallbackModel）
            BedrockModel model = ammoModel != null ? ammoModel : BedrockModelLoader.getModel(fallbackModel);
            var runner = ammo.getAnimationRunner();
            if (runner != null) {
                runner.tick();
                model.applyPose(BLENDER.blend(model.getBindPose(), runner.evaluate()));
            }
            model.renderToBuffer(poseStack, bufferSource,
                    RenderType.entityCutout(texture),
                    RVP_RenderTypes.polyMeshCutout(texture),
                    packedLight,
                    OverlayTexture.pack(0f, false));
            model.applyPose(model.getBindPose());
        }
        poseStack.popPose();
    }

    private static RotationSample sampleProjectileRotation(AmmoEntity ammo, float entityYaw, float partialTick,
                                                            boolean virtualVisual) {
        Vec3 velocity = ammo.getDeltaMovement();
        if (ammo instanceof RVP_BulletEntity && velocity.lengthSqr() > 1.0E-12) {
            // 调用本体速度换角工具，让显式配置的子弹模型和曳光沿同一条轨迹。
            var flightRotation = VectorUtil.vecToRot(velocity);
            return new RotationSample(flightRotation.y, flightRotation.x);
        }
        return virtualVisual ? new RotationSample(ammo.getYRot(), ammo.getXRot())
                : sampleSmoothedRotation(ammo, entityYaw, partialTick);
    }

    private static RotationSample sampleSmoothedRotation(AmmoEntity ammo, float entityYaw, float partialTick) {
        float targetYaw = entityYaw;
        float targetPitch = Mth.rotLerp(partialTick, ammo.xRotO, ammo.getXRot());
        RotationSmoother smoother = ROTATION_SMOOTHERS.computeIfAbsent(ammo, key -> new RotationSmoother(targetYaw, targetPitch));
        return smoother.sample(targetYaw, targetPitch);
    }

    private record RotationSample(float yaw, float pitch) {}

    private static final class RotationSmoother {
        private float yaw;
        private float pitch;
        private boolean initialized;

        private RotationSmoother(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.initialized = false;
        }

        private RotationSample sample(float targetYaw, float targetPitch) {
            if (!initialized) {
                yaw = targetYaw;
                pitch = targetPitch;
                initialized = true;
            } else {
                yaw = Mth.rotLerp(PROJECTILE_RENDER_ROT_SMOOTH, yaw, targetYaw);
                pitch = Mth.rotLerp(PROJECTILE_RENDER_ROT_SMOOTH, pitch, targetPitch);
            }
            return new RotationSample(yaw, pitch);
        }
    }
}
