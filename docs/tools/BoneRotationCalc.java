import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 结构骨 rotation 验算器 —— 复刻 ywzj_vehicle 的真实代码路径，别再靠推理猜。
 *
 * 依据（ywzj_vehicle 1.20.1，simplebedrockmodel 2.5.1）：
 *   1) BedrockModel.initialWithBoneItems 构造骨旋转：
 *        rot = Arrays.copyOf(json.rotation, 3);
 *        rot[0] = -toRadians(rot[0]);   rot[1] = -toRadians(rot[1]);   rot[2] = +toRadians(rot[2]);
 *        bone.rotation.rotateZYX(rot[0], rot[1], rot[2]);      // joml: rotateZYX(angleZ, angleY, angleX)
 *      ⇒ M = Rz(-x) · Ry(-y) · Rx(+z)
 *   2) WeaponUnit.aimContext():948-951 取发射方向：
 *        vehicle.rotYXZ().mul(baseRotation).getEulerAnglesYXZ(e);
 *        direction = (deg(e.x) + bolt.xRot, deg(-e.y) + bolt.yRot);
 *   3) VectorUtil.rotToVec(pitch, yaw) = ( sin(-yaw)cos(pitch), -sin(pitch), cos(-yaw)cos(pitch) )
 *      ⇒ pitch 正 = 向下（俯角），与 rot_info「负=仰角」一致。
 *
 * 用法（jdk-21，joml 从 gradle 缓存取）：
 *   JOML="C:\Users\<你>\.gradle\caches\forge_gradle\maven_downloader\org\joml\joml\1.10.5\joml-1.10.5.jar"
 *   javac -encoding UTF-8 -cp "$JOML" -d . BoneRotationCalc.java
 *   java -Dfile.encoding=UTF-8 -cp ".;$JOML" BoneRotationCalc
 *
 * 结论速查（rotation = [第1位(横滚), 第2位(偏航), 第3位(俯仰, 负=向上)]）：
 *   [0,   0, -30]  朝前、向上 30°        [0,   0, +30]  朝前、向下 30°
 *   [0, -90, -30]  朝 +X、向上 30°       [0, +90, -30]  朝 -X、向上 30°
 *   [0,   0, -90]  垂直向上              [0,   0, +90]  垂直向下
 *   ⚠️ 只有第 2、3 位影响发射方向；第 1 位是纯横滚。
 *   ⚠️ 「朝两侧、同样仰角」时第 3 位相同、只翻第 2 位；用 [30,±90,0] 这类写法会出现一门朝上一门朝下。
 */
public class BoneRotationCalc {

    /** 复刻本体的构造 */
    static Quaternionf build(float jx, float jy, float jz) {
        Quaternionf q = new Quaternionf();
        q.rotateZYX((float) Math.toRadians(-jx), (float) Math.toRadians(-jy), (float) Math.toRadians(jz));
        return q;
    }

    /** 复刻 VectorUtil.rotToVec(pitch, yaw) */
    static Vector3f direction(float jx, float jy, float jz) {
        Vector3f e = new Vector3f();
        build(jx, jy, jz).getEulerAnglesYXZ(e);
        double pitch = Math.toRadians(Math.toDegrees(e.x));
        double yaw = Math.toRadians(Math.toDegrees(-e.y));
        return new Vector3f(
                (float) (Math.sin(-yaw) * Math.cos(pitch)),
                (float) (-Math.sin(pitch)),
                (float) (Math.cos(-yaw) * Math.cos(pitch)));
    }

    /** 打印一组的 pitch / yaw / 世界方向向量 */
    static void show(float jx, float jy, float jz) {
        Vector3f e = new Vector3f();
        build(jx, jy, jz).getEulerAnglesYXZ(e);
        Vector3f v = direction(jx, jy, jz);
        double pitch = Math.toDegrees(e.x), yaw = Math.toDegrees(-e.y);
        String ud = Math.abs(pitch) < 0.5 ? "水平" : (v.y > 0 ? "向上" : "向下");
        System.out.printf("  [%6.0f,%6.0f,%5.0f]  pitch=%+7.1f yaw=%+7.1f  vec=(%+.2f,%+.2f,%+.2f)  %s%.0f°%n",
                jx, jy, jz, pitch, yaw, v.x, v.y, v.z, ud, Math.abs(pitch));
    }

    public static void main(String[] args) {
        System.out.println("=== 参考解 ===");
        show(0, 0, -30);   // 朝前 向上30
        show(0, -90, -30); // 朝 +X 向上30
        show(0, 90, -30);  // 朝 -X 向上30
        show(0, 0, -90);   // 垂直向上
        show(90, 0, 0);    // 垂发写法：横滚90，方向不变（水平）
        System.out.println("=== DDG51 鱼叉踩的坑 ===");
        show(30, 90, 0);   // => 向上30
        show(30, -90, 0);  // => 向下30  ← 就是它
        System.out.println("=== 自定义：改这里的值 ===");
        show(0, 0, 0);
    }
}
