package org.ywzj.rvp.client.laser.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState.LaserBeamKey;
import org.ywzj.rvp.client.light.RVP_LightSourceType;
import org.ywzj.rvp.client.light.RVP_ScreenLightManager;
import org.ywzj.rvp.client.shader.RVP_NvgHandler;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightParameters;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 激光武器与 DIRCM 干扰激光光束两端的屏幕光：起点定向光、终点球光，颜色与光束本身一致。
 *
 * <p>灯位取渲染用的已平滑坐标，起点沿光束方向略微外移，避免灯陷进炮口或车壳内部；
 * 定向轴就是光束方向，Shader 按水平 140° × 竖直 180° 裁切，所以起点只照亮炮口前方，
 * 不会把光打回车体，也不会像整圈半球那样往两侧糊出一大片。两盏灯每帧心跳续期，
 * 光束停止绘制后由 {@link RVP_ScreenLightManager} 在过期 tick 内自然回收。</p>
 *
 * <p>归属统一取发射载具 UUID（与干扰机玻片灯同归属），因此需要整车清灯时必须走
 * {@link RVP_ScreenLightManager#removePersistent(UUID, RVP_LightSourceType)} 的类型范围入口，
 * 否则会连带清掉另一类灯。灯键由光束标识派生，同一武器重复开火只续期不叠加。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_LaserBeamLights {

    /** 只在相机附近登记灯泡，远处光束不挤占八盏灯的预算。 */
    private static final double MAX_LIGHT_DISTANCE_SQR = 128.0 * 128.0;
    /** 起点灯沿光束方向外移的距离，单位格。 */
    private static final double START_FORWARD_OFFSET = 0.3;
    /** DIRCM 光束颜色，与 {@code RVP_DircmBeamRenderer#drawBeam} 的顶点色保持同值。 */
    private static final float DIRCM_RED = 1.0F;
    private static final float DIRCM_GREEN = 0.35F;
    private static final float DIRCM_BLUE = 0.1F;

    private RVP_LaserBeamLights() {
    }

    /**
     * 激光武器：颜色取自光束 ARGB，{@code strength} 为充能透明度（充能时灯先暗后亮）。
     */
    public static void updateLaser(LaserBeamKey key, UUID owner, Vec3 start, Vec3 end,
                                   float red, float green, float blue, float strength) {
        update(beamId("laser", key), owner, start, end, red, green, blue, strength);
    }

    /** DIRCM 干扰激光：颜色固定红橙，与光束顶点色一致。 */
    public static void updateDircm(int vehicleId, String boneName, UUID owner, Vec3 start, Vec3 end) {
        update(beamId("dircm", vehicleId + ":" + boneName), owner, start, end,
                DIRCM_RED, DIRCM_GREEN, DIRCM_BLUE, 1F);
    }

    /** 两盏灯共用同一套校验：无效坐标不登记，屏幕光总开关关闭时同样跳过。 */
    private static void update(String beamId, UUID owner, Vec3 start, Vec3 end,
                               float red, float green, float blue, float strength) {
        if (owner == null || start == null || end == null) {
            return;
        }
        if (!RVP_LightingConfig.screenEnabled() || RVP_LightingConfig.maxLights() <= 0) {
            return;
        }
        Vec3 delta = end.subtract(start);
        double lengthSqr = delta.lengthSqr();
        if (!Double.isFinite(lengthSqr) || lengthSqr < 1.0E-8) {
            return;
        }
        Vec3 direction = delta.normalize();
        Vec3 head = start.add(direction.scale(START_FORWARD_OFFSET));
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        // 夜视仪下换用更大更亮的一档（与光电干扰机同思路）：像增强管把光束散射放大，
        // 同一盏灯在夜视里半径/亮度都要抬一档；普通视角则收成干扰机大小的小光圈。
        boolean nightVision = RVP_NvgHandler.isActive();
        // 起点定向光：轴向沿光束方向，Shader 内按水平 140° × 竖直 180° 裁切，能量只投向前方。
        upsert(lightKey(beamId, "head"), owner, head, camera, direction,
                nightVision ? RVP_LightParameters.laserStartNightVision(red, green, blue, strength)
                        : RVP_LightParameters.laserStart(red, green, blue, strength));
        // 终点球光：命中点一圈亮斑。
        upsert(lightKey(beamId, "tail"), owner, end, camera, null,
                nightVision ? RVP_LightParameters.laserEndNightVision(red, green, blue, strength)
                        : RVP_LightParameters.laserEnd(red, green, blue, strength));
    }

    private static void upsert(UUID key, UUID owner, Vec3 position, Vec3 camera, Vec3 axis,
                               RVP_LightParameters parameters) {
        if (camera.distanceToSqr(position) > MAX_LIGHT_DISTANCE_SQR) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(key, RVP_LightSourceType.LASER);
            return;
        }
        // 调用持续光统一入口：每帧心跳续期，半球轴缺省即全向球光。
        RVP_ScreenLightManager.INSTANCE.upsertPersistent(key, owner, position,
                RVP_LightSourceType.LASER, parameters, axis);
    }

    private static String beamId(String kind, Object beam) {
        return "ywzj_rvp:" + kind + ':' + beam;
    }

    private static UUID lightKey(String beamId, String end) {
        return UUID.nameUUIDFromBytes((beamId + ':' + end).getBytes(StandardCharsets.UTF_8));
    }
}
