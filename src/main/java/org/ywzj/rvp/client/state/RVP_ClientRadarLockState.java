package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.List;

public final class RVP_ClientRadarLockState {

    public record Contact(boolean external, int entityId, Vec3 position, Entity resolvedEntity) {}

    private static final RVP_ClientRadarLockState INSTANCE = new RVP_ClientRadarLockState();

    private final List<Contact> contacts = new ArrayList<>();
    private boolean active;
    private int cursorIndex;

    private RVP_ClientRadarLockState() {}

    public static RVP_ClientRadarLockState getInstance() {
        return INSTANCE;
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean artilleryMapPassthrough = mc.screen instanceof RVP_TacticalMapScreen screen
                && screen.allowsVehicleInputPassthrough();
        if (player == null || mc.level == null || (mc.screen != null && !artilleryMapPassthrough)) {
            clear();
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            clear();
            return;
        }
        if (RVP_WeaponSensorHelper.effectiveSensorType(weaponUnit) != WeaponUnitData.FireControlSensorType.RF) {
            clear();
            return;
        }
        if (isAntiRadiationSelected(weaponUnit)) {
            clear();
            return;
        }

        contacts.clear();
        for (RVP_RadarRoleHelper.ManualLockCandidate candidate : RVP_RadarRoleHelper.collectManualLockCandidates(weaponUnit)) {
            Entity entity = candidate.entity();
            contacts.add(new Contact(false, entity.getId(), candidate.position(), entity));
        }
        if (contacts.isEmpty()) {
            for (RVP_ExternalRadarLinkHelper.ClientLockCandidate candidate
                    : RVP_ExternalRadarLinkHelper.collectViewManualClientLockCandidates(weaponUnit)) {
                contacts.add(new Contact(true, candidate.entityId(), candidate.position(), candidate.resolvedEntity()));
            }
        }
        active = !contacts.isEmpty();
        if (!active) {
            cursorIndex = 0;
            return;
        }

        int currentEntityId = resolveCurrentLockedEntityId(weaponUnit, contacts.get(0).external());
        if (currentEntityId != Integer.MIN_VALUE) {
            for (int i = 0; i < contacts.size(); i++) {
                if (contacts.get(i).entityId() == currentEntityId) {
                    cursorIndex = i;
                    return;
                }
            }
        }
        if (cursorIndex >= contacts.size()) {
            cursorIndex = 0;
        }
    }

    public boolean isActive() {
        return active;
    }

    public void selectPrev() {
        if (!active || contacts.isEmpty()) {
            return;
        }
        cursorIndex = (cursorIndex - 1 + contacts.size()) % contacts.size();
        applyCurrentSelection();
    }

    public void selectNext() {
        if (!active || contacts.isEmpty()) {
            return;
        }
        cursorIndex = (cursorIndex + 1) % contacts.size();
        applyCurrentSelection();
    }

    private void applyCurrentSelection() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || contacts.isEmpty()) {
            return;
        }
        Contact contact = contacts.get(cursorIndex);
        if (contact.external()) {
            RVP_ExternalRadarLinkHelper.applyClientLockRequest(weaponUnit, contact.entityId());
            return;
        }
        Entity entity = contact.resolvedEntity();
        if (entity == null || !entity.isAlive()) {
            return;
        }
        RVP_RadarRoleHelper.applyRequestedLock(weaponUnit, entity);
    }

    private static boolean isAntiRadiationSelected(WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> currentWeapon = RVP_LaserWeapons.unwrap(weaponUnit.getCurrentWeapon().orElse(null));
        return currentWeapon instanceof RVP_WeaponBase rvpWeapon
                && rvpWeapon.getData().isAntiRadiationMissile();
    }

    private static int resolveCurrentLockedEntityId(WeaponUnit weaponUnit, boolean externalContacts) {
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (externalContacts) {
            Minecraft mc = Minecraft.getInstance();
            AbstractVehicle launcher = LocalVehiclePlayer.instance.getVehicle();
            ResourceLocation dimension = mc.level != null ? mc.level.dimension().location() : null;
            int lockedId = RVP_ExternalRadarLinkHelper.getClientLockedEntityId(launcher, dimension);
            if (lockedId != Integer.MIN_VALUE) {
                return lockedId;
            }
            int requestedId = RVP_ExternalRadarLinkHelper.getClientRequestedEntityId(launcher, dimension);
            if (requestedId != Integer.MIN_VALUE) {
                return requestedId;
            }
            if (root instanceof WeaponUnitExternalRadarLockExt ext) {
                int serverLockedId = ext.ywzj_rvp$getExternalRadarLockedEntityId();
                if (serverLockedId != Integer.MIN_VALUE) {
                    return serverLockedId;
                }
            }
            return Integer.MIN_VALUE;
        }
        Entity radarLocked = RVP_RadarRoleHelper.getLockedRadarEntity(root);
        if (radarLocked != null && radarLocked.isAlive()) {
            return radarLocked.getId();
        }
        Entity localLocked = root.getLockedEntity();
        return localLocked != null && localLocked.isAlive() ? localLocked.getId() : Integer.MIN_VALUE;
    }

    private void clear() {
        active = false;
        contacts.clear();
        cursorIndex = 0;
    }
}
