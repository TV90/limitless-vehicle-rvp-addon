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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Installs the bundled {@code /rvp} vehicle pack into {@code limitless_vehicle/rvp}
 * before {@link org.ywzj.vehicle.resource.VehiclePackLoader} scans packs.
 */
public final class RVP_VehiclePackInstaller {

    private static final Logger LOGGER = LogManager.getLogger(RVP_MOD.MOD_ID);
    private static final String PACK_RESOURCE_ROOT = "/rvp";
    private static final String PACK_FOLDER_NAME = "rvp";
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
                return;
            }
            copyModDirectory(PACK_RESOURCE_ROOT, targetPath);
            LOGGER.info("Installed RVP vehicle pack to {}", targetPath);
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
                if (packMetaExist.getVersion().compareTo(packMetaJar.getVersion()) >= 0) {
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

    private static void copyModDirectory(String sourcePath, Path targetPath) throws IOException, URISyntaxException {
        URL url = RVP_MOD.class.getResource(sourcePath);
        if (url == null) {
            LOGGER.warn("RVP vehicle pack resources missing at {}", sourcePath);
            return;
        }
        GetJarResources.copyFolder(url.toURI(), targetPath);
    }
}
