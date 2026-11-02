import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { validateReplay, sampleReplay } from '../replay.js';

/** 最小的独立记录夹具，不依赖提前运行 Java。 */
function fixture() {
  const actor = { id: 'blue', name: '车辆', team: 'blue', mode: 'ground', position: [0, 64, 0], radius: 1, height: 3, searchRadius: 100 };
  const sample = { id: 'blue', active: true, position: [0, 64, 0], yaw: 179, pitch: 0, bank: 0, speed: 1, targetId: null,
    phase: 'PATROL', behavior: 'ground_patrol', blocked: false, combatAmmo: true, fireAttempt: false, diagnostics: [],
    command: { forward: true, backward: false, left: false, right: false, pitch: 0, yaw: 0, roll: 0, throttle: 0 } };
  return { schemaVersion: 1, tickRate: 20, scene: { id: 'test', name: '测试', description: '', groundY: 64, ticks: 1, actors: [actor], obstacles: [] },
    coverage: [], limitations: [], summary: { distance: { blue: 10 }, blockedTicks: 0, fireAttempts: 0, recoveries: 0 },
    frames: [{ tick: 0, actors: [sample], events: [] }, { tick: 1, actors: [{ ...sample, position: [10, 64, 0], yaw: -179, phase: 'HOLD', blocked: true }], events: [] }] };
}

test('往返拖动按绝对刻采样，平滑跨越正负180度，不提前切换离散行为', () => {
  const replay = validateReplay(fixture());
  const middle = sampleReplay(replay, 0.5).actors[0];
  assert.equal(middle.position[0], 5); assert.equal(middle.yaw, 180); assert.equal(middle.phase, 'PATROL'); assert.equal(middle.blocked, false);
  assert.equal(sampleReplay(replay, 1).actors[0].phase, 'HOLD');
  assert.equal(sampleReplay(replay, 0).actors[0].position[0], 0);
  assert.equal(sampleReplay(replay, -100).index, 0); assert.equal(sampleReplay(replay, 100).index, 1);
});

test('离场边界保留当前刻，不从未来样本插出出场位置', () => {
  const replay = fixture(); replay.frames[1].actors[0].active = false;
  assert.equal(sampleReplay(replay, 0.5).actors[0].position[0], 0);
  assert.equal(sampleReplay(replay, 1).actors[0].active, false);
});

test('拒绝坏版本、不连续刻号、缺失对象、NaN和不存在的目标', () => {
  for (const mutate of [d => { d.schemaVersion = 0; }, d => { d.frames[1].tick = 2; }, d => { d.frames[1].actors = []; },
    d => { d.frames[0].actors[0].position[1] = NaN; }, d => { d.frames[0].actors[0].targetId = 'missing'; }]) {
    const replay = fixture(); mutate(replay); assert.throws(() => validateReplay(replay), /回放格式错误/);
  }
});

test('Java 实际生成的记录能被浏览器合同读取', async t => {
  const root = resolve(import.meta.dirname, '../../../build/gunner-simulation');
  let index;
  try { index = JSON.parse(await readFile(resolve(root, 'index.json'), 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') { t.skip('先运行 ./gradlew gunnerSimulation 可验证跨语言合同'); return; } throw error; }
  for (const entry of index.replays) {
    const replay = validateReplay(JSON.parse(await readFile(resolve(root, entry.file), 'utf8')));
    assert.equal(replay.scene.id, entry.id); assert.equal(sampleReplay(replay, replay.scene.ticks).index, replay.scene.ticks);
  }
});
