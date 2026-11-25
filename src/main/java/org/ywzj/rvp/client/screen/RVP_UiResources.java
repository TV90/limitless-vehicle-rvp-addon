package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.parser.HTML;
import com.sighs.apricityui.resource.async.image.ImageAsyncHandler;
import com.sighs.apricityui.style.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.gui.RVP_HitIndicatorOverlay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只预热静态资源；载具状态、改装回调与网络请求仍由真正打开的界面创建。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID)
public final class RVP_UiResources extends SimplePreparableReloadListener<NativeImage> {
    /** 由客户端资源重载事件注册，服务端不加载本类。 */
    public static final RVP_UiResources INSTANCE = new RVP_UiResources();
    /** 仅包含这两张界面的静态模板，不预建带实体状态的 Screen。 */
    private static final String[] TEMPLATES = {"screens/rvp_variants.html", "screens/rvp_equipment.html"};
    /** 记录已预热的模板内容；AUI 重载发生较晚时，也能识别更新后的模板。 */
    private static final Map<String, String> preparedTemplates = new HashMap<>();
    /** 候选图标按帧交给 AUI 自有异步加载器，不一次上传整个素材库。 */
    private static final ArrayDeque<String> icons = new ArrayDeque<>();
    /** 当前会话中已经请求过的图标，避免反复提交。 */
    private static final Set<String> requestedIcons = new HashSet<>();
    /** 最近目标仅保存 UUID，不持有退出世界后的实体。 */
    private static UUID lastVehicle;
    /** 每秒检查一次附近目标，不在每个渲染帧扫描实体。 */
    private static long nextVehicleCheck;
    /** 字体和地图类初始化只在标题/载入阶段完成。 */
    private static boolean fontsPrepared, mapPrepared;

    private RVP_UiResources() { }

    @Override
    protected NativeImage prepare(ResourceManager resources, ProfilerFiller profiler) {
        try {
            // 调用原背景处理，确保只改变准备时机，不改变颜色或模糊程度。
            return RVP_WorkshopPreview.prepareBackdrop(resources);
        } catch (IOException error) {
            LogUtils.getLogger().warn("[RVP] 无法预加载改装背景", error);
            return null;
        }
    }

    @Override
    protected void apply(NativeImage image, ResourceManager resources, ProfilerFiller profiler) {
        // 上传静态背景；TextureManager 接管图像和旧纹理的生命周期。
        RVP_WorkshopPreview.installBackdrop(image);
        preparedTemplates.clear();
        fontsPrepared = mapPrepared = false;
        clearWorldResources();
    }

    @SubscribeEvent
    public static void onFrame(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) return;
        if (mc.level == null || mc.screen instanceof ReceivingLevelScreen) {
            prepareTemplate();
            return;
        }
        if (mc.player == null || mc.screen != null) return;
        // 每帧最多创建一层缓冲，窗口缩放后也走相同的分帧路径。
        if (!RVP_MapFrost.prepareOne() || !RVP_HitIndicatorOverlay.prepareGlassBuffers()) return;
        long tick = mc.level.getGameTime();
        if (tick >= nextVehicleCheck) {
            nextVehicleCheck = tick + 20;
            AbstractVehicle target = mc.player.getVehicle() instanceof AbstractVehicle ridden ? ridden
                    : mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof AbstractVehicle aimed ? aimed : null;
            if (target == null) target = mc.level.getEntitiesOfClass(AbstractVehicle.class,
                    mc.player.getBoundingBox().inflate(16)).stream()
                    .min(java.util.Comparator.comparingDouble(mc.player::distanceToSqr)).orElse(null);
            if (target != null && !target.getUUID().equals(lastVehicle)) {
                lastVehicle = target.getUUID();
                // 与菜单使用同一槽位解析，只请求当前目标真正可展示的武器图。
                for (var station : RVP_WorkshopStation.collect(target)) {
                    for (int i = 0; i < station.size(); i++) {
                        ResourceLocation id = station.weapon(i).getData().getWeaponId();
                        String path = "icons/workshop/" + id.getNamespace() + "/" + id.getPath() + ".png";
                        if (mc.getResourceManager().getResource(new ResourceLocation("apricityui", "apricity/" + path)).isPresent()
                                && requestedIcons.add(path)) icons.add(path);
                    }
                }
            }
        }
        if (!icons.isEmpty()) ImageAsyncHandler.INSTANCE.request(icons.removeFirst());
    }

    /** 模板布局可能耗时较长，只在标题/载入画面处理，一帧一个，不占用正常游玩帧。 */
    private static void prepareTemplate() {
        if (!fontsPrepared) {
            fontsPrepared = true;
            Text.warmUpFontFamily("\"Microsoft YaHei\",\"Segoe UI\",sans-serif");
            return;
        }
        for (String path : TEMPLATES) {
            String source = HTML.getTemple(path);
            if (source == null || source.equals(preparedTemplates.get(path))) continue;
            preparedTemplates.put(path, source);
            Document document = null;
            try {
                document = Document.create(path);
                document.setManuallyRendered(true);
                document.commitRenderState();
            } catch (RuntimeException error) {
                LogUtils.getLogger().warn("[RVP] 无法预热界面模板 {}", path, error);
            } finally {
                if (document != null) document.remove();
            }
            return;
        }
        if (!mapPrepared) {
            mapPrepared = true;
            // 仅预热地图构造所需的类，不 init、不切换画面，也不发送网络包。
            new RVP_TacticalMapScreen();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clearWorldResources(); }

    /** 换世界与资源重载释放显存；下一帧重新捕获当前画面，不保留旧场景。 */
    private static void clearWorldResources() {
        RVP_MapFrost.release();
        RVP_HitIndicatorOverlay.releaseGlassBuffers();
        icons.clear();
        requestedIcons.clear();
        lastVehicle = null;
        nextVehicleCheck = 0;
    }
}
