/** 当前回放数据合同与时间采样，不依赖 Three.js，供浏览器和 Node 测试共用。 */
const finite = n => typeof n === 'number' && Number.isFinite(n);
const vector = v => Array.isArray(v) && v.length === 3 && v.every(finite);
const check = (ok, message) => { if (!ok) throw new Error(`回放格式错误：${message}`); };
const string = value => typeof value === 'string';

/** 严格读取当前格式，坏记录在替换已有回放之前给出中文说明。 */
export function validateReplay(data) {
  check(data?.schemaVersion === 1, '仅支持 schemaVersion=1');
  check(Number.isInteger(data.tickRate) && data.tickRate > 0 && data.tickRate <= 240, 'tickRate 无效');
  const scene = data.scene;
  check(scene && string(scene.name) && string(scene.description) && string(scene.id), '缺少场景名称、描述或 id');
  check(finite(scene.groundY) && Number.isInteger(scene.ticks) && scene.ticks > 0 && scene.ticks <= 72000, '场景高度/时长无效');
  check(Array.isArray(scene.actors) && scene.actors.length > 0 && scene.actors.length <= 32, '缺少场景对象或数量超限');
  const ids = new Set();
  for (const a of scene.actors) {
    check(a && string(a.id) && a.id && !ids.has(a.id), '对象 id 缺失或重复'); ids.add(a.id);
    check(string(a.name) && string(a.team) && ['ground', 'launcher', 'fixed_wing', 'scripted'].includes(a.mode), `对象 ${a.id} 的名称/阵营/类型无效`);
    check([a.radius, a.height, a.searchRadius].every(n => finite(n) && n > 0) && vector(a.position), `对象 ${a.id} 的尺寸/坐标无效`);
  }
  check(Array.isArray(scene.obstacles), '缺少 obstacles');
  for (const box of scene.obstacles) check(box && string(box.name) && vector(box.min) && vector(box.max)
    && box.min.every((n, i) => n < box.max[i]), '障碍盒无效');
  check(Array.isArray(data.frames) && data.frames.length === scene.ticks + 1, '必须连续记录第 0 刻到最后一刻');
  for (let i = 0; i < data.frames.length; i++) {
    const f = data.frames[i];
    check(f?.tick === i && Array.isArray(f.actors) && f.actors.length === ids.size, `第 ${i} 刻对象缺失或刻号不连续`);
    const present = new Set();
    for (const a of f.actors) {
      check(a && ids.has(a.id) && !present.has(a.id), `第 ${i} 刻包含未知或重复对象`); present.add(a.id);
      check(vector(a.position) && [a.yaw, a.pitch, a.bank, a.speed].every(finite), `第 ${i} 刻位置/姿态无效`);
      check(['active', 'blocked', 'combatAmmo', 'fireAttempt'].every(k => typeof a[k] === 'boolean'), `第 ${i} 刻状态无效`);
      check(a.targetId === null || ids.has(a.targetId), `第 ${i} 刻目标不存在`);
      check(string(a.phase) && string(a.behavior) && Array.isArray(a.diagnostics) && a.diagnostics.every(string), `第 ${i} 刻行为无效`);
      check(a.command && ['forward', 'backward', 'left', 'right'].every(k => typeof a.command[k] === 'boolean')
        && ['pitch', 'yaw', 'roll', 'throttle'].every(k => finite(a.command[k])), `第 ${i} 刻命令无效`);
    }
    check(Array.isArray(f.events) && f.events.every(e => e && e.tick === i && ids.has(e.actorId)
      && string(e.type) && string(e.message)), `第 ${i} 刻事件无效`);
  }
  check(Array.isArray(data.coverage) && data.coverage.every(string) && Array.isArray(data.limitations)
    && data.limitations.every(string), '缺少算法接入范围或限制说明');
  check(data.summary && ['blockedTicks', 'fireAttempts', 'recoveries'].every(k => Number.isInteger(data.summary[k]) && data.summary[k] >= 0)
    && data.summary.distance && [...ids].every(id => finite(data.summary.distance[id]) && data.summary.distance[id] >= 0), '汇总统计无效');
  return data;
}

/** 数值位置平滑插值；行为、目标、事件始终取时间轴当前刻，倒放不会提前看到未来状态。 */
export function sampleReplay(replay, playhead) {
  const max = replay.frames.length - 1;
  const tick = Math.max(0, Math.min(max, finite(playhead) ? playhead : 0));
  const index = Math.floor(tick), current = replay.frames[index], next = replay.frames[Math.min(max, index + 1)];
  const fraction = tick - index;
  const nextActors = new Map(next.actors.map(a => [a.id, a]));
  const actors = current.actors.map(a => {
    const b = nextActors.get(a.id);
    if (!a.active || !b.active) return a;
    const yawDelta = ((b.yaw - a.yaw + 540) % 360 + 360) % 360 - 180;
    return { ...a, position: a.position.map((v, i) => v + (b.position[i] - v) * fraction),
      yaw: a.yaw + yawDelta * fraction, pitch: a.pitch + (b.pitch - a.pitch) * fraction,
      bank: a.bank + (b.bank - a.bank) * fraction, speed: a.speed + (b.speed - a.speed) * fraction };
  });
  return { tick, index, actors, events: current.events };
}

/** 常见策略阶段的中文名称。 */
export const phaseNames = {
  SCRIPTED: '脚本运动', NO_TARGET: '无目标', PATROL: '巡逻', PATROL_TURN: '巡逻转向', PATROL_TURN_START: '开始转向',
  APPROACH: '接近目标', HOLD: '停车观察', EVADE: '战术侧移', RECOVERY: '倒车脱困', LAUNCHER_PARK: '有弹停车',
  TAKEOFF: '起飞爬升', ATTACK: '攻击航段', DISENGAGE: '脱离重整', RETURN: '返回任务区', SEAD_AWAY: '反雷达脱离', SEAD_TOWARD: '反雷达回转',
};

/** 统一格式化刻号和秒数。 */
export function timeLabel(tick, rate) { return `${(tick / rate).toFixed(2)} s`; }
