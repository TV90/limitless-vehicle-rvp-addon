package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceLaunchConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceModelResolver;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Gunner 调试监控器。
 * 输入 /rvpdebug gunner monitor 启动，每隔 20 tick 将 gunner 状态写入 logs/rvp_gunner_debug.log 和聊天栏。
 * 输入 /rvpdebug gunner stop 停止监控。
 * 纯客户端调试工具：引用 Minecraft/LocalPlayer 等客户端专属类，服务端严禁加载本类
 * （GunnerBrain 已按 dist 隔离调用）。
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_GunnerDebugMonitor {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS");
    private static PrintWriter writer;
    private static boolean active = false;
    private static int tickCounter = 0;
    private static final int INTERVAL_TICK = 20;

    private RVP_GunnerDebugMonitor() {}

    /** 返回 writer 实例（创建日志文件，不清除已有内容），用于一次性 dump 写入。 */
    public static PrintWriter getOneShotWriter() {
        if (FMLEnvironment.dist != Dist.CLIENT) return null;
        PrintWriter pw = getWriter();
        return pw;
    }

    private static PrintWriter getWriter() {
        if (writer == null) {
            try {
                File gameDir = Minecraft.getInstance().gameDirectory;
                File logFile = new File(gameDir, "logs/rvp_gunner_debug.log");
                File parent = logFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                writer = new PrintWriter(new FileWriter(logFile, false));
                writer.println("--- rvp_gunner_debug.log started ---");
                writer.flush();
            } catch (Exception e) {
                System.err.println("[RVP_GunnerDebugMonitor] Failed to create log file: " + e.getMessage());
            }
        }
        return writer;
    }

    public static boolean isActive() {
        return active;
    }

    public static void start() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        active = true;
        tickCounter = 0;
        if (writer != null) {
            writer.close();
            writer = null;
        }
        PrintWriter pw = getWriter();
        if (pw != null) {
            pw.println("=== Monitor started ===");
            pw.flush();
        }
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal("§e[Gunner] §a监控已启动，每 " + INTERVAL_TICK + " tick 写入 logs/rvp_gunner_debug.log"));
        }
    }

    public static void stop() {
        if (FMLEnvironment.dist != Dist.CLIENT) { active = false; tickCounter = 0; return; }
        active = false;
        tickCounter = 0;
        PrintWriter pw = getWriter();
        if (pw != null) {
            pw.println("=== Monitor stopped ===");
            pw.flush();
        }
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal("§e[Gunner] §c监控已停止"));
        }
    }

    /** 由 GunnerBrain.tick() 在每 tick 调用 */
    public static void onTick(GunnerEntity gunner, AbstractVehicle vehicle,
                              @Nullable WeaponUnit weaponUnit, @Nullable Entity target) {
        if (!active) return;
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        tickCounter++;
        if (tickCounter % INTERVAL_TICK != 0) return;

        String dump = buildDump(gunner, vehicle, weaponUnit, target);
        PrintWriter pw = getWriter();
        if (pw != null) {
            pw.println(FMT.format(new Date()) + " [GunnerBrain] " + dump.replace("\n", " | "));
            pw.flush();
        }
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal("§7[Gunner Monitor] §f" + dump.replace("\n", " §7| §f")));
        }
    }

    private static String buildDump(GunnerEntity gunner, AbstractVehicle vehicle,
                                    @Nullable WeaponUnit weaponUnit, @Nullable Entity target) {
        StringBuilder sb = new StringBuilder();
        GunnerProfile profile = GunnerProfileManager.INSTANCE.getProfile(
                GunnerProfileManager.INSTANCE.normalizeProfileId(gunner.getProfileId()));

        // --- basic ---
        sb.append("profile=").append(gunner.getProfileId());
        sb.append(" driver=").append(vehicle.getDriver() == gunner);
        sb.append(" launcher=").append(GunnerBrain.hasLauncherDeployConfig(vehicle));

        // --- weaponUnit null? ---
        sb.append(" wu=").append(weaponUnit != null ? "ok" : "null");

        // --- ammo ---
        boolean hasAny = false;
        for (var pu : vehicle.getPartUnits()) {
            if (!(pu instanceof WeaponUnit wu)) continue;
            for (AbstractVehicleWeapon<?> w : wu.getIndexedWeapons()) {
                var proxy = wu.proxyWeapon(w);
                sb.append(" ammo=").append(proxy.getRemainAmmo()).append("/").append(proxy.getMaxCapacity());
                if (proxy.hasAmmo()) hasAny = true;
            }
        }
        sb.append(" hasAny=").append(hasAny);

        // --- target ---
        if (target != null && target.isAlive()) {
            sb.append(" target=").append(target.getType().toString());
            sb.append(" dist=").append(String.format("%.0f", vehicle.position().distanceTo(target.position())));
            // target altitude AGL
            int groundY = target.level().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    Mth.floor(target.getX()), Mth.floor(target.getZ()));
            double agl = target.getY() - groundY;
            sb.append(" tgtAGL=").append(String.format("%.0f", agl));
            // target velocity
            sb.append(" tgtVel=").append(String.format("%.1f", target.getDeltaMovement().length()));
        } else {
            sb.append(" target=none");
        }

        // --- CIWS ---
        AmmoEntity ciws = GunnerTargeting.findCiwsTarget(gunner, vehicle);
        sb.append(" ciws=").append(ciws != null ? ciws.getType().toString() : "none");

        // --- selected weapon details ---
        int idx = gunner.getControlledWeaponIndex();
        sb.append(" wepIdx=").append(idx);
        if (idx >= 0 && weaponUnit != null) {
            AbstractVehicleWeapon<?> rawWep = idx < weaponUnit.getIndexedWeapons().size()
                    ? weaponUnit.getIndexedWeapons().get(idx) : null;
            if (rawWep != null) {
                AbstractVehicleWeapon<?> proxy = weaponUnit.proxyWeapon(rawWep);
                sb.append(" wepID=").append(proxy.getData() != null && proxy.getData().getWeaponId() != null
                        ? proxy.getData().getWeaponId().toString() : "null");
                sb.append(" cd=").append(proxy.isCoolingDown());
                sb.append(" reload=").append(proxy.isReloading());
                sb.append(" hasAmmo=").append(proxy.hasAmmo());
                // guidance types
                if (proxy instanceof RVP_WeaponBase rvp) {
                    RVP_WeaponData data = rvp.getData();
                    if (data != null) {
                        sb.append(" homing=").append(data.isHomingProjectile());
                        sb.append(" reqLock=").append(data.isRequireLock());
                        sb.append(" guide=");
                        if (data.usesGuidanceType(RVP_EnumGuidanceType.SARH)) sb.append("SARH,");
                        if (data.usesGuidanceType(RVP_EnumGuidanceType.ARH))  sb.append("ARH,");
                        if (data.usesGuidanceType(RVP_EnumGuidanceType.IR))   sb.append("IR,");
                        if (data.usesGuidanceType(RVP_EnumGuidanceType.AIR))  sb.append("AIR,");
                        if (data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)) sb.append("SACLOS,");
                        if (data.usesGuidanceType(RVP_EnumGuidanceType.SALH)) sb.append("SALH,");
                        if (data.usesGuidanceType(RVP_EnumGuidanceType.LBR))  sb.append("LBR,");
                        if (data.usesGuidanceType(RVP_EnumGuidanceType.GPS))  sb.append("GPS,");
                        if (data.isAntiRadiationMissile()) sb.append("ARM,");
                    }
                }
            } else {
                sb.append(" wep=null");
            }

            // --- aim error (fire window check, skipped for launchers) ---
            if (!GunnerBrain.hasLauncherDeployConfig(vehicle) && profile != null) {
                float xErr = Math.abs(Mth.wrapDegrees(weaponUnit.getXRot() - weaponUnit.getXAimRot()));
                float yErr = Math.abs(Mth.wrapDegrees(weaponUnit.getYRot() - weaponUnit.getYAimRot()));
                float window = profile.getFireWindowDeg();
                sb.append(" aimErr=").append(String.format("%.1f/%.1f", xErr, yErr));
                sb.append(" win=").append(String.format("%.1f", window));
                sb.append(" aimOK=").append(xErr <= window && yErr <= window);
            } else if (GunnerBrain.hasLauncherDeployConfig(vehicle)) {
                sb.append(" aimErr=skip(launcher)");
            }

            // --- fire control sensor & radar lock state ---
            if (weaponUnit != null) {
                WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
                WeaponUnitData.FireControlSensorType sensor = root.getFireControlSensorType();
                sb.append(" sensor=").append(sensor);

                // per-radar lock state
                int ri = 0;
                for (RadarUnit ru : weaponUnit.getRadarUnits()) {
                    Entity rLocked = ru.getLockedEntity();
                    sb.append(" rd").append(ri).append("=").append(ru.isOn() ? "on" : "off")
                            .append("_lk=").append(rLocked != null ? rLocked.getType().toString() + "#" + rLocked.getId() : "n");
                    ri++;
                }

                // root locked entity & lockMatch
                Entity locked = root.getLockedEntity();
                sb.append(" locked=").append(locked != null ? locked.getType().toString() + "#" + locked.getId() + "@" + locked.getId() : "none");
                sb.append(" lkMatch=").append(locked != null && target != null && locked.getId() == target.getId());

                // --- prepareLaunchLock envelope simulation (read-only) ---
                if (idx >= 0) {
                    AbstractVehicleWeapon<?> selWep = idx < weaponUnit.getIndexedWeapons().size()
                            ? weaponUnit.getIndexedWeapons().get(idx) : null;
                    if (selWep != null && selWep instanceof RVP_WeaponBase rvp && target != null) {
                        RVP_WeaponData data = rvp.getData();
                        if (data != null && data.isHomingProjectile()) {
                            Vec3 origin = weaponUnit.worldPivotPosition();
                            Vec3 tgtCenter = target.getBoundingBox().getCenter();
                            double dist = origin.distanceTo(tgtCenter);
                            RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
                            // distance envelope
                            RVP_Range<Float> dRange = launch.targetDistanceRange();
                            if (dRange != null && !dRange.intervals().isEmpty()) {
                                var first = dRange.intervals().get(0);
                                var last  = dRange.intervals().get(dRange.intervals().size() - 1);
                                Object dLo = first.lower() != null ? String.format("%.0f", (float)first.lower()) : "0";
                                Object dHi = last.upper()  != null ? String.format("%.0f", (float)last.upper()) : "inf";
                                sb.append(" dEnv=").append(dLo).append("-").append(dHi);
                                sb.append(" dOk=").append(dRange.contains((float)dist));
                            }
                            // altitude envelope
                            RVP_Range<Float> aRange = launch.altitudeRange();
                            double tgtAgl = target.getY() - target.level().getHeight(
                                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                                    Mth.floor(target.getX()), Mth.floor(target.getZ()));
                            if (aRange != null && !aRange.intervals().isEmpty()) {
                                var first = aRange.intervals().get(0);
                                var last  = aRange.intervals().get(aRange.intervals().size() - 1);
                                Object aLo = first.lower() != null ? String.format("%.0f", (float)first.lower()) : "0";
                                Object aHi = last.upper()  != null ? String.format("%.0f", (float)last.upper()) : "inf";
                                sb.append(" aEnv=").append(aLo).append("-").append(aHi);
                                sb.append(" aOk=").append(aRange.contains((float)tgtAgl));
                            }
                            // lock angle: axis to target vs maxLockHalfAngle
                            Vec3 axis = weaponUnit.worldVec();
                            if (axis.lengthSqr() > 1.0E-6) axis = axis.normalize();
                            else {
                                Vec3 vLook = vehicle.getLookAngle();
                                axis = vLook.lengthSqr() > 1.0E-6 ? vLook.normalize() : Vec3.ZERO;
                            }
                            double halfAngle = launch.maxLockHalfAngle();
                            if (axis.lengthSqr() > 1.0E-8 && tgtCenter.subtract(origin).lengthSqr() > 1.0E-8) {
                                double angle = Math.toDegrees(Math.acos(
                                        Mth.clamp(axis.dot(tgtCenter.subtract(origin).normalize()), -1.0, 1.0)));
                                sb.append(" lockAng=").append(String.format("%.1f", angle));
                                sb.append(" angOk=").append(angle <= Math.max(halfAngle, 0.0) + 1.0E-6);
                            } else {
                                sb.append(" lockAng=noAxis");
                            }
                            sb.append(" maxAng=").append(String.format("%.1f", halfAngle));
                        }
                    }
                }
            }
        }

        // --- burst & cooldown ---
        sb.append(" missileCD=").append(gunner.getMissileCooldown());
        sb.append(" burst=").append(gunner.isBurstWindowOpen());
        if (ciws != null) {
            sb.append(" ciwsOnCD=").append(gunner.isCiwsTargetOnCooldown(ciws));
        }

        // --- combat flow hints ---
        boolean canFire = true;
        if (weaponUnit == null) { sb.append(" BLOCK=wuNull"); canFire = false; }
        if (target == null || !target.isAlive()) { sb.append(" BLOCK=noTarget"); canFire = false; }
        if (canFire) {
            // check if tickCombat would actually fire
            int wepIdx = GunnerBrain.findWeaponIndexForDump(weaponUnit, target);
            if (wepIdx < 0) {
                sb.append(" BLOCK=noSuitableWep");
            }
        }

        return sb.toString();
    }
}
