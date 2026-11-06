package org.ywzj.rvp.client.visual.explosion;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings.Parameter;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings.Snapshot;

/** 单个本地预览的世界位置与时间线；不注册击毁监听、不发网络包、不生成实体。 */
public final class RVP_VehicleExplosionPreview {
    /** F8和屏幕绘制共用的唯一预览实例。 */
    public static final RVP_VehicleExplosionPreview INSTANCE = new RVP_VehicleExplosionPreview();
    /** 两次循环之间的空白观察时间，单位秒。 */
    private static final double LOOP_GAP = 0.6;
    /** 只按对象身份比较的世界；离开该世界必须丢弃预览。 */
    private Object world;
    /** 不跟随摄像机的世界爆心。 */
    private Vec3 origin = Vec3.ZERO;
    /** 上次采样的游戏时间，单位秒，不使用电脑墙上时钟。 */
    private double lastTime;
    /** 已播放秒数；定格时暂停累积，退出定格从当前指定进度继续。 */
    private double elapsed;

    /** 再次预览替换旧效果，空间和显卡开销始终只有一团。 */
    public void start(Object currentWorld, Vec3 position, double timeSeconds) {
        if (currentWorld == null || position == null || !Double.isFinite(timeSeconds)
                || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            throw new IllegalArgumentException("预览需要有效世界、位置和时间。");
        }
        world = currentWorld; origin = position; lastTime = timeSeconds; elapsed = 0;
    }

    /** 本帧早期查询；没有预览时不要求复制深度，也不创建任何显卡资源。 */
    public boolean active(Object currentWorld) {
        if (world != currentWorld) {
            // 调用统一清理，换维度、登出和重进同名维度都不能继承旧位置。
            clear();
        }
        return world != null;
    }

    /** 按游戏时钟推进；循环间隔返回空，定格时噪声动画也使用相同年龄。 */
    public Frame sample(Object currentWorld, double timeSeconds) {
        // 调用世界身份检查，渲染发生在首个逻辑刻之前也不会看见旧世界预览。
        if (!active(currentWorld) || !Double.isFinite(timeSeconds)) { return null; }
        Snapshot settings = RVP_VehicleExplosionSettings.snapshot();
        double delta = Math.max(0, timeSeconds - lastTime);
        lastTime = timeSeconds;
        float duration = settings.get(Parameter.DURATION);
        if (settings.get(Parameter.HOLD) > 0.5F) {
            elapsed = settings.get(Parameter.PROGRESS) * duration;
        } else {
            elapsed += delta;
            if (settings.get(Parameter.LOOP) > 0.5F) { elapsed %= duration + LOOP_GAP; }
            else if (elapsed >= duration) {
                // 调用停止入口，单次播放结束后立即让整套深度管线恢复零预览开销。
                clear(); return null;
            }
        }
        if (elapsed >= duration) { return null; }
        float age = (float) elapsed;
        Vec3 center = origin.add(settings.get(Parameter.WIND_X) * age,
                settings.get(Parameter.RISE) * age, settings.get(Parameter.WIND_Z) * age);
        return new Frame(center, age, settings);
    }

    /** 手动停止、世界卸载及资源重载共用；保留F8参数以便重播。 */
    public void clear() { world = null; origin = Vec3.ZERO; elapsed = 0; lastTime = 0; }

    /** 一帧渲染事实；世界尺寸同时供屏幕裁剪和体积求交使用。 */
    public record Frame(
            /** 已叠加漂移和上升的世界中心。 */ Vec3 center,
            /** 当前年龄，单位秒，定格时固定。 */ float age,
            /** 当前帧不可变的参数。 */ Snapshot settings) {
        /** 保守包围球；覆盖烟的后期膨胀、竖向拉伸及独立扩散空气壳。 */
        public float boundRadius() {
            // 调用同帧参数取最大范围，任何滑条修改都会同步改变体积裁剪范围。
            float radius = settings.get(Parameter.RADIUS);
            return radius * Math.max(1.8F, (1F + settings.get(Parameter.SMOKE_EXPANSION))
                    * Math.max(1F, settings.get(Parameter.STRETCH))) * 1.08F;
        }
    }
}
