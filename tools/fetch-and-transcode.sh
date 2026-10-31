#!/usr/bin/env bash
# 从上游 dsh-pet 还原素材并转码（Linux / macOS / CI 用）
#
#   ./tools/fetch-and-transcode.sh
#   WIDTH=640 QUALITY=60 FPS=24 ./tools/fetch-and-transcode.sh
#
# 需要：node/npm、ffmpeg（带 libvpx-vp9 解码 + libwebp_anim 编码）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> npm pack dsh-pet"
npm pack dsh-pet --pack-destination "$WORK" >/dev/null
TGZ="$(ls "$WORK"/dsh-pet-*.tgz | head -n1)"
tar -xzf "$TGZ" -C "$WORK"

echo "==> 转码 + 生成配置"
node tools/build-assets.mjs \
  --pkg "$WORK/package" \
  --out "app/src/main/assets/webp" \
  --width "${WIDTH:-480}" \
  --quality "${QUALITY:-50}" \
  --fps "${FPS:-15}" \
  --ffmpeg "${FFMPEG:-ffmpeg}"

echo "==> 完成"
