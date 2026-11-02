import { createProjectile } from './body.js';

/** 展示值保留四位小数，原始帧仍保留 Java 输出精度。 */
const displayNumber = value => typeof value === 'number' ? Number(value.toFixed(4))
  : Array.isArray(value) ? value.map(displayNumber) : value;

/** 独立弹体插件：只翻译 Java 已计算的记录，不在网页重写弹道。 */
export default {
  /** 接口版本与同名目录标识。 */
  apiVersion: 1, id: 'projectile',
  /** 明确格式标识，避免和通用解析抛体示例混淆。 */
  accepts: data => data?.format === 'rvp-projectile-simulation',
  /** 保留来源摘要和原始武器 JSON，统一帧结构仍由主体严格校验。 */
  adapt(data) {
    if (data.schemaVersion !== 2 || data.physics?.implementation !== 'rvp-entity-flight'
      || typeof data.physics.entityClass !== 'string' || typeof data.physics.motionEntry !== 'string'
      || typeof data.weapon?.id !== 'string'
      || !/^[a-f0-9]{64}$/.test(data.weapon?.sha256) || !data.weapon.json
      || !data.scene || data.scene.weapon !== data.weapon.id) throw new Error('需要第 2 版真实实体回放、计算入口与武器来源摘要；请重新运行 projectileSimulation');
    return { ...data, format: 'rvp-test-replay', schemaVersion: 1,
      summary: data.summary.map(row => ({ ...row, value: displayNumber(row.value) })) };
  },
  /** 几何图形用于观察方向，不冒充载具包真实模型或碰撞箱。 */
  renderers: { body: createProjectile },
  /** 原始键用于自动检查；专用面板解释单位和术语。 */
  inspect({ sample, object }) {
    const labels = {
      weaponId: '武器 ID', speed: '速度（格/秒）', velocity: '速度 X/Y/Z（格/秒）',
      height: '离平地高度（格）', distance: '累计路程（格）', life: '剩余寿命（刻，20 刻/秒）',
      status: '当前状态', motorStage: '发动机阶段', targetDistance: '到目标中心（格）',
      mass: '当前质量（RVP 配置单位）', thrust: '当前推力（RVP 配置单位）', densityFactor: '空气密度倍率',
      entityYaw: '实体原始航向（度）', entityPitch: '实体原始俯仰（度）', pitchRate: '机头俯仰角速度（度/刻）',
      yawRate: '机头航向角速度（度/刻）', aeroLoadFactor: '气动载荷使用率', inWater: '实体处于水中',
      phase: '实体制导阶段', directMotion: '本刻执行直控运动',
    };
    const priority = ['status', 'motorStage', 'phase', 'speed', 'height', 'aeroLoadFactor', 'pitchRate', 'yawRate',
      'inWater', 'distance', 'life', 'mass', 'thrust', 'targetDistance', 'entityYaw', 'entityPitch', 'velocity', 'weaponId', 'directMotion'];
    const rows = Object.entries(sample.values).sort(([a], [b]) => (priority.indexOf(a) < 0 ? 99 : priority.indexOf(a))
      - (priority.indexOf(b) < 0 ? 99 : priority.indexOf(b))).map(([key, value]) => ({ label: labels[key] ?? key, value: displayNumber(value) }));
    if (object?.attributes?.guidanceModel) rows.unshift({ label: '制导计算范围', value: object.attributes.guidanceModel });
    return rows;
  },
};
