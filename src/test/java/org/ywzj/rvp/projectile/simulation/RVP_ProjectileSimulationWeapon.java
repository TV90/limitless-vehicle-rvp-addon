package org.ywzj.rvp.projectile.simulation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** 只读权威武器包；保留生产数据类的字段默认值，不创建 Forge 世界或弹药物品。 */
public record RVP_ProjectileSimulationWeapon(
        /** 武器资源标识，仅用于查找文件与显示。 */ String id,
        /** 当前武器公开类型。 */ RVP_EnumWeaponKind kind,
        /** 中文优先的显示名。 */ String name,
        /** 实际输入文件的 SHA-256，便于核对是否重新计算过。 */ String sha256,
        /** 原始配置快照；只参与导出，不修改源文件。 */ JsonObject source,
        /** 供生产数学代码使用的武器数据。 */ RVP_WeaponData data) {
    /** 全部具有实体运动的公开类型；激光和吊舱不产生弹体。 */
    private static final Set<String> TYPES = Set.of("missile", "rocket", "machinegun", "bomb", "torpedo", "dispenser");

    /** 限定在指定 weapons 根目录内，禁止读取备份目录、路径跳转和目录外符号链接。 */
    public static RVP_ProjectileSimulationWeapon read(Path weapons, String id) throws IOException {
        if (id == null || !id.matches("rvp:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("weapon 必须是 rvp:武器文件名（不含 .json）");
        }
        String relative = id.substring(4);
        if (relative.startsWith("/") || relative.endsWith("/")
                || java.util.Arrays.stream(relative.split("/", -1))
                .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith("_"))) {
            throw new IllegalArgumentException("weapon 不能包含跳转或备份目录：" + id);
        }
        Path root = weapons.toRealPath();
        Path file = root.resolve(relative + ".json").normalize().toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("武器文件超出 weapons 目录：" + id);
        }
        byte[] bytes = Files.readAllBytes(file);
        String content = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
        JsonObject source = JsonParser.parseString(content).getAsJsonObject();
        // 调用本项目只读解析入口，将文件与来源校验摘要一同交给测试进程。
        return parse(id, source, digest(bytes));
    }

    /** 同一个解析入口供命令行和数值回归使用，不增加旧键别名。 */
    static RVP_ProjectileSimulationWeapon parse(String id, JsonObject source, String sha256) {
        String type = source.has("type") ? source.get("type").getAsString() : "";
        if (!type.startsWith("rvp:") || !TYPES.contains(type.substring(4))) {
            throw new IllegalArgumentException("不是具有实体运动的武器类型：" + type);
        }
        RVP_EnumWeaponKind kind = RVP_EnumWeaponKind.valueOf(type.substring(4).toUpperCase(Locale.ROOT));
        // 调用最小构造环境，弹体与武器数据均走真实构造器，禁止手抄物理默认值。
        RVP_ProjectileSimulationBootstrap.initialize();
        JsonObject flightInput = source.deepCopy();
        // reload 的物品注册不参与飞行；保留原始快照，只在解析副本中省略未运行的装填配置。
        flightInput.remove("reload");
        // 调用本体 Gson 和本项目字段适配器，完整读取气动、风漂、部署、水中和制导参数。
        RVP_WeaponData data = GsonUtil.GSON.fromJson(flightInput, RVP_WeaponData.class);
        data.getProjectileData().resolvePropulsionFallback(source, group(source, "projectile_data"));
        data.setRootCaliberConfigured(source.has("caliber") && !source.get("caliber").isJsonNull());
        // 调用生产武器类型入口，使口径/发射速度等 getter 获得正确的类型语义。
        data.setWeaponKind(kind);
        data.setWeaponId(ResourceLocation.tryParse(id));
        String name = source.has("name_CN") ? source.get("name_CN").getAsString()
                : source.has("name") ? source.get("name").getAsString() : id;
        if (name.isBlank()) name = source.has("name") ? source.get("name").getAsString() : id;
        if (name.isBlank()) name = id;
        if (!Float.isFinite(data.resolveMuzzleSpeed(kind)) || data.getLife() < 0) {
            throw new IllegalArgumentException("武器初速必须有限，life 不能为负：" + id);
        }
        return new RVP_ProjectileSimulationWeapon(id, kind, name, sha256, source.deepCopy(), data);
    }

    /** 未提供的分组采用生产数据类自己的默认值；显式坏分组直接拒绝。 */
    private static JsonObject group(JsonObject source, String key) {
        if (!source.has(key)) return new JsonObject();
        if (!source.get(key).isJsonObject()) throw new IllegalArgumentException(key + " 必须是对象");
        return source.getAsJsonObject(key);
    }

    /** 摘要不包含时间、绝对路径或其他会影响重复运行的机器信息。 */
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
