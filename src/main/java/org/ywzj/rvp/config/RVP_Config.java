package org.ywzj.rvp.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class RVP_Config {

    private static RVP_Config INSTANCE;

    private final ForgeConfigSpec.ConfigValue<List<? extends String>> craterDepthRules;

    private final ForgeConfigSpec.BooleanValue forceImmediateExplosionDestruction;

    private final ForgeConfigSpec.DoubleValue eraMaxIncidenceAngle;

    private final ForgeConfigSpec.DoubleValue hitlTvBreakLockTargetSpeedKph;

    /** Parsed cache: sorted by maxRadius ascending. */
    private volatile List<CraterRule> parsedRules = List.of();

    public RVP_Config(ForgeConfigSpec.Builder builder) {
        builder.push("explosion");

        forceImmediateExplosionDestruction = builder
                .comment(
                        "Force RVP projectile explosions to always use the vanilla-style immediate",
                        "destruction path (GridCollectionTask + destroyBlocksImmediately), even when",
                        "the radius exceeds the base mod's 32-block threshold that would normally",
                        "switch to the batched nuclear path (SphericalCollectionTask: split-tick",
                        "scorch-transforming block replacement + sustained multi-tick server load).",
                        "true (default): RVP projectile explosions never enter the batched path;",
                        "false: vanilla threshold behavior.",
                        "Only affects explosions triggered by RVP projectile triggerExplosion."
                )
                .define("forceImmediateExplosionDestruction", true);

        eraMaxIncidenceAngle = builder
                .comment(
                        "Incidence angle upper bound for direct hits on bones with active ERA:",
                        "when the hit bone has ERA configured (and not yet destroyed), the impact",
                        "angle used by the damage_decay 'angle' rules is clamped to",
                        "min(actualAngle, this value) - simulating ERA's equivalent protection",
                        "against steep impacts. 90 disables the clamp (vanilla angle decay).",
                        "Only affects direct-hit angle decay; ricochet judgment keeps the raw angle.",
                        "Default: 35"
                )
                .defineInRange("eraMaxIncidenceAngle", 35.0D, 0.0D, 90.0D);

        builder.push("guidance");

        // HITL 电视制导速度脱锁反制（2026-09-21 用户需求）：被锁定实体速度超过该阈值（km/h）
        // 时立即脱锁，锁定位置冻结为最后一次持续跟踪的坐标；操作手可重新锁定，但再锁超速目标
        // 仍会立刻脱锁。0 = 关闭该反制（维持旧行为：可无限跟踪任意速度目标）。
        hitlTvBreakLockTargetSpeedKph = builder
                .comment(
                        "HITL TV guidance break-lock countermeasure: when the locked entity's speed",
                        "exceeds this threshold (km/h), the missile breaks lock immediately and its",
                        "aim point freezes at the last continuously tracked position. The operator",
                        "can keep controlling and re-lock, but re-locking a target above the speed",
                        "breaks lock again right away.",
                        "0 = disable (old behavior: track targets at any speed).",
                        "Default: 72 (equals 1 block/tick)"
                )
                .defineInRange("hitlTvBreakLockTargetSpeedKph", 72.0D, 0.0D, 10000.0D);

        builder.pop();

        craterDepthRules = builder
                .comment(
                        "Crater depth limits: how many layers below the explosion center blocks can be destroyed.",
                        "Each entry format: \"maxRadius:maxDepth\"",
                        "maxRadius = explosion radius threshold (blocks),",
                        "maxDepth  = max block layers below center Y that can be broken (0 = surface only).",
                        "Entries are sorted by maxRadius automatically on load.",
                        "Examples:",
                        "  [\"5:0\", \"15:1\", \"35:2\", \"65:3\", \"100:4\", \"9999:5\"]",
                        "  Empty list = no limit (vanilla behavior)."
                )
                .defineListAllowEmpty(
                        Collections.singletonList("craterDepthRules"),
                        () -> List.of("5:0", "15:1", "35:2", "65:3", "100:4", "9999:5"),
                        o -> o instanceof String s && s.matches("\\d+:\\d+")
                );

        builder.pop();
    }

    private CraterRule toRule(String entry) {
        String[] parts = entry.split(":");
        return new CraterRule(
                Float.parseFloat(parts[0]),
                Integer.parseInt(parts[1])
        );
    }

    private synchronized void ensureParsed() {
        if (!parsedRules.isEmpty()) {
            return;
        }
        List<String> raw = new ArrayList<>(craterDepthRules.get());
        List<CraterRule> rules = new ArrayList<>(raw.size());
        for (String s : raw) {
            rules.add(toRule(s));
        }
        rules.sort(Comparator.comparingDouble(CraterRule::maxRadius));
        parsedRules = List.copyOf(rules);
    }

    /** Call this after config reload to flush cache. */
    public static synchronized void invalidateCache() {
        if (INSTANCE != null) {
            INSTANCE.parsedRules = List.of();
        }
    }

    /**
     * Returns max depth (layers below center Y) allowed for the given explosion radius.
     * Returns -1 if no limit applies (no rules configured).
     */
    public static int getMaxDepthForRadius(float radius) {        RVP_Config cfg = INSTANCE;
        if (cfg == null) {
            return -1;
        }
        cfg.ensureParsed();
        List<CraterRule> rules = cfg.parsedRules;
        if (rules.isEmpty()) {
            return -1;
        }
        for (CraterRule rule : rules) {
            if (radius <= rule.maxRadius()) {
                return rule.maxDepth();
            }
        }
        // If radius exceeds the last rule's threshold, return the last rule's depth
        return rules.get(rules.size() - 1).maxDepth();
    }

    /**
     * RVP 弹体爆炸是否强制走即时破坏路径（true = 半径 &gt; 32 也不进本体核爆炸批量路径）。
     * 高频调用（mixin 常量修改处每条射线判定），直接读缓存的 ConfigValue。
     */
    public static boolean isForceImmediateExplosionDestruction() {
        RVP_Config cfg = INSTANCE;
        return cfg != null && cfg.forceImmediateExplosionDestruction.get();
    }

    /**
     * 激活 ERA 骨骼直击的入射角上界（度）：伤害衰减角度取 min(实际入射角, 该值)。
     * 高频调用（每枚命中弹一次），直接读缓存的 ConfigValue。
     */
    public static float getEraMaxIncidenceAngle() {
        RVP_Config cfg = INSTANCE;
        return cfg != null ? cfg.eraMaxIncidenceAngle.get().floatValue() : 35.0F;
    }

    /**
     * HITL 电视制导速度脱锁阈值（km/h）：被锁实体速度超过该值即脱锁并冻结跟踪点。
     * 返回 0 表示关闭该反制。速度换算：格/tick × 72 = km/h（72 kph = 1 格/tick）。
     */
    public static float getHitlTvBreakLockTargetSpeedKph() {
        RVP_Config cfg = INSTANCE;
        return cfg != null ? cfg.hitlTvBreakLockTargetSpeedKph.get().floatValue() : 72.0F;
    }

    /** Register the server config. Must be called from mod constructor. */
    public static void register(ModLoadingContext context) {
        Pair<RVP_Config, ForgeConfigSpec> specPair =
                new ForgeConfigSpec.Builder().configure(RVP_Config::new);
        INSTANCE = specPair.getLeft();
        context.registerConfig(ModConfig.Type.SERVER, specPair.getRight(), "ywzj_rvp-server.toml");
    }

    private record CraterRule(float maxRadius, int maxDepth) {
    }
}
