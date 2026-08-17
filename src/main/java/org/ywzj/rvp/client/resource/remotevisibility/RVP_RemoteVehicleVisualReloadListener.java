package org.ywzj.rvp.client.resource.remotevisibility;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState;

/** 资源重载时清除远距载具代理，防止旧 display 模型实例跨资源代际复用。 */
public final class RVP_RemoteVehicleVisualReloadListener extends SimplePreparableReloadListener<Void> {
    /** 客户端资源系统注册的单例监听器。 */
    public static final RVP_RemoteVehicleVisualReloadListener INSTANCE =
            new RVP_RemoteVehicleVisualReloadListener();

    private RVP_RemoteVehicleVisualReloadListener() {
    }

    @Override
    protected @NotNull Void prepare(@NotNull ResourceManager resourceManager,
                                    @NotNull ProfilerFiller profiler) {
        return null;
    }

    @Override
    protected void apply(@NotNull Void ignored, @NotNull ResourceManager resourceManager,
                         @NotNull ProfilerFiller profiler) {
        // 调用 RVP 载具视觉状态，释放旧资源对应的代理和远距 LOD 选级缓存。
        RVP_ClientRemoteVehicleVisualState.clear();
    }
}
