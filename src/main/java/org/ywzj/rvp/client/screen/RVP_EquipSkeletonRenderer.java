package org.ywzj.rvp.client.screen;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 辅助设备面板左上角俯视图（HTML div 注入，纯客户端）。
 *
 * <p><b>定版：静态"车辆数据俯视图"，零姿态跟随（用户定版）</b>。
 * 几何取 {@code VehicleCubeOBB} 的静态模型坐标经<b>骨链变换</b>后的位置——
 * cube 的 x/z 是相对骨枢轴的原始坐标，必须经 {@code VehicleCubeGroup#globalTransform}
 * （父级 pivot+rotation 链）变换才是真正的模型空间位置；此前直接用原始 x/z，
 * 挂在带枢轴父骨下的骨块（ERA 组、炮塔件）全部错位，已修正。
 * 投影朝向：{@code px = -p.x}、{@code py = -p.z}（车首朝上）。</p>
 *
 * <p><b>★渲染硬约束（2026-09-26 实测定版）</b>：AUI 的 div 不支持 transform 旋转，
 * <b>只能画轴对齐矩形</b>——因此视图固定在 bind-pose 朝向（无倾斜矩形），
 * 禁止任何视图旋转/姿态跟随。显示分层：普通部件 OBB = 纯白实心块（拼整车轮廓）；
 * ERA/设备骨等特殊骨骼 = 彩色空心线框（ERA 绿/失效红、设备骨蓝/失效红，
 * 失效判定查 {@link RVP_ClientBoneModuleState}）；线框在白底之后注入，永远压在白底之上。
 * 炮管等长条部件不参与缩放包络（防车体被压扁）。已知边界：APS 扇区锥未画。</p>
 */
public final class RVP_EquipSkeletonRenderer {

    /** 投影矩形：视空间坐标 + 线框颜色；solid=true 为纯白实心块（普通部件），false 为彩色空心线框（特殊设备）。 */
    public record ViewRect(float x0, float y0, float x1, float y1, String line, boolean solid, boolean fit) {
    }

    private static final String ERA_OK_LINE = "#69AD45";
    private static final String DEV_OK_LINE = "#4C8BE8";
    private static final String DEAD_LINE = "#D45B50";
    /** 普通部件 OBB：白色线框。 */
    private static final String NORMAL_LINE = "#FFFFFF";

    private RVP_EquipSkeletonRenderer() {
    }

    /**
     * 构建俯视图全部投影矩形：遍历全部部件的 {@code VehicleCubeOBB}（覆盖整车结构）。
     * 两层顺序即绘制层级：先全部普通部件白底实心块，后特殊设备骨彩色空心线框（永远在上层）。
     * ★炮管/机枪管等长条部件不参与缩放包络（fit=false）——否则炮管把视图撑大、车体被压扁；
     * 它们仍会被绘制（超出包络的部分由画布裁剪）。
     */
    public static List<ViewRect> buildRects(AbstractVehicle vehicle) {
        List<ViewRect> out = new ArrayList<>();
        Map<String, Set<BoneModuleType>> modules = RVP_EquipPanelData.boneModules(vehicle);
        int entityId = vehicle.getId();
        // 第一层：非模块部件 → 纯白实心块（拼出整车轮廓）
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (modules.containsKey(partUnit.getId())) {
                continue; // 特殊设备骨留到第二层画线框
            }
            boolean isBarrel = partUnit.getId().contains("barrel");
            for (VehicleCubeOBB cube : partUnit.getPartCubeOBBs()) {
                out.add(cubeRect(cube, NORMAL_LINE, true, !isBarrel));
            }
        }
        // 第二层：骨模块部件 → 彩色空心线框（ERA 绿/失效红，设备骨蓝/失效红）
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            String bone = partUnit.getId();
            Set<BoneModuleType> types = modules.get(bone);
            if (types == null) {
                continue;
            }
            boolean destroyed = false;
            for (BoneModuleType type : types) {
                if (!RVP_ClientBoneModuleState.isModuleActive(entityId, bone, type)) {
                    destroyed = true;
                    break;
                }
            }
            String line = destroyed ? DEAD_LINE : (types.contains(BoneModuleType.ERA) ? ERA_OK_LINE : DEV_OK_LINE);
            for (VehicleCubeOBB cube : partUnit.getPartCubeOBBs()) {
                out.add(cubeRect(cube, line, false, true));
            }
        }
        return out;
    }

    /**
     * 单 VehicleCubeOBB → 静态模型坐标线框矩形：cube 的 8 个角点逐个经
     * {@code group.globalTransform(corner, false)} 做骨链变换（父级 pivot+rotation），
     * 得到 bind-pose 模型空间位置，投影 {@code px = -p.x}、{@code py = -p.z}（车首朝上）后取包络。
     */
    private static ViewRect cubeRect(VehicleCubeOBB cube, String line, boolean solid, boolean fit) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        VehicleCubeGroup group = cube.group;
        if (group == null) {
            return null; // 无骨链信息（理论不发生）：跳过
        }
        for (int cx = 0; cx <= 1; cx++) {
            for (int cy = 0; cy <= 1; cy++) {
                for (int cz = 0; cz <= 1; cz++) {
                    Vec3 corner = new Vec3(
                            cube.getX() + (cx == 1 ? cube.getWidth() : 0),
                            cube.getY() + (cy == 1 ? cube.getHeight() : 0),
                            cube.getZ() + (cz == 1 ? cube.getDepth() : 0));
                    // 纯 bind-pose 骨链变换：全链用 baseRotation（基础旋转）+ 纯 pivot 链（零动画偏移），
                    // 与炮塔/车体的实时姿态完全无关
                    Vec3 p = group.globalTransform(corner, true,
                            g -> g.baseRotation,
                            g -> Vec3.ZERO).offset();
                    float px = (float) -p.x;
                    float py = (float) -p.z;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                }
            }
        }
        return new ViewRect(minX, minY, maxX, maxY, line, solid, fit);
    }
}
