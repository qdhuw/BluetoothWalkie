# 蓝牙对讲 (BluetoothWalkie)

两台 Android 手机通过**经典蓝牙（SPP）** 直接通信，实现无需网络的语音对讲，类似对讲机（Push-To-Talk）操作体验。

---

## 功能特性

| 功能 | 说明 |
|------|------|
| 🔵 蓝牙扫描 | 扫描附近蓝牙设备并显示配对状态 |
| 🤝 一键配对连接 | 点击设备名即可发起连接，自动协商服务端/客户端角色 |
| 🎙 PTT 按住说话 | 按住大圆按钮录制并实时发送语音，松开停止 |
| 🔊 实时接收播放 | 自动接收对方语音并通过扬声器播放 |
| 📳 触觉反馈 | 按下/松开PTT时震动提示，连接成功时震动 |
| 🔄 自动重连监听 | 断线后自动回到监听状态等待重连 |

---

## 技术架构

```
MainActivity
    └── BluetoothService (bound service)
            ├── AcceptThread     → 服务端：等待对方连接
            ├── ConnectThread    → 客户端：主动连接对方
            ├── ConnectedThread  → 已连接：读取对方发来的音频数据
            ├── AudioRecordThread→ 麦克风采集 PCM → 写入蓝牙输出流
            └── AudioPlayThread  → 读取接收队列 → AudioTrack 播放
```

**音频参数：**
- 采样率：16000 Hz（人声清晰，带宽友好）
- 声道：单声道
- 编码：PCM 16-bit
- 传输协议：RFCOMM over SPP UUID `00001101-0000-1000-8000-00805F9B34FB`

---

## 使用步骤

### 首次使用（两台手机需先配对）
1. 在系统蓝牙设置中将两台手机互相配对
2. 安装并打开 App，授予蓝牙和录音权限

### 建立对讲连接
1. **手机 A**：打开 App → 自动进入"等待连接中"状态（服务端监听）
2. **手机 B**：打开 App → 点击"扫描设备"或查看"已配对"列表
3. **手机 B**：点击手机 A 的设备名 → 确认连接
4. 两台手机均显示"已连接：设备名"

### 对讲
- **按住"按住说话"** → 说话 → **松开结束**
- 对方手机扬声器实时播放你的声音
- 双方均可按住按钮说话（半双工，不能同时说）

---

## 项目结构

```
BluetoothWalkie/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # 权限声明
│   │   ├── java/com/bluete/walkie/
│   │   │   ├── MainActivity.kt          # 主界面、设备扫描、PTT交互
│   │   │   ├── BluetoothService.kt      # 蓝牙连接 + 音频传输核心
│   │   │   └── DeviceAdapter.kt         # 设备列表适配器
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml    # 主界面布局
│   │       │   └── item_device.xml      # 设备列表项布局
│   │       ├── drawable/                # 图标、背景 Drawable
│   │       ├── color/                   # 颜色选择器
│   │       └── values/                  # 颜色、字符串、主题
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

---

## GitHub Actions 自动构建

每次推送到 `main` 分支，GitHub Actions 会自动构建 APK，产物在 **Actions** → **Build Android APK** → **Artifacts** 下载。

```bash
# 首次推送（先在 GitHub 创建仓库）
git init
git add .
git commit -m "feat: 蓝牙对讲 App"
git branch -M main
git remote add origin https://github.com/<你的用户名>/BluetoothWalkie.git
git push -u origin main
```

构建产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 编译要求（本地）

- **Android Studio** Hedgehog（2023.1.1）或更高
- **compileSdk** 34，**minSdk** 23（Android 6.0+）
- **Kotlin** 1.9.22
- **Gradle** 8.14

---

## 权限说明

| 权限 | 用途 |
|------|------|
| `BLUETOOTH_CONNECT` (API 31+) | 连接蓝牙设备 |
| `BLUETOOTH_SCAN` (API 31+) | 扫描附近设备 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` (API ≤30) | 旧版蓝牙权限 |
| `RECORD_AUDIO` | 麦克风录音 |
| `ACCESS_FINE_LOCATION` | 旧版蓝牙扫描需要位置权限 |

---

## 注意事项

- 蓝牙传输为**原始 PCM**，无压缩，适合近距离使用（蓝牙通信范围内）
- 两台设备必须提前在系统设置中**完成蓝牙配对**
- 经典蓝牙 SPP 带宽约 700 Kbps，16kHz 单声道 PCM 占用约 256 Kbps，余量充足
- 如需更低延迟可将 `SAMPLE_RATE` 改为 8000，音质略降但延迟更小
