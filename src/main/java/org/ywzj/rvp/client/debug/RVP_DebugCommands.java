package org.ywzj.rvp.client.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.rvp.debug.RVP_AheadDebug;
import org.ywzj.rvp.debug.RVP_HitboxDebug;
import org.ywzj.rvp.debug.RVP_WeaponOriginDebug;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_DebugCommands {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path LOG_PATH = FMLPaths.CONFIGDIR.get().resolve("rvpui.log");
    private static final Path HITBOX_RESOLVE_LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("hitboxresolve.log");

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("rvpdebug")
                        .then(Commands.literal("tvMissileDump").executes(ctx -> {
                            RVP_TVMissileDebug.requestDump();
                            LOGGER.info("[RVP][TVMissile] tvMissileDump requested");
                            ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.tv_missile.dump"), false);
                            return 1;
                        }))
                        .then(Commands.literal("tvMissileBwSpam")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_TVMissileDebug.setBwSpamEnabled(true);
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.tv_missile.bw_spam.on"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_TVMissileDebug.setBwSpamEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.tv_missile.bw_spam.off"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_TVMissileDebug.isBwSpamEnabled();
                                    Component state = Component.translatable(enabled ? "commands.ywzj_rvp.state.on" : "commands.ywzj_rvp.state.off");
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.tv_missile.bw_spam.status", state), false);
                                    return enabled ? 1 : 0;
                                }))
                        )
                        .then(Commands.literal("ahead")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_AheadDebug.setEnabled(true);
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.ahead.on"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_AheadDebug.setEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.ahead.off"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_AheadDebug.isEnabled();
                                    Component state = Component.translatable(enabled ? "commands.ywzj_rvp.state.on" : "commands.ywzj_rvp.state.off");
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.ahead.status", state), false);
                                    return enabled ? 1 : 0;
                                }))
                        )
                        .then(Commands.literal("hitboxlog")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_HitboxDebug.clearLog();
                                    RVP_HitboxDebug.setEnabled(true);
                                    RVP_HitboxDebug.dumpVehicleSnapshot("command-on", LocalVehiclePlayer.instance.getVehicle());
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已开启 hitboxlog: " + RVP_HitboxDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_HitboxDebug.setEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已关闭 hitboxlog: " + RVP_HitboxDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_HitboxDebug.isEnabled();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] hitboxlog=" + enabled + " path=" + RVP_HitboxDebug.getLogPath()), false);
                                    return enabled ? 1 : 0;
                                }))
                                .then(Commands.literal("dump").executes(ctx -> {
                                    RVP_HitboxDebug.dumpVehicleSnapshot("command-dump", LocalVehiclePlayer.instance.getVehicle());
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已立即写入 hitboxlog: " + RVP_HitboxDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("clear").executes(ctx -> {
                                    RVP_HitboxDebug.clearLog();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已清空 hitboxlog: " + RVP_HitboxDebug.getLogPath()), false);
                                    return 1;
                                }))
                        )
                        .then(Commands.literal("hitboxresolve")
                                .then(Commands.literal("dump").executes(ctx -> {
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance.getVehicle();
                                    String content = RVP_VehicleHitboxFactorManager.INSTANCE.dumpResolveDebug(vehicle);
                                    writeLog(HITBOX_RESOLVE_LOG_PATH, content);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已写入 " + HITBOX_RESOLVE_LOG_PATH), false);
                                    return vehicle == null ? 0 : 1;
                                }))
                        )
                        .then(Commands.literal("weaponorigin")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_WeaponOriginDebug.clearLog();
                                    RVP_WeaponOriginDebug.setFireMonitorEnabled(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已开启 weaponorigin 开火监控: " + RVP_WeaponOriginDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_WeaponOriginDebug.setFireMonitorEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已关闭 weaponorigin 开火监控: " + RVP_WeaponOriginDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("verbose")
                                        .then(Commands.literal("on").executes(ctx -> {
                                            RVP_WeaponOriginDebug.setVerboseEnabled(true);
                                            ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已开启 weaponorigin 详细发射链追踪: " + RVP_WeaponOriginDebug.getLogPath()), false);
                                            return 1;
                                        }))
                                        .then(Commands.literal("off").executes(ctx -> {
                                            RVP_WeaponOriginDebug.setVerboseEnabled(false);
                                            ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已关闭 weaponorigin 详细发射链追踪: " + RVP_WeaponOriginDebug.getLogPath()), false);
                                            return 1;
                                        }))
                                        .then(Commands.literal("status").executes(ctx -> {
                                            boolean enabled = RVP_WeaponOriginDebug.isVerboseEnabled();
                                            ctx.getSource().sendSuccess(() -> Component.literal("[RVP] weaponorigin.verbose=" + enabled + " path=" + RVP_WeaponOriginDebug.getLogPath()), false);
                                            return enabled ? 1 : 0;
                                        }))
                                )
                                .then(Commands.literal("dump").executes(ctx -> {
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.getVehicle();
                                    RVP_WeaponOriginDebug.dumpVehicleSnapshot("command-dump", vehicle);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已写入 weaponorigin 调试日志: " + RVP_WeaponOriginDebug.getLogPath()), false);
                                    return vehicle == null ? 0 : 1;
                                }))
                                .then(Commands.literal("clear").executes(ctx -> {
                                    RVP_WeaponOriginDebug.clearLog();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已清空 weaponorigin 调试日志: " + RVP_WeaponOriginDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_WeaponOriginDebug.isFireMonitorEnabled();
                                    boolean verbose = RVP_WeaponOriginDebug.isVerboseEnabled();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] weaponorigin=" + enabled + " verbose=" + verbose + " path=" + RVP_WeaponOriginDebug.getLogPath()), false);
                                    return enabled ? 1 : 0;
                                }))
                        )
                        .then(Commands.literal("custommount")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_CustomMountRenderLogic.clearDebugLog();
                                    RVP_CustomMountRenderLogic.setDebugEnabled(true);
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance.getVehicle();
                                    if (vehicle != null) {
                                        writeLog(RVP_CustomMountRenderLogic.getDebugLogPath(),
                                                RVP_CustomMountRenderLogic.dumpDebugSnapshot(vehicle));
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已开启 custommount 调试: " + RVP_CustomMountRenderLogic.getDebugLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_CustomMountRenderLogic.setDebugEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已关闭 custommount 调试: " + RVP_CustomMountRenderLogic.getDebugLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_CustomMountRenderLogic.isDebugEnabled();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] custommount=" + enabled + " path=" + RVP_CustomMountRenderLogic.getDebugLogPath()), false);
                                    return enabled ? 1 : 0;
                                }))
                                .then(Commands.literal("dump").executes(ctx -> {
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance.getVehicle();
                                    String content = RVP_CustomMountRenderLogic.dumpDebugSnapshot(vehicle);
                                    writeLog(RVP_CustomMountRenderLogic.getDebugLogPath(), content);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已写入 " + RVP_CustomMountRenderLogic.getDebugLogPath()), false);
                                    return vehicle == null ? 0 : 1;
                                }))
                                .then(Commands.literal("clear").executes(ctx -> {
                                    RVP_CustomMountRenderLogic.clearDebugLog();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已清空 custommount 调试日志: " + RVP_CustomMountRenderLogic.getDebugLogPath()), false);
                                    return 1;
                                }))
                        )
                        .then(Commands.literal("ui").executes(ctx -> {
                            AbstractVehicle vehicle = LocalVehiclePlayer.instance.getVehicle();
                            StringBuilder sb = new StringBuilder();
                            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                            sb.append("=== RVP UI Debug ").append(ts).append(" ===\n");

                            if (vehicle == null) {
                                sb.append("状态: 未乘坐载具\n");
                                sb.append("已加载预设: ").append(String.join(", ", UIPresetManager.getLoadedPresetNames())).append("\n");
                                writeLog(LOG_PATH, sb.toString());
                                ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已写入 " + LOG_PATH), false);
                                return 0;
                            }

                            // 载具信息
                            sb.append("载具类型: ").append(vehicle.getClass().getName()).append("\n");

                            // uiPreset 字段
                            String vehiclePreset = "";
                            if (vehicle.getVehicleId() != null) {
                                vehiclePreset = org.ywzj.rvp.config.VehicleUIPresetCache.get(vehicle.getVehicleId());
                            }
                            if (vehiclePreset == null) vehiclePreset = "";
                            sb.append("VehicleUIPresetCache.get(").append(vehicle.getVehicleId()).append("): \"").append(vehiclePreset).append("\"\n");

                            // 预设管理器查询
                            String lookupName = (vehiclePreset != null && !vehiclePreset.isEmpty()) ? vehiclePreset : "default";
                            var preset = UIPresetManager.get(lookupName);
                            sb.append("查询预设名: \"").append(lookupName).append("\"\n");
                            sb.append("已加载预设: [").append(String.join(", ", UIPresetManager.getLoadedPresetNames())).append("]\n");

                            if (preset == null) {
                                sb.append("结果: 预设未找到\n");
                                writeLog(LOG_PATH, sb.toString());
                                ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已写入 " + LOG_PATH), false);
                                return 0;
                            }

                            sb.append("预设名称: \"").append(preset.name).append("\"\n");

                            // 雷达位置
                            UIPosition radarPos = preset.radar;
                            if (radarPos != null) {
                                sb.append("雷达 radar: anchor=").append(radarPos.anchor)
                                        .append(" offset_x=").append(radarPos.offsetX)
                                        .append(" offset_y=").append(radarPos.offsetY)
                                        .append(" scale=").append(radarPos.scale).append("\n");
                                sb.append("  计算位置(1920x1080): X=").append(radarPos.computeX(1920))
                                        .append(" Y=").append(radarPos.computeY(1080)).append("\n");
                            } else {
                                sb.append("雷达 radar: null\n");
                            }

                            // RWR 位置
                            UIPosition rwrPos = preset.rwr;
                            if (rwrPos != null) {
                                sb.append("RWR rwr: anchor=").append(rwrPos.anchor)
                                        .append(" offset_x=").append(rwrPos.offsetX)
                                        .append(" offset_y=").append(rwrPos.offsetY)
                                        .append(" scale=").append(rwrPos.scale).append("\n");
                            } else {
                                sb.append("RWR rwr: null\n");
                            }

                            // 多雷达独立位置
                            if (preset.radars != null && !preset.radars.isEmpty()) {
                                sb.append("多雷达 radars:\n");
                                preset.radars.forEach((partId, pos) -> {
                                    sb.append("  ").append(partId).append(": anchor=").append(pos.anchor)
                                            .append(" offset_x=").append(pos.offsetX)
                                            .append(" offset_y=").append(pos.offsetY)
                                            .append(" scale=").append(pos.scale).append("\n");
                                });
                            } else {
                                sb.append("多雷达 radars: null\n");
                            }

                            // 实际渲染情况：当前武器站雷达列表
                            var wu = LocalVehiclePlayer.instance.getWeaponUnit();
                            if (wu != null) {
                                var radars = wu.getRadarUnits();
                                sb.append("当前武器站雷达数: ").append(radars.size()).append("\n");
                                for (int i = 0; i < radars.size(); i++) {
                                    var r = radars.get(i);
                                    sb.append("  雷达[").append(i).append("]: id=").append(r.getId())
                                            .append(" isOn=").append(r.isOn())
                                            .append(" isUiHide=").append(r.isUiHide()).append("\n");
                                }
                            } else {
                                sb.append("当前武器站: null\n");
                            }

                            // === RVP Overlay 渲染状态 ===
                            sb.append("--- RVP Overlay 渲染状态 ---\n");
                            sb.append("RVP 雷达替代: ").append(vehiclePreset != null && !vehiclePreset.isEmpty() ? "是 (原版已取消)" : "否 (原版渲染)").append("\n");
                            sb.append("RVP 观瞄替代: ").append(vehiclePreset != null && !vehiclePreset.isEmpty() ? "是 (原版已取消)" : "否 (原版渲染)").append("\n");

                            // 如果使用 RVP，打印实际渲染参数
                            if (vehiclePreset != null && !vehiclePreset.isEmpty()) {
                                int screenW = 1920;
                                int screenH = 1080;
                                // 尝试从 Minecraft 获取实际分辨率
                                var mc = net.minecraft.client.Minecraft.getInstance();
                                if (mc.getWindow() != null) {
                                    screenW = mc.getWindow().getGuiScaledWidth();
                                    screenH = mc.getWindow().getGuiScaledHeight();
                                }
                                sb.append("当前分辨率: ").append(screenW).append("x").append(screenH).append("\n");

                                // 雷达实际渲染位置
                                UIPosition rp = UIPresetManager.getRadar(lookupName);
                                if (rp != null) {
                                    int rx = rp.computeX(screenW);
                                    int ry = rp.computeY(screenH);
                                    float radScale = rp.scale;
                                    float radius = 50.0f * radScale;
                                    sb.append("RVP 雷达渲染: center=(").append(rx).append(",").append(ry)
                                            .append(") scale=").append(radScale)
                                            .append(" radius=").append(String.format("%.1f", radius)).append("\n");
                                }

                                // RWR 实际渲染位置
                                UIPosition rwp = UIPresetManager.getRwr(lookupName);
                                if (rwp != null) {
                                    int rwx = rwp.computeX(screenW);
                                    int rwy = rwp.computeY(screenH);
                                    sb.append("RVP RWR 渲染: center=(").append(rwx).append(",").append(rwy)
                                            .append(") scale=").append(rwp.scale).append("\n");
                                }

                                // show_skeleton 状态
                                sb.append("show_skeleton: ")
                                        .append(org.ywzj.rvp.config.VehicleUIPresetCache.isShowSkeleton(vehicle.getVehicleId())).append("\n");

                                // 雷达 yRot 值（扫描线动画）
                                var wu2 = LocalVehiclePlayer.instance.getWeaponUnit();
                                if (wu2 != null) {
                                    var radars = wu2.getRadarUnits();
                                    for (var ru : radars) {
                                        sb.append("  雷达[").append(ru.getId()).append("] yRotO=")
                                                .append(String.format("%.1f", ru.yRotO))
                                                .append(" yRot=").append(String.format("%.1f", ru.getYRot()))
                                                .append(" locked=").append(ru.getLockedEntity() != null ? "是" : "否")
                                                .append("\n");
                                    }
                                }
                            }

                            writeLog(LOG_PATH, sb.toString());
                            ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已写入 " + LOG_PATH), false);
                            return 1;
                        }))
        );
    }

    private static void writeLog(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            LOGGER.info("Wrote debug log to {}", path);
        } catch (IOException e) {
            LOGGER.error("Failed to write debug log to {}", path, e);
        }
    }
}
