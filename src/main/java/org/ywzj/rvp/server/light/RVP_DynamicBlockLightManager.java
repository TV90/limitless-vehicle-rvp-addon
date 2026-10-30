package org.ywzj.rvp.server.light;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.visual.RVP_FiringMuzzleResolver;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5 服务端原版光照的保留实现；当前代码开关固定关闭，禁止创建任何新临时光源。
 * 凭据先存档再放置，异常退出后可在区块再次加载时恢复；从不强制加载区块。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DynamicBlockLightManager extends SavedData {
    /** 各维度独立保存的恢复凭据名。 */
    private static final String DATA_NAME = "rvp_dynamic_block_lights";
    /** 单 tick 最多受理四次真实光请求；磁盘凭据写入不允许随大规模齐射无限扩张。 */
    private static final int MAX_REQUESTS_PER_TICK = 4;
    /** 存档故障只记录日志并停止该次可选视觉，不能中断服务端世界 tick。 */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** 尚未到 tick 末尾的请求；维度卸载和服务器停止必须清理。 */
    private static final Map<ServerLevel, List<Request>> PENDING = new HashMap<>();
    /** 仅记录本系统实际替换过的位置，不处理玩家原有 LightBlock。 */
    private final Map<BlockPos, RVP_DynamicBlockLightEntry> entries = new LinkedHashMap<>();

    /** @param position 权威位置 @param parameters 经过清洗的光参数 */
    private record Request(Vec3 position, RVP_LightParameters parameters) { }

    /** 当前 P5 固定关闭，任何调用都在首个门控返回；保留实现以便后续单独恢复开发。 */
    public static void request(ServerLevel level, Vec3 position, RVP_LightParameters parameters) {
        // 调用代码层 P5 门控，旧配置文件也不能开启，不接受任何客户端请求包。
        if (!RVP_LightingConfig.blockLightEnabled() || parameters == null || !parameters.enabled()
                || parameters.blockLightLevel() <= 0 || parameters.radius() <= 0 || parameters.intensity() <= 0
                || position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)) { return; }
        List<Request> pending = PENDING.computeIfAbsent(level, ignored -> new ArrayList<>());
        // 每维度队列也有硬上限，连射不能积压数千次方块更新。
        if (pending.size() < RVP_LightingConfig.maxBlockLights()) { pending.add(new Request(position, parameters)); }
    }

    /** 从维度存档读取本系统凭据，不访问客户端类型。 */
    private static RVP_DynamicBlockLightManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(RVP_DynamicBlockLightManager::load,
                RVP_DynamicBlockLightManager::new, DATA_NAME);
    }

    /** 只寻找半径两格内已加载的空气，不替换水、草、玩家灯或可破坏方块。 */
    private void place(ServerLevel level, Request request) {
        BlockPos center = BlockPos.containing(request.position());
        long now = level.getGameTime();
        int lifetime = Math.min(request.parameters().durationTicks(), RVP_LightingConfig.maxBlockLifetime());
        for (RVP_DynamicBlockLightEntry entry : entries.values()) {
            if (entry.position().distSqr(center) <= 4 && level.hasChunkAt(entry.position())
                    && entry.owns(level.getBlockState(entry.position()))) {
                entries.put(entry.position(), new RVP_DynamicBlockLightEntry(entry.position(),
                        entry.original(), entry.placed(),
                        // 调用续期上限，持续重炮齐射不能让单条凭据永不过期。
                        RVP_BlockLightRecovery.renewedExpiry(entry.created(), entry.expires(), now, lifetime), entry.created()));
                setDirty();
                return;
            }
        }
        if (entries.size() >= RVP_LightingConfig.maxBlockLights()) { return; }
        // 调用原版封闭方盒遍历；先近后远，绝不请求未加载区块。
        for (BlockPos cursor : BlockPos.withinManhattan(center, 2, 2, 2)) {
            if (level.isOutsideBuildHeight(cursor) || !level.hasChunkAt(cursor)
                    || !level.getWorldBorder().isWithinBounds(cursor)) { continue; }
            BlockState original = level.getBlockState(cursor);
            if (!original.isAir() || !original.getFluidState().isEmpty()) { continue; }
            BlockPos position = cursor.immutable();
            BlockState placed = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL,
                    Math.max(1, Math.min(15, request.parameters().blockLightLevel())));
            RVP_DynamicBlockLightEntry entry = new RVP_DynamicBlockLightEntry(position, original, placed, now + lifetime, now);
            entries.put(position, entry);
            setDirty();
            // 先持久化恢复凭据，避免区块已写入光方块而凭据尚未存盘的异常中断窗口。
            if (!persistAndVerify(level, entry)) {
                entries.remove(position);
                setDirty();
                return;
            }
            if (!level.setBlock(position, placed, Block.UPDATE_CLIENTS)) {
                entries.remove(position);
                setDirty();
            }
            return;
        }
    }

    /** 到期或停服清理；未加载位置保留凭据，等区块加载后恢复，绝不强载区块。 */
    private void expire(ServerLevel level, boolean all) {
        Iterator<RVP_DynamicBlockLightEntry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            RVP_DynamicBlockLightEntry entry = iterator.next();
            boolean loaded = level.hasChunkAt(entry.position());
            // 调用已测试的恢复状态机；未加载不读区块，恢复失败仍保留凭据，外部改动立即放弃所有权。
            RVP_BlockLightRecovery.Result result = RVP_BlockLightRecovery.recover(loaded,
                    all || entry.expires() <= level.getGameTime(), entry.placed(), entry.original(),
                    loaded ? level.getBlockState(entry.position()) : null,
                    original -> level.setBlock(entry.position(), original, Block.UPDATE_CLIENTS));
            if (result.release()) { iterator.remove(); setDirty(); }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) { return; }
        // 调用维度凭据清理：P5 固定关闭时只恢复已有临时光，不再排入任何新方块写入。
        RVP_DynamicBlockLightManager manager = get(level);
        manager.expire(level, !RVP_LightingConfig.blockLightEnabled());
        List<Request> requests = PENDING.remove(level);
        if (requests != null && RVP_LightingConfig.blockLightEnabled()) {
            // 调用 tick 末尾放置，爆炸破坏阶段已经结束。
            for (int index = 0; index < Math.min(requests.size(), MAX_REQUESTS_PER_TICK); index++) {
                try { manager.place(level, requests.get(index)); }
                catch (RuntimeException exception) {
                    LOGGER.warn("RVP 真实光请求失败，已跳过本 tick 后续光源", exception);
                    break;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onFire(VehicleFireEvent.Post event) {
        // 调用本体成功开火事实，只处理服务端；不让客户端伪造真实光。
        if (event.isClientSide() || !(event.getVehicle().level() instanceof ServerLevel level)
                || !RVP_LightingConfig.blockLightEnabled()) { return; }
        // 调用双端共用武器与炮口解析，与客户端屏幕光共用数据源。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        RVP_LightParameters parameters = RVP_FiringMuzzleResolver.lightParameters(weapon);
        if (parameters == null || weapon == null || parameters.blockLightLevel() <= 0
                || RVP_FiringMuzzleResolver.resolveCaliber(weapon, false) < 45) { return; }
        for (RVP_FiringMuzzleResolver.MuzzleContext muzzle :
                RVP_FiringMuzzleResolver.resolveFiringMuzzleContexts(weapon.getWeaponUnit())) {
            // 调用有界请求队列，一次齐射相邻炮口会合并成少量真实光。
            request(level, muzzle.position(), parameters);
        }
    }

    /** 玩家放置同状态 LightBlock 时也主动放弃凭据，不能误恢复玩家新灯。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!event.isCanceled() && event.getLevel() instanceof ServerLevel level) {
            RVP_DynamicBlockLightManager manager = get(level);
            if (manager.entries.remove(event.getPos()) != null) { manager.setDirty(); }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!event.isCanceled() && event.getLevel() instanceof ServerLevel level) {
            RVP_DynamicBlockLightManager manager = get(level);
            if (manager.entries.remove(event.getPos()) != null) { manager.setDirty(); }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // 调用主线程清理，加载磁盘残留光的区块不会重新点亮一轮。
            level.getServer().execute(() -> get(level).expire(level, false));
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING.remove(level);
            // 调用清理并保存，未加载区块的恢复凭据保留到下次加载。
            get(level).expire(level, true);
            level.getDataStorage().save();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // 调用关服恢复，早于原版世界最终保存，不留下已加载临时灯。
            get(level).expire(level, true);
            level.getDataStorage().save();
        }
    }

    /** 只载入本系统凭据；重启时全部标为过期，等待相关区块可用后清理。 */
    private static RVP_DynamicBlockLightManager load(CompoundTag tag) {
        RVP_DynamicBlockLightManager manager = new RVP_DynamicBlockLightManager();
        ListTag saved = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int index = 0; index < Math.min(saved.size(), 128); index++) {
            CompoundTag entry = saved.getCompound(index);
            BlockPos position = BlockPos.of(entry.getLong("position"));
            BlockState original = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry.getCompound("original"));
            BlockState placed = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry.getCompound("placed"));
            if (original.isAir() && placed.is(Blocks.LIGHT)) {
                manager.entries.put(position, new RVP_DynamicBlockLightEntry(position, original, placed, Long.MIN_VALUE, Long.MIN_VALUE));
            }
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag saved = new ListTag();
        for (RVP_DynamicBlockLightEntry entry : entries.values()) {
            // 调用统一凭据编码，存档写入与放置前的回读验证比较完全相同的内容。
            saved.add(encodeEntry(entry));
        }
        tag.put("entries", saved);
        return tag;
    }

    /** SavedData 内部会吞掉 I/O 异常，因此必须回读确认本条凭据确实落盘后才允许放灯。 */
    private boolean persistAndVerify(ServerLevel level, RVP_DynamicBlockLightEntry expected) {
        level.getDataStorage().save();
        try {
            CompoundTag disk = level.getDataStorage().readTagFromDisk(DATA_NAME,
                    net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            ListTag saved = disk.getCompound("data").getList("entries", Tag.TAG_COMPOUND);
            CompoundTag wanted = encodeEntry(expected);
            for (int index = 0; index < saved.size(); index++) {
                if (wanted.equals(saved.getCompound(index))) { return true; }
            }
            LOGGER.warn("RVP 真实光恢复凭据回读不一致，取消该次临时方块写入：{}", expected.position());
        } catch (java.io.IOException | RuntimeException exception) {
            LOGGER.warn("RVP 真实光恢复凭据无法回读，取消该次临时方块写入", exception);
        }
        return false;
    }

    /** 编码只含恢复所需的当前位置与两种状态，不恢复上次会话的燃烧时间线。 */
    private static CompoundTag encodeEntry(RVP_DynamicBlockLightEntry entry) {
        CompoundTag value = new CompoundTag();
        value.putLong("position", entry.position().asLong());
        value.put("original", NbtUtils.writeBlockState(entry.original()));
        value.put("placed", NbtUtils.writeBlockState(entry.placed()));
        return value;
    }
}
