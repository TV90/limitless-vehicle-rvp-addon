/** 当前游戏模型每次绘制的角度追赶比例；测试会与生产渲染常量核对。 */
export const DISPLAY_ROTATION_BLEND = 0.35;
/** 默认显示朝向估算帧率，不使用浏览器当前帧率推进诊断。 */
export const DEFAULT_DIAGNOSTIC_FPS = 60;
/** 三根方向箭头的颜色、长度（格）与说明，全部从弹体中心出发。 */
export const DIRECTION_ARROWS = Object.freeze([
  { key: 'velocityDirection', name: '实际速度', color: '#4fc3f7', length: 12 },
  { key: 'bodyDirection', name: '真实机头', color: '#ffbc42', length: 10 },
  { key: 'displayDirection', name: '模型显示估算', color: '#e879f9', length: 8 },
]);
/** 每份只读时间线共用一个采样器，模型与观察面板查询不会互相推进。 */
const TIMELINES = new WeakMap();
/** 防止零速参与方向归一化的数值阈值。 */
const EPSILON = 1e-10;

/** 与 Minecraft rotLerp 相同，角度差限定在 [-180,180)。 */
export const wrapDegrees = value => ((value + 180) % 360 + 360) % 360 - 180;
/** 角度按最短弧取样，不在竖直/偏航边界凭空绕一圈。 */
const interpolateAngle = (from, to, fraction) => from + wrapDegrees(to - from) * fraction;
/** 点积与方向归一化只处理观测数据，不计算制导或弹道。 */
const dot = (a, b) => a.reduce((sum, value, axis) => sum + value * b[axis], 0);
const unit = vector => { const length = Math.hypot(...vector); return length > EPSILON ? vector.map(value => value / length) : [0, 0, 0]; };
/** 已归一化方向的夹角，单位度；零速度没有可定义的夹角。 */
const angleBetween = (a, b) => Math.hypot(...a) > EPSILON && Math.hypot(...b) > EPSILON
  ? Math.acos(Math.max(-1, Math.min(1, dot(a, b)))) * 180 / Math.PI : null;

/** 工作台约定：yaw=0朝+Z、90朝-X，pitch正数抬头；机头为模型局部+Z。 */
export function directionFromRotation(yaw, pitch) {
  const y = yaw * Math.PI / 180, p = pitch * Math.PI / 180;
  return [-Math.sin(y) * Math.cos(p), Math.sin(p), Math.cos(y) * Math.cos(p)];
}

/** 找到不晚于观察时刻的样本，支持稀疏记录。 */
function indexAt(samples, tick) {
  let low = 0, high = samples.length;
  while (low < high) { const middle = (low + high) >>> 1; if (samples[middle].tick <= tick) low = middle + 1; else high = middle; }
  return Math.max(0, low - 1);
}

/** 同时刻插值速度与真实姿态；位置由工作台已有采样器负责。 */
function physicalAt(samples, tick) {
  const index = indexAt(samples, tick), a = samples[index], b = samples[Math.min(index + 1, samples.length - 1)];
  const fraction = a.tick === b.tick || !a.active || !b.active ? 0 : (tick - a.tick) / (b.tick - a.tick);
  return { active: a.active, yaw: interpolateAngle(a.yaw, b.yaw, fraction), pitch: interpolateAngle(a.pitch, b.pitch, fraction),
    velocity: a.velocity.map((value, axis) => value + (b.velocity[axis] - value) * fraction) };
}

/** 离线重放真实实体分支的显示平滑；每个模拟显示帧只追赶一次，查询本身没有副作用。 */
export function createDirectionTimeline(track) {
  if (!Number.isFinite(track.fps) || track.fps < 1 || track.fps > 240
    || !Number.isFinite(track.tickRate) || track.tickRate <= 0 || !track.samples?.length) throw new Error('方向诊断需要有效帧率、刻率和实体样本');
  const endTick = track.samples.at(-1).tick, step = track.tickRate / track.fps;
  if (!Number.isFinite(endTick) || endTick < 0 || endTick / step > 1000000
    || track.samples.some((sample, index) => !Number.isFinite(sample.tick) || sample.tick < 0
      || (index > 0 && sample.tick <= track.samples[index - 1].tick)
      || !Number.isFinite(sample.yaw) || !Number.isFinite(sample.pitch)
      || !Array.isArray(sample.velocity) || sample.velocity.length !== 3 || !sample.velocity.every(Number.isFinite))) {
    throw new Error('方向诊断需要递增的有限实体样本，模拟显示帧不得超过一百万');
  }
  const first = physicalAt(track.samples, 0), frames = [{ tick: 0, yaw: first.yaw, pitch: first.pitch }];
  let yaw = first.yaw, pitch = first.pitch, wasActive = first.active;
  // 从发射开始固定重放，倒拖、暂停、多相机和网页FPS都不能改变这一份显示估算。
  for (let frame = 1; frame * step <= endTick + EPSILON; frame++) {
    const tick = Math.min(frame * step, endTick), target = physicalAt(track.samples, tick);
    if (!wasActive || !target.active) { yaw = target.yaw; pitch = target.pitch; }
    else { yaw = interpolateAngle(yaw, target.yaw, DISPLAY_ROTATION_BLEND); pitch = interpolateAngle(pitch, target.pitch, DISPLAY_ROTATION_BLEND); }
    frames.push({ tick, yaw, pitch }); wasActive = target.active;
  }
  return {
    /** 输入单位仍是游戏刻；两个显示帧之间保持上一显示帧，符合固定FPS的离散绘制。 */
    sample(inputTick) {
      const tick = Math.max(0, Math.min(endTick, inputTick)), physical = physicalAt(track.samples, tick);
      const shown = frames[indexAt(frames, tick)], velocityDirection = unit(physical.velocity);
      const bodyDirection = directionFromRotation(physical.yaw, physical.pitch), displayDirection = directionFromRotation(shown.yaw, shown.pitch);
      return { fps: track.fps, tick, displayTick: shown.tick, velocityDirection, bodyDirection, displayDirection,
        displayYaw: shown.yaw, displayPitch: shown.pitch, physicalYaw: physical.yaw, physicalPitch: physical.pitch,
        bodyAttackDeg: angleBetween(velocityDirection, bodyDirection), displayAttackDeg: angleBetween(velocityDirection, displayDirection),
        modelLagDeg: angleBetween(bodyDirection, displayDirection) };
    },
    /** 固定显示帧用于独立统计与导出，禁止向其中累计网页播放状态。 */
    frames,
  };
}

/** 对象静态扩展字段存时间线；缓存只避免重复预计算，不保存播放头或相机状态。 */
export function directionTimeline(object) {
  const track = object?.attributes?.directionDiagnostics;
  if (!track) return null;
  if (!TIMELINES.has(track)) TIMELINES.set(track, createDirectionTimeline(track));
  return TIMELINES.get(track);
}

/** 仅给真实弹体回放补充观察数据，不修改Java原始记录、位置、速度或实体姿态。 */
export function withDirectionDiagnostics(data) {
  // 本次诊断只对应导弹的Bedrock模型绘制；炮弹曳光使用另一套渲染算法。
  if (data.physics?.entityClass !== 'RVP_MissileEntity') return data;
  if (!Array.isArray(data.objects) || !Array.isArray(data.frames)) return data;
  const timelines = new Map();
  const objects = data.objects.map(object => {
    if (object.renderer !== 'projectile:body') return object;
    const samples = data.frames.map(frame => {
      const source = frame.objects.find(sample => sample.id === object.id), velocity = source?.values?.velocity;
      if (!Array.isArray(velocity) || velocity.length !== 3 || !velocity.every(Number.isFinite)) throw new Error('弹体方向诊断需要Java记录的世界速度X/Y/Z');
      return { tick: frame.tick, active: source.active, yaw: source.yaw, pitch: source.pitch, velocity: [...velocity] };
    });
    const track = { fps: object.attributes?.directionDiagnosticsFPS ?? DEFAULT_DIAGNOSTIC_FPS, tickRate: data.tickRate, samples };
    const result = { ...object, attributes: { ...object.attributes, directionDiagnostics: track } };
    timelines.set(object.id, directionTimeline(result)); return result;
  });
  const frames = data.frames.map(frame => ({ ...frame, objects: frame.objects.map(sample => {
    const timeline = timelines.get(sample.id);
    return timeline ? { ...sample, values: { ...sample.values, directionDiagnostics: timeline.sample(frame.tick) } } : sample;
  }) }));
  return { ...data, objects, frames };
}
