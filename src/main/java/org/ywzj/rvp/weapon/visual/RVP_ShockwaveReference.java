package org.ywzj.rvp.weapon.visual;

import net.minecraft.world.phys.Vec3;

/**
 * 数值参照配置
 */
public final class RVP_ShockwaveReference {
    /** 球体尺寸标定使用的爆炸半径（格）。 */
    private static final double REFERENCE_POWER = 24;
    /** meshSize=1 时标定的视觉半径（格）。 */
    private static final double REFERENCE_RADIUS = 60;
    /** 尘环主体 shrapnel_smoke 的默认寿命（tick）。 */
    public static final float RING_TICKS = 15;

    private RVP_ShockwaveReference() { }

    /** 实际算法为幂次缩放，并非配置注释里的线性 power×2.5。 */
    public static float sphereRadius(float explosionRadius, float meshSize) {
        return (float) Math.max(2, REFERENCE_RADIUS * Math.pow(Math.max(0, explosionRadius) / REFERENCE_POWER, 0.8)
                * Math.max(0, meshSize));
    }

    /** 在基础 12 tick 上按尺寸缩放，并限制到基础时长的 0.45～2 倍。 */
    public static float sphereTimeScale(float radius) {
        return (float) Math.max(0.45, Math.min(2, radius / REFERENCE_RADIUS));
    }

    /** 默认几何路径的单位球；经线 36 段时，纬线为 18 段，南北半球均保留。 */
    public static Vec3[][] buildUnitSphere(int segments) {
        int longitudes = Math.max(8, Math.min(64, segments));
        int latitudes = Math.max(4, longitudes / 2);
        Vec3[][] sphere = new Vec3[latitudes + 1][longitudes + 1];
        for (int latitude = 0; latitude <= latitudes; latitude++) {
            double phi = -Math.PI / 2 + Math.PI * latitude / latitudes;
            double horizontal = Math.cos(phi);
            for (int longitude = 0; longitude <= longitudes; longitude++) {
                double theta = Math.PI * 2 * longitude / longitudes;
                sphere[latitude][longitude] = new Vec3(horizontal * Math.cos(theta), Math.sin(phi),
                        horizontal * Math.sin(theta));
            }
        }
        return sphere;
    }

    /**
     * 先剔除背向观察者的球面片，保留轮廓附近少量侧面；这里不是原版的背面剔除。
     * 两个法线取该面片的对角顶点，toCamera 是爆心到相机的向量，距离单位为格。
     */
    public static boolean isVisibleFace(Vec3 first, Vec3 opposite, Vec3 toCamera, double cameraDistance) {
        double dot = ((first.x + opposite.x) * toCamera.x + (first.y + opposite.y) * toCamera.y
                + (first.z + opposite.z) * toCamera.z) * 0.5;
        return dot >= -0.25 * cameraDistance;
    }

    /**
     * bandAlpha：正对球面透明，轮廓亮带达到 brightness × lifeFade。
     * width 对应原始 thickness，几何路径实际限制为 0.02～0.6；亮带内缘用宽度的 15% 线性过渡。
     * normal 是单位球顶点，radius 为当前半径（格），toCamera/cameraDistanceSquared 使用爆心相对坐标。
     */
    public static float bandAlpha(Vec3 normal, double radius, Vec3 toCamera, double cameraDistanceSquared,
            float lifeFade, float brightness, double width) {
        double along = normal.x * toCamera.x + normal.y * toCamera.y + normal.z * toCamera.z;
        double distanceSquared = cameraDistanceSquared - 2 * radius * along + radius * radius;
        if (!(distanceSquared >= 0.000001) || !Double.isFinite(distanceSquared)) { return 0; }
        double cosine = Math.max(-1, Math.min(1, (along - radius) / Math.sqrt(distanceSquared)));
        double rim = 1 - Math.abs(cosine);
        double boundedWidth = Math.max(0.02, Math.min(0.6, width));
        double inner = 1 - boundedWidth;
        if (rim < inner) { return 0; }
        double feather = Math.max(0.15 * boundedWidth, 0.001);
        double transition = Math.min(1, (rim - inner) / feather);
        return (float) (Math.max(0, brightness) * transition * Math.max(0, lifeFade));
    }

    /** 用户确认的近似：用爆炸半径对应的半球体积代替实际射线采样方块数。 */
    public static int estimateBlocks(float radius) {
        double volume = Math.PI * 2 / 3 * Math.pow(Math.max(0, radius), 3);
        return (int) Math.min(Integer.MAX_VALUE - 1, Math.max(200, Math.round(volume)));
    }

    /** 三档尘环平均初速度（格/tick）；随机速度扰动为零均值，不改变几何环平均半径。 */
    public static float ringSpeed(int blocks, float speedScale) {
        if (blocks < 200) { return 0; }
        double base = blocks < 2000 ? 0.3 : blocks < 18000 ? 0.5 : 0.8;
        double factor = blocks < 2000 ? 0.0008 : blocks < 18000 ? 0.00025 : 0.0002725;
        return (float) ((base + blocks * factor) * speedScale);
    }

    /**
     * 对每 tick 位移求和，再在相邻 tick 之间线性插值，与粒子绘制插值一致。
     * 默认保留速度 0.8 来自 CBC ShrapnelSmokeParticle；忽略风、碰撞及垂直运动。
     */
    public static float ringDistance(float initialSpeed, float damping, float ticks) {
        double age = Math.max(0, ticks);
        double whole = Math.floor(age);
        double decay = Math.pow(damping, whole);
        double sum = damping == 1 ? whole : (1 - decay) / (1 - damping);
        return (float) (initialSpeed * (sum + (age - whole) * decay));
    }
}
