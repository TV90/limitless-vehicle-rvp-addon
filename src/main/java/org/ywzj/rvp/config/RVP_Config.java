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

    /** Parsed cache: sorted by maxRadius ascending. */
    private volatile List<CraterRule> parsedRules = List.of();

    public RVP_Config(ForgeConfigSpec.Builder builder) {
        builder.push("explosion");

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
    public static int getMaxDepthForRadius(float radius) {
        RVP_Config cfg = INSTANCE;
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
