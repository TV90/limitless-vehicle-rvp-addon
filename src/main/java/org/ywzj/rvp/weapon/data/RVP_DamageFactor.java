package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 按目标类别缩放 RVP 武器直击、激光与近炸直伤（不作用于 {@link org.ywzj.vehicle.util.VehicleExplosion} 波及伤害）。
 *
 * <p>与 MCH {@code DamageFactor} 不同：载具倍率按 Forge 实体类型 ID 配置（如
 * {@code ywzj_vehicle:rotary_wing_vehicle}），不再写死直升机/坦克等枚举。</p>
 */
public class RVP_DamageFactor {

    public static final RVP_DamageFactor DEFAULT = new RVP_DamageFactor();

    /** 对玩家倍率，默认 1。 */
    @SerializedName("player")
    private float player = 1f;

    /** 对非玩家生物实体倍率，默认 1。 */
    @SerializedName("living")
    private float living = 1f;

    /**
     * 对 {@link AbstractVehicle} 且未在 {@link #vehicles} 中单独配置时的默认倍率。
     */
    @SerializedName("vehicle_default")
    private float vehicleDefault = 1f;

    /**
     * 按实体类型 ID 的载具倍率。键为 {@code namespace:path}，如
     * {@code ywzj_vehicle:rotary_wing_vehicle}。
     */
    @SerializedName("vehicles")
    private Map<String, Float> vehicles = new HashMap<>();

    public float getPlayer() {
        return player;
    }

    public float getLiving() {
        return living;
    }

    public float getVehicleDefault() {
        return vehicleDefault;
    }

    public Map<String, Float> getVehicles() {
        if (vehicles == null || vehicles.isEmpty()) {
            return Collections.emptyMap();
        }
        return vehicles;
    }

    /** 是否配置了非默认倍率（用于跳过无意义的乘法）。 */
    public boolean isConfigured() {
        if (player != 1f || living != 1f || vehicleDefault != 1f) {
            return true;
        }
        return vehicles != null && !vehicles.isEmpty();
    }

    /**
     * 返回对 {@code entity} 的伤害系数；未知实体为 1。
     */
    public float getFactor(Entity entity) {
        if (entity == null) {
            return 1f;
        }
        if (entity instanceof Player) {
            return sanitize(player);
        }
        if (entity instanceof AbstractVehicle) {
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (typeId != null) {
                Float mapped = getVehicles().get(typeId.toString());
                if (mapped != null) {
                    return sanitize(mapped);
                }
            }
            return sanitize(vehicleDefault);
        }
        if (entity instanceof LivingEntity) {
            return sanitize(living);
        }
        return 1f;
    }

    private static float sanitize(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 1f;
        }
        return Math.max(0f, value);
    }
}
