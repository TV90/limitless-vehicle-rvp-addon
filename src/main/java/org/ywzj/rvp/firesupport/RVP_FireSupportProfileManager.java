package org.ywzj.rvp.firesupport;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.Map;

/** 服务端权威 profile 管理器；失败重载不会破坏上一份可用快照。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FireSupportProfileManager
        extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {
    /** 全局管理器实例。 */ public static final RVP_FireSupportProfileManager INSTANCE = new RVP_FireSupportProfileManager();
    /** 配置日志。 */ private static final Logger LOGGER = LogUtils.getLogger();
    /** 当前原子发布的不可变快照。 */ private volatile RVP_FireSupportSnapshot snapshot = RVP_FireSupportSnapshot.empty();

    private RVP_FireSupportProfileManager() {}

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        // 调用本体资源扫描器读取载具包 data/<namespace>/fire_support_profiles，仅接受当前目录和 schema。
        return Map.copyOf(ResourceScanner.scanDirectory(resourceManager, "fire_support_profiles", GsonUtil.GSON));
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> candidates, ResourceManager resourceManager, ProfilerFiller profiler) {
        if (!tryPublish(candidates, RVP_FireSupportProfileManager::resolveIndexedWeapon)) {
            LOGGER.error("炮火支援 profile 重载整批失败，继续使用 revision {} 的旧快照", snapshot.revision());
        }
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

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    private static RVP_FireSupportResolvedWeapon resolveIndexedWeapon(ResourceLocation weaponId) {
        // 调用本体权威武器索引交叉引用 profile；非 RVP 数据明确返回 null，禁止路径名推断。
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .filter(index -> index.data() instanceof RVP_WeaponData)
                .map(index -> RVP_FireSupportResolvedWeapon.from((RVP_WeaponData) index.data()))
                .orElse(null);
    }
}
