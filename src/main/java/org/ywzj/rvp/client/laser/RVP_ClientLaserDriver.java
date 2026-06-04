package org.ywzj.rvp.client.laser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState.LaserBeamKey;
import org.ywzj.rvp.weapon.core.RVP_LaserWeapon;
import org.ywzj.rvp.weapon.data.RVP_LaserVisualData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

/**
 * Keeps local {@code rvp:laser} beams visible while the player holds fire, without relying only on
 * {@link org.ywzj.vehicle.api.event.VehicleFireEvent.Post} (which may carry a {@link org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent}).
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientLaserDriver {

    private RVP_ClientLaserDriver() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            return;
        }
        boolean firing = AllKeys.MAIN_WEAPON_SHOOT.isDown() || AllKeys.SECONDARY_WEAPON_SHOOT.isDown();
        if (firing && player.getVehicle() instanceof AbstractVehicle vehicle) {
            PartUnit<?> operatorUnit = vehicle.getOwnOperatorUnit(player);
            if (operatorUnit instanceof WeaponUnit weaponUnit) {
                boolean primary = AllKeys.MAIN_WEAPON_SHOOT.isDown();
                Optional<AbstractVehicleWeapon<?>> current = primary
                        ? weaponUnit.getCurrentWeapon()
                        : weaponUnit.getCurrentSecondaryWeapon();
                long gameTime = level.getGameTime();
                current.ifPresent(w -> pulseIfLaser(vehicle, w, gameTime, player.getId()));
            }
        }

        RVP_ClientLaserState.tick(level);
        if (!RVP_ClientLaserState.view().isEmpty()) {
            tickImpactParticles(level);
        }
    }

    private static void tickImpactParticles(Level level) {
        Minecraft mc = Minecraft.getInstance();
        float partialTick = mc.getFrameTime();
        for (var entry : RVP_ClientLaserState.view().entrySet()) {
            RVP_ClientLaserBeamResolver.ResolvedBeam resolved =
                    RVP_ClientLaserBeamResolver.resolve(level, entry.getKey(), entry.getValue(), partialTick);
            if (resolved != null) {
                RVP_LaserImpactEffects.spawnImpact(level, entry.getKey(), resolved.beam(), resolved.data());
            }
        }
    }

    /** Remote / packet-driven fire (also used from {@link org.ywzj.rvp.client.RVP_ClientEvents}). */
    public static void pulseFromFireEvent(AbstractVehicle vehicle, AbstractVehicleWeapon<?> firedWeapon,
                                          long gameTime, int operatorId) {
        RVP_LaserWeapon laser = RVP_LaserWeapons.asLaser(firedWeapon);
        if (laser == null || !RVP_LaserWeapons.canRenderBeam(laser)) {
            return;
        }
        RVP_WeaponData data = laser.getData();
        RVP_ClientLaserState.pulse(LaserBeamKey.of(vehicle, laser), data.getLaserVisual(), gameTime, operatorId);
    }

    private static void pulseIfLaser(AbstractVehicle vehicle, AbstractVehicleWeapon<?> weapon,
                                     long gameTime, int operatorId) {
        RVP_LaserWeapon laser = RVP_LaserWeapons.asLaser(weapon);
        if (laser == null) {
            return;
        }
        LaserBeamKey key = LaserBeamKey.of(vehicle, laser);
        if (!RVP_LaserWeapons.canRenderBeam(laser)) {
            RVP_ClientLaserState.clear(key);
            return;
        }
        RVP_WeaponData data = laser.getData();
        RVP_LaserVisualData visual = data.getLaserVisual();
        int chargeTime = data.getFireData().getChargeTime();
        if (chargeTime > 0) {
            float ratio = Math.max(laser.getChargeTick() / (float) chargeTime, 0.12f);
            visual = visual.withChargeRatio(ratio);
        }
        RVP_ClientLaserState.pulse(key, visual, gameTime, operatorId);
    }
}
