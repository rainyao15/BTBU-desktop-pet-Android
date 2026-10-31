# 北工商桌宠 · 怎么把它变成 APK

这个工程是 `dsh-pet-android` 的改造版：桌宠本体不变，加了**北工商数据层**（课表 / 校园网网费 / 校园卡余额）和一张**桌面小卡**。

两条路，任选：

---

## 路线 A · 云端构建（推荐，本地什么都不用装）

你机器上现在没有 JDK、没有 Android SDK、没有 ffmpeg —— 但**不需要**。

1. 在 GitHub 建一个空仓库（私有就行），把 `dsh-pet-android-main` 整个目录推上去：

   ```bash
   cd D:/btbu/dsh-pet-android-main
   git init
   git add -A
   git commit -m "北工商桌宠：数据层 + 桌面小卡"
   git branch -M main
   git remote add origin https://github.com/<你的账号>/btbu-pet.git
   git push -u origin main
   ```

2. 仓库页 → **Actions** → 左侧 `build-apk` → 右侧 `Run workflow`。
   我放好了 `.github/workflows/build.yml`，它会自动：
   - 装 JDK 17 + Android SDK 34 + ffmpeg + node
   - `npm pack dsh-pet` 拉上游动画素材，转成透明动画 WebP（480p，约 156 MB，**要等 10~20 分钟**）
   - `./gradlew :app:assembleDebug`
   - 把 `btbu-pet-debug.apk` 传到 Artifact

3. 从 Actions 运行结果页底部下载 `btbu-pet-debug-apk.zip` → 解压 → 传到手机安装（允许"未知来源"）。

> 素材是上游 `PC2005-cloud/dsh-pet` 的动画，声明「允许开源使用、禁止商用」，所以**只自己在手机上用**。
> 想公开发布，得换成自己画的帧。

---

## 路线 B · 本地构建（想改代码即时看效果时用）

需要装齐这些（一次性，约 3 GB）：

| 依赖 | 版本 | 怎么装 |
|---|---|---|
| JDK | 17 | `winget install EclipseAdoptium.Temurin.17.JDK` |
| Android SDK | Platform 34 + Build-Tools 34.0.0 | Android Studio 的 SDK Manager，或 cmdline-tools + `sdkmanager` |
| Node | 22 | `winget install OpenJS.NodeJS` |
| ffmpeg | 任意新版本（要带 `libvpx-vp9` 解码 + `libwebp_anim` 编码） | `winget install Gyan.FFmpeg` |

然后：

```bash
cd D:/btbu/dsh-pet-android-main
echo "sdk.dir=C:/Users/<你>/AppData/Local/Android/Sdk" > local.properties

# 还原动画素材（会 npm pack dsh-pet 再转码，第一次约 10 分钟）
bash tools/fetch-and-transcode.sh          # Git Bash / MSYS
# 或 PowerShell： pwsh -File tools/fetch-and-transcode.ps1

# 构建
./gradlew :app:assembleDebug
# 产物： app/build/outputs/apk/debug/app-debug.apk
```

只改 Java/Kotlin 逻辑、不改素材时，第二次起不用再跑素材脚本。

---

## 装上之后怎么用

1. 打开 App → 先给三个权限：**悬浮窗**、**通知**、**忽略电池优化**
2. 在「北工商 · 账号与同步」卡片里填 **学号 + 密码**，点「登录并同步」
   - 密码只写进本 App 私有 SharedPreferences，别的应用读不到
   - 登录失败会直接把服务端的原话显示出来（"用户名或密码错误" 之类）
3. 点「放出来」→ 宠物出现在桌面，右上角同时出现**数据小卡**：

   ```
   20:46:49
   第8周 · 网 15.00  卡 25.80
   周一 15:30 模拟电子技术 @工2-203-2
   ```

4. 小卡行为：
   - 时间每秒跳
   - 数据 20 秒重算一次（读本地缓存，不耗电）
   - 网络 15 分钟拉一次；**戳一下小卡 = 立刻刷新**
   - 网费状态是「服务受限」时，小卡下半行会变琥珀色并多一句「网要没了」
   - 超过 30 分钟没同步成功，会显示 `(1h 前)`

---

## 想要"先看 UI 不装素材"的偷懒法

动画素材不在仓库里（`.gitignore` 掉了），所以**没跑素材脚本时宠物是隐形的**，
但 `PetService` 里数据小卡是在宠物之后独立创建的 —— 所以**小卡照样会出来**，
可以用来先验证账号、课表、余额这条链路通不通。

---

## 改了什么（相对于上游 dsh-pet-android）

```
新增  app/src/main/java/com/dshpet/android/btbu/
      ├─ Tron.kt        请求信封 + MD5 签名 + HttpURLConnection（零第三方依赖）
      ├─ BtbuParse.kt   数据类 + JSON 解析
      ├─ BtbuApi.kt     login / schedule / net / card
      ├─ BtbuStore.kt   SharedPreferences 缓存 + 派生"当前课/下一节/周次"
      ├─ BtbuSync.kt    单线程同步调度 + 错误留痕
      └─ BtbuHud.kt     桌面小卡悬浮窗（1s 时钟 / 20s 派生 / 15min 网络）
新增  app/src/main/res/layout/card_btbu.xml   设置页里的"账号与同步"卡片
改动  activity_main.xml   插入上面那张卡
改动  Prefs.kt            账号 / 设备号 / 小卡开关
改动  PetService.kt       spawnAll 后挂小卡、teardown 时释放
改动  MainActivity.kt     账号绑定 / 登录 / 同步 / 小卡开关
改动  AndroidManifest.xml  加 INTERNET + ACCESS_NETWORK_STATE
改动  Sfx.kt              R.raw.xxx → getIdentifier（干净 clone 也能编译）
新增  res/drawable/whale_cutout.png、res/drawable-nodpi/whale_selfie.png
                          1×1 透明占位（原图有版权，不随仓库分发）
新增  .github/workflows/build.yml  云端出 APK
```

### 为什么动 Sfx.kt

上游仓库 `.gitignore` 掉了 `res/raw/tap_press.mp3` 和两张立绘，但 `Sfx.kt` 里写死了 `R.raw.tap_press`
—— **干净 clone 直接编译不过**（AAPT 找不到资源）。改成 `getIdentifier` 之后：没有就静默、有就播，行为不变，工程随时可编译。
立绘同理，用 1×1 透明占位顶上（不用原作者的图）。

---

## 本地类型检查（不用 Android Studio，一条命令）

上一轮踩过的坑（Kotlin 块注释嵌套、字符串字面量裸换行）都是肉眼看不出来的，所以有了这个：

```bash
# 一次性准备（约 340 MB，都是官方包）
#   JDK 17        https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse
#   kotlinc 2.0.20 https://github.com/JetBrains/kotlin/releases/download/v2.0.20/kotlin-compiler-2.0.20.zip
#   android.jar    https://dl.google.com/android/repository/platform-34-ext7_r03.zip  （zip 里取 android-34/android.jar）

export JAVA_HOME='D:\jdk-17'
export KOTLINC=/d/kotlin/kotlinc/bin/kotlinc.bat
export ANDROID_JAR='D:ndroid-sdk\platformsndroid-34ndroid.jar'

./tools/typecheck.sh
# ==> 类型检查通过（0 error）
```

原理：`tools/gen_r_stub.py` 扫 `res/` 生成 `R.kt` 桩（R 类本来是 aapt 生成的，纯 kotlinc 没有），
然后 kotlinc 拿 `android.jar` 当 classpath 把 20 个源文件整体编译。
改完代码先跑它，再 push，能省掉一轮 20 分钟的 CI 往返。

## 自检

没有 Android SDK 也能先跑一遍资源体检（防止 R.id 写错、漏建 layout）：

```bash
python tools/check_glue.py
```

它会把 Kotlin 里所有 `R.xxx.yyy` 跟 `res/` 里真实存在的资源对一遍。
当前结果：**18 个 Kotlin 文件、125 个资源，全部对得上**。
