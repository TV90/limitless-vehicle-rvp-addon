# -*- coding: utf-8 -*-
"""
Mixin remap 防回归检查（2026-09-20 生产崩溃教训）。
规则：@Mixin(remap = false) 的 mixin 里，@At target 若引用原版方法
（字符串形如 Lnet/minecraft/...;方法名(，注意 owner 后的分号+方法名），
必须在该文件显式写 remap = true，否则注解处理器不会为其生成 refmap 条目，
生产 SRG 环境下注入点扫描为 0：require>=1 直接崩溃，require=0 静默失效。
类名（描述符里的 Lnet/minecraft/...;）不受影响，1.20.1 生产只混淆方法/字段名。

用法：python scripts/check_mixin_remap.py  （违例时退出码 1）
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
MIXIN_DIR = os.path.join(ROOT, 'src', 'main', 'java', 'org', 'ywzj', 'rvp', 'mixin')

REMIX_FALSE = re.compile(r'@Mixin\s*\([^)]*remap\s*=\s*false', re.S)
VANILLA_TARGET = re.compile(r'target\s*=\s*"(Lnet/minecraft/[A-Za-z0-9/]+;[A-Za-z0-9_]+\()')
REMAP_TRUE = re.compile(r'remap\s*=\s*true')


def main():
    violations = []
    for dirpath, _, files in os.walk(MIXIN_DIR):
        for name in files:
            if not name.endswith('.java'):
                continue
            path = os.path.join(dirpath, name)
            with io_open(path) as fp:
                src = fp.read()
            if not REMIX_FALSE.search(src):
                continue
            if VANILLA_TARGET.search(src) and not REMAP_TRUE.search(src):
                rel = os.path.relpath(path, ROOT)
                violations.append('%s: remap=false mixin 含原版方法 target 但无 remap = true' % rel)
    if violations:
        for v in violations:
            print('[FAIL] ' + v)
        sys.exit(1)
    print('mixin remap check: OK')


def io_open(path):
    import io
    return io.open(path, encoding='utf-8')


if __name__ == '__main__':
    main()
