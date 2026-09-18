package org.ywzj.rvp.client.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.GunnerTargeting;
import org.ywzj.rvp.entity.gunner.ai.GunnerBrain;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerDebugMonitor;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerLockDebug;
import org.ywzj.rvp.radar.RVP_RadarAmmoDebug;
import org.ywzj.rvp.network.C2SDebugSpawnVehicle;
import org.ywzj.rvp.network.RVP_Network;

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
                                    RVP_HitboxDebug.dumpVehicleSnapshot("command-on", LocalVehiclePlayer.instance.vehicle);
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
                                    RVP_HitboxDebug.dumpVehicleSnapshot("command-dump", LocalVehiclePlayer.instance.vehicle);
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
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
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
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.vehicle;
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
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
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
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
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
                                // [RVP] 出弹点状态 dump：缓存队列命中/已应用 Bolt/当前出弹点世界坐标
                                .then(Commands.literal("bolts").executes(ctx -> {
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
                                    if (vehicle == null) {
                                        ctx.getSource().sendFailure(Component.literal("[RVP] 未乘坐载具"));
                                        return 0;
                                    }
                                    String content = org.ywzj.rvp.mount.RVP_ShootBoltQueueResolver.dumpBoltState(vehicle);
                                    writeLog(RVP_CustomMountRenderLogic.getDebugLogPath(), content);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 出弹点状态已写入 " + RVP_CustomMountRenderLogic.getDebugLogPath()), false);
                                    return 1;
                                }))
                        )
                        .then(Commands.literal("sbmprobe")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_SbmProbeDebug.clearLog();
                                    RVP_SbmProbeDebug.setEnabled(true);
                                    RVP_SbmProbeDebug.dumpCurrentVehicleState("command-on");
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已开启 sbmprobe: " + RVP_SbmProbeDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_SbmProbeDebug.setEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已关闭 sbmprobe: " + RVP_SbmProbeDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_SbmProbeDebug.isEnabled();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] sbmprobe=" + enabled + " path=" + RVP_SbmProbeDebug.getLogPath()), false);
                                    return enabled ? 1 : 0;
                                }))
                                .then(Commands.literal("dump").executes(ctx -> {
                                    RVP_SbmProbeDebug.dumpCurrentVehicleState("command-dump");
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已写入 " + RVP_SbmProbeDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("clear").executes(ctx -> {
                                    RVP_SbmProbeDebug.clearLog();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已清空 sbmprobe 日志: " + RVP_SbmProbeDebug.getLogPath()), false);
                                    return 1;
                                }))
                        )
                        .then(Commands.literal("gunner")
                                .executes(ctx -> {
                                    // one-shot dump to chat + file
                                    AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.vehicle;
                                    if (vehicle == null) {
                                        ctx.getSource().sendSuccess(() -> Component.literal("§c[Gunner] 未乘坐载具"), false);
                                        return 0;
                                    }
                                    GunnerEntity gunner = null;
                                    for (var p : vehicle.getPassengers()) {
                                        if (p instanceof GunnerEntity g) { gunner = g; break; }
                                    }
                                    if (gunner == null) {
                                        ctx.getSource().sendSuccess(() -> Component.literal("§c[Gunner] 载具上没有 gunner"), false);
                                        return 0;
                                    }
                                    String dump = buildGunnerDump(gunner, vehicle);
                                    ctx.getSource().sendSuccess(() -> Component.literal(dump), false);
                                    // also write to log file
                                    java.io.PrintWriter pw = RVP_GunnerDebugMonitor.getOneShotWriter();
                                    if (pw != null) {
                                        pw.println(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " [Gunner] " + dump.replace("\n", " | "));
                                        pw.flush();
                                    }
                                    return 1;
                                })
                                .then(Commands.literal("monitor").executes(ctx -> {
                                    // 全局监控，不要求玩家在载具上
                                    // 实际数据来自 GunnerBrain.tick() 中每个 gunner 的 onTick() 调用
                                    RVP_GunnerDebugMonitor.start();
                                    return 1;
                                }))
                                .then(Commands.literal("stop").executes(ctx -> {
                                    RVP_GunnerDebugMonitor.stop();
                                    return 1;
                                }))
                        )
                        .then(Commands.literal("gunnerlock")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_GunnerLockDebug.setEnabled(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已开启 gunnerlock 诊断: " + RVP_GunnerLockDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_GunnerLockDebug.setEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已关闭 gunnerlock 诊断: " + RVP_GunnerLockDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_GunnerLockDebug.isEnabled();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] gunnerlock=" + enabled + " path=" + RVP_GunnerLockDebug.getLogPath()), false);
                                    return enabled ? 1 : 0;
                                }))
                        )
                        .then(Commands.literal("radarammo")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_RadarAmmoDebug.setEnabled(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已开启 radarammo 诊断: " + RVP_RadarAmmoDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_RadarAmmoDebug.setEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已关闭 radarammo 诊断: " + RVP_RadarAmmoDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_RadarAmmoDebug.isEnabled();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[RVP] radarammo=" + enabled + " path=" + RVP_RadarAmmoDebug.getLogPath()), false);
                                    return enabled ? 1 : 0;
                                }))
                        )
                        .then(Commands.literal("ui").executes(ctx -> {
                            AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
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
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("vehicleId", ResourceLocationArgument.id())
                                        .executes(ctx -> {
                                            ResourceLocation vehicleId = ResourceLocationArgument.getId(ctx, "vehicleId");
                                            RVP_Network.CHANNEL.sendToServer(new C2SDebugSpawnVehicle(vehicleId.toString()));
                                            ctx.getSource().sendSuccess(() -> Component.literal("[RVP] 已请求在面前生成载具: " + vehicleId), false);
                                            return 1;
                                        }))
                        )
        );
    }

    private static String buildGunnerDump(GunnerEntity gunner, AbstractVehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Gunner Debug ===\n");
        sb.append("§eProfileId: §f").append(gunner.getProfileId()).append("\n");
        ResourceLocation normId = GunnerProfileManager.INSTANCE.normalizeProfileId(gunner.getProfileId());
        GunnerProfile profile = GunnerProfileManager.INSTANCE.getProfile(normId);
        sb.append("§eProfile: §f").append(profile != null ? profile.getName() : "null").append("\n");
        sb.append("§eFaction: §f").append(profile != null ? profile.getFaction() : "null").append("\n");
        boolean driver = vehicle.getDriver() == gunner;
        sb.append("§eIsDriver: §f").append(driver).append("\n");
        sb.append("§eLauncherConfig: §f").append(GunnerBrain.hasLauncherDeployConfig(vehicle)).append("\n");
        sb.append("§eVehicleId: §f").append(vehicle.getVehicleId()).append("\n");

        // ammo
        boolean hasAny = false;
        for (var pu : vehicle.getPartUnits()) {
            if (!(pu instanceof WeaponUnit wu)) continue;
            for (AbstractVehicleWeapon<?> w : wu.getIndexedWeapons()) {
                var proxy = wu.proxyWeapon(w);
                sb.append("§eWeapon: §f").append(w.getData() != null ? w.getData().getWeaponId() : "null")
                        .append(" remain=").append(proxy.getRemainAmmo())
                        .append("/").append(proxy.getMaxCapacity())
                        .append(" cd=").append(proxy.isCoolingDown())
                        .append(" reload=").append(proxy.isReloading())
                        .append(" hasAmmo=").append(proxy.hasAmmo())
                        .append("\n");
                if (proxy.hasAmmo()) hasAny = true;
            }
        }
        sb.append("§ehasAnyAmmo: §f").append(hasAny).append("\n");

        // target
        var target = gunner.getTrackedTarget();
        if (target != null && target.isAlive()) {
            sb.append("§eTarget: §f").append(target.getType().toString())
                    .append(" pos=").append(String.format("%.1f,%.1f,%.1f", target.getX(), target.getY(), target.getZ()))
                    .append(" dist=").append(String.format("%.1f", vehicle.position().distanceTo(target.position())))
                    .append("\n");
            if (target instanceof AmmoEntity ammo) {
                sb.append("§e  (ammo) owner=").append(ammo.getOwner()).append("\n");
            }
        } else {
            sb.append("§eTarget: §cnone (null/dead)\n");
        }

        sb.append("§eMissileCooldown: §f").append(gunner.getMissileCooldown()).append("\n");
        sb.append("§eBurstWindowOpen: §f").append(gunner.isBurstWindowOpen()).append("\n");
        sb.append("§eWeaponIdx: §f").append(gunner.getControlledWeaponIndex()).append("\n");

        AmmoEntity ciws = GunnerTargeting.findCiwsTarget(gunner, vehicle);
        sb.append("§eCIWS target: §f").append(ciws != null ? ciws.getType().toString() : "none").append("\n");
        return sb.toString();
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
