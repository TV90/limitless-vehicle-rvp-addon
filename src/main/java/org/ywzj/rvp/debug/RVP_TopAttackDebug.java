package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionPayloadKind;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.weapon.VehicleWeaponIndex;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 攻顶引信（TOW-2B 自锻破片）专用调试：区分配置问题与代码/运行时问题。
 *
 * <p>用法：{@code /rvpdebug topattack on|off|status|clear}
 * <ul>
 *     <li>{@code status}：直接解析目标武器 id 的 {@code fuse_data}/{@code submunition_data}/{@code reload}
 *         实际加载结果。武器返回 NOT_LOADED 说明 JSON 加载失败（配置问题）；
 *         参数正确但运行时仍不触发则用 {@code on} 开逐 tick 日志查代码路径。</li>
 *     <li>{@code on}：在 {@link RVP_BaseBullet#tickTopAttackFuse()} 各关键分支写入
 *         {@code logs/rvp_topattack_debug.log}。</li>
 * </ul>
 */
public final class RVP_TopAttackDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get()
            .resolve("logs")
            .resolve("rvp_topattack_debug.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private RVP_TopAttackDebug() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
    }

    public static Path getLogPath() {
        return LOG_PATH;
    }

    public static void clearLog() {
        try {
            Files.deleteIfExists(LOG_PATH);
        } catch (IOException e) {
            LOGGER.error("[RVP] Failed to clear topattack debug log", e);
        }
    }

    /**
     * 由 {@link RVP_BaseBullet#tickTopAttackFuse()} 调用；开关关闭时无开销。
     *
     * <p><b>调用方注意（2026-09-14 性能审查 §5.1）</b>：本方法的 {@code detail} 是<b>饿汉求值</b>的
     * {@code String}——实参在调用前就会拼好，开关关闭也白付这份构建成本。凡处于<b>每 tick 热路径</b>
     * 的调用点，必须先用 {@link #isEnabled()} 短路再调用；仅"事件级"（引爆/命中/单次触发）的调用点
     * 可直接调用。新增调用点请遵守此约定，或改用惰性 {@code Supplier} 重载。</p>
     */
    public static void noteTick(RVP_BaseBullet bullet, String detail) {
        if (!ENABLED.get()) {
            return;
        }
        write(bullet.level().getGameTime()
                + " bullet=" + bullet.getUUID().toString().substring(0, 8)
                + " y=" + String.format("%.1f", bullet.getY())
                + " " + detail);
    }

    /**
     * 子母弹生成链路调试（引信引爆 → runner → spawner → 子弹实体），与 topattack 同开关。
     *
     * <p>与 {@link #noteTick} 同注意项：{@code detail} 为饿汉求值，位于每 tick 热路径的调用点
     * 须先用 {@link #isEnabled()} 短路。</p>
     */
    public static void noteSpawn(RVP_BaseBullet bullet, String detail) {
        if (!ENABLED.get()) {
            return;
        }
        write(bullet.level().getGameTime()
                + " bullet=" + bullet.getUUID().toString().substring(0, 8)
                + " y=" + String.format("%.1f", bullet.getY())
                + " " + detail);
    }

    /** 配置解析诊断：判断武器 JSON 是否被正确加载、参数是否生效。 */
    public static String buildStatus(ResourceLocation weaponId) {
        StringBuilder sb = new StringBuilder("[RVP][topattack] ").append(weaponId);
        VehicleWeaponIndex<?, ?> index = CommonAssetsManager.vehicleWeaponManager()
                .getIndex(weaponId).orElse(null);
        if (index == null) {
            sb.append(" -> NOT_LOADED（武器数据加载失败或不存在）");
            return sb.toString();
        }
        sb.append(" -> loaded");
        BaseVehicleWeaponData data = index.data();
        if (data.getReload() != null && data.getReload().getAmmo() != null) {
            sb.append(" reload.time=").append(data.getReload().getTime())
                    .append(" reload.ammo=").append(data.getReload().getAmmo().toJson());
        }
        if (!(data instanceof RVP_WeaponData rvp)) {
            return sb.toString();
        }
        RVP_FuseData fuse = rvp.getFuseData();
        sb.append(" fuse.topAttack=").append(fuse.isTopAttackFuseEnabled())
                .append(" dist=").append(fuse.getTopAttackFuseDistance())
                .append(" fov=").append(fuse.getTopAttackFuseFov())
                .append(" delay=").append(fuse.getTopAttackFuseDelayTick())
                .append(" arm=").append(fuse.getTopAttackFuseArmTick());
        RVP_SubmunitionData sub = rvp.getSubmunitionData();
        if (sub.getReleases().isEmpty()) {
            sb.append(" submunition=NONE");
            return sb.toString();
        }
        int r = 0;
        for (RVP_SubmunitionReleaseData release : sub.getReleases()) {
            sb.append(" | release").append(r++)
                    .append(" triggers=").append(release.getTriggers())
                    .append(" parentAction=").append(release.getParentAction())
                    .append(" delay=").append(release.getDelayTick());
            int p = 0;
            for (RVP_SubmunitionPayloadData payload : release.getPayloads()) {
                sb.append(" |  payload").append(p++)
                        .append(" kind=").append(payload.getKind())
                        .append(" weaponId=").append(payload.resolveWeaponId(weaponId))
                        .append(" count=").append(payload.getCount())
                        .append(" yaw=").append(payload.getLaunchYaw())
                        .append(" pitch=").append(payload.getLaunchPitch())
                        .append(" angleMode=")
                        .append(payload.isLaunchAngleAbsolute() ? "absolute" : "relative")
                        .append(" speed=").append(payload.getLaunchSpeed())
                        .append(" inheritVel=").append(payload.isInheritParentVelocity())
                        .append(" suppressExplosion=").append(payload.isSuppressExplosion());
                if (payload.getKind() == RVP_EnumSubmunitionPayloadKind.RVP_WEAPON) {
                    ResourceLocation childId = payload.resolveWeaponId(weaponId);
                    boolean childLoaded = childId != null
                            && CommonAssetsManager.vehicleWeaponManager().getIndex(childId).isPresent();
                    sb.append(" childData=").append(childLoaded ? "LOADED" : "NOT_LOADED");
                }
            }
        }
        return sb.toString();
    }

    private static void write(String line) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            Files.writeString(LOG_PATH,
                    TIME_FORMAT.format(LocalDateTime.now()) + " " + line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[RVP] Failed to write topattack debug log", e);
        }
    }
}
