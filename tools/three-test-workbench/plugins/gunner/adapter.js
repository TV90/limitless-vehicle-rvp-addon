import { createVehicle } from './vehicle.js';

/** 阶段中文名只属于 Gunner 插件；新增其他领域的阶段不用改主体。 */
const phaseNames = {
  SCRIPTED: '脚本运动', NO_TARGET: '无目标', PATROL: '巡逻', PATROL_TURN: '巡逻转向', PATROL_TURN_START: '开始转向',
  APPROACH: '接近目标', HOLD: '停车观察', EVADE: '战术侧移', RECOVERY: '倒车脱困', LAUNCHER_PARK: '有弹停车',
  TAKEOFF: '起飞爬升', ATTACK: '攻击航段', DISENGAGE: '脱离重整', RETURN: '返回任务区', SEAD_AWAY: '反雷达脱离', SEAD_TOWARD: '反雷达回转',
};
/** 把技术标识配上中文含义；保留括号内 id 方便对照 Java 记录。 */
const modeNames = { ground: '地面载具', launcher: '发射架', fixed_wing: '固定翼飞机', scripted: '脚本目标' };
/** 常见胜出行为的解释不进入通用主体。 */
const behaviorNames = { ground_patrol: '地面巡逻', ground_engagement_move: '地面接敌走位',
  ground_stuck_recovery: '受阻倒车脱困', launcher_position: '发射架定位', fixed_wing: '固定翼飞行' };
/** 原始 Gunner 记录有自己的严格合同，不与统一格式混用。 */
const check = (ok, message) => { if (!ok) throw new Error(`Gunner 记录错误：${message}`); };
/** 数值字段禁止 NaN/Infinity。 */
const finite = value => typeof value === 'number' && Number.isFinite(value);

/** 校验生产侧当前导出，再转换成通用“对象/快照/事件”。不修改原始 JSON。 */
export function adaptGunner(data) {
  const scene = data?.scene;
  check(data?.schemaVersion === 1 && scene && Array.isArray(scene.actors) && scene.actors.length > 0, '需要当前场景和对象');
  check(Number.isInteger(data.tickRate) && data.tickRate > 0 && data.tickRate <= 240, 'tickRate 无效');
  check(Number.isInteger(scene.ticks) && scene.ticks > 0 && scene.ticks <= 72000
    && Array.isArray(data.frames) && data.frames.length === scene.ticks + 1, '须连续记录 0..ticks');
  for (const actor of scene.actors) {
    check(actor && typeof actor.team === 'string' && ['ground', 'launcher', 'fixed_wing', 'scripted'].includes(actor.mode)
      && [actor.radius, actor.height, actor.searchRadius].every(n => finite(n) && n > 0), '对象类型、阵营或尺寸无效');
  }
  for (const [index, frame] of data.frames.entries()) {
    check(frame?.tick === index && Array.isArray(frame.actors) && Array.isArray(frame.events), `第 ${index} 帧无效`);
    for (const actor of frame.actors) {
      check(actor && finite(actor.speed) && ['blocked', 'combatAmmo', 'fireAttempt'].every(key => typeof actor[key] === 'boolean')
        && typeof actor.phase === 'string' && typeof actor.behavior === 'string'
        && Array.isArray(actor.diagnostics) && actor.diagnostics.every(line => typeof line === 'string'), `第 ${index} 帧的业务状态无效`);
      check(actor.command && ['forward', 'backward', 'left', 'right'].every(key => typeof actor.command[key] === 'boolean')
        && ['pitch', 'yaw', 'roll', 'throttle'].every(key => finite(actor.command[key])), `第 ${index} 帧的操作无效`);
      check(actor.targetId === null || typeof actor.targetId === 'string', `第 ${index} 帧的 targetId 无效`);
    }
    check(frame.events.every(event => event && event.tick === index && typeof event.actorId === 'string'), `第 ${index} 帧的事件刻号无效`);
  }
  check(Array.isArray(scene.obstacles), '缺少 obstacles');
  check(data.summary && ['blockedTicks', 'fireAttempts', 'recoveries'].every(key => Number.isInteger(data.summary[key]) && data.summary[key] >= 0)
    && scene.actors.every(actor => finite(data.summary.distance?.[actor.id]) && data.summary.distance[actor.id] >= 0), 'summary 无效');
  return {
    /** 通用格式版本；原始 Gunner 格式独立保留。 */
    format: 'rvp-test-replay', schemaVersion: 1,
    /** 名称与世界基准直接来自 Java 记录。 */
    id: scene.id, name: scene.name, description: scene.description, tickRate: data.tickRate, groundY: scene.groundY,
    /** renderer 用插件命名空间，主体不用认识 fixed_wing 等业务类型。 */
    objects: scene.actors.map(actor => ({ id: actor.id, name: actor.name, renderer: 'gunner:vehicle',
      color: actor.team === 'red' ? '#ee8d80' : actor.mode === 'scripted' ? '#8fb583' : '#70d8c0',
      size: [actor.radius * 2, actor.height, actor.radius * 2], range: actor.searchRadius,
      attributes: { mode: actor.mode, team: actor.team } })),
    /** 障碍仅用于回放，不读取载具包模型。 */
    obstacles: scene.obstacles.map(box => ({ ...box, color: '#3d5669' })),
    /** 姿态统一为 yaw/pitch/roll，业务观察量全部放在 values。 */
    frames: data.frames.map(frame => ({ tick: frame.tick, objects: frame.actors.map(actor => ({
      id: actor.id, active: actor.active, position: actor.position, yaw: actor.yaw, pitch: actor.pitch, roll: actor.bank,
      targetId: actor.targetId, values: { speed: actor.speed, phase: actor.phase, behavior: actor.behavior, command: actor.command,
        blocked: actor.blocked, combatAmmo: actor.combatAmmo, fireAttempt: actor.fireAttempt, diagnostics: actor.diagnostics },
    })) })),
    /** 事件按原始时间排列，主体只负责跳转。 */
    events: data.frames.flatMap(frame => frame.events.map(event => ({ tick: event.tick, objectId: event.actorId,
      type: event.type, message: event.type === 'PHASE' ? phaseNames[event.message] ?? event.message : event.message }))),
    /** 汇总不推断“算法正确”，只报告原始计数。 */
    summary: [{ label: '受阻刻数', value: data.summary.blockedTicks }, { label: '脱困次数', value: data.summary.recoveries },
      { label: '模拟开火尝试', value: data.summary.fireAttempts }],
    checks: [], coverage: data.coverage, limitations: data.limitations,
  };
}

export default {
  /** 插件接口版本及与目录清单一致的标识。 */
  apiVersion: 1, id: 'gunner',
  /** 识别当前 Gunner 导出，不接管其他统一格式的记录。 */
  accepts: data => data?.format === undefined && data?.schemaVersion === 1 && Array.isArray(data.scene?.actors),
  /** 数据适配器与专用绘制器均封装在插件目录。 */
  adapt: adaptGunner, renderers: { vehicle: createVehicle },
  /** 业务面板用文字行返回，主体负责安全显示；不传 HTML。 */
  inspect({ sample, object }) {
    const values = sample.values, command = values.command;
    return [
      { label: '阵营 / 类型', value: `${{ blue: '蓝方', red: '红方' }[object.attributes.team] ?? object.attributes.team} / ${modeNames[object.attributes.mode]}` },
      { label: '当前阶段', value: phaseNames[values.phase] ?? values.phase },
      { label: '胜出行为', value: behaviorNames[values.behavior] ? `${behaviorNames[values.behavior]}（${values.behavior}）` : values.behavior },
      { label: '速度（格/秒）', value: values.speed.toFixed(2) },
      { label: '受阻 / 有弹 / 开火尝试', value: [values.blocked, values.combatAmmo, values.fireAttempt].map(v => v ? '是' : '否').join(' / ') },
      { label: '方向输入', value: ['forward', 'backward', 'left', 'right'].map((key, i) => `${['前', '后', '左', '右'][i]} ${command[key] ? '●' : '○'}`).join('  ') },
      { label: '俯仰 / 偏航 / 滚转 / 油门%', value: [command.pitch, command.yaw, command.roll, command.throttle].map(v => v.toFixed(2)).join(' / ') },
      { label: '决策反馈', value: values.diagnostics.join('\n') || '本刻无附加反馈' },
    ];
  },
};
