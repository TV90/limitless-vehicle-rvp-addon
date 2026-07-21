package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.render.RVP_CockpitPassengerRenderer;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.rvp.client.resource.RVP_DisplayTransparentModeManager;
import org.ywzj.vehicle.client.render.ModRenderTypes;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RVP_VehicleBedrockModel extends VehicleBedrockModel {

    public record RvpSpecialBoneEntry(BedrockBone bone, SpecialBoneEffect effect) {}

    private final Map<String, SpecialBoneEffect> rvpSpecialBoneMap = new HashMap<>();
    private final List<RvpSpecialBoneEntry> rvpSpecialBoneEntries = new ArrayList<>();

    public RVP_VehicleBedrockModel(BedrockModelPOJO pojo, List<SpecialBoneEffect> specialBoneEffects) {
        super(pojo, List.of());
        if (specialBoneEffects != null) {
            for (SpecialBoneEffect effect : specialBoneEffects) {
                if (effect == null || !effect.isValid()) {
                    continue;
                }
                rvpSpecialBoneMap.put(effect.bone, effect);
                BedrockBone bone = getBone(effect.bone);
                if (bone != null) {
                    rvpSpecialBoneEntries.add(new RvpSpecialBoneEntry(bone, effect));
                }
            }
        }
    }

    @Override
    public void setSpecialBoneVisible(boolean visible) {
        for (RvpSpecialBoneEntry entry : rvpSpecialBoneEntries) {
            entry.bone().visible = visible;
        }
    }

    @Override
    public Map<String, SpecialBoneEffect> getSpecialBoneMap() {
        return rvpSpecialBoneMap;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    @ParametersAreNonnullByDefault
    public void renderToBuffer(PoseStack poseStack, MultiBufferSource bufferSource, ResourceLocation texture, int packedLight) {
        setSpecialBoneVisible(false);
        super.renderToBuffer(
                poseStack,
                bufferSource,
                RenderType.entityCutout(texture),
                RVP_RenderTypes.polyMeshCutout(texture),
                packedLight,
                OverlayTexture.pack(0f, false)
        );
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    @ParametersAreNonnullByDefault
    public void renderSpecialBones(PoseStack poseStack, MultiBufferSource source, int packedLight, int packedOverlay, boolean isLocalPlayerVehicle) {
        if (rvpSpecialBoneEntries.isEmpty()) {
            return;
        }
        setSpecialBoneVisible(true);
        RVP_CockpitPassengerRenderer.renderLocalPassengerBeforeCockpit(this, poseStack, source, packedLight);
        for (RvpSpecialBoneEntry entry : rvpSpecialBoneEntries) {
            BedrockBone bone = entry.bone();
            SpecialBoneEffect effect = entry.effect();
            boolean cockpitDepthFix = RVP_DisplayTransparentModeManager.INSTANCE.isCockpitDepthFix(this, effect.bone);
            VertexConsumer buffer;
            if (bone.hasCubesInTree()) {
                switch (effect.type) {
                    case MUZZLE_FLASH ->
                            buffer = source.getBuffer(ModRenderTypes.muzzleFlash(effect.texture));
                    case TRANSPARENT ->
                            buffer = source.getBuffer(cockpitDepthFix
                                    ? RVP_RenderTypes.cubeCockpitTransparent(effect.texture)
                                    : RVP_RenderTypes.cubeTransparent(effect.texture));
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
                                    : RVP_RenderTypes.polyMeshTransparent(effect.texture));
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
