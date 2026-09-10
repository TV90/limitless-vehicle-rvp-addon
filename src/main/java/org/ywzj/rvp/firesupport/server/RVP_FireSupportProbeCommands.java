package org.ywzj.rvp.firesupport.server;

import java.util.UUID;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportImpactPoint;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes;
import org.ywzj.rvp.firesupport.delivery.RVP_VerticalProjectileDelivery;
import org.ywzj.rvp.firesupport.delivery.RVP_GroundLaunchedProjectileDelivery;
import org.ywzj.rvp.firesupport.delivery.RVP_AirLaunchedProjectileDelivery;
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
                        .then(Commands.argument("delivery", StringArgumentType.word())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                new String[]{"vertical", "ground", "air"}, builder))
                        .then(Commands.argument("weapon", ResourceLocationArgument.id())
                                .executes(context -> execute(context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "delivery"),
                                        ResourceLocationArgument.getId(context, "weapon"),
                                        context.getSource().getPosition()))
                                .then(Commands.argument("target", Vec3Argument.vec3())
                                        .executes(context -> execute(context.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(context, "delivery"),
                                                ResourceLocationArgument.getId(context, "weapon"),
                                                Vec3Argument.getVec3(context, "target"))))))));
    }

    private static int execute(ServerPlayer player, String deliveryType, ResourceLocation weaponId, Vec3 target) {
        // 调用本体权威武器索引：测试入口不接受任意构造数据，也不按资源路径猜测武器类型。
        RVP_WeaponData weapon = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(index -> index.data() instanceof RVP_WeaponData data ? data : null)
                .orElse(null);
        if (weapon == null) {
            player.sendSystemMessage(Component.literal("[RVP] 武器不存在或不是 RVP 武器: " + weaponId));
            return 0;
        }
        RVP_FireSupportDelivery delivery = switch (deliveryType) {
            case "vertical" -> new RVP_VerticalProjectileDelivery(
                    new RVP_FireSupportDeliveryTypes.VerticalProjectileData(64.0, 0.0, 0, 0.0));
            case "ground" -> new RVP_GroundLaunchedProjectileDelivery(
                    new RVP_FireSupportDeliveryTypes.GroundLaunchedProjectileData(
                            768.0, 256.0, 2.5, 512.0, 80, 0.0));
            case "air" -> new RVP_AirLaunchedProjectileDelivery(
                    new RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData(
                            ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "none"),
                            new RVP_FireSupportDeliveryTypes.LocalOffset(0.0, -2.0, 0.0),
                            512.0, 512.0, 256.0, 96.0, 768.0, 2.5, 80, 0.0));
            default -> null;
        };
        if (delivery == null) {
            player.sendSystemMessage(Component.literal("[RVP] 投送类型必须为 vertical、ground 或 air"));
            return 0;
        }
        UUID probeMission = UUID.randomUUID();
        double heading = Math.toDegrees(Math.atan2(target.x - player.getX(), target.z - player.getZ()));
        long scheduledTick = player.serverLevel().getGameTime();
        RVP_FireSupportDeliveryContext deliveryContext = new RVP_FireSupportDeliveryContext(
                player.serverLevel(), player, weapon, new RVP_FireSupportImpactPoint(target.x, target.z),
                target.x, target.z, 0.0, heading, probeMission, 0, probeMission.getMostSignificantBits(),
                scheduledTick, 4);
        // 调用投送器准备阶段：探针也遵守正式任务的确定性解算与 Chunk 租约流程。
        delivery.prepare(deliveryContext);
        RVP_FireSupportDeliveryResult result = delivery.deliver(new RVP_FireSupportDeliveryContext(
                player.serverLevel(), player, weapon, new RVP_FireSupportImpactPoint(target.x, target.z), target.x,
                target.z, 0.0, heading, probeMission, 0, probeMission.getMostSignificantBits(), scheduledTick, 4));
        if (!result.delivered()) {
            RVP_FireSupportSpawnChunkLeaseManager.releaseMission(player.server, probeMission);
            player.sendSystemMessage(Component.literal("[RVP] 炮火探针未生成: " + result.status()
                    + "（目标必须位于已 entity-ticking 的 Chunk）"));
            return 0;
        }
        RVP_FireSupportSpawnChunkLeaseManager.releaseMission(player.server, probeMission);
        player.sendSystemMessage(Component.literal("[RVP] 炮火探针已生成: delivery=" + deliveryType + " entity="
                + result.projectile().getId() + " kind=" + weapon.getWeaponKind()
                + " owner=" + result.projectile().getOwner().getUUID()
                + " sourceVehicle=" + result.projectile().getShooterVehicle()
                + " spawn=" + result.spawnPosition()));
        return 1;
    }
}
