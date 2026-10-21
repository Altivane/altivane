# Altivane — 骑行 / 徒步轨迹记录（Android）

自用运动记录器：GPS 轨迹 + 骑行/徒步双运动 + BLE 心率/踏频 + AI 教练语音播报 + 彩虹轨迹分享图 + 桌面卡片 + 拍照打卡 + 内置三山五岳线路。

> 命名：Alti（海拔）+ vane（罗盘风标）。

## 功能

- **轨迹记录**：前台服务后台记录、打卡点拍照（照片钉在轨迹图对应位置）、BLE 心率/踏频、气压计海拔
- **地图**：MapLibre + CARTO 矢量底图，四模式图层循环（标准 / 卫星 / 徒步等高线 / 骑行），离线地图包下载，拖动不掉帧（数据推送让路手势）
- **数据**：速度彩虹轨迹、每公里配速、爬升/下降曲线、心率区间、海拔剖面、卡路里估算（Keytel 心率公式 / MET 兜底）
- **运动详情**：距离横幅、较上次对比徽章、运动解读自动生成、心率曲线 + 区间分布
- **分享图**：MapSnapshotter 地图 + 彩虹轨迹 + 统计面板，深浅双主题
- **指南针**：旋转矢量标准解算（`getOrientation` 原生语义）+ 外圈水平仪弧 + °数标尺
- **桌面小部件** ×8：速度/心率/里程/卡路里磁贴 + 汇总/罗盘/轨迹/SOS
- **三山五岳**：内置经典登山线（矢量线路图 + 海拔剖面）
- **AI 教练**：离线规则引擎，开始/每公里/心率预警/补水提醒/热身拉伸清单，系统 TTS 中文播报

## 技术栈

Kotlin 2.2 · Jetpack Compose · Room · MapLibre Native 13 · minSdk 29 / targetSdk 36 · arm64-v8a

## 构建

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 手机设置（首次安装后）

1. 位置权限选 **「始终允许」**（锁屏记录不断点）
2. 通知权限允许（前台服务常显）
3. 电池优化对本 App 选「不限制」

## 隐私

- 所有数据（轨迹、打卡、照片、运动记录）仅存储于设备本地（Room + 应用私有目录）
- 无统计 SDK、无遥测、无云端同步、无账号体系
- 联网仅用于：地图瓦片（CARTO / Esri / OpenTopoMap / CyclOSM）、打卡点逆地名（Nominatim）

## 免责声明

> **使用本应用前请务必阅读并理解以下内容。**
>
> 1. 本应用按“现状”提供（AS IS），不附带任何明示或暗示的担保，包括但不限于适销性、特定用途适用性与不侵权的担保。
> 2. 本应用仅供个人运动记录与参考，**不构成专业导航、定位、气象或安全设备**，不得用于航空、航海、搜救等关键安全场景。
> 3. 骑行、徒步、登山、越野等户外运动存在固有风险。使用本应用时请遵守当地法律法规，注意交通安全，**请勿在骑行或运动过程中操作手机**。
> 4. GPS、罗盘、气压等传感器数据可能存在误差或失效（磁干扰、隧道、恶劣天气、设备限制），由此导致的路径偏差、位置误报及任何直接或间接的人身伤害、财产损失、延误或费用，**开发者不承担任何责任**。户外出行请携带专业装备并做好备份方案。
> 5. 在任何司法管辖区，若本免责声明的部分条款不被承认，其余条款仍然有效；使用本应用即表示你已阅读、理解并同意本声明。

## 地图与数据署名

- 矢量底图：© [CARTO](https://carto.com/attributions)，数据 © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL)
- 卫星影像：© Esri World Imagery
- 徒步等高线：© [OpenTopoMap](https://opentopomap.org) (CC-BY-SA)
- 骑行图层：© [CyclOSM](https://cyclosm.org) / OpenStreetMap contributors
- 地名检索：© Nominatim / OpenStreetMap

## 架构

```
domain/   Models · RideStats(haversine/谷峰爬升) · GattParser(HR/CSC) · Coach(卡路里/区间/播报)
session/  RideSession 实时状态 + km 节点 + 打卡 + 快照
sensor/   LocationEngine(免GMS 1Hz) · SensorEngine(罗盘+气压+水平仪) · BleManager
service/  RideRecordingService(前台+TTS+widget推送+富通知)
data/     Room v3(rides/points含speed/waypoints) · GpxExporter
share/    ShareImageRenderer(彩虹轨迹+打卡点)
widget/   AppWidget ×8
ui/       竞速主题(深空黑×电光青×荧光绿 / 日光浅色) · MapLibre 地图组件
```

## License

[MIT](LICENSE) © 2026 RETI
