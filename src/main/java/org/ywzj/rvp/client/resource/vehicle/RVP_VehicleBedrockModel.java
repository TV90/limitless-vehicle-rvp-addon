package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunkRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.ywzj.rvp.client.compat.acceleratedrendering.RVP_AcceleratedMeshCompat;
import org.ywzj.rvp.client.light.RVP_SmokeLightPass;
import org.ywzj.rvp.client.compat.acceleratedrendering.RVP_SharedOpaqueMesh;
import org.ywzj.rvp.client.render.RVP_HitPanelMesh;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.rvp.config.RVP_ClientConfig;
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
    /** 资源加载时统计的最大 mesh 顶点数，避免小模型逐帧创建加速包装。 */
    private final int largestMeshVertexCount;
    /** 展板共享绘制所需静态骨索引，未启用 baked 模型时为 null。 */
    private RVP_BakedMeshPass panelMeshPass;
    /** 最近一次展板实例实际绑定的 baked 版本；资源重载后旧实例不能混用新骨索引。 */
    private BakedBedrockModel panelMeshModel;
    /** 与本体同规则筛出的特殊骨；主体绘制时隐藏，随后仍交给本体独立材质通道。 */
    private int[] specialBoneIndices;

    public RVP_VehicleBedrockModel(BedrockModelPOJO pojo, List<SpecialBoneEffect> specialBoneEffects) {
        this(pojo, specialBoneEffects, null);
    }

    public RVP_VehicleBedrockModel(BedrockModelPOJO pojo,
                                   List<SpecialBoneEffect> specialBoneEffects,
                                   BakerOptions bakerOptions) {
        super(pojo, specialBoneEffects, bakerOptions);
        this.effects = specialBoneEffects == null ? List.of() : List.copyOf(specialBoneEffects);
        int largest = 0;
        // 调用本体的 baked 模型入口，只读取静态几何规模，不改骨架、可见性或缓存。
        if (hasBakedModel()) {
            for (var chunk : getBakedModel().meshChunks()) {
                largest = Math.max(largest, chunk.vertices().vertexCount());
            }
        }
        this.largestMeshVertexCount = largest;
        this.panelMeshModel = getBakedModel();
        this.panelMeshPass = hasBakedModel() ? new RVP_BakedMeshPass(panelMeshModel) : null;
        this.specialBoneIndices = hasBakedModel() ? effects.stream().filter(SpecialBoneEffect::isValid)
                .mapToInt(effect -> getBakedModel().getIndex(effect.bone)).filter(index -> index >= 0)
                .distinct().toArray() : new int[0];
    }

    @Override
    public void renderToBuffer(BakedModelInstance instance, PoseStack poseStack,
                               MultiBufferSource source, ResourceLocation texture, int packedLight) {
        // 调用显式展板窗口；不改 AR 阶段标记，非标准缓冲与其它界面保持原提交语义。
        if (panelMeshPass != null && texture != null && RVP_HitPanelMesh.accepts(source)
                && RVP_SharedOpaqueMesh.isEnabled()
                && largestMeshVertexCount >= RVP_ClientConfig.getAcceleratedDenseMinVertices()) {
            renderPanelBody(instance, poseStack, source, texture, packedLight);
            return;
        }
        // 调用可选 AR 桥，仅包装主体的不透明三角形提交；骨骼遍历和动画继续由本体/SBM 执行。
        MultiBufferSource meshSource = RVP_AcceleratedMeshCompat.wrapOpaqueMeshSource(
                source, texture, largestMeshVertexCount);
        super.renderToBuffer(instance, poseStack, meshSource, texture, packedLight);
    }

    private void renderPanelBody(BakedModelInstance instance, PoseStack pose, MultiBufferSource source,
                                 ResourceLocation texture, int light) {
        // 调用实例自己的定义，与 SBM instance.renderToBuffer 一致；重载不能串用新/旧骨树。
        BakedBedrockModel baked = instance.baseModel();
        if (panelMeshModel != baked) {
            panelMeshModel = baked;
            panelMeshPass = new RVP_BakedMeshPass(baked);
            specialBoneIndices = effects.stream().filter(SpecialBoneEffect::isValid)
                    .mapToInt(effect -> baked.getIndex(effect.bone)).filter(index -> index >= 0).distinct().toArray();
        }
        for (int index : specialBoneIndices) {
            BoneState bone = instance.getBone(index);
            if (bone != null) bone.visible = false;
        }
        // 调用 SBM 原 cube 通道，保持隐藏骨、自发光与面剔除；在即时 mesh 前先提交其深度。
        baked.renderBoneTree(instance, pose, source.getBuffer(RenderType.entityCutout(texture)),
                light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1, true);
        RVP_HitPanelMesh.flush();
        RenderType material = RVP_RenderTypes.polyMeshCutout(texture);
        int threshold = RVP_ClientConfig.getAcceleratedDenseMinVertices();
        // 调用独立骨树遍历；共享只读局部几何，每台车/回放代理仍用自己的骨骼矩阵与可见性。
        panelMeshPass.render(instance, pose, light, (chunk, stack, boneLight) -> {
            if (!RVP_SharedOpaqueMesh.tryDrawPanel(chunk, material, stack, boneLight,
                    OverlayTexture.NO_OVERLAY, threshold)) {
                // 小块、非均匀缩放或不支持材质继续调用 SBM；不强行解包 AR 消费者。
                BakedGeometryChunkRenderer.INSTANCE.renderVertexChunk(chunk, stack, source.getBuffer(material),
                        boneLight, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            }
        });
        // 调用既有 display no_cull_bones 白名单，严格保留指定薄片补画，其余主体仍单面剔除。
        for (String name : RVP_DisplayBackendUtil.getNoCullBones(this)) {
            int index = baked.getIndex(name);
            if (index >= 0) instance.renderSingleBone(pose, index, source, RenderType.entityCutout(texture),
                    RVP_RenderTypes.polyMeshCutoutNoCull(texture), light, OverlayTexture.NO_OVERLAY,
                    1, 1, 1, 1, false);
        }
    }

    @Override
    public void renderSpecialBones(BakedModelInstance instance, PoseStack poseStack,
                                   MultiBufferSource source, ResourceLocation texture,
                                   int packedLight, int packedOverlay, List<BoneState> invisibleBones,
                                   boolean isLocalPlayerVehicle) {
        // 调用同帧世界窗口检查，界面预览、光影阴影和无灯画面不记录炮口几何。
        MultiBufferSource lightingSource = source;
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
                VertexConsumer original = source.getBuffer(type);
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
