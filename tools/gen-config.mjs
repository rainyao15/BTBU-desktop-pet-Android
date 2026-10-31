#!/usr/bin/env node
/**
 * 从 dsh-pet 的 assets/config.jsonc 生成手机端配置 pet-config.json。
 *
 * 只保留手机端需要的东西：动画池、动画权重、物理参数、宠物外观。
 * 碎碎念 / 对话 / 余额 / 通知 / memes 全部丢弃（手机版没有 AI，也不联网）。
 *
 * JSONC → JSON：自己实现一个字符串感知的扫描器，去掉 // 与块注释和尾逗号。
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

// 位置参数：config.jsonc / anim-map.json / out.json
// 可选参数：--name <宠物名>  --durations <durations.json>
const argv = process.argv.slice(2);
const flags = { name: null, durations: null };
const positional = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--name') { flags.name = argv[++i] ?? null; continue; }
  if (a === '--durations') { flags.durations = argv[++i] ?? null; continue; }
  positional.push(a);
}
const [PLUGIN_CONFIG, ANIM_MAP, OUT] = positional;

if (!PLUGIN_CONFIG || !ANIM_MAP || !OUT) {
  console.error('usage: node gen-config.mjs <config.jsonc> <anim-map.json> <out.json> [--name 名字] [--durations durations.json]');
  process.exit(2);
}

/** 去掉 JSONC 的注释与尾逗号（不动字符串内容） */
function stripJsonc(src) {
  let out = '';
  let i = 0;
  let inStr = false;
  let quote = '';
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (inStr) {
      out += c;
      if (c === '\\') { out += n ?? ''; i += 2; continue; }
      if (c === quote) inStr = false;
      i++;
      continue;
    }
    if (c === '"' || c === "'") { inStr = true; quote = c; out += c; i++; continue; }
    if (c === '/' && n === '/') { while (i < src.length && src[i] !== '\n') i++; continue; }
    if (c === '/' && n === '*') { i += 2; while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) i++; i += 2; continue; }
    out += c;
    i++;
  }
  // 尾逗号：,] 与 ,}
  return out.replace(/,(\s*[}\]])/g, '$1');
}

const raw = readFileSync(PLUGIN_CONFIG, 'utf8').replace(/^\uFEFF/, '');
const cfg = JSON.parse(stripJsonc(raw));
const map = JSON.parse(readFileSync(ANIM_MAP, 'utf8').replace(/^\uFEFF/, ''));

const missing = new Set();
function slugOf(name) {
  const hit = map[name];
  if (!hit) { missing.add(name); return null; }
  return hit.file;
}
function slugList(list) {
  const out = [];
  for (const n of list ?? []) {
    const s = slugOf(n);
    if (s) out.push(s);
  }
  return out;
}

const anims = cfg.animations ?? {};
const movesSrc = anims.moves ?? {};
const moveActions = [];
for (const a of movesSrc.actions ?? []) {
  const s = slugOf(a.name);
  if (!s) continue;
  const p = a.params ?? {};
  moveActions.push({
    name: s,
    ...(p.minDist !== undefined ? { minDist: p.minDist } : {}),
    ...(p.maxDist !== undefined ? { maxDist: p.maxDist } : {}),
  });
}

const categories = [];
for (const c of anims.categories ?? []) {
  const acts = slugList(c.actions);
  if (acts.length === 0) continue;
  categories.push({
    id: c.id,
    weight: c.weight ?? 0,
    actions: acts,
    ...(c.noMirror ? { noMirror: true } : {}),
  });
}

// 手机端尺寸：桌面配置里的 462 是 640×360 画布下的桌面像素，手机用 dp；给个适合手掌的默认值
const pets = (cfg.pets ?? []).map((p, i) => ({
  id: p.id ?? `pet${i}`,
  name: flags.name ?? p.name ?? `宠物${i + 1}`,
  size: 150,
  corner: p.position?.corner ?? 'top-right',
  marginX: p.position?.marginX ?? 24,
  marginY: p.position?.marginY ?? 100,
}));
if (pets.length === 0) pets.push({ id: 'main', name: '宠物', size: 150, corner: 'top-right', marginX: 24, marginY: 100 });

// 动画 slug -> 中文名（长按菜单里要显示可读的名字，配置里只存 slug）
const names = {};
for (const [n, v] of Object.entries(map)) names[v.file] = n;

// 每段动画的时长（毫秒）。由 tools/make-durations.py 按帧数/15fps 算出。
// 手机端靠这个时长在"播完一遍"时切下一段，避免依赖 AnimatedImageDrawable 的 repeat 语义。
const durations = {};
if (flags.durations && existsSync(flags.durations)) {
  Object.assign(durations, JSON.parse(readFileSync(flags.durations, 'utf8').replace(/^\uFEFF/, '')));
}

const out = {
  pets,
  names,
  durations,
  animations: {
    idle: slugList(anims.idle),
    turn: slugList(anims.turn),
    drag: slugList(anims.drag),
    clicks: slugList(anims.clicks),
    moves: {
      default: movesSrc.default ?? { minDist: 60, maxDist: 240, margin: 20 },
      actions: moveActions,
    },
    categories,
  },
  animationWeights: cfg.animationWeights ?? { idle: 10, turn: 5, move: 5 },
  physics: cfg.physics ?? {
    gravity: 1400,
    restitution: 0.78,
    groundFriction: 2.5,
    ceilingBounce: true,
    throwPower: 1,
  },
};

writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n', 'utf8');

const total =
  out.animations.idle.length + out.animations.turn.length + out.animations.drag.length +
  out.animations.clicks.length + moveActions.length +
  categories.reduce((n, c) => n + c.actions.length, 0);

console.log(`写出: ${OUT}`);
console.log(`宠物 ${pets.length} 只 | 待机 ${out.animations.idle.length} / 转向 ${out.animations.turn.length} / 拖拽 ${out.animations.drag.length} / 点击 ${out.animations.clicks.length} / 移动 ${moveActions.length}`);
console.log(`随机动作分类 ${categories.length} 个，动作合计 ${categories.reduce((n, c) => n + c.actions.length, 0)}；引用动画总数 ${total}`);
console.log(`未在 anim-map 中找到的动画名: ${missing.size === 0 ? '无' : [...missing].join(', ')}`);
