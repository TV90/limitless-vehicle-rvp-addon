package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.ext.WeaponUnitArmExt;
import org.ywzj.rvp.network.C2SSetArmPreselect;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class RVP_ClientArmState {

    public record Contact(int vehicleId, int radarIndex, Vec3 position, boolean locked, double score) {
    }

    private static final RVP_ClientArmState INSTANCE = new RVP_ClientArmState();

    private boolean active;
    private final List<Contact> contacts = new ArrayList<>();
    private final Map<Long, Integer> pulseTickMap = new HashMap<>();
    private int cursorIndex;
    private int lockedVehicleId = -1;
    private int lockedRadarIndex = -1;
    private long lockedKeySent = Long.MIN_VALUE;
    /** 连续无 contact 的 tick 数，用于去抖动。 */
    private int lostTicks = 0;
    private int maxLostTicks = 25; // 默认 pulseMemoryTick

    private RVP_ClientArmState() {
    }

    public static RVP_ClientArmState getInstance() {
        return INSTANCE;
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            clear();
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            clear();
            return;
        }
        var weaponOpt = weaponUnit.getCurrentWeapon();
        if (weaponOpt.isEmpty()) {
            clear();
            return;
        }
        var weapon = weaponOpt.get();
        if (!(weapon.getData() instanceof RVP_WeaponData rvpData)) {
            clear();
            return;
        }
        if (!rvpData.isAntiRadiationMissile()) {
            clear();
            return;
        }
        if (!weaponUnit.isSeekerOn()) {
            clear();
            return;
        }
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            clear();
            return;
        }

        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (!(root instanceof WeaponUnitArmExt ext)) {
            clear();
            return;
        }

        float seekerFov = 60f;
        float seekRange = 900f;
        int pulseMemoryTick = 25;
        float lockedBonus = 0.5f;

        var stages = rvpData.getGuidanceData().getStages();
        if (stages != null) {
            for (var stage : stages) {
                var sources = stage.getSources();
                if (sources == null) continue;
                for (var source : sources) {
                    if (source.getType() == org.ywzj.rvp.guidance.RVP_EnumGuidanceType.ARM) {
                        seekerFov = stage.getSeeker().getFov();
                        seekRange = stage.getSeeker().getRange();
                        var params = source.getParams();
                        if (params != null) {
                            pulseMemoryTick = params.radiationPulseMemoryTick(25);
                            lockedBonus = params.lockedBonus(0.5f);
                        }
                        break;
                    }
                }
            }
        }

        // Use weapon mount pivot position + rotation (same as old approach)
        Vec3 seekerPos = root.worldPivotPosition();
        Vec2 rot = root.worldRot();
        Vec3 seekerLook = VectorUtil.rotToVec(rot.x, rot.y);

        contacts.clear();
        scanContacts(player, vehicle, seekerPos, seekerLook, seekerFov, seekRange, player.tickCount, pulseMemoryTick, lockedBonus, contacts);
        contacts.sort(Comparator.comparingDouble(Contact::score));
        active = true;
        lostTicks = 0;
        maxLostTicks = pulseMemoryTick; // 记忆时间内的脉冲闪烁不丢锁

        if (cursorIndex >= contacts.size()) {
            cursorIndex = 0;
        }

        if (!contacts.isEmpty()) {
            int lockedIndex = -1;
            if (lockedVehicleId >= 0 && lockedRadarIndex >= 0) {
                for (int i = 0; i < contacts.size(); i++) {
                    Contact c = contacts.get(i);
                    if (c.vehicleId() == lockedVehicleId && c.radarIndex() == lockedRadarIndex) {
                        lockedIndex = i;
                        break;
                    }
                }
            }
            if (lockedIndex >= 0) {
                cursorIndex = lockedIndex;
                ensureLocked(contacts.get(cursorIndex), ext);
            } else {
                cursorIndex = 0;
                ensureLocked(contacts.get(0), ext);
            }
        } else {
            // 无 contact 时不清理预设——脉冲间歇期保持目标，等下次脉冲回来继续用
            // 除非 rvp 明确告知 seeker 关闭
        }
    }

    public void selectPrev() {
        if (!active || contacts.isEmpty()) {
            return;
        }
        cursorIndex = (cursorIndex - 1 + contacts.size()) % contacts.size();
        WeaponUnitArmExt ext = getExt();
        if (ext != null) {
            ensureLocked(contacts.get(cursorIndex), ext);
        }
    }

    public void selectNext() {
        if (!active || contacts.isEmpty()) {
            return;
        }
        cursorIndex = (cursorIndex + 1) % contacts.size();
        WeaponUnitArmExt ext = getExt();
        if (ext != null) {
            ensureLocked(contacts.get(cursorIndex), ext);
        }
    }

    private void clear() {
        active = false;
        contacts.clear();
        cursorIndex = 0;
        lostTicks = 0;
        if (lockedVehicleId >= 0 || lockedRadarIndex >= 0 || lockedKeySent != Long.MIN_VALUE) {
            lockedVehicleId = -1;
            lockedRadarIndex = -1;
            lockedKeySent = Long.MIN_VALUE;
            WeaponUnitArmExt ext = getExt();
            if (ext != null) {
                ext.ywzj_rvp$setArmPreselected(-1, -1, null);
            }
            RVP_Network.CHANNEL.sendToServer(C2SSetArmPreselect.clear());
        }
    }

    private void ensureLocked(Contact c, WeaponUnitArmExt ext) {
        if (c.vehicleId() == lockedVehicleId && c.radarIndex() == lockedRadarIndex) {
            return;
        }
        lockedVehicleId = c.vehicleId();
        lockedRadarIndex = c.radarIndex();
        long key = AntiRadiationSeekerHelper.emitterKey(c.vehicleId(), c.radarIndex());
        if (key != lockedKeySent) {
            lockedKeySent = key;
            ext.ywzj_rvp$setArmPreselected(lockedVehicleId, lockedRadarIndex, c.position());
            RVP_Network.CHANNEL.sendToServer(C2SSetArmPreselect.set(lockedVehicleId, lockedRadarIndex, c.position()));
        }
    }

    private static void scanContacts(LocalPlayer player, AbstractVehicle excludeVehicle, Vec3 seekerPos, Vec3 seekerLook, float seekerFov, float seekRange, int tickCount, int pulseMemoryTick, float lockedBonus, List<Contact> out) {
        List<AbstractVehicle> candidates = new ArrayList<>();
        HashSet<Integer> ids = new HashSet<>();
        double range = Math.max(seekRange, 1f);
        AABB searchBox = AABB.ofSize(seekerPos, range * 2.0, range * 2.0, range * 2.0);
        for (AbstractVehicle v : player.level().getEntitiesOfClass(AbstractVehicle.class, searchBox, entity -> entity != excludeVehicle)) {
            if (ids.add(v.getId())) {
                candidates.add(v);
            }
        }
        for (LocalVehiclePlayer.ServerEntity serverEntity : LocalVehiclePlayer.instance.serverEntities.values()) {
            if (serverEntity == null || serverEntity.entity == null) continue;
            if (serverEntity.entity instanceof AbstractVehicle v && v != excludeVehicle) {
                if (ids.add(v.getId())) {
                    candidates.add(v);
                }
            }
        }

        for (AbstractVehicle vehicle : candidates) {
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (!(partUnit instanceof RadarUnit radarUnit)) continue;
                if (!radarUnit.isOn()) continue;

                Vec3 radarPos = radarUnit.worldRadarPosition();
                double distance = radarPos.distanceTo(seekerPos);
                if (distance > seekRange) continue;

                Vec3 toRadar = radarPos.subtract(seekerPos);
                double angle = Math.toDegrees(VectorUtil.angleBetween(seekerLook, toRadar));
                if (angle > seekerFov) continue;

                boolean locked = radarUnit.getLockedEntity() != null;
                boolean visible;
                if (locked) {
                    visible = true;
                } else {
                    long key = AntiRadiationSeekerHelper.emitterKey(vehicle.getId(), radarUnit.getIndex());
                    if (isScanRadiatingToSeekerClient(radarUnit, seekerPos, tickCount)) {
                        INSTANCE.pulseTickMap.put(key, tickCount);
                        visible = true;
                    } else if (pulseMemoryTick > 0) {
                        Integer last = INSTANCE.pulseTickMap.get(key);
                        visible = last != null && tickCount - last <= pulseMemoryTick;
                    } else {
                        visible = false;
                    }
                }

                if (!visible) continue;

                double score = AntiRadiationSeekerHelper.score(seekerPos, seekerLook, seekerFov, seekRange, radarPos, locked, lockedBonus);
                out.add(new Contact(vehicle.getId(), radarUnit.getIndex(), radarPos, locked, score));
            }
        }
    }

    private static boolean isScanRadiatingToSeekerClient(RadarUnit radarUnit, Vec3 seekerPos, int tickCount) {
        int periodTick = 20;
        if (radarUnit.getData() instanceof org.ywzj.rvp.ext.RadarUnitDataExt ext) {
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
        return !(Math.abs(aimRot.x - radarUnit.getXRot()) > radarUnit.getScanSectorAngle() / 2.0f);
    }

    private static WeaponUnitArmExt getExt() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) return null;
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        return root instanceof WeaponUnitArmExt ext ? ext : null;
    }

    public boolean isActive() {
        return active;
    }

    public List<Contact> getContacts() {
        return contacts;
    }

    public int getLockedVehicleId() {
        return lockedVehicleId;
    }

    public int getLockedRadarIndex() {
        return lockedRadarIndex;
    }
}
