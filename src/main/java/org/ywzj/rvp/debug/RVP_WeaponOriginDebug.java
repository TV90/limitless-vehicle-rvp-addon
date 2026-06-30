package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.mixin.accessor.WeaponUnitAccessor;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WeaponOriginDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("weaponorigindebug.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final double SAME_POS_EPSILON_SQR = 1.0E-6;
    private static final AtomicBoolean FIRE_MONITOR_ENABLED = new AtomicBoolean(false);
    private static final AtomicBoolean VERBOSE_ENABLED = new AtomicBoolean(false);

    private RVP_WeaponOriginDebug() {}

    public static Path getLogPath() {
        return LOG_PATH;
    }

    public static boolean isFireMonitorEnabled() {
        return FIRE_MONITOR_ENABLED.get();
    }

    public static boolean isVerboseEnabled() {
        return VERBOSE_ENABLED.get();
    }

    public static void setFireMonitorEnabled(boolean enabled) {
        FIRE_MONITOR_ENABLED.set(enabled);
        appendFileLog("fireMonitor.enabled=" + enabled);
    }

    public static void setVerboseEnabled(boolean enabled) {
        VERBOSE_ENABLED.set(enabled);
        appendFileLog("verbose.enabled=" + enabled);
    }

    public static void clearLog() {
        try {
            Files.deleteIfExists(LOG_PATH);
        } catch (IOException e) {
            LOGGER.error("[RVP][WeaponOriginDebug] Failed to clear {}", LOG_PATH, e);
        }
    }

    public static void dumpVehicleSnapshot(String reason, AbstractVehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        sb.append("reason=").append(reason).append('\n');
        if (vehicle == null) {
            sb.append("vehicle=<null>\n");
            appendFileLog(sb.toString());
            return;
        }

        sb.append("vehicle.id=").append(vehicle.getVehicleId()).append('\n');
        sb.append("vehicle.class=").append(vehicle.getClass().getName()).append('\n');
        sb.append("vehicle.pos=").append(formatVec(vehicle.position())).append('\n');
        sb.append("vehicle.centerOffset=").append(formatVec(vehicle.centerOffset)).append('\n');
        sb.append("partCount=").append(vehicle.getPartUnits().size()).append('\n');

        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            appendWeaponUnit(sb, weaponUnit, vehicle);
        }

        appendFileLog(sb.toString());
    }

    public static void dumpFireSnapshot(String reason, AbstractVehicle vehicle,
                                        AbstractVehicleWeapon<?> weapon,
                                        LivingEntity operator) {
        StringBuilder sb = new StringBuilder();
        sb.append("reason=").append(reason).append('\n');
        if (vehicle == null) {
            sb.append("vehicle=<null>\n");
            appendFileLog(sb.toString());
            return;
        }

        sb.append("vehicle.id=").append(vehicle.getVehicleId()).append('\n');
        sb.append("vehicle.class=").append(vehicle.getClass().getName()).append('\n');
        sb.append("vehicle.pos=").append(formatVec(vehicle.position())).append('\n');
        sb.append("operator=").append(operator == null ? "<null>" : operator.getName().getString()).append('\n');
        sb.append("weapon.display=").append(weapon == null ? "<null>" : weapon.getDisplayName().getString()).append('\n');
        sb.append("weapon.class=").append(weapon == null ? "<null>" : weapon.getClass().getName()).append('\n');
        sb.append("weapon.index=").append(weapon == null ? "<null>" : weapon.getIndex()).append('\n');

        if (weapon == null) {
            appendFileLog(sb.toString());
            return;
        }

        WeaponUnit firedUnit = weapon.getWeaponUnit();
        WeaponUnit rootUnit = firedUnit == null ? null : firedUnit.getRootParentWeaponUnit();
        if (firedUnit != null) {
            appendWeaponUnit(sb, firedUnit, vehicle, "fired");
        } else {
            sb.append("fired=<null>\n");
        }
        if (rootUnit != null && rootUnit != firedUnit) {
            appendWeaponUnit(sb, rootUnit, vehicle, "root");
        }

        appendFileLog(sb.toString());
    }

    public static void noteShootInvocation(WeaponUnit weaponUnit, int requestedWeaponIndex,
                                           AbstractVehicleWeapon<?> resolvedWeapon,
                                           List<AimContext> aimContexts,
                                           LivingEntity operator) {
        if (!VERBOSE_ENABLED.get()) {
            return;
        }
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        StringBuilder sb = new StringBuilder();
        sb.append("reason=shoot-invoke").append('\n');
        sb.append("vehicle.id=").append(vehicle.getVehicleId()).append('\n');
        sb.append("vehicle.class=").append(vehicle.getClass().getName()).append('\n');
        sb.append("vehicle.pos=").append(formatVec(vehicle.position())).append('\n');
        sb.append("operator=").append(operator == null ? "<null>" : operator.getName().getString()).append('\n');
        sb.append("requestedWeaponIndex=").append(requestedWeaponIndex).append('\n');
        sb.append("resolvedWeapon=").append(resolvedWeapon == null ? "<null>" : resolvedWeapon.getDisplayName().getString()).append('\n');
        sb.append("resolvedWeaponClass=").append(resolvedWeapon == null ? "<null>" : resolvedWeapon.getClass().getName()).append('\n');
        sb.append("resolvedWeaponIndex=").append(resolvedWeapon == null ? "<null>" : resolvedWeapon.getIndex()).append('\n');
        appendWeaponUnit(sb, weaponUnit, vehicle, "shoot");
        appendAimContexts(sb, "shootAim", aimContexts);
        appendFileLog(sb.toString());
    }

    public static void noteDispatchInvocation(AbstractVehicleWeapon<?> weapon, WeaponUnit firedUnit, WeaponUnit rootUnit,
                                              LivingEntity shooter, List<AimContext> aimContexts, float chargeScale) {
        if (!VERBOSE_ENABLED.get()) {
            return;
        }
        AbstractVehicle vehicle = weapon.getVehicle();
        StringBuilder sb = new StringBuilder();
        sb.append("reason=dispatch").append('\n');
        sb.append("vehicle.id=").append(vehicle.getVehicleId()).append('\n');
        sb.append("vehicle.class=").append(vehicle.getClass().getName()).append('\n');
        sb.append("vehicle.pos=").append(formatVec(vehicle.position())).append('\n');
        sb.append("shooter=").append(shooter == null ? "<null>" : shooter.getName().getString()).append('\n');
        sb.append("weapon.display=").append(weapon.getDisplayName().getString()).append('\n');
        sb.append("weapon.class=").append(weapon.getClass().getName()).append('\n');
        sb.append("weapon.index=").append(weapon.getIndex()).append('\n');
        sb.append("chargeScale=").append(chargeScale).append('\n');
        if (firedUnit != null) {
            appendWeaponUnit(sb, firedUnit, vehicle, "dispatchFired");
        }
        if (rootUnit != null && rootUnit != firedUnit) {
            appendWeaponUnit(sb, rootUnit, vehicle, "dispatchRoot");
        }
        appendAimContexts(sb, "dispatchAim", aimContexts);
        appendFileLog(sb.toString());
    }

    public static void noteSpawnInvocation(AbstractVehicle vehicle, ResourceRef ref, WeaponUnit weaponUnit,
                                           AimContext aim, Vec3 muzzle, Vec3 initialMotion,
                                           float xRot, float yRot, boolean includeFireSpread,
                                           float powerScale, float extraSpread) {
        if (!VERBOSE_ENABLED.get()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("reason=spawn").append('\n');
        sb.append("vehicle.id=").append(vehicle.getVehicleId()).append('\n');
        sb.append("vehicle.class=").append(vehicle.getClass().getName()).append('\n');
        sb.append("vehicle.pos=").append(formatVec(vehicle.position())).append('\n');
        sb.append("weaponId=").append(ref.weaponId == null ? "<null>" : ref.weaponId).append('\n');
        sb.append("weaponKind=").append(ref.weaponKind).append('\n');
        sb.append("includeFireSpread=").append(includeFireSpread).append('\n');
        sb.append("powerScale=").append(powerScale).append('\n');
        sb.append("extraSpread=").append(extraSpread).append('\n');
        sb.append("spawn.muzzle=").append(formatVec(muzzle)).append('\n');
        sb.append("spawn.initialMotion=").append(formatVec(initialMotion)).append('\n');
        sb.append("spawn.finalAimRot=").append(formatRot(new Vec2(xRot, yRot))).append('\n');
        sb.append("spawn.sourceAim.from=").append(formatVec(aim == null ? null : aim.from)).append('\n');
        sb.append("spawn.sourceAim.direction=").append(formatRot(aim == null ? null : aim.direction)).append('\n');
        sb.append("spawn.sourceAim.position=").append(formatVec(aim == null ? null : aim.position)).append('\n');
        if (weaponUnit != null) {
            appendWeaponUnit(sb, weaponUnit, vehicle, "spawnWeaponUnit");
        }
        appendFileLog(sb.toString());
    }

    @SubscribeEvent
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        if (!FIRE_MONITOR_ENABLED.get() || event.isClientSide()) {
            return;
        }
        dumpFireSnapshot("fire-post", event.getVehicle(), event.getWeapon(), event.getOperator());
    }

    private static void appendWeaponUnit(StringBuilder sb, WeaponUnit weaponUnit, AbstractVehicle vehicle) {
        appendWeaponUnit(sb, weaponUnit, vehicle, "weapon");
    }

    private static void appendWeaponUnit(StringBuilder sb, WeaponUnit weaponUnit, AbstractVehicle vehicle, String prefix) {
        WeaponUnitData data = weaponUnit.getData();
        VehicleCubeGroup rawStructureGroup = data.getRawStructureGroup();
        VehicleCubeGroup rawXTurnGroup = data.getRawXTurnGroup();
        VehicleCubeGroup instanceStructureGroup = weaponUnit.getStructureGroup();
        VehicleCubeGroup instanceXTurnGroup = ((WeaponUnitAccessor) weaponUnit).getXTurnGroup();
        String structureBone = data.getStructureBone();
        String expectedBarrelBone = structureBone == null ? "<null>" : structureBone + "_barrel";
        WeaponUnit parent = weaponUnit.getParentWeaponUnit();
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        AimContext aimContext = weaponUnit.aimContext();
        Bolt currentBolt = weaponUnit.getCurrentBolt();
        Vec3 worldCurrentBolt = instanceXTurnGroup == null ? null : weaponUnit.worldCurrentBoltPosition();
        AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().orElse(null);

        sb.append('\n');
        sb.append("[").append(prefix).append("_weapon_unit]").append('\n');
        sb.append(prefix).append(".id=").append(weaponUnit.getId()).append('\n');
        sb.append(prefix).append(".index=").append(weaponUnit.getIndex()).append('\n');
        sb.append(prefix).append(".structureBone=").append(nullToMarker(structureBone)).append('\n');
        sb.append(prefix).append(".expectedBarrelBone=").append(expectedBarrelBone).append('\n');
        sb.append(prefix).append(".parentId=").append(parent == null ? "<null>" : parent.getId()).append('\n');
        sb.append(prefix).append(".rootId=").append(root == null ? "<null>" : root.getId()).append('\n');
        sb.append(prefix).append(".parentWeaponUnitAim=").append(weaponUnit.isParentWeaponUnitAim()).append('\n');
        sb.append(prefix).append(".rawStructureGroup=").append(rawStructureGroup != null).append('\n');
        sb.append(prefix).append(".rawXTurnGroup=").append(rawXTurnGroup != null).append('\n');
        sb.append(prefix).append(".instanceStructureGroup=").append(instanceStructureGroup != null).append('\n');
        sb.append(prefix).append(".instanceXTurnGroup=").append(instanceXTurnGroup != null).append('\n');
        sb.append(prefix).append(".rawXEqualsRawStructure=").append(rawXTurnGroup != null && rawXTurnGroup == rawStructureGroup).append('\n');
        sb.append(prefix).append(".instanceXEqualsStructure=").append(instanceXTurnGroup != null && instanceXTurnGroup == instanceStructureGroup).append('\n');
        sb.append(prefix).append(".partCubeObbCount=").append(weaponUnit.getPartCubeOBBs() == null ? 0 : weaponUnit.getPartCubeOBBs().size()).append('\n');
        sb.append(prefix).append(".pivotOffset(data)=").append(formatVec(data.getPivotOffset())).append('\n');
        sb.append(prefix).append(".vehicle.position=").append(formatVec(vehicle.position())).append('\n');
        sb.append(prefix).append(".worldPivotPosition=").append(formatVec(weaponUnit.worldPivotPosition())).append('\n');
        sb.append(prefix).append(".aim.from=").append(formatVec(aimContext == null ? null : aimContext.from)).append('\n');
        sb.append(prefix).append(".aim.direction=").append(formatRot(aimContext == null ? null : aimContext.direction)).append('\n');
        sb.append(prefix).append(".fallbackToVehiclePos=").append(isSamePos(aimContext == null ? null : aimContext.from, vehicle.position())).append('\n');
        sb.append(prefix).append(".currentBolt.offset=").append(formatVec(currentBolt == null ? null : currentBolt.offset)).append('\n');
        sb.append(prefix).append(".currentBolt.barrelLength=").append(currentBolt == null ? "<null>" : currentBolt.barrelLength).append('\n');
        sb.append(prefix).append(".currentBolt.xRot=").append(currentBolt == null ? "<null>" : currentBolt.xRot).append('\n');
        sb.append(prefix).append(".currentBolt.yRot=").append(currentBolt == null ? "<null>" : currentBolt.yRot).append('\n');
        sb.append(prefix).append(".worldCurrentBoltPosition=").append(formatVec(worldCurrentBolt)).append('\n');
        sb.append(prefix).append(".currentWeapon=").append(currentWeapon == null ? "<null>" : currentWeapon.getDisplayName().getString()).append('\n');
        sb.append(prefix).append(".currentWeaponClass=").append(currentWeapon == null ? "<null>" : currentWeapon.getClass().getName()).append('\n');
        sb.append(prefix).append(".currentWeaponIndex=").append(currentWeapon == null ? "<null>" : currentWeapon.getIndex()).append('\n');
        sb.append(prefix).append(".weaponUnitCurrentWeaponIndex=").append(((WeaponUnitAccessor) weaponUnit).getCurrentWeaponIndex()).append('\n');

        if (data instanceof WeaponUnitDataExt ext) {
            sb.append(prefix).append(".ext.followParentOnlyIds=").append(ext.ywzj_rvp$getFollowParentOnlyPartUnitIds()).append('\n');
            sb.append(prefix).append(".ext.structureBoltBones=").append(ext.ywzj_rvp$getStructureBoltBones()).append('\n');
        }
    }

    private static void appendAimContexts(StringBuilder sb, String prefix, List<AimContext> aimContexts) {
        int count = aimContexts == null ? 0 : aimContexts.size();
        sb.append(prefix).append(".count=").append(count).append('\n');
        if (aimContexts == null) {
            return;
        }
        for (int i = 0; i < aimContexts.size(); i++) {
            AimContext aim = aimContexts.get(i);
            sb.append(prefix).append('[').append(i).append("].from=").append(formatVec(aim == null ? null : aim.from)).append('\n');
            sb.append(prefix).append('[').append(i).append("].direction=").append(formatRot(aim == null ? null : aim.direction)).append('\n');
            sb.append(prefix).append('[').append(i).append("].position=").append(formatVec(aim == null ? null : aim.position)).append('\n');
        }
    }

    private static String formatVec(Vec3 vec) {
        if (vec == null) {
            return "<null>";
        }
        return String.format("(%.4f, %.4f, %.4f)", vec.x, vec.y, vec.z);
    }

    private static String formatRot(Vec2 rot) {
        if (rot == null) {
            return "<null>";
        }
        return String.format("(pitch=%.4f, yaw=%.4f)", rot.x, rot.y);
    }

    private static boolean isSamePos(Vec3 a, Vec3 b) {
        return a != null && b != null && a.distanceToSqr(b) <= SAME_POS_EPSILON_SQR;
    }

    private static String nullToMarker(String value) {
        return value == null ? "<null>" : value;
    }

    private static synchronized void appendFileLog(String message) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[RVP][WeaponOriginDebug] Failed to append debug log to {}", LOG_PATH, e);
        }
    }

    public record ResourceRef(String weaponId, String weaponKind) {}
}
