package org.ywzj.rvp.guidance;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Rules for simultaneous multi-phase / multi-source guidance composition.
 *
 * <p>See {@code docs/plan/导弹分段复合制导实现.md} § 复合制导兼容性。</p>
 */
public final class RVP_GuidanceCompositeCompatibility {

    private static final Set<RVP_EnumGuidanceType> MANUAL_TYPES = EnumSet.of(
            RVP_EnumGuidanceType.MCLOS
    );

    private static final Set<RVP_EnumGuidanceType> AUTONOMOUS_SEEKERS = EnumSet.of(
            RVP_EnumGuidanceType.IR,
            RVP_EnumGuidanceType.ARH,
            RVP_EnumGuidanceType.SARH,
            RVP_EnumGuidanceType.ARM,
            RVP_EnumGuidanceType.GPS,
            RVP_EnumGuidanceType.SACLOS
    );

    private RVP_GuidanceCompositeCompatibility() {}

    public static boolean canComposite(RVP_EnumGuidanceType a, RVP_EnumGuidanceType b) {
        if (a == b) {
            return true;
        }
        if (a == RVP_EnumGuidanceType.NONE || b == RVP_EnumGuidanceType.NONE) {
            return true;
        }
        if (a == RVP_EnumGuidanceType.IOG || b == RVP_EnumGuidanceType.IOG) {
            return true;
        }
        if (MANUAL_TYPES.contains(a) || MANUAL_TYPES.contains(b)) {
            return false;
        }
        if (a == RVP_EnumGuidanceType.ARM || b == RVP_EnumGuidanceType.ARM) {
            return false;
        }
        if (pair(a, b, RVP_EnumGuidanceType.SARH, RVP_EnumGuidanceType.ARH)) {
            return false;
        }
        if (pair(a, b, RVP_EnumGuidanceType.SARH, RVP_EnumGuidanceType.IR)) {
            return false;
        }
        if (pair(a, b, RVP_EnumGuidanceType.GPS, RVP_EnumGuidanceType.SACLOS)) {
            return false;
        }
        if (AUTONOMOUS_SEEKERS.contains(a) && AUTONOMOUS_SEEKERS.contains(b)) {
            return true;
        }
        return (a == RVP_EnumGuidanceType.GPS && b == RVP_EnumGuidanceType.IR)
                || (a == RVP_EnumGuidanceType.IR && b == RVP_EnumGuidanceType.GPS)
                || (a == RVP_EnumGuidanceType.ARH && b == RVP_EnumGuidanceType.IR)
                || (a == RVP_EnumGuidanceType.IR && b == RVP_EnumGuidanceType.ARH);
    }

    public static boolean areAllCompatible(List<RVP_EnumGuidanceType> types) {
        List<RVP_EnumGuidanceType> active = types.stream()
                .filter(t -> t != RVP_EnumGuidanceType.NONE && t != RVP_EnumGuidanceType.IOG)
                .distinct()
                .toList();
        for (int i = 0; i < active.size(); i++) {
            for (int j = i + 1; j < active.size(); j++) {
                if (!canComposite(active.get(i), active.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Greedy resolve: keep highest-specificity phases whose primary types are mutually compatible.
     */
    public static <T> List<T> resolveCompatible(
            List<T> phases,
            java.util.function.ToIntFunction<T> specificity,
            java.util.function.Function<T, RVP_EnumGuidanceType> primaryType
    ) {
        List<T> sorted = new ArrayList<>(phases);
        sorted.sort((a, b) -> Integer.compare(specificity.applyAsInt(b), specificity.applyAsInt(a)));
        List<T> kept = new ArrayList<>();
        List<RVP_EnumGuidanceType> keptTypes = new ArrayList<>();
        for (T phase : sorted) {
            RVP_EnumGuidanceType type = primaryType.apply(phase);
            boolean ok = true;
            for (RVP_EnumGuidanceType existing : keptTypes) {
                if (!canComposite(existing, type)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                kept.add(phase);
                keptTypes.add(type);
            }
        }
        return kept.isEmpty() ? List.of(sorted.get(0)) : kept;
    }

    private static boolean pair(RVP_EnumGuidanceType a, RVP_EnumGuidanceType b,
                              RVP_EnumGuidanceType x, RVP_EnumGuidanceType y) {
        return (a == x && b == y) || (a == y && b == x);
    }
}
