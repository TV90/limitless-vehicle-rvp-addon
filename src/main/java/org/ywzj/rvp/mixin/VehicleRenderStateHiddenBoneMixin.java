package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.render.RVP_DistanceBoneHider;
import org.ywzj.rvp.client.render.RVP_StateBoneHider;
import org.ywzj.rvp.debug.RVP_BoneHideDebug;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.VehicleDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 在载具主体渲染前应用"状态机隐藏骨骼"：
 * <p>
 * 依据 display JSON 的 {@code state_hidden_bones} 配置，将处于指定状态（如起落架
 * 收起完毕）的骨骼标记为不渲染；状态退出时恢复渲染。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = VehicleRender.class, remap = false)
public abstract class VehicleRenderStateHiddenBoneMixin {

    private static long lastDebugTick = Long.MIN_VALUE;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/resource/vehicle/VehicleBedrockModel;renderToBuffer(Lcom/github/mcmodderanchor/simplebedrockmodel/v2/common/model/runtime/BakedModelInstance;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/resources/ResourceLocation;I)V"
            ),
            remap = false
    )
    private void ywzj_rvp$applyStateHiddenBones(AbstractVehicle vehicle,
                                                float pEntityYaw,
                                                float pPartialTick,
                                                PoseStack pPoseStack,
                                                MultiBufferSource bufferSource,
                                                int pPackedLight,
                                                CallbackInfo ci) {
        long tick = currentTick();
        // 注意：lastDebugTick 初始为 Long.MIN_VALUE，直接相减会溢出为负数，
        // 导致节流判断恒 false、日志永远不输出。首轮用哨兵值无条件放行。
        boolean dbg = RVP_BoneHideDebug.isEnabled()
                && (lastDebugTick == Long.MIN_VALUE || tick - lastDebugTick >= 60);
        if (dbg) {
            lastDebugTick = tick;
        }
        BakedModelInstance instance = vehicle.getVehicleModelInstance();
        if (instance == null) {
            // 与 VehicleRender.render 相同的兜底：未绑定实例时使用模型默认实例渲染。
            // 必须与渲染用的实例一致，否则骨骼可见性设置在另一个实例上不生效。
            VehicleBedrockModel model = ClientAssetsManager.INSTANCE
                    .getVehicleDisplay(vehicle.getDisplayId())
                    .map(VehicleDisplay::getModel)
                    .orElse(null);
            if (model != null && model.hasBakedModel()) {
                instance = model.getDefaultModelInstance();
            }
        }
        if (dbg) {
            RVP_BoneHideDebug.log("渲染注入触发：vehicle=" + vehicle + " displayId=" + vehicle.getDisplayId()
                    + " getVehicleModelInstance=" + vehicle.getVehicleModelInstance() + " 使用实例=" + instance);
        }
        if (instance != null) {
            RVP_StateBoneHider.apply(vehicle, instance);
            RVP_DistanceBoneHider.apply(vehicle, instance);
        }
    }

    private static long currentTick() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }
}
