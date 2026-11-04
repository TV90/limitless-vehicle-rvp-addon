package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.maydaymemory.mae.control.runner.AnimationContext;
import com.maydaymemory.mae.control.runner.AnimationRunner;
import com.maydaymemory.mae.control.runner.PlayingState;
import com.maydaymemory.mae.control.runner.StopState;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientVirtualMissileVisualState;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;

import java.util.Map;
import java.util.WeakHashMap;

/** 没有生成包的显示对象专用模型/动画副本，不修改本体私有字段。 */
public final class RVP_VirtualMissileModelRuntime extends SimplePreparableReloadListener<Void> {
    /** 注册到现有客户端资源重载链，释放上个资源代际。 */
    public static final RVP_VirtualMissileModelRuntime INSTANCE = new RVP_VirtualMissileModelRuntime();
    /** 每对象一份烘焙实例和动画，弱键外加显式释放，绝不让多枚弹共享可变姿态。 */
    private static final Map<AmmoEntity, Runtime> RUNTIMES = new WeakHashMap<>();

    private RVP_VirtualMissileModelRuntime() { }

    /** display 或模型资源对象变化时重建；缺资源不缓存失败，下一帧可重试。 */
    static Runtime resolve(AmmoEntity entity, BaseDisplay display) {
        // 调用对象引用归属查询；真实实体和雷达代理仍走原有生成包初始化。
        if (RVP_ClientVirtualMissileVisualState.owned(entity) == null || display == null
                || display.getModel() == null || !display.getModel().hasBakedModel()) return null;
        Runtime runtime = RUNTIMES.get(entity);
        if (runtime == null || runtime.display != display || runtime.bakedResource != display.getModel().getBakedModel()) {
            // 调用本体公开烘焙资源接口建立独立实例，不伪造 readSpawnData。
            BakedModelInstance instance = display.getModel().getBakedModel().createInstance();
            AnimationRunner runner = null;
            if (!display.getAnimations().isEmpty()) {
                var animation = display.getAnimations().values().iterator().next();
                runner = new AnimationRunner(animation, new AnimationContext(animation.getSpecifiedEndTimeS()));
                // 动画时间也绑定客户端动画刻，暂停后恢复不能把现实经过的时间一次性跳过去。
                runner.setState(new PlayingState(() -> RVP_ClientVirtualMissileVisualState.animationTick() * 50_000_000L,
                        StopState::new));
            }
            runtime = new Runtime(display, display.getModel().getBakedModel(), instance, runner);
            RUNTIMES.put(entity, runtime);
        }
        return runtime;
    }

    /** 代理退出时立刻释放，不等待弱引用回收。 */
    public static void release(AmmoEntity entity) { RUNTIMES.remove(entity); }

    @Override
    protected @NotNull Void prepare(@NotNull ResourceManager resources, @NotNull ProfilerFiller profiler) { return null; }

    @Override
    protected void apply(@NotNull Void unused, @NotNull ResourceManager resources, @NotNull ProfilerFiller profiler) {
        RUNTIMES.clear();
    }

    /** 每枚显示弹独享的可变模型与动画；只能在渲染线程访问。 */
    static final class Runtime {
        /** 本次使用的 display 身份，热重载替换时重建。 */
        private final BaseDisplay display;
        /** 烘焙资源身份，display 不变但资源更新时也重建。 */
        private final Object bakedResource;
        /** 独立烘焙模型。 */
        final BakedModelInstance instance;
        /** 可选动画运行器，不复用真实实体或其他导弹的状态。 */
        private final AnimationRunner runner;
        /** 上次更新动画的客户端动画刻，同刻多相机不重复推进。 */
        private long animationTick = Long.MIN_VALUE;

        private Runtime(BaseDisplay display, Object bakedResource, BakedModelInstance instance, AnimationRunner runner) {
            this.display = display; this.bakedResource = bakedResource; this.instance = instance; this.runner = runner;
        }

        /** 姿态先复位；只在新客户端刻更新动画，暂停时保留画面。 */
        void updatePose() {
            // 调用显示缓存动画时钟，不通过代理实体 tick 推进。
            long now = RVP_ClientVirtualMissileVisualState.animationTick();
            if (runner != null && animationTick != now && !Minecraft.getInstance().isPaused()) {
                runner.tick(); animationTick = now;
            }
            instance.applyPose(instance.getBindPose());
            if (runner != null) instance.applyPose(org.ywzj.vehicle.client.render.animation.util.PoseBlenders.BLENDER
                    .blend(instance.getBindPose(), runner.evaluate()));
        }
    }
}
