// tools/fix-config-slugs.mjs —— 修正 pet-config.json 里 slug 错位（文件顺序与配置顺序不一致）
// 用法: node tools/fix-config-slugs.mjs <canonical.json> [--apply]
import { readFileSync, writeFileSync, copyFileSync, existsSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8').replace(/^\ufeff/, ''));
const canonPath = process.argv[2];
const apply = process.argv.includes('--apply');
if (!canonPath) { console.error('用法: node tools/fix-config-slugs.mjs <canonical.json> [--apply]'); process.exit(2); }

const canon = readJson(canonPath);
const map = readJson(join(root, 'anim-map.json'));
const cfgPath = join(root, 'app/src/main/assets/pet-config.json');
const cur = readJson(cfgPath);

// 1) 建置换：canonical slug -> 文件真实 slug
const perm = {};
for (const [slug, name] of Object.entries(canon.names)) {
  const t = map[name];
  if (!t) { console.error('anim-map 里找不到素材: ' + name); process.exit(3); }
  perm[slug] = t.file;
}
const targets = new Set(Object.values(perm));
if (targets.size !== Object.keys(perm).length) { console.error('置换不是双射'); process.exit(3); }
const tr = (s) => perm[s] ?? s;

// 2) 用 canonical 重建配置，只把 slug 全部翻译成新 slug
const names = {};
for (const [slug, name] of Object.entries(canon.names)) names[tr(slug)] = name;

const a = canon.animations;
const animations = {
  idle: (a.idle ?? []).map(tr),
  turn: (a.turn ?? []).map(tr),
  drag: (a.drag ?? []).map(tr),
  clicks: (a.clicks ?? []).map(tr),
  moves: {
    default: a.moves?.default,
    actions: (a.moves?.actions ?? []).map((x) => (x.name ? { ...x, name: tr(x.name) } : x)),
  },
  categories: (a.categories ?? []).map((c) => ({ ...c, actions: c.actions.map(tr) })),
  ...(a.extra ? { extra: a.extra } : {}),
};
const out = { ...canon, names, animations, durations: cur.durations };

// 3) 自检
const errs = [];
const allSlugs = new Set([...Object.keys(names), ...animations.idle, ...animations.turn, ...animations.drag, ...animations.clicks, ...animations.moves.actions.map(x => x.name), ...animations.categories.flatMap(c => c.actions)]);
for (const s of allSlugs) if (s && !/^a\d{3}$/.test(s)) errs.push('非法 slug: ' + s);
if (Object.keys(names).length !== 106) errs.push('names 条数=' + Object.keys(names).length);
if (Object.keys(cur.durations ?? {}).length !== 106) errs.push('durations 条数=' + Object.keys(cur.durations ?? {}).length);
for (const s of Object.keys(names)) if (!(s in (cur.durations ?? {}))) errs.push('缺时长: ' + s);
if (errs.length) { console.error('自检失败:\n  ' + errs.join('\n  ')); process.exit(4); }

console.log('置换 106/106 ✓  示例: a034(' + canon.names.a034 + ') -> ' + perm.a034 + ' = ' + names[perm.a034]);
console.log('idle=' + animations.idle.map(s => s + ':' + names[s]).join(','));
console.log('clicks=' + animations.clicks.map(s => s + ':' + names[s]).join(','));
if (apply) {
  const bak = cfgPath.replace(/pet-config\.json$/, 'pet-config.pre-fix.json');
  copyFileSync(cfgPath, bak);
  writeFileSync(cfgPath, JSON.stringify(out, null, 2) + '\n', 'utf8');
  console.log('已写入 ' + cfgPath + '（旧文件备份为 ' + bak + '）');
} else {
  console.log('（未加 --apply，仅演练）');
}
