package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.client.render.entity.weapon.AmmoEntityRenderer;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.resource.BedrockModelLoader;

import java.lang.reflect.Field;

/**
 * 修复 AmmoEntityRenderer 中 BedrockModel 强转为 VehicleBedrockModel 的 ClassCastException。
 * <p>原代码第65-66行：
 * <pre>
 *   if (ammoModel == null) {
 *       ammoModel = (VehicleBedrockModel) BedrockModelLoader.getModel(defaultModel);
 *   }
 * </pre>
 * 当 BedrockModelLoader.getModel() 返回 BedrockModel 基类（非 VehicleBedrockModel 子类）时，
 * 强转崩溃（概率性，取决于模型是否由 VehicleBedrockModel 加载器创建）。</p>
 * <p>修复：Redirect 拦截 getModel 返回值做 instanceof 检查，
 * 非 VehicleBedrockModel 时返回一个空壳 VehicleBedrockModel，
 * 其 hasBakedModel() 返回 false，后续渲染逻辑安全跳过。</p>
 */
@Mixin(AmmoEntityRenderer.class)
public class AmmoEntityRendererMixin {

    /** 空壳 VehicleBedrockModel，延迟初始化以避免clinit阶段NPE。 */
    private static VehicleBedrockModel emptyModel;

    private static VehicleBedrockModel getEmptyModel() {
        if (emptyModel == null) {
            try {
                BedrockModelPOJO pojo = new BedrockModelPOJO();
                // BedrockModelPOJO 没有 setter，通过反射设置 formatVersion 避免 NPE
                Field field = BedrockModelPOJO.class.getDeclaredField("formatVersion");
                field.setAccessible(true);
                field.set(pojo, "1.12.0");
                emptyModel = new VehicleBedrockModel(pojo, null);
            } catch (Exception e) {
                // 反射失败时，创建不带骨骼数据的模型作为最终兜底
                throw new RuntimeException("Failed to create empty VehicleBedrockModel", e);
            }
        }
        return emptyModel;
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/resource/BedrockModelLoader;getModel(Lnet/minecraft/resources/ResourceLocation;)Lcom/github/mcmodderanchor/simplebedrockmodel/v1/common/model/BedrockModel;",
                    remap = false),
            remap = false
    )
    private BedrockModel redirectGetModel(ResourceLocation location) {
        BedrockModel model = BedrockModelLoader.getModel(location);
        if (model instanceof VehicleBedrockModel) {
            return model;
        }
        // 模型不是 VehicleBedrockModel，返回空壳模型避免强转崩溃
        // 空壳模型的 hasBakedModel() 返回 false，后续渲染会安全跳过
        return getEmptyModel();
    }
}
