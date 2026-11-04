package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.*;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import org.joml.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RVP_BakedMeshPassTest {
    record Submission(BakedGeometryChunk chunk, Matrix4f pose, Matrix3f normal, int light) { }

    @Test
    void sharesChunksButKeepsNestedAnimationVisibilityAndLightingIdenticalToSbm() {
        RecordingModel model = new RecordingModel();
        RVP_BakedMeshPass pass = new RVP_BakedMeshPass(model);
        BakedModelInstance a = model.createInstance(), b = model.createInstance();
        a.getBone(0).rotation.rotateY(0.9F);
        a.getBone(1).rotation.rotateX(-0.4F);
        a.getBone(1).illuminated = true;
        b.getBone(0).rotation.rotateY(-1.2F);
        b.getBone(1).visible = false;
        for (BakedModelInstance instance : List.of(a, b, a)) {
            List<Submission> actual = new ArrayList<>();
            PoseStack stack = new PoseStack();
            stack.translate(500, 100, 100);
            stack.mulPoseMatrix(new Matrix4f().scaling(30, 30, -30));
            Matrix4f before = new Matrix4f(stack.last().pose());
            pass.render(instance, stack, 32, (chunk, pose, light) -> actual.add(
                    new Submission(chunk, new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), light)));
            model.expected.clear();
            model.renderBoneTree(instance, stack, null, 32, 0, 1, 1, 1, 1, false);
            assertEquals(model.expected.size(), actual.size());
            for (int i = 0; i < actual.size(); i++) {
                Submission expected = model.expected.get(i), got = actual.get(i);
                assertSame(expected.chunk(), got.chunk(), "必须共享原始 chunk，不能为回放克隆顶点");
                assertTrue(expected.pose().equals(got.pose(), 0.00001F));
                assertTrue(expected.normal().equals(got.normal(), 0.00001F));
                assertEquals(expected.light(), got.light());
            }
            assertTrue(before.equals(stack.last().pose()), "遍历必须恢复调用方姿势栈");
        }
        assertFalse(b.getBone(1).visible, "绘制其它实例不能恢复本实例的损坏隐藏部件");
    }

    @Test
    void failedMeshDrawRestoresNestedPoseStack() {
        RecordingModel model = new RecordingModel();
        PoseStack stack = new PoseStack();
        assertThrows(IllegalStateException.class, () -> new RVP_BakedMeshPass(model).render(model.createInstance(), stack,
                32, (chunk, pose, light) -> { if (chunk.attachBoneIndex() == 2) throw new IllegalStateException("draw"); }));
        assertTrue(stack.clear());
        assertTrue(stack.last().pose().equals(new Matrix4f()));
    }

    /** 用 SBM 自己的公开遍历记录作为对照，避免测试仅重复新实现。 */
    static final class RecordingModel extends BakedBedrockModel {
        final List<Submission> expected = new ArrayList<>();
        RecordingModel() {
            super(new BakedBoneDefinition[]{bone(0, -1, new int[]{1}), bone(1, 0, new int[]{2}), bone(2, 1, new int[0])},
                    Map.of("b0", 0, "b1", 1, "b2", 2),
                    new BakedGeometryChunk[]{chunk(-1), chunk(0), chunk(1), chunk(2)},
                    new BoneLocator[0], Map.of(), new QueryTransform[0], Map.of(), null, new AABB(0, 0, 0, 2, 2, 2));
        }
        @Override
        public void renderChunkForPass(BakedGeometryChunk chunk, PoseStack pose, VertexConsumer consumer,
                                       int light, int overlay, float r, float g, float b, float a,
                                       boolean quadsPass, boolean skipCull) {
            if (!quadsPass && chunk.hasVertices()) expected.add(new Submission(chunk,
                    new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), light));
        }
        static BakedBoneDefinition bone(int index, int parent, int[] children) {
            Matrix4f folded = new Matrix4f().translate(0.2F, 1.3F, -0.1F).rotateZ(0.2F);
            return new BakedBoneDefinition("b" + index, index, parent, children,
                    0.3F, 0.4F, 0.5F, 0, 2, 0, null, null,
                    folded, new Matrix3f(folded), new Quaternionf(), new Vector3f(), false, true);
        }
        static BakedGeometryChunk chunk(int index) {
            return new BakedGeometryChunk(index, null, new BakedVertexData(
                    new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
                    new float[]{0, 0, 1, 0, 0, 1}, 3), new String[]{"b" + index});
        }
    }
}
