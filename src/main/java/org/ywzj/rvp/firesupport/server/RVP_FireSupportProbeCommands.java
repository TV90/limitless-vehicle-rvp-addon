package org.ywzj.rvp.firesupport.server;

import java.util.UUID;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryResult;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportImpactPoint;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes;
import org.ywzj.rvp.firesupport.delivery.RVP_VerticalProjectileDelivery;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;

/** 阶段 B 游戏内验收入口：由管理员玩家在指定已加载坐标投下一发真实 RVP 弹体。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FireSupportProbeCommands {
    private RVP_FireSupportProbeCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rvpdebug")
                .then(Commands.literal("firesupportprobe")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("weapon", ResourceLocationArgument.id())
                                .executes(context -> execute(context.getSource().getPlayerOrException(),
                                        ResourceLocationArgument.getId(context, "weapon"),
                                        context.getSource().getPosition()))
                                .then(Commands.argument("target", Vec3Argument.vec3())
                                        .executes(context -> execute(context.getSource().getPlayerOrException(),
                                                ResourceLocationArgument.getId(context, "weapon"),
                                                Vec3Argument.getVec3(context, "target")))))));
    }

    private static int execute(ServerPlayer player, ResourceLocation weaponId, Vec3 target) {
        // 调用本体权威武器索引：测试入口不接受任意构造数据，也不按资源路径猜测武器类型。
        RVP_WeaponData weapon = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(index -> index.data() instanceof RVP_WeaponData data ? data : null)
                .orElse(null);
        if (weapon == null) {
            player.sendSystemMessage(Component.literal("[RVP] 武器不存在或不是 RVP 武器: " + weaponId));
            return 0;
        }
        var config = new RVP_FireSupportDeliveryTypes.VerticalProjectileData(64.0, 0.0, 0, 0.0);
        var delivery = new RVP_VerticalProjectileDelivery(config);
        UUID probeMission = UUID.randomUUID();
        RVP_FireSupportDeliveryResult result = delivery.deliver(new RVP_FireSupportDeliveryContext(
                player.serverLevel(), player, weapon, new RVP_FireSupportImpactPoint(target.x, target.z),
                probeMission, 0, probeMission.getMostSignificantBits(), player.serverLevel().getGameTime(), 1));
        if (!result.delivered()) {
            RVP_FireSupportSpawnChunkLeaseManager.releaseMission(player.server, probeMission);
            player.sendSystemMessage(Component.literal("[RVP] 炮火探针未生成: " + result.status()
                    + "（目标必须位于已 entity-ticking 的 Chunk）"));
            return 0;
        }
        RVP_FireSupportSpawnChunkLeaseManager.releaseMission(player.server, probeMission);
        player.sendSystemMessage(Component.literal("[RVP] 炮火探针已生成: entity="
                + result.projectile().getId() + " kind=" + weapon.getWeaponKind()
                + " owner=" + result.projectile().getOwner().getUUID()
                + " sourceVehicle=" + result.projectile().getShooterVehicle()
                + " spawn=" + result.spawnPosition()));
        return 1;
    }
}
