package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.rvp.uav.RVP_DeployableUavLinkRegistry;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ServerDebugCommands {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_ServerDebugCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("rvpdebug")
                        .then(Commands.literal("dualpulse")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_DualPulseDebug.clearLog();
                                    RVP_DualPulseDebug.setEnabled(true);
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已开启 dualpulse 调试: " + RVP_DualPulseDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_DualPulseDebug.setEnabled(false);
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已关闭 dualpulse 调试: " + RVP_DualPulseDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_DualPulseDebug.isEnabled();
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] dualpulse=" + enabled + " path=" + RVP_DualPulseDebug.getLogPath()), false);
                                    return enabled ? 1 : 0;
                                }))
                                .then(Commands.literal("dump").executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    writeLog(RVP_DualPulseDebug.getLogPath(), RVP_DualPulseDebug.dumpSnapshot(player));
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已写入 " + RVP_DualPulseDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("clear").executes(ctx -> {
                                    RVP_DualPulseDebug.clearLog();
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已清空 dualpulse 调试日志: " + RVP_DualPulseDebug.getLogPath()), false);
                                    return 1;
                                })))
                        .then(Commands.literal("uav")
                                .then(Commands.literal("deploy").executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    RVP_DeployableUavService.DeployResult result = RVP_DeployableUavService.deployLinkedUav(player);
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] deploy_uav=" + result.name().toLowerCase()), false);
                                    return result == RVP_DeployableUavService.DeployResult.SUCCESS ? 1 : 0;
                                }))
                                .then(Commands.literal("switch").executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    boolean ok = RVP_DeployableUavService.switchToLinkedUav(player);
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] switch_to_linked_uav=" + ok), false);
                                    return ok ? 1 : 0;
                                }))
                                .then(Commands.literal("back").executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    boolean ok = RVP_DeployableUavService.switchBackToParent(player);
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] switch_back_parent=" + ok), false);
                                    return ok ? 1 : 0;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    String status = buildUavStatus(player);
                                    ctx.getSource().sendSuccess(() -> Component.literal(status), false);
                                    return 1;
                                })))
                        .then(Commands.literal("radar")
                                .then(Commands.literal("status").executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    String status = buildExternalRadarStatus(player);
                                    ctx.getSource().sendSuccess(() -> Component.literal(status), false);
                                    return 1;
                                }))
                                .then(Commands.literal("dump").executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    String status = buildExternalRadarStatus(player);
                                    Path path = getExternalRadarLogPath();
                                    writeLog(path, status + System.lineSeparator());
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已写入 external radar 调试日志: " + path), false);
                                    return 1;
                                })))
        );
    }

    private static String buildUavStatus(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle current)) {
            return "[RVP] 当前未在载具内";
        }
        if (!(current instanceof AbstractVehicleLinkedUavExt ext)) {
            return "[RVP] 当前载具无 linked_uav 运行时状态";
        }
        return "[RVP] current=" + current.getVehicleId()
                + " parent=" + ext.ywzj_rvp$getLinkedParentVehicleUuid()
                + " child=" + ext.ywzj_rvp$getLinkedChildVehicleUuid()
                + " launcher=" + ext.ywzj_rvp$getLinkedLauncherVehicleUuid()
                + " deployable=" + ext.ywzj_rvp$isDeployableUavInstance()
                + " role=" + ext.ywzj_rvp$getDeployableUavRole()
                + " datalink=" + ext.ywzj_rvp$getDatalinkRole()
                + " returnSeat=" + ext.ywzj_rvp$getReturnSeatIndex();
    }

    private static String buildExternalRadarStatus(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle launcher)) {
            return "[RVP] 当前未在载具内";
        }
        StringBuilder sb = new StringBuilder("[RVP][radar]");
        sb.append(" launcher=").append(launcher.getVehicleId());

        PartUnit<?> operatorUnit = launcher.getOwnOperatorUnit(player);
        sb.append(" operatorPart=").append(operatorUnit == null ? "null" : operatorUnit.getId())
                .append(":").append(operatorUnit == null ? "null" : operatorUnit.getClass().getSimpleName());

        WeaponUnit weaponUnit = operatorUnit instanceof WeaponUnit unit ? unit.getRootParentWeaponUnit() : null;
        if (weaponUnit == null) {
            sb.append(" weaponUnit=null");
        } else {
            sb.append(" weaponUnit=").append(weaponUnit.getId())
                    .append(" sensor=").append(weaponUnit.getFireControlSensorType())
                    .append(" localLocked=").append(describeEntity(weaponUnit.getLockedEntity()))
                    .append(" radarUnits=").append(weaponUnit.getRadarUnits().size());
            if (weaponUnit instanceof WeaponUnitExternalRadarLockExt ext) {
                sb.append(" requested=").append(ext.ywzj_rvp$getExternalRadarRequestedEntityId())
                        .append(" externalLocked=").append(ext.ywzj_rvp$getExternalRadarLockedEntityId());
            }
            RadarUnit preferred = RVP_RadarRoleHelper.getPreferredLockRadar(weaponUnit);
            sb.append(" preferredLocalRadar=").append(preferred == null ? "null" : preferred.getId());
        }

        java.util.UUID childUuid = null;
        if (launcher instanceof AbstractVehicleLinkedUavExt ext) {
            childUuid = ext.ywzj_rvp$getLinkedChildVehicleUuid();
        }
        if (childUuid == null) {
            childUuid = RVP_DeployableUavLinkRegistry.getChildUuid(launcher.getUUID());
        }
        sb.append(" childUuid=").append(childUuid);

        AbstractVehicle relay = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(launcher).orElse(null);
        if (relay == null) {
            sb.append(" relay=null");
            return sb.toString();
        }
        sb.append(" relay=").append(relay.getVehicleId())
                .append(" relayAlive=").append(relay.isAlive())
                .append(" relayRemoved=").append(relay.isRemoved());

        for (PartUnit<?> partUnit : relay.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radarUnit)) {
                continue;
            }
            sb.append(" | radar=").append(radarUnit.getId())
                    .append(" role=").append(RVP_RadarRoleHelper.getRadarRole(radarUnit))
                    .append(" on=").append(radarUnit.isOn())
                    .append(" locked=").append(describeEntity(radarUnit.getLockedEntity()))
                    .append(" yRot=").append(String.format("%.1f", radarUnit.getYRot()))
                    .append(" ySpeed=").append(String.format("%.1f", radarUnit.getYRotSpeed()));
            if (radarUnit.getData() instanceof RadarUnitDataExt ext) {
                sb.append(" mode=").append(ext.ywzj_rvp$getScanAnimationMode())
                        .append(" h=").append(String.format("%.1f", ext.ywzj_rvp$getScanMinHeight()))
                        .append("~").append(String.format("%.1f", ext.ywzj_rvp$getScanMaxHeight()));
            }
        }
        return sb.toString();
    }

    private static String describeEntity(net.minecraft.world.entity.Entity entity) {
        if (entity == null) {
            return "null";
        }
        return entity.getType().toShortString() + "#" + entity.getId();
    }

    private static Path getExternalRadarLogPath() {
        return Path.of("logs", "externalradardebug.log");
    }

    private static void writeLog(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            LOGGER.error("Failed to write dualpulse debug log to {}", path, e);
        }
    }
}
