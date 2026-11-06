import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { basename, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { directionTimeline, withDirectionDiagnostics } from '../plugins/projectile/directions.js';

/** 输入是真实Java回放；输出只附加显示条件，不重算或改写实体弹道。 */
const input = resolve(process.argv[2] ?? '../../build/projectile-simulation/hq9b_h100_v40.json');
/** 独立诊断数据源由弹体插件自动发现。 */
const output = resolve(process.argv[3] ?? '../../build/projectile-direction-diagnostics');
/** 帧率只决定每秒重放几次35%角度追赶，不影响Java记录。 */
const frameRates = [30, 60, 144];
/** 当前生产绘制源码指纹用于说明本轮估算依据。 */
const renderSource = await readFile(new URL('../../../src/main/java/org/ywzj/rvp/client/render/VehicleProjectileRenderLogic.java', import.meta.url));
const sourceSHA256 = createHash('sha256').update(renderSource).digest('hex');
const raw = JSON.parse((await readFile(input, 'utf8')).replace(/^\uFEFF/, ''));
if (raw.physics?.implementation !== 'rvp-entity-flight' || raw.physics?.guidanceRuntime?.mode !== 'PPN') throw new Error('需要当前PPN真实实体回放');
await mkdir(output, { recursive: true });
/** 向量运算仅测量已经记录的三维方向。 */
const dot = (a, b) => a.reduce((sum, value, axis) => sum + value * b[axis], 0);
const subtract = (a, b) => a.map((value, axis) => value - b[axis]);
const normalPart = (a, tangent) => a.map((value, axis) => value - tangent[axis] * dot(a, tangent));
const unit = vector => { const length = Math.hypot(...vector); return length > 1e-10 ? vector.map(value => value / length) : null; };

/** 相邻速度单位方向的变化给出本段曲率内侧；直飞段不作内外判定。 */
function turnSide(previous, current, key) {
  if (!previous) return null;
  const inward = unit(normalPart(subtract(current.velocityDirection, previous.velocityDirection), current.velocityDirection));
  if (!inward) return null;
  return Math.atan2(dot(current[key], inward), dot(current[key], current.velocityDirection)) * 180 / Math.PI;
}

/** 把连续弯外显示帧合并成区间，便于在工作台时间轴定位。 */
function intervals(rows, key) {
  const result = []; let active = null;
  for (const row of rows) {
    if (row[key] !== null && row[key] < -1e-5) {
      if (!active) { active = { startTick: row.tick, endTick: row.tick, frames: 0 }; result.push(active); }
      active.endTick = row.tick; active.frames++;
    } else active = null;
  }
  return result;
}

const entries = [], results = [];
for (const fps of frameRates) {
  const variant = { ...raw, id: `${raw.id}_directions_${fps}fps`, name: `${raw.name} · ${fps} FPS三方向对照`,
    description: `${raw.description} 青=实际速度，金=真实机头，紫=模型显示估算；固定${fps}FPS逐帧重放35%平滑，未模拟网络同步。`,
    objects: raw.objects.map(object => object.renderer === 'projectile:body'
      ? { ...object, attributes: { ...object.attributes, directionDiagnosticsFPS: fps } } : object) };
  const adapted = withDirectionDiagnostics(variant), object = adapted.objects.find(item => item.renderer === 'projectile:body');
  const timeline = directionTimeline(object), rows = []; let previous = null;
  for (const frame of timeline.frames) {
    const value = timeline.sample(frame.tick), bodySide = turnSide(previous, value, 'bodyDirection'), displaySide = turnSide(previous, value, 'displayDirection');
    rows.push({ tick: frame.tick, seconds: frame.tick / raw.tickRate, physicalPitch: value.physicalPitch, physicalYaw: value.physicalYaw,
      displayPitch: value.displayPitch, displayYaw: value.displayYaw, bodyAttackDeg: value.bodyAttackDeg,
      displayAttackDeg: value.displayAttackDeg, modelLagDeg: value.modelLagDeg, bodySignedInwardDeg: bodySide, displaySignedInwardDeg: displaySide,
      ...Object.fromEntries(['velocityDirection', 'bodyDirection', 'displayDirection'].flatMap(key => value[key].map((number, axis) => [`${key}_${'xyz'[axis]}`, number]))) });
    previous = value;
  }
  const bodyOutside = intervals(rows, 'bodySignedInwardDeg'), displayOutside = intervals(rows, 'displaySignedInwardDeg');
  // 原始逐刻姿态和显示帧间插值分别评分，避免把插值的微小换边归因于物理模型。
  let previousRecorded = null;
  const recordedRows = raw.frames.map(frame => {
    const value = timeline.sample(frame.tick), side = turnSide(previousRecorded, value, 'bodyDirection');
    previousRecorded = value; return { tick: frame.tick, signedInward: side };
  });
  const recordedBodyOutside = intervals(recordedRows, 'signedInward');
  const maximumLag = rows.reduce((best, row) => row.modelLagDeg > best.modelLagDeg ? row : best);
  const interpolatedBodyMaxOutsideDeg = rows.reduce((maximum, row) => Math.max(maximum, -(row.bodySignedInwardDeg ?? 0)), 0);
  const result = { fps, renderFrames: rows.length, recordedBodyOutside, bodyOutside, displayOutside, interpolatedBodyMaxOutsideDeg, maximumLag,
    checkpoints: [20, 31, 49, 55, 56, 76, 95, 106].map(tick => timeline.sample(tick)) };
  results.push(result);
  variant.summary = [...raw.summary, { label: '模型显示估算帧率', value: fps },
    { label: '青/金/紫箭头', value: '实际速度 / 真实机头 / 固定FPS下模型显示估算' },
    { label: '最大模型显示角差（度）', value: maximumLag.modelLagDeg },
    { label: '显示机头落到弯外的连续区间数', value: displayOutside.length }];
  variant.limitations = [...raw.limitations,
    '紫色箭头只复现普通实体的角度插值及每显示帧35%追赶，不包含网络抖动、追踪延迟、真实客户端时序或虚拟显示对象。',
    '真实姿态与速度在同一显示时间线上插值；紫色估算在两个固定显示帧之间保持上一显示结果。',
    '内外统计按相邻回放速度方向变化测量；仅表示本场景的几何关系，不推断所有重力/风偏/反向控制情形。'];
  // 调用纯查询检查倒拖后同刻结果完全相同，不能靠重放浏览器历史碰巧得到正确箭头。
  const checkpoint = timeline.sample(49); timeline.sample(106); timeline.sample(1);
  if (JSON.stringify(timeline.sample(49)) !== JSON.stringify(checkpoint)) throw new Error('方向采样存在播放顺序依赖');
  const file = `${variant.id}.json`;
  await writeFile(resolve(output, file), JSON.stringify(variant)); entries.push({ id: variant.id, name: variant.name, file });
  const columns = Object.keys(rows[0]);
  await writeFile(resolve(output, `${variant.id}.csv`), '\uFEFF' + [columns.join(','), ...rows.map(row => columns.map(key => row[key] ?? '').join(','))].join('\n') + '\n');
}
await writeFile(resolve(output, 'index.json'), JSON.stringify({ schemaVersion: 1, replays: entries }));
await writeFile(resolve(output, 'comparison.json'), JSON.stringify({ input: basename(input), weaponSHA256: raw.weapon.sha256,
  guidanceRuntime: raw.physics.guidanceRuntime, renderSourceSHA256: sourceSHA256, results }, null, 2) + '\n');
const table = ['| FPS | 模拟显示帧 | 最大显示角差 | 原始逐刻机头弯外区间 | 插值机头弯外区间 | 显示机头弯外区间 |', '|---:|---:|---:|---|---|---|',
  ...results.map(result => `| ${result.fps} | ${result.renderFrames} | ${result.maximumLag.modelLagDeg.toFixed(4)}° | ${result.recordedBodyOutside.map(item => `${item.startTick.toFixed(3)}～${item.endTick.toFixed(3)}刻`).join('；') || '无'} | ${result.bodyOutside.map(item => `${item.startTick.toFixed(3)}～${item.endTick.toFixed(3)}刻`).join('；') || '无'} | ${result.displayOutside.map(item => `${item.startTick.toFixed(3)}～${item.endTick.toFixed(3)}刻`).join('；') || '无'} |`)];
const recordedFinding = results[0].recordedBodyOutside.length ? `原始逐刻物理姿态存在${results[0].recordedBodyOutside.length}段弯外采样，详见表格。` : '原始逐刻物理姿态没有弯外采样点。';
const interpolatedFinding = results.map(result => `${result.fps} FPS帧间插值最大向外夹角${result.interpolatedBodyMaxOutsideDeg.toFixed(4)}°`).join('；');
await writeFile(resolve(output, 'report.md'), `# ${raw.name} 三方向箭头对照\n\n青色＝实际速度；金色＝真实机头；紫色＝固定FPS下的模型显示估算。三箭头共用弹体中心，长度分别12、10、8格，模型本身跟随紫色箭头。\n\n${table.join('\n')}\n\n${recordedFinding}${interpolatedFinding}。帧间姿态/速度独立插值可能在接近零攻角时产生微小换边；它们属于插值测量，不是额外执行的物理子步。显示平滑保留上一方向的历史，因此显示换边还可能晚于插值机头。\n\n最大角差统计全程三维机头方向，不把偏航180°坐标换向直接当成真实180°姿态突变。内外统计使用相邻显示时刻速度方向变化的法向分量。帧末/帧间插值与真实客户端时序不同，本轮没有运行网络同步或完整Minecraft渲染器。\n\n输入武器SHA-256：${raw.weapon.sha256}。输入真实运行时：${raw.physics.guidanceRuntime.mode}。\n\n打开工作台的“RVP弹体仿真”分组，选择30、60、144 FPS三方向对照，建议跟随弹体并在20、49、55、76刻暂停观察。原始Java位置、速度、实体姿态和武器快照均保留。\n`);
console.log(JSON.stringify({ output, results: results.map(({ fps, bodyOutside, displayOutside, maximumLag }) => ({ fps, bodyOutside, displayOutside, maximumLagDeg: maximumLag.modelLagDeg, maximumLagTick: maximumLag.tick })) }, null, 2));
