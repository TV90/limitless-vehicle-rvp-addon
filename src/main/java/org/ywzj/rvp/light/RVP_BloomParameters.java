package org.ywzj.rvp.light;

/** 多尺度光晕的尺寸和固定能量分配；不引用客户端类型，也不读取武器 JSON。 */
public final class RVP_BloomParameters {
    /** 四层分别使用原画面宽高的二分之一、四分之一、八分之一、十六分之一。 */
    public static final int LEVELS = 4;
    /** 前两层负责贴近亮焰的光晕，后两层负责向周围缓慢消散的辉光；默认总和严格为一。 */
    private static final float[] WEIGHTS = {0.4F, 0.3F, 0.2F, 0.1F};

    private RVP_BloomParameters() { }

    /** 各层向上取整，奇数窗口和极小窗口也至少分配一个像素；不使用可能溢出的加法。 */
    public static int size(int sceneSize, int level) {
        // 调用统一层号检查，防止不合法位移产生错误的显存尺寸。
        checkLevel(level);
        return sceneSize <= 1 ? 1 : 1 + (sceneSize - 1) / (1 << (level + 1));
    }

    /** 固定基础权重乘独立强度；关闭近层不会重新分配其能量给远层，反之亦然。 */
    public static float weight(int level, float nearStrength, float farStrength) {
        // 调用层号检查，调参只改变对应两层的能量，不改变层数、半径或喷焰尺寸。
        checkLevel(level);
        return WEIGHTS[level] * (level < 2 ? nearStrength : farStrength);
    }

    /** 不允许把数组越界或负位移交给渲染资源分配。 */
    private static void checkLevel(int level) {
        if (level < 0 || level >= LEVELS) { throw new IllegalArgumentException("无效光晕层号：" + level); }
    }
}
