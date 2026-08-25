package org.ywzj.rvp.client;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.client.render.RVP_BedrockProjectileEntityRenderer;
import org.ywzj.rvp.client.render.RVP_BulletEntityRenderer;
import org.ywzj.rvp.countermeasure.client.RVP_DecoyRenderer;
import org.ywzj.rvp.countermeasure.client.RVP_SmokeRenderer;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_DispensedEntity;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.all.AllEntities;
import org.ywzj.vehicle.entity.weapon.ActiveProtectionGrenadeEntity;
import org.ywzj.vehicle.entity.weapon.AerialBombEntity;
import org.ywzj.vehicle.entity.weapon.FragGrenadeEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
import org.ywzj.vehicle.entity.weapon.SmokeGrenadeEntity;

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
    private static final ResourceLocation FALLBACK_GRENADE_MODEL =
            YwzjVehicle.modLocation("entity/grenade_40mm");
    private static final ResourceLocation FALLBACK_GRENADE_TEXTURE =
            YwzjVehicle.modLocation("textures/entity/grenade_40mm.png");

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
        EntityRenderers.register(RVP_Entities.RVP_DECOY.get(), RVP_DecoyRenderer::new);
        EntityRenderers.register(RVP_Entities.RVP_ECM_DECOY.get(), org.ywzj.rvp.entity.ecm.RVP_EcmDecoyRenderer::new);
        EntityRenderers.register(RVP_Entities.RVP_SMOKE.get(), RVP_SmokeRenderer::new);
        EntityRenderers.register(AllEntities.ROCKET.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<RocketEntity>(ctx, FALLBACK_ROCKET_MODEL, FALLBACK_ROCKET_TEXTURE));
        EntityRenderers.register(AllEntities.AERIAL_BOMB.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<AerialBombEntity>(ctx, FALLBACK_BOMB_MODEL, FALLBACK_BOMB_TEXTURE));
        EntityRenderers.register(AllEntities.MISSILE.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<MissileEntity>(ctx, FALLBACK_MISSILE_MODEL, FALLBACK_MISSILE_TEXTURE));
        EntityRenderers.register(AllEntities.SMOKE_GRENADE.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<SmokeGrenadeEntity>(ctx, FALLBACK_GRENADE_MODEL, FALLBACK_GRENADE_TEXTURE));
        EntityRenderers.register(AllEntities.APS_GRENADE.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<ActiveProtectionGrenadeEntity>(ctx, FALLBACK_GRENADE_MODEL, FALLBACK_GRENADE_TEXTURE));
        EntityRenderers.register(AllEntities.FRAG_GRENADE.get(), ctx ->
                new RVP_BedrockProjectileEntityRenderer<FragGrenadeEntity>(ctx, FALLBACK_GRENADE_MODEL, FALLBACK_GRENADE_TEXTURE));
    }
}
