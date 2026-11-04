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
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.InternalAssets;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.resource.BedrockModelLoader;

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
        BedrockModel model = BedrockModelLoader.getModel(InternalAssets.BASIC_BULLET_MODEL);
        poseStack.pushPose();
        // 调用本项目弹体视觉口径入口：曳光宽度使用 tracer_caliber，不混用炮口烟/物理口径 caliber。
        float width = Math.min(0.04f * bullet.getTracerCaliber() / 7.62f, 0.2f);
        Vec3 bulletPosition = bullet.getPosition(partialTicks);
        double disToEye = bulletPosition.distanceTo(bullet.getStartPos());
        // 曳光长度 = 0.3 × 当前速度 × effects_data.tracer_length_scale，上限 = 已飞距离 × 0.8
        double trailLength = RVP_EffectsData.resolveTracerLength(bullet.getDeltaMovement().length(), disToEye,
                bullet.getTracerLengthScale());
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, bullet.yRotO, bullet.getYRot()) - 180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partialTicks, bullet.xRotO, bullet.getXRot())));
        poseStack.translate(0, 0, trailLength / 2.0);
        poseStack.scale(width, width, (float) trailLength);
        if (disToEye > 1.0) {
            RenderType type = RenderType.energySwirl(InternalAssets.BASIC_BULLET_TEXTURE, 15, 15);
            VertexConsumer builder = bufferSource.getBuffer(type);
            model.renderToBuffer(poseStack, builder, packedLight, OverlayTexture.NO_OVERLAY,
                    bullet.getTracerR(), bullet.getTracerG(), bullet.getTracerB(), 1f);
        }
        poseStack.popPose();
    }

    static void renderBedrockProjectile(AmmoEntity ammo, float entityYaw, float partialTick, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight,
                                        ResourceLocation fallbackModel, ResourceLocation fallbackTexture) {
        poseStack.pushPose();
        Vec3 root = Vec3.ZERO;
        // 调用专用显示归属门，虚拟显示已经统一插值，模型不能再比喷口/HUD 多平滑一次。
        boolean virtualVisual = org.ywzj.rvp.client.state.remotevisibility.RVP_ClientVirtualMissileVisualState.owned(ammo) != null;
        RotationSample sample = virtualVisual ? new RotationSample(ammo.getYRot(), ammo.getXRot())
                : sampleSmoothedRotation(ammo, entityYaw, partialTick);
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
