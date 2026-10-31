package org.ywzj.rvp.weapon.visual;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
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

    /** 空中起爆的高度门槛，单位格：爆心离地至少 35 格才生成 explosion 屏幕光源。 */
    public static final double MIN_AIR_BURST_CLEARANCE = 35.0D;

    private static final RVP_NetworkVisualEventPublisher PUBLISHER = new RVP_NetworkVisualEventPublisher();

    private RVP_DefaultExplosionVisualService() {
    }

    /**
     * 纯数值判定：爆心离地高度是否达到空中起爆门槛。
     *
     * @param explosionY 爆心世界 Y
     * @param groundY 该水平位置的地面高度（原版 heightmap 返回的“地面之上第一格”）
     * @return 离地高度 ≥ {@link #MIN_AIR_BURST_CLEARANCE} 时为 true；非有限值一律 false
     */
    public static boolean isAirBurstAt(double explosionY, int groundY) {
        if (!Double.isFinite(explosionY)) { return false; }
        return explosionY - groundY >= MIN_AIR_BURST_CLEARANCE;
    }

    /**
     * 按真实地形判定一次起爆是否属于空中起爆；只有空中起爆才让客户端生成 explosion 光源。
     *
     * <p>用原版 {@link Heightmap.Types#MOTION_BLOCKING} 取地面高度：树叶、水面也挡住高度图，
     * 因此在树冠正上方或水面上方不会因为“看起来高”而误判；地下爆心得到负的离地高度，
     * 自然判为非空中起爆。</p>
     *
     * @param level 服务端世界
     * @param pos 爆心
     */
    public static boolean isAirBurst(ServerLevel level, Vec3 pos) {
        if (level == null || pos == null || !Double.isFinite(pos.y)) { return false; }
        BlockPos center = BlockPos.containing(pos.x, pos.y, pos.z);
        // 调用原版高度图，避免额外扫描方块；区块在弹体起爆时必然已加载。
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, center.getX(), center.getZ());
        return isAirBurstAt(pos.y, groundY);
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
        // 调用统一入口：是否空中起爆由爆心离地高度决定（≥35 格），与武器或载具无关。
        spawn(level, pos, radius, weaponKind, explosionSound, false, isAirBurst(level, pos));
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
        // 调用统一入口：空中起爆按爆心离地高度判定，命中地面/载具的爆点自然低于 35 格。
        spawn(level, pos, radius, weaponKind, explosionSound, cannonSound, isAirBurst(level, pos));
    }

    /**
     * 发布一次默认爆炸视觉与声音事件，并标明是否空中起爆。
     *
     * @param level 服务端世界
     * @param pos 服务端权威爆心
     * @param radius 已解析引信覆盖后的最终爆炸半径，单位格
     * @param weaponKind RVP 弹体行为类型，用于客户端选择默认爆炸音色
     * @param explosionSound 自定义爆炸音效事件 ID（可空）
     * @param cannonSound 是否使用大口径炮弹音效档案
     * @param airBurst 是否空中起爆；false 时客户端不生成屏幕光源（照亮/光晕/核心/天空）
     */
    public static void spawn(ServerLevel level, Vec3 pos, float radius, RVP_EnumWeaponKind weaponKind,
                             @Nullable String explosionSound, boolean cannonSound, boolean airBurst) {
        RVP_VisualEffectEvent event = new RVP_VisualEffectEvent(
                EFFECT_TYPE,
                new ResourceLocation("rvp", "default"),
                // 调用 RVP 默认爆炸事件编码器，把类型化武器分类、自定义音效与起爆性质写入受限公共载荷。
                RVP_DefaultExplosionEventData.encode(weaponKind, explosionSound, cannonSound, airBurst),
                level.dimension(),
                pos,
                radius,
                1.0f,
                1.0f,
                -1,
                // 调用端广播距离：空爆与远距爆炸的视觉、声音都需要更长覆盖，统一放宽到 8192 格。
                8192.0,
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
