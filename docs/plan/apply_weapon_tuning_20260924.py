# -*- coding: utf-8 -*-
"""
按用户填好的两份文档，把「曳光」与「近炸」的数值写进武器 JSON。

设计要点（外科式改写，绝不重排其余内容）：
- 只在目标对象的块内替换数值；块用花括号配对定位（正则无法区分同名键，如 proximity_radius 在 fuse_data / explosion_data 各一份）
- 保留每个文件自身的缩进（2/4 空格）、行尾（LF/CRLF/混合，逐行沿用）、末行换行与否
- 「取消近炸」= 把 fuse_data.proximity_radius 与 explosion_data.proximity_radius 都置 0
  （有效半径有回退链：fuse_data 优先，为空/0 时读 explosion_data 的那份；两者都 0 才真正关闭）
- 曳光「新值」列填的是**目标粗细（格）**，换算 caliber = 粗细 × 7.62 / 0.04 = 粗细 × 190.5

用法：
  python apply_weapon_tuning.py            # dry-run，只打印计划
  python apply_weapon_tuning.py --apply    # 真正写入
"""
import json
import os
import re
import sys

APPLY = '--apply' in sys.argv
ROOT = 'd:/ywzj/ywzj/ywzj_rvp/limitless_vehicle/rvp/'
WDIR = ROOT + 'data/rvp/weapons/'
MG_DOC = 'd:/ywzj/ywzj/ywzj_rvp/docs/plan/机枪曳光参数盘点_20260924.md'
FUSE_DOC = 'd:/ywzj/ywzj/ywzj_rvp/docs/plan/近炸武器参数盘点_20260924.md'


# ---------- 解析填好的文档 ----------
def doc_cells(path):
    for ln in open(path, encoding='utf-8'):
        if ln.startswith('| `'):
            cells = [c.strip().replace('**', '') for c in ln.strip().strip('|').split('|')]
            yield cells


def parse_mg():
    out = {}
    for c in doc_cells(MG_DOC):
        wid = c[0].strip('`')
        if '（本体）' in wid or len(c) < 4:
            continue
        d = {}
        if c[2]:
            try:
                width = float(c[2])
                d['caliber'] = round(width * 7.62 / 0.04, 3)      # 粗细 → caliber
                d['target_width'] = width
            except ValueError:
                pass
        if c[3]:
            try:
                d['scale'] = float(c[3])
            except ValueError:
                pass
        if d:
            out[wid] = d
    return out


def parse_fuse():
    out = {}
    for c in doc_cells(FUSE_DOC):
        wid = c[0].strip('`')
        if len(c) < 5:
            continue
        d = {}
        if c[2]:
            parts = [p.strip() for p in re.split(r'[/／]', c[2])]
            if len(parts) == 2:
                if parts[0] not in ('', '-', '–'):
                    d['tick'] = int(parts[0])
                if parts[1] not in ('', '-', '–'):
                    d['height'] = int(parts[1])
        if c[4]:
            try:
                d['radius'] = float(c[4])
            except ValueError:
                pass
        if d:
            out[wid] = d
    return out


# ---------- 块定位 ----------
def find_block(txt, key):
    """返回 key 对应对象的 (open_brace_idx, close_brace_idx)；找不到返回 None。"""
    m = re.search(r'"%s"\s*:\s*\{' % re.escape(key), txt)
    if not m:
        return None
    o = txt.index('{', m.start())
    depth = 0
    i = o
    instr = False
    esc = False
    while i < len(txt):
        ch = txt[i]
        if instr:
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == '"':
                instr = False
        else:
            if ch == '"':
                instr = True
            elif ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    return (o, i)
        i += 1
    return None


NUM = r'-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?'


def indent_unit(txt):
    """顶层属性的缩进单元（2 或 4 空格）。"""
    m = re.search(r'\n(\s+)"', txt)
    return m.group(1) if m else '  '


def insert_prop_before(txt, anchor_key, new_key, pairs):
    """在 anchor_key 所在行之前插入一个新对象属性（末带逗号）。返回 (新文本, 描述)"""
    m = re.search(r'"%s"\s*:' % re.escape(anchor_key), txt)
    if not m:
        return txt, '无锚点 %s ✗' % anchor_key
    nl = '\r\n' if '\r\n' in txt else '\n'
    ls = txt.rfind(nl, 0, m.start()) + len(nl)
    indent = txt[ls:m.start()]
    if '\r' in indent or '"' in indent:
        indent = re.match(r'[\t ]*', txt[ls:]).group(0)
    unit = indent_unit(txt)
    block = ('%s"%s": {' % (indent, new_key) + nl
             + nl.join('%s"%s": %s,' % (indent + unit, k, v) for k, v in pairs) + nl
             + indent + '},' + nl)
    # 去掉最后一条的多余逗号
    block = block.replace(',' + nl + indent + '},', nl + indent + '},')
    return txt[:ls] + block + txt[ls:], '新建 %s%s' % (new_key, dict(pairs))


def block_set(txt, key, name, value):
    """在 key 对象内把 name 设为 value；不存在则插入为第一条属性。返回 (新文本, 动作描述)"""
    blk = find_block(txt, key)
    if blk is None:
        return None, 'block:%s 不存在' % key
    o, c = blk
    inner = txt[o + 1:c]
    pat = re.compile(r'("' + re.escape(name) + r'"\s*:\s*)(' + NUM + r'|true|false)')
    m = pat.search(inner)
    if m:
        if m.group(2) == str(value):
            return txt, '已是 %s' % value
        new_inner = inner[:m.start(2)] + str(value) + inner[m.end(2):]
        return txt[:o + 1] + new_inner + txt[c:], '改 %s=%s' % (name, value)
    # 插入：在块内第一条属性前插入
    m2 = re.search(r'(\r\n|\n)(\s*)"', inner)
    if m2:
        nl, ind = m2.group(1), m2.group(2)
        ins = nl + ind + '"%s": %s,' % (name, value)
        return txt[:o + 1] + inner[:m2.start()] + ins + inner[m2.start():] + txt[c:], '加 %s=%s' % (name, value)
    if inner.strip() == '':
        # 空块 {} → 展开
        nl = '\r\n' if '\r\n' in txt else '\n'
        ind = '  '
        new_inner = nl + ind + '"%s": %s' % (name, value) + nl
        return txt[:o + 1] + new_inner + txt[c:], '加 %s=%s(空块展开)' % (name, value)
    return txt, '插入失败(块格式未知)'


def bset(txt, key, name, value, log):
    txt2, act = block_set(txt, key, name, value)
    if txt2 is None:
        log.append('✗ %s' % act)
        return txt
    log.append(act)
    return txt2


def apply_mg(txt, spec):
    log = []
    if 'caliber' in spec:
        blk = find_block(txt, 'effects_data')
        if blk is None:
            return txt, ['effects_data 缺失 ✗']
        o, c = blk
        inner = txt[o + 1:c]
        pat = re.compile(r'("caliber"\s*:\s*)(' + NUM + r')')
        m = pat.search(inner)
        if m:
            new_inner = inner[:m.start(2)] + '%s' % spec['caliber'] + inner[m.end(2):]
            txt = txt[:o + 1] + new_inner + txt[c:]
            log.append('caliber=%s (粗细→%.4f)' % (spec['caliber'], spec.get('target_width', 0)))
        else:
            txt = bset(txt, 'effects_data', 'caliber', spec['caliber'], log)
    if 'scale' in spec:
        txt = bset(txt, 'effects_data', 'tracer_length_scale', spec['scale'], log)
    return txt, log


def apply_fuse(txt, spec):
    log = []
    need_pairs = []
    if 'tick' in spec:
        need_pairs.append(('proximity_fuse_tick', spec['tick']))
    if 'height' in spec:
        need_pairs.append(('proximity_fuse_height', spec['height']))
    if 'radius' in spec and spec['radius'] > 0:
        need_pairs.append(('proximity_radius', spec['radius']))

    # fuse_data 缺失时新建（放在 detonate_data 之前）
    if find_block(txt, 'fuse_data') is None:
        if need_pairs:
            txt, act = insert_prop_before(txt, 'detonate_data', 'fuse_data', need_pairs)
            log.append(act)
            need_pairs = []
        elif 'radius' in spec and spec['radius'] == 0:
            log.append('fuse_data 不存在，取消近炸只需处理 explosion')
    for k, v in need_pairs:
        txt = bset(txt, 'fuse_data', k, v, log)

    if 'radius' in spec:
        r = spec['radius']
        if r == 0:
            if find_block(txt, 'fuse_data'):
                txt = bset(txt, 'fuse_data', 'proximity_radius', 0, log)
            blk = find_block(txt, 'explosion_data')
            if blk:
                inner_ex = txt[blk[0] + 1:blk[1]]
                if re.search(r'"proximity_radius"\s*:', inner_ex):
                    txt = bset(txt, 'explosion_data', 'proximity_radius', 0, log)
                else:
                    log.append('explosion 无 proximity_radius(无需处理)')
    return txt, log


# ---------- 主流程 ----------
def main():
    mg = parse_mg()
    fu = parse_fuse()
    print('文档解析：曳光 %d 条，近炸 %d 条\n' % (len(mg), len(fu)))

    all_ids = sorted(set(mg) | set(fu))
    plan = []
    for wid in all_ids:
        p = WDIR + wid + '.json'
        if not os.path.exists(p):
            plan.append((wid, ['文件不存在 ✗'], None))
            continue
        raw = open(p, 'rb').read()
        txt = raw.decode('utf-8')
        before = json.loads(txt)          # 解析校验
        logs = []
        if wid in mg:
            txt, lg = apply_mg(txt, mg[wid])
            logs += lg
        if wid in fu:
            txt, lg = apply_fuse(txt, fu[wid])
            logs += lg
        # 语义校验
        try:
            after = json.loads(txt)
            ok = True
            err = ''
        except Exception as e:
            ok = False
            err = str(e)
            after = None
        plan.append((wid, logs, (ok, err, before, after, txt, raw)))

    # 打印计划
    for wid, logs, extra in plan:
        print('%-30s %s' % (wid, ' | '.join(logs)))
        if extra and not extra[0]:
            print('      ✗ JSON 解析失败: %s' % extra[1])

    print('\n=== 语义复核（改动后生效值）===')
    bad = 0
    for wid, logs, extra in plan:
        if not extra:
            bad += 1
            continue
        ok, err, before, after, txt, raw = extra
        if not ok:
            bad += 1
            continue
        msgs = []
        if wid in mg:
            e = (after.get('effects_data') or {})
            cal = e.get('caliber')
            cal = cal if (cal is not None and cal > 0) else 7.62
            w = min(0.04 * cal / 7.62, 0.2)
            msgs.append('宽=%.4f' % w)
            if 'scale' in mg[wid]:
                msgs.append('倍率=%s' % e.get('tracer_length_scale'))
        if wid in fu:
            fd = (after.get('fuse_data') or {})
            ex = (after.get('detonate_data') or {}).get('explosion_data') or {}
            fr = fd.get('proximity_radius') or 0
            er = (ex.get('proximity_radius') or 0) if ex.get('proximity_fuze') else 0
            eff = fr if fr > 0 else er
            msgs.append('半径=%s' % eff)
            if 'tick' in fu[wid] or 'height' in fu[wid]:
                msgs.append('保险=%s/%s' % (fd.get('proximity_fuse_tick', '未写'), fd.get('proximity_fuse_height', '未写')))
        print('%-30s %s' % (wid, '  '.join(msgs)))

    if not APPLY:
        print('\n[dry-run] 未写入。加 --apply 执行。')
        return
    n = 0
    for wid, logs, extra in plan:
        if not extra:
            continue
        ok, err, before, after, txt, raw = extra
        if not ok:
            print('跳过（解析失败）:', wid)
            continue
        if txt == raw.decode('utf-8'):
            continue
        p = WDIR + wid + '.json'
        open(p, 'wb').write(txt.encode('utf-8'))
        n += 1
    print('\n已写入 %d 个文件' % n)


main()
