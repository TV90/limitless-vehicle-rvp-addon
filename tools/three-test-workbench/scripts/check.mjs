import { readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

/** 递归校验本工具所有 JavaScript，不扫描已安装依赖。 */
async function checkDirectory(root) {
  for (const entry of await readdir(root, { withFileTypes: true })) {
    if (entry.name === 'node_modules') continue;
    const path = join(root, entry.name);
    if (entry.isDirectory()) await checkDirectory(path);
    else if (/\.m?js$/.test(entry.name)) {
      const result = spawnSync(process.execPath, ['--check', path], { encoding: 'utf8', windowsHide: true });
      if (result.status !== 0) throw new Error(result.stderr || result.error?.message || `语法检查失败：${path}`);
    }
  }
}
await checkDirectory(fileURLToPath(new URL('../', import.meta.url)));
console.log('所有工作台 JavaScript 语法检查通过。');
