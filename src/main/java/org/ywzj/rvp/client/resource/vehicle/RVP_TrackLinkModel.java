package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 履带链板专用模型（2026-10-19）：绕过 SBM v1 直渲染路径的 mesh 缺陷，改走烘焙实例管线。
 *
 * <p>背景：本体 {@code TrackUnit.renderTrack} 用
 * {@code renderToBuffer(poseStack, VertexConsumer, light, overlay)} 直渲染链板模型——
 * 该调用落到 SBM v1 {@code renderGeometryPass}，其中 mesh 分支优先交给 Accelerated
 * Rendering 兼容层接管（返回 true 即不再走普通绘制）。实测在该直渲染上下文中 AR 接管后
 * mesh 并未真正落笔（cube 分支走另一兼容分支无此问题，99A 的 cube 链板正常），表现为
 * mesh 链板整体不可见。</p>
 *
 * <p>本类改走与车体 poly_mesh 完全相同的<b>烘焙实例管线</b>，并在烘焙选项上做了一个
 * 关键对齐：SBM 烘焙器把骨分成"运行时骨"（animatedBones∪preservedBones 集合内）与
 * "静态骨"（折叠进静态几何、挂到最近运行时骨祖先）；链板模型没有动画/控制器，
 * 空集合会让全部几何的挂载点落到 -1 而无法渲染——因此把模型全部骨名放入
 * preservedBones，让每块几何都挂在自己的骨骼上、按实例正常渲染（与车体一致）。</p>
 */
public class RVP_TrackLinkModel extends VehicleBedrockModel {

    public RVP_TrackLinkModel(BedrockModelPOJO pojo) {
        super(pojo, List.of(), createBakerOptions(pojo));
    }

    /** 与车体 BaseDisplay.createBakerOptions 同款语义：bakeStaticGeometry=true + 骨骼全部 preserved。 */
    private static BakerOptions createBakerOptions(BedrockModelPOJO pojo) {
        Set<String> boneNames = new HashSet<>();
        var geometry = pojo.getGeometryModelNew();
        if (geometry != null) {
            for (var bone : geometry.getBones()) {
                boneNames.add(bone.getName());
            }
        }
        return new BakerOptions(Set.of(), boneNames, new HashSet<>(), true, false, true);
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay) {
        BakedModelInstance instance = getDefaultModelInstance();
        if (instance == null) {
            return;
        }
        // 画进 TrackUnit 已绑定链板贴图的缓冲；旋转/平移由调用方 PoseStack 携带
        instance.renderToBuffer(poseStack, consumer, packedLight, packedOverlay);
    }
}
