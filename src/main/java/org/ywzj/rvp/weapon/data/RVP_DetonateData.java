package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Mth;
/**
 * 弹体落点效果：爆炸与自定义落点逻辑。JSON 键 {@code detonate_data}。
 *
 * <p>{@link #effectsBeforeExplosion} 控制火焰、药水云等自定义效果与 {@link #explosionData}
 * 的触发顺序。爆炸字段见 {@link RVP_Explosion}（继承本体 {@link org.ywzj.vehicle.vehicle.pojo.Explosion}）。</p>
 */
public class RVP_DetonateData {

    /**
     * 为 {@code true} 时先执行本分组中的自定义效果（{@code fire_data} 等），再触发 {@link #explosionData}；
     * 为 {@code false} 时先爆炸再自定义效果。默认 {@code true}（与历史行为一致）。
     */
    @SerializedName("effects_before_explosion")
    private boolean effectsBeforeExplosion = true;

    /** 爆炸参数（{@link RVP_Explosion}，继承本体 {@link org.ywzj.vehicle.vehicle.pojo.Explosion}）。 */
    @SerializedName("explosion_data")
    private RVP_Explosion explosionData;

    /** 点燃方块：范围、概率、是否灵魂火等。 */
    @SerializedName("fire_data")
    private FireEffectData fireData;

    /** 区域药水云（{@link net.minecraft.world.entity.AreaEffectCloud}）。 */
    @SerializedName("potion_cloud_data")
    private PotionCloudEffectData potionCloudData;

    /** 对范围内活体直接施加状态效果（不生成云）。 */
    @SerializedName("potion_effect_data")
    private PotionCloudEffectData potionEffectData;

    /** 在落点放置方块。 */
    @SerializedName("place_block_data")
    private PlaceBlockEffectData placeBlockData;

    /** 召唤闪电。 */
    @SerializedName("lightning_data")
    private LightningEffectData lightningData;

    /** 点燃范围内实体。 */
    @SerializedName("ignite_entity_data")
    private IgniteEntityEffectData igniteEntityData;

    /** 范围击退。 */
    @SerializedName("knockback_data")
    private KnockbackEffectData knockbackData;

    /** 清除范围内可替换植物（草、花、树叶等）。 */
    @SerializedName("clear_plants_data")
    private RadiusEffectData clearPlantsData;

    public boolean isEffectsBeforeExplosion() {
        return effectsBeforeExplosion;
    }

    public RVP_Explosion getExplosionData() {
        return explosionData;
    }

    public boolean hasExplosion() {
        return explosionData != null && explosionData.explode;
    }

    /** 是否配置了任意自定义落点效果（不含爆炸）。 */
    public boolean hasAnyEffect() {
        return hasFire() || hasPotionCloud() || hasPotionEffect() || hasPlaceBlock()
                || hasLightning() || hasIgniteEntity() || hasKnockback()
                || hasClearPlants();
    }

    public boolean hasFire() {
        return fireData != null && fireData.isActive();
    }

    public FireEffectData getFireData() {
        return fireData == null ? new FireEffectData() : fireData;
    }

    public boolean hasPotionCloud() {
        return potionCloudData != null && potionCloudData.isActive();
    }

    public PotionCloudEffectData getPotionCloudData() {
        return potionCloudData == null ? new PotionCloudEffectData() : potionCloudData;
    }

    public boolean hasPotionEffect() {
        return potionEffectData != null && potionEffectData.isActive();
    }

    public PotionCloudEffectData getPotionEffectData() {
        return potionEffectData == null ? new PotionCloudEffectData() : potionEffectData;
    }

    public boolean hasPlaceBlock() {
        return placeBlockData != null && placeBlockData.isActive();
    }

    public PlaceBlockEffectData getPlaceBlockData() {
        return placeBlockData == null ? new PlaceBlockEffectData() : placeBlockData;
    }

    public boolean hasLightning() {
        return lightningData != null && lightningData.isActive();
    }

    public LightningEffectData getLightningData() {
        return lightningData == null ? new LightningEffectData() : lightningData;
    }

    public boolean hasIgniteEntity() {
        return igniteEntityData != null && igniteEntityData.isActive();
    }

    public IgniteEntityEffectData getIgniteEntityData() {
        return igniteEntityData == null ? new IgniteEntityEffectData() : igniteEntityData;
    }

    public boolean hasKnockback() {
        return knockbackData != null && knockbackData.isActive();
    }

    public KnockbackEffectData getKnockbackData() {
        return knockbackData == null ? new KnockbackEffectData() : knockbackData;
    }

    public boolean hasClearPlants() {
        return clearPlantsData != null && clearPlantsData.isActive();
    }

    public RadiusEffectData getClearPlantsData() {
        return clearPlantsData == null ? new RadiusEffectData() : clearPlantsData;
    }

    public static class FireEffectData {
        /** 水平扩散半径（格）。0 = 仅在命中面相邻空气格点火。 */
        @SerializedName("radius")
        private int radius = 0;

        /** 每个候选格点火概率 0–1。 */
        @SerializedName("chance")
        private float chance = 1f;

        /** 使用灵魂火。 */
        @SerializedName("soul_fire")
        private boolean soulFire = false;

        /** 方块命中时生效。 */
        @SerializedName("on_block")
        private boolean onBlock = true;

        /** 实体命中时生效（在实体脚下扩散）。 */
        @SerializedName("on_entity")
        private boolean onEntity = true;

        public boolean isActive() {
            return chance > 0f && (onBlock || onEntity);
        }

        public int getRadius() {
            return Math.max(radius, 0);
        }

        public float getChance() {
            return Mth.clamp(chance, 0f, 1f);
        }

        public boolean isSoulFire() {
            return soulFire;
        }

        public boolean isOnBlock() {
            return onBlock;
        }

        public boolean isOnEntity() {
            return onEntity;
        }
    }

  /**
     * 药水云 / 直接上 debuff 共用字段；{@code cloud_duration_ticks > 0} 时生成云，否则仅
     * 使用直接施加逻辑。
     */
    public static class PotionCloudEffectData {
        /** 效果 ID，如 {@code minecraft:slowness} 或 {@code slowness}。 */
        @SerializedName("effect")
        private String effect = "";

        @SerializedName("amplifier")
        private int amplifier = 0;

        /** 施加给实体的效果时长（tick）。 */
        @SerializedName("duration_ticks")
        private int durationTicks = 100;

        /** 云存在时间（tick）；仅 {@code potion_cloud} 使用。 */
        @SerializedName("cloud_duration_ticks")
        private int cloudDurationTicks = 200;

        /** 云半径。 */
        @SerializedName("radius")
        private float radius = 3f;

        /** 云每秒收缩量（可为 0）。 */
        @SerializedName("radius_per_tick")
        private float radiusPerTick = 0f;

        /**
         * 目标过滤：{@code all}、{@code living}、{@code players}、{@code hostile}、
         * {@code non_allied}（排除发射者载具乘员与 owner）。
         */
        @SerializedName("targets")
        private String targets = "living";

        public boolean isActive() {
            return effect != null && !effect.isBlank();
        }

        public String getEffect() {
            return effect == null ? "" : effect.trim();
        }

        public int getAmplifier() {
            return Math.max(amplifier, 0);
        }

        public int getDurationTicks() {
            return Math.max(durationTicks, 1);
        }

        public int getCloudDurationTicks() {
            return Math.max(cloudDurationTicks, 1);
        }

        public float getRadius() {
            return Math.max(radius, 0.5f);
        }

        public float getRadiusPerTick() {
            return radiusPerTick;
        }

        public String getTargets() {
            return targets == null || targets.isBlank() ? "living" : targets.trim().toLowerCase();
        }
    }

    /** 在落点周围放置方块（骨粉、TNT 等）。 */
    public static class PlaceBlockEffectData {
        /** 方块 ID，如 {@code minecraft:tnt}。 */
        @SerializedName("block")
        private String block = "";

        /** 水平放置半径。 */
        @SerializedName("radius")
        private int radius = 0;

        @SerializedName("chance")
        private float chance = 1f;

        /**
         * {@code air_only}：仅空气；
         * {@code replaceable}：可被替换的方块（草、雪等）；
         * {@code always}：强制覆盖（慎用）。
         */
        @SerializedName("replace_mode")
        private String replaceMode = "air_only";

        public boolean isActive() {
            return block != null && !block.isBlank();
        }

        public String getBlock() {
            return block == null ? "" : block.trim();
        }

        public int getRadius() {
            return Math.max(radius, 0);
        }

        public float getChance() {
            return Mth.clamp(chance, 0f, 1f);
        }

        public String getReplaceMode() {
            return replaceMode == null ? "air_only" : replaceMode.trim().toLowerCase();
        }
    }

    /** 在落点召唤闪电。 */
    public static class LightningEffectData {
        /** 是否对击中的实体造成闪电伤害。 */
        @SerializedName("damage")
        private boolean damage = true;

        public boolean isActive() {
            return true;
        }

        public boolean dealsDamage() {
            return damage;
        }
    }

    public static class IgniteEntityEffectData extends RadiusEffectData {
        @SerializedName("seconds")
        private int seconds = 5;

        @Override
        public boolean isActive() {
            return seconds > 0 && super.isActive();
        }

        public int getSeconds() {
            return Math.max(seconds, 1);
        }
    }

    public static class KnockbackEffectData extends RadiusEffectData {
        @SerializedName("strength")
        private float strength = 1.2f;

        @Override
        public boolean isActive() {
            return strength > 0f && super.isActive();
        }

        public float getStrength() {
            return Math.max(strength, 0.05f);
        }
    }

    public static class RadiusEffectData {
        @SerializedName("radius")
        private float radius = 3f;

        @SerializedName("targets")
        private String targets = "living";

        public boolean isActive() {
            return radius > 0f;
        }

        public float getRadius() {
            return Math.max(radius, 0.5f);
        }

        public String getTargets() {
            return targets == null || targets.isBlank() ? "living" : targets.trim().toLowerCase();
        }
    }
}

