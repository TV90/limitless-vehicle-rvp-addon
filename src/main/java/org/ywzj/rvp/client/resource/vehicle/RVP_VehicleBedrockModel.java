package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.light.RVP_SmokeLightPass;
import org.ywzj.rvp.client.render.RVP_CockpitTransparencyPass;
import org.ywzj.vehicle.client.render.ModRenderTypes;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** RVP 模型保留本体动画与特殊骨绘制，通过顶点转发补上炮口贴图的屏幕空间受光。 */
public class RVP_VehicleBedrockModel extends VehicleBedrockModel {

    /** 资源加载时冻结的特殊骨条目；只读取类型和贴图，不根据武器或骨骼名称猜测炮口。 */
    private final List<SpecialBoneEffect> effects;

    public RVP_VehicleBedrockModel(BedrockModelPOJO pojo, List<SpecialBoneEffect> specialBoneEffects) {
        this(pojo, specialBoneEffects, null);
    }

    public RVP_VehicleBedrockModel(BedrockModelPOJO pojo,
                                   List<SpecialBoneEffect> specialBoneEffects,
                                   BakerOptions bakerOptions) {
        super(pojo, specialBoneEffects, bakerOptions);
        this.effects = specialBoneEffects == null ? List.of() : List.copyOf(specialBoneEffects);
    }

    @Override
    public void renderSpecialBones(BakedModelInstance instance, PoseStack poseStack,
                                   MultiBufferSource source, ResourceLocation texture,
                                   int packedLight, int packedOverlay, List<BoneState> invisibleBones,
                                   boolean isLocalPlayerVehicle) {
        // 调用同帧世界窗口检查，界面预览、光影阴影和无灯画面不记录炮口几何。
        // 调用独立透明阶段，玻璃只记录一次；屏幕、炮口及其它材质保持原出口。
        MultiBufferSource materialSource = RVP_CockpitTransparencyPass.defer(source);
        MultiBufferSource lightingSource = materialSource;
        if (RVP_SmokeLightPass.capturesMuzzle()
                && effects.stream().anyMatch(effect -> effect.type == SpecialBoneEffect.SpecialBoneEffectType.MUZZLE_FLASH)) {
            Map<RenderType, ResourceLocation> materials = new IdentityHashMap<>();
            // 调用本体已有的同一材质实例进行识别；它使用 COLOR_WRITE，始终只写颜色、不写深度。
            if (texture != null) { materials.put(ModRenderTypes.muzzleFlash(texture), texture); }
            for (SpecialBoneEffect effect : effects) {
                // 同时登记所有显式贴图，覆盖本体现有特殊骨循环中继承前一条贴图的情形。
                if (effect.texture != null) { materials.put(ModRenderTypes.muzzleFlash(effect.texture), effect.texture); }
            }
            lightingSource = type -> {
                VertexConsumer original = materialSource.getBuffer(type);
                ResourceLocation effectTexture = materials.get(type);
                // 调用共用薄面片受光记录器，只包装真正使用炮口材质的顶点；玻璃和普通车体原样放行。
                return effectTexture == null ? original : RVP_SmokeLightPass.captureMuzzle(original, effectTexture);
            };
        }
        // 调用本体现有特殊骨流程，沿用动画、隐藏骨、脱落、座舱与贴图透明度，不另画一份模型。
        super.renderSpecialBones(instance, poseStack, lightingSource, texture, packedLight, packedOverlay,
                invisibleBones, isLocalPlayerVehicle);
    }
}
