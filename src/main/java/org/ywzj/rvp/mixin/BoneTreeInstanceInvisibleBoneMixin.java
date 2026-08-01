package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneTreeInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把"缩放塌缩"的隐藏骨骼改为真正不渲染。
 * <p>
 * 现有 hideBone 通过把骨骼缩放设为 0 实现视觉隐藏，但该骨骼（及其子树）仍会
 * 被 SBM v2 每帧 CPU 处理（全局矩阵计算 + 顶点组装提交）。本 mixin 在
 * {@code BoneTreeInstance.applyPose} 完成后，把缩放全为 0 的骨骼及其整棵子树
 * 标记 {@code BoneState.visible = false}，SBM v2 渲染时对这些骨骼直接短路跳过，
 * 从而真正省掉顶点组装开销。
 * <p>
 * 视觉完全等价：scale 全为 0 的骨骼（及其子孙，因父级塌缩）本来就不可见。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = BoneTreeInstance.class, remap = false)
public abstract class BoneTreeInstanceInvisibleBoneMixin {

    @Inject(method = "applyPose", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$markZeroScaleBonesInvisible(CallbackInfo ci) {
        BoneTreeInstance self = (BoneTreeInstance) (Object) this;
        BoneState[] bones = self.getBoneIndexes();
        // 先复位可见性：避免上一帧被隐藏的骨骼在本帧恢复非 0 缩放后仍然不渲染
        for (BoneState bone : bones) {
            bone.visible = true;
        }
        for (BoneState bone : bones) {
            if (bone.xScale == 0.0F && bone.yScale == 0.0F && bone.zScale == 0.0F) {
                markInvisible(self, bone);
            }
        }
    }

    private static void markInvisible(BoneTreeInstance instance, BoneState bone) {
        bone.visible = false;
        for (Integer childIndex : instance.getChildren(bone.index())) {
            BoneState child = instance.getBone(childIndex);
            if (child != null && child.visible) {
                markInvisible(instance, child);
            }
        }
    }
}
