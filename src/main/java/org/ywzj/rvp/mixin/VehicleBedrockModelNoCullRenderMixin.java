package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.rvp.client.resource.vehicle.RVP_DisplayBackendUtil;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.util.Set;

/**
 * 为 RVP 载具提供"指定骨骼不启用单面剔除"的能力。
 * <p>
 * display JSON 中配置 {@code "no_cull_bones": ["boneName", ...]} 后：
 * 主体仍用带剔除（CULL）的渲染类型正常绘制，随后用 NO_CULL 变体单独补画这些骨骼，
 * 使其薄片/垂尾等内侧面不再被剔除（可解决一侧黑、一侧被剔除的问题）。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = VehicleBedrockModel.class, remap = false)
public abstract class VehicleBedrockModelNoCullRenderMixin {

    @Shadow(remap = false)
    private void setSpecialBoneVisible(BakedModelInstance instance, boolean visible) {}

    @Shadow(remap = false)
    public abstract BakedBedrockModel getBakedModel();

    @Inject(
            method = "renderToBuffer(Lcom/github/mcmodderanchor/simplebedrockmodel/v2/common/model/runtime/BakedModelInstance;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/resources/ResourceLocation;I)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void ywzj_rvp$renderNoCullBones(BakedModelInstance instance, PoseStack poseStack,
                                            MultiBufferSource bufferSource, ResourceLocation texture,
                                            int packedLight, CallbackInfo ci) {
        VehicleBedrockModel self = (VehicleBedrockModel) (Object) this;
        Set<String> noCullBones = RVP_DisplayBackendUtil.getNoCullBones(self);
        if (noCullBones.isEmpty()) {
            return;
        }
        BakedBedrockModel bakedModel = getBakedModel();
        if (bakedModel == null) {
            return;
        }
        // 复刻原逻辑：主渲染先隐藏特殊骨骼（座舱/透明件）
        setSpecialBoneVisible(instance, false);
        instance.renderToBuffer(poseStack, bufferSource,
                RenderType.entityCutout(texture),
                RVP_RenderTypes.polyMeshCutout(texture),
                packedLight,
                OverlayTexture.NO_OVERLAY);
        // 用 NO_CULL 渲染类型补画指定骨骼（含其子树），使背面可见
        RenderType noCullMesh = RVP_RenderTypes.polyMeshCutoutNoCull(texture);
        for (String boneName : noCullBones) {
            int boneIndex = bakedModel.getIndex(boneName);
            if (boneIndex < 0) {
                continue;
            }
            instance.renderSingleBone(poseStack, boneIndex, bufferSource,
                    RenderType.entityCutout(texture), noCullMesh,
                    packedLight, OverlayTexture.NO_OVERLAY,
                    1.0F, 1.0F, 1.0F, 1.0F, false);
        }
        ci.cancel();
    }
}
