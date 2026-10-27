package org.ywzj.rvp.resource;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.all.AllTabs;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplayPojo;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.resource.VehiclePackLoader;
import org.ywzj.vehicle.resource.VehiclePackLoader.VehiclePack;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 首启补注册载具包的创造模式标签页（2026-10-25）。
 *
 * <p>本体 {@code AllTabs.register} 在 {@code YwzjVehicle} 构造器内执行，消费的是构造器
 * 开头 {@code scanVehiclePacks()} 的缓存列表——依赖顺序上本体先于本 mod 构造，RVP 包
 * 解压（含 {@link RVP_VehiclePackInstaller} 的补扫）永远晚于 tab 注册：全新安装或
 * 版本不等触发"备份删除重装"的首次启动，创造栏没有包标签页（物品/实体经补扫已可用，
 * 仅 tab 缺失），第二次启动本体构造器即可扫到才显示。</p>
 *
 * <p>修复：安装路径（解压+补扫完成后）把扫描列表里 tab 尚未注册的包按本体
 * {@code AllTabs.register} 同款规则（默认 tab + tabName 分组 tab，UUID 后缀同名）追加进
 * 本体 public 的 {@code AllTabs.TABS}——DeferredRegister 在注册事件触发前（= 所有 mod
 * 构造完成前）可继续加条目，本注入恰在窗口内。按包 meta 的 namespace 判断（与本体
 * tab 命名 "tab_"+namespace 一致；载具包 namespace 是 "rvp"，不是 mod id "ywzj_rvp"），
 * 已注册（二启后本体自建）则跳过，进程级幂等；tab 内容 generator 为懒执行（打开背包时
 * 读 CommonAssetsManager），届时包数据已加载。对第三方后装包（namespace 非 ywzj_vehicle）
 * 同样生效。</p>
 */
public final class RVP_PackTabInjector {

    private RVP_PackTabInjector() {}

    public static void injectIfNeeded() {
        for (VehiclePack vehiclePack : VehiclePackLoader.INSTANCE.getVehiclePacks()) {
            String namespace = vehiclePack.meta().getNamespace();
            if (namespace == null || namespace.equals("ywzj_vehicle")) {
                continue;
            }
            ResourceLocation expectedTabId = new ResourceLocation("ywzj_vehicle", "tab_" + namespace);
            boolean alreadyRegistered = AllTabs.TABS.getEntries().stream()
                    .anyMatch(entry -> entry.getKey().equals(expectedTabId));
            if (alreadyRegistered) {
                continue;
            }
            registerTabsForPack(namespace, vehiclePack);
        }
    }

    /** 复刻本体 AllTabs.register 对单个载具包的建 tab 逻辑（默认 tab + tabName 分组）。 */
    private static void registerTabsForPack(String namespace, VehiclePack vehiclePack) {
        ResourceLocation defaultIcon = new ResourceLocation(namespace + ":textures/tab.png");
        Map<String, ResourceLocation> groups = new TreeMap<>();
        VehiclePackLoader.INSTANCE.scanVehicleDisplayDefinitions().entrySet().stream()
                .filter(entry -> entry.getKey().getNamespace().equals(namespace))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    BaseDisplayPojo display = entry.getValue();
                    if (display != null && display.tabName != null && !display.tabName.isBlank()) {
                        groups.putIfAbsent(display.tabName, display.tabIcon);
                    }
                });

        registerVehicleTab("tab_" + namespace,
                Component.translatable(vehiclePack.meta().getTitle()),
                defaultIcon,
                namespace, vehicleId -> !groups.containsKey(tabName(vehicleId)));
        groups.forEach((tabName, icon) -> {
            String suffix = UUID.nameUUIDFromBytes(tabName.getBytes(StandardCharsets.UTF_8)).toString();
            registerVehicleTab("tab_" + namespace + "/" + suffix,
                    Component.translatable(tabName),
                    icon != null ? icon : defaultIcon,
                    namespace, vehicleId -> tabName.equals(tabName(vehicleId)));
        });
    }

    /** 复刻本体 AllTabs.addVehicleTab（private，此处等价重写）：displayItems 懒读运行期数据。 */
    private static void registerVehicleTab(String name, Component title, ResourceLocation icon,
                                           String namespace, Predicate<ResourceLocation> filter) {
        Supplier<ItemStack> iconSupplier = () ->
                AllItems.PLAIN_TEXTURE_ITEM.get().createInstance(icon);
        RegistryObject<CreativeModeTab> registered = AllTabs.TABS.register(name, () ->
                CreativeModeTab
                        .builder()
                        .title(title)
                        .icon(iconSupplier)
                        .displayItems((displayParams, output) ->
                                CommonAssetsManager.vehicleDataManager().getVehicleData().values().stream()
                                        .filter(vehicleData -> vehicleData.getVehicleId().getNamespace().equals(namespace))
                                        .filter(vehicleData -> filter.test(vehicleData.getVehicleId()))
                                        .sorted(Comparator.comparingInt(vehicleData ->
                                                ClientAssetsManager.INSTANCE
                                                        .getVehicleDisplay(vehicleData.getVehicleId())
                                                        .map(baseDisplay -> baseDisplay.getTabIndex()
                                                                + (vehicleData.isExperimental()
                                                                && (baseDisplay.getTabName() == null || baseDisplay.getTabName().isBlank())
                                                                ? 1_000_000 : 0))
                                                        .orElse(0)))
                                        .forEach(vehicleData -> output.accept(
                                                AllItems.VEHICLE_SPAWN_ITEM.get().createInstance(vehicleData.getVehicleId()))))
                        .build());
        org.apache.logging.log4j.LogManager.getLogger(RVP_MOD.MOD_ID)
                .info("Injected first-launch creative tab {} for pack namespace {}", registered.getId(), namespace);
    }

    /** 复刻本体 AllTabs.getTabName（private，此处等价重写）。 */
    private static String tabName(ResourceLocation vehicleId) {
        return ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicleId)
                .map(BaseDisplay::getTabName)
                .orElse("");
    }
}
