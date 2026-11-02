package org.ywzj.rvp.weapon.sound;

import com.google.gson.JsonObject;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.resource.PathPackResources;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_DisplayFireSound;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CVehicleSound;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.resource.VehiclePackLoader;
import org.ywzj.vehicle.util.ResourceScanner;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** 复用本体载具包资源列表读取 display，只给跟踪范围外且处于配置听距内的玩家补发开火通知。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VehicleSoundService {
    private static Map<ResourceLocation, RVP_DisplayFireSound> displaySounds;
    private RVP_VehicleSoundService() {}
    /** 首次开火懒加载；资源列表、目录/zip 与优先级均沿用本体 scanVehicleDisplayDefinitions。 */
    private static Map<ResourceLocation, RVP_DisplayFireSound> displays() {
        if (displaySounds != null) return displaySounds;
        var loaded = new HashMap<ResourceLocation, RVP_DisplayFireSound>();
        var packs = VehiclePackLoader.INSTANCE.getVehiclePacks();
        if (packs == null) return Map.of();
        var resources = new ArrayList<PackResources>();
        for (var pack : packs) {
            var name = pack.meta().getNamespace();
            resources.add(Files.isDirectory(pack.path()) ? new PathPackResources(name, false, pack.path())
                    : new FilePackResources(name, pack.path().toFile(), false));
        }
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, resources)) {
            ResourceScanner.scanDirectory(manager, FileToIdConverter.json("display/weapon"), GsonUtil.GSON, JsonObject.class)
                    .forEach((id, json) -> {
                        var sound = RVP_DisplayFireSound.parse(json);
                        if (sound != null) loaded.put(id, sound);
                    });
        }
        displaySounds = Map.copyOf(loaded);
        return displaySounds;
    }
    @SubscribeEvent public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        if (!(event.getVehicle().level() instanceof ServerLevel level)
                || event.getWeapon() instanceof org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent) return;
        var weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        if (weapon == null || weapon.getWeaponUnit() == null) return;
        var id = weapon.getData().getWeaponId();
        if (id == null) return;
        var config = displays().get(id);
        if (config == null) return;
        var vehicle = event.getVehicle();
        var position = weapon.getWeaponUnit().worldPivotPosition();
        var message = new S2CVehicleSound(level.dimension().location(), vehicle.getUUID(), vehicle.getId(), position, id);
        double tracking = effectiveTrackingBlocks(vehicle.getType().clientTrackingRange(), level.getServer().getPlayerList().getViewDistance());
        double maximum = (double) config.getMaxDistance() * config.getMaxDistance();
        for (var player : level.players()) {
            double distance = player.distanceToSqr(position);
            if (distance <= maximum && needsFallback(distance, tracking))
                RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }
    /** reload 命令执行前失效，命令完成后的首次开火重读 display；不改本体也不新增 Mixin。 */
    @SubscribeEvent public static void onCommand(CommandEvent event) {
        String command = event.getParseResults().getReader().getString().trim().replaceFirst("^/", "");
        if (command.equals("reload") || command.equals("ywzj_vehicle reload") || command.equals("rvp reload"))
            displaySounds = null;
    }
    @SubscribeEvent public static void onStop(ServerStoppedEvent event) { displaySounds = null; }
    static double trackingRangeBlocks(int chunks) { return Math.max(16.0D, chunks * 16.0D); }
    static double effectiveTrackingBlocks(int chunks, int viewDistance) {
        return Math.min(trackingRangeBlocks(chunks), trackingRangeBlocks(viewDistance));
    }
    static boolean needsFallback(double distanceSquared, double trackingBlocks) {
        return distanceSquared > trackingBlocks * trackingBlocks;
    }
}
