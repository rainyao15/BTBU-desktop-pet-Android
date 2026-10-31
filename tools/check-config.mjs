import { readFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const map = JSON.parse(readFileSync(join(root, 'anim-map.json'), 'utf8').replace(/^\ufeff/, ''));
const cfg = JSON.parse(readFileSync(join(root, 'app/src/main/assets/pet-config.json'), 'utf8'));

const expect = {};
for (const [name, v] of Object.entries(map)) expect[v.file] = name;

let ok = 0; const bad = [];
for (const [slug, name] of Object.entries(cfg.names ?? {})) {
  if (expect[slug] === name) ok++; else bad.push(slug + ': 配置=「' + name + '」 文件实际=「' + (expect[slug] ?? '?') + '」');
}
console.log('names 与文件实际一致: ' + ok + '/' + Object.keys(cfg.names ?? {}).length);
bad.slice(0, 5).forEach(s => console.log('   ✗ ' + s));

const show = (label, slugs) => console.log(label + ': ' + slugs.map(s => s + '=' + (expect[s] ?? '?')).join(' | '));
show('idle  ', cfg.animations?.idle ?? []);
show('turn  ', cfg.animations?.turn ?? []);
show('drag  ', cfg.animations?.drag ?? []);
show('clicks', cfg.animations?.clicks ?? []);
show('moves ', (cfg.animations?.moves?.actions ?? []).map(a => a.name));
for (const c of cfg.animations?.categories ?? []) {
  console.log('类别「' + c.id + '」' + c.actions.length + ' 条，前 4: ' + c.actions.slice(0,4).map(s => s + '=' + (expect[s] ?? '?')).join(' | '));
}
const m = re => Object.keys(expect).filter(s => re.test(s)).map(s => s + '->' + (Object.entries(map).find(([n]) => n === s)?.[1].file));
console.log('文件名含「点击回应」的: ' + m(/^点击回应/).join(', '));
console.log('文件名含「待机/转向/拖拽/移动」的: ' + m(/待机|转向|拖拽/).join(', '));
