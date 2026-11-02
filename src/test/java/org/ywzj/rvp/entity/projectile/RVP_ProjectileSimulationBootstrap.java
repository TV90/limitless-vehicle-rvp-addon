package org.ywzj.rvp.entity.projectile;

import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryBuilder;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.vehicle.all.AllItems;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** 测试进程的最小构造环境；只准备注册元信息，不启动网络、客户端或世界 Tick。 */
public final class RVP_ProjectileSimulationBootstrap {
    /** 这些准备只在独立 Java 测试进程执行一次，不进入发布 jar。 */
    private static boolean initialized;
    private RVP_ProjectileSimulationBootstrap() {}

    /** 让真实实体构造器和真实武器默认值可用，气动及运动方法完全不做替换。 */
    public static synchronized void initialize() {
        if (initialized) return;
        SharedConstants.tryDetectVersion();
        // 原版基础注册初始化可独立运行；完整 Bootstrap 末尾会启动 Forge 网络，数值测试不需要它。
        write(Bootstrap.class, null, "isBootstrapped", true);
        // 先建立原版根注册表，避免从 Forge 延迟注册入口反向触发循环类初始化。
        if (BuiltInRegistries.REGISTRY.keySet().isEmpty()) throw new IllegalStateException("原版注册表未建立");
        // 调用生产延迟注册表中的工厂，保留每一种真实 RVP EntityType 的尺寸和类型定义。
        bindProjectileTypes();
        BuiltInRegistries.bootStrap();
        // 测试不执行世界资源重载，显式绑定原版水标签；鱼雷仍读取真实 FluidState.is(WATER)。
        BuiltInRegistries.FLUID.bindTags(Map.of(net.minecraft.tags.FluidTags.WATER, java.util.List.of(
                BuiltInRegistries.FLUID.wrapAsHolder(net.minecraft.world.level.material.Fluids.WATER),
                BuiltInRegistries.FLUID.wrapAsHolder(net.minecraft.world.level.material.Fluids.FLOWING_WATER))));
        try {
            var builder = new RegistryBuilder<FluidType>().setName(ForgeRegistries.Keys.FLUID_TYPES.location());
            Method create = RegistryBuilder.class.getDeclaredMethod("create"); create.setAccessible(true); create.invoke(builder);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("无法准备测试流体注册表", error); }
        // 实体构造器需要“眼部为空气”的初值；水/空气查询由场景世界提供，不由该占位对象计算运动。
        write(RegistryObject.class, ForgeMod.EMPTY_TYPE, "value", new FluidType(FluidType.Properties.create()) {});
        // 本体武器构造器总会建立 reload；弹药物品不参与飞行，用已有物品占位，所有物理默认值由真构造器建立。
        write(RegistryObject.class, AllItems.AMMO_AUTO_CANNON, "value", Items.ARROW);
        FMLPaths.loadAbsolutePaths(Path.of("build/projectile-simulation-environment"));
        initialized = true;
    }

    /** 运行已有注册工厂并接入此测试进程的原版注册表，不复制实体类型定义。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bindProjectileTypes() {
        Map<RegistryObject<?>, Supplier<?>> factories = (Map) read(DeferredRegister.class, RVP_Entities.ENTITIES, "entries");
        for (var entry : factories.entrySet()) {
            Object type = entry.getValue().get();
            Registry.register((Registry) BuiltInRegistries.ENTITY_TYPE, entry.getKey().getId(), type);
            write(RegistryObject.class, entry.getKey(), "value", type);
        }
    }

    /** 仅给没有真实服务器的环境边界赋值，不改任何运动算法。 */
    public static void write(Class<?> owner, Object instance, String name, Object value) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("测试环境字段已变化：" + owner.getSimpleName() + "." + name, error); }
    }

    /** 读取测试环境和只读观察值，字段变化直接报错，不能静默改成近似算法。 */
    public static Object read(Class<?> owner, Object instance, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("测试环境字段已变化：" + owner.getSimpleName() + "." + name, error); }
    }

    /** 只分配外部环境对象；弹体和武器配置必须走真实构造器。 */
    public static <T> T environment(Class<T> type) {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("无法建立测试环境：" + type.getSimpleName(), error); }
    }
}
