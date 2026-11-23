package org.ywzj.rvp.resource;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.resource.PackMeta;
import org.ywzj.vehicle.util.GetJarResources;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Installs the bundled {@code /rvp} vehicle pack into {@code limitless_vehicle/rvp}
 * before {@link org.ywzj.vehicle.resource.VehiclePackLoader} scans packs.
 */
public final class RVP_VehiclePackInstaller {

    private static final Logger LOGGER = LogManager.getLogger(RVP_MOD.MOD_ID);
    private static final String PACK_RESOURCE_ROOT = "/rvp";
    private static final String PACK_FOLDER_NAME = "rvp";
    private static final String PACK_UPDATES_RESOURCE = PACK_RESOURCE_ROOT + "/vehicle_pack_updates.json";

    private record PackUpdate(String path, String previousSha256) {}
    private static final Path VEHICLE_PACKS_PATH = FMLPaths.GAMEDIR.get().resolve("limitless_vehicle");
    private static final Path VEHICLE_PACKS_BACKUP_PATH = FMLPaths.GAMEDIR.get().resolve("limitless_vehicle_backup");

    private RVP_VehiclePackInstaller() {}

    public static void ensureInstalled() {
        Path targetPath = VEHICLE_PACKS_PATH.resolve(PACK_FOLDER_NAME);
        try {
            if (!Files.isDirectory(VEHICLE_PACKS_PATH)) {
                Files.createDirectories(VEHICLE_PACKS_PATH);
            }
            if (!Files.isDirectory(VEHICLE_PACKS_BACKUP_PATH)) {
                Files.createDirectories(VEHICLE_PACKS_BACKUP_PATH);
            }
            if (shouldSkipInstall(targetPath)) {
                if (applyPackUpdates(targetPath)) {
                    org.ywzj.vehicle.resource.VehiclePackLoader.INSTANCE.scanVehiclePacks();
                }
                return;
            }
            copyModDirectory(PACK_RESOURCE_ROOT, targetPath);
            LOGGER.info("Installed RVP vehicle pack to {}", targetPath);
            // [RVP] 首启即有内容的关键补扫（2026-10-25）：本体 YwzjVehicle 构造器在依赖顺序上
            // 先于本 mod 构造，其 scanVehiclePacks 跑在 RVP 包落盘之前（全新安装或版本
            // 不等触发"备份删除重装"时），构造期唯一一次扫描读不到 rvp——表现为首启无内容、
            // 二启才有。此处解压完成后主动重扫一次覆盖该空扫（幂等：default_vehicle 版本
            // 相同时自跳过重装，packType 已由本体设好不受影响）。
            org.ywzj.vehicle.resource.VehiclePackLoader.INSTANCE.scanVehiclePacks();
            // [RVP] 首启创造栏补注册（2026-10-25）：本体 AllTabs.register 同样消费构造期扫描
            // 列表，tab 注册窗口早于补扫——首启 RealVehiclePack 标签页缺失（物品/实体经补扫
            // 已可用）。已注册（二启后本体自建）自动跳过，见 RVP_PackTabInjector。
            RVP_PackTabInjector.injectIfNeeded();
        } catch (Exception exception) {
            LOGGER.warn("Failed to install RVP vehicle pack to {}", targetPath, exception);
        }
    }

    private static boolean shouldSkipInstall(Path targetPath) throws IOException {
        Path metaPath = targetPath.resolve("vehicle_pack.meta.json");
        if (!Files.isDirectory(targetPath) || !Files.isRegularFile(metaPath)) {
            return false;
        }
        try (InputStream streamExist = Files.newInputStream(metaPath)) {
            PackMeta packMetaExist = GsonUtil.GSON.fromJson(
                    new InputStreamReader(streamExist, StandardCharsets.UTF_8), PackMeta.class);
            if (packMetaExist == null || packMetaExist.getVersion() == null) {
                return false;
            }
            try (InputStream streamJar = RVP_MOD.class.getResourceAsStream(
                    PACK_RESOURCE_ROOT + "/vehicle_pack.meta.json")) {
                if (streamJar == null) {
                    return true;
                }
                PackMeta packMetaJar = GsonUtil.GSON.fromJson(
                        new InputStreamReader(streamJar, StandardCharsets.UTF_8), PackMeta.class);
                if (packMetaJar == null || packMetaJar.getVersion() == null) {
                    return true;
                }
                // 调用本项目版本策略：版本串保留本体尾段（如 1.0.1_preview_0.6.2.1）
                // 仅用于显示与追溯；校验只比较第一个下划线前的 RVP 版本段，
                // 避免 master/next-version 切换时仅因本体尾段不同而重解压整包。
                if (rvpVersion(packMetaExist.getVersion()).equals(rvpVersion(packMetaJar.getVersion()))) {
                    return true;
                }
            }
        } catch (JsonSyntaxException | JsonIOException exception) {
            LOGGER.warn("Failed to compare RVP vehicle pack versions, reinstalling pack", exception);
            return false;
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String timestamp = LocalDateTime.now().format(formatter);
        Path backupPath = VEHICLE_PACKS_BACKUP_PATH.resolve(PACK_FOLDER_NAME + "_" + timestamp);
        GetJarResources.copyFolder(targetPath.toUri(), backupPath);
        GetJarResources.deleteFiles(targetPath);
        return false;
    }

    /** 取版本串中第一个下划线之前的 RVP 版本段；本体/阶段尾段不参与校验。 */
    private static String rvpVersion(String version) {
        int separator = version.indexOf('_');
        return separator < 0 ? version : version.substring(0, separator);
    }

    /** 同前缀的旧主包按清单补缺，已有文件只在原始内容匹配时更新。 */
    private static boolean applyPackUpdates(Path targetPath) throws IOException, NoSuchAlgorithmException {
        try (InputStream manifest = RVP_MOD.class.getResourceAsStream(PACK_UPDATES_RESOURCE)) {
            if (manifest == null) return false;

            PackUpdate[] updates = GsonUtil.GSON.fromJson(
                    new InputStreamReader(manifest, StandardCharsets.UTF_8), PackUpdate[].class);
            if (updates == null) return false;

            Path root = targetPath.toAbsolutePath().normalize();
            boolean changed = false;
            for (PackUpdate update : updates) {
                Path file = root.resolve(update.path()).normalize();
                if (!file.startsWith(root) || file.equals(root)) {
                    throw new IOException("Invalid bundled pack update path: " + update.path());
                }
                boolean exists = Files.exists(file);
                if (exists && !matchesOriginal(file, update.previousSha256())) continue;

                try (InputStream payload = RVP_MOD.class.getResourceAsStream(PACK_RESOURCE_ROOT + "/" + update.path())) {
                    if (payload == null) throw new IOException("Missing bundled pack update: " + update.path());
                    if (writePackUpdate(file, payload.readAllBytes(), exists, update.previousSha256())) {
                        LOGGER.info("Applied RVP pack update {}", update.path());
                        changed = true;
                    }
                }
            }
            return changed;
        }
    }

    private static boolean matchesOriginal(Path file, String expected) throws IOException, NoSuchAlgorithmException {
        if (expected == null || !Files.isRegularFile(file)) return false;
        return expected.equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
    }

    private static boolean writePackUpdate(Path file, byte[] data, boolean existed, String previousSha256)
            throws IOException, NoSuchAlgorithmException {
        Files.createDirectories(file.getParent());
        if (!existed) {
            try {
                Files.write(file, data, StandardOpenOption.CREATE_NEW);
                return true;
            } catch (FileAlreadyExistsException exception) {
                return false;
            }
        }

        Path staged = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(staged, data);
            if (!matchesOriginal(file, previousSha256)) return false;
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void copyModDirectory(String sourcePath, Path targetPath) throws IOException, URISyntaxException {
        URL url = RVP_MOD.class.getResource(sourcePath);
        if (url == null) {
            LOGGER.warn("RVP vehicle pack resources missing at {}", sourcePath);
            return;
        }
        GetJarResources.copyFolder(url.toURI(), targetPath);
    }
}
