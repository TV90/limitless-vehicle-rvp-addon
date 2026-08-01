package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_LodModelManager;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 整模型 LOD 渲染重定向：
 * <p>
 * LOD 激活时，把 {@link VehicleRender#render} 中的模型、实例、贴图替换为 LOD 版本，
 * 并跳过 {@code applyAnimationPose}（全静态渲染）：
 * <ul>
 *   <li>{@code getModel()} → LOD 的 {@link VehicleBedrockModel}；</li>
 *   <li>{@code getModelInstance()} → LOD 的静态 bind pose 实例；</li>
 *   <li>{@code getTexture()} → LOD 贴图；</li>
 *   <li>{@code applyAnimationPose(...)} → LOD 激活时跳过。</li>
 * </ul>
 * 未激活时全部回调原方法，行为与基座一致。部件/饰品/弹孔仍走原实例渲染。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = VehicleRender.class, remap = false)
public abstract class VehicleRenderLodMixin {

    /** 当前帧正在渲染的载具的 LOD 状态（HEAD 注入时解析一次，避免每处重算）。 */
    private static RVP_LodModelManager.VehicleLodState ywzj_rvp$lodState;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$resolveLod(AbstractVehicle vehicle,
                                     float pEntityYaw,
                                     float pPartialTick,
                                     PoseStack pPoseStack,
                                     MultiBufferSource bufferSource,
                                     int pPackedLight,
                                     CallbackInfo ci) {
        ywzj_rvp$lodState = RVP_LodModelManager.resolve(vehicle);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/resource/vehicle/BaseDisplay;getModel()Lorg/ywzj/vehicle/client/resource/vehicle/VehicleBedrockModel;"
            ),
            remap = false
    )
    private VehicleBedrockModel ywzj_rvp$lodModel(BaseDisplay display) {
        RVP_LodModelManager.VehicleLodState state = ywzj_rvp$lodState;
        return state != null ? state.model : display.getModel();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/entity/vehicle/AbstractVehicle;getModelInstance()Lcom/github/mcmodderanchor/simplebedrockmodel/v2/common/model/runtime/BakedModelInstance;"
            ),
            remap = false
    )
    private BakedModelInstance ywzj_rvp$lodInstance(AbstractVehicle vehicle) {
        RVP_LodModelManager.VehicleLodState state = ywzj_rvp$lodState;
        return state != null ? state.instance : vehicle.getModelInstance();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/resource/vehicle/BaseDisplay;getTexture()Lnet/minecraft/resources/ResourceLocation;"
            ),
            remap = false
    )
    private ResourceLocation ywzj_rvp$lodTexture(BaseDisplay display) {
        RVP_LodModelManager.VehicleLodState state = ywzj_rvp$lodState;
        return state != null && state.texture != null ? state.texture : display.getTexture();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/render/entity/vehicle/VehicleRender;applyAnimationPose(Lorg/ywzj/vehicle/entity/vehicle/AbstractVehicle;FLcom/github/mcmodderanchor/simplebedrockmodel/v2/common/model/runtime/BakedModelInstance;)V"
            ),
            remap = false
    )
    private void ywzj_rvp$skipLodAnimation(AbstractVehicle vehicle,
                                           float pPartialTick,
                                           BakedModelInstance modelInstance) {
        if (ywzj_rvp$lodState == null) {
            VehicleRender.applyAnimationPose(vehicle, pPartialTick, modelInstance);
        }
    }
}
