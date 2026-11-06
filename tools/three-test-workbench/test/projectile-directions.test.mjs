import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import * as THREE from 'three';
import { compileReplay, sampleReplay } from '../core/replay.js';
import projectile from '../plugins/projectile/adapter.js';
import { createProjectile } from '../plugins/projectile/body.js';
import { DISPLAY_ROTATION_BLEND, createDirectionTimeline, directionFromRotation, directionTimeline, withDirectionDiagnostics } from '../plugins/projectile/directions.js';
import { fixture } from './fixture.mjs';

/** 简单的匀角速记录可用解析结果验证显示延迟，不依赖游戏弹道。 */
function linearTrack(fps = 60) {
  return { fps, tickRate: 20, samples: [
    { tick: 0, active: true, yaw: 0, pitch: 0, velocity: [0, 0, 20] },
    { tick: 40, active: true, yaw: 0, pitch: 60, velocity: [0, 0, 20] },
  ] };
}

/** 为插件边界验证构造当前格式；原始数据不会被观察层改写。 */
function replay() {
  const data = fixture(); data.format = 'rvp-projectile-simulation'; data.schemaVersion = 2;
  data.physics = { implementation: 'rvp-entity-flight', entityClass: 'RVP_MissileEntity', motionEntry: 'tickMotion' };
  data.weapon = { id: 'rvp:probe', sha256: '0'.repeat(64), json: {} }; data.scene = { weapon: 'rvp:probe' };
  data.objects[0].renderer = 'projectile:body';
  for (const frame of data.frames) { frame.objects[0].values.velocity = [0, 4, 20]; frame.objects[0].values.motorStage = '主发动机燃烧'; }
  return data;
}

/** 向量误差采用长度，覆盖三个轴而非只比较俯仰。 */
const closeVector = (actual, expected, tolerance = 1e-10) => assert.ok(new THREE.Vector3(...actual).distanceTo(new THREE.Vector3(...expected)) < tolerance, `${actual} != ${expected}`);

test('方向换算遵守Minecraft航向与抬头符号，零速不伪造速度箭头', () => {
  closeVector(directionFromRotation(0, 0), [0, 0, 1]);
  closeVector(directionFromRotation(90, 0), [-1, 0, 0]);
  closeVector(directionFromRotation(-90, 0), [1, 0, 0]);
  closeVector(directionFromRotation(0, 90), [0, 1, 0]);
  const track = linearTrack(); track.samples.forEach(sample => { sample.velocity = [0, 0, 0]; });
  const value = createDirectionTimeline(track).sample(10);
  assert.deepEqual(value.velocityDirection, [0, 0, 0]); assert.equal(value.bodyAttackDeg, null);
});

test('匀速转头的显示滞后满足指数平滑解析解，并随固定FPS变化', () => {
  for (const fps of [30, 60, 144]) {
    const value = createDirectionTimeline(linearTrack(fps)).sample(40), frames = fps * 2;
    // 每显示帧目标增量30/fps度；平滑残差是等比数列，作为独立解析期望。
    const retained = 1 - DISPLAY_ROTATION_BLEND;
    const expectedLag = (30 / fps) * retained * (1 - retained ** frames) / DISPLAY_ROTATION_BLEND;
    assert.ok(Math.abs(value.displayPitch - (60 - expectedLag)) < 1e-10);
    assert.ok(Math.abs(value.modelLagDeg - expectedLag) < 1e-9);
  }
});

test('暂停、重复查询、快进与倒拖均不推进额外显示帧', () => {
  const timeline = createDirectionTimeline(linearTrack()), first = timeline.sample(17.25);
  timeline.sample(40); timeline.sample(2); timeline.sample(17.25);
  assert.deepEqual(timeline.sample(17.25), first);
  assert.deepEqual(timeline.sample(-1), timeline.sample(0));
  assert.deepEqual(timeline.sample(100), timeline.sample(40));
  const halfFrame = timeline.sample(10.2);
  assert.ok(halfFrame.displayTick <= 10.2); assert.ok(10.2 - halfFrame.displayTick < 20 / 60 + 1e-10);
});

test('跨偏航边界按最短角差插值，坏帧率和非有限记录在预计算前被拒绝', () => {
  const track = linearTrack(); track.samples[0].yaw = 179; track.samples[1].yaw = -179;
  const value = createDirectionTimeline(track).sample(20);
  assert.ok(Math.abs(value.physicalYaw - 180) < 1e-10);
  assert.ok(value.modelLagDeg < 3);
  assert.throws(() => createDirectionTimeline({ ...track, fps: 0 }), /有效帧率/);
  assert.throws(() => createDirectionTimeline({ ...track, samples: [{ ...track.samples[0], tick: Infinity }] }), /有限实体样本/);
});

test('补充观察数据不改变任何原始位置、速度、姿态或武器快照', () => {
  const data = replay(), before = JSON.stringify(data), adapted = projectile.adapt(data);
  assert.equal(JSON.stringify(data), before);
  for (let i = 0; i < data.frames.length; i++) {
    const a = data.frames[i].objects[0], b = adapted.frames[i].objects[0];
    assert.deepEqual(b.position, a.position); assert.deepEqual(b.values.velocity, a.values.velocity);
    assert.equal(b.yaw, a.yaw); assert.equal(b.pitch, a.pitch);
    assert.equal(b.values.directionDiagnostics.fps, 60);
  }
  compileReplay(adapted);
  assert.ok(projectile.inspect({ sample: adapted.frames[0].objects[0], object: adapted.objects[0] }).some(row => row.label === '方向箭头'));
  const cannon = replay(); cannon.physics.entityClass = 'RVP_BulletEntity';
  assert.equal(withDirectionDiagnostics(cannon), cannon, '炮弹曳光不冒用导弹模型的35%平滑');
});

test('真实Three.js几何中三箭头同原点，世界方向正确，模型机头与紫色显示箭头一致', () => {
  const data = replay(), adapted = projectile.adapt(data), compiled = compileReplay(adapted), object = adapted.objects[0];
  const visual = createProjectile({ THREE, object }), attitude = new THREE.Group(); attitude.add(visual.root);
  attitude.position.set(12, 23, 34);
  for (const tick of [7.5, 1, 9, 0, 7.5]) {
    const sample = sampleReplay(compiled, tick).objects[0], diagnostic = directionTimeline(object).sample(tick);
    attitude.rotation.set(-THREE.MathUtils.degToRad(sample.pitch), -THREE.MathUtils.degToRad(sample.yaw), THREE.MathUtils.degToRad(sample.roll), 'YXZ');
    visual.update({ sample, tick }); attitude.updateMatrixWorld(true);
    const group = visual.root.getObjectByName('projectile-direction-arrows');
    for (const key of ['velocityDirection', 'bodyDirection', 'displayDirection']) {
      const arrow = group.getObjectByName(key), rotation = arrow.getWorldQuaternion(new THREE.Quaternion());
      closeVector(new THREE.Vector3(0, 1, 0).applyQuaternion(rotation).toArray(), diagnostic[key]);
      closeVector(arrow.getWorldPosition(new THREE.Vector3()).toArray(), [12, 23, 34]);
    }
    const rotation = visual.root.getWorldQuaternion(new THREE.Quaternion());
    closeVector(new THREE.Vector3(0, 0, 1).applyQuaternion(rotation).toArray(), diagnostic.displayDirection);
  }
  visual.root.traverse(node => { node.geometry?.dispose(); node.material?.dispose(); });
});

test('显示平滑系数与当前生产渲染器一致，防止离线估算静默过期', async () => {
  const source = await readFile(new URL('../../../src/main/java/org/ywzj/rvp/client/render/VehicleProjectileRenderLogic.java', import.meta.url), 'utf8');
  const match = source.match(/PROJECTILE_RENDER_ROT_SMOOTH\s*=\s*([\d.]+)F/);
  assert.ok(match); assert.equal(Number(match[1]), DISPLAY_ROTATION_BLEND);
});

test('HQ9B真实记录可以比较三档FPS，记录时刻真实机头保持在本次转弯内侧', async t => {
  let raw;
  try { raw = JSON.parse(await readFile(new URL('../../../build/projectile-simulation/hq9b_h100_v40.json', import.meta.url), 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') { t.skip('运行HQ9B仿真后验证真实实体记录'); return; } throw error; }
  const before = JSON.stringify(raw);
  for (const fps of [30, 60, 144]) {
    const input = { ...raw, objects: raw.objects.map(object => object.renderer === 'projectile:body'
      ? { ...object, attributes: { ...object.attributes, directionDiagnosticsFPS: fps } } : object) };
    const result = withDirectionDiagnostics(input), timeline = directionTimeline(result.objects[0]);
    assert.equal(timeline.sample(49).fps, fps);
    assert.ok(timeline.sample(49).modelLagDeg > 0);
    assert.ok(timeline.sample(49).bodyDirection[1] < timeline.sample(49).velocityDirection[1]);
    compileReplay({ ...result, format: 'rvp-test-replay', schemaVersion: 1 });
  }
  assert.equal(JSON.stringify(raw), before);
  let previous = null;
  for (const frame of raw.frames) {
    const sample = frame.objects.find(object => object.id === 'projectile'), velocity = new THREE.Vector3(...sample.values.velocity).normalize();
    if (previous) {
      const normal = velocity.clone().sub(previous).projectOnPlane(velocity);
      if (normal.length() > 1e-8) {
        const body = new THREE.Vector3(...directionFromRotation(sample.yaw, sample.pitch));
        assert.ok(body.dot(normal.normalize()) >= -1e-7, `真实逐刻机头不能落到弯外：第${frame.tick}刻`);
      }
    }
    previous = velocity;
  }
});
