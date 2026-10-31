"""校验转码后的动画 WebP：帧数、尺寸、透明通道是否真实存在。

用法：
    python tools/verify_webp.py [素材目录]

默认校验 app/src/main/assets/webp。Android 的 AnimatedImageDrawable 用的也是 libwebp，
所以这里通过 Pillow(libwebp) 能读出来，基本可以确认手机端也能解。
"""
import os
import sys

from PIL import Image


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    default = os.path.join(here, "..", "app", "src", "main", "assets", "webp")
    directory = sys.argv[1] if len(sys.argv) > 1 else default
    directory = os.path.abspath(directory)

    files = sorted(f for f in os.listdir(directory) if f.lower().endswith(".webp"))
    if not files:
        print(f"目录里没有 .webp: {directory}")
        return 1

    total_frames = 0
    alpha_ok = 0
    problems = []
    sizes = {}

    for name in files:
        path = os.path.join(directory, name)
        try:
            im = Image.open(path)
            frames = getattr(im, "n_frames", 1)
            total_frames += frames
            im.seek(0)
            key = "{}x{}".format(*im.size)
            sizes[key] = sizes.get(key, 0) + 1

            lowest = 255
            for idx in sorted({0, max(0, frames // 2), max(0, frames - 1)}):
                im.seek(idx)
                lowest = min(lowest, im.convert("RGBA").getchannel("A").getextrema()[0])
            if lowest < 200:
                alpha_ok += 1
            else:
                problems.append((name, f"没有透明像素（最小 alpha={lowest}）"))
        except Exception as exc:  # noqa: BLE001
            problems.append((name, f"{type(exc).__name__}: {exc}"))

    print(f"目录              : {directory}")
    print(f"文件数            : {len(files)}")
    print(f"总帧数            : {total_frames}（平均 {total_frames // max(len(files), 1)} 帧/段）")
    print(f"含真实透明像素    : {alpha_ok} / {len(files)}")
    print(f"尺寸分布          : {dict(sorted(sizes.items()))}")
    print(f"问题数            : {len(problems)}")
    for name, why in problems[:12]:
        print(f"  ! {name}: {why}")
    return 0 if not problems else 1


if __name__ == "__main__":
    raise SystemExit(main())
