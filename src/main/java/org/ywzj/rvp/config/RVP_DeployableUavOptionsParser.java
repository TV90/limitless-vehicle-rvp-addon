package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 解析 deployable_uav_options 唯一当前 schema，并继承母车级公共策略。 */
public final class RVP_DeployableUavOptionsParser {
    /** 已校验的候选列表和稳定默认项。 */
    public record Parsed(List<RVP_DeployableUavCatalog.Option> options, String defaultOptionId) {}
    private RVP_DeployableUavOptionsParser() {}

    /** 拒绝重复ID、非法载具ID、非有限坐标/速度，并将非法默认项回退到首个合法项。 */
    public static Parsed parse(JsonObject parent) {
        if (!GsonHelper.getAsBoolean(parent, "deployable_uav_enabled", false)
                || !parent.has("deployable_uav_options") || !parent.get("deployable_uav_options").isJsonArray()) {
            return new Parsed(List.of(), "");
        }
        List<RVP_DeployableUavCatalog.Option> options = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (JsonElement element : parent.getAsJsonArray("deployable_uav_options")) {
            RVP_DeployableUavCatalog.Option option = parseOption(parent, element, ids);
            if (option != null) {
                options.add(option);
                ids.add(option.id());
            }
        }
        String requested = GsonHelper.getAsString(parent, "deployable_uav_default_option", "");
        String chosen = ids.contains(requested) ? requested : options.isEmpty() ? "" : options.get(0).id();
        return new Parsed(List.copyOf(options), chosen);
    }

    /** 单独隔离每项解析异常，非法候选跳过但不丢弃同车其它合法项。 */
    @Nullable private static RVP_DeployableUavCatalog.Option parseOption(JsonObject parent,
                                                                          @Nullable JsonElement element,
                                                                          List<String> seenIds) {
        try {
            if (element == null || !element.isJsonObject()) return null;
            JsonObject option = element.getAsJsonObject();
            String id = GsonHelper.getAsString(option, "id", "").trim();
            ResourceLocation vehicleId = ResourceLocation.tryParse(GsonHelper.getAsString(option, "vehicle_id", ""));
            if (id.isEmpty() || id.length() > 128 || seenIds.contains(id) || vehicleId == null
                    || vehicleId.getPath().isBlank()) return null;
            Vec3 offset = parseOffset(option.get("spawn_offset"));
            if (offset == null) return null;
            // null 与未填写都表示不赋予初速度；地面雷达车的当前 schema 允许显式留空。
            // 保留 GsonHelper 对非数字类型的校验，不把其它格式错误悄悄当成零。
            JsonElement speedElement = option.get("initial_speed");
            double speed = speedElement == null || speedElement.isJsonNull()
                    ? 0.0 : GsonHelper.getAsDouble(option, "initial_speed");
            if (!Double.isFinite(speed) || speed < 0.0 || speed > Float.MAX_VALUE) return null;
            double signalRange = GsonHelper.getAsDouble(option, "signal_range",
                    GsonHelper.getAsDouble(parent, "deployable_uav_signal_range", RVP_DeployableUavConfig.DEFAULT_SIGNAL_RANGE));
            if (!Double.isFinite(signalRange)) return null;
            String role = nonBlank(GsonHelper.getAsString(option, "role", "uav"), "uav");
            String yaw = nonBlank(GsonHelper.getAsString(option, "spawn_yaw_mode", "parent"), "parent").toLowerCase();
            if (!yaw.equals("parent") && !yaw.equals("operator_look")) yaw = "parent";
            boolean allowSwitch = GsonHelper.getAsBoolean(option, "allow_control_switch",
                    GsonHelper.getAsBoolean(parent, "deployable_uav_allow_control_switch", true));
            boolean autoLink = GsonHelper.getAsBoolean(option, "auto_link_datalink",
                    GsonHelper.getAsBoolean(parent, "deployable_uav_auto_link_datalink", true));
            int cooldown = Math.max(0, GsonHelper.getAsInt(option, "redeploy_cooldown_tick",
                    GsonHelper.getAsInt(parent, "deployable_uav_redeploy_cooldown_tick", 0)));
            boolean autoLoiter = GsonHelper.getAsBoolean(option, "auto_loiter_on_switch_back",
                    GsonHelper.getAsBoolean(parent, "deployable_uav_auto_loiter_on_switch_back", true));
            List<Integer> seats = option.has("allowed_seat_indexes")
                    ? parseSeats(option.get("allowed_seat_indexes"))
                    : parseSeats(parent.get("deployable_uav_allowed_seat_indexes"));
            RVP_DeployableUavConfig config = new RVP_DeployableUavConfig(true, vehicleId, role, offset, yaw,
                    true, allowSwitch, autoLink, cooldown, autoLoiter, (float) speed, signalRange, seats);
            String name = nonBlank(GsonHelper.getAsString(option, "name", id), id);
            return new RVP_DeployableUavCatalog.Option(id, name, vehicleId, role, offset, yaw,
                    (float) speed, config);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** 解析 object 或三元 array 偏移；非有限值和形状错误会拒绝候选。 */
    @Nullable private static Vec3 parseOffset(@Nullable JsonElement element) {
        if (element == null) return Vec3.ZERO;
        try {
            double x; double y; double z;
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                x = GsonHelper.getAsDouble(obj, "x", 0.0);
                y = GsonHelper.getAsDouble(obj, "y", 0.0);
                z = GsonHelper.getAsDouble(obj, "z", 0.0);
            } else if (element.isJsonArray() && element.getAsJsonArray().size() == 3) {
                x = element.getAsJsonArray().get(0).getAsDouble();
                y = element.getAsJsonArray().get(1).getAsDouble();
                z = element.getAsJsonArray().get(2).getAsDouble();
            } else return null;
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) ? new Vec3(x, y, z) : null;
        } catch (RuntimeException invalid) { return null; }
    }

    /** 解析候选或母车级允许座位，空列表表示仅驾驶位。 */
    private static List<Integer> parseSeats(@Nullable JsonElement element) {
        List<Integer> seats = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement seat : element.getAsJsonArray()) {
                if (seat != null && seat.isJsonPrimitive() && seat.getAsJsonPrimitive().isNumber()) {
                    int index = seat.getAsInt();
                    if (index >= 0 && !seats.contains(index)) seats.add(index);
                }
            }
        }
        return List.copyOf(seats);
    }

    /** 空白字符串回退到确定默认值，避免候选记录持有无效 UI/部署值。 */
    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}