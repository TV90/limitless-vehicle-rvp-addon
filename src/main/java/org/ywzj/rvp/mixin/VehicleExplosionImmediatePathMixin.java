package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.ywzj.rvp.config.RVP_Config;
import org.ywzj.rvp.weapon.effects.RVP_ExplosionImmediatePath;
import org.ywzj.vehicle.util.VehicleExplosion;

/**
 * RVP 弹体爆炸强制即时破坏路径（2026-09-20 用户定版）：把本体
 * {@code VehicleExplosion} 的批量破坏半径阈值
 * {@code BATCHED_DESTRUCTION_RADIUS_THRESHOLD = 32.0F} 在 RVP 弹体爆炸期间抬到
 * 正无穷——任意半径都走 ≤32 的即时路径（{@code GridCollectionTask} +
 * {@code destroyBlocksImmediately}），不再进入核爆炸批处理
 * （{@code SphericalCollectionTask}：分 tick 扫描 + 烧灼方块替换 + 跨 tick
 * 服务端持续负载，实测为半径 &gt;32 爆炸卡顿来源）。
 *
 * <p><b>注入方式与理由</b>：{@code @ModifyConstant} 是能改变该阈值分岔的最小注入
 * （仅改一个 float 常量的读取值，不重定向、不覆写逻辑）；替代方案均不可行——
 * 本体无暴露阈值的 JSON/配置，{@code VehicleExplosion} 不可继承替换（RVP 弹体
 * 伤害/视觉/掉落全依赖本类的 explode 管线），Forge 事件总线无爆炸路径选择事件。</p>
 *
 * <p><b>常量的两个使用点一并正确</b>：①{@code explode()} 的路径分岔——窗口内恒走
 * 即时路径；②{@code maximumRayEnergy(radius)} 的能量分档（&gt;32 → radius×5.0，
 * ≤32 → radius×1.3）——窗口内自动落回 ≤32 档能量公式，即用户要求的"用那个函数
 * 套更高的值"，方块阻力归一化与 ≤32 爆炸完全同源。</p>
 *
 * <p><b>作用范围</b>：仅 {@code RVP_ExplosionImmediatePath.active()}（由
 * {@code RVP_BaseBullet.triggerExplosion} 包住爆炸调用）且配置
 * {@code forceImmediateExplosionDestruction}（默认 true）时生效；本体/其它 mod
 * 的 {@code VehicleExplosion} 不经窗口，返回原值，行为不变。目标类双端安全
 * （{@code VehicleExplosionHurtSkipMixin} 已在公共数组注入同类），注册于公共数组。</p>
 */
@Mixin(value = VehicleExplosion.class, remap = false)
public abstract class VehicleExplosionImmediatePathMixin {

    @ModifyConstant(
            method = "explode(Ljava/util/List;)V",
            constant = @Constant(floatValue = 32.0F),
            allow = 2,
            require = 1,
            remap = false
    )
    private static float rvp$raiseBatchedThreshold(float original) {
        return RVP_ExplosionImmediatePath.active() && RVP_Config.isForceImmediateExplosionDestruction()
                ? Float.MAX_VALUE
                : original;
    }
}
