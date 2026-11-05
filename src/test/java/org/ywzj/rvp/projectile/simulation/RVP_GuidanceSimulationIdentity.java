package org.ywzj.rvp.projectile.simulation;

import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeMath;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 核对独立测试进程实际加载的制导类，防止类路径遮蔽失效后产生同算法互相比的假结果。 */
public final class RVP_GuidanceSimulationIdentity {
    /** 固定的改造前 PIP 源码对象，已有 Git 历史可重建，不改变工作区源码。 */
    public static final String PIP_SOURCE_BLOB = "974ba042786f8f7b455f868a7400bf2a87734357";

    /** 工具类不允许实例化。 */
    private RVP_GuidanceSimulationIdentity() {}

    /**
     * @param mode PIP 固定基线或当前 PPN；仅影响独立测试进程
     * @param runtimeSHA256 实际被类加载器读取的运行时 class 内容指纹
     * @param pipSourceBlob 原 PIP 源码的不可变 Git 对象
     */
    public record Identity(String mode, String runtimeSHA256, String pipSourceBlob) {}

    /** 测试入口在任何仿真前核对 Gradle 指定的 class 指纹，不读取可变全局制导开关。 */
    public static Identity current() {
        String mode = System.getProperty("rvp.simulation.guidance", "PPN");
        if (!mode.equals("PIP") && !mode.equals("PPN")) throw new IllegalStateException("未知仿真制导模式：" + mode);
        try (var bytes = RVP_GuidanceRuntimeMath.class.getResourceAsStream("RVP_GuidanceRuntimeMath.class")) {
            if (bytes == null) throw new IllegalStateException("无法校验实际制导运行时");
            // 调用共同摘要工具，校验实际资源与 Gradle 编译输出一致。
            String actual = sha256(bytes.readAllBytes());
            String expected = System.getProperty("rvp.simulation.expectedRuntimeSHA256");
            if (expected != null && !expected.equals(actual)) throw new IllegalStateException("制导类路径错误，拒绝生成假对照");
            return new Identity(mode, actual, PIP_SOURCE_BLOB);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取实际制导 class", error);
        }
    }

    /** 对参数、场景清单与 class 使用相同 SHA-256 格式，避免只比较文件名或时间。 */
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
