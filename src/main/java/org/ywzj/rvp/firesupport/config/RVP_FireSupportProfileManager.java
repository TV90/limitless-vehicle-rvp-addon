package org.ywzj.rvp.firesupport.config;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportResolvedWeapon;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportSnapshot;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

/** 服务端权威 profile 管理器；失败重载不会破坏上一份可用快照。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FireSupportProfileManager
        extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {
    /** 全局管理器实例。 */ public static final RVP_FireSupportProfileManager INSTANCE = new RVP_FireSupportProfileManager();
    /** 配置日志。 */ private static final Logger LOGGER = LogUtils.getLogger();
    /** 当前原子发布的不可变快照。 */ private volatile RVP_FireSupportSnapshot snapshot = RVP_FireSupportSnapshot.empty();
    /** 等待本体武器索引完成同轮 reload 后再交叉校验的候选 JSON；null 表示没有待发布批次。 */
    private Map<ResourceLocation, JsonElement> pendingCandidates;

    private RVP_FireSupportProfileManager() {}

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        // 调用本体资源扫描器读取载具包 data/<namespace>/fire_support_profiles，仅接受当前目录和 schema。
        return Map.copyOf(ResourceScanner.scanDirectory(resourceManager, "fire_support_profiles", GsonUtil.GSON));
    }

    @Override
    protected synchronized void apply(Map<ResourceLocation, JsonElement> candidates,
                                      ResourceManager resourceManager, ProfilerFiller profiler) {
        // 本体武器索引与自定义 listener 的 apply 顺序没有依赖保证；先冻结候选，稍后统一交叉校验。
        pendingCandidates = Map.copyOf(candidates);
        LOGGER.info("已准备 {} 个炮火支援 profile 候选，等待本体武器索引完成", candidates.size());
    }

    /**
     * 尝试原子发布候选快照。公开此纯入口是为了单测验证“非法候选保留旧快照”。
     */
    public synchronized boolean tryPublish(Map<ResourceLocation, JsonElement> candidates,
                                           RVP_FireSupportWeaponResolver resolver) {
        try {
            Map<ResourceLocation, RVP_FireSupportProfile> parsed = RVP_FireSupportProfileParser.parseAll(candidates, resolver);
            snapshot = new RVP_FireSupportSnapshot(snapshot.revision() + 1, parsed);
            LOGGER.info("已发布 {} 个炮火支援 profile，revision={}", parsed.size(), snapshot.revision());
            return true;
        } catch (RuntimeException ex) {
            LOGGER.error("炮火支援 profile 候选配置无效: {}", ex.getMessage());
            return false;
        }
    }

    /** @return 当前不可变快照。 */
    public RVP_FireSupportSnapshot snapshot() { return snapshot; }

    /**
     * 在本体同轮资源索引已经可见后发布候选批次。
     * 候选在尝试前即从 pending 槽取走；非法数据只记录一次并继续保留旧快照。
     */
    public synchronized boolean publishPreparedCandidates() {
        Map<ResourceLocation, JsonElement> candidates = pendingCandidates;
        if (candidates == null) return true;
        pendingCandidates = null;
        boolean published = tryPublish(candidates, RVP_FireSupportProfileManager::resolveIndexedWeapon);
        if (!published) {
            LOGGER.error("炮火支援 profile 重载整批失败，继续使用 revision {} 的旧快照", snapshot.revision());
        }
        return published;
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    /** 初次专服资源加载完成后发布候选；此时本体武器索引已经构建完毕。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        INSTANCE.publishPreparedCandidates();
    }

    private static RVP_FireSupportResolvedWeapon resolveIndexedWeapon(ResourceLocation weaponId) {
        // 调用本体权威武器索引交叉引用 profile；非 RVP 数据明确返回 null，禁止路径名推断。
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .filter(index -> index.data() instanceof RVP_WeaponData)
                .map(index -> RVP_FireSupportResolvedWeapon.from((RVP_WeaponData) index.data()))
                .orElse(null);
    }
}
