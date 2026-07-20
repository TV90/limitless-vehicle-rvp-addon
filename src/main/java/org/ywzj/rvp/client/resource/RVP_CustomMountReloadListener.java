package org.ywzj.rvp.client.resource;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;

public final class RVP_CustomMountReloadListener extends SimplePreparableReloadListener<Void> {

    public static final RVP_CustomMountReloadListener INSTANCE = new RVP_CustomMountReloadListener();

    private RVP_CustomMountReloadListener() {}

    @Override
    protected @NotNull Void prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        return null;
    }

    @Override
    protected void apply(@NotNull Void object, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        RVP_CustomMountRenderLogic.clearModelCache();
    }
}
