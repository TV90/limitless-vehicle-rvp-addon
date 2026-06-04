package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_DispensedEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.resource.BedrockModelLoader;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Resolves bedrock model/texture for in-flight RVP projectiles from weapon display JSON.
 */
public final class RVP_ProjectileDisplayHelper {

    private static final ResourceLocation DEFAULT_MISSILE_MODEL =
            ResourceLocation.fromNamespaceAndPath("rvp", "entity/missile_pl_12");
    private static final ResourceLocation DEFAULT_MISSILE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("rvp", "textures/entity/j15.png");
    private static final ResourceLocation DEFAULT_BOMB_MODEL =
            ResourceLocation.fromNamespaceAndPath("rvp", "entity/bomb_spice_1000");
    private static final ResourceLocation DEFAULT_BOMB_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("rvp", "textures/entity/f16v.png");
    private static final ResourceLocation FALLBACK_MISSILE_MODEL =
            YwzjVehicle.modLocation("entity/missile_akd10");
    private static final ResourceLocation FALLBACK_MISSILE_TEXTURE =
            YwzjVehicle.modLocation("textures/entity/missile_akd10.png");
    private static final ResourceLocation DEFAULT_BULLET_MODEL =
            YwzjVehicle.modLocation("entity/basic_bullet");
    private static final ResourceLocation DEFAULT_BULLET_TEXTURE =
            YwzjVehicle.modLocation("textures/entity/basic_bullet.png");

    private RVP_ProjectileDisplayHelper() {}

    public record ResolvedDisplay(BedrockModel model, ResourceLocation texture) {}

    public static RVP_EnumWeaponKind kindFromEntity(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof RVP_MissileEntity) {
            return RVP_EnumWeaponKind.MISSILE;
        }
        if (entity instanceof RVP_RocketEntity) {
            return RVP_EnumWeaponKind.ROCKET;
        }
        if (entity instanceof RVP_BulletEntity) {
            return RVP_EnumWeaponKind.MACHINEGUN;
        }
        if (entity instanceof RVP_BombEntity) {
            return RVP_EnumWeaponKind.BOMB;
        }
        if (entity instanceof RVP_DispensedEntity) {
            return RVP_EnumWeaponKind.DISPENSER;
        }
        return RVP_EnumWeaponKind.ROCKET;
    }

    public static boolean isHeavyProjectile(net.minecraft.world.entity.Entity entity) {
        RVP_EnumWeaponKind kind = kindFromEntity(entity);
        return kind == RVP_EnumWeaponKind.MISSILE || kind == RVP_EnumWeaponKind.ROCKET
                || kind == RVP_EnumWeaponKind.BOMB || kind == RVP_EnumWeaponKind.DISPENSER;
    }

    @Nullable
    public static ResolvedDisplay resolve(AmmoEntity ammo) {
        ResourceLocation weaponId = ammo.getWeaponId();
        RVP_EnumWeaponKind kind = kindFromEntity(ammo);

        BedrockModel model = null;
        ResourceLocation texture = null;
        if (weaponId != null) {
            Optional<BaseDisplay> displayOptional = ClientAssetsManager.INSTANCE.getWeaponDisplay(weaponId);
            if (displayOptional.isPresent()) {
                BaseDisplay display = displayOptional.get();
                if (display.getModel() != null) {
                    model = display.getModel();
                }
                if (display.getTexture() != null) {
                    texture = display.getTexture();
                }
            }
        }

        if (model == null || texture == null) {
            ResolvedDisplay defaults = defaultsForAmmo(ammo, kind);
            if (model == null) {
                model = defaults.model();
            }
            if (texture == null) {
                texture = defaults.texture();
            }
        }

        if (model == null) {
            return null;
        }
        return new ResolvedDisplay(model, texture);
    }

    private static ResolvedDisplay defaultsForAmmo(AmmoEntity ammo, RVP_EnumWeaponKind kind) {
        return switch (kind) {
            case MACHINEGUN, LASER -> new ResolvedDisplay(
                    BedrockModelLoader.getModel(DEFAULT_BULLET_MODEL),
                    DEFAULT_BULLET_TEXTURE);
            case BOMB, DISPENSER -> new ResolvedDisplay(
                    BedrockModelLoader.getModel(DEFAULT_BOMB_MODEL),
                    DEFAULT_BOMB_TEXTURE);
            case MISSILE, ROCKET, TARGETING_POD -> new ResolvedDisplay(
                    BedrockModelLoader.getModel(DEFAULT_MISSILE_MODEL),
                    DEFAULT_MISSILE_TEXTURE);
        };
    }

    public static boolean usesBuiltinShotgunCube(AmmoEntity ammo) {
        return ammo instanceof RVP_BulletEntity bullet && bullet.usesShotgunCubeVisual();
    }

    public static boolean usesBuiltinSabotDart(AmmoEntity ammo) {
        return ammo instanceof RVP_BulletEntity bullet && bullet.usesSabotDartVisual();
    }

    public static ResolvedDisplay fallbackMissile() {
        return new ResolvedDisplay(
                BedrockModelLoader.getModel(FALLBACK_MISSILE_MODEL),
                FALLBACK_MISSILE_TEXTURE);
    }
}
