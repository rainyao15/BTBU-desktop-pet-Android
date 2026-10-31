# 大肥鱼桌宠 · Android

把电脑端插件 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 移植成**安卓悬浮桌宠**：
宠物住在桌面上（不是住在某个 App 里），完全离线。

- ✅ 全量 **106 段**透明动画（待机 / 转向 / 拖拽 / 5 种点击回应 / 3 种移动 / 5 大类随机动作）
- ✅ 点击 Q 弹回应、拖拽过阻尼弹簧跟手、甩抛抛物线 + 撞边反弹 + 地面摩擦
- ✅ 屏幕横向漫游，走不出去会自动掉头
- ✅ **长按动作菜单**：像电脑版的右键菜单一样，点播任意一段动画
- ✅ **点击音效**（拍一拍），设置页可手动开关
- ✅ **台词气泡碎碎念**（内置 320 句，按情境弹：开机 / 被连点 / 被甩 / 贴边 / 早晚深夜 / 久置，20 秒冷却不刷屏）
- ✅ **台词库可以自己改**：App 内编辑器（11 个分类、一行一句）+ 导出 / 导入文件，改坏了可分类恢复默认或全部回内置
- ✅ **贴边躲藏**：拖到屏幕边缘自动贴边，静置 12 秒缩进去只露半个身子，碰一下或拖一下就探出来
- ✅ **多只宠物**：1–3 只「大肥鱼」一起住桌面（设置页一键切换）
- ✅ **后台保活**（默认开）：从「最近任务」隐藏，手滑清后台不会划掉，被系统杀掉会自动回来
- ✅ 大小可调（80–360dp）、开机自启
- ❌ 没有 AI 对话 / 余额 / 系统级通知（这些需要 DSH 的模型与账号服务）
- ✅ **零网络权限**：没有 AI 就没有联网，不消耗 token

## 下载安装

到 [Releases](../../releases) 下载对应的 APK，传到手机安装（需允许「安装未知来源应用」）。
下面三个版本**功能完全一样**，只差动画素材的分辨率与帧率——按手机新旧挑一个：

| 版本 | 素材分辨率 | 帧率 | 体积 | 适合 |
|---|---|---|---|---|
| `-300p` | 300×168 | 15fps | ~80 MB | 旧机 / 低端机 / 存储紧张（最省内存、解码最轻） |
| `-480p-24fps` | 480×270 | 24fps | ~277 MB | **推荐**，150–200dp 下正好 1:1 清晰，动作最顺 |
| `-640p-24fps` | 640×360 | 24fps | ~425 MB | 大屏或想贴近看细节，吃内存与解码性能 |

安装后：

1. 打开 App → **授予「显示在其他应用上层」**（悬浮窗权限，必需）
2. 授予通知权限（前台服务常驻用）
3. 点**「放出来」**

### 厂商后台保活（否则锁屏久了会被系统清掉）

- **荣耀 / MagicOS**：设置 → 应用 → 桌宠 → 电池 → 允许后台活动；并加入「应用启动管理」允许列表
- **小米 / 红米**：省电策略选「无限制」+ 打开「自启动」

## 交互

| 操作 | 效果 |
|---|---|
| 单击 | 随机点击回应动画 + Q 弹挤压 |
| **长按** | 弹出动作菜单（分类可折叠，点播任意动画 / 回到初始位置 / 翻转朝向 / 收起来） |
| 按住拖动 | 弹簧跟手 |
| 快速甩出 | 抛物线飞出，撞边反弹，落地摩擦减速并 Q 弹 |
| 通知栏 | 「收起来」= 停止 |

## 手感为什么和电脑版一致

物理是逐行移植，不是重写：

| 电脑版（dsh-pet） | 本项目 |
|---|---|
| `src/shared/physics.ts` | `app/.../Physics.kt`（常量与公式一字未改） |
| `src/shared/constants.ts` | `Pet.kt` 里的命中框 / 16:9 比例 / 基准宽 462 |
| `src/shared/motion.ts` | `Pet.kt` 的 `anchorPixel()` 与漫游边界 |
| `assets/config.jsonc` | `tools/gen-config.mjs` 生成的 `pet-config.json` |

弹簧 `K=200 / C=30`、重力 `1400`、恢复系数 `0.78`、地面摩擦 `2.5`、死区 `500px/s`、
软钳速上限 `3600px/s`、Q 弹 `220ms` / 压深 `0.55` —— 全部与电脑版同值。

## 素材为什么是 WebP

电脑版用 VP9-alpha 的 `.webm`，安卓原生播不了透明视频（`MediaCodec` 不暴露 alpha 平面）。
所以转成**动画 WebP**（Alpha 保留），由 Android 9+ 的 `AnimatedImageDrawable` 原生播放：

```bash
ffmpeg -c:v libvpx-vp9 -i in.webm \
  -vf "fps=15,scale=480:-2:flags=lanczos" \
  -c:v libwebp_anim -pix_fmt yuva420p -q:v 50 -loop 0 \
  out.webp
```

关键点：**必须用 `-c:v libvpx-vp9` 解码**，默认解码器会把 alpha 丢成 `rgb24`。

分辨率选 **480px** 是因为 150dp × density 3.275 ≈ 475px，正好 1:1；
用 300px 时会被放大 1.58 倍，看起来发虚。动画 WebP 体积随分辨率涨得很快
（640px 全量约 530 MB，480px 约 156 MB）。

## 从源码构建

**仓库不包含动画素材**（它们来自上游且有「禁止商用」约束，见 [NOTICE.md](NOTICE.md)），
首次构建前先还原一次：

```bash
# 1) 还原素材 + 生成配置（需要 node/npm 与 ffmpeg）
./tools/fetch-and-transcode.sh          # Linux / macOS
pwsh -File tools/fetch-and-transcode.ps1   # Windows PowerShell

# 2) 构建 debug APK（需要 JDK 17 + Android SDK 34）
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

转码参数可调：`WIDTH=640 QUALITY=60 FPS=24 ./tools/fetch-and-transcode.sh`
（体积会明显变大，见上节）。

校验素材是否正确（帧数 / 尺寸 / 透明）：

```bash
python tools/verify_webp.py
```

CI：把 `ci/build.yml.example` 复制成 `.github/workflows/build.yml` 即可启用
（push 与 PR 自动还原素材并构建 APK）。GitHub 要求推送 workflow 文件时 token 带
`workflow` 权限，所以它没有随首次提交一起推上去，需要手动加一次。

## 代码结构

```
app/src/main/java/com/dshpet/android/
├─ MainActivity.kt   设置/授权界面
├─ PetService.kt     前台服务：悬浮窗管理 + 帧循环 + 长按动作菜单
├─ Pet.kt            单只宠物：动画链 + 触摸 + 物理 + 漫游 + 长按
├─ Physics.kt        dsh-pet physics.ts 的逐行移植
├─ PetConfig.kt      pet-config.json 解析
├─ Prefs.kt          尺寸 / 开关
├─ Diag.kt           自诊断（异常落盘 + 界面显示，避免无声闪退）
├─ App.kt            未捕获异常兜底
└─ BootReceiver.kt   开机恢复
tools/
├─ build-assets.mjs          素材转码（跨平台统一排序）
├─ gen-config.mjs            插件配置 → 手机配置（含中文动画名）
├─ fetch-and-transcode.*     一键还原素材
└─ verify_webp.py            素材校验
```

## 已知限制 / 可以贡献的方向

- **悬浮窗是矩形**：宠物盒子里那圈透明区域也会挡住点击（电脑版是逐像素命中）。
  可以按 alpha 做命中判定，或把窗口裁到身体范围。
- **多开没有 UI**：需要在 `pet-config.json` 的 `pets` 数组里加条目。
- **没有右键菜单**（用长按替代），动作菜单的分类目前是折叠列表，可以做成二级页面。
- **画质与体积**：想做「原生 640×360 + 24fps 且体积小」，需要改用
  「彩色流 + 透明遮罩流」双视频 + GL 合成（预计全量 ~80 MB，比现在的 WebP 更小更清晰）。

## 更新日志

### v1.8.1（2026-10-22）

- **修复「动作名字和动画对不上」**：24fps 重转把素材编号顺序从拼音序换成了编码序，配置却沿用了旧顺序，
  106 段动画的名字全部错位（长按菜单里点「三球抛接」放的是别的段，待机 / 点击回应也指错了）。
  现在按「名字 → 正确编号」整表重映射；另加 `tools/check-config.mjs`（一致性体检）与
  `tools/fix-config-slugs.mjs`（一键纠偏），以后重转素材跑一遍就能发现
- 300p 未受影响（它的素材与配置一直匹配），但为版本号统一也重出了一份

### v1.8（2026-10-22）

- **台词库可以自己改了**：设置页「✏️ 编辑台词」→ App 内编辑器按 11 个分类改（一行一句），
  「保存并生效」立刻生效；也能「导出到文件…」用电脑改好再「从文件导入…」（系统文件选择器）；
  另有「这一类恢复默认」和「全部恢复成内置台词」兜底。外部文件放在
  `Android/data/com.dshpet.android/files/lines.json`，不存在或解析失败就回落到内置 320 句
- **后台保活开关**（默认开）：用 `AppTask.setExcludeFromRecents(true)` 把桌宠从「最近任务」里隐藏，
  手滑清后台不会划掉它；服务改 `START_STICKY`，被系统杀掉后自动回来（实测 `kill -9` 后 9 秒重新拉起）；
  首次开启时申请「忽略电池优化」
- **新增 480p / 640p 的 24fps 档**：按母版真实的 24 帧重转（`tools/build-assets.mjs --fps 24`）；
  `durations` 改为按每段动画的**实际帧时长**计算（顺带修好了旧素材里有一段被截成 4.9 秒的问题，
  那一段以前播到一半就跳下一段）
- **修复：气泡位置**（不再掉在屏幕顶上，跟随宠物正确偏移）与**多只宠物重叠**（错位后整只夹进屏幕内）

### v1.5.1（2026-10-21）

- **点击音效**：点一下有「拍一拍」的按键声（按下 + 抬起两层，素材来自 dsh-whale-widget），
  设置页新增**手动开关**（默认开，关掉后完全静音）
- **界面重做**：DeepSeek 二创「大肥鱼」风格 —— 卡片半透明、蓝紫渐变、鲸鱼娘立绘垫在右下角当背景、圆角胶囊状态标
- **修复：点一下动作会连做两遍**。根因是 `AnimatedImageDrawable.repeatCount = 1` 在多数机型上表示
  「再重复一次」= 播两遍；改成按每段动画的真实时长（`pet-config.json` 的 `durations`，
  由帧数/15fps 算出）定时收尾，一段一播
- **修复：漫游会走出屏幕**。根因是移动动作拿左上角 `x` 当中心去比边界，
  且额外允许了 1/5 身位挂出屏幕；改成按中心计算、夹在 `[minLeft, maxLeft]` 内，
  贴边且朝外时才掉头待机
- **改名**：宠物名改为「大肥鱼」（`tools/gen-config.mjs --name`）
- 新增 **300p / 480p / 640p** 三种素材分辨率的构建产物

### v1.4 / v1.5

- v1.4：加入长按动作菜单（分类折叠 + 点播任意动画 + 回到初始位置 / 翻转 / 收起）
- v1.5：素材从 300px 升到 480px，UI 第一版重做

## 许可与署名

- **代码**：MIT（见 [LICENSE](LICENSE)）
- **动画素材**：来自 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)，
  其声明为「允许开源使用，**禁止商用**」——因此本仓库不含素材原件，Release 附件同样受此约束。
  详见 [NOTICE.md](NOTICE.md)。
- **点击音效与鲸鱼娘立绘**：取自社区插件 `dsh-whale-widget`，版权归原作者；
  本仓库**不包含**这些二进制（`res/raw/*.mp3`、`res/drawable/whale_*.png` 已在 `.gitignore` 中）。
  想自己构建带音效的版本，把它们放到：

  ```
  app/src/main/res/raw/tap_press.mp3      # 按下音
  app/src/main/res/raw/tap_release.mp3    # 抬起音
  app/src/main/res/drawable/whale_cutout.png      # 界面顶部头像
  app/src/main/res/drawable-nodpi/whale_selfie.png # 背景立绘（可选）
  ```

  不带这些文件也能构建（音效静音、头像位置留空）。
- 本项目是社区移植，与 DeepSeek 官方无关。
