package org.ywzj.rvp.server.wreck;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CWreckDestructionState;
import org.ywzj.rvp.vehicle.wreck.RVP_WreckDestructionState;
import org.ywzj.rvp.vehicle.wreck.RVP_WreckDestructionState.Mode;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.util.VehiclePartSpawner;

import java.util.HashSet;
import java.util.Set;

/** 服务端决定击毁结果并调度本体飞头；只持有等待飞头的已加载载具，不扫描全世界实体。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DestroyedPartLaunchManager {
    /** 已加载且尚未消费延迟飞头的实体；离开世界时立即释放引用。 */
    private static final Set<AbstractVehicle> PENDING = new HashSet<>();
    /** 到时重入本体生成器的一次性放行凭据，只在服务端线程使用。 */
    private static final Set<AbstractVehicle> NATIVE_CALLS = new HashSet<>();

    private RVP_DestroyedPartLaunchManager() {}

    /** 仅履带和轮式车生效；飞机落地、舰船和脱落残件均保持本体行为。 */
    public static boolean isGroundVehicle(AbstractVehicle vehicle) {
        return vehicle instanceof TrackedVehicle || vehicle instanceof WheeledVehicle;
    }

    /** 从实体 Forge 持久化数据读取一次性决定，不依赖客户端或本体的 protected 字段。 */
    private static RVP_WreckDestructionState readState(AbstractVehicle vehicle) {
        // 调用本项目当前 schema 解码，损坏的记录不能导致重复飞头。
        return RVP_WreckDestructionState.fromTag(vehicle.getPersistentData().getCompound(RVP_WreckDestructionState.NBT_KEY));
    }

    /** 保存权威状态；原版实体保存流程自动持久化 ForgeData，无需额外 NBT Mixin。 */
    private static void saveState(AbstractVehicle vehicle, RVP_WreckDestructionState state) {
        // 调用本项目编码，将已经抽取的结果和执行标记一起写入实体。
        vehicle.getPersistentData().put(RVP_WreckDestructionState.NBT_KEY, state.toTag());
    }

    /** Mixin 唯一转发入口；true 表示取消这次本体生成，false 表示原样放行。 */
    public static boolean interceptNativeSpawn(AbstractVehicle vehicle) {
        // 调用本体状态与本类类型门控，只接管服务端真实击毁的地面载具。
        if (vehicle.level().isClientSide() || !isGroundVehicle(vehicle) || !vehicle.isDestroyed()) return false;
        if (NATIVE_CALLS.remove(vehicle)) return false;
        if (vehicle.getPersistentData().contains(RVP_WreckDestructionState.NBT_KEY)) return true;
        // 调用公共时长规则，锁定服务端配置；运行中调参不会移动已存在残骸的时间点。
        long duration = RVP_WreckDestructionState.cookoffDurationTicks(
                RVP_CommonConfig.getWreckLifetimeSeconds(), RVP_WreckDestructionState.WRECK_LIFETIME_PERCENT);
        // 调用本项目 30/30/40 分段；殉燃关闭时维持原本立即飞头。
        Mode mode = duration < 2 ? Mode.IMMEDIATE : RVP_WreckDestructionState.selectMode(vehicle.level().random.nextInt(100));
        long delay = mode == Mode.BURN_ONLY ? -1 : 0;
        if (mode == Mode.DELAYED) {
            // 调用本项目上下界与整数采样，包含 40% 和 80% 的合法 tick 边界。
            long min = RVP_WreckDestructionState.minimumLaunchDelay(duration);
            long max = RVP_WreckDestructionState.maximumLaunchDelay(duration);
            long sample = (long) (vehicle.level().random.nextDouble() * (max - min + 1));
            delay = RVP_WreckDestructionState.launchDelay(duration, sample);
        }
        RVP_WreckDestructionState state = new RVP_WreckDestructionState(mode, vehicle.level().getGameTime(),
                mode == Mode.IMMEDIATE ? 0 : duration, delay, 30 + vehicle.level().random.nextInt(31), mode == Mode.IMMEDIATE);
        // 调用持久化和同步入口，立即飞头标记必须先于本体脱落与客户端第一帧喷燃生效。
        saveState(vehicle, state);
        broadcast(vehicle, state);
        if (mode == Mode.DELAYED) PENDING.add(vehicle);
        return mode != Mode.IMMEDIATE;
    }

    /** 将决定发送给当前追踪玩家；后加入追踪者由 StartTracking 补发同一状态。 */
    private static void broadcast(AbstractVehicle vehicle, RVP_WreckDestructionState state) {
        RVP_Network.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CWreckDestructionState(vehicle.level().dimension().location(), vehicle.getUUID(), state));
    }

    /** 仅遍历延迟任务；无人观察不影响服务端时钟，残骸提前移除则取消飞头。 */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        for (AbstractVehicle vehicle : Set.copyOf(PENDING)) {
            if (vehicle.level() != level) continue;
            // 调用本体击毁状态，避免复活/移除后的旧任务生成幽灵部件。
            if (vehicle.isRemoved() || !vehicle.isDestroyed()) {
                PENDING.remove(vehicle);
                continue;
            }
            // 调用当前持久化读取；读档后继续原定时间表，绝不重新抽签。
            RVP_WreckDestructionState state = readState(vehicle);
            if (state == null || state.mode() != Mode.DELAYED || state.partsLaunched()) {
                PENDING.remove(vehicle);
                continue;
            }
            // 调用公共时间门，到达目标 tick 才消费飞头任务。
            if (!state.isLaunchDue(level.getGameTime())) continue;
            PENDING.remove(vehicle);
            // 调用一次性状态转换并先保存，阻止本体事件回调中的重复调用。
            RVP_WreckDestructionState launched = state.markPartsLaunched();
            saveState(vehicle, launched);
            NATIVE_CALLS.add(vehicle);
            try {
                // 调用本体原方法，原样保留全部可脱落部件、初速度、物理、隐藏和实体同步行为。
                VehiclePartSpawner.spawnDestroyedParts(vehicle);
            } finally {
                NATIVE_CALLS.remove(vehicle);
            }
            // 调用同步入口，追踪玩家与之后重连者得到相同执行标记。
            broadcast(vehicle, launched);
        }
    }

    /** 加载保存的残骸时恢复等待表；不为没有本功能记录的既存残骸补抽签。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractVehicle vehicle)
                || !isGroundVehicle(vehicle)) return;
        // 调用本类 NBT 读取，仅恢复尚未飞头的延迟任务。
        RVP_WreckDestructionState state = readState(vehicle);
        if (state != null && state.mode() == Mode.DELAYED && !state.partsLaunched()) PENDING.add(vehicle);
    }

    /** 卸载时释放实体引用；时间表留在实体 NBT，再次加载后按原定时刻处理。 */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof AbstractVehicle vehicle) {
            PENDING.remove(vehicle);
            NATIVE_CALLS.remove(vehicle);
        }
    }

    /** 重连、远近往返和首次追踪补发快照；包携带 UUID，允许早于实体生成包抵达。 */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof AbstractVehicle vehicle)
                || !isGroundVehicle(vehicle) || !vehicle.isDestroyed()) return;
        // 调用本类 NBT 读取；既存的无记录残骸仅显示普通烟，不凭空重放击毁过程。
        RVP_WreckDestructionState state = readState(vehicle);
        if (state == null) state = new RVP_WreckDestructionState(Mode.BURN_ONLY, vehicle.level().getGameTime(), 0, -1, 30, false);
        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CWreckDestructionState(vehicle.level().dimension().location(), vehicle.getUUID(), state));
    }

    /** 单机退出/服务端停止后释放所有静态引用，避免下一个世界继承旧任务。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        NATIVE_CALLS.clear();
    }
}
