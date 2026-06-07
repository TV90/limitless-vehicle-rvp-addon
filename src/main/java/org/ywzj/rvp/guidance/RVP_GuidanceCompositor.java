package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Combines multiple guidance source intents according to composite mode.
 */
public final class RVP_GuidanceCompositor {

    private RVP_GuidanceCompositor() {}

    public record StageSource(RVP_GuidanceContext context, RVP_GuidanceData.Source source, double weight) {}

    /**
     * Blends guidance from multiple simultaneously active stages (overlapping activation windows).
     */
    public static boolean applyOverlappingStages(RVP_BaseBullet projectile, List<StageSource> stageSources) {
        if (stageSources.isEmpty()) {
            return false;
        }
        if (stageSources.size() == 1) {
            StageSource only = stageSources.get(0);
            return applyStage(only.context(), List.of(only.source()));
        }

        Vec3 origin = projectile.position();
        Vec3 blended = Vec3.ZERO;
        double totalWeight = 0.0;
        RVP_GuidanceEffectiveConfig lastConfig = stageSources.get(0).context().effective();
        RVP_EnumGuidanceType lastType = RVP_EnumGuidanceType.NONE;

        for (StageSource stageSource : stageSources) {
            RVP_GuidanceData.Source source = stageSource.source();
            if (source.getType() == RVP_EnumGuidanceType.NONE
                    || source.getCompositeMode() == RVP_EnumCompositeMode.DISABLED) {
                continue;
            }
            RVP_GuidanceContext ctx = stageSource.context();
            if (!isSourceSupported(projectile, source.getType())) {
                continue;
            }
            RVP_EnumCounterDecision counterDecision = RVP_GuidanceCountermeasures.apply(
                    projectile, source.getType(), ctx, source);
            if (counterDecision != RVP_EnumCounterDecision.CLEAR) {
                continue;
            }
            RVP_GuidanceIntent intent = evaluateIntent(ctx, source);
            Vec3 dir = intent.directionFrom(origin);
            if (!intent.success() || dir == null) {
                continue;
            }
            double weight = stageSource.weight();
            blended = blended.add(dir.scale(weight));
            totalWeight += weight;
            lastConfig = ctx.effective();
            lastType = intent.sourceType();
        }

        if (totalWeight <= 1.0E-6) {
            StageSource fallback = stageSources.stream()
                    .filter(ss -> ss.source().getType() == RVP_EnumGuidanceType.IOG)
                    .findFirst()
                    .orElse(stageSources.get(0));
            return applyStage(fallback.context(), List.of(fallback.source()));
        }

        Vec3 target = origin.add(blended.normalize().scale(256.0));
        projectile.setActiveSourceType(lastType);
        return RVP_GuidanceMath.guidanceToPos(projectile, target, lastConfig);
    }

    public static boolean applyStage(
            RVP_GuidanceContext baseContext,
            List<RVP_GuidanceData.Source> sources
    ) {
        if (sources.isEmpty()) {
            return false;
        }

        boolean hasBlend = sources.stream().anyMatch(s -> s.getCompositeMode() == RVP_EnumCompositeMode.BLEND);
        if (hasBlend) {
            return applyBlend(baseContext, sources);
        }

        boolean hasOverlay = sources.stream().anyMatch(s -> s.getCompositeMode() == RVP_EnumCompositeMode.OVERLAY);
        if (hasOverlay) {
            return applyOverlay(baseContext, sources);
        }

        boolean hasRace = sources.stream().anyMatch(s -> s.getCompositeMode() == RVP_EnumCompositeMode.RACE);
        if (hasRace) {
            return applyRace(baseContext, sources);
        }

        return applyPrimary(baseContext, sources);
    }

    private static boolean applyPrimary(RVP_GuidanceContext baseContext, List<RVP_GuidanceData.Source> sources) {
        List<RVP_GuidanceData.Source> sorted = sources.stream()
                .filter(s -> s.getCompositeMode() != RVP_EnumCompositeMode.DISABLED)
                .sorted(Comparator.comparingInt(RVP_GuidanceData.Source::getPriority).reversed())
                .toList();
        for (RVP_GuidanceData.Source source : sorted) {
            if (source.getType() == RVP_EnumGuidanceType.NONE) {
                return false;
            }
            RVP_GuidanceContext ctx = baseContext.withSource(source);
            if (!isSourceSupported(ctx.projectile(), source.getType())) {
                continue;
            }
            RVP_EnumCounterDecision counterDecision = RVP_GuidanceCountermeasures.apply(ctx.projectile(), source.getType(), ctx, source);
            if (counterDecision == RVP_EnumCounterDecision.DISCARD || counterDecision == RVP_EnumCounterDecision.STOP_STAGE) {
                return false;
            }
            if (counterDecision == RVP_EnumCounterDecision.SKIP_SOURCE) {
                continue;
            }
            RVP_GuidanceIntent intent = evaluateIntent(ctx, source);
            if (intent.success() && RVP_GuidanceMath.applyIntent(ctx.projectile(), intent, ctx.effective())) {
                ctx.projectile().setActiveSourceType(intent.sourceType());
                return true;
            }
        }
        return false;
    }

    private static boolean applyBlend(RVP_GuidanceContext baseContext, List<RVP_GuidanceData.Source> sources) {
        RVP_BaseBullet projectile = baseContext.projectile();
        Vec3 origin = projectile.position();
        Vec3 blended = Vec3.ZERO;
        double totalWeight = 0.0;
        RVP_GuidanceEffectiveConfig lastConfig = baseContext.effective();
        RVP_EnumGuidanceType lastType = RVP_EnumGuidanceType.NONE;

        for (RVP_GuidanceData.Source source : sources) {
            if (source.getCompositeMode() == RVP_EnumCompositeMode.DISABLED) {
                continue;
            }
            RVP_GuidanceContext ctx = baseContext.withSource(source);
            if (!isSourceSupported(projectile, source.getType())) {
                continue;
            }
            RVP_EnumCounterDecision counterDecision = RVP_GuidanceCountermeasures.apply(projectile, source.getType(), ctx, source);
            if (counterDecision != RVP_EnumCounterDecision.CLEAR) {
                continue;
            }
            RVP_GuidanceIntent intent = evaluateIntent(ctx, source);
            Vec3 dir = intent.directionFrom(origin);
            if (!intent.success() || dir == null) {
                continue;
            }
            double weight = source.getWeight();
            blended = blended.add(dir.scale(weight));
            totalWeight += weight;
            lastConfig = ctx.effective();
            lastType = intent.sourceType();
        }

        if (totalWeight <= 1.0E-6) {
            return false;
        }
        Vec3 target = origin.add(blended.normalize().scale(256.0));
        projectile.setActiveSourceType(lastType);
        return RVP_GuidanceMath.guidanceToPos(projectile, target, lastConfig);
    }

    private static boolean applyOverlay(RVP_GuidanceContext baseContext, List<RVP_GuidanceData.Source> sources) {
        List<RVP_GuidanceData.Source> sorted = sources.stream()
                .filter(s -> s.getCompositeMode() != RVP_EnumCompositeMode.DISABLED)
                .sorted(Comparator.comparingInt(RVP_GuidanceData.Source::getPriority).reversed())
                .toList();
        if (sorted.isEmpty()) {
            return false;
        }

        RVP_BaseBullet projectile = baseContext.projectile();
        Vec3 origin = projectile.position();
        RVP_GuidanceIntent primary = null;
        RVP_GuidanceEffectiveConfig primaryConfig = baseContext.effective();
        List<Vec3> corrections = new ArrayList<>();

        for (RVP_GuidanceData.Source source : sorted) {
            RVP_GuidanceContext ctx = baseContext.withSource(source);
            if (!isSourceSupported(projectile, source.getType())) {
                continue;
            }
            RVP_EnumCounterDecision counterDecision = RVP_GuidanceCountermeasures.apply(projectile, source.getType(), ctx, source);
            if (counterDecision != RVP_EnumCounterDecision.CLEAR) {
                continue;
            }
            RVP_GuidanceIntent intent = evaluateIntent(ctx, source);
            if (!intent.success()) {
                continue;
            }
            if (primary == null) {
                primary = intent;
                primaryConfig = ctx.effective();
            } else {
                Vec3 dir = intent.directionFrom(origin);
                if (dir != null) {
                    corrections.add(dir.scale(source.getWeight()));
                }
            }
        }

        if (primary == null) {
            return false;
        }
        Vec3 baseDir = primary.directionFrom(origin);
        if (baseDir == null) {
            return false;
        }
        Vec3 combined = baseDir;
        for (Vec3 correction : corrections) {
            combined = combined.add(correction);
        }
        Vec3 target = origin.add(combined.normalize().scale(256.0));
        projectile.setActiveSourceType(primary.sourceType());
        return RVP_GuidanceMath.guidanceToPos(projectile, target, primaryConfig);
    }

    private static boolean applyRace(RVP_GuidanceContext baseContext, List<RVP_GuidanceData.Source> sources) {
        RVP_BaseBullet projectile = baseContext.projectile();
        Vec3 look = projectile.getLookAngle();
        RVP_GuidanceIntent best = null;
        RVP_GuidanceEffectiveConfig bestConfig = baseContext.effective();
        double bestAngle = Double.MAX_VALUE;

        for (RVP_GuidanceData.Source source : sources) {
            if (source.getCompositeMode() == RVP_EnumCompositeMode.DISABLED) {
                continue;
            }
            RVP_GuidanceContext ctx = baseContext.withSource(source);
            if (!isSourceSupported(projectile, source.getType())) {
                continue;
            }
            RVP_EnumCounterDecision counterDecision = RVP_GuidanceCountermeasures.apply(projectile, source.getType(), ctx, source);
            if (counterDecision != RVP_EnumCounterDecision.CLEAR) {
                continue;
            }
            RVP_GuidanceIntent intent = evaluateIntent(ctx, source);
            Vec3 dir = intent.directionFrom(projectile.position());
            if (!intent.success() || dir == null) {
                continue;
            }
            double angle = RVP_GuidanceSeekerUtil.angleBetween(look, dir);
            if (angle < bestAngle) {
                bestAngle = angle;
                best = intent;
                bestConfig = ctx.effective();
            }
        }

        if (best == null) {
            return false;
        }
        projectile.setActiveSourceType(best.sourceType());
        return RVP_GuidanceMath.applyIntent(projectile, best, bestConfig);
    }

    private static RVP_GuidanceIntent evaluateIntent(RVP_GuidanceContext ctx, RVP_GuidanceData.Source source) {
        RVP_BaseBullet projectile = ctx.projectile();
        int rigidity = ctx.effective().steering().getRigidityTime();
        if (projectile.tickCount <= rigidity) {
            return RVP_GuidanceIntent.failed(source.getType());
        }
        return RVP_GuidanceSourceRegistry.get(source.getType()).evaluate(ctx, source);
    }

    private static boolean isSourceSupported(RVP_BaseBullet projectile, RVP_EnumGuidanceType type) {
        return switch (type) {
            case ARH -> projectile instanceof org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
            default -> true;
        };
    }
}
