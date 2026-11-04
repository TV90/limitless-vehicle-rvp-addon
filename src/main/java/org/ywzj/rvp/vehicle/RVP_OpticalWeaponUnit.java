package org.ywzj.rvp.vehicle;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** 可越过竖直方向的观瞄站；保留本体火控、稳定器、限速和同步，只修正角度分支。 */
public class RVP_OpticalWeaponUnit extends WeaponUnit {
    /** 本体稳定器在普通旋转后反解上一刻视线；极点方位必须取刻首角，避免一刻叠加两次转速。 */
    private boolean resolvingTickRotation;

    /** 使用本体武器站配置初始化，不新增另一套数据字段。 */
    public RVP_OpticalWeaponUnit(int index, AbstractVehicle vehicle, WeaponUnitData data) {
        super(index, vehicle, data);
    }

    /** 只标记稳定器的解角上下文；真实旋转、动力和受损判断仍执行本体实现。 */
    @Override
    protected void tickRot() {
        resolvingTickRotation = true;
        try {
            // 调用本体旋转链，RotatableUnit 在刻首保存 xRotO/yRotO，随后稳定器仍需使用这一参考。
            super.tickRot();
        } finally {
            resolvingTickRotation = false;
        }
    }

    /** 本体稳定器和瞄准都经此公开虚方法解角，统一保留可达且连续的姿态。 */
    @Override
    public Vec2 worldVecToLocalRot(Vec3 worldDirection) {
        // 调用本体逆基座变换，左右/俯仰仍相对于这座吊舱，而不是假定机体水平。
        Vec3 localDirection = worldVecToLocalVec(worldDirection);
        // 调用连续分支求解；限位由本体随后执行，不能为躲避止挡把当前姿态翻到另一侧。
        return RVP_OpticalSightMath.localAngles(localDirection,
                resolvingTickRotation ? xRotO : getXRot(), resolvingTickRotation ? yRotO : getYRot(),
                xRotMin - xSelfRot, xRotMax - xSelfRot, yRotMin - ySelfRot, yRotMax - ySelfRot);
    }

    /** 以画面轴累加普通观瞄输入，只写目标角；本体继续负责实际转速、稳定器和网络同步。 */
    public Quaterniond turnInView(Quaterniondc view, double yawDelta, double pitchDelta) {
        // 调用本体目标方向，不能每次从尚未追上的实际角重新开始，且包含炮管自身偏角。
        Vec3 requested = worldVec(getXAimRot() + xBarrelSelfRot, getYAimRot() + yBarrelSelfRot);
        // 调用完整姿态输入，过正下方后左右鼠标仍沿画面左右，不再直接加到机械偏航轴。
        Quaterniond rotated = RVP_OpticalSightMath.turnInView(view, requested, yawDelta, pitchDelta);
        // 调用本体逆基座变换与双分支解角，将画面请求还原到这座吊舱的机械角。
        Vec2 local = RVP_OpticalSightMath.localAngles(worldVecToLocalVec(RVP_OpticalSightMath.direction(rotated)),
                getXAimRot() + xBarrelSelfRot, getYAimRot() + yBarrelSelfRot,
                xRotMin - xSelfRot + xBarrelSelfRot, xRotMax - xSelfRot + xBarrelSelfRot,
                yRotMin - ySelfRot + yBarrelSelfRot, yRotMax - ySelfRot + yBarrelSelfRot);
        // 调用公开目标角写入，机械止挡仍采用原配置，不扩大转角权限。
        setXAimRot(Math.max(xRotMin - xSelfRot, Math.min(xRotMax - xSelfRot, local.x - xBarrelSelfRot)));
        setYAimRot(Math.max(yRotMin - ySelfRot, Math.min(yRotMax - ySelfRot, local.y - yBarrelSelfRot)));
        updateWorldAimVec();
        // 调用最短对齐，若触及止挡则让下一次输入从实际可达请求继续，反向时不积压越界输入。
        return RVP_OpticalSightMath.followDirection(rotated,
                worldVec(getXAimRot() + xBarrelSelfRot, getYAimRot() + yBarrelSelfRot));
    }

    /** 与本体 worldRot 返回的视线配套，避免原实现以反号俯仰计算横滚。 */
    @Override
    public float worldZRot() {
        // 调用本体方向入口，保留炮管自身偏角与相机逻辑视线的一致性。
        Vec2 direction = worldRot(getXRot() + xBarrelSelfRot, getYRot() + yBarrelSelfRot);
        // 调用本体基座姿态，正号俯仰与实际结构旋转一致。
        Quaternionf rotation = baseRot().rotateY((float) Math.toRadians(-getYRot() - yBarrelSelfRot))
                .rotateX((float) Math.toRadians(getXRot() + xBarrelSelfRot));
        // 调用完整姿态的横滚解析，使逻辑相机、选点射线与实际镜头保持同向。
        return RVP_OpticalSightMath.roll(rotation, direction.x, direction.y);
    }
}
