package org.ywzj.rvp.debug;

import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import org.ywzj.rvp.sight.RVP_SightFireDisguise;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 观瞄射弹原点分离（rvp_sight_fire_disguise）单发取证（2026-10-24，命令式）。
 *
 * <p>现象：近距离向下射击时，弹体模型从炮口「斜向上」飞出，而实际弹着点在下方；仅配置了
 * {@code rvp_sight_fire_disguise} 的载具出现。</p>
 *
 * <p>怀疑点：伪装渲染只做**固定平移** {@code offset = visualMuzzle − actualSpawn}，
 * 隐含前提是「炮口出弹点」与「真实出弹点」落在同一条弹道直线上（即 offset 与 direction 平行）。
 * 若观瞄相机与炮口的相对位置在竖直方向偏离弹道线，offset 就会带很重的横向/竖直分量，
 * 前 {@code disguise_ticks} 内模型被整体抬离弹道线，视觉上表现为「从炮口斜向飞」。</p>
 *
 * <p>用法：{@code /rvpdebug shotdump} 武装，下一次出弹打印一行到 {@code logs/rvp_shotdump.log}。
 * 不自动触发、不周期刷盘；未武装时调用点只做一次布尔判断。取证完成后可整体摘除。</p>
 */
public final class RVP_ShotDumpDiag {

    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get()
            .resolve("logs").resolve("rvp_shotdump.log");

    private static volatile boolean armed;

    private RVP_ShotDumpDiag() {
    }

    /** 命令入口：武装下一发。 */
    public static void arm() {
        armed = true;
    }

    public static boolean isArmed() {
        return armed;
    }

    /**
     * 出弹时调用（服务端）。输出炮口点、实际出弹点、伪装出发点、方向与 offset 的几何关系。
     *
     * @param actualSpawn 覆盖后的真实出弹点（启用伪装时 = 观瞄相机坐标，否则 = 炮口）
     * @param normalMuzzle 未覆盖的原炮口点
     * @param motion      弹体初速向量（方向 × 速度）
     */
    public static void capture(String weaponId, String kind, Vec3 actualSpawn, Vec3 normalMuzzle,
                               Vec3 motion, float xRot, float yRot, RVP_SightFireDisguise disguise) {
        if (!armed) {
            return;
        }
        armed = false;
        Vec3 dir = motion.lengthSqr() < 1.0E-9 ? Vec3.ZERO : motion.normalize();
        Vec3 visual = disguise == null ? null : disguise.visualMuzzle();
        Vec3 offset = visual == null ? null : visual.subtract(actualSpawn);
        StringBuilder sb = new StringBuilder();
        sb.append("[shotDump] weapon=").append(weaponId).append(" kind=").append(kind)
                .append(" rot=(").append(fmt(xRot)).append(", ").append(fmt(yRot)).append(")")
                .append(" dir=").append(vec(dir))
                .append(" speed=").append(fmt(motion.length()))
                .append(" actualSpawn=").append(vec(actualSpawn))
                .append(" normalMuzzle=").append(vec(normalMuzzle))
                .append(" 相机-炮口位移=").append(vec(normalMuzzle.subtract(actualSpawn)));
        if (disguise != null && offset != null) {
            double len = offset.length();
            double parallel = len < 1.0E-6 ? 0.0 : offset.dot(dir);
            double perp = len < 1.0E-6 ? 0.0 : Math.sqrt(Math.max(0.0, len * len - parallel * parallel));
            double angle = len < 1.0E-6 ? 0.0 : Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, parallel / len))));
            sb.append(" disguise(visual=").append(vec(visual))
                    .append(" ticks=").append(disguise.disguiseTicks())
                    .append("/").append(disguise.blendTicks()).append(")")
                    .append(" offset=").append(vec(offset))
                    .append(" |offset|=").append(fmt(len))
                    .append(" 沿弹道分量=").append(fmt(parallel))
                    .append(" 垂直弹道分量=").append(fmt(perp))
                    .append(" offset与弹道夹角=").append(fmt(angle)).append("°");
        } else {
            sb.append(" disguise=无");
        }
        try {
            Files.writeString(LOG_PATH, sb.append(System.lineSeparator()).toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 诊断写盘失败不影响射击
        }
    }

    private static String vec(Vec3 v) {
        return "(" + fmt(v.x) + ", " + fmt(v.y) + ", " + fmt(v.z) + ")";
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
