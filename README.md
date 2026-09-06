# AI Auto

一个安卓自动化 APP：前端 WebView 控制台 + 本地 HTTP 后端，通过系统无障碍服务执行点击、滑动、返回，通过 MediaProjection 实现截图。可给 AI 当"手"用。

## 能力
- `tap` 点击坐标
- `swipe` 滑动
- `back` / `home` 返回、主页
- `capture` 截图
- `GET /status` 查看状态

## 本地 API（手机内 127.0.0.1:8080）
- `POST /action` body: `{"type":"tap","x":100,"y":200}` 或 `{"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` 或 `{"type":"back"}`、`{"type":"home"}`
- `POST /capture` 触发截图
- `GET /screenshot` 获取最新截图 PNG
- `GET /status` 返回 JSON 状态

## 使用
1. Android Studio 打开，构建安装。
2. 点「开启无障碍」在系统设置里打开本服务的开关。
3. 点「开启截图」授权屏幕录制。
4. 在 APP 内控制台操作，或让 AI 直接调用上面的本地接口。

## AI 接入示例
```python
import requests
ip = "192.168.x.x"   # 手机局域网 IP（需在同一 Wi-Fi）
requests.post(f"http://{ip}:8080/action", json={"type":"tap","x":100,"y":200})
```

## 权限说明
- 无障碍服务：执行手势与全局返回。
- 媒体投影：截图。
- 服务器监听 `ServerSocket(8080)`，默认绑定所有网卡，局域网可直接访问手机 `IP:8080`；需与手机处于同一 Wi-Fi。
