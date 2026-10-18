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
 * 该调用落到 SBM v1 {@code BedrockBedrockBone.renderGeometryPass}，其中 mesh 分支优先交给
 * Accelerated Rendering 兼容层接管（返回 true 即不再走普通绘制）。实测在该直渲染上下文中
 * AR 接管后 mesh 并未真正落笔（cube 分支无此问题，99A 的 cube 链板正常），表现为 mesh
 * 链板整体不可见。</p>
 *
 * <p>本类改走与车体 poly_mesh 完全相同的<b>烘焙实例管线</b>：构造时显式传入
 * {@link BakerOptions}（bakeStaticGeometry=true，poly_mesh 随静态几何一起烘焙，
 * 与车体 BaseDisplay.createBakerOptions 同款），并覆写直渲染入口，把烘焙实例
 * 直接画进 TrackUnit 已绑定 entityCutout(链板贴图) 的缓冲——烘焙实例管线
 * 渲染 poly_mesh 已被车体长期验证。</p>
 */
public class RVP_TrackLinkModel extends VehicleBedrockModel {

    public RVP_TrackLinkModel(BedrockModelPOJO pojo) {
        // bakeStaticGeometry=true：poly_mesh 随静态几何烘焙（与车体 BaseDisplay 同款）
        super(pojo, List.of(), new BakerOptions(Set.of(), Set.of(), new HashSet<>(), true, false, true));
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
