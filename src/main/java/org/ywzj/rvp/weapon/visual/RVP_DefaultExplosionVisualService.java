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

    private static final RVP_NetworkVisualEventPublisher PUBLISHER = new RVP_NetworkVisualEventPublisher();

    private RVP_DefaultExplosionVisualService() {
    }

    /**
     * 纯数值事实：爆心离地高度，单位格。
     *
     * <p>这里只计算“离地多少”，是否达到空中起爆门槛由客户端按自己的
     * {@code airBurstClearance} 配置判定；非有限爆心按 0 处理，避免把 NaN 写进载荷。</p>
     *
     * @param explosionY 爆心世界 Y
     * @param groundY 该水平位置的地面高度（原版 heightmap 返回的“地面之上第一格”）
     * @return 离地高度；负值表示爆心在地面以下（洞穴、山体内部）
     */
    public static float clearanceAt(double explosionY, int groundY) {
        if (!Double.isFinite(explosionY)) { return 0F; }
        return (float) (explosionY - groundY);
    }

    /**
     * 取该水平位置的地面高度。
     *
     * <p>用原版 {@link Heightmap.Types#MOTION_BLOCKING}：树叶、水面也挡住高度图，
     * 因此在树冠正上方或水面上方不会因为“看起来高”而误判。</p>
     *
     * @param level 服务端世界
     * @param pos 爆心
     * @return 地面之上第一格的 Y；世界或坐标无效时返回 0
     */
    public static int groundHeightAt(ServerLevel level, Vec3 pos) {
        if (level == null || pos == null) { return 0; }
        BlockPos center = BlockPos.containing(pos.x, pos.y, pos.z);
        // 调用原版高度图，避免额外扫描方块；区块在弹体起爆时必然已加载。
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, center.getX(), center.getZ());
    }

    /** 本次爆炸的离地高度事实，单位格；门槛判定在客户端，服务端只提供数值。 */
    public static float clearance(ServerLevel level, Vec3 pos) {
        if (level == null || pos == null) { return 0F; }
        return clearanceAt(pos.y, groundHeightAt(level, pos));
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
        // 调用统一入口：服务端只下发离地高度事实，是否建灯由客户端按 airBurstClearance 判定。
        spawn(level, pos, radius, weaponKind, explosionSound, false, clearance(level, pos));
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
        // 调用统一入口：命中地面/载具的爆点离地≈0，会自然低于客户端门槛。
        spawn(level, pos, radius, weaponKind, explosionSound, cannonSound, clearance(level, pos));
    }

    /**
     * 发布一次默认爆炸视觉与声音事件，并携带爆心离地高度事实。
     *
     * @param level 服务端世界
     * @param pos 服务端权威爆心
     * @param radius 已解析引信覆盖后的最终爆炸半径，单位格
     * @param weaponKind RVP 弹体行为类型，用于客户端选择默认爆炸音色
     * @param explosionSound 自定义爆炸音效事件 ID（可空）
     * @param cannonSound 是否使用大口径炮弹音效档案
     * @param clearance 爆心离地高度（格）；客户端用它与自己配置的 airBurstClearance 门槛比较
     */
    public static void spawn(ServerLevel level, Vec3 pos, float radius, RVP_EnumWeaponKind weaponKind,
                             @Nullable String explosionSound, boolean cannonSound, float clearance) {
        RVP_VisualEffectEvent event = new RVP_VisualEffectEvent(
                EFFECT_TYPE,
                new ResourceLocation("rvp", "default"),
                // 调用 RVP 默认爆炸事件编码器，把类型化武器分类、自定义音效与离地高度写入受限公共载荷。
                RVP_DefaultExplosionEventData.encode(weaponKind, explosionSound, cannonSound, clearance),
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

