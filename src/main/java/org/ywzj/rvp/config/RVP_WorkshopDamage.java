package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.weapon.data.RVP_CollisionData;
import org.ywzj.rvp.weapon.data.RVP_DamageFactor;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionParentAction;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionPayloadKind;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpawner;
import org.ywzj.rvp.weapon.util.RVP_DamageDecayUtil;

import java.util.ArrayList;
import java.util.List;
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

    private RVP_WorkshopDamage() {}

    /** 原始数据用于识别缺项，已加载的 RVP 数据用于共用运行时规则。 */
    public record Projectile(JsonObject raw, RVP_WeaponData resolved) {}

    /**
     * 一组以相同规则结算的弹丸。直击、爆炸与半径为空时表示没有显式数值。
     * requiredHit 是必须未跳弹的上游阶段序号，-1 表示独立释放。
     * afterImpact 表示弹体在命中处或其后生成，表中免除距离衰减。
     */
    public record Stage(long count, int requiredHit, boolean afterImpact, Double direct, Double explosion, Double radius,
                        RVP_CollisionData collision, RVP_DamageFactor blastFactor) {
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
    }

    /** 依赖顺序排列，首位为实际发射的弹丸，其后为释放的子弹药。 */
    public record Shot(List<Stage> stages) {
        public boolean hasPower() {
            return stages.stream().anyMatch(Stage::hasDamage);
        }

        public boolean hasDirect() {
            return stages.stream().anyMatch(stage -> stage.direct() != null);
        }

        public double power(String target) {
            return stages.stream().mapToDouble(stage -> stage.count() * (stage.directFor(target) + stage.explosionFor(target))).sum();
        }

        /** 半径沿用一个数值，取能起爆的各段中最大的单次半径。 */
        public Double radius() {
            return stages.stream().map(Stage::radius).filter(java.util.Objects::nonNull).max(Double::compare).orElse(null);
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

        /** 表格只累加能命中的直击。全部直击跳弹或随上游跳弹被跳过时返回空。 */
        public Double impact(String target, int distance, int angle) {
            boolean vehicle = isVehicle(target);
            boolean[] hits = new boolean[stages.size()];
            Double total = null;
            for (int i = 0; i < stages.size(); i++) {
                Stage stage = stages.get(i);
                if (stage.requiredHit() >= 0 && !hits[stage.requiredHit()]) continue;
                var collision = stage.collision();
                if (vehicle && collision != null && collision.isBounceOnVehicle() && collision.getBounce() > 0
                        && angle >= collision.getBounceIncidenceAngle()) continue;
                hits[i] = true;
                if (stage.direct() == null) continue;

                // 与弹体共用衰减插值。对生物的直击没有入射角衰减。
                double hit = stage.count() * stage.directFor(target);
                if (collision != null) {
                    var rules = collision.getDamageDecayRules();
                    if (!stage.afterImpact()) hit *= RVP_DamageDecayUtil.distanceFactor(rules, distance);
                    if (vehicle) hit *= RVP_DamageDecayUtil.angleFactor(rules, angle);
                }
                total = total == null ? hit : total + hit;
            }
            return total;
        }
    }

    public static Shot read(ResourceLocation id, Projectile projectile, Function<ResourceLocation, Projectile> lookup) {
        // 复用实际发射的弹丸数。一次按键排队的后续射击各自消耗弹药。
        var fire = projectile.resolved() == null ? null : projectile.resolved().getFireData();
        long count = fire == null ? 1 : (long) fire.getCanisterCount() * fire.getCanisterBurstCount();
        List<Stage> stages = new ArrayList<>();
        collect(stages, lookup, id, projectile, new Spawn(count, 0, -1, false), null);
        return new Shot(List.copyOf(stages));
    }

    /** 当前弹体的数量、释放层数与命中依赖。 */
    private record Spawn(long count, int depth, int requiredHit, boolean afterImpact) {}

    private static void collect(List<Stage> stages, Function<ResourceLocation, Projectile> lookup, ResourceLocation id,
                                Projectile projectile, Spawn spawn, RVP_SubmunitionPayloadData payload) {
        RVP_WeaponData data = projectile.resolved();
        // 只列出通常能对目标产生伤害的释放路径。撞墙和被拦截是独立情况。
        List<RVP_SubmunitionReleaseData> releases = data == null || spawn.depth() >= RVP_SubmunitionSpawner.MAX_DEPTH
                || (payload != null && !payload.isAllowSubmunition()) ? List.of()
                : data.getSubmunitionData().getReleases().stream()
                        .filter(release -> release.resolveReleaseEvents() > 0 && isDamageRelease(release.getTriggers()))
                        .toList();
        // 命中释放并移除母弹时，运行时先结算母弹直击，随后跳过母弹碰炸。
        boolean skipBlast = (payload != null && payload.isSuppressExplosion())
                || releases.stream().anyMatch(release -> release.getParentAction() != RVP_EnumSubmunitionParentAction.CONTINUE);
        int index = stages.size();
        stages.add(stage(projectile, spawn, payload, skipBlast));

        for (var release : releases) {
            var triggers = release.getTriggers();
            boolean onHit = triggers.contains(ON_ENTITY_HIT) || triggers.contains(ON_IMPACT);
            int requiredHit = onHit ? index : spawn.requiredHit();
            long events = release.resolveReleaseEvents();
            if (release.getIntervalTick() > 0) {
                long perTick = release.getPerTick();
                events = !triggers.contains(IN_FLIGHT) || release.getParentAction() == RVP_EnumSubmunitionParentAction.DISCARD_ON_FIRST_SPAWN
                        ? perTick : (events + perTick - 1) / perTick * perTick;
            }
            for (var child : release.getPayloads()) {
                if (child.getKind() != RVP_EnumSubmunitionPayloadKind.RVP_WEAPON || child.getCount() <= 0) continue;
                // 由载荷处理命名空间、由武器索引解析来源，与实际生成子弹药相同。
                ResourceLocation childId = child.resolveWeaponId(id);
                Projectile childProjectile = childId == null ? null : lookup.apply(childId);
                if (childProjectile == null || childProjectile.raw() == null || childProjectile.resolved() == null) continue;
                collect(stages, lookup, childId, childProjectile,
                        new Spawn(spawn.count() * events * child.getCount(), spawn.depth() + 1, requiredHit,
                                spawn.afterImpact() || onHit), child);
            }
        }
    }

    private static boolean isDamageRelease(Set<RVP_EnumSubmunitionTrigger> triggers) {
        return triggers.contains(ON_ENTITY_HIT) || triggers.contains(ON_IMPACT)
                || triggers.contains(ON_FUSE) || triggers.contains(IN_FLIGHT);
    }

    private static Stage stage(Projectile projectile, Spawn spawn, RVP_SubmunitionPayloadData payload, boolean skipBlast) {
        JsonObject raw = projectile.raw();
        Double direct = number(object(raw, "collision_data"), "direct_damage");
        if (direct == null) direct = number(raw, "damage");
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
        boolean explodes = !skipBlast && (frag ? radius != null && radius > 0
                : flag != null && flag.isJsonPrimitive() && flag.getAsBoolean());
        RVP_WeaponData data = projectile.resolved();
        var blast = !explodes || data == null ? null : data.getExplosionData();
        return new Stage(spawn.count(), spawn.requiredHit(), spawn.afterImpact(), direct,
                explodes ? number(explosion, "damage") : null, explodes ? radius : null,
                data == null ? null : data.getCollisionData(), blast == null ? null : blast.getExplosionDamageFactor());
    }

    private static boolean isVehicle(String target) {
        return !PLAYER.equals(target) && !LIVING.equals(target);
    }

    private static double factor(RVP_DamageFactor factors, String target) {
        if (factors == null) return 1;
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
