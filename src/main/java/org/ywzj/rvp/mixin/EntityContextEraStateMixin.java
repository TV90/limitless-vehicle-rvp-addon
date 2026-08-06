package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.client.state.RVP_ClientVehicleEraState;
import org.ywzj.vehicle.client.render.animation.context.EntityContext;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 动画脚本 ERA 查询接口恢复。
 *
 * <p>被删 {@code AbstractVehicleEraStateMixin} 曾把 {@code rvp_isEraActive} 注入到
 * {@link AbstractVehicle} 实体上，供 Rhino 动画脚本（如 {@code rvp:scripts/t90m.js}、
 * {@code bmpt72.js}、{@code m1a2sep.js}）经 {@code context.getEntity().rvp_isEraActive(...)}
 * 查询爆反骨块是否仍激活并隐藏失效骨块。</p>
 *
 * <p>实体为纯 Forge 服务端启动的“带毒”目标，不能再 mixin。改为在客户端动画上下文类
 * {@link EntityContext} 上承载同名方法（脚本入参 {@code context} 即其实例），内部查询
 * 客户端 ERA 侧表 {@link RVP_ClientVehicleEraState}（数据经 {@code S2CVehicleEraState}
 * 网络包同步）。脚本调用点相应改为 {@code context.rvp_isEraActive(...)}。</p>
 */
@Mixin(value = EntityContext.class, remap = false)
public abstract class EntityContextEraStateMixin {

    @Unique
    public boolean rvp_isEraActive(String boneName) {
        EntityContext<?> self = (EntityContext<?>) (Object) this;
        Entity entity = self.getEntity();
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return true;
        }
        return RVP_ClientVehicleEraState.isEraActive(vehicle.getId(), boneName);
    }
}
