package org.ywzj.rvp.client.tuning;

import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.IModBusEvent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 复现真实setup阶段游戏总线尚未启动的条件，防止面板再次显示零分类、零参数。 */
class RVP_TuningModuleRegistrationTest {
    @Test
    void annotatedModuleRegistersOnLoadingBusWhileGameplayBusIsStillShutdown() throws IOException {
        // 调用Forge真实总线构造器，保留游戏总线的启动前状态，不用已启动的总线掩盖问题。
        IEventBus gameplayBus = BusBuilder.builder().startShutdown().build();
        IEventBus loadingBus = BusBuilder.builder().markerType(IModBusEvent.class).build();
        try {
            // 像Forge自动扫描器一样读取字节码注解，避免反射初始化Bus枚举并意外启动整个MinecraftForge。
            String subscriberBus = readSubscriberBus(RVP_ScreenSpaceTuningModule.class);
            assertEquals("MOD", subscriberBus, "setup期登记必须监听MOD加载总线");
            assertEquals("MOD", readSubscriberBus(RVP_MuzzleSmokeTuningModule.class), "白烟同样须使用MOD总线");
            assertEquals("MOD", readSubscriberBus(RVP_WreckCookoffTuningModule.class), "殉燃须使用MOD总线");
            assertEquals("MOD", readSubscriberBus(RVP_WreckSmokeTuningModule.class), "击毁烟须使用MOD总线");
            assertEquals("MOD", readSubscriberBus(RVP_RocketPlumeTuningModule.class), "程序尾焰须使用MOD总线");
            assertEquals("MOD", readSubscriberBus(RVP_VehicleExplosionTuningModule.class), "程序大爆炸须使用MOD总线");
            assertEquals("MOD", readSubscriberBus(RVP_RenderPerformanceTuningModule.class), "性能记录入口须使用MOD总线");
            assertTrue(IModBusEvent.class.isAssignableFrom(RVP_RegisterTuningModulesEvent.class));
            RVP_TuningRegistry registry = new RVP_TuningRegistry();
            RVP_RegisterTuningModulesEvent event = new RVP_RegisterTuningModulesEvent(registry);
            // 普通JUnit没有ModLauncher生成的事件监听表，先通过真实事件实例建立Forge原生监听表。
            event.getListenerList();
            Map<String, IEventBus> buses = Map.of("FORGE", gameplayBus, "MOD", loadingBus);
            // 根据生产类实际的自动订阅注解选择总线，再注册真实的静态事件处理器。
            buses.get(subscriberBus).register(RVP_ScreenSpaceTuningModule.class);
            loadingBus.register(RVP_MuzzleSmokeTuningModule.class);
            loadingBus.register(RVP_WreckCookoffTuningModule.class);
            loadingBus.register(RVP_WreckSmokeTuningModule.class);
            loadingBus.register(RVP_RocketPlumeTuningModule.class);
            loadingBus.register(RVP_VehicleExplosionTuningModule.class);
            loadingBus.register(RVP_RenderPerformanceTuningModule.class);

            gameplayBus.post(event);
            assertTrue(registry.modules().isEmpty(), "尚未启动的游戏总线不会执行登记回调");
            loadingBus.post(event);
            registry.seal();
            assertEquals(7, registry.modules().size());
            assertEquals("screen_space", registry.modules().get(0).id());
            assertEquals(6, registry.modules().get(0).groups().size());
            assertFalse(registry.options().isEmpty());
            assertFalse(registry.search("screen_space", "brightness").isEmpty());
            assertNotNull(registry.options().get("screen_space/shockwave_sphere/refraction"));
            assertNotNull(registry.options().get("muzzle_smoke/motion/curlStrength"));
            assertNotNull(registry.options().get("muzzle_smoke/appearance/expansionFade"));
            assertNotNull(registry.options().get("wreck_cookoff/spark_look/spark_cooling"));
            assertNotNull(registry.options().get("wreck_cookoff/shape/natural_tail"));
            assertNotNull(registry.options().get("wreck_smoke/long_shape/sizeGain"));
            assertNotNull(registry.options().get("wreck_smoke/near_flow/nearCurlStrength"));
            assertNotNull(registry.options().get("rocket_plume/shape/lengthScale"));
            assertNotNull(registry.options().get("rocket_plume/transition/ignitionTicks"));
            assertNotNull(registry.options().get("vehicle_explosion/shape/radius"));
            assertNotNull(registry.options().get("vehicle_explosion/preview/hold"));
            assertNotNull(registry.options().get("render_performance/recording/intervalSeconds"));
            assertTrue(registry.snapshot().isEmpty(), "登记时不能把默认值写成用户覆盖");
            System.out.println("REGRESSION_REGISTER bus=" + subscriberBus + " modules="
                    + registry.modules().stream().map(RVP_TuningModule::id).toList() + " options=" + registry.options().size());
        } finally {
            // 移除真实静态处理器，避免本测试的私有总线继续持有模块类。
            loadingBus.unregister(RVP_ScreenSpaceTuningModule.class);
            loadingBus.unregister(RVP_MuzzleSmokeTuningModule.class);
            loadingBus.unregister(RVP_WreckCookoffTuningModule.class);
            loadingBus.unregister(RVP_WreckSmokeTuningModule.class);
            loadingBus.unregister(RVP_RocketPlumeTuningModule.class);
            loadingBus.unregister(RVP_VehicleExplosionTuningModule.class);
            loadingBus.unregister(RVP_RenderPerformanceTuningModule.class);
        }
    }

    /** 仅检查实际编译产物上的订阅总线，不启动Forge的游戏环境或改写生产字节码。 */
    private static String readSubscriberBus(Class<?> module) throws IOException {
        String[] bus = {null};
        try (var source = module.getResourceAsStream(module.getSimpleName() + ".class")) {
            assertNotNull(source);
            new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!descriptor.equals("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;")) { return null; }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override public void visitEnum(String name, String descriptor, String value) {
                            if (name.equals("bus")) { bus[0] = value; }
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return bus[0];
    }
}
