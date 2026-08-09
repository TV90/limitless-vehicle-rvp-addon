package org.ywzj.rvp.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 放载具调试命令的服务端处理（rvpdebug spawn &lt;vehicleId&gt;）。
 *
 * <p>客户端 rvpdebug 命令发出本包，服务端在玩家前方生成指定 ID 的载具实体。
 * 生成逻辑复刻本体 {@code VehicleSpawnItem.useOn}（construct + addFreshEntity），
 * 仅限创造模式或 OP（等级 2）使用。vehicleId 无命名空间时默认补 {@code rvp:}。</p>
 */
public class C2SDebugSpawnVehicle {

    private static final Logger LOGGER = LogUtils.getLogger();

    public String vehicleId;

    public C2SDebugSpawnVehicle() {
    }

    public C2SDebugSpawnVehicle(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public static void encode(C2SDebugSpawnVehicle msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.vehicleId);
    }

    public static C2SDebugSpawnVehicle decode(FriendlyByteBuf buf) {
        return new C2SDebugSpawnVehicle(buf.readUtf());
    }

    public static void handle(C2SDebugSpawnVehicle msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            String rawId = msg.vehicleId == null ? "" : msg.vehicleId.trim();
            LOGGER.info("[RVP-Debug] 收到放载具请求: rawId={} 玩家={}", rawId, player.getName().getString());
            if (!player.isCreative() && !player.hasPermissions(2)) {
                player.displayClientMessage(Component.literal("§c[RVP] 放载具命令需要创造模式或 OP"), false);
                return;
            }
            if (rawId.isEmpty()) {
                player.displayClientMessage(Component.literal("§c[RVP] 载具 ID 为空"), false);
                return;
            }
            ResourceLocation vehicleId = rawId.contains(":")
                    ? ResourceLocation.tryParse(rawId)
                    : ResourceLocation.tryParse("rvp:" + rawId);
            if (vehicleId == null) {
                player.displayClientMessage(Component.literal("§c[RVP] 非法载具 ID: " + rawId), false);
                return;
            }
            Optional<BaseVehicleData> vehicleDataOptional = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicleId);
            // 无命名空间输入（如 t90m）经 ResourceLocationArgument 解析为 minecraft:t90m，兜底尝试 rvp: 命名空间
            if (vehicleDataOptional.isEmpty() && vehicleId.getNamespace().equals("minecraft")) {
                vehicleId = ResourceLocation.tryParse("rvp:" + vehicleId.getPath());
                if (vehicleId != null) {
                    vehicleDataOptional = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicleId);
                }
            }
            if (vehicleDataOptional.isEmpty()) {
                LOGGER.warn("[RVP-Debug] 未找到载具数据: {}（车辆数据管理器中共 {} 条）", vehicleId,
                        CommonAssetsManager.vehicleDataManager().getVehicleData().size());
                player.displayClientMessage(Component.literal("§c[RVP] 未找到载具: " + rawId), false);
                return;
            }
            LOGGER.info("[RVP-Debug] 载具数据存在: {} class={}", vehicleId,
                    vehicleDataOptional.get().getClass().getName());
            ServerLevel serverLevel = (ServerLevel) player.level();
            Vec3 look = player.getLookAngle();
            Vec3 pos = player.position().add(look.x * 4.0, 1.0, look.z * 4.0);
            int x = (int) Math.floor(pos.x);
            int z = (int) Math.floor(pos.z);
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            try {
                Entity vehicle = vehicleDataOptional.get().construct(serverLevel,
                        new Vec3(pos.x, y + 1, pos.z), 0, player.getYRot());
                LOGGER.info("[RVP-Debug] construct 成功: {} entityClass={}", vehicleId,
                        vehicle == null ? "null" : vehicle.getClass().getName());
                serverLevel.addFreshEntity(vehicle);
                LOGGER.info("[RVP-Debug] addFreshEntity 成功: {} @{}", vehicleId, vehicle.blockPosition());
                player.displayClientMessage(Component.literal("§a[RVP] 已生成载具: " + vehicleId
                        + " @ " + (int) pos.x + "," + (y + 1) + "," + (int) pos.z), false);
            } catch (Exception e) {
                LOGGER.error("[RVP-Debug] 生成载具异常: {}", vehicleId, e);
                player.displayClientMessage(Component.literal("§c[RVP] 生成载具异常: " + vehicleId
                        + " 详情见 latest.log"), false);
            }
        });
    }
}
