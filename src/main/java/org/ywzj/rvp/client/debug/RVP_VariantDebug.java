package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 调试命令：/rvp debug variantDump - 打印当前载具的统一改装界面数据
 * 用于排查混合模式下挂架/弹种列表与后端切换是否一致
 */
public class RVP_VariantDebug {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rvp")
            .then(Commands.literal("debug")
                .then(Commands.literal("variantDump")
                    .executes(ctx -> {
                        Entity vehicle = ctx.getSource().getEntity();
                        if (vehicle == null) {
                            // 尝试取玩家载具
                            if (ctx.getSource().getPlayer() != null && ctx.getSource().getPlayer().getVehicle() instanceof AbstractVehicle av) {
                                vehicle = av;
                            } else {
                                ctx.getSource().sendFailure(Component.literal("请先上载具或指定载具"));
                                return 0;
                            }
                        }
                        if (!(vehicle instanceof AbstractVehicle av)) {
                            ctx.getSource().sendFailure(Component.literal("目标不是载具"));
                            return 0;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("Vehicle: ").append(av.getVehicleId()).append(" id=").append(av.getId()).append("\n");
                        // modding_only_multi
                        var entries = org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager.INSTANCE.getModdingOnlyEntries(av);
                        sb.append("modding_only_multi entries: ").append(entries.size()).append("\n");
                        for (var e : entries) {
                            sb.append("  part=").append(e.partId()).append(" idx=").append(e.weaponIndex()).append("\n");
                        }
                        // custom mounts
                        var mounts = RVP_CustomMountConfigCache.get(av.getVehicleId());
                        sb.append("rvp_custom_mounts: ").append(mounts.size()).append("\n");
                        java.util.Map<String, java.util.List<org.ywzj.rvp.config.RVP_CustomMountConfig>> groups = new java.util.LinkedHashMap<>();
                        for (var c : mounts) groups.computeIfAbsent(c.partUnitId(), k->new java.util.ArrayList<>()).add(c);
                        for (var en : groups.entrySet()) {
                            sb.append("  pylon=").append(en.getKey()).append(" cfgs=").append(en.getValue().size()).append(" weaponIds=");
                            for (var c : en.getValue()) sb.append(c.weaponId()).append(",");
                            sb.append("\n");
                            var pu = av.getPartUnit(en.getKey()).orElse(null);
                            if (pu instanceof WeaponUnit wu) {
                                sb.append("    WeaponUnit weapons=").append(wu.weapons.size()).append(" curIdx=").append(wu.getCurrentWeaponIndex()).append("\n");
                                for (int i=0;i<wu.weapons.size();i++) {
                                    var w = wu.weapons.get(i);
                                    sb.append("      [").append(i).append("] ").append(w.getClass().getSimpleName()).append(" name=").append(w.getDisplayName().getString()).append("\n");
                                }
                            } else {
                                sb.append("    not WeaponUnit\n");
                            }
                        }
                        // 统一站：按当前 RVP_AuiVariantScreen 的 rebuild 逻辑预览
                        sb.append("，建议：若挂架队 weaponIds 与 WeaponUnit weapons 不一致，检查 j15.json 的 rvp_custom_mounts weapon_id 是否与 WeaponUnit 的 weapon id 匹配\n");
                        String out = sb.toString();
                        System.out.println(out);
                        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
                        return 1;
                    }))));
    }
}
