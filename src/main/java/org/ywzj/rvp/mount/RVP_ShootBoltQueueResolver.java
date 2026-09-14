package org.ywzj.rvp.mount;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockCube;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [RVP] 可变挂架出弹队列：重载期预计算 + 运行时查表（零 Mixin / 零反射新增）。
 *
 * <p>数据流：服务端数据重载时（{@code VehicleDataManagerMixin.apply} TAIL，此时结构模型
 * 与部件模板百分百在册）按挂架条目 {@code shoot_structure_bones} 预计算每个
 * （武器站 × 武器口径）的出弹 Bolt 队列并存入 {@link #QUEUES}；队列经
 * {@code S2CShootBoltQueueSync} 下发客户端（同 JVM 单人游戏直接共享同一张表）。
 * 运行期双端只查表：开火轮转与弹体显隐均按同一队列口径，构造性对齐。</p>
 *
 * <p>Cube→Bolt 数学与本体 {@code buildBolts} / 既有 {@code appendBoltsFromBone} 完全同构
 * （Cube 前端面中心为炮闩、Z+ 为炮管轴、depth 为管长）；跨组偏移用骨骼 bind 枢轴差
 * 换算到锚定骨坐标系。</p>
 */
public final class RVP_ShootBoltQueueResolver {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 骨链回溯深度保护：结构模型骨层级异常（成环）时中断，防止死循环。 */
    private static final int MAX_BONE_DEPTH = 32;

    /**
     * 出弹队列表：载具 id → 武器站 id → 武器口径（weaponId 字符串）→ Bolt 队列。
     * 服务端重载期写入；客户端经 S2CShootBoltQueueSync 写入同一张表（单人游戏共用 JVM）。
     */
    private static final Map<ResourceLocation, Map<String, Map<String, List<Bolt>>>> QUEUES = new ConcurrentHashMap<>();
    /** 编码/解码/重建的跨线程互斥锁（单人游戏下服务端主线程与客户端网络线程并发访问）。 */
    private static final Object QUEUE_LOCK = new Object();
    /**
     * [RVP] 队列表版本号：rebuild（数据重载预计算）与 decodeQueue（S2C 整表同步）每次
     * 整表替换时递增。应用器（RVP_ShootBoltQueueApplier.ensureApplied）用它判断
     * "已应用记录"是否过期——表一变，所有站的队列自动视为待重放。
     */
    private static final java.util.concurrent.atomic.AtomicLong TABLE_GENERATION =
            new java.util.concurrent.atomic.AtomicLong();

    /** 目的：暴露当前队列表版本号，供应用器做"已应用记录是否过期"比对。 */
    public static long getTableGeneration() {
        return TABLE_GENERATION.get();
    }

    private RVP_ShootBoltQueueResolver() {}

    /**
     * 反射读取武器站私有 {@code xTurnGroup}（挂架锚点骨组）。
     *
     * <p>与 {@code RVP_CustomMountRenderLogic} 既有逻辑一致：结构模型中常只有
     * {@code structure_bone + "_barrel"} 骨而无本体骨，导致 structureGroup 恒空、
     * 挂架整体不渲染，故必须读实例 xTurnGroup；反射不可用时回退 structureGroup
     * （仅功能降级，不崩溃）。</p>
     */
    public static VehicleCubeGroup findXTurnGroup(WeaponUnit unit) {
        java.lang.reflect.Field field;
        try {
            field = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(WeaponUnit.class, "xTurnGroup");
            field.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.warn("[RVP] 无法解析 WeaponUnit.xTurnGroup 字段，出弹锚点退化为 structureGroup", t);
            return unit.getStructureGroup();
        }
        try {
            VehicleCubeGroup group = (VehicleCubeGroup) field.get(unit);
            if (group != null) {
                return group;
            }
        } catch (Throwable t) {
            LOGGER.debug("[RVP] 读取 WeaponUnit.xTurnGroup 失败，退化为 structureGroup", t);
        }
        return unit.getStructureGroup();
    }

    /**
     * [RVP] 该武器站（沿母站链）在队列表中是否存在出弹队列（预热门控用）。
     * 目的：就绪门控直接依赖队列表本身（登录同步/数据重载即重建），替代对
     * {@code RVP_CustomMountConfigCache} 的等待——配置包晚于载具生成时，旧"200 tick
     * 等不到配置即永久放弃"的门控会把客户端出弹点粘在挂点模板上。
     */
    public static boolean hasStationQueue(ResourceLocation vehicleId, WeaponUnit station) {
        synchronized (QUEUE_LOCK) {
            Map<String, Map<String, List<Bolt>>> stations = QUEUES.get(vehicleId);
            if (stations == null || stations.isEmpty()) {
                return false;
            }
            WeaponUnit current = station;
            int guard = 0;
            while (current != null && guard++ < 16) {
                Map<String, List<Bolt>> perWeapon = stations.get(current.getId());
                if (perWeapon != null && !perWeapon.isEmpty()) {
                    return true;
                }
                current = current.getParentWeaponUnit();
            }
            return false;
        }
    }

    /**
     * [RVP] 当前选中武器的 weaponId（字符串口径，供应用器做换弹种检测）。
     * 无当前武器时返回空串。
     */
    public static String currentWeaponKey(WeaponUnit station) {
        ResourceLocation id = currentWeaponId(station);
        return id == null ? "" : id.toString();
    }

    /**
     * 当前选中武器的 weaponId。仅做公共类型解包（多弹种取当前选中子武器）；
     * 解包失败返回 null。运行期不依赖任何客户端专属类型。
     */
    @Nullable
    private static ResourceLocation currentWeaponId(WeaponUnit station) {
        java.util.Optional<AbstractVehicleWeapon<?>> weapon = station.getCurrentWeapon();
        if (weapon.isEmpty()) {
            return null;
        }
        AbstractVehicleWeapon<?> current = weapon.get();
        // 目的：多弹种武器站（VehicleMultiWeapons）出弹口径应跟随当前选中的子武器
        while (current instanceof VehicleMultiWeapons multi) {
            AbstractVehicleWeapon<?> selected = multi.getSelectedWeapon();
            if (selected == null || selected == current) {
                break;
            }
            current = selected;
        }
        return current.getData() == null ? null : current.getData().getWeaponId();
    }

    /**
     * 查询出弹队列（武器站实例版）。
     *
     * <p>目的：本站 id 未命中时沿母武器站链逐级回退——与 {@code RVP_ShootBoltQueueApplier}
     * 的 matchesStation 同口径，避免"条目配置在母站、实际开火的是子站"时查表永久 miss。</p>
     *
     * @return 命中的 Bolt 队列（只读语义，调用方不得修改）；整条链都无可用队列时返回 {@code null}
     *         （调用方保持本体原 Bolt，即回退现状）。
     */
    @Nullable
    public static List<Bolt> lookupQueue(ResourceLocation vehicleId, WeaponUnit station, String weaponKey) {
        WeaponUnit current = station;
        while (current != null) {
            List<Bolt> queue = lookupQueueById(vehicleId, current.getId(), weaponKey);
            if (queue != null) {
                return queue;
            }
            current = current.getParentWeaponUnit();
        }
        return null;
    }

    /**
     * 按站 id 查询出弹队列（查表段纳入 {@link #QUEUE_LOCK}）。
     *
     * <p>目的：S2CShootBoltQueueSync 的 decode 在网络线程 clear+重灌整表，旧实现无锁读
     * 会撞上清空窗口拿到空表/半表，导致应用器误判"未命中"并把武器站粘滞在本体模板出弹点。</p>
     *
     * @return 命中的 Bolt 队列（只读语义，调用方不得修改）；该武器站没有可用的
     *         出弹队列时返回 {@code null}（调用方保持本体原 Bolt，即回退现状）。
     */
    @Nullable
    public static List<Bolt> lookupQueueById(ResourceLocation vehicleId, String stationId, String weaponKey) {
        synchronized (QUEUE_LOCK) {
            Map<String, Map<String, List<Bolt>>> stations = QUEUES.get(vehicleId);
            if (stations == null) {
                return null;
            }
            Map<String, List<Bolt>> perWeapon = stations.get(stationId);
            if (perWeapon == null) {
                return null;
            }
            // 目的：口径精确命中优先；未命中（如该站只有一把武器在环）回退该站任一队列
            List<Bolt> exact = perWeapon.get(weaponKey);
            if (exact != null && !exact.isEmpty()) {
                return exact;
            }
            for (List<Bolt> queue : perWeapon.values()) {
                if (queue != null && !queue.isEmpty()) {
                    return queue;
                }
            }
        }
        return null;
    }

    // ==================================================================
    // 重载期预计算（仅服务端数据重载线程调用；客户端经 S2C 同步获取结果）
    // ==================================================================

    /**
     * 重载期预计算：扫描全部载具的挂架条目，为每个（武器站 × 武器口径）构建出弹
     * Bolt 队列。调用时机：{@code VehicleDataManagerMixin.apply} TAIL——此时结构模型、
     * 部件模板、挂架条目缓存三者百分百在册。
     *
     * @param rawVehicleJson 本轮重载的原始载具 JSON（用于读取部件 structure_bone）
     */
    public static void rebuild(Map<ResourceLocation, JsonElement> rawVehicleJson) {
        Map<ResourceLocation, Map<String, Map<String, List<Bolt>>>> rebuilt = new HashMap<>();
        if (rawVehicleJson != null) {
            for (Map.Entry<ResourceLocation, JsonElement> entry : rawVehicleJson.entrySet()) {
                ResourceLocation vehicleId = entry.getKey();
                // 目的：读取本载具的挂架条目；未配置自定义挂架的载具直接跳过
                List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicleId);
                if (configs.isEmpty()) {
                    continue;
                }
                buildVehicleQueues(rebuilt, vehicleId, entry.getValue(), configs);
            }
        }
        synchronized (QUEUE_LOCK) {
            QUEUES.clear();
            QUEUES.putAll(rebuilt);
            // 目的：整表替换即递增版本号——应用器据此判断"已应用记录"过期并重放
            TABLE_GENERATION.incrementAndGet();
        }
        int vehicles = rebuilt.size();
        int queues = 0;
        for (Map<String, Map<String, List<Bolt>>> stations : rebuilt.values()) {
            queues += stations.values().stream().mapToInt(Map::size).sum();
        }
        LOGGER.info("[RVP] 出弹队列预计算完成：载具 {} 个，队列 {} 条（表版本 {}）",
                vehicles, queues, TABLE_GENERATION.get());
    }

    /**
     * 构建单载具的出弹队列表（vehicleId → 武器站 → 武器口径 → Bolt 队列）。
     */
    private static void buildVehicleQueues(Map<ResourceLocation, Map<String, Map<String, List<Bolt>>>> rebuilt,
                                           ResourceLocation vehicleId, JsonElement root,
                                           List<RVP_CustomMountConfig> configs) {
        if (!root.isJsonObject() || !root.getAsJsonObject().has("parts")) {
            return;
        }
        // 目的：解析部件表，得到每个武器站的 structure_bone（锚定骨基础名）
        Map<String, String> stationBones = new HashMap<>();
        for (JsonElement partElement : root.getAsJsonObject().getAsJsonArray("parts")) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject partObj = partElement.getAsJsonObject();
            String id = GsonHelper.getAsString(partObj, "id", "").trim();
            if (!id.isEmpty()) {
                stationBones.put(id, GsonHelper.getAsString(partObj, "structure_bone", "").trim());
            }
        }

        // 目的：结构模型（骨骼树）查询——重载期服务端实例百分百在册
        String structureModelId = GsonHelper.getAsString(root.getAsJsonObject(), "structure_model", "");
        ResourceLocation structureModelIdRl = ResourceLocation.tryParse(structureModelId);
        BedrockModel model = structureModelIdRl == null ? null
                : CommonAssetsManager.structureModelManager().getStructureModel(structureModelIdRl).orElse(null);
        if (model == null) {
            LOGGER.warn("[RVP] 出弹队列预计算跳过：结构模型缺失 vehicle={} model={}", vehicleId, structureModelId);
            return;
        }

        // 目的：按（武器站 × 武器口径）分组挂架条目
        Map<String, Map<String, List<RVP_CustomMountConfig>>> grouped = new HashMap<>();
        for (RVP_CustomMountConfig config : configs) {
            if (config.shootStructureBones().isEmpty()) {
                continue;
            }
            String stationId = config.partUnitId();
            String weaponKey = config.weaponId() == null ? "" : config.weaponId().toString();
            grouped.computeIfAbsent(stationId, key -> new HashMap<>())
                    .computeIfAbsent(weaponKey, key -> new ArrayList<>())
                    .add(config);
        }
        if (grouped.isEmpty()) {
            return;
        }

        // 目的：逐武器站构建队列（确定排序：ammoSlot 升序 → 配置顺序，与消失渲染同口径）
        Map<String, Map<String, List<Bolt>>> stationQueues = new HashMap<>();
        for (Map.Entry<String, Map<String, List<RVP_CustomMountConfig>>> stationEntry : grouped.entrySet()) {
            String stationId = stationEntry.getKey();
            String structureBone = stationBones.getOrDefault(stationId, "");
            String anchorBoneName = structureBone.isEmpty() ? "" : structureBone + "_barrel";
            BedrockBone anchorBone = model.getBoneMap().get(anchorBoneName);
            if (anchorBone == null) {
                LOGGER.warn("[RVP] 出弹队列预计算：锚定骨缺失 station={} bone={}，跳过",
                        stationId, anchorBoneName);
                continue;
            }
            Map<String, List<Bolt>> weaponQueues = new HashMap<>();
            for (Map.Entry<String, List<RVP_CustomMountConfig>> weaponEntry : stationEntry.getValue().entrySet()) {
                List<RVP_CustomMountConfig> entries = new ArrayList<>(weaponEntry.getValue());
                entries.sort(Comparator
                        .comparingInt((RVP_CustomMountConfig config) -> config.ammoSlot() > 0 ? config.ammoSlot() : Integer.MAX_VALUE)
                        .thenComparingInt(RVP_CustomMountConfig::configOrder));
                // 目的：同一骨骼被红外/激光等重复条目引用时只展开一次（按条目顺序保序）
                Set<String> usedBones = new LinkedHashSet<>();
                List<Bolt> queue = new ArrayList<>();
                for (RVP_CustomMountConfig config : entries) {
                    for (String boneName : config.shootStructureBones()) {
                        if (boneName == null || boneName.isBlank() || !usedBones.add(boneName)) {
                            continue;
                        }
                        BedrockBone bone = model.getBoneMap().get(boneName);
                        if (bone == null) {
                            LOGGER.warn("[RVP] 出弹骨不存在，跳过 vehicle={} station={} bone={}",
                                    vehicleId, stationId, boneName);
                            continue;
                        }
                        // 目的：出弹骨坐标系 → 锚定骨坐标系（xTurnGroup 空间）的平移差
                        Vec3 delta = bindPivot(bone).subtract(bindPivot(anchorBone));
                        appendBoneBolts(bone, anchorBone, delta, queue, model);
                    }
                }
                if (!queue.isEmpty()) {
                    weaponQueues.put(weaponEntry.getKey(), queue);
                    // [RVP] 逐队列输出预计算结果（首 Bolt 偏移/管长），让"表里算的是什么"直接可见，
                    // 用于对照 /rvpdebug custommount bolts 的实际应用值
                    Bolt first = queue.get(0);
                    LOGGER.info("[RVP] 出弹队列预计算 vehicle={} station={} key={} bolts={} first=({},{},{}) 管长={}",
                            vehicleId, stationId, weaponEntry.getKey(), queue.size(),
                            String.format(java.util.Locale.ROOT, "%.3f", first.offset.x),
                            String.format(java.util.Locale.ROOT, "%.3f", first.offset.y),
                            String.format(java.util.Locale.ROOT, "%.3f", first.offset.z),
                            String.format(java.util.Locale.ROOT, "%.3f", first.barrelLength));
                }
            }
            if (!weaponQueues.isEmpty()) {
                stationQueues.put(stationId, weaponQueues);
            }
        }
        rebuilt.put(vehicleId, stationQueues);
    }

    // ==================================================================
    // 网络编解码（S2CShootBoltQueueSync 委托；跨线程经 QUEUE_LOCK 串行）
    // ==================================================================

    /** 目的：把整张出弹队列表编码到包缓冲（服务端 → 客户端整体下发）。 */
    public static void encodeQueue(net.minecraft.network.FriendlyByteBuf buf) {
        synchronized (QUEUE_LOCK) {
            buf.writeVarInt(QUEUES.size());
            for (Map.Entry<ResourceLocation, Map<String, Map<String, List<Bolt>>>> vehicleEntry : QUEUES.entrySet()) {
                buf.writeUtf(vehicleEntry.getKey().toString());
                Map<String, Map<String, List<Bolt>>> stations = vehicleEntry.getValue();
                buf.writeVarInt(stations.size());
                for (Map.Entry<String, Map<String, List<Bolt>>> stationEntry : stations.entrySet()) {
                    buf.writeUtf(stationEntry.getKey());
                    Map<String, List<Bolt>> perWeapon = stationEntry.getValue();
                    buf.writeVarInt(perWeapon.size());
                    for (Map.Entry<String, List<Bolt>> weaponEntry : perWeapon.entrySet()) {
                        buf.writeUtf(weaponEntry.getKey());
                        List<Bolt> queue = weaponEntry.getValue();
                        buf.writeVarInt(queue.size());
                        for (Bolt bolt : queue) {
                            buf.writeFloat((float) bolt.offset.x);
                            buf.writeFloat((float) bolt.offset.y);
                            buf.writeFloat((float) bolt.offset.z);
                            buf.writeFloat(bolt.barrelLength);
                            buf.writeFloat(bolt.xRot);
                            buf.writeFloat(bolt.yRot);
                        }
                    }
                }
            }
        }
    }

    /** 目的：从包缓冲解码并整表替换本地出弹队列（客户端侧数据入口），替换后递增表版本号。 */
    public static void decodeQueue(net.minecraft.network.FriendlyByteBuf buf) {
        synchronized (QUEUE_LOCK) {
            QUEUES.clear();
            int vehicleCount = buf.readVarInt();
            for (int vi = 0; vi < vehicleCount; vi++) {
                ResourceLocation vehicleId = ResourceLocation.tryParse(buf.readUtf());
                int stationCount = buf.readVarInt();
                Map<String, Map<String, List<Bolt>>> stations = new HashMap<>();
                for (int si = 0; si < stationCount; si++) {
                    String stationId = buf.readUtf();
                    int weaponCount = buf.readVarInt();
                    Map<String, List<Bolt>> perWeapon = new HashMap<>();
                    for (int wi = 0; wi < weaponCount; wi++) {
                        String weaponKey = buf.readUtf();
                        int boltCount = buf.readVarInt();
                        List<Bolt> queue = new ArrayList<>(boltCount);
                        for (int bi = 0; bi < boltCount; bi++) {
                            float ox = buf.readFloat();
                            float oy = buf.readFloat();
                            float oz = buf.readFloat();
                            float barrelLength = buf.readFloat();
                            float xRot = buf.readFloat();
                            float yRot = buf.readFloat();
                            queue.add(new Bolt(new Vec3(ox, oy, oz), barrelLength, xRot, yRot));
                        }
                        perWeapon.put(weaponKey, queue);
                    }
                    stations.put(stationId, perWeapon);
                }
                if (vehicleId != null) {
                    QUEUES.put(vehicleId, stations);
                }
            }
            // 目的：整表替换即递增版本号——应用器据此判断"已应用记录"过期并重放
            TABLE_GENERATION.incrementAndGet();
        }
    }


    /**
     * 条目 {@code part_unit_id} 是否指向本武器站。
     * 与渲染侧 {@code matchesConfiguredPartUnit} 同口径：本站 id 命中，或条目指向
     * 本站任一母武器站（子站继承母站条目）。
     */
    public static boolean matchesStation(WeaponUnit station, String partUnitId) {
        WeaponUnit current = station;
        while (current != null) {
            if (partUnitId.equals(current.getId())) {
                return true;
            }
            current = current.getParentWeaponUnit();
        }
        return false;
    }

    // ==================================================================
    // 调试输出（/rvpdebug custommount bolts）
    // ==================================================================

    /**
     * dump 单载具出弹点全状态：缓存队列命中情况、已应用 Bolt 明细、当前出弹点世界坐标。
     * 供 {@code /rvpdebug custommount bolts} 调试命令与排查使用；仅读写公共状态，双端安全。
     */
    public static String dumpBoltState(AbstractVehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RVP 出弹点状态 ===\n");
        sb.append("vehicleId=").append(vehicle.getVehicleId())
          .append(" entityId=").append(vehicle.getId()).append('\n');
        List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicle.getVehicleId());
        sb.append("挂架条目: ").append(configs.size()).append(" 条\n");
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit station)) {
                continue;
            }
            boolean managed = false;
            for (RVP_CustomMountConfig config : configs) {
                if (!config.shootStructureBones().isEmpty()
                        && matchesStation(station, config.partUnitId())) {
                    managed = true;
                    break;
                }
            }
            if (!managed) {
                continue;
            }
            String weaponKey = currentWeaponKey(station);
            // 目的：与 RVP_ShootBoltQueueApplier 同口径（本站未命中沿母武器站链回退），dump 才能反映实际应用值
            List<Bolt> cached = lookupQueue(vehicle.getVehicleId(), station, weaponKey);
            List<Bolt> applied = station.getBolts();
            sb.append("== 站 ").append(station.getId())
              .append(" 当前口径=").append(weaponKey).append("\n");
            sb.append("  缓存队列: ").append(cached == null ? "<未命中>" : cached.size() + " 条").append('\n');
            sb.append("  已应用 Bolt: ").append(applied.size()).append(" 根\n");
            for (int i = 0; i < applied.size(); i++) {
                Bolt bolt = applied.get(i);
                sb.append("    #").append(i)
                  .append(" offset=[").append(fmt(bolt.offset.x)).append(',').append(fmt(bolt.offset.y)).append(',').append(fmt(bolt.offset.z)).append(']')
                  .append(" 管长=").append(fmt(bolt.barrelLength))
                  .append(" xRot=").append(fmt(bolt.xRot)).append(" yRot=").append(fmt(bolt.yRot))
                  .append('\n');
            }
            Vec3 world = station.worldCurrentBoltPosition();
            sb.append("  当前出弹点世界坐标: [").append(fmt(world.x)).append(',').append(fmt(world.y)).append(',').append(fmt(world.z)).append("]\n");
            // 目的：SARH 半主动弹锁状态（定位"无锁制导"泄漏来源）；当前武器制导类型
            String currentGuidance = "<none>";
            java.util.Optional<AbstractVehicleWeapon<?>> operated = station.getCurrentWeapon();
            if (operated.isPresent() && operated.get().getData() != null
                    && operated.get().getData().getWeaponId() != null) {
                currentGuidance = operated.get().getData().getWeaponId().toString();
            }
            org.ywzj.vehicle.vehicle.part.RadarUnit lockRadar =
                    org.ywzj.rvp.radar.RVP_RadarRoleHelper.getLockedRadar(station);
            Entity radarLock = lockRadar == null ? null : lockRadar.getLockedEntity();
            sb.append("  雷达TWS锁: ").append(radarLock == null ? "<无>"
                    : radarLock.getName().getString() + " #" + radarLock.getId()).append('\n');
            Entity unitLock = station.getLockedEntity();
            sb.append("  武器站锁: ").append(unitLock == null ? "<无>"
                    : unitLock.getName().getString() + " #" + unitLock.getId()).append('\n');
        }
        sb.append("=== 结束 ===\n");
        return sb.toString();
    }

    /** 三位小数格式化（调试输出用）。 */
    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    // ==================================================================
    // 骨骼数学（Cube→Bolt，与本体 buildBolts 同构）
    // ==================================================================

    /**
     * 把出弹骨（含匿名子骨）的每个 Cube 展开为一根炮管 Bolt，追加进队列。
     * 锚定骨自身的 Bolt 不叠加骨骼自转（与本体主骨口径一致）；
     * 非锚定骨的骨骼自转写入 bolt.xRot/yRot（支持斜置发射）。
     */
    private static void appendBoneBolts(BedrockBone bone, BedrockBone anchorBone, Vec3 delta,
                                        List<Bolt> out, BedrockModel model) {
        boolean isAnchorBone = bone == anchorBone;
        for (BedrockCube cube : bone.cubes) {
            float x = cube.x() + cube.width() / 2;
            float y = cube.y() + cube.height() / 2;
            float z = cube.z();
            Vec3 boltOffset = new Vec3(bone.rotation.transform(new Vector3f(x, y, z)));
            boltOffset = boltOffset.add(delta);
            float barrelLength = cube.depth();
            Vector3f selfRot = new Vector3f();
            if (isAnchorBone) {
                out.add(new Bolt(boltOffset, barrelLength, 0, 0));
            } else {
                bone.rotation.getEulerAnglesYXZ(selfRot);
                out.add(new Bolt(boltOffset, barrelLength,
                        (float) Math.toDegrees(selfRot.x), (float) Math.toDegrees(-selfRot.y)));
            }
        }
        // 目的：与本体 buildBolts 一致，递归展开出弹骨下的匿名子骨（命名骨属于其他部件，跳过）
        for (BedrockBone child : bone.getChildren()) {
            if (child == null || model.getBoneMap().containsValue(child)) {
                continue;
            }
            appendChildBolts(child, delta.add(new Vec3(child.x / 16, child.y / 16, child.z / 16)),
                    out, model);
        }
    }

    /** 子骨递归展开（见 {@link #appendBoneBolts}），偏移按子骨局部平移累加。 */
    private static void appendChildBolts(BedrockBone bone, Vec3 offset,
                                         List<Bolt> out, BedrockModel model) {
        for (BedrockCube cube : bone.cubes) {
            float x = cube.x() + cube.width() / 2;
            float y = cube.y() + cube.height() / 2;
            float z = cube.z();
            Vec3 boltOffset = new Vec3(bone.rotation.transform(new Vector3f(x, y, z)));
            boltOffset = boltOffset.add(offset);
            float barrelLength = cube.depth();
            Vector3f selfRot = new Vector3f();
            bone.rotation.getEulerAnglesYXZ(selfRot);
            out.add(new Bolt(boltOffset, barrelLength,
                    (float) Math.toDegrees(selfRot.x), (float) Math.toDegrees(-selfRot.y)));
        }
        for (BedrockBone child : bone.getChildren()) {
            if (child == null || model.getBoneMap().containsValue(child)) {
                continue;
            }
            appendChildBolts(child, offset.add(new Vec3(child.x / 16, child.y / 16, child.z / 16)),
                    out, model);
        }
    }

    /**
     * 骨骼 bind 枢轴（模型空间，格单位）：自根向下组合父链平移与旋转。
     * 与 simplebedrockmodel 组全局变换同口径，用于把出弹骨平移差换算到锚定骨坐标系
     * （替代不可公开访问的 vehiclePartGroups 查表，避免新增 @Accessor）。
     */
    private static Vec3 bindPivot(@Nullable BedrockBone bone) {
        if (bone == null) {
            return Vec3.ZERO;
        }
        // 迭代回溯根骨，避免深层骨树递归栈风险；沿途记录各骨局部枢轴
        List<BedrockBone> chain = new ArrayList<>();
        BedrockBone current = bone;
        while (current != null && chain.size() <= MAX_BONE_DEPTH) {
            chain.add(current);
            current = current.parent;
        }
        Vec3 offset = Vec3.ZERO;
        Quaternionf rotation = new Quaternionf();
        for (int i = chain.size() - 1; i >= 0; i--) {
            BedrockBone node = chain.get(i);
            Vec3 local = new Vec3(node.x / 16, node.y / 16, node.z / 16);
            // 目的：父链累计旋转作用于本骨局部枢轴，得到该骨 bind 全局枢轴
            offset = offset.add(rotate(rotation, local));
            rotation = new Quaternionf(rotation).mul(node.rotation);
        }
        return offset;
    }

    /** 用四元数旋转 Vec3 的轻量封装（JOML 的 transform 是原地操作，避免误解）。 */
    private static Vec3 rotate(Quaternionf rotation, Vec3 vec) {
        Vector3f out = rotation.transform(new Vector3f((float) vec.x, (float) vec.y, (float) vec.z));
        return new Vec3(out.x, out.y, out.z);
    }
}
