package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** 内建炮火几何工厂注册表；公共初始化后冻结，重复 ID 直接失败。 */
public final class RVP_FireSupportPatternTypes {
    /** 内建点状类型。 */ public static final ResourceLocation POINT = id("point");
    /** 内建条状类型。 */ public static final ResourceLocation LINE = id("line");
    /** 内建徐进类型。 */ public static final ResourceLocation CREEPING = id("creeping");
    /** 可变注册阶段使用的工厂表。 */ private static final Map<ResourceLocation, RVP_FireSupportPatternFactory> MUTABLE = new LinkedHashMap<>();
    /** 冻结后的只读工厂表。 */ private static Map<ResourceLocation, RVP_FireSupportPatternFactory> factories;

    static {
        register(new ParameterPatternFactory(POINT, Map.of("radius_m", 1)));
        register(new ParameterPatternFactory(LINE, Map.of("length_m", 1, "width_m", 1)));
        register(new ParameterPatternFactory(CREEPING, Map.of("length_m", 1, "width_m", 1, "step_m", 1)));
        freeze();
    }

    private RVP_FireSupportPatternTypes() {}

    private static void register(RVP_FireSupportPatternFactory factory) {
        if (factories != null) throw new IllegalStateException("炮火几何注册表已经冻结");
        if (MUTABLE.putIfAbsent(factory.typeId(), factory) != null) throw new IllegalStateException("重复几何类型 " + factory.typeId());
    }

    private static void freeze() { factories = Map.copyOf(MUTABLE); }

    /** @return 已冻结的工厂；未知类型返回 null。 */
    public static RVP_FireSupportPatternFactory get(ResourceLocation id) { return factories.get(id); }

    /** @return 已注册类型的不可变视图。 */
    public static Map<ResourceLocation, RVP_FireSupportPatternFactory> all() { return factories; }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("rvp", path); }

    private record ParameterPatternFactory(ResourceLocation typeId, Map<String, Integer> required)
            implements RVP_FireSupportPatternFactory {
        @Override
        public RVP_FireSupportPattern parseAndCreate(JsonObject data, RVP_FireSupportProblemCollector problems, String path) {
            RVP_FireSupportJson.keys(data, java.util.Set.of("parameters"), path, problems);
            JsonObject parameters = RVP_FireSupportJson.object(data, "parameters", path, problems, true);
            if (!parameters.keySet().equals(required.keySet())) {
                problems.add(path + ".parameters", "参数必须恰好为 " + required.keySet());
            }
            Map<String, Double> maximums = new LinkedHashMap<>();
            for (String key : required.keySet()) {
                JsonObject spec = parameters.has(key) && parameters.get(key).isJsonObject()
                        ? parameters.getAsJsonObject(key) : new JsonObject();
                maximums.put(key, RVP_FireSupportJson.number(spec, "max", 0, path + ".parameters." + key, problems));
            }
            return new BuiltInPattern(typeId, maximums);
        }
    }

    private record BuiltInPattern(ResourceLocation typeId, Map<String, Double> maximums) implements RVP_FireSupportPattern {
        private BuiltInPattern { maximums = Map.copyOf(maximums); }

        @Override
        public RVP_FireSupportImpactPoint resolve(Context context) {
            if (context.roundIndex() < 0 || context.totalRounds() <= 0 || context.roundIndex() >= context.totalRounds()) {
                throw new IllegalArgumentException("轮次必须位于任务总弹数范围内");
            }
            Random random = new Random(mix(context.seed(), context.roundIndex()));
            double heading = Math.toRadians(context.headingDegrees());
            if (POINT.equals(typeId)) {
                double radius = scaled(context, "radius_m");
                double r = radius * Math.sqrt(random.nextDouble());
                double angle = random.nextDouble() * Math.PI * 2.0;
                return new RVP_FireSupportImpactPoint(context.anchorX() + Math.sin(angle) * r,
                        context.anchorZ() + Math.cos(angle) * r);
            }
            double width = scaled(context, "width_m");
            double across = (random.nextDouble() - 0.5) * width;
            double along;
            if (LINE.equals(typeId)) {
                double length = scaled(context, "length_m");
                along = (random.nextDouble() - 0.5) * length;
            } else {
                double length = scaled(context, "length_m");
                double step = scaled(context, "step_m");
                int stepCount = Math.max(1, (int) Math.floor(length / step) + 1);
                int stepIndex = context.totalRounds() == 1 ? 0
                        : (int) Math.round((double) context.roundIndex() * (stepCount - 1) / (context.totalRounds() - 1));
                along = Math.min(length, stepIndex * step);
            }
            double dx = Math.sin(heading) * along + Math.cos(heading) * across;
            double dz = Math.cos(heading) * along - Math.sin(heading) * across;
            return new RVP_FireSupportImpactPoint(context.anchorX() + dx, context.anchorZ() + dz);
        }

        private double scaled(Context context, String key) {
            Double value = context.parameters().get(key);
            if (value == null || !Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("缺少有限正参数 " + key);
            Double maximum = maximums.get(key);
            if (maximum == null || !Double.isFinite(maximum) || maximum <= 0) throw new IllegalStateException("几何参数缺少已校验最大值 " + key);
            return Math.min(value * context.dispersionMultiplier(), maximum);
        }

        private static long mix(long seed, int round) {
            long value = seed ^ (0x9E3779B97F4A7C15L * (round + 1L));
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            return value ^ (value >>> 31);
        }
    }
}
