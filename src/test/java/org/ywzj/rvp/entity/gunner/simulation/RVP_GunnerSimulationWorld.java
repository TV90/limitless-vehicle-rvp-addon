package org.ywzj.rvp.entity.gunner.simulation;

/** 测试用平地与实心盒环境；运动和感知都使用线段检测，避免高速跨过薄墙。 */
final class RVP_GunnerSimulationWorld {
    /** 当前只读场景。 */ private final RVP_GunnerSimulationScenario scene;
    RVP_GunnerSimulationWorld(RVP_GunnerSimulationScenario scene) { this.scene = scene; }

    /** 以保守方盒近似车体体积，检测整段运动；不模拟游戏中的斜坡或模型碰撞盒。 */
    boolean blocked(double[] start, double[] end, double radius, double height) {
        if (end[1] < scene.groundY - 0.001) return true;
        for (var box : scene.obstacles) {
            double[] min = {box.min()[0] - radius, box.min()[1] - height, box.min()[2] - radius};
            double[] max = {box.max()[0] + radius, box.max()[1], box.max()[2] + radius};
            if (intersects(start, end, min, max)) return true;
        }
        return false;
    }

    /** 感知射线从车体中部出发，不按车体半径膨胀。 */
    boolean visible(double[] from, double fromHeight, double[] to, double toHeight) {
        double[] start = {from[0], from[1] + fromHeight * 0.5, from[2]};
        double[] end = {to[0], to[1] + toHeight * 0.5, to[2]};
        for (var box : scene.obstacles) if (intersects(start, end, box.min(), box.max())) return false;
        return true;
    }

    /** slab 法：逐轴收紧线段落在盒内的时间区间；区间交集非空即发生接触。 */
    static boolean intersects(double[] start, double[] end, double[] min, double[] max) {
        double enter = 0, exit = 1;
        for (int i = 0; i < 3; i++) {
            double delta = end[i] - start[i];
            if (Math.abs(delta) < 1.0E-12) {
                if (start[i] < min[i] || start[i] > max[i]) return false;
            } else {
                double a = (min[i] - start[i]) / delta, b = (max[i] - start[i]) / delta;
                enter = Math.max(enter, Math.min(a, b)); exit = Math.min(exit, Math.max(a, b));
                if (enter > exit) return false;
            }
        }
        return true;
    }
}
