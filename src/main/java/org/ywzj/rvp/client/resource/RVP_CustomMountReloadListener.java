package org.ywzj.rvp.client.resource;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.rvp.client.resource.vehicle.RVP_DisplayBackendUtil;
import org.ywzj.rvp.config.UIPresetManager;

public final class RVP_CustomMountReloadListener extends SimplePreparableReloadListener<Void> {

    public static final RVP_CustomMountReloadListener INSTANCE = new RVP_CustomMountReloadListener();

    private RVP_CustomMountReloadListener() {}

    @Override
    protected @NotNull Void prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        return null;
    }

    /**
     * 资源重载（启动 / F3+T / 服务器资源包 / {@code /ywzj_vehicle reload}）结束时清理并重载 RVP 侧缓存。
     *
     * <p>本方法只做**不依赖本体 display 数据**的部分：纯清空（挂架模型、backend 缓存）与
     * UI 预设重读（直接读 resourceManager）。</p>
     *
     * <p>依赖 display 数据的规则表（{@code RVP_StateBoneHider} / {@code RVP_DistanceBoneHider} /
     * {@code RVP_LodModelManager}）**不在这里重建**：本监听器与本体 display 监听器同属一个重载批次，
     * 执行先后无法保证，此刻拿到的 display 可能是半新半旧。它们改为在渲染热路径比对
     * {@code ClientAssetsManager.getVehicleDisplays()} 的实例引用（本体每次重载都 build 新 ImmutableMap），
     * 引用变化即自动重建 —— 这也正是原本由 MinecraftReloadResourcePacksMixin 与
     * ClientAssetsManagerCustomMountMixin 两个 mixin 承担的职责（2026-10-28 退休，改走公共 API）。</p>
     */
    @Override
    protected void apply(@NotNull Void object, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        RVP_CustomMountRenderLogic.clearModelCache();
        // backend / no-cull 骨骼缓存是 WeakHashMap<VehicleBedrockModel, …>：旧模型实例会随 GC 消失，
        // 这里主动清一遍让重载后首个访问直接落到最新 display 上（未命中时 getBackend 自会 bindKnownDisplays）。
        RVP_DisplayBackendUtil.clearCache();
        // UI 预设：数据包同名优先、config 兜底，只依赖 resourceManager，可在此立即重读。
        UIPresetManager.load(resourceManager);
        // 载具 JSON 的 RVP 扩展字段（ui_preset / 隐藏骨 / 挂架 / 过热 / RCS …）：
        // 原先由 VehicleDataManagerMixin 贴在 VehicleDataManager.apply 末尾，2026-10-30 改为数据重载监听器；
        // 客户端资源重载（启动 / F3+T / 服务器资源包）不走 AddReloadListenerEvent，在这里补一次。
        org.ywzj.rvp.config.RVP_VehicleSupplementListener.INSTANCE.reloadFrom(resourceManager);
    }
}
