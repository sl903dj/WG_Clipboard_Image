# WG Clipboard：文字 + 图片双向同步（实验版，One UI 8.5 / Android 16 适配）

保留现有 WireGuard、HTTP Bearer Token 和 `/clipboard` 文本接口；增加 `/clipboard/image` 图片接口，以及 Android 原生剪切板辅助 APK 源码。

## 重要说明

- Android 图片剪切板读取受系统前台限制。脚本通过 `am start` 拉起辅助 Activity，再由 Activity 调用 `ClipboardManager`。
- APK 使用共享目录 `/storage/emulated/0/Pictures/wg-clipboard/` 与 Termux 交换图片。首次运行需要授予“所有文件访问”特殊权限。读取剪切板图片时优先使用系统剪切板授予的临时 `content://` URI 权限，不额外申请整个媒体库读取权限。
- 目前支持 PNG、JPEG、WebP、GIF，图片最大 20 MiB。
- 这是首版源码，尚未在真实 Android 设备上完成兼容性验证。不同厂商对图片剪切板 URI 授权的实现可能有差异。

## 1. Linux 服务端

依赖：Python 3、X11、`xclip`。安装 `xclip` 后，将 `server/server.py` 放到电脑上，例如 `~/.local/bin/wg-clipboard-server.py`。

确认 `HOST` 是电脑 WireGuard 地址，`TOKEN_FILE` 指向原来的 token 文件：

```bash
python ~/.local/bin/wg-clipboard-server.py
```

确保 Linux 的 `~/.config/wg-clipboard/token` 与手机 config 中 TOKEN 一致。建议只在 WireGuard 接口监听，不要暴露到公网。

## 2. Android APK 构建

需要 JDK 17、Android SDK 35、Gradle 8.9 或兼容版本：

```bash
cd android
gradle assembleDebug
```

APK 输出：`android/app/build/outputs/apk/debug/app-debug.apk`。

也可以把本项目推送到 GitHub，在 Actions 中运行 `Build Android APK`，下载 `wg-clipboard-helper-debug-apk` artifact。

安装：

```bash
adb install -r app-debug.apk
```

包名：`com.example.wgclipboard`。APK 没有桌面启动入口，由 Termux 脚本通过显式 Activity 启动。

## 3. Termux 配置

先在 Termux 执行：

```bash
pkg install curl file
termux-setup-storage
mkdir -p ~/.config/wg-clipboard
```

保留原来的 `~/.config/wg-clipboard/config`，至少包含：

```bash
SERVER="http://10.66.0.2:8765"
TOKEN="你的token"
```

将 `termux/push-image.sh` 和 `termux/pull-image.sh` 复制到 `~/.local/bin/` 并赋予执行权限。运行：

```bash
push-image.sh   # Android 图片剪切板 -> Linux
pull-image.sh   # Linux 图片剪切板 -> Android
```

首次运行会跳转到“所有文件访问”设置，请允许 `WG Clipboard Helper`。手机需处于解锁状态，辅助 Activity 必须能正常显示在前台。Android 16 对后台启动 Activity 有限制，因此请在用户主动执行 Termux 命令时调用脚本，不要依赖后台静默读取剪切板。

## 4. 接口

- `GET /clipboard`、`POST /clipboard`：原有文本接口。
- `GET /clipboard/image`：返回 PNG/JPEG/WebP/GIF 图片二进制，Content-Type 标明格式。
- `POST /clipboard/image`：请求 Content-Type 必须是支持的图片 MIME。

## 5. 已知限制

1. Android 不能保证后台应用随时读取剪切板；需要通过前台 Activity 触发。
2. 某些 App 复制图片时放入的是自定义 Intent 或只提供临时 URI，当前实现只处理 ClipData 中的 URI。
3. Android 版本、厂商 ROM、Termux 存储授权均可能影响行为；需要在目标手机实测。
4. `set_image` 依赖系统/目标 App 能读取 FileProvider URI。若粘贴端不接受图片 URI，需要针对目标 App 调整 URI 授权策略。
5. 本工程暂以 `compileSdk/targetSdk 35` 构建，能够安装运行在 Android 16；暂不主动切换 `targetSdk 36`，避免引入 Android 16 针对目标版本 36 的强制 edge-to-edge 等额外行为变化。
5. 现有文字脚本不在本包内替换，继续使用你原来的 push/pull 即可。

### Android 存储权限补充

为让 Termux 与辅助 APK 读写同一个共享目录（而不是各自私有沙盒），首版 APK 使用 Android 的“所有文件访问”权限。首次启动会跳转到系统设置页，需要手动允许 `WG Clipboard Helper` 的“允许管理所有文件”。这是侧载工具的权限取舍；如果不希望授予该权限，需要改成 MediaStore/SAF 共享方案，Termux 脚本也要相应调整。
