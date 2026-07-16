package org.ywzj.rvp.weapon.effects;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CNuclearVisualEffect;
import org.ywzj.rvp.weapon.data.RVP_HbmEffectData;

import java.util.Locale;

public final class RVP_HbmVisualService {

    private RVP_HbmVisualService() {}

    public static boolean spawn(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec) {
        if (level == null || pos == null || spec == null) {
            return false;
        }
        String preset = normalizePreset(spec.getVisualPreset());
        if (preset == null) {
            return false;
        }
        boolean nuclear = "nuclear".equals(preset);
        S2CNuclearVisualEffect message = new S2CNuclearVisualEffect(
                preset,
                pos.x, pos.y, pos.z,
                Math.max(1.0F, spec.getEffectYield()),
                spec.getVisualScale(),
                spec.getVisualDensity(),
                level.random.nextLong(),
                level.getGameTime(),
                spec.isVisualSound() && (!nuclear || spec.isNuclearSound()),
                nuclear && spec.isNuclearFlash(),
                nuclear && spec.isNuclearShake());
        for (ServerPlayer player : level.players()) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
        return true;
    }

    private static String normalizePreset(String preset) {
        String normalized = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "shell" -> "shell";
            case "bomb" -> "bomb";
            case "nuclear", "nuke" -> "nuclear";
            default -> null;
        };
    }
}
