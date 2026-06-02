package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.network.C2SSetAntiRadiationPreselect;
import org.ywzj.rvp.network.RvpNetwork;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.rvp.weapon.VehicleAntiRadiationMissile;
import org.ywzj.rvp.weapon.data.VehicleAntiRadiationMissileWeaponData;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RvpClientAntiRadiationState {

    public record Contact(int vehicleId, int radarIndex, Vec3 position, boolean locked, double score) {
    }

    private static boolean active;
    private static final List<Contact> contacts = new ArrayList<>();
    private static final Map<Long, Integer> pulseTickMap = new HashMap<>();

    private static int cursorIndex;
    private static int lockedVehicleId = -1;
    private static int lockedRadarIndex = -1;
    private static long lockedKeySent = Long.MIN_VALUE;

    public static boolean isActive() {
        return active;
    }

    public static List<Contact> getContacts() {
        return contacts;
    }

    public static int getLockedVehicleId() {
        return lockedVehicleId;
    }

    public static int getLockedRadarIndex() {
        return lockedRadarIndex;
    }

    public static void tick(Minecraft mc, LocalPlayer player) {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            clear();
            return;
        }
        Optional<AbstractVehicleWeapon<?>> weaponOptional = weaponUnit.getCurrentWeapon();
        if (weaponOptional.isEmpty()) {
            clear();
            return;
        }
        AbstractVehicleWeapon<?> weapon = weaponOptional.get();
        if (!(weapon instanceof VehicleAntiRadiationMissile antiRadiationMissile)) {
            clear();
            return;
        }
        VehicleAntiRadiationMissileWeaponData data = antiRadiationMissile.getData();
        if (!data.isAntiRadiationPreselectEnabled()) {
            clear();
            return;
        }
        float seekerFov = data.getSeekerFov();
        float seekRange = data.getAntiRadiationSeekRange();
        int pulseMemoryTick = data.getAntiRadiationRadiationPulseMemoryTick();
        float lockedBonus = data.getAntiRadiationLockedBonus();

        if (!weaponUnit.isSeekerOn()) {
            clear();
            return;
        }
        Entity vehicleEntity = player.getVehicle();
        if (!(vehicleEntity instanceof AbstractVehicle vehicle)) {
            clear();
            return;
        }

        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        Vec3 seekerPos = root.worldPivotPosition();
        Vec2 rot = root.worldRot();
        Vec3 seekerLook = VectorUtil.rotToVec(rot.x, rot.y);

        contacts.clear();
        scanContacts(player, vehicle, seekerPos, seekerLook, seekerFov, seekRange, player.tickCount, pulseMemoryTick, lockedBonus, contacts);
        contacts.sort(Comparator.comparingDouble(Contact::score));
        active = true;

        if (cursorIndex >= contacts.size()) {
            cursorIndex = 0;
        }

        if (!contacts.isEmpty()) {
            int lockedIndex = -1;
            if (lockedVehicleId >= 0 && lockedRadarIndex >= 0) {
                for (int i = 0; i < contacts.size(); i++) {
                    Contact c = contacts.get(i);
                    if (c.vehicleId == lockedVehicleId && c.radarIndex == lockedRadarIndex) {
                        lockedIndex = i;
                        break;
                    }
                }
            }
            if (lockedIndex >= 0) {
                cursorIndex = lockedIndex;
                ensureLocked(contacts.get(cursorIndex));
            } else {
                cursorIndex = 0;
                ensureLocked(contacts.get(0));
            }
        }
    }

    public static void selectPrev() {
        if (!active || contacts.isEmpty()) {
            return;
        }
        cursorIndex = (cursorIndex - 1 + contacts.size()) % contacts.size();
        ensureLocked(contacts.get(cursorIndex));
    }

    public static void selectNext() {
        if (!active || contacts.isEmpty()) {
            return;
        }
        cursorIndex = (cursorIndex + 1) % contacts.size();
        ensureLocked(contacts.get(cursorIndex));
    }

    private static void clear() {
        active = false;
        contacts.clear();
        cursorIndex = 0;
        if (lockedVehicleId >= 0 || lockedRadarIndex >= 0 || lockedKeySent != Long.MIN_VALUE) {
            lockedVehicleId = -1;
            lockedRadarIndex = -1;
            lockedKeySent = Long.MIN_VALUE;
            RvpNetwork.CHANNEL.sendToServer(C2SSetAntiRadiationPreselect.clear());
        }
    }

    private static void ensureLocked(Contact c) {
        lockedVehicleId = c.vehicleId;
        lockedRadarIndex = c.radarIndex;
        long key = AntiRadiationSeekerHelper.emitterKey(c.vehicleId, c.radarIndex);
        if (key != lockedKeySent) {
            lockedKeySent = key;
            RvpNetwork.CHANNEL.sendToServer(C2SSetAntiRadiationPreselect.set(lockedVehicleId, lockedRadarIndex, c.position));
        }
    }

    private static void scanContacts(LocalPlayer player, AbstractVehicle excludeVehicle, Vec3 seekerPos, Vec3 seekerLook, float seekerFov, float seekRange, int tickCount, int pulseMemoryTick, float lockedBonus, List<Contact> out) {
        List<AbstractVehicle> candidates = new ArrayList<>();
        HashSet<Integer> ids = new HashSet<>();
        double range = Math.max(seekRange, 1f);
        AABB searchBox = AABB.ofSize(seekerPos, range * 2.0, range * 2.0, range * 2.0);
        for (AbstractVehicle v : player.level().getEntitiesOfClass(AbstractVehicle.class, searchBox, entity -> excludeVehicle == null || entity != excludeVehicle)) {
            if (ids.add(v.getId())) {
                candidates.add(v);
            }
        }
        for (LocalVehiclePlayer.ServerEntity serverEntity : LocalVehiclePlayer.instance.serverEntities.values()) {
            if (serverEntity == null || serverEntity.entity == null) {
                continue;
            }
            if (serverEntity.entity instanceof AbstractVehicle v && (excludeVehicle == null || v != excludeVehicle)) {
                if (ids.add(v.getId())) {
                    candidates.add(v);
                }
            }
        }

        for (AbstractVehicle vehicle : candidates) {
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (!(partUnit instanceof RadarUnit radarUnit)) {
                    continue;
                }
                if (!radarUnit.isOn()) {
                    continue;
                }
                Vec3 radarPos = radarUnit.worldRadarPosition();
                double distance = radarPos.distanceTo(seekerPos);
                if (distance > seekRange) {
                    continue;
                }
                Vec3 toRadar = radarPos.subtract(seekerPos);
                double angle = Math.toDegrees(VectorUtil.angleBetween(seekerLook, toRadar));
                if (angle > seekerFov) {
                    continue;
                }

                boolean locked = radarUnit.getLockedEntity() != null;
                boolean visible;
                if (locked) {
                    visible = true;
                } else {
                    long key = AntiRadiationSeekerHelper.emitterKey(vehicle.getId(), radarUnit.getIndex());
                    if (isScanRadiatingToSeekerClient(radarUnit, seekerPos, tickCount)) {
                        pulseTickMap.put(key, tickCount);
                        visible = true;
                    } else if (pulseMemoryTick > 0) {
                        Integer last = pulseTickMap.get(key);
                        visible = last != null && tickCount - last <= pulseMemoryTick;
                    } else {
                        visible = false;
                    }
                }

                if (!visible) {
                    continue;
                }

                double score = AntiRadiationSeekerHelper.score(seekerPos, seekerLook, seekerFov, seekRange, radarPos, locked, lockedBonus);
                out.add(new Contact(vehicle.getId(), radarUnit.getIndex(), radarPos, locked, score));
            }
        }
    }

    private static boolean isScanRadiatingToSeekerClient(RadarUnit radarUnit, Vec3 seekerPos, int tickCount) {
        int periodTick = 20;
        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        if (data instanceof org.ywzj.rvp.ext.RadarUnitDataExt ext) {
            int scanPeriodTick = ext.ywzj_rvp$getScanPeriodTick();
            if (scanPeriodTick > 0) {
                periodTick = scanPeriodTick;
            }
        }
        if (periodTick <= 0) {
            periodTick = 20;
        }
        int phase = Math.abs((radarUnit.getVehicle().getId() * 31) ^ (radarUnit.getIndex() * 131)) % periodTick;
        if ((tickCount + phase) % periodTick != 0) {
            return false;
        }
        Vec2 aimRot = radarUnit.aimRot(seekerPos);
        if (aimRot.y < radarUnit.getYRotMin() || aimRot.y > radarUnit.getYRotMax()) {
            return false;
        }
        if (Math.abs(aimRot.x - radarUnit.getXRot()) > radarUnit.getScanSectorAngle() / 2.0f) {
            return false;
        }
        return true;
    }
}
