# BLE防丢器 Android App

## 上传到GitHub后自动编译APK，步骤如下：

### 第一步：创建GitHub仓库
1. 打开 github.com，登录账号
2. 点右上角 "+" → "New repository"
3. 仓库名填：`ble-anti-lost`
4. 选 **Public**（免费用Actions）
5. 点 "Create repository"

### 第二步：上传所有文件
1. 在新建的仓库页面，点 "uploading an existing file"
2. 把本压缩包解压后，将**所有文件夹和文件**拖入上传框
3. 点 "Commit changes"

### 第三步：等待自动编译
1. 点仓库顶部 **Actions** 标签
2. 看到 "Build APK" 正在运行（黄色圆圈）
3. 等待约 3-5 分钟变成绿色 ✅

### 第四步：下载APK
1. 点击绿色的 "Build APK" 任务
2. 页面最底部 **Artifacts** 区域
3. 点击 "BLE防丢器-APK" 下载zip
4. 解压得到 `app-debug.apk`
5. 传到手机安装（需要允许"安装未知来源应用"）

## APP功能
- 自动扫描BLE广播，按COMPANY_ID(0xABCD)过滤
- 实时显示TAG编号、电量、信号强度、在线/离线状态
- 10秒无信号自动标记为离线
- 支持多个TAG同时监控
