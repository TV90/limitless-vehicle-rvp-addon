package org.ywzj.rvp.entity.ecm;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * 被动电子战假目标渲染器：隐形（世界内不渲染，纯雷达幻影）。
 * 仅为避免客户端 {@code EntityRenderDispatcher} 因缺少 renderer 而 NPE。
 */
public class RVP_EcmDecoyRenderer extends EntityRenderer<Entity> {

    public RVP_EcmDecoyRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(Entity entity, Frustum frustum, double x, double y, double z) {
        // 隐形：世界内不画任何东西（已批示），但 renderer 必须存在以防 NPE
        return false;
    }

    @Override
    public ResourceLocation getTextureLocation(Entity entity) {
        return null;
    }
}
