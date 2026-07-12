package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.ywzj.rvp.client.resource.RVP_DisplayTransparentModeManager;
import org.ywzj.rvp.client.render.RVP_CockpitPassengerRenderer;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.vehicle.client.render.ModRenderTypes;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

@Mixin(value = VehicleBedrockModel.class, remap = false)
public abstract class VehicleBedrockModelCockpitRenderMixin {

    @Shadow(remap = false)
    private List<VehicleBedrockModel.SpecialBoneEntry> specialBoneEntries;

    @Shadow(remap = false)
    public abstract void setSpecialBoneVisible(boolean visible);

    /**
     * @author Codex
     * @reason Keep cockpit glass depth-tested but stop it from writing depth so cockpit occupants are not culled behind a single translucent shell.
     */
    @Overwrite(remap = false)
    @OnlyIn(Dist.CLIENT)
    @ParametersAreNonnullByDefault
    public void renderSpecialBones(PoseStack poseStack, MultiBufferSource source, int packedLight, int packedOverlay, boolean isLocalPlayerVehicle) {
        if (specialBoneEntries.isEmpty()) {
            return;
        }
        VehicleBedrockModel self = (VehicleBedrockModel) (Object) this;
        setSpecialBoneVisible(true);
        RVP_CockpitPassengerRenderer.renderLocalPassengerBeforeCockpit(self, poseStack, source, packedLight);
        for (var entry : specialBoneEntries) {
            BedrockBone bone = entry.bone();
            SpecialBoneEffect effect = entry.effect();
            boolean cockpitDepthFix = RVP_DisplayTransparentModeManager.INSTANCE.isCockpitDepthFix(self, effect.bone);
            VertexConsumer buffer;
            if (bone.hasCubesInTree()) {
                switch (effect.type) {
                    case MUZZLE_FLASH ->
                            buffer = source.getBuffer(ModRenderTypes.muzzleFlash(effect.texture));
                    case TRANSPARENT ->
                            buffer = source.getBuffer(cockpitDepthFix
                                    ? RVP_RenderTypes.cubeCockpitTransparent(effect.texture)
                                    : ModRenderTypes.cubeTransparent(effect.texture));
                    case COCKPIT -> {
                        if (isLocalPlayerVehicle && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR) {
                            continue;
                        }
                        buffer = source.getBuffer(RVP_RenderTypes.cubeCockpitTransparent(effect.texture));
                    }
                    default -> {
                        continue;
                    }
                }
                poseStack.pushPose();
                poseStack.mulPoseMatrix(VehicleBedrockModel.getGlobalTransform(bone));
                bone.render(poseStack, buffer, packedLight, packedOverlay);
                poseStack.popPose();
            } else if (bone.hasMeshesInTree()) {
                switch (effect.type) {
                    case MUZZLE_FLASH ->
                            buffer = source.getBuffer(ModRenderTypes.muzzleFlash(effect.texture));
                    case TRANSPARENT ->
                            buffer = source.getBuffer(cockpitDepthFix
                                    ? RVP_RenderTypes.polyMeshCockpitTransparent(effect.texture)
                                    : ModRenderTypes.polyMeshTransparent(effect.texture));
                    case COCKPIT -> {
                        if (isLocalPlayerVehicle && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR) {
                            continue;
                        }
                        buffer = source.getBuffer(RVP_RenderTypes.polyMeshCockpitTransparent(effect.texture));
                    }
                    default -> {
                        continue;
                    }
                }
                poseStack.pushPose();
                poseStack.mulPoseMatrix(VehicleBedrockModel.getGlobalTransform(bone));
                bone.renderMeshes(poseStack, buffer, packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
                poseStack.popPose();
            }
        }
    }
}
