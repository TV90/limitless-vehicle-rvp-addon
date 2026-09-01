package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneTreeInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 把"缩放塌缩"的隐藏骨骼改为真正不渲染（性能优化：跳过 SBM v2 对隐形骨骼的
 * 全局矩阵计算 + 顶点组装提交）。
 *
 * <p>现有 hideBone（JS 动画脚本，如 ERA 爆反消失、导弹发射后弹体隐藏）通过把骨骼缩放
 * 设为 0 实现视觉隐藏，但该骨骼（及其子树）仍会被 SBM v2 每帧 CPU 处理。本 mixin 在
 * {@code BoneTreeInstance.applyPose} 完成后，把缩放全为 0 的骨骼及其整棵子树标记
 * {@code BoneState.visible = false}，SBM v2 渲染时对这些骨骼直接短路跳过。</p>
 *
 * <p><b>修复记录（2026-09-02，击毁载具外壳不脱落部件/不变暗 bug）</b>：早期实现为
 * "先把全车骨骼复位 visible=true、再重新隐藏缩放为零者"，该复位发生在本体
 * {@code VehicleRender.applyDetachedPart}（击毁时把已脱落部件骨骼设为不可见）之后，
 * 会把外部标记擦掉——导致击毁载具的外壳把炮塔等已飞出的部件重新画回车体上。
 * 现改为只记忆并撤回<b>自己</b>打的标记（缩放恢复非零时恢复可见），外部设置的
 * visible 一律不碰；外部每帧都会重新设置自己的标记，两种机制互不干扰。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = BoneTreeInstance.class, remap = false)
public abstract class BoneTreeInstanceInvisibleBoneMixin {

    /** 本 mixin 自己标记为不可见的骨骼（跨帧记忆，用于只撤销自己的标记）。 */
    @Unique
    private Set<BoneState> ywzj_rvp$selfHidden = null;

    @Inject(method = "applyPose", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$markZeroScaleBonesInvisible(CallbackInfo ci) {
        BoneTreeInstance self = (BoneTreeInstance) (Object) this;
        BoneState[] bones = self.getBoneIndexes();
        if (ywzj_rvp$selfHidden == null) {
            ywzj_rvp$selfHidden = new HashSet<>();
        }
        // 第一步：只撤回自己上一帧打的标记——缩放恢复非零的骨骼恢复可见并移出记录，
        // 缩放仍为零的保持不可见。外部代码本帧设置的 visible 一律不被触碰。
        Iterator<BoneState> iterator = ywzj_rvp$selfHidden.iterator();
        while (iterator.hasNext()) {
            BoneState bone = iterator.next();
            if (bone.xScale != 0.0F || bone.yScale != 0.0F || bone.zScale != 0.0F) {
                bone.visible = true;
                iterator.remove();
            }
        }
        // 第二步：把本帧缩放全为零的骨骼（含子树）标记不可见并记入自己的集合。
        for (BoneState bone : bones) {
            if (bone.xScale == 0.0F && bone.yScale == 0.0F && bone.zScale == 0.0F) {
                ywzj_rvp$markInvisible(bone);
            }
        }
    }

    /** 标记骨骼及其可见的子树不可见，并逐个记入自己的集合（便于日后只撤回自己的标记）。 */
    @Unique
    private void ywzj_rvp$markInvisible(BoneState bone) {
        bone.visible = false;
        ywzj_rvp$selfHidden.add(bone);
        BoneTreeInstance self = (BoneTreeInstance) (Object) this;
        for (Integer childIndex : self.getChildren(bone.index())) {
            BoneState child = self.getBone(childIndex);
            if (child != null && child.visible) {
                ywzj_rvp$markInvisible(child);
            }
        }
    }
}
