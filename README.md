# AI Auto

一个安卓自动化 APP：**给 AI 当"手和眼"**。无障碍服务负责点击/滑动/输入/读屏，MediaProjection 负责截图，
APP 里跑一个本地 HTTP 服务（默认 8080），既能当 REST 接口用，也能当 **MCP 服务器**直接挂给 AI 客户端。

装一次、开无障碍就够了（截图默认走无障碍自带那条路，**不用**授权录屏，Android 11 以下或想更稳再开那个开关）。之后 AI 就能开应用、按文字点按钮、滑列表、打字、看截图。

## 能力（MCP 工具）

| 工具 | 说明 |
| --- | --- |
| `screen_info` | 屏幕尺寸 + 当前前台应用包名（算坐标前先看一眼） |
| `ui_scan` | 读当前界面：文字/图标描述 + 中心坐标 + 是否可点（外层列表项可点也算） |
| `click` | 点目标：给坐标 `(100,200)` 或给界面文字（图标描述也认，如「搜索」） |
| `tap_xy` | 纯坐标点击（AI 自己看 `ui_scan` 的坐标来点） |
| `long_press_target` | 长按某个文字/坐标 |
| `swipe_screen` | 滑动：`up/down/left/right` 或起止坐标 |
| `type_text` | 往输入框打字；`enter=true` 顺手点「发送」/回车 |
| `press` | 系统键：`back/home/recents/notifications/quick_settings/lock/power` |
| `launch_app` | 开应用（名字或包名都行，会问系统认） |
| `list_apps` | 列出已装应用（名字+包名） |
| `screenshot_vision` | 截图（压缩 JPEG，base64） |
| `open_url` | 打开网址 |
| `wechat_type` / `wechat_search_contact` / `wechat_moments` | 微信：发消息 / 找联系人 / 进朋友圈 |
| `play_song` | 搜网易云的歌（要用 `orpheus://` 打开） |

> 所有定位都走"读屏 + 文字/描述"，**不写死坐标** —— 手机和平板分辨率不一样也能用。

## 本地接口

- `GET /status` → `{"accessibility":"on","capture":"off","screenshot":"accessibility","port":8080,"width":..,"height":..,"package":"..","token_required":false}`
  （`screenshot` 三条路：`accessibility` 无障碍自带 / `projection` 录屏授权 / `none`；端口被占会在 `error` 里说明）
- `GET /ui` → UI 树（文字/坐标/可点）
- `POST /action` → `{"type":"tap","x":100,"y":200}`、`{"type":"swipe",...}`、`{"type":"back"}`、
  `{"type":"home"}`、`{"type":"recents"}`、`{"type":"notifications"}`、`{"type":"clickText","text":"发送"}`、
  `{"type":"inputTextSend","text":"你好"}`、`{"type":"openApp","package":"com.tencent.mm"}`
- `POST /clickNode` → `{"text":"发送"}` 按文字点
- `POST /capture` + `GET /screenshot` → 截图（PNG/JPEG 字节）
- `POST /set_token` → `{"token":"..."}` 设置局域网口令，**只能从本机调用**（留空 = 关掉校验）
- `POST /mcp` → JSON-RPC 2.0（`initialize` / `tools/list` / `tools/call`）

无障碍没开 / 没开服务时，接口不会假装成功，会直接回一句原因，AI 看得懂。

局域网里：`http://<平板IP>:8080/...`；平板本机：`http://127.0.0.1:8080/...`

> 设了 token 之后：局域网请求要带 `X-Token: <token>` 头或 `?token=<token>`；本机回环不受影响。
> 只在自家 WiFi 用的话可以不设。

## 用法

1. 装机，打开 APP（第一次会问通知权限，给它 —— 就是靠通知提醒你「无障碍没开」）。
   按钮本身就是状态灯：无障碍已开 / 截图已授权 / 服务在跑。
2. 「开无障碍」→ 在系统设置里打开 **AI Auto** 的无障碍开关（不点开这个，一切都不工作）。
3. 「开截图（可选）」→ 允许录屏。Android 11+ 一般不用点，无障碍自带截图；
   点了也只是多一条后备路。
4. 「启动服务」→ 常驻前台服务，端口 8080。
5. AI 那边把 `http://<平板IP>:8080/mcp` 当 MCP 服务器挂上（或在电脑上用 `ai-auto-mcp` 那套转发）。
6. 想在局域网里加个口令：APP 页面最下面填 token 保存，之后局域网请求带 `X-Token`。

## 权限说明

- 无障碍：手势、全局按键、读屏、输入文字、截图。
- 媒体投影：录屏式截图（可选的后备路）。
- 前台服务：让本地服务常驻（有常驻通知）。
- 通知：用来提醒无障碍被系统关掉了。
- 服务监听 `0.0.0.0:8080`，同意局域网里的设备访问 —— 别在不可信的 Wi-Fi 下开着，或者设个 token。

## 自己编

- **GitHub Actions**：推 main 自动出包（`.github/workflows/build.yml`，用 setup-gradle 钉死 Gradle 8.9）。
  产物既在 Actions 的 artifact 里，也会自动推到 `apk` 分支，所以能直接下：
  `https://github.com/yivi-yi/ai-auto-app/raw/apk/apk-out/app-debug.apk`
- **本地（Linux/沙箱）**：`bash tools/build-apk.sh`（产物 `apk-out/app-debug.apk`，`--push` 会推到 `apk` 分支）。
  arm64 上要 `AAPT2_OVERRIDE=/opt/android-sdk/build-tools/35.0.0/aapt2`，见 `Little-Planet/docs/本地打包备忘.md`。
