import test from 'node:test';
import assert from 'node:assert/strict';
import { compileReplay, displayValue, indexAt, sampleReplay, validateReplay } from '../core/replay.js';
import { createDemo } from '../scripts/generate-demo.mjs';
import { fixture } from './fixture.mjs';

test('稀疏刻插值跨越正负180度，离散业务字段与目标不提前切换', () => {
  const compiled = compileReplay(fixture()), sample = sampleReplay(compiled, 5).objects[0];
  assert.deepEqual(sample.position, [5, 0, 0]); assert.equal(sample.yaw, 180);
  assert.equal(sample.values.phase, 'FIRST'); assert.equal(sample.targetId, 'b');
  assert.equal(sampleReplay(compiled, 10).objects[0].values.phase, 'SECOND');
  assert.equal(sampleReplay(compiled, 0).objects[0].position[0], 0);
});

test('对象顺序可以跨帧变化；按 id 对齐，原始数据保持不变', () => {
  const data = fixture(); data.frames[1].objects.reverse(); const before = JSON.stringify(data);
  assert.equal(sampleReplay(compileReplay(data), 5).objects[0].position[0], 5);
  assert.equal(JSON.stringify(data), before);
});

test('出场和离场边界不从未来插出位置；单帧与越界时间也能读取', () => {
  for (const change of ['spawn', 'despawn']) {
    const data = fixture(); data.frames[change === 'spawn' ? 0 : 1].objects[0].active = false;
    const compiled = compileReplay(data);
    assert.equal(sampleReplay(compiled, 5).objects[0].position[0], 0);
    assert.equal(sampleReplay(compiled, 10).objects[0].active, change === 'spawn');
    assert.equal(sampleReplay(compiled, -100).tick, 0); assert.equal(sampleReplay(compiled, Infinity).tick, 0);
    assert.equal(sampleReplay(compiled, 999).tick, 10);
  }
  const data = fixture(); data.frames.pop(); data.events = [];
  assert.equal(sampleReplay(compileReplay(data), 99).tick, 0);
});

test('同刻多个事件全部计入，倒拖不会保留未来事件', () => {
  const events = [{ tick: 0 }, { tick: 3 }, { tick: 3 }, { tick: 9 }];
  assert.equal(indexAt(events, 3), 2); assert.equal(indexAt(events, 2), 0); assert.equal(indexAt(events, 9), 3);
  assert.equal(indexAt([], 0), -1);
});

/** 每项破坏一种外部输入约束，验证失败能在渲染之前被定位。 */
const invalidCases = [
  ['旧版本', data => { data.schemaVersion = 0; }],
  ['未知格式', data => { data.format = 'other'; }],
  ['零刻率', data => { data.tickRate = 0; }],
  ['重复对象', data => { data.objects[1].id = 'a'; }],
  ['非有限坐标', data => { data.frames[0].objects[0].position[0] = NaN; }],
  ['缺失快照', data => { data.frames[1].objects.pop(); }],
  ['重复快照', data => { data.frames[1].objects[1].id = 'a'; }],
  ['倒序采样', data => { data.frames[1].tick = 0; }],
  ['非零起点', data => { data.frames[0].tick = 1; }],
  ['不存在的目标', data => { data.frames[0].objects[0].targetId = 'missing'; }],
  ['坏尺寸', data => { data.objects[0].size[0] = -1; }],
  ['坏颜色', data => { data.objects[0].color = 'url(anything)'; }],
  ['非有限扩展值', data => { data.frames[0].objects[0].values.extra = { bad: Infinity }; }],
  ['负事件刻', data => { data.events[0].tick = -1; }],
  ['未来事件', data => { data.events[0].tick = 11; }],
  ['事件乱序', data => { data.events.push({ ...data.events[0], tick: 1 }); }],
  ['未知断言结果', data => { data.checks.push({ name: '检查', status: 'maybe', message: '' }); }],
];
for (const [name, mutate] of invalidCases) test(`导入前拒绝：${name}`, () => {
  const data = fixture(); mutate(data); assert.throws(() => validateReplay(data), /回放格式错误/);
});

test('独立演示的公式、采样时长、断言与回放能够对应', () => {
  const data = createDemo(), compiled = compileReplay(data);
  assert.equal(compiled.endTick, 80); assert.equal(data.frames.length, 41);
  assert.deepEqual(sampleReplay(compiled, 40).objects[0].position, [40, 20, 0]);
  assert.deepEqual(sampleReplay(compiled, 80).objects[0].position, [80, 0, 0]);
  assert.ok(data.checks.every(check => check.status === 'passed'));
  assert.match(data.limitations.join(''), /不是 RVP/);
  assert.equal(displayValue(false), '否'); assert.equal(displayValue(null), '—');
});
