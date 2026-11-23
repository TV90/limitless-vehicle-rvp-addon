package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.custom.vehicle.RotaryWingVehicleDataPojo.RotaryWingAttributes;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 改装界面使用服务器同步的原始字段，区分“缺项”和显式零值。 */
public final class RVP_WorkshopData {
    /** 最近一次完整同步的快照，包括本体和附加包；换服及重载时整体替换。 */
    private static volatile RVP_WorkshopData current = new RVP_WorkshopData(Map.of(), Map.of());
    /** 载具面板摘要和同类车型每项机动指标的最大值。 */
    private final Map<ResourceLocation, Vehicle> vehicles = new HashMap<>();
    private final Map<String, Map<String, Double>> maxima = new HashMap<>();
    /** 服务端载具包实际使用的实体类型与装甲分类。 */
    private final Set<String> vehicleTypes = new TreeSet<>();
    /** 武器原文按选项读取，无需在进服时反序列化所有弹药。 */
    private final Map<ResourceLocation, String> weapons;

    private RVP_WorkshopData(Map<ResourceLocation, String> vehicleJson, Map<ResourceLocation, String> weaponJson) {
        weapons = Map.copyOf(weaponJson);
        vehicleJson.forEach((id, raw) -> {
            JsonObject data = json(raw);
            if (data == null) return;
            Vehicle vehicle = vehicle(data);
            vehicles.put(id, vehicle);
            if (!vehicle.type().isBlank()) vehicleTypes.add(vehicle.type());
            String armorType = text(data, "rvp_armor_type");
            if (!armorType.isBlank()) vehicleTypes.add(armorType);
            var maximum = maxima.computeIfAbsent(vehicle.type(), ignored -> new HashMap<>());
            vehicle.mobility().forEach((key, value) -> maximum.merge(key, Math.abs(value), Math::max));
        });
    }

    public static void replace(Map<ResourceLocation, String> vehicles, Map<ResourceLocation, String> weapons) {
        current = new RVP_WorkshopData(vehicles, weapons);
    }

    public static RVP_WorkshopData current() { return current; }
    public Vehicle vehicle(ResourceLocation id) { return vehicles.get(id); }
    public JsonObject weapon(ResourceLocation id) { return json(weapons.get(id)); }
    public Set<String> vehicleTypes() { return Collections.unmodifiableSet(vehicleTypes); }
    public double maximum(Vehicle vehicle, String key) {
        return maxima.getOrDefault(vehicle.type(), Map.of()).getOrDefault(key, 0.0);
    }

    private static JsonObject json(String raw) {
        if (raw == null) return null;
        try {
            JsonElement value = JsonParser.parseString(raw);
            return value.isJsonObject() ? value.getAsJsonObject() : null;
        } catch (com.google.gson.JsonParseException invalid) {
            return null;
        }
    }

    /** 读取原始对象，缺字段和 JSON null 均传递为空。 */
    public static JsonObject object(JsonObject data, String key) {
        JsonElement value = data == null ? null : data.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    public static Double number(JsonObject data, String key) {
        JsonElement value = data == null ? null : data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        double n = value.getAsDouble();
        return Double.isFinite(n) ? n : null;
    }

    public static String text(JsonObject data, String key) {
        JsonElement value = data == null ? null : data.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().trim() : "";
    }

    /** 包内耐久、装甲、乘员数据是否存在、两种语言的介绍，以及按显示顺序排列的机动项。 */
    public record Vehicle(String type, Double health, Double armor, boolean hasParts,
                          String description, String descriptionCN, Map<String, Double> mobility) {}

    public static Vehicle vehicle(JsonObject data) {
        var mobility = new LinkedHashMap<String, Double>();
        String type = text(data, "type");
        JsonObject attributes = object(data, "attributes");
        boolean fixedWing = type.endsWith(":fixed_wing_vehicle");
        boolean helicopter = type.endsWith(":rotary_wing_vehicle");
        Double turret = null;
        int turretRank = -1;
        JsonElement parts = data.get("parts");
        boolean hasParts = parts != null && parts.isJsonArray();
        if (hasParts) for (JsonElement entry : parts.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject part = entry.getAsJsonObject();
            if (!text(part, "type").endsWith(":weapon") || !part.has("weapons")) continue;
            Double yaw = number(object(part, "rot_info"), "y_rot_speed");
            // 从包内顺序取主武器站；优先可转动、有乘员和瞄具的站，避免选到并列或固定挂架。
            int rank = (yaw != null && yaw > 0 ? 4 : 0) + (part.has("seat_offset") ? 2 : 0)
                    + (part.has("optical_sight_offset") ? 1 : 0);
            if (yaw != null && rank > turretRank) {
                turret = yaw;
                turretRank = rank;
            }
        }
        if (fixedWing || helicopter) {
            JsonObject physics = object(data, "physics_info");
            // 固定翼的空速和两类航空器的爬升率都来自游戏运动方程；旋翼机另外有直接限速。
            RVP_WorkshopFlight flight = fixedWing ? RVP_WorkshopFlight.fixed(data, attributes, number(physics, "mass")) : null;
            Double airspeed = fixedWing ? flight.airspeed() : number(attributes, "max_air_speed");
            put(mobility, "airspeed", airspeed);
            Double ceiling = number(attributes, "ceiling");
            if (ceiling == null && helicopter && attributes != null)
                ceiling = (double) new RotaryWingAttributes().ceiling;
            put(mobility, "ceiling", ceiling);
            Double turn = number(attributes, "max_turn");
            put(mobility, "turn", turn != null ? turn : number(attributes, fixedWing ? "y_turn_rate" : "y_rot_speed_max"));
            put(mobility, "climb", fixedWing ? flight.climb() : RVP_WorkshopFlight.rotaryClimb(attributes, physics, airspeed));
        } else {
            put(mobility, "forward", number(attributes, "max_speed_forward"));
            put(mobility, "reverse", number(attributes, "max_speed_backward"));
            put(mobility, "turn", number(attributes, "max_turn"));
            put(mobility, "turret", turret);
        }
        return new Vehicle(type, number(data, "max_health"), number(data, "armor_min_damage"), hasParts,
                text(data, "description"), text(data, "description_CN"), Collections.unmodifiableMap(mobility));
    }

    private static void put(Map<String, Double> values, String key, Double value) {
        if (value != null) values.put(key, value);
    }

    /** 武器档案的制导、发射与装填数据。 */
    public record Weapon(boolean guided, String guidance, String terminalGuidance, String fireMode, Double caliber, Double rpm,
                         Double capacity, Double reloadSeconds) {}

    public static Weapon weapon(JsonObject data) {
        return weapon(data, null);
    }

    /** 候选武器已有实际的数据实例，档案与它共用默认开火模式和解析后的主、末段制导。 */
    public static Weapon weapon(JsonObject data, BaseVehicleWeaponData resolved) {
        String type = text(data, "type");
        JsonObject guidanceData = object(data, "guidance_data");
        String guidance = text(guidanceData, "guidance_type");
        String terminal = text(object(guidanceData, "terminal_guidance"), "guidance_type");
        String mode = text(object(data, "fire_data"), "fire_mode");
        if (guidance.isEmpty()) guidance = text(data, "guidance");
        if (guidance.equalsIgnoreCase("HOMING") && !text(data, "homing_mode").isEmpty())
            guidance = text(data, "homing_mode");
        if (resolved instanceof RVP_WeaponData rvp) {
            var active = rvp.getGuidanceData();
            guidance = active.getGuidanceType().name();
            var last = active.getTerminalGuidance();
            terminal = last == null ? "" : last.getGuidanceType().name();
            mode = rvp.getFireData().getFireMode().name();
        } else if (resolved instanceof VehicleMissileWeaponData missile) {
            guidance = missile.getGuidance() == VehicleMissileWeaponData.Guidance.HOMING
                    ? missile.getHomingMode().name() : missile.getGuidance().name();
        }
        if (terminal.equalsIgnoreCase("NONE") || terminal.equalsIgnoreCase(guidance)) terminal = "";
        boolean guided = (!guidance.isEmpty() && !guidance.equalsIgnoreCase("NONE"))
                || !terminal.isEmpty() || type.equals("ywzj_vehicle:missile");
        Double caliber = number(data, "caliber");
        if (caliber == null) caliber = number(object(data, "effects_data"), "caliber");
        Double interval = number(data, "shoot_interval");
        Double ticks = number(object(data, "reload"), "time");
        return new Weapon(guided, guidance.equalsIgnoreCase("NONE") ? "" : guidance, terminal, mode, caliber,
                interval != null && interval > 0 ? 60000.0 / interval : null, number(data, "max_capacity"),
                ticks == null ? null : ticks / 20.0);
    }

    /** 用户指定的五档装甲；大于最后一档时没有可显示的等级。 */
    public static String armorGrade(Double armor) {
        if (armor == null || armor > 30) return "";
        if (armor <= 8) return "very_low";
        if (armor <= 12) return "low";
        if (armor <= 15) return "medium";
        if (armor <= 20) return "high";
        return "very_high";
    }
}
