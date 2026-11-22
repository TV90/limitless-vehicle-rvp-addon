package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_CollisionData;
import org.ywzj.rvp.weapon.data.RVP_DamageDecayRuleData;
import org.ywzj.rvp.weapon.data.RVP_DamageFactor;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionParentAction;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionPayloadKind;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpawner;
import org.ywzj.rvp.weapon.util.RVP_DamageDecayUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static org.ywzj.rvp.config.RVP_WorkshopData.number;
import static org.ywzj.rvp.config.RVP_WorkshopData.object;
import static org.ywzj.rvp.config.RVP_WorkshopData.text;
import static org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger.IN_FLIGHT;
import static org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger.ON_ENTITY_HIT;
import static org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger.ON_FUSE;
import static org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger.ON_IMPACT;

/** 载具改装面板的一发弹药，计算数据面板所需的伤害与衰减。 */
public final class RVP_WorkshopDamage {
    /** 下拉框的通用目标。载具的具体装甲类别使用实体类型 ID。 */
    public static final String DEFAULT_VEHICLE = "vehicle_default";
    public static final String PLAYER = "player";
    public static final String LIVING = "living";
    public static final String BLOCK = "block";

    private RVP_WorkshopDamage() {}

    /** 原始数据用于识别缺项，已加载的 RVP 数据用于共用运行时规则。 */
    public record Projectile(JsonObject raw, RVP_WeaponData resolved) {}

    /** 面板允许切换的结算情形，与游戏的碰撞、引信及水下爆心路径对应。 */
    public enum Context {
        IMPACT, BLOCK_HIT, PENETRATE, AIRBURST, DELAY, DISTANCE, BOUNCE_DELAY, INTERCEPT,
        GROUND_PROXIMITY, PROXIMITY, NORMAL_BLAST, UNDERWATER;

        private boolean impact() {
            return this == IMPACT || this == NORMAL_BLAST || this == UNDERWATER;
        }

        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /** 来源名称由释放条件和层级生成。母弹行只有伤害类型。 */
    public enum Source {
        ROOT, FLIGHT_HEAD, AMMUNITION, NESTED, FOLLOWUP, PROXIMITY;

        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /** threshold 是此情形的秒数或距离；未配置门槛时为空。 */
    public record Scenario(Context context, Shot shot, Double threshold) {}

    /**
     * 一组以相同规则结算的弹丸。直击、爆炸与半径为空时表示没有显式数值。
     * requiredHit 是必须未跳弹的上游阶段序号，-1 表示独立释放。
     * afterImpact 表示弹体在命中处或其后生成，表中免除距离衰减。
     */
    public record Stage(BigInteger count, int requiredHit, boolean afterImpact, Source source,
                        Double direct, Double explosion, Double radius, RVP_CollisionData collision,
                        RVP_DamageFactor blastFactor, boolean fixedBlastDamage, Double blockRadius) {
        public boolean hasDamage() {
            return direct != null || explosion != null;
        }

        public double directFor(String target) {
            return direct == null ? 0 : direct * factor(collision == null ? null : collision.getDirectDamageFactor(), target);
        }

        public double explosionFor(String target) {
            // 武器侧爆炸倍率在载具伤害处理器内生效，生物取爆炸原值。
            return explosion == null ? 0 : explosion * (isVehicle(target) ? factor(blastFactor, target) : 1);
        }

        /** 近炸触发伤害沿用直击倍率；表格仅列碰撞直击。 */
        private boolean hasImpact() { return direct != null && source != Source.PROXIMITY; }
    }

    /** 依赖顺序排列，首位为实际发射的弹丸，其后为释放的子弹药。 */
    public record Shot(List<Stage> stages, List<String> effects, boolean airOnly) {
        public Shot(List<Stage> stages, List<String> effects) {
            this(stages, effects, false);
        }

        public boolean hasPower() {
            return stages.stream().anyMatch(Stage::hasDamage);
        }

        public boolean hasDirect() {
            return stages.stream().anyMatch(Stage::hasImpact);
        }

        public boolean hasTableDamage() {
            return !airOnly && (hasDirect() || stages.stream().anyMatch(stage -> stage.explosion() != null));
        }

        public double power(String target) {
            return stages.stream().mapToDouble(stage ->
                    stage.count().doubleValue() * (stage.directFor(target) + stage.explosionFor(target))).sum();
        }

        /** 半径沿用一个数值，取能起爆的各段中最大的单次半径。 */
        public Double radius() {
            return stages.stream().map(Stage::radius).filter(Objects::nonNull).max(Double::compare).orElse(null);
        }

        public Double radius(String target) {
            return BLOCK.equals(target) ? stages.stream().map(Stage::blockRadius).filter(Objects::nonNull)
                    .max(Double::compare).orElse(null) : radius();
        }

        /** 包含弹药单独定义的装甲类型，服务器没有同类可操控载具时也可比较。 */
        public Set<String> vehicleTypes() {
            Set<String> types = new TreeSet<>();
            for (Stage stage : stages) {
                if (stage.collision() != null) types.addAll(stage.collision().getDirectDamageFactor().getVehicles().keySet());
                if (stage.blastFactor() != null) types.addAll(stage.blastFactor().getVehicles().keySet());
            }
            return types;
        }

        /** 累加当前情形下有效的直击与爆炸。原本判为跳弹的格子不渲染数值。 */
        public Double impact(String target, int distance, int angle) {
            boolean vehicle = isVehicle(target);
            boolean[] hits = new boolean[stages.size()];
            boolean directHit = false;
            Double total = null;
            for (int i = 0; i < stages.size(); i++) {
                Stage stage = stages.get(i);
                if (stage.requiredHit() >= 0 && !hits[stage.requiredHit()]) continue;
                var collision = stage.collision();
                if (vehicle && stage.source() != Source.PROXIMITY && collision != null
                        && collision.isBounceOnVehicle() && collision.getBounce() > 0
                        && angle >= collision.getBounceIncidenceAngle()) continue;
                hits[i] = true;
                boolean direct = stage.hasImpact();
                if (!direct && stage.explosion() == null) continue;

                List<RVP_DamageDecayRuleData> rules = collision == null ? List.of() : collision.getDamageDecayRules();
                double rangeFactor = stage.afterImpact() ? 1 : RVP_DamageDecayUtil.distanceFactor(rules, distance);
                double count = stage.count().doubleValue();
                // 命中爆炸使用距离衰减，水下独立威力覆盖衰减后的爆炸值。
                double value = count * stage.explosionFor(target) * (stage.fixedBlastDamage() ? 1 : rangeFactor);
                if (direct) {
                    directHit = true;
                    double hit = count * stage.directFor(target) * rangeFactor;
                    if (vehicle) hit *= RVP_DamageDecayUtil.angleFactor(rules, angle);
                    value += hit;
                }
                total = total == null ? value : total + value;
            }
            return directHit || !hasDirect() ? total : null;
        }
    }

    public static List<Scenario> read(ResourceLocation id, Projectile projectile, Function<ResourceLocation, Projectile> lookup) {
        return read(id, projectile, lookup, false);
    }

    /** 方块分支按当前目标请求，独立处理撞墙、穿墙及方块破坏半径。 */
    public static List<Scenario> read(ResourceLocation id, Projectile projectile, Function<ResourceLocation, Projectile> lookup,
                                      boolean block) {
        var data = projectile.resolved();
        var blast = data == null ? null : data.getExplosionData();
        boolean underwater = blast != null && blast.explode
                && (blast.getDamageInWater() != null || blast.getRadiusInWater() != null);
        var contexts = new ArrayList<>(List.of(block ? Context.BLOCK_HIT : underwater ? Context.NORMAL_BLAST : Context.IMPACT));
        Double timer = null, distance = null, bounce = null;
        if (data != null) {
            var fuse = data.getFuseData();
            var releases = data.getSubmunitionData().getReleases();
            if (block && data.getWallPenetration() > 0 && releases.stream().anyMatch(r -> r.getTriggers().contains(RVP_EnumSubmunitionTrigger.ON_PENETRATE)))
                contexts.add(Context.PENETRATE);
            if (fuse.isProgrammableAirburst()) contexts.add(Context.AIRBURST);
            // 寿命超时与发射定时共用一项，显示先到的有效门槛。
            if (fuse.isDetonateOnLifeEnd()) timer = Math.max(0, data.getLife()) / 20.0;
            if (data.getDelayFuse() > 0 && data.getDelayFuse() <= lifetime(data)) {
                double delay = data.getDelayFuse() / 20.0;
                timer = timer == null ? delay : Math.min(timer, delay);
            }
            if (timer != null) contexts.add(Context.DELAY);
            double limit = data.getProjectileData().resolveSelfDestructDistance(data.getWeaponKind());
            if (limit > 0 && data.getProjectileData().isSelfDestructExplode()) {
                distance = limit;
                contexts.add(Context.DISTANCE);
            }
            if (data.getBounce() > 0 && data.getBounceFuseTick() > 0) {
                bounce = data.getBounceFuseTick() / 20.0;
                contexts.add(Context.BOUNCE_DELAY);
            }
            if (releases.stream().anyMatch(r -> r.getTriggers().contains(RVP_EnumSubmunitionTrigger.ON_APS_INTERCEPT)))
                contexts.add(Context.INTERCEPT);
            long available = flightWindow(data);
            if (fuse.getGroundProximityFuseDistance() > 0) {
                // 近地引信在延时引信和飞行释放之后检查，还需要等方块碰撞安全期结束。
                long discard = data.getSubmunitionData().getReleases().stream().filter(release -> release.resolveReleaseEvents() > 0)
                        .mapToLong(RVP_WorkshopDamage::discardTick)
                        .min().orElse(Long.MAX_VALUE);
                int arm = Math.max(fuse.getGroundProximityFuseArmTick(), data.getProjectileData().getBlockCollisionSafeTick());
                if (Math.min(available, discard - 1) >= Math.max(1, arm)) contexts.add(Context.GROUND_PROXIMITY);
            }
            boolean near = data.getProximityFuseDist() > 0 && available > Math.max(0, fuse.getProximityFuseTick());
            // 智能攻顶仅改变已启用攻顶引信的制导行为，默认值本身不会开启近炸。
            boolean topAttack = fuse.isTopAttackFuseEnabled() && fuse.getTopAttackFuseDistance() > 0
                    && available > fuse.getTopAttackFuseArmTick();
            if (!block && (near || topAttack))
                contexts.add(Context.PROXIMITY);
        }
        if (underwater) contexts.add(Context.UNDERWATER);

        // 多个情形共用同一份已加载弹药数据。显示内容相同的情形只保留一个。
        var cached = new HashMap<ResourceLocation, Projectile>();
        var seen = new LinkedHashSet<Shot>();
        var scenarios = new ArrayList<Scenario>();
        boolean airOnly = isAirOnlyMissile(data);
        for (Context context : contexts) {
            Shot shot = readShot(id, projectile, childId -> {
                if (!cached.containsKey(childId)) cached.put(childId, lookup.apply(childId));
                return cached.get(childId);
            }, context, airOnly, block);
            Double threshold = switch (context) {
                case DELAY -> timer;
                case DISTANCE -> distance;
                case BOUNCE_DELAY -> bounce;
                default -> null;
            };
            boolean distinct = seen.add(shot);
            if (context == Context.BLOCK_HIT || (shot.hasPower() || shot.radius() != null || !shot.effects().isEmpty())
                    && (distinct || threshold != null || context == Context.INTERCEPT || context == Context.PENETRATE))
                scenarios.add(new Scenario(context, shot, threshold));
        }
        return List.copyOf(scenarios);
    }

    /** 使用已加载弹药的目标离地高度、制导阶段和引信配置判断表格是否适用。 */
    public static boolean isAirOnlyMissile(RVP_WeaponData data) {
        if (data == null || data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE) return false;
        var guidance = data.getGuidanceData();
        var launchAltitude = guidance.getLockAltitudeRange();
        var mainAltitude = guidance.getGuidanceAltitudeRange();
        var terminal = guidance.getTerminalGuidance();
        var terminalAltitude = terminal == null ? null : terminal.getGuidanceAltitudeRange();
        var fuse = data.getFuseData();
        // 显式地面包线及近地、攻顶引信是保留表格的依据。
        if (allowsSurfaceTargets(launchAltitude) || allowsSurfaceTargets(mainAltitude)
                || allowsSurfaceTargets(terminalAltitude)
                || fuse.getGroundProximityFuseDistance() > 0 || fuse.isTopAttackFuseEnabled()) return false;

        if (terminal != null && switch (terminal.getGuidanceType()) {
            case IR, AIR, ARH, SARH -> false;
            default -> true;
        }) return false;

        boolean airProximityFuse = !fuse.isProximityFuseAntiTorpedo()
                && fuse.getProximityFuseHeight() > 0 && data.getProximityFuseDist() > 0;
        // 复用发射阶段锁定判定，避免把仅在末段启用雷达的对地、反舰弹算成对空弹。
        if (data.launchPhaseRequiresSeekerLock())
            return launchAltitude != null || mainAltitude != null || terminalAltitude != null || airProximityFuse;

        // 指令制导弹需要末段明确限制目标离地高度，近炸引信本身不足以确定用途。
        return guidance.getGuidanceType() == RVP_EnumGuidanceType.SACLOS && terminalAltitude != null && airProximityFuse;
    }

    private static boolean allowsSurfaceTargets(RVP_Range<Float> altitude) {
        if (altitude == null) return false;
        Float minimum = altitude.intervals().get(0).lower();
        return minimum == null || minimum <= 0;
    }

    private static Shot readShot(ResourceLocation id, Projectile projectile, Function<ResourceLocation, Projectile> lookup,
                                 Context context, boolean airOnly, boolean block) {
        // 复用实际发射的弹丸数。一次按键排队的后续射击各自消耗弹药。
        var fire = projectile.resolved() == null ? null : projectile.resolved().getFireData();
        long count = fire == null ? 1 : (long) fire.getCanisterCount() * fire.getCanisterBurstCount();
        List<Stage> stages = new ArrayList<>();
        Set<String> effects = new LinkedHashSet<>();
        collect(stages, effects, lookup, id, projectile, new Spawn(BigInteger.valueOf(count), 0, -1, false, Source.ROOT),
                null, context, block);
        return new Shot(List.copyOf(stages), List.copyOf(effects), airOnly);
    }

    /** 当前弹体的数量、释放层数与命中依赖。 */
    private record Spawn(BigInteger count, int depth, int requiredHit, boolean afterImpact, Source source) {}

    /** 每波在选定情形中的飞行释放次数和剩余的结束事件次数。 */
    private record Wave(RVP_SubmunitionReleaseData rule, long flight, long terminal) {}

    private static void collect(List<Stage> stages, Set<String> effects, Function<ResourceLocation, Projectile> lookup,
                                ResourceLocation id, Projectile projectile, Spawn spawn, RVP_SubmunitionPayloadData payload,
                                Context context, boolean block) {
        RVP_WeaponData data = projectile.resolved();
        List<RVP_SubmunitionReleaseData> releases = data == null || spawn.depth() >= RVP_SubmunitionSpawner.MAX_DEPTH
                || (payload != null && !payload.isAllowSubmunition()) ? List.of()
                : data.getSubmunitionData().getReleases().stream()
                        .filter(release -> release.resolveReleaseEvents() > 0)
                        .toList();

        // 延时引信在飞行释放前执行。需要目标或测距的引信以可用飞行窗口预览。
        long flightLimit = data == null ? 0 : flightWindow(data);
        long discard = releases.stream().mapToLong(RVP_WorkshopDamage::discardTick).min().orElse(Long.MAX_VALUE);
        boolean flightDiscard = discard <= flightLimit;
        // 这些引信可在飞行布撒移除母弹之前触发，保留该情形的最后一个可用飞行刻。
        if (flightDiscard && (context == Context.AIRBURST || context == Context.PROXIMITY || context == Context.GROUND_PROXIMITY)) {
            flightLimit = discard - 1;
            flightDiscard = false;
        } else if (flightDiscard) {
            flightLimit = discard;
        }

        List<Wave> waves = new ArrayList<>();
        boolean terminalDiscard = false;
        // 方块专用波次若会移除母弹，运行时短路后续通用撞击触发。
        boolean blockHit = context == Context.BLOCK_HIT || block && context == Context.UNDERWATER;
        long beforeHit = flightLimit;
        boolean blockDiscards = blockHit && releases.stream().anyMatch(r ->
                r.getTriggers().contains(RVP_EnumSubmunitionTrigger.ON_BLOCK_HIT)
                        && r.getParentAction() != RVP_EnumSubmunitionParentAction.CONTINUE
                        && flightReleases(r, beforeHit) < r.resolveReleaseEvents());
        for (var release : releases) {
            long flight = flightReleases(release, flightLimit);
            long remaining = release.resolveReleaseEvents() - flight;
            var triggers = release.getTriggers();
            boolean matches = blockHit ? triggers.contains(RVP_EnumSubmunitionTrigger.ON_BLOCK_HIT)
                    || !blockDiscards && triggers.contains(ON_IMPACT) : switch (context) {
                case PENETRATE -> triggers.contains(RVP_EnumSubmunitionTrigger.ON_PENETRATE);
                case INTERCEPT -> triggers.contains(RVP_EnumSubmunitionTrigger.ON_APS_INTERCEPT);
                case IMPACT, NORMAL_BLAST, UNDERWATER -> triggers.contains(ON_ENTITY_HIT) || triggers.contains(ON_IMPACT);
                default -> triggers.contains(ON_FUSE);
            };
            long terminal = !flightDiscard && matches && remaining > 0
                    ? release.getIntervalTick() > 0 ? release.getPerTick() : remaining : 0;
            waves.add(new Wave(release, flight, terminal));
            terminalDiscard |= terminal > 0 && release.getParentAction() != RVP_EnumSubmunitionParentAction.CONTINUE;
        }

        // 实体命中先结算直击，其后才触发释放。引信路径在近炸和范围伤害前先判断是否移除弹体。
        boolean skipBlast = flightDiscard || context == Context.PENETRATE || terminalDiscard && context != Context.INTERCEPT
                || (payload != null && payload.isSuppressExplosion());
        int index = stages.size();
        stages.add(stage(projectile, spawn, payload, context, flightDiscard, terminalDiscard, skipBlast, blockHit));
        if (data != null && !flightDiscard && context != Context.PENETRATE && (!terminalDiscard || context == Context.INTERCEPT)) {
            var detonation = data.getDetonateData();
            if (detonation.hasIgniteEntity()) effects.add("ignite");
            if (detonation.hasPotionCloud()) effects.add("potion_cloud");
            if (detonation.hasLightning()) effects.add("lightning");
        }
        for (var wave : waves)
            if (wave.flight() > 0) collectPayloads(stages, effects, lookup, id, spawn, wave.rule(), wave.flight(), -1, Source.FLIGHT_HEAD);
        for (var wave : waves)
            if (wave.terminal() > 0) {
                boolean hit = context.impact() || context == Context.BLOCK_HIT || context == Context.PENETRATE;
                collectPayloads(stages, effects, lookup, id, spawn, wave.rule(), wave.terminal(),
                        hit ? index : -1, hit ? Source.FOLLOWUP : Source.AMMUNITION);
            }
    }

    /** 与飞行释放器一致，首次在延迟后的下一刻释放，间歇最后一批仍按整批结算。 */
    private static long flightReleases(RVP_SubmunitionReleaseData rule, long limit) {
        long start = rule.getDelayTick() + 1L;
        if (!rule.getTriggers().contains(IN_FLIGHT) || start > limit) return 0;
        if (rule.getIntervalTick() == 0) return rule.resolveReleaseEvents();
        long batches = Math.min(batches(rule), 1 + (limit - start) / (rule.getIntervalTick() + 1L));
        return batches * rule.getPerTick();
    }

    private static long batches(RVP_SubmunitionReleaseData rule) {
        return (rule.resolveReleaseEvents() + (long) rule.getPerTick() - 1) / rule.getPerTick();
    }

    private static long discardTick(RVP_SubmunitionReleaseData rule) {
        if (!rule.getTriggers().contains(IN_FLIGHT) || rule.getParentAction() == RVP_EnumSubmunitionParentAction.CONTINUE)
            return Long.MAX_VALUE;
        long first = rule.getDelayTick() + 1L;
        if (rule.getParentAction() == RVP_EnumSubmunitionParentAction.DISCARD_ON_FIRST_SPAWN || rule.getIntervalTick() == 0)
            return first;
        return first + (batches(rule) - 1) * (rule.getIntervalTick() + 1L);
    }

    private static long lifetime(RVP_WeaponData data) { return Math.max(1, data.getLife() + 1L); }

    private static long flightWindow(RVP_WeaponData data) {
        return data.getDelayFuse() > 0 ? Math.min(lifetime(data), data.getDelayFuse() - 1L) : lifetime(data);
    }

    private static void collectPayloads(List<Stage> stages, Set<String> effects, Function<ResourceLocation, Projectile> lookup,
                                        ResourceLocation id, Spawn parent, RVP_SubmunitionReleaseData wave, long events,
                                        int requiredHit, Source source) {
        for (var child : wave.getPayloads()) {
            if (child.getKind() != RVP_EnumSubmunitionPayloadKind.RVP_WEAPON || child.getCount() <= 0) continue;
            // 使用实际子弹药的资源 ID 解析规则。嵌套数量保持整数精度，避免溢出为负。
            ResourceLocation childId = child.resolveWeaponId(id);
            Projectile projectile = childId == null ? null : lookup.apply(childId);
            if (projectile == null || projectile.raw() == null || projectile.resolved() == null) continue;
            BigInteger count = parent.count().multiply(BigInteger.valueOf(events)).multiply(BigInteger.valueOf(child.getCount()));
            Source label = parent.depth() == 0 ? source : Source.NESTED;
            collect(stages, effects, lookup, childId, projectile,
                    new Spawn(count, parent.depth() + 1, requiredHit >= 0 ? requiredHit : parent.requiredHit(),
                            parent.afterImpact() || requiredHit >= 0, label), child, Context.IMPACT, false);
        }
    }

    private static Stage stage(Projectile projectile, Spawn spawn, RVP_SubmunitionPayloadData payload, Context context,
                               boolean flightDiscard, boolean terminalDiscard, boolean skipBlast, boolean blockHit) {
        JsonObject raw = projectile.raw();
        RVP_WeaponData data = projectile.resolved();
        Double direct = null;
        if (context.impact() && !blockHit && !flightDiscard) {
            direct = number(object(raw, "collision_data"), "direct_damage");
            if (direct == null) direct = number(raw, "damage");
        } else if (context == Context.PROXIMITY && !flightDiscard && !terminalDiscard) {
            // 触发目标已单独受伤。区域爆炸会排除该目标，半径和范围伤害不纳入本行。
            float guaranteed = data.getProximityFuseDirectDamage();
            if (guaranteed <= 0) guaranteed = data.resolveProximityFuseExplosionDamage();
            if (guaranteed <= 0 && data.getExplosionData() != null) guaranteed = data.getExplosionData().damage;
            if (Float.isFinite(guaranteed) && guaranteed > 0) direct = (double) guaranteed;
        }
        // 复用子弹药生成时只缩放直击的覆写，爆炸使用子弹药自己的配置。
        if (direct != null && payload != null && payload.getDamageMultiplier() != null) {
            direct = (double) Math.max(0.01f, direct.floatValue() * payload.getDamageMultiplier());
        }

        JsonObject explosion = object(object(raw, "detonate_data"), "explosion_data");
        if (explosion == null) explosion = object(raw, "explosion");
        Double radius = number(explosion, "radius");
        JsonElement flag = explosion == null ? null : explosion.get("explode");
        // 本体破片榴弹按半径引爆，其余弹药要求启用爆炸。
        boolean frag = text(raw, "type").equals("ywzj_vehicle:grenade") && text(raw, "grenade").equalsIgnoreCase("frag");
        boolean explodes = context != Context.PROXIMITY && !skipBlast && (frag ? radius != null && radius > 0
                : flag != null && flag.isJsonPrimitive() && flag.getAsBoolean());
        var blast = !explodes || data == null ? null : data.getExplosionData();
        Double explosionDamage = explodes ? number(explosion, "damage") : null;
        boolean fixedBlastDamage = blast != null && context == Context.UNDERWATER && blast.getDamageInWater() != null;
        if (blast != null && context == Context.AIRBURST) {
            if (data.getFuseData().hasAirburstExplosionDamageOverride()) explosionDamage = finite(data.resolveAirburstExplosionDamage());
            if (data.getFuseData().hasAirburstExplosionRadiusOverride()) radius = finite(data.resolveAirburstExplosionRadius());
        } else if (blast != null && context == Context.UNDERWATER) {
            if (fixedBlastDamage) explosionDamage = finite(blast.getDamageInWater());
            if (blast.getRadiusInWater() != null) radius = finite(blast.getRadiusInWater());
        }
        Source source = context == Context.PROXIMITY && direct != null ? Source.PROXIMITY : spawn.source();
        if (source == Source.FLIGHT_HEAD && direct == null && explosionDamage != null) source = Source.AMMUNITION;
        Double blockRadius = null;
        if (explodes) {
            boolean destroy = blast != null ? blast.destroyBlock
                    : explosion.has("destroy_block") && explosion.get("destroy_block").getAsBoolean();
            blockRadius = destroy ? number(explosion, "destroy_radius") : Double.valueOf(0);
            if (blockRadius == null || blockRadius < 0) blockRadius = radius;
        }
        return new Stage(spawn.count(), spawn.requiredHit(), spawn.afterImpact(), source, direct,
                explosionDamage, explodes ? radius : null,
                data != null && (context.impact() || source == Source.PROXIMITY)
                        && object(raw, "collision_data") != null ? data.getCollisionData() : null,
                blast == null ? null : blast.getExplosionDamageFactor(), fixedBlastDamage, blockRadius);
    }

    private static Double finite(float value) { return Float.isFinite(value) ? (double) value : null; }

    private static boolean isVehicle(String target) {
        return !PLAYER.equals(target) && !LIVING.equals(target) && !BLOCK.equals(target);
    }

    private static double factor(RVP_DamageFactor factors, String target) {
        if (factors == null || BLOCK.equals(target)) return 1;
        // 使用弹体的实体分类及载具默认倍率，和运行时一样过滤无效倍率。
        Float override = factors.getVehicles().get(target);
        float value = switch (target) {
            case PLAYER -> factors.getPlayer();
            case LIVING -> factors.getLiving();
            case DEFAULT_VEHICLE -> factors.getVehicleDefault();
            default -> override == null ? factors.getVehicleDefault() : override;
        };
        return Float.isFinite(value) ? Math.max(0, value) : 1;
    }
}
