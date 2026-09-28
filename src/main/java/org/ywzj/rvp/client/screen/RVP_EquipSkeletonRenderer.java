package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RotatableUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 辅助设备面板左上角俯视图（原生 GuiGraphics 绘制，纯客户端）。
 *
 * <p><b>渲染路径（2026-09-26 定版）</b>：AUI 屏 {@code super.render} 之后原生 scissor 绘制。
 * 此前 div 路线受"AUI div 只能画轴对齐矩形"硬约束被迫 bind-pose 静态；原生路线解除该限制后，
 * 画法整体复刻本体观瞄俯视图 {@code VehicleScopeOverlay.renderVehicleHeading + renderCubeOBB}：
 * 活体 {@link VehicleCubeOBB} 逐 cube 投影（{@code positionO→position} partialTick 插值中心
 * − 插值车体位置 → 车体本地轴 axisX/axisZ 点积，轴含车体俯仰/侧倾），全局
 * {@code mulPose(180 − 炮塔相对偏航)} 炮塔朝上、转塔全图跟转。视空间比例沿用本体的
 * 10 px/方块（半宽 = width×5），画布适配缩放由 {@link #render} 统一完成。</p>
 *
 * <p><b>rvp 朝向修正内联</b>：OBB 俯视朝向取 {@code VehicleScopeOverlayHeadingFixMixin}
 * 同款数学（slerp(rotationO→rotation) 的 OBB X 轴对车体本地轴投影取 atan2）——该 mixin 只对
 * 本体观瞄 overlay 生效，面板是独立绘制路径必须自带，否则炮塔/ERA 组等挂枢轴父骨的
 * OBB 朝向错位。无旋转四元数时回退 −骨链 baseRotation 的 EulerYXZ.y（防御路径：
 * 本体 lerpZRotDiff 对无旋转 cube 恒返回 0，两口径一致）。</p>
 *
 * <p><b>显示分层（面板定版配色，与旧 div 版一致）</b>：车体结构 cube + 非模块部件 =
 * 白实心块拼整车轮廓（带深灰描边，邻接块轮廓可辨）；骨模块部件 = 彩色空心线框
 * （ERA 绿/设备骨蓝，失效红查 {@link RVP_ClientBoneModuleState}）。
 * 全部 cube（含 ERA/特殊设备线框，用户定版）按局部高度（车体 up 轴分量）升序分带绘制——
 * 高块后画盖住低块（车体盖履带线框），同带先填充后描边/线框。
 * 炮管等长条部件不参与缩放包络（fit=false）仍绘制。已知边界：APS 扇区锥未画。</p>
 *
 * <p><b>取景冻结（2026-09-29 闪烁根治）</b>：闪烁根因 = 旧版每帧用插值实时数据重算
 * fit cube 的旋转敏感 AABB 包络 → {@code scale} 与居中平移每帧变化——炮塔回转/悬架
 * 微振/行驶姿态变化时整个俯视图逐帧缩放+平移跳动（"帧间布局跳动"；本体观瞄图同数据
 * 不闪，因其固定 10px/格、锚定载具中心，无自适应包络）。修复：包络改<b>角度无关半径</b>
 * {@code max(√(cx²+cy²)+√(hw²+hd²))}——画布空间中整车随炮塔刚性旋转，角点到原点距离
 * 恒定，任意炮塔角/车体朝向都不裁切；半径按"载具+画布尺寸"缓存且<b>只增不减</b>
 * （{@link Framing}，由面板屏持有实例、开屏自然重置），居中锚定载具原点（本体观瞄同款
 * 锚点选择）。静止时帧间像素级全等；炮塔扫到更远处才一次性放大一档，无连续呼吸。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_EquipSkeletonRenderer {

    /**
     * 俯视图取景缓存（由调用方面板屏持有实例——屏对象每次开屏新建，缓存自然重置）：
     * key = 载具 entityId 与画布像素尺寸的组合（任一变化即重算）；
     * radiusPx = 角度无关包络半径（10px/格 视空间，只增不减——包络变大立即跟随、
     * 变小不收缩，避免任何形式的连续缩放呼吸）。
     */
    public static final class Framing {
        /** 上次取景使用的 key（载具 id ^ 画布宽高组合）；初始 0 表示无缓存。 */
        private long key;
        /** 冻结的包络半径（视空间 px，10px/格）。 */
        private float radiusPx = -1.0f;
    }

    /**
     * 单个 OBB 的俯视图绘制数据（视空间 = 本体俯视图坐标系，10 px/方块）：
     * cx/cy = cube 中心在车体本地 (axisX, axisZ) 基下的投影，height = 中心沿车体 up 轴的
     * 局部高度（白层深度排序键：高者后画、盖住低者），hw/hd = 半宽/半深（px），
     * headingDeg = 俯视朝向（度），solid = 白实心/彩色线框，fit = 是否参与缩放包络。
     */
    private record ViewCube(float cx, float cy, float height, int hw, int hd, float headingDeg, int color, boolean solid, boolean fit) {
    }

    /** 普通部件 OBB：白色实心块。 */
    private static final int NORMAL_SOLID = 0xFFFFFFFF;
    /** 白实心块描边：深灰（与面板 HTML 图例色块描边 #141517 同色），邻接白块靠描边区分轮廓。 */
    private static final int NORMAL_OUTLINE = 0xFF141517;
    /** ERA 模块生效：绿色线框。 */
    private static final int ERA_OK_LINE = 0xFF69AD45;
    /** 设备骨模块生效：蓝色线框。 */
    private static final int DEV_OK_LINE = 0xFF4C8BE8;
    /** 受损档线框色（两档炮管）：黄。 */
    private static final int DAMAGED_LINE = 0xFFD4C44F;
    /** 模块失效：红色线框。 */
    private static final int DEAD_LINE = 0xFFD45B50;
    /** 本体俯视图比例：1 方块 = 10 px（本体 renderCubeOBB 的 offset×10 / 半宽 width×5）。 */
    private static final float PX_PER_BLOCK = 10.0f;
    /** 白层深度排序的同高判定容差（px）：对称块的同高值经四元数换算有 ~1e-6 级浮点差，须归入同一带。 */
    private static final float HEIGHT_BAND_EPSILON = 0.01f;

    private RVP_EquipSkeletonRenderer() {
    }

    /**
     * 在面板俯视图画布（GuiGraphics 坐标 [x0,y0]-[x1,y1]）内绘制整车俯视图。
     * 调用时机 = AUI {@code super.render} 之后（AUI 无 post-render 钩子，不会覆盖原生内容）；
     * scissor 与深度抬升（translate z=400 盖过 AUI 元素深度）由调用方处理。
     *
     * @param modules 骨名 → 模块类型分类表（面板 10 帧节流缓存；失效态仍逐帧查状态表）
     * @param framing 取景缓存（面板屏实例持有，跨帧冻结缩放——闪烁根治见类注释）
     */
    public static void render(GuiGraphics guiGraphics, AbstractVehicle vehicle,
                              Map<String, Set<BoneModuleType>> modules,
                              int x0, int y0, int x1, int y1, float partialTick, Framing framing) {
        List<ViewCube> cubes = buildCubes(vehicle, modules, partialTick);
        if (cubes.isEmpty()) {
            return;
        }
        // 取景冻结（2026-09-29 闪烁根治）：包络半径用角度无关口径实时计算，但只增不减地
        // 冻结在 framing 缓存里——帧间 scale 恒定，消灭"每帧重算包络 → 缩放/居中跳动"。
        // 角度无关依据：画布空间中整车随炮塔刚性旋转（180−炮塔偏航全局旋转），cube 角点到
        // 画布原点（= 载具原点）的距离恒定，故 √(cx²+cy²)+√(hw²+hd²) 覆盖任意旋转姿态；
        // 炮管组 cube 中心随炮塔在车体本地系绕塔轴公转、到原点距离会变 → 只增不减策略
        // 让扫到更远包络时一次性放大一档后再次冻结。
        float canvasW = x1 - x0;
        float canvasH = y1 - y0;
        // 取景缓存 key：载具实体 + 画布像素尺寸（换车/窗口缩放/布局变化都会换 key 重算）
        long key = (long) vehicle.getId() << 32 ^ (long) (x1 - x0) << 16 ^ (long) (y1 - y0);
        float liveRadius = 0.0f;
        for (ViewCube cube : cubes) {
            if (!cube.fit()) {
                continue;
            }
            float centerDist = Mth.sqrt(cube.cx() * cube.cx() + cube.cy() * cube.cy());
            float cornerRadius = Mth.sqrt(cube.hw() * (float) cube.hw() + cube.hd() * (float) cube.hd());
            liveRadius = Math.max(liveRadius, centerDist + cornerRadius);
        }
        liveRadius = Math.max(liveRadius, 0.01f);
        if (framing.radiusPx < 0.0f || framing.key != key) {
            // 无缓存 / 载具或画布尺寸变化：重置为当前实测半径
            framing.key = key;
            framing.radiusPx = liveRadius;
        } else if (liveRadius > framing.radiusPx) {
            // 只增不减：包络实测变大（炮塔扫到更远）立即跟随一次，随后再次冻结
            framing.radiusPx = liveRadius;
        }
        double scale = Math.min((canvasW - 12) / (2f * framing.radiusPx),
                (canvasH - 12) / (2f * framing.radiusPx));
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            // 画布中心（= 载具原点锚点，本体观瞄同款锚定载具中心而非足印质心）
            // → 炮塔朝上整体旋转（本体 renderVehicleHeading 同款 180−zRot，转塔全图跟转）
            // → 冻结缩放（无每帧包络居中平移——闪烁根因）
            poseStack.translate((x0 + x1) / 2f, (y0 + y1) / 2f, 0f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(180 - turretZRotDeg(vehicle, partialTick)));
            poseStack.scale((float) scale, (float) scale, (float) scale);
            // 全部 cube（含 ERA/特殊设备线框，用户定版）按局部高度升序"分带"绘制：
            // 低带先画、高带后画自然盖住低带（车体比履带高 → 履带被车体遮住）；
            // 同带内先全部填充（白实心轮廓）再全部描边/线框（白块深灰描边 + 模块彩色线框），
            // 带内邻接块共边轮廓保留。
            cubes.sort(Comparator.comparingDouble(ViewCube::height));
            int index = 0;
            while (index < cubes.size()) {
                float bandHeight = cubes.get(index).height();
                int bandEnd = index;
                while (bandEnd < cubes.size()
                        && Math.abs(cubes.get(bandEnd).height() - bandHeight) <= HEIGHT_BAND_EPSILON) {
                    bandEnd++;
                }
                for (int k = index; k < bandEnd; k++) {
                    if (cubes.get(k).solid()) {
                        drawCubeFill(guiGraphics, cubes.get(k));
                    }
                }
                for (int k = index; k < bandEnd; k++) {
                    ViewCube cube = cubes.get(k);
                    drawCubeFrame(guiGraphics, cube, cube.solid() ? NORMAL_OUTLINE : cube.color());
                }
                index = bandEnd;
            }
        }
        poseStack.popPose();
    }

    /** 实心块绘制：中心平移 + 朝向旋转后 fill（白实心整车轮廓）。 */
    private static void drawCubeFill(GuiGraphics guiGraphics, ViewCube cube) {
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            poseStack.translate(cube.cx(), cube.cy(), 0f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(cube.headingDeg()));
            guiGraphics.fill(-cube.hw(), -cube.hd(), cube.hw(), cube.hd(), cube.color());
        }
        poseStack.popPose();
    }

    /** 空心线框绘制：中心平移 + 朝向旋转后 drawRectByCorner（本体观瞄同款观感；白块描边与模块线框共用）。 */
    private static void drawCubeFrame(GuiGraphics guiGraphics, ViewCube cube, int color) {
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            poseStack.translate(cube.cx(), cube.cy(), 0f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(cube.headingDeg()));
            RenderHelper.drawRectByCorner(guiGraphics,
                    -cube.hw(), cube.hw(), -cube.hd(), cube.hd(), color, 1);
        }
        poseStack.popPose();
    }

    /**
     * 构建俯视图全部 cube 数据（覆盖整车结构，本体 renderVehicleHeading 同源）：
     * 车体结构 cube + 全部部件（rvp 载具武器站不跳过，语义对齐
     * {@code VehicleScopeOverlayWeaponUnitsMixin}，含各站子武器）白实心，
     * 骨模块部件彩色线框。
     */
    private static List<ViewCube> buildCubes(AbstractVehicle vehicle, Map<String, Set<BoneModuleType>> modules, float partialTick) {
        List<ViewCube> out = new ArrayList<>();
        // 车体插值位置与本地轴（本体 renderVehicleHeading 同款：yaw-pitch-roll 四元数，含俯仰/侧倾）
        Vec3 vehiclePos = new Vec3(Mth.lerp(partialTick, vehicle.xo, vehicle.getX()),
                Mth.lerp(partialTick, vehicle.yo, vehicle.getY()),
                Mth.lerp(partialTick, vehicle.zo, vehicle.getZ()));
        Vector3f axisX = new Vector3f(1, 0, 0);
        Vector3f axisY = new Vector3f(0, 1, 0);
        Vector3f axisZ = new Vector3f(0, 0, 1);
        Quaternionf vehicleRot = new Quaternionf()
                .rotateY((float) Math.toRadians(-vehicle.getViewYRot(partialTick)))
                .rotateX((float) Math.toRadians(vehicle.getViewXRot(partialTick)))
                .rotateZ((float) Math.toRadians(vehicle.getViewZRot(partialTick)));
        vehicleRot.transform(axisX);
        vehicleRot.transform(axisY);
        vehicleRot.transform(axisZ);
        int entityId = vehicle.getId();
        // 第一层·车体结构 cube：白实心（本体画法含 getVehicleCubeOBBs 车体层，保证轮廓完整）
        for (VehicleCubeOBB cube : vehicle.getVehicleCubeOBBs()) {
            addCube(out, cube, vehiclePos, axisX, axisY, axisZ, partialTick, NORMAL_SOLID, true, true);
        }
        // 第一层·非模块部件：白实心（模块骨留到第二层画线框）
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            String bone = partUnit.getId();
            boolean isBarrel = bone.contains("barrel");
            if (modules.containsKey(bone)) {
                continue;
            }
            for (VehicleCubeOBB cube : partUnit.getPartCubeOBBs()) {
                // [RVP] 炮管骨模块（2026-09-28）：炮管骨已配置 bone_modules 时其炮管组 cube
                // 留给第二层画彩色线框（与其它模块骨一致：无白色实心填充）
                if (partUnit instanceof WeaponUnit weaponUnit && isStationBarrelModule(weaponUnit, modules)
                        && org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.isBarrelGroupCube(cube, weaponUnit)) {
                    continue;
                }
                addCube(out, cube, vehiclePos, axisX, axisY, axisZ, partialTick, NORMAL_SOLID, true, !isBarrel);
            }
            // 子武器 cube 一并画入轮廓（本体对 rvp 载具经 WeaponUnitsMixin 同样全部绘制）
            if (partUnit instanceof WeaponUnit weaponUnit) {
                for (WeaponUnit subWeaponUnit : weaponUnit.getSubWeaponUnits()) {
                    boolean subIsBarrel = subWeaponUnit.getId().contains("barrel");
                    for (VehicleCubeOBB cube : subWeaponUnit.getPartCubeOBBs()) {
                        // 子站炮管骨模块同款让位（如 cssa5 的 missile 子站 → missile_barrel 骨）
                        if (isStationBarrelModule(subWeaponUnit, modules)
                                && org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.isBarrelGroupCube(cube, subWeaponUnit)) {
                            continue;
                        }
                        addCube(out, cube, vehiclePos, axisX, axisY, axisZ, partialTick, NORMAL_SOLID, true, !subIsBarrel);
                    }
                }
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
            int line = destroyed ? DEAD_LINE : (types.contains(BoneModuleType.ERA) ? ERA_OK_LINE : DEV_OK_LINE);
            for (VehicleCubeOBB cube : partUnit.getPartCubeOBBs()) {
                addCube(out, cube, vehiclePos, axisX, axisY, axisZ, partialTick, line, false, true);
            }
        }
        // [RVP] 第二层·武器站炮管骨模块（2026-09-28）：炮管骨（structure_bone+"_barrel"）不是
        // 部件 id、上循环遍历不到——此处按 manager 组判定把炮管组 cube 单独画设备色线框
        //（蓝 = 存活，红 = BARREL 失效）
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            String structureBone = weaponUnit.getData() == null ? null : weaponUnit.getData().getStructureBone();
            if (structureBone == null || structureBone.isBlank()) {
                continue;
            }
            String barrelBone = structureBone + "_barrel";
            Set<BoneModuleType> barrelTypes = modules.get(barrelBone);
            if (barrelTypes == null || !barrelTypes.contains(BoneModuleType.BARREL)) {
                continue;
            }
            boolean barrelBurst = barrelTypes.stream().anyMatch(
                    type -> type == BoneModuleType.BARREL
                            && !RVP_ClientBoneModuleState.isModuleActive(entityId, barrelBone, type));
            boolean barrelDamaged = barrelTypes.stream().anyMatch(
                    type -> type == BoneModuleType.BARREL_DAMAGED
                            && !RVP_ClientBoneModuleState.isModuleActive(entityId, barrelBone, type));
            // 三色（2026-09-28 两档炮管）：彻底损坏红 / 受损黄 / 存活蓝
            int line = barrelBurst ? DEAD_LINE : (barrelDamaged ? DAMAGED_LINE : DEV_OK_LINE);
            for (VehicleCubeOBB cube : weaponUnit.getPartCubeOBBs()) {
                if (org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.isBarrelGroupCube(cube, weaponUnit)) {
                    addCube(out, cube, vehiclePos, axisX, axisY, axisZ, partialTick, line, false, true);
                }
            }
        }
        return out;
    }

    /**
     * 武器站的炮管骨是否配置了 bone_modules（供第一层让位判断）：炮管骨键存在即视为模块骨
     *（不限定类型——与命中判定同口径，见 manager.resolveBarrelBone 的骨名约定）。
     */
    private static boolean isStationBarrelModule(WeaponUnit weaponUnit, Map<String, Set<BoneModuleType>> modules) {
        String structureBone = weaponUnit.getData() == null ? null : weaponUnit.getData().getStructureBone();
        return structureBone != null && !structureBone.isBlank()
                && modules.containsKey(structureBone + "_barrel");
    }

    /**
     * 单 OBB → 视空间数据：中心投影（本体 renderCubeOBB 同款）+ 局部高度（深度排序键）
     * + rvp 真实朝向修正（HeadingFix 同款）。
     */
    private static void addCube(List<ViewCube> out, VehicleCubeOBB cubeOBB, Vec3 vehiclePos,
                                Vector3f axisX, Vector3f axisY, Vector3f axisZ, float partialTick,
                                int color, boolean solid, boolean fit) {
        if (cubeOBB.group == null) {
            return; // 无骨链信息（理论不发生）：跳过
        }
        Vec3 cubePos = new Vec3(Mth.lerp(partialTick, cubeOBB.positionO.x, cubeOBB.position.x),
                Mth.lerp(partialTick, cubeOBB.positionO.y, cubeOBB.position.y),
                Mth.lerp(partialTick, cubeOBB.positionO.z, cubeOBB.position.z));
        Vec3 offset = cubePos.subtract(vehiclePos);
        float cx = (float) ((offset.x * axisX.x() + offset.y * axisX.y() + offset.z * axisX.z()) * PX_PER_BLOCK);
        float cy = (float) ((offset.x * axisZ.x() + offset.y * axisZ.y() + offset.z * axisZ.z()) * PX_PER_BLOCK);
        // 局部高度 = 世界偏移在车体 up 轴上的分量（等价 cube 本地 y，与投影同轴，俯仰/侧倾下仍正确）
        float height = (float) ((offset.x * axisY.x() + offset.y * axisY.y() + offset.z * axisY.z()) * PX_PER_BLOCK);
        int hw = (int) Math.max(cubeOBB.width * (PX_PER_BLOCK / 2), 1);
        int hd = (int) Math.max(cubeOBB.depth * (PX_PER_BLOCK / 2), 1);
        out.add(new ViewCube(cx, cy, height, hw, hd, headingDeg(cubeOBB, axisX, axisZ, partialTick), color, solid, fit));
    }

    /**
     * OBB 俯视朝向（度，车体本地系）：slerp(rotationO→rotation) 的 OBB X 轴对
     * axisX/axisZ 投影取 atan2——内联自 {@code VehicleScopeOverlayHeadingFixMixin}；
     * 无旋转数据时回退 −骨链 baseRotation 的 EulerYXZ.y（防御路径，正常结构模型 rotation 恒存在）。
     */
    private static float headingDeg(VehicleCubeOBB cubeOBB, Vector3f axisX, Vector3f axisZ, float partialTick) {
        Quaternionf rotation = null;
        if (cubeOBB.rotationO != null && cubeOBB.rotation != null) {
            rotation = new Quaternionf(cubeOBB.rotationO).slerp(cubeOBB.rotation, partialTick);
        } else if (cubeOBB.rotation != null) {
            rotation = new Quaternionf(cubeOBB.rotation);
        } else if (cubeOBB.selfRot() != null) {
            rotation = new Quaternionf(cubeOBB.selfRot());
        }
        float headingDeg = (float) -Math.toDegrees(cubeOBB.group.baseRotation.getEulerAnglesYXZ(new Vector3f()).y);
        if (rotation != null) {
            Vector3f obbXAxis = rotation.transform(new Vector3f(1, 0, 0));
            float projectedX = obbXAxis.x() * axisX.x() + obbXAxis.y() * axisX.y() + obbXAxis.z() * axisX.z();
            float projectedZ = obbXAxis.x() * axisZ.x() + obbXAxis.y() * axisZ.y() + obbXAxis.z() * axisZ.z();
            if (Math.abs(projectedX) >= 1.0e-5f || Math.abs(projectedZ) >= 1.0e-5f) {
                headingDeg = (float) Math.toDegrees(Math.atan2(projectedZ, projectedX));
            }
        }
        return headingDeg;
    }

    /**
     * 炮塔相对车体的实时偏航（度，partialTick 插值）——复刻本体
     * {@code VehicleScopeOverlay.lerpZRotDiff(RotatableUnit)}（私有方法无法直调）。
     * 本地玩家无可旋转操作员位时返回 0（图固定车首朝上）。
     */
    private static float turretZRotDeg(AbstractVehicle vehicle, float partialTick) {
        Player player = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.getPlayer();
        if (player == null) {
            return 0;
        }
        PartUnit<?> operator = vehicle.getOwnOperatorUnit(player);
        if (!(operator instanceof RotatableUnit<?> rotatableUnit)) {
            return 0;
        }
        float zRot = Mth.wrapDegrees(rotatableUnit.worldRot().y - vehicle.getYRot());
        float zRotO = Mth.wrapDegrees(rotatableUnit.worldRot(rotatableUnit.xRotO, rotatableUnit.yRotO).y - vehicle.getYRot());
        float zRotDiff = Mth.wrapDegrees(zRot - zRotO);
        return Mth.lerp(partialTick, zRot - zRotDiff, zRot);
    }
}
