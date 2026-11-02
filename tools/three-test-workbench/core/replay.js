/** 统一记录格式；只描述如何观察测试，不包含某种载具或算法的业务规则。 */
export const REPLAY_FORMAT = 'rvp-test-replay';
/** 单个导入文件上限，浏览器与 HTTP 服务共用，单位字节。 */
export const MAX_RECORD_BYTES = 50 * 1024 * 1024;
/** 稳定标识允许用于插件、对象及场景，不接收路径。 */
export const isId = value => typeof value === 'string' && /^[a-z0-9][a-z0-9_-]*$/.test(value);
/** 所有进入三维计算的数字必须有限。 */
const finite = value => typeof value === 'number' && Number.isFinite(value);
/** 世界坐标与尺寸均采用 X/Y/Z 三元组。 */
const vector = value => Array.isArray(value) && value.length === 3 && value.every(finite);
/** 显示文字始终通过 textContent 输出。 */
const string = value => typeof value === 'string';
/** 颜色限定为六位十六进制，避免插件数据携带样式表达式。 */
const color = value => string(value) && /^#[0-9a-f]{6}$/i.test(value);
/** 便于定位输入记录中出错的字段。 */
const check = (ok, message) => { if (!ok) throw new Error(`回放格式错误：${message}`); };

/** 扩展观察值可嵌套，但必须仍是有限的 JSON 数据。 */
function jsonValue(value, depth = 0) {
  if (depth > 12) return false;
  if (value === null || string(value) || typeof value === 'boolean' || finite(value)) return true;
  if (Array.isArray(value)) return value.every(item => jsonValue(item, depth + 1));
  return !!value && Object.getPrototypeOf(value) === Object.prototype
    && Object.values(value).every(item => jsonValue(item, depth + 1));
}

/** 验证当前 schema（数据格式版本），不猜测旧字段，也不迁移记录。 */
export function validateReplay(data) {
  check(data?.format === REPLAY_FORMAT && data.schemaVersion === 1, '需要 rvp-test-replay / schemaVersion=1');
  check(isId(data.id) && string(data.name) && string(data.description), 'id、name 或 description 无效');
  check(finite(data.tickRate) && data.tickRate > 0 && data.tickRate <= 1000, 'tickRate 必须在 (0,1000]');
  check(finite(data.groundY), 'groundY 必须是有限高度');
  check(Array.isArray(data.objects) && data.objects.length > 0 && data.objects.length <= 256, 'objects 数量须为 1..256');
  const ids = new Set();
  for (const object of data.objects) {
    check(isId(object?.id) && !ids.has(object.id), '对象 id 缺失或重复'); ids.add(object.id);
    check(string(object.name) && string(object.renderer) && /^[a-z0-9_-]+(?::[a-z0-9_-]+)?$/.test(object.renderer), `${object.id} 的名称或 renderer 无效`);
    check(color(object.color) && vector(object.size) && object.size.every(n => n > 0), `${object.id} 的颜色或尺寸无效`);
    check(object.range === undefined || (finite(object.range) && object.range >= 0), `${object.id} 的范围无效`);
    check(object.attributes === undefined || jsonValue(object.attributes), `${object.id} 的 attributes 不是有限 JSON`);
  }
  check(Array.isArray(data.obstacles), 'obstacles 必须是数组');
  for (const box of data.obstacles) check(box && string(box.name) && vector(box.min) && vector(box.max)
    && box.min.every((v, i) => v < box.max[i]) && color(box.color), '障碍盒的名称、范围或颜色无效');
  check(Array.isArray(data.frames) && data.frames.length > 0 && data.frames.length <= 100001, 'frames 数量须为 1..100001');
  let previousTick = -1;
  for (const [index, frame] of data.frames.entries()) {
    check(Number.isSafeInteger(frame?.tick) && frame.tick > previousTick && (index > 0 || frame.tick === 0), `第 ${index} 帧必须从 0 开始且刻号递增`);
    previousTick = frame.tick;
    check(Array.isArray(frame.objects) && frame.objects.length === ids.size, `第 ${index} 帧缺少对象快照`);
    const present = new Set();
    for (const sample of frame.objects) {
      check(sample && ids.has(sample.id) && !present.has(sample.id), `第 ${index} 帧含未知或重复对象`); present.add(sample.id);
      check(typeof sample.active === 'boolean' && vector(sample.position)
        && [sample.yaw, sample.pitch, sample.roll].every(finite), `第 ${index} 帧 ${sample.id} 的状态、位置或角度无效`);
      check(sample.targetId === undefined || sample.targetId === null || ids.has(sample.targetId), `第 ${index} 帧的目标不存在`);
      check(sample.values && !Array.isArray(sample.values) && typeof sample.values === 'object'
        && jsonValue(sample.values), `第 ${index} 帧的 values 不是有限 JSON 对象`);
    }
  }
  check(Array.isArray(data.events), 'events 必须是数组');
  let previousEvent = -1;
  for (const event of data.events) {
    check(Number.isSafeInteger(event?.tick) && event.tick >= previousEvent && event.tick >= 0 && event.tick <= previousTick
      && (event.objectId === undefined || event.objectId === null || ids.has(event.objectId))
      && string(event.type) && string(event.message), '事件刻号、对象或说明无效');
    previousEvent = event.tick;
  }
  for (const key of ['coverage', 'limitations']) check(Array.isArray(data[key]) && data[key].every(string), `${key} 必须是文字数组`);
  check(Array.isArray(data.summary) && data.summary.every(row => row && string(row.label)
    && (string(row.value) || finite(row.value) || typeof row.value === 'boolean')), 'summary 必须是 label/value 数组');
  check(Array.isArray(data.checks) && data.checks.every(row => row && string(row.name)
    && ['passed', 'failed', 'info'].includes(row.status) && string(row.message)), 'checks 必须提供名称、结果和说明');
  return data;
}

/** 建立一次性对象索引；后续每次采样不用反复在整帧中查找对象。 */
export function compileReplay(data) {
  // 先校验再建立索引，禁止坏记录进入渲染循环。
  const replay = validateReplay(data);
  const frames = replay.frames.map(frame => {
    const byId = new Map(frame.objects.map(object => [object.id, object]));
    return { tick: frame.tick, objects: replay.objects.map(object => byId.get(object.id)) };
  });
  return { replay, frames, endTick: frames.at(-1).tick };
}

/** 找到不晚于当前刻的最后一项，支持稀疏采样与同刻多个事件。 */
export function indexAt(items, tick) {
  let low = 0, high = items.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (items[middle].tick <= tick) low = middle + 1; else high = middle;
  }
  return low - 1;
}

/** 角度走最短弧，避免 179° 到 -179° 转过整圈。 */
const angle = (a, b, fraction) => a + ((((b - a + 180) % 360 + 360) % 360) - 180) * fraction;

/** 位置和姿态插值；values、目标与在场状态取当前已发生的样本，倒拖不留未来状态。 */
export function sampleReplay(compiled, playhead) {
  const tick = Math.max(0, Math.min(compiled.endTick, finite(playhead) ? playhead : 0));
  const index = indexAt(compiled.frames, tick);
  const current = compiled.frames[index], next = compiled.frames[Math.min(index + 1, compiled.frames.length - 1)];
  const fraction = next.tick === current.tick ? 0 : (tick - current.tick) / (next.tick - current.tick);
  const objects = current.objects.map((a, i) => {
    const b = next.objects[i];
    if (!a.active || !b.active) return a;
    return { ...a, position: a.position.map((v, axis) => v + (b.position[axis] - v) * fraction),
      yaw: angle(a.yaw, b.yaw, fraction), pitch: angle(a.pitch, b.pitch, fraction), roll: angle(a.roll, b.roll, fraction) };
  });
  return { tick, index, objects };
}

/** 通用观察面板的文字值；复杂扩展值直接保留可读 JSON。 */
export function displayValue(value) {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
}
