import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateReplay } from '../core/replay.js';

/** 可重复生成的解析抛体演示，用来展示另一领域的测试接入，不冒充游戏弹道。 */
export function createDemo() {
  const frames = [];
  for (let tick = 0; tick <= 80; tick += 2) {
    const seconds = tick / 20, verticalSpeed = 20 - 10 * seconds;
    frames.push({ tick, objects: [
      { id: 'probe', active: true, position: [20 * seconds, 20 * seconds - 5 * seconds ** 2, 0],
        yaw: -90, pitch: Math.atan2(verticalSpeed, 20) * 180 / Math.PI, roll: 0, targetId: 'landing',
        values: { '水平速度（格/秒）': 20, '竖直速度（格/秒）': verticalSpeed, '理论飞行时间（秒）': seconds } },
      { id: 'landing', active: true, position: [80, 0, 0], yaw: 0, pitch: 0, roll: 0, values: { '标记用途': '理论落点，不计算命中' } },
    ] });
  }
  return validateReplay({
    /** format/schemaVersion 是通用记录唯一识别方式。 */
    format: 'rvp-test-replay', schemaVersion: 1, id: 'analytic_projectile', name: '解析抛体 · 扩展接入示例',
    description: '用数学公式直接生成轨迹；演示稀疏采样、观察值和断言结果。此场景未调用 RVP 弹体算法。',
    tickRate: 20, groundY: 0,
    /** 对象使用主体内置图形，不需要编写 Three.js。 */
    objects: [
      { id: 'probe', name: '抛体采样点', renderer: 'sphere', color: '#efc77a', size: [2, 2, 2] },
      { id: 'landing', name: '理论落点', renderer: 'box', color: '#77d3c1', size: [4, 0.5, 4] },
    ],
    obstacles: [], frames,
    /** 事件与对象数据分开，非采样刻也可以放事件。 */
    events: [{ tick: 0, objectId: 'probe', type: 'START', message: '初速：水平 20、竖直 20 格/秒' },
      { tick: 40, objectId: 'probe', type: 'APEX', message: '到达理论最高点：20 格' },
      { tick: 80, objectId: 'probe', type: 'LANDING', message: '到达理论落点：X=80 格' }],
    summary: [{ label: '记录间隔', value: '2 刻' }, { label: '理论最高点', value: '20 格' }, { label: '理论航程', value: '80 格' }],
    /** 演示断言只检查公式自身，实际算法须由测试进程生成自己的检查结果。 */
    checks: [
      { name: '最高点符合公式', status: Math.abs(frames[20].objects[0].position[1] - 20) < 1e-9 ? 'passed' : 'failed', message: 't=2 秒时 Y=20' },
      { name: '终点符合公式', status: frames.at(-1).objects[0].position[0] === 80 && frames.at(-1).objects[0].position[1] === 0 ? 'passed' : 'failed', message: 't=4 秒时 X=80、Y=0' },
    ],
    coverage: ['解析公式：X=20t，Y=20t-5t²；仅用于工具扩展和回放功能演示。'],
    limitations: ['不是 RVP 生产弹道测试，不含阻力、制导、碰撞、伤害。', '每两刻采样一次，画面在相邻采样点间做直线插值。'],
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = fileURLToPath(new URL('../plugins/standard/records/', import.meta.url));
  await mkdir(root, { recursive: true });
  let entries = [];
  try {
    const index = JSON.parse((await readFile(resolve(root, 'index.json'), 'utf8')).replace(/^\uFEFF/, ''));
    if (index.schemaVersion !== 1 || !Array.isArray(index.replays)) throw new Error('示例索引不是当前格式，请先检查 index.json');
    entries = index.replays.filter(entry => entry.id !== 'analytic_projectile');
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  await writeFile(resolve(root, 'analytic_projectile.json'), JSON.stringify(createDemo()) + '\n', 'utf8');
  await writeFile(resolve(root, 'index.json'), JSON.stringify({ schemaVersion: 1,
    replays: [...entries, { id: 'analytic_projectile', name: '解析抛体 · 扩展接入示例', file: 'analytic_projectile.json' }] }, null, 2) + '\n', 'utf8');
  console.log('已生成独立示例；不需要修改工作台主体。');
}
