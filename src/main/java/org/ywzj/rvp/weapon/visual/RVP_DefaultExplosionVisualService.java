package org.ywzj.rvp.weapon.visual;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.server.visual.RVP_NetworkVisualEventPublisher;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/**
 * RVP 内置默认爆炸视觉（MCHR 风格）服务端发布端。
 *
 * <p>把爆心 + 最终半径包装为公共领域事件（{@code effectType = rvp:mchr_explosion}）经
 * {@link RVP_NetworkVisualEventPublisher} 广播，客户端 {@code RVP_DefaultExplosionFactory}
 * 按 {@code baseExplosionRadius} + {@code seed} 内置推导全部粒子数值（不开放 JSON）。</p>
 *
 * <p>音效由同一事件在客户端按武器类型、爆炸半径和听者距离驱动；服务端不再补播
 * {@code GENERIC_EXPLODE}，避免与客户端近远音重复。</p>
 */
public final class RVP_DefaultExplosionVisualService {

    /** 默认爆炸视觉事件类型（客户端工厂按此注册）。 */
    public static final ResourceLocation EFFECT_TYPE =
            new ResourceLocation("rvp", "mchr_explosion");

    /** 大口径炮弹音效的严格阈值，单位毫米；恰好 16 mm 仍使用机枪音效。 */
    static final float CANNON_CALIBER_THRESHOLD_MM = 16.0F;

    private static final RVP_NetworkVisualEventPublisher PUBLISHER = new RVP_NetworkVisualEventPublisher();

    private RVP_DefaultExplosionVisualService() {
    }

    /**
     * 判断本次默认爆炸是否应使用大口径炮弹音效。
     *
     * <p>只对 {@link RVP_EnumWeaponKind#MACHINEGUN} 生效；顶层 {@code caliber} 与
     * {@code effects_data.caliber} 任一有效值大于 16 mm 即返回 true。服务端只计算一次，
     * 客户端通过事件参数接收结果，避免客户端自行读取武器配置后与服务端分歧。</p>
     *
     * @param weaponKind 类型化武器行为分类
     * @param weaponData 当前弹体使用的 RVP 武器数据
     * @return 是否使用 cannon 音效档案
     */
    public static boolean shouldUseCannonSound(RVP_EnumWeaponKind weaponKind,
                                               @Nullable RVP_WeaponData weaponData) {
        if (weaponData == null) {
            return false;
        }
        // 调用本项目 RVP 武器数据 getter，读取顶层口径与 effects_data 中的真实弹体口径。
        Float effectsCaliber = weaponData.getEffectsData().hasCaliberOverride()
                ? weaponData.getEffectsData().getCaliber() : null;
        return shouldUseCannonSound(weaponKind, weaponData.getCaliber(), effectsCaliber);
    }

    /** 使用两个可选口径值执行纯数值判断，便于测试边界和处理缺失字段。 */
    static boolean shouldUseCannonSound(RVP_EnumWeaponKind weaponKind,
                                        @Nullable Float topLevelCaliber,
                                        @Nullable Float effectsCaliber) {
        if (weaponKind != RVP_EnumWeaponKind.MACHINEGUN) {
            return false;
        }
        return isCannonCaliber(topLevelCaliber) || isCannonCaliber(effectsCaliber);
    }

    /** 判断口径是否为有限且严格大于 16 mm 的有效值。 */
    private static boolean isCannonCaliber(@Nullable Float caliber) {
        return caliber != null && Float.isFinite(caliber) && caliber > CANNON_CALIBER_THRESHOLD_MM;
    }

    /**
     * 发布一次默认爆炸视觉与声音事件。水中爆炸的水花由客户端按爆心流体状态自行判定。
     *
     * @param level 服务端世界
     * @param pos 服务端权威爆心
     * @param radius 已解析引信覆盖后的最终爆炸半径，单位格
     * @param weaponKind RVP 弹体行为类型，用于客户端选择默认爆炸音色
     * @param explosionSound 自定义爆炸音效事件 ID（可空；空则客户端按 weaponKind 选默认音色）
     */
    public static void spawn(ServerLevel level, Vec3 pos, float radius, RVP_EnumWeaponKind weaponKind,
                             @Nullable String explosionSound) {
        // 保留旧调用入口：ERA 等没有弹体口径的数据源默认继续使用原有音效档案。
        spawn(level, pos, radius, weaponKind, explosionSound, false);
    }

    /**
     * 发布一次带有大口径炮弹音效标记的默认爆炸视觉与声音事件。
     *
     * @param level 服务端世界
     * @param pos 服务端权威爆心
     * @param radius 已解析引信覆盖后的最终爆炸半径，单位格
     * @param weaponKind RVP 弹体行为类型，用于客户端选择默认爆炸音色
     * @param explosionSound 自定义爆炸音效事件 ID（可空）
     * @param cannonSound 是否使用大口径炮弹音效档案
     */
    public static void spawn(ServerLevel level, Vec3 pos, float radius, RVP_EnumWeaponKind weaponKind,
                             @Nullable String explosionSound, boolean cannonSound) {
        RVP_VisualEffectEvent event = new RVP_VisualEffectEvent(
                EFFECT_TYPE,
                new ResourceLocation("rvp", "default"),
                // 调用 RVP 默认爆炸事件编码器，把类型化武器分类与自定义音效写入受限公共载荷。
                RVP_DefaultExplosionEventData.encode(weaponKind, explosionSound, cannonSound),
                level.dimension(),
                pos,
                radius,
                1.0f,
                1.0f,
                -1,
                1536.0,
                level.random.nextLong(),
                level.getGameTime(),
                // sound=true：由 rvp:mchr_explosion 客户端实例按声速延迟播放近音或远音
                true,
                true,
                false,
                false, null);
        // 调用 RVP 网络视觉发布器，把同一份视觉与声音事件发送给广播范围内的客户端。
        PUBLISHER.publish(level, event);
        // 水中状态不进入事件参数：客户端按服务端权威 position 处的流体状态自行判定水花。
    }
}
