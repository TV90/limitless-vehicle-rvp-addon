package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.ywzj.rvp.client.render.RVP_CockpitPassengerRenderer;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.rvp.client.resource.RVP_DisplayTransparentModeManager;
import org.ywzj.rvp.client.resource.vehicle.RVP_DisplayBackendUtil;
import org.ywzj.vehicle.client.render.ModRenderTypes;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.List;

@Mixin(value = VehicleBedrockModel.class, remap = false)
public abstract class VehicleBedrockModelCockpitRenderMixin {

    @Shadow(remap = false)
    private List<VehicleBedrockModel.BakedSpecialBoneEntry> bakedSpecialBoneEntries;

    @Shadow(remap = false)
    public abstract BakedBedrockModel getBakedModel();

    /**
     * @author Codex
     * @reason Keep RVP transparent/cockpit rendering on top of the latest baked SBM pipeline while letting non-RVP displays use the base render behavior.
     */
    @Overwrite(remap = false)
    @OnlyIn(Dist.CLIENT)
    public void renderSpecialBonesBaked(BakedModelInstance instance,
                                        PoseStack poseStack,
                                        MultiBufferSource source,
                                        int packedLight,
                                        int packedOverlay,
                                        boolean isLocalPlayerVehicle) {
        BakedBedrockModel bakedModel = getBakedModel();
        if (bakedModel == null || bakedSpecialBoneEntries.isEmpty()) {
            return;
        }
        VehicleBedrockModel self = (VehicleBedrockModel) (Object) this;
        boolean rvpBackend = RVP_DisplayBackendUtil.isRvpBackend(self);
        boolean hasCockpitDepthFix = rvpBackend && RVP_DisplayTransparentModeManager.INSTANCE.hasCockpitDepthFix(self);
        for (VehicleBedrockModel.BakedSpecialBoneEntry entry : bakedSpecialBoneEntries) {
            BoneState bone = instance.getBone(entry.boneIndex());
            if (bone != null) {
                bone.visible = true;
            }
        }
        if (hasCockpitDepthFix) {
            RVP_CockpitPassengerRenderer.renderLocalPassengerBeforeCockpit(self, poseStack, source, packedLight);
        }
        for (VehicleBedrockModel.BakedSpecialBoneEntry entry : bakedSpecialBoneEntries) {
            SpecialBoneEffect effect = entry.effect();
            if (effect.type == SpecialBoneEffect.SpecialBoneEffectType.COCKPIT
                    && isLocalPlayerVehicle
                    && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR) {
                continue;
            }
            boolean cockpitDepthFix = hasCockpitDepthFix
                    && RVP_DisplayTransparentModeManager.INSTANCE.isCockpitDepthFix(self, effect.bone);
            RenderType quadType;
            RenderType meshType;
            switch (effect.type) {
                case MUZZLE_FLASH -> {
                    quadType = ModRenderTypes.muzzleFlash(effect.texture);
                    meshType = ModRenderTypes.muzzleFlash(effect.texture);
                }
                case TRANSPARENT -> {
                    if (rvpBackend) {
                        quadType = cockpitDepthFix
                                ? RVP_RenderTypes.cubeCockpitTransparent(effect.texture)
                                : RVP_RenderTypes.cubeTransparent(effect.texture);
                        meshType = cockpitDepthFix
                                ? RVP_RenderTypes.polyMeshCockpitTransparent(effect.texture)
                                : RVP_RenderTypes.polyMeshTransparent(effect.texture);
                    } else {
                        quadType = ModRenderTypes.cubeTransparent(effect.texture);
                        meshType = ModRenderTypes.polyMeshTransparent(effect.texture);
                    }
                }
                case COCKPIT -> {
                    if (rvpBackend) {
                        quadType = RVP_RenderTypes.cubeCockpitTransparent(effect.texture);
                        meshType = RVP_RenderTypes.polyMeshCockpitTransparent(effect.texture);
                    } else {
                        quadType = ModRenderTypes.cubeTransparent(effect.texture);
                        meshType = ModRenderTypes.polyMeshTransparent(effect.texture);
                    }
                }
                default -> {
                    continue;
                }
            }
            BoneState bone = instance.getBone(entry.boneIndex());
            if (bone == null) {
                continue;
            }
            int parentIndex = bone.parentIndex();
            poseStack.pushPose();
            try {
                if (parentIndex >= 0) {
                    poseStack.mulPoseMatrix(instance.getGlobalTransform(parentIndex));
                }
                bakedModel.renderBone(instance, entry.boneIndex(), poseStack, source.getBuffer(quadType), packedLight,
                        packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F, true);
                bakedModel.renderBone(instance, entry.boneIndex(), poseStack, source.getBuffer(meshType), packedLight,
                        packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F, false);
            } finally {
                poseStack.popPose();
            }
        }
    }
}
