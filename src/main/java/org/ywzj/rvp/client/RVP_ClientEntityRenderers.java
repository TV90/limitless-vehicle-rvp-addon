package org.ywzj.rvp.client;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.client.render.RVP_BedrockProjectileEntityRenderer;
import org.ywzj.rvp.client.render.RVP_BulletEntityRenderer;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_DispensedEntity;
import org.ywzj.vehicle.YwzjVehicle;

/**
 * RVP projectile {@link net.minecraft.client.renderer.entity.EntityRenderer}s (typed for RVP entities).
 * Draw rules mirror ywzj_vehicle; models come from weapon display JSON + pack bedrock assets.
 */
public final class RVP_ClientEntityRenderers {

    private static final ResourceLocation FALLBACK_MISSILE_MODEL =
            YwzjVehicle.modLocation("entity/missile_akd10");
    private static final ResourceLocation FALLBACK_MISSILE_TEXTURE =
            YwzjVehicle.modLocation("textures/entity/missile_akd10.png");
    private static final ResourceLocation FALLBACK_ROCKET_MODEL =
            YwzjVehicle.modLocation("entity/rocket_57mm");
    private static final ResourceLocation FALLBACK_ROCKET_TEXTURE =
            YwzjVehicle.modLocation("textures/entity/rocket_57mm.png");
    private static final ResourceLocation FALLBACK_BOMB_MODEL =
            YwzjVehicle.modLocation("entity/aerial_bomb");
    private static final ResourceLocation FALLBACK_BOMB_TEXTURE =
            YwzjVehicle.modLocation("textures/entity/aerial_bomb.png");

    private RVP_ClientEntityRenderers() {}

    public static void register() {
        EntityRenderers.register(RVP_Entities.RVP_BULLET.get(), RVP_BulletEntityRenderer::new);
        EntityRenderers.register(RVP_Entities.RVP_MISSILE.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<>(ctx, FALLBACK_MISSILE_MODEL, FALLBACK_MISSILE_TEXTURE));
        EntityRenderers.register(RVP_Entities.RVP_ROCKET.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<>(ctx, FALLBACK_ROCKET_MODEL, FALLBACK_ROCKET_TEXTURE));
        EntityRenderers.register(RVP_Entities.RVP_BOMB.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<RVP_BombEntity>(ctx, FALLBACK_BOMB_MODEL, FALLBACK_BOMB_TEXTURE));
        EntityRenderers.register(RVP_Entities.RVP_DISPENSED.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<RVP_DispensedEntity>(ctx, FALLBACK_BOMB_MODEL, FALLBACK_BOMB_TEXTURE));
    }
}
