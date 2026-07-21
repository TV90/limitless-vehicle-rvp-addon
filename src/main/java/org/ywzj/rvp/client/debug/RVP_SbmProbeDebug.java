package org.ywzj.rvp.client.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.rvp.client.resource.vehicle.RVP_BaseDisplay;
import org.ywzj.rvp.client.resource.vehicle.RVP_VehicleBedrockModel;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RVP_SbmProbeDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Map<String, Boolean> ONCE_KEYS = new ConcurrentHashMap<>();
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("sbmprobe.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private RVP_SbmProbeDebug() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static Path getLogPath() {
        return LOG_PATH;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
        ONCE_KEYS.clear();
        append("toggle enabled=" + enabled);
    }

    public static void clearLog() {
        ONCE_KEYS.clear();
        try {
            Files.deleteIfExists(LOG_PATH);
        } catch (IOException e) {
            LOGGER.error("[RVP][SBMProbe] Failed to clear {}", LOG_PATH, e);
        }
    }

    public static void dumpCurrentVehicleState(String reason) {
        AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.getVehicle();
        StringBuilder sb = new StringBuilder();
        sb.append("=== SBM PROBE DUMP ===\n");
        sb.append("reason=").append(reason).append('\n');
        appendVehicleState(sb, vehicle);
        append(sb.toString().trim());
    }

    public static void noteRvpVehicleRender(AbstractVehicle vehicle, VehicleBedrockModel model) {
        if (!ENABLED.get()) {
            return;
        }
        String key = "rvp-render|" + vehicle.getId() + "|" + vehicle.getVehicleId();
        if (ONCE_KEYS.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== RVP VEHICLE RENDER HIT ===\n");
        sb.append("vehicle=").append(vehicle.getVehicleId()).append('\n');
        sb.append("entityId=").append(vehicle.getId()).append('\n');
        sb.append("rendererClass=").append("org.ywzj.rvp.client.render.RVP_VehicleRender").append('\n');
        sb.append("modelClass=").append(model == null ? "<null>" : model.getClass().getName()).append('\n');
        appendVehicleState(sb, vehicle);
        append(sb.toString().trim());
    }

    public static void noteVehicleRenderMixin(AbstractVehicle vehicle, @Nullable VehicleBedrockModel model) {
        if (!ENABLED.get()) {
            return;
        }
        String key = "vehicle-render-mixin|" + vehicle.getId() + "|" + vehicle.getVehicleId();
        if (ONCE_KEYS.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== BASE VEHICLE RENDER MIXIN HIT ===\n");
        sb.append("vehicle=").append(vehicle.getVehicleId()).append('\n');
        sb.append("entityId=").append(vehicle.getId()).append('\n');
        sb.append("mixinClass=").append("<removed>").append('\n');
        sb.append("modelClass=").append(model == null ? "<null>" : model.getClass().getName()).append('\n');
        appendVehicleState(sb, vehicle);
        append(sb.toString().trim());
    }

    public static void noteWeaponSuppress(WeaponUnit weaponUnit, boolean suppressed) {
        if (!ENABLED.get()) {
            return;
        }
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        ResourceLocation vehicleId = vehicle == null ? null : vehicle.getVehicleId();
        String weaponId = "<null>";
        AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().orElse(null);
        if (currentWeapon != null && currentWeapon.getData() != null && currentWeapon.getData().getWeaponId() != null) {
            weaponId = currentWeapon.getData().getWeaponId().toString();
        }
        String key = "weapon-suppress|" + (vehicle == null ? "null" : vehicle.getId()) + "|" + weaponUnit.getId() + "|" + weaponId + "|" + suppressed;
        if (ONCE_KEYS.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        append("weaponSuppress vehicle=" + vehicleId
                + " entityId=" + (vehicle == null ? -1 : vehicle.getId())
                + " partUnit=" + weaponUnit.getId()
                + " currentWeapon=" + weaponId
                + " suppressed=" + suppressed);
    }

    private static void appendVehicleState(StringBuilder sb, @Nullable AbstractVehicle vehicle) {
        if (vehicle == null) {
            sb.append("vehicle=<null>\n");
            return;
        }
        sb.append("vehicleClass=").append(vehicle.getClass().getName()).append('\n');
        sb.append("vehicleId=").append(vehicle.getVehicleId()).append('\n');
        sb.append("displayId=").append(vehicle.getDisplayId()).append('\n');
        sb.append("entityId=").append(vehicle.getId()).append('\n');

        BaseDisplay display = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        sb.append("displayClass=").append(display == null ? "<null>" : display.getClass().getName()).append('\n');
        sb.append("displayModelClass=")
                .append(display == null || display.getModel() == null ? "<null>" : display.getModel().getClass().getName())
                .append('\n');
        sb.append("displayBackend=").append(describeDisplayBackend(display)).append('\n');

        EntityRenderer<? super AbstractVehicle> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(vehicle);
        sb.append("dispatcherRendererClass=")
                .append(renderer == null ? "<null>" : renderer.getClass().getName())
                .append('\n');

        var configs = RVP_CustomMountConfigCache.get(vehicle.getVehicleId());
        sb.append("customMountConfigCount=").append(configs.size()).append('\n');
        sb.append("customMountDebugEnabled=").append(RVP_CustomMountRenderLogic.isDebugEnabled()).append('\n');
        sb.append("currentWeaponUnit=")
                .append(LocalVehiclePlayer.instance == null || LocalVehiclePlayer.instance.getWeaponUnit() == null
                        ? "<null>"
                        : LocalVehiclePlayer.instance.getWeaponUnit().getId())
                .append('\n');
    }

    private static String describeDisplayBackend(@Nullable BaseDisplay display) {
        if (display == null) {
            return "<null>";
        }
        if (display instanceof RVP_BaseDisplay rvpDisplay) {
            return String.valueOf(rvpDisplay.getBedrockBackend());
        }
        try {
            var method = display.getClass().getMethod("getBedrockBackend");
            Object result = method.invoke(display);
            if (result != null) {
                return String.valueOf(result);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        if (display.getModel() instanceof RVP_VehicleBedrockModel) {
            return "<rvp-model-only>";
        }
        return "<non-rvp-display>";
    }

    private static synchronized void append(String message) {
        if (!ENABLED.get() && !message.startsWith("toggle")) {
            return;
        }
        try {
            Files.createDirectories(LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[RVP][SBMProbe] Failed to append {}", LOG_PATH, e);
        }
    }
}
