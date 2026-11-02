import { readdir, realpath, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { confinedFile, FileError, readJSON } from '../../lib/catalog.mjs';
import { normalizeScenario, validWeaponId } from './scenario.js';

/** 场景输入唯一目录；不读取 run 沙盒或废止的权威包。 */
const SCENARIO_ROOT = 'src/test/resources/projectile-simulation';
/** 武器只读项目根权威副本。 */
const WEAPON_ROOT = 'limitless_vehicle/rvp/data/rvp/weapons';
/** 与 Java 运行器支持的六种实体类型一致。 */
const FLIGHT_TYPES = new Set(['rvp:missile', 'rvp:rocket', 'rvp:machinegun', 'rvp:bomb', 'rvp:torpedo', 'rvp:dispenser']);
/** 场景不包含回放帧，限制为 128 KiB，避免意外把大结果文件提交到保存接口。 */
const MAX_SCENARIO_BYTES = 128 * 1024;

/** 只收集真实目录中的当前 JSON；备份目录和链接不参与递归。 */
async function weaponFiles(root, directory = '') {
  const files = [];
  for (const entry of await readdir(join(root, directory), { withFileTypes: true })) {
    if (entry.name.startsWith('_')) continue;
    const relative = directory ? `${directory}/${entry.name}` : entry.name;
    if (entry.isDirectory()) files.push(...await weaponFiles(root, relative));
    else if (entry.isFile() && entry.name.endsWith('.json') && validWeaponId(`rvp:${relative.slice(0, -5)}`)) files.push(relative);
  }
  return files.sort();
}

/** 目录只带名称、类型和原始配置提示；不在 Node 重写生产初速解析器。 */
async function readWeapons(projectRoot, warnings) {
  const root = resolve(projectRoot, WEAPON_ROOT), weapons = [];
  try {
    for (const file of await weaponFiles(root)) {
      try {
        const data = await readJSON(await confinedFile(root, file), 1024 * 1024);
        if (!FLIGHT_TYPES.has(data.type)) continue;
        weapons.push({ id: `rvp:${file.slice(0, -5)}`, name: data.name_CN || data.name || file.slice(0, -5),
          type: data.type, inheritVehicleVelocity: data.projectile_data?.inherit_vehicle_velocity === true });
      } catch (error) { warnings.push(`武器 ${file}：${error.message}`); }
    }
  } catch (error) { warnings.push(`权威武器目录不可读：${error.message}`); }
  return weapons;
}

/** Java 默认扫描顶层 JSON；此处遵循相同范围，坏场景单独提示。 */
async function readScenarios(projectRoot, warnings) {
  const root = resolve(projectRoot, SCENARIO_ROOT), scenarios = [];
  try {
    const entries = await readdir(root, { withFileTypes: true });
    for (const entry of entries.filter(e => e.isFile() && e.name.endsWith('.json')).sort((a, b) => a.name.localeCompare(b.name))) {
      try {
        const scene = normalizeScenario(await readJSON(await confinedFile(root, entry.name), MAX_SCENARIO_BYTES));
        scenarios.push({ file: entry.name, scene });
      } catch (error) { warnings.push(`场景 ${entry.name}：${error.message}`); }
    }
  } catch (error) { warnings.push(`场景目录不可读：${error.message}`); }
  return scenarios;
}

/** 限量读取请求；不允许表单、任意路径或外部命令。 */
async function readBody(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? '')) throw new FileError(415, '保存需要 application/json');
  if (Number(request.headers['content-length']) > MAX_SCENARIO_BYTES) throw new FileError(413, '场景超过 128 KiB');
  const chunks = []; let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_SCENARIO_BYTES) throw new FileError(413, '场景超过 128 KiB');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8').replace(/^\uFEFF/, ''));
}

/** 插件自有接口：提供模板/武器目录，以及仅创建新场景的保存操作。 */
export async function handleRequest({ request, path, projectRoot, send }) {
  if (path === '/catalog' && ['GET', 'HEAD'].includes(request.method)) {
    const warnings = [];
    // 调用两类只读目录读取器，供编辑页选择模板和当前有效武器。
    const scenarios = await readScenarios(projectRoot, warnings), weapons = await readWeapons(projectRoot, warnings);
    send(200, 'application/json; charset=utf-8', JSON.stringify({ scenarios, weapons, warnings })); return;
  }
  if (path === '/scenarios' && request.method === 'POST') {
    // 校验当前 schema 后才确定文件名，浏览器不能选择保存目录。
    const scene = normalizeScenario(await readBody(request));
    const weapon = await readJSON(await confinedFile(resolve(projectRoot, WEAPON_ROOT), `${scene.weapon.slice(4)}.json`), 1024 * 1024);
    if (!FLIGHT_TYPES.has(weapon.type)) throw new FileError(400, '该武器不产生受支持的飞行实体');
    const root = await realpath(resolve(projectRoot, SCENARIO_ROOT));
    // 同 ID 即便放在另一个名称的文件中，也不能新增，否则 Java 批量任务会拒绝重复 ID。
    const existing = await readScenarios(projectRoot, []);
    if (existing.some(entry => entry.scene.id === scene.id)) throw new FileError(409, '已有相同 id 的场景，请使用“复制为新用例”或修改 id');
    try {
      // wx 使用文件系统的独占创建；重名文件、链接以及并发保存都不能覆盖原文件。
      await writeFile(join(root, `${scene.id}.json`), `${JSON.stringify(scene, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
    } catch (error) {
      if (error.code === 'EEXIST') throw new FileError(409, '同名文件已存在，请修改 id 后保存');
      throw error;
    }
    send(201, 'application/json; charset=utf-8', JSON.stringify({ file: `${SCENARIO_ROOT}/${scene.id}.json` })); return;
  }
  if (['/catalog', '/scenarios'].includes(path)) throw new FileError(405, '此接口不支持该请求方式');
  throw new FileError(404, '没有此插件接口');
}
