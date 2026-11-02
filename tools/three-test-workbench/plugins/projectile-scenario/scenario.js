/** 当前 Java 场景的默认值；速度均为格/秒，时间为刻，20 刻等于 1 秒。 */
export const DEFAULT_SCENARIO = {
  schemaVersion: 1, // 当前场景格式，不接受历史键或格式迁移。
  id: 'custom_projectile', // 保存时的稳定文件名，只用小写字母、数字、下划线和连字符。
  name: '自定义弹体测试', // 工作台显示名称。
  description: '调整目标运动与发射初态，观察真实实体运行结果。', // 用例观察目的。
  weapon: '', // 权威包中的 rvp:武器文件名，交由用户选择。
  ticks: 600, // 最长运行刻数，范围 1..20000。
  position: [0, 164, 0], // 发射世界 X/Y/Z，必须高于地面。
  yaw: 0, // 航向角；0 朝 +Z、90 朝 -X。
  pitch: 0, // 俯仰角；正数抬头，范围 ±90 度。
  vehicleVelocity: [0, 0, 0], // 平台世界速度，仅武器开启速度继承时生效。
  initialVelocity: null, // 可选生成上下文初速，仍由生产 initFromWeapon 处理。
  parentRotation: null, // 可选母弹释放姿态：航向、俯仰、滚转，度。
  parentVelocity: [0, 0, 0], // 母弹释放世界速度，用于生产风向解析回退。
  carrierRotation: [0, 0, 0], // 发射平台航向、俯仰、滚转，用于冷发射轴转换。
  coldLaunchTicks: 0, // 挂架冷发射时长，不覆写武器点火延迟。
  coldLaunchVelocity: [0, -20, 0], // 挂架本地冷发射速度，沿用本体局部轴。
  deploymentVelocity: null, // 可选布撒初始分量，衰减仍由武器配置决定。
  smartFuseTarget: null, // 可选已接管的智能引信目标点，不模拟触发过程。
  seed: 721, // 固定随机种子；网页仅接受可无损表达的安全整数。
  target: null, // 可选目标初始世界坐标，null 表示无目标。
  targetVelocity: [0, 0, 0], // 目标脚本初速度。
  targetAcceleration: [0, 0, 0], // 目标恒定加速度，格/秒²。
  targetRadius: 2, // 几何停止球半径，格；不是引信半径。
  groundY: 64, // 平地或海底的世界高度。
  waterY: null, // 可选水平水面，必须高于地面。
  guidance: true, // 是否给真实执行器输入目标观测。
};

/** 仅接受有限数值，防止空输入、NaN 或字符串混入导出的 JSON。 */
const finite = value => typeof value === 'number' && Number.isFinite(value);
/** 可选三元组与必填三元组共用一份检查。 */
const vector = value => Array.isArray(value) && value.length === 3 && value.every(finite);

/** 和 Java 武器读取器一致地拒绝跳转、空层级及下划线备份目录。 */
export function validWeaponId(id) {
  return typeof id === 'string' && /^rvp:[a-z0-9_./-]+$/.test(id)
    && id.slice(4).split('/').every(part => part && part !== '.' && part !== '..' && !part.startsWith('_'));
}

/** 填充当前字段的默认值并校验；不修改输入，也不默默丢弃未知字段。 */
export function normalizeScenario(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || input.schemaVersion !== 1) throw new Error('场景仅接受 schemaVersion=1');
  for (const key of Object.keys(input)) if (!Object.hasOwn(DEFAULT_SCENARIO, key)) throw new Error(`未知场景字段：${key}`);
  const scene = { ...structuredClone(DEFAULT_SCENARIO), ...structuredClone(input) };
  if (typeof scene.id !== 'string' || !/^[a-z0-9][a-z0-9_-]*$/.test(scene.id) || scene.id.length > 120) throw new Error('id 须为 1..120 个小写字母、数字、下划线或连字符，首位为字母或数字');
  if (typeof scene.name !== 'string' || !scene.name.trim() || typeof scene.description !== 'string') throw new Error('请填写名称和有效的测试说明');
  if (!validWeaponId(scene.weapon)) throw new Error('weapon 须为权威包中的 rvp:武器文件名，不含 .json');
  if (!Number.isInteger(scene.ticks) || scene.ticks < 1 || scene.ticks > 20000) throw new Error('运行时长 ticks 须为 1..20000 刻');
  if (!Number.isInteger(scene.coldLaunchTicks) || scene.coldLaunchTicks < 0 || scene.coldLaunchTicks > 20000) throw new Error('冷发射时长须为 0..20000 刻');
  for (const key of ['position', 'vehicleVelocity', 'parentVelocity', 'carrierRotation', 'coldLaunchVelocity', 'targetVelocity', 'targetAcceleration']) {
    if (!vector(scene[key])) throw new Error(`${key} 必须是三个有限数值`);
  }
  for (const key of ['initialVelocity', 'parentRotation', 'deploymentVelocity', 'smartFuseTarget', 'target']) {
    if (scene[key] !== null && !vector(scene[key])) throw new Error(`${key} 必须是三个有限数值或 null`);
  }
  if (!finite(scene.yaw) || !finite(scene.pitch) || Math.abs(scene.pitch) > 90) throw new Error('航向须为有限角度，俯仰须在 ±90 度内');
  if (!finite(scene.groundY) || scene.position[1] <= scene.groundY) throw new Error('发射点 Y 必须高于地面 groundY');
  if (scene.waterY !== null && (!finite(scene.waterY) || scene.waterY <= scene.groundY)) throw new Error('水面 waterY 必须高于地面');
  if (!finite(scene.targetRadius) || scene.targetRadius <= 0) throw new Error('目标停止球半径必须大于 0');
  if (!Number.isSafeInteger(scene.seed)) throw new Error('随机种子须为 JavaScript 可无损表达的整数（绝对值不超过 9007199254740991）');
  if (typeof scene.guidance !== 'boolean') throw new Error('guidance 必须是 true 或 false');
  if (scene.target) {
    // 调用同一目标解析式，提早发现最长时刻发生的数值溢出。
    targetAt(scene, scene.ticks);
  }
  return scene;
}

/** 与 RVP_ProjectileEntitySimulation.updateTarget 一致：p(t)=p₀+v₀t+½at²，不积分导弹运动。 */
export function targetAt(scene, tick) {
  if (!scene.target) return null;
  const seconds = tick / 20;
  const position = scene.target.map((p, i) => p + scene.targetVelocity[i] * seconds + 0.5 * scene.targetAcceleration[i] * seconds * seconds);
  const velocity = scene.targetVelocity.map((v, i) => v + scene.targetAcceleration[i] * seconds);
  if (!position.every(finite) || !velocity.every(finite)) throw new Error('目标运动超出有限数值范围，请减小速度、加速度或时长');
  return { position, velocity };
}

/** Minecraft 航向和正数抬头的俯仰，转换为世界 X/Y/Z 速度输入。 */
export function velocityFromAngles(yaw, pitch, speed) {
  const y = yaw * Math.PI / 180, p = pitch * Math.PI / 180;
  return [-Math.sin(y) * Math.cos(p) * speed, Math.sin(p) * speed, Math.cos(y) * Math.cos(p) * speed].map(v => Math.abs(v) < 1e-12 ? 0 : v);
}

/** 世界速度分量转换成可读角度；零速度不猜方向。 */
export function anglesFromVelocity(velocity) {
  const speed = Math.hypot(...velocity);
  return { speed, yaw: speed ? (Math.atan2(-velocity[0], velocity[2]) * 180 / Math.PI || 0) : 0,
    pitch: speed ? Math.atan2(velocity[1], Math.hypot(velocity[0], velocity[2])) * 180 / Math.PI : 0 };
}

/** 单例输出放独立目录，避免改写完整回放的索引；调用者须先保存场景。 */
export function simulationCommand(scene) {
  if (!/^[a-z0-9][a-z0-9_-]*$/.test(scene.id)) throw new Error('请先填写有效 id');
  return `./gradlew projectileSimulation "-PprojectileScenario=src/test/resources/projectile-simulation/${scene.id}.json" "-PprojectileOutput=build/projectile-simulation-editor"`;
}

/** 只生成几何观察记录：发射点静止、目标按脚本运动，绝不伪造导弹未来轨迹。 */
export function buildPreviewReplay(input) {
  const scene = normalizeScenario(input);
  const end = targetAt(scene, scene.ticks), span = scene.target ? Math.max(40, Math.hypot(...scene.target.map((v, i) => v - scene.position[i])),
    Math.hypot(...end.position.map((v, i) => v - scene.position[i]))) : 100;
  const size = Math.max(2, span / 160);
  const objects = [{ id: 'launch', name: '发射点 / 机头方向', renderer: 'projectile-scenario:arrow', color: '#f2bd72', size: [size, size, size] }];
  if (scene.initialVelocity) objects.push({ id: 'velocity', name: '初速输入方向（非实体最终初速）', renderer: 'projectile-scenario:arrow', color: '#76d9c2', size: [size, size, size] });
  if (scene.target) objects.push({ id: 'target', name: '目标运动预览', renderer: 'sphere', color: '#e89ba6', size: Array(3).fill(scene.targetRadius * 2) });
  const velocityAngles = anglesFromVelocity(scene.initialVelocity ?? [0, 0, 0]);
  const frames = Array.from({ length: scene.ticks + 1 }, (_, tick) => {
    const state = targetAt(scene, tick);
    return { tick, objects: objects.map(object => ({ id: object.id, active: true,
      // 工作台对象以底部为原点；目标脚本给球心，按真实 Java 回放同样减去半径。
      position: object.id === 'target' ? state.position.map((value, axis) => axis === 1 ? value - scene.targetRadius : value) : scene.position,
      yaw: object.id === 'velocity' ? velocityAngles.yaw : object.id === 'launch' ? scene.yaw : 0,
      pitch: object.id === 'velocity' ? velocityAngles.pitch : object.id === 'launch' ? scene.pitch : 0, roll: 0,
      values: object.id === 'target' ? { velocity: state.velocity, preview: '目标恒加速脚本；不是导弹仿真' }
        : { preview: '发射初态示意；位置保持不动', initialVelocity: scene.initialVelocity, weapon: scene.weapon },
    })) };
  });
  return { format: 'rvp-test-replay', schemaVersion: 1, id: scene.id, name: `${scene.name} · 几何预览`,
    description: '仅展示目标运动和发射初态；导弹真实飞行需运行 Java 仿真。', tickRate: 20, groundY: scene.groundY,
    objects, obstacles: [], frames, events: [], summary: [{ label: '预览时长（秒）', value: scene.ticks / 20 }],
    checks: [{ name: '几何预览', status: 'info', message: '没有执行实体飞行或自动命中断言' }],
    coverage: ['当前场景的目标恒加速运动、机头方向与可选初速输入。'],
    limitations: ['没有计算导弹轨迹、制导、推力、碰撞或冷发射后的真实速度。', 'initialVelocity 仍由真实实体初始化处理，不保证等于实体最终初速。'] };
}
