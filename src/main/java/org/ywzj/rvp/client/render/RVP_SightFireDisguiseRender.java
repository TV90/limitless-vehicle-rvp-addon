package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/**
 * 观瞄视角射弹原点分离的客户端伪装渲染：伪装期内把弹体整体平移渲染为
 * "从炮口出发的平行弹道"（模型/曳光/尾焰同 Pose 一次生效），合流期线性回到真实弹道。
 *
 * <p>平移常量 = visualMuzzle − 实际出弹点（readSpawnData 时冻结），两端弹道方向一致
 * （炮管指向=准星方向），因此两条弹道互为平行平移，硬切换不会发生——factor 从 1 线性降到 0
 * 即为平滑合流。命中判定/雷达/尾迹逻辑全部不受影响（纯渲染）。</p>
 */
public final class RVP_SightFireDisguiseRender {

    private RVP_SightFireDisguiseRender() {
    }

    /** 渲染器入口：有伪装数据且未过合流期时对 PoseStack 施加平移；否则零开销返回。 */
    public static void applyDisguiseTranslate(Entity entity, PoseStack poseStack, float partialTick) {
        if (!(entity instanceof RVP_BaseBullet bullet)) {
            return;
        }
        Vec3 offset = bullet.rvp$getSightDisguiseRenderOffset();
        if (offset == null) {
            return;
        }
        int disguiseTicks = bullet.rvp$getSightDisguiseTicks();
        int endTick = bullet.rvp$getSightDisguiseEndTick();
        float age = bullet.tickCount + partialTick;
        if (age >= endTick) {
            return;
        }
        // smoothstep（3t²−2t³）缓入缓出：伪装期结束进入合流段时不是线性匀速回收，
        // 而是起步缓、收尾缓，肉眼过渡更顺（2026-09-19 用户反馈线性过渡生硬后调整）
        float blendRatio = (age - disguiseTicks) / Math.max(1, endTick - disguiseTicks);
        blendRatio = Mth.clamp(blendRatio, 0.0F, 1.0F);
        float factor = 1.0F - blendRatio * blendRatio * (3.0F - 2.0F * blendRatio);
        poseStack.translate(offset.x * factor, offset.y * factor, offset.z * factor);
    }
}
