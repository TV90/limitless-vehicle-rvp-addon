package org.ywzj.rvp.sight;

import net.minecraft.world.phys.Vec3;

/**
 * 观瞄视角射弹原点分离的单发伪装数据（服务端出弹时解析，随生成包同步到客户端渲染）。
 *
 * <p>{@code visualMuzzle} = 炮管真实炮口（伪装渲染的出发点）；{@code actualSpawn} = 实际出弹点
 * （观瞄相机坐标）；两端弹道方向一致（炮管指向=准星方向），两条弹道互为平行平移，
 * 客户端在 {@code disguiseTicks} 内把模型/曳光平移到伪装弹道上，随后 {@code blendTicks}
 * 内线性合流真实弹道。</p>
 */
public record RVP_SightFireDisguise(Vec3 visualMuzzle, Vec3 actualSpawn, int disguiseTicks, int blendTicks) {
}
