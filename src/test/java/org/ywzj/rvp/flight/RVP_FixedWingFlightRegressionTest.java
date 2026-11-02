package org.ywzj.rvp.flight;

import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;
import org.ywzj.rvp.flight.RVP_FlightRequest.*;
import static org.junit.jupiter.api.Assertions.*;

/** 生产控制器和执行器连续120秒回归，物理顺序依据本体和既有UAV回归模型。 */
class RVP_FixedWingFlightRegressionTest {
    /** 测试机质量，kg。 */ private static final double MASS_KG = 4074;
    /** 推力，N。 */ private static final double THRUST_N = 24444;
    /** 最低阻力系数。 */ private static final double DRAG_MIN = 16.296;
    /** 最高阻力系数。 */ private static final double DRAG_MAX = 24.444;
    /** 升阻比。 */ private static final double LIFT_TO_DRAG = 3;
    /** 升限，世界Y。 */ private static final double CEILING = 512;
    /** 负迎角边界，度。 */ private static final double AOA_MIN = -10;
    /** 正迎角边界，度。 */ private static final double AOA_MAX = 25;

    @Test void sharedControllerAndExecutorSustainDifferentParameterizedOrbitsFor120Seconds() {
        simulate("right", 164.1, 99, 20, 0, 1, false);
        simulate("left-high", 200, 200, 32, 0, -1, false);
        simulate("banked-moving", 115, 99, 30, 28, 1, true);
        simulate("steep-bank", 164.1, 99, 33, 55, 1, false);
        simulate("hold-heading", 164.1, 150, 32, 0, 1, false, true);
    }

    /** 同一生产入口使用不同目标、方向和高度，最后20秒统计稳定性。 */
    private static void simulate(String label, double initialY, double targetY, double initialSpeed,
                                 double initialBank, int direction, boolean moveCenter) {
        simulate(label, initialY, targetY, initialSpeed, initialBank, direction, moveCenter, false);
    }

    /** 同一测试物理环境验证绕圈和直线保持两种公共目标。 */
    private static void simulate(String label, double initialY, double targetY, double initialSpeed,
                                 double initialBank, int direction, boolean moveCenter, boolean holdCourse) {
        double x=100, y=initialY, z=0, yaw=0, pitch=0, bank=initialBank;
        double vx=0, vy=0, vz=initialSpeed/20, throttle=100, centerX=0;
        double minimumY=Double.POSITIVE_INFINITY, finalMinY=Double.POSITIVE_INFINITY, finalMaxY=-Double.MAX_VALUE;
        double maxRadialError=0, minSpeed=Double.POSITIVE_INFINITY, maxSpeed=0;
        int captured=0, aoaRecoveries=0;
        State state=State.empty();
        double previousBank=bank;
        RVP_FixedWingFlightController controller=new RVP_FixedWingFlightController();
        RVP_FlightExecutor executor=new RVP_FlightExecutor();
        RVP_FlightExecutorTest.TestPort port=new RVP_FlightExecutorTest.TestPort();
        for (int tick=0; tick<2400; tick++) {
            if (moveCenter && tick==1200) centerX=180;
            double scaleAir=scaleAir(y);
            double totalSpeed=Math.sqrt(vx*vx+vy*vy+vz*vz);
            double turnResponse=scaleAir*totalSpeed*0.4;
            double bankRate=RVP_FlightMath.wrap(bank-previousBank)*20;
            previousBank=bank;
            Observation observation=new Observation(port.id, port.dimension(), tick, port.generation,
                    new Vector(x,y,z), new Vector(vx*20,vy*20,vz*20), yaw,-pitch,bank,bankRate,throttle,false,
                    new Terrain(64,64,true,true));
            Airframe airframe=new Airframe(MASS_KG,THRUST_N,DRAG_MIN,DRAG_MAX,LIFT_TO_DRAG,scaleAir,AOA_MIN,AOA_MAX,
                    Math.min(2,turnResponse*2),Math.min(3,turnResponse*3),Math.min(4,turnResponse*4),130);
            var request=new RVP_FlightRequest("orbit",moveCenter && tick>=1200 ? 2 : 1,
                    holdCourse ? new HoldCourse(40) : new Orbit(new Vector(centerX,64,0),100,direction),
                    Altitude.worldY(targetY),Speed.safeCruise(1.25),
                    Limits.cruiseDefaults().withMaxBankDeg(40));
            Solution solution=controller.solve(observation,airframe,request,RVP_FlightTuning.smoothCruise(),state);
            if (solution.feedback().reason().equals("ANGLE_OF_ATTACK") && aoaRecoveries++ < 4) {
                Vector3f actualUp=rotation(yaw,pitch,bank).transform(new Vector3f(0,1,0));
                double actualAoA=Math.toDegrees(angleBetween(new Vector3f((float)vx,(float)vy,(float)vz),actualUp))-90;
                System.out.printf("[SHARED-FLIGHT-AOA] %s tick=%d actualAoA=%.2f pitchUp=%.2f bank=%.2f vy=%.2f%n",
                        label,tick,actualAoA,-pitch,bank,vy*20);
            }
            port.tick=tick;
            assertEquals(RVP_FlightExecutor.Result.APPLIED,executor.execute(port,solution.command()));
            state=solution.nextState();
            throttle=port.frame.throttle();
            // 实际执行器的预补偿值经本体inputStep消费后，再进入本体气动公式。
            float xInput=(float)(port.frame.pitchSetter()-port.pitchStep());
            float yawInput=(float)(port.frame.yawSetter()+port.yawStep());
            float rollInput=(float)(port.frame.rollSetter()-port.rollStep());
            Quaternionf rotation = rotation(yaw, pitch, bank);
            Vector3f forward = rotation.transform(new Vector3f(0, 0, 1));
            Vector3f up = rotation.transform(new Vector3f(0, 1, 0));
            Vector3f left = rotation.transform(new Vector3f(1, 0, 0));
            Vector3f air = new Vector3f((float) vx, (float) vy, (float) vz);
            double thrustAcceleration = THRUST_N * (throttle / 100.0) / MASS_KG / 400.0;
            air.add(new Vector3f(forward).mul((float) thrustAcceleration));
            double angleX = angleBetween(air, up) - Math.PI / 2.0;
            double dragK = (DRAG_MAX - DRAG_MIN) * Math.abs(Math.sin(angleX)) + DRAG_MIN;
            double airSpeed = air.length();
            double speedSquared = airSpeed * airSpeed * 400.0;
            double dragForce = speedSquared * dragK;
            air.normalize().mul((float) (airSpeed - dragForce / MASS_KG / 400.0));
            double aRaw = air.length();
            double aoaDeg = Math.toDegrees(angleX);
            double liftFactor;
            if (aoaDeg >= AOA_MIN && aoaDeg <= AOA_MAX) {
                liftFactor = LIFT_TO_DRAG * scaleAir + 2.0 * scaleAir * aoaDeg / AOA_MAX;
            } else {
                double exceed = aoaDeg > AOA_MAX ? aoaDeg - AOA_MAX : AOA_MIN - aoaDeg;
                liftFactor = LIFT_TO_DRAG * scaleAir * Math.exp(-exceed);
            }
            double liftForce = speedSquared * dragK * liftFactor;
            air.add(new Vector3f(up).mul((float) (liftForce / MASS_KG / 400.0)));
            double angleY = angleBetween(air, left) - Math.PI / 2.0;
            double forwardSpeed = air.dot(forward);
            double tailForce = forwardSpeed * forwardSpeed * 400.0 * 8.0 * dragK * Math.sin(angleY);
            air.add(new Vector3f(left).mul((float) (tailForce / MASS_KG / 400.0)));
            double controlDrag = (Math.abs(xInput) + Math.abs(yawInput) / 4.0 + Math.abs(rollInput) / 8.0) * DRAG_MIN;
            double controlForce = speedSquared * controlDrag;
            aRaw -= controlForce / MASS_KG / 400.0;
            air.normalize().mul((float) aRaw);

            // 依本体滚转→俯仰→偏航顺序更新姿态；每轴均按当前速度和气动 scaleAir 限制转速。
            Quaternionf nextRotation = rotation(yaw, pitch, bank);
            double turnRate = scaleAir * air.length() * 0.4;
            nextRotation.rotateZ((float) Math.toRadians(rollInput * Math.min(4.0, turnRate * 4.0)));
            nextRotation.rotateX((float) Math.toRadians(xInput * Math.min(2.0, turnRate * 2.0)));
            double yawRate = Math.min(3.0, turnRate * 3.0);
            double finalAngleY = angleBetween(air, left) - Math.PI / 2.0;
            double yawDamp = Math.min(1.0, 2.0 / Math.max(1.0E-6, Math.abs(Math.toDegrees(finalAngleY))));
            if (Math.abs(yawInput) > 0.0) {
                nextRotation.rotateY((float) Math.toRadians(yawInput * yawRate * yawDamp));
            } else {
                nextRotation.rotateY((float) (turnRate * -finalAngleY / 5.0));
            }
            Vector3f down = new Vector3f(up).negate();
            if (angleBetween(air, down) < Math.PI / 4.0) {
                double downwardSpeed = air.dot(down);
                double spinRate = Math.min(1.5, downwardSpeed);
                nextRotation.rotateY((float) (Math.PI / 72.0 * spinRate));
            }
            Vector3f euler = new Vector3f();
            nextRotation.getEulerAnglesYXZ(euler);
            pitch = Math.toDegrees(euler.x);
            yaw = Math.toDegrees(-euler.y);
            bank = Math.toDegrees(euler.z);

            // 按本体自由飞行分支积分；本模型不代替方块碰撞与实机验收。
            vx = air.x;
            // PhysicsEngine 对自由飞行体先处理升力 force，再减 G；升力不足的分支自由落体。
            double liftForcePerTick = liftForce / 400.0;
            vy = air.y;
            if (liftForcePerTick >= org.ywzj.vehicle.vehicle.PhysicsEngine.G * MASS_KG) {
                vy -= org.ywzj.vehicle.vehicle.PhysicsEngine.G;
            } else {
                vy -= org.ywzj.vehicle.vehicle.PhysicsEngine.G;
            }
            vz = air.z;
            x += vx;
            y += vy;
            z += vz;

            minimumY=Math.min(minimumY,y);
            if(tick>=2000) {
                finalMinY=Math.min(finalMinY,y); finalMaxY=Math.max(finalMaxY,y);
                double actualRadius=Math.hypot(x-centerX,z);
                if (!holdCourse) maxRadialError=Math.max(maxRadialError,Math.abs(actualRadius-solution.feedback().effectiveRadius()));
                double sampleSpeed=Math.sqrt(vx*vx+vz*vz)*20;
                minSpeed=Math.min(minSpeed,sampleSpeed); maxSpeed=Math.max(maxSpeed,sampleSpeed);
                if(solution.feedback().status()==Status.CAPTURED) captured++;
            }
        }
        String report=String.format("%s minY=%.2f finalY=[%.2f,%.2f] radialError=%.2f speed=[%.2f,%.2f] captured=%d/400",
                label,minimumY,finalMinY,finalMaxY,maxRadialError,minSpeed,maxSpeed,captured);
        System.out.println("[SHARED-FLIGHT-120S] "+report);
        assertTrue(minimumY>69,report);
        assertTrue(finalMinY>targetY-15 && finalMaxY<targetY+15,report);
        assertTrue(maxRadialError<35,report);
        assertTrue(minSpeed>18 && maxSpeed<65,report);
        assertTrue(captured>=300,report);
    }

    /** 本体高度空气影响系数。 */
    private static double scaleAir(double y) {
        return Math.max(0.001,y<64 ? 1 : Math.sqrt(Math.max(0,CEILING-y)/(CEILING-64)));
    }
    /** 本体向量夹角的有限值计算。 */
    private static double angleBetween(Vector3f a,Vector3f b) {
        return Math.acos(Mth.clamp((float)(a.dot(b)/Math.max(1.0E-12,a.length()*b.length())),-1f,1f));
    }
    /** 本体YXZ姿态约定，俯仰为本体低头正值。 */
    private static Quaternionf rotation(double yaw,double pitch,double bank) {
        return new Quaternionf().rotateY((float)Math.toRadians(-yaw))
                .rotateX((float)Math.toRadians(pitch)).rotateZ((float)Math.toRadians(bank));
    }
}
