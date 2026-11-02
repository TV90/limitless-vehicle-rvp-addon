import { readFile, readdir, realpath, stat } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import { isId, MAX_RECORD_BYTES } from '../core/replay.js';

/** 带 HTTP 状态的可读错误，避免路由各自重复解释文件系统错误。 */
export class FileError extends Error {
  /** status 为 HTTP 响应码；message 为本地页面可直接展示的说明。 */
  constructor(status, message) { super(message); this.status = status; }
}

/** 阻止路径逃出指定目录；同时按 Windows 与 URL 的分隔符检查。 */
export function resolveInside(root, path) {
  if (typeof path !== 'string' || !path || isAbsolute(path) || /^[a-z]:/i.test(path)
    || path.includes('\0') || path.split(/[\\/]/).includes('..')) throw new FileError(403, '路径超出允许目录');
  const target = resolve(root, path), child = relative(resolve(root), target);
  if (!child || child === '..' || child.startsWith(`..${sep}`) || isAbsolute(child)) throw new FileError(403, '路径超出允许目录');
  return target;
}

/** 再检查磁盘实际位置，防止符号链接把允许目录内的路径引向目录外。 */
export async function confinedFile(root, path) {
  const expected = resolveInside(root, path);
  const actualRoot = await realpath(root), actual = await realpath(expected);
  const child = relative(actualRoot, actual);
  if (!child || child === '..' || child.startsWith(`..${sep}`) || isAbsolute(child)) throw new FileError(403, '文件链接超出允许目录');
  return actual;
}

/** 先检查大小再读取；目录不能作为数据文件提供。 */
export async function readLimited(file, limit = MAX_RECORD_BYTES) {
  const info = await stat(file);
  if (!info.isFile()) throw new FileError(404, '需要一个普通文件');
  if (info.size > limit) throw new FileError(413, `文件超过 ${Math.round(limit / 1024 / 1024)} MB`);
  const data = await readFile(file);
  if (data.length > limit) throw new FileError(413, '读取期间文件超过大小限制');
  return data;
}

/** UTF-8 记录与配置允许文件头 BOM，内容仍只接受当前明确格式。 */
export async function readJSON(file, limit) {
  return JSON.parse((await readLimited(file, limit)).toString('utf8').replace(/^\uFEFF/, ''));
}

/** 自动发现 plugins/<id>/plugin.json；一个坏插件或缺失数据源不阻断其他测试。 */
export async function discoverCatalog({ pluginRoot, projectRoot, env = process.env }) {
  const plugins = [], warnings = [], records = new Map();
  let directories;
  try { directories = await readdir(pluginRoot, { withFileTypes: true }); }
  catch (error) { return { plugins, warnings: [`无法读取插件目录：${error.message}`], records }; }
  for (const directory of directories.filter(item => item.isDirectory()).sort((a, b) => a.name.localeCompare(b.name))) {
    try {
      const folder = resolveInside(pluginRoot, directory.name);
      const manifest = await readJSON(await confinedFile(pluginRoot, `${directory.name}/plugin.json`), 1024 * 1024);
      if (manifest?.schemaVersion !== 1 || manifest.id !== directory.name || !isId(manifest.id)
        || typeof manifest.name !== 'string' || typeof manifest.module !== 'string' || !manifest.module.endsWith('.js')
        || !Array.isArray(manifest.sources)) throw new Error('plugin.json 需要当前版本、同名 id、name、module 与 sources');
      await confinedFile(folder, manifest.module);
      const plugin = { id: manifest.id, name: manifest.name,
        module: `/plugins/${directory.name}/${manifest.module.split(/[\\/]/).map(encodeURIComponent).join('/')}`, cases: [], warnings: [] };
      const sources = new Set();
      for (const source of manifest.sources) {
        try {
          if (!source || !isId(source.id) || sources.has(source.id) || typeof source.root !== 'string'
            || typeof source.index !== 'string' || !source.index.endsWith('.json')
            || (source.rootEnv !== undefined && !/^[A-Z][A-Z0-9_]*$/.test(source.rootEnv))) throw new Error('数据源的 id、root、index 或 rootEnv 无效/重复');
          sources.add(source.id);
          // root 来自本地插件清单；环境变量只在清单主动声明时覆盖目录。
          const root = source.rootEnv && env[source.rootEnv]
            ? resolve(env[source.rootEnv]) : resolveInside(projectRoot, source.root);
          const index = await readJSON(await confinedFile(root, source.index), 1024 * 1024);
          if (index?.schemaVersion !== 1 || !Array.isArray(index.replays)) throw new Error('索引需要 schemaVersion=1 与 replays 数组');
          const ids = new Set(), entries = [];
          for (const entry of index.replays) {
            if (!entry || !isId(entry.id) || ids.has(entry.id) || typeof entry.name !== 'string'
              || typeof entry.file !== 'string' || !entry.file.endsWith('.json')) throw new Error('索引条目的 id、name、file 无效/重复');
            resolveInside(root, entry.file); ids.add(entry.id);
            const url = `/api/records/${manifest.id}/${source.id}/${entry.id}`;
            entries.push({ entry, url });
          }
          // 整个索引通过后才公开条目，避免坏索引留下半份目录。
          for (const { entry, url } of entries) {
            plugin.cases.push({ id: `${source.id}/${entry.id}`, name: entry.name, url });
            records.set(url, { root, file: entry.file });
          }
        } catch (error) {
          plugin.warnings.push(`数据源 ${source?.id ?? '?'}：${error.message}${source?.hint ? `；${source.hint}` : ''}`);
        }
      }
      plugins.push(plugin);
    } catch (error) { warnings.push(`插件 ${directory.name} 未加载：${error.message}`); }
  }
  return { plugins, warnings, records };
}
