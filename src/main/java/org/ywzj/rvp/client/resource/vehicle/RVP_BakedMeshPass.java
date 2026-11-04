package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBoneDefinition;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunk;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;

/** 沿 SBM 骨树提交局部 mesh；仅缓存静态索引，姿态、可见性和自发光逐实例读取。 */
final class RVP_BakedMeshPass {
    /** 烘焙骨定义；资源重载时随所属模型重建。 */
    private final BakedBoneDefinition[] bones;
    /** 挂到动态骨的几何，数组元素仍是原 chunk，绝不复制顶点。 */
    private final BakedGeometryChunk[] chunks;
    /** 未挂到动态骨的静态根几何。 */
    private final BakedGeometryChunk root;

    @FunctionalInterface
    interface Sink {
        void draw(BakedGeometryChunk chunk, PoseStack pose, int light);
    }

    RVP_BakedMeshPass(BakedBedrockModel model) {
        bones = model.bones();
        chunks = new BakedGeometryChunk[bones.length];
        BakedGeometryChunk foundRoot = null;
        for (BakedGeometryChunk chunk : model.meshChunks()) {
            if (chunk.isRootAttached()) foundRoot = chunk;
            else chunks[chunk.attachBoneIndex()] = chunk;
        }
        root = foundRoot;
    }

    void render(BakedModelInstance instance, PoseStack pose, int light, Sink sink) {
        if (root != null) sink.draw(root, pose, light);
        for (BakedBoneDefinition bone : bones) {
            if (bone.parentIndex() < 0) renderBone(instance, bone.index(), pose, light, sink);
        }
    }

    private void renderBone(BakedModelInstance instance, int index, PoseStack pose, int light, Sink sink) {
        BakedBoneDefinition definition = bones[index];
        if (!definition.hasVerticesInTree()) return;
        BoneState bone = instance.getBone(index);
        if (bone == null || !bone.visible) return;
        int boneLight = bone.illuminated ? LightTexture.FULL_BRIGHT : light;
        pose.pushPose();
        try {
            // 调用 SBM 的现有骨骼变换，包含折叠父骨、枢轴、动画和缩放；不从实时载具重算姿态。
            bone.translateAndRotateAndScale(pose);
            if (chunks[index] != null) sink.draw(chunks[index], pose, boneLight);
            for (int child : definition.children()) renderBone(instance, child, pose, boneLight, sink);
        } finally {
            pose.popPose();
        }
    }
}
