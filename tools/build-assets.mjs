#!/usr/bin/env node
/**
 * 从 dsh-pet 的素材目录批量转码成 480px 动画 WebP，并生成 anim-map.json 与 pet-config.json。
 *
 * 为什么单独成一个脚本：PowerShell 与 Bash 的排序规则对中文文件名可能不同，
 * slug（a001/a002…）一旦错位，配置里的动画就会指错段。这里用 Node 统一排序，两端共用。
 *
 * 用法：
 *   node tools/build-assets.mjs --pkg <dsh-pet 包目录> --out app/src/main/assets/webp \
 *        [--width 480] [--quality 50] [--fps 15] [--ffmpeg ffmpeg] [--force]
 */
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');

const argv = process.argv.slice(2);
const opt = { width: '480', quality: '50', fps: '15', ffmpeg: 'ffmpeg', force: false };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--force') { opt.force = true; continue; }
  if (a.startsWith('--')) {
    opt[a.slice(2)] = argv[i + 1];
    i++;
  }
}
if (!opt.pkg || !opt.out) {
  console.error('usage: node tools/build-assets.mjs --pkg <dsh-pet package dir> --out <assets/webp> [--width 480] [--quality 50] [--fps 15] [--ffmpeg ffmpeg] [--force]');
  process.exit(2);
}

const srcDir = resolve(opt.pkg, 'assets', 'webm');
const configJsonc = resolve(opt.pkg, 'assets', 'config.jsonc');
const outDir = resolve(repoRoot, opt.out);
const mapPath = join(repoRoot, 'anim-map.json');
const petConfigPath = resolve(outDir, '..', 'pet-config.json');

if (!existsSync(srcDir)) {
  console.error(`找不到素材目录: ${srcDir}`);
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });

// 逐字符比较，跨平台一致（不用 localeCompare，避免不同系统/区域设置排法不同）
const files = readdirSync(srcDir, { withFileTypes: true })
  .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.webm'))
  .map((e) => e.name)
  .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));

console.log(`转码 ${files.length} 段 → ${opt.width}px / q${opt.quality} / ${opt.fps}fps`);
const map = {};
let i = 0;
for (const name of files) {
  i++;
  const slug = 'a' + String(i).padStart(3, '0');
  const dst = join(outDir, slug + '.webp');
  if (existsSync(dst) && !opt.force) {
    map[name.replace(/\.webm$/i, '')] = { file: slug, kb: Math.round(existsSync(dst) ? 0 : 0) };
    continue;
  }
  execFileSync(opt.ffmpeg, [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-c:v', 'libvpx-vp9',            // 必须：默认解码器会丢掉 alpha
    '-i', join(srcDir, name),
    '-vf', `fps=${opt.fps},scale=${opt.width}:-2:flags=lanczos`,
    '-c:v', 'libwebp_anim', '-pix_fmt', 'yuva420p', '-q:v', String(opt.quality),
    '-loop', '0', '-an', '-f', 'webp', dst,
  ], { stdio: 'inherit' });
  map[name.replace(/\.webm$/i, '')] = { file: slug, kb: 0 };
  if (i % 20 === 0) console.log(`  [${i}/${files.length}]`);
}
writeFileSync(mapPath, JSON.stringify(map, null, 2) + '\n', 'utf8');

console.log('生成 anim-map.json 完毕，接着生成 pet-config.json');
execFileSync(process.execPath, [join(here, 'gen-config.mjs'), configJsonc, mapPath, petConfigPath], {
  stdio: 'inherit',
});
