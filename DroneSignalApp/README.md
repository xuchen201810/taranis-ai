# 无人机信号接收（DroneSignal）

一款运行在**普通安卓手机**上的「一键运行」APP，用于接收和解析无人机信号。
无需 root、无需额外硬件，安装后点一下按钮即可开始接收。

- 包名：`com.dronesignal.app`
- 最低系统：Android 7.0（API 24），目标 Android 14（API 34）
- 构建产物：[`drone-signal-debug.apk`](drone-signal-debug.apk)（debug 签名，可直接侧载安装）

## 一键运行

1. 把 `drone-signal-debug.apk` 传到手机，允许「安装未知来源应用」后安装。
2. 打开 APP，点击中间的大圆按钮 **一键启动**。
3. 首次启动会请求位置 / 附近设备 / 通知权限，全部允许即可（WiFi、蓝牙扫描必需）。
4. 按钮变红即处于接收状态；再点一次停止。接收时会常驻一条通知，保证息屏/后台不中断。

## 能接收哪些无人机信号

普通安卓手机没有专用射频硬件，但可以通过以下三条链路接收无人机信号：

1. **MAVLink 遥测（UDP 14550）** — 最核心。手机连接到无人机 / 遥控器的 WiFi 热点后，APP 在 UDP 14550 端口（MAVLink 地面站标准端口）监听并解析遥测报文，同时每秒广播一次 GCS 心跳以触发无人机推流。解析内容包括：
   - 机型 / 飞控（PX4、ArduPilot 等）、系统状态、解锁与飞行模式
   - 电池电量 / 电压 / 电流、GPS 坐标 / 定位类型 / 卫星数
   - 海拔、相对高度、地速 / 空速、航向、爬升率、滚转 / 俯仰 / 偏航姿态
   - 支持 MAVLink v1 与 v2，含 X.25 校验和验证

2. **WiFi 无人机网络** — 周期性扫描附近 WiFi，按 SSID 关键字（DJI / Tello / Parrot / PX4 / Holybro …）和厂商 OUI 前缀识别疑似无人机或遥控器热点，高亮显示，并标注当前连接的网络。

3. **蓝牙 BLE 设备** — 低延迟扫描附近 BLE 设备，按设备名和 MAC OUI 识别疑似无人机 / 遥控器。

界面实时展示以上三部分，并在底部保留滚动事件日志。

> 提示：若连接到无人机 WiFi 后仍无遥测，请确认该无人机使用 MAVLink 协议并向 14550 端口推流（绝大多数 PX4 / ArduPilot 飞控默认如此）。DJI 等闭源协议的消费级无人机，可通过 WiFi / BLE 扫描发现其信号，但遥测解析需对应厂商 SDK。

## 项目结构

```
DroneSignalApp/
├── app/src/main/
│   ├── AndroidManifest.xml          # 权限与前台服务声明
│   ├── java/com/dronesignal/app/
│   │   ├── DroneSignalApp.kt        # Application，持有全局仓库
│   │   ├── MainActivity.kt          # 主界面：一键按钮 + 实时渲染
│   │   ├── model/DroneModels.kt     # 数据模型
│   │   └── signal/
│   │       ├── MavlinkParser.kt     # MAVLink v1/v2 解析 + CRC 校验
│   │       ├── MavlinkReceiver.kt   # UDP 14550 接收 + 心跳广播
│   │       ├── WifiScanner.kt       # WiFi 扫描与无人机识别
│   │       ├── BleScanner.kt        # BLE 扫描
│   │       ├── DroneDetect.kt       # 无人机特征库（关键字 + OUI）
│   │       ├── SignalRepository.kt  # 统一调度，StateFlow 暴露状态
│   │       └── SignalService.kt     # 前台保活服务
│   └── res/                         # 布局 / 图标 / 主题
├── build.gradle.kts / settings.gradle.kts
└── gradle.properties
```

## 重新构建

已安装 Android SDK（platforms;android-34、build-tools;34.0.0、platform-tools）和 JDK 17 后：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
cd DroneSignalApp
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本沙箱环境通过 `127.0.0.1:18080` 代理下载依赖，`gradle.properties` 中已配置对应 `systemProp.*.proxy*`；在普通网络环境下可删除这些代理配置。
