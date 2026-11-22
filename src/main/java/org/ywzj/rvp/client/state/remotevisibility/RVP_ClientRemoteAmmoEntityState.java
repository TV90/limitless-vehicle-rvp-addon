package org.ywzj.rvp.client.state.remotevisibility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteAmmoEntitySnapshot;
import org.ywzj.vehicle.api.entity.RemoteTickEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 客户端远程弹药实体克隆状态入口。
 *
 * <p>克隆继续放在本体公开的 {@code serverEntities} 表中，复用本体雷达、弹体插值和 RVP 弹药渲染路径，
 * 但不再经过本体的 {@code ServerBroadcastEntities} 编码器。</p>
 */
public final class RVP_ClientRemoteAmmoEntityState {

    private RVP_ClientRemoteAmmoEntityState() {
    }

    /** 接受服务端弹药快照，并在客户端主线程创建或更新远程弹药克隆。 */
    public static void accept(S2CRemoteAmmoEntitySnapshot message) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().location().equals(message.dimension())) {
            return;
        }
        // 调用本体乘员状态，确保远程弹药克隆只服务于当前乘载具玩家。
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        for (S2CRemoteAmmoEntitySnapshot.Entry entry : message.entries()) {
            applyEntry(level, player, entry);
        }
    }

    /** 把单条服务端实体快照写入本体远程实体表。 */
    private static void applyEntry(ClientLevel level, Player player,
                                   S2CRemoteAmmoEntitySnapshot.Entry entry) {
        LocalVehiclePlayer localPlayer = LocalVehiclePlayer.instance;
        LocalVehiclePlayer.ServerEntity serverEntity = localPlayer.serverEntities.get(entry.entityId());
        if (serverEntity == null || serverEntity.entity == null) {
            Entity entity = createEntity(level, entry);
            if (entity == null) {
                return;
            }
            serverEntity = new LocalVehiclePlayer.ServerEntity();
            serverEntity.entity = entity;
            serverEntity.updateTick = player.tickCount;
            localPlayer.serverEntities.put(entry.entityId(), serverEntity);
            return;
        }

        // 调用本体远程 Tick 数据接口，刷新已有克隆的制导与发动机扩展状态。
        updateEntity(serverEntity.entity, entry);
        serverEntity.updateTick = player.tickCount;
    }

    /** 按服务端实体类型创建一个不加入 ClientLevel 的远程弹药克隆。 */
    private static Entity createEntity(ClientLevel level, S2CRemoteAmmoEntitySnapshot.Entry entry) {
        // 调用本体实体注册表，把服务端类型 ID 解析为客户端可创建的实体类型。
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entry.entityType());
        if (type == null) {
            return null;
        }
        // 调用本体实体工厂创建内存克隆；该实体不会通过 level.addFreshEntity 加入世界。
        Entity entity = type.create(level);
        if (entity == null) {
            return null;
        }
        updateEntity(entity, entry);
        if (entity instanceof AbstractVehicle vehicle) {
            // 调用本体远程载具标记，避免意外走本地载具交互路径。
            vehicle.remote = true;
        }
        return entity;
    }

    /** 写入克隆的位置、速度、ID 和本体远程状态数据。 */
    private static void updateEntity(Entity entity, S2CRemoteAmmoEntitySnapshot.Entry entry) {
        // 调用本体实体加载器，恢复弹药所需的服务端 NBT 状态。
        entity.load(entry.data());
        entity.setId(entry.entityId());
        entity.xo = entry.position().x;
        entity.yo = entry.position().y;
        entity.zo = entry.position().z;
        entity.setPos(entry.position());
        entity.setDeltaMovement(entry.velocity());
        if (entity instanceof RemoteTickEntity remoteTickEntity) {
            // 调用本体远程 Tick 数据接口，恢复 RVP 弹体客户端外推所需的扩展状态。
            remoteTickEntity.readData(entry.data());
        }
    }
}
