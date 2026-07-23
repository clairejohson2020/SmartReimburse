# SmartReimburse

SmartReimburse 是一个 Kotlin + Jetpack Compose 构建的离线优先报销管理 Android 应用，支持 Android 8.0/API 26 及以上版本，并可通过微信云统一后端与微信小程序同步。

## 项目结构

- `app/src/main/java/com/smartreimburse/data`：Room 实体、DAO、数据库、附件类型转换。
- `app/src/main/java/com/smartreimburse/repository`：备用金、支出、附件的 Repository。
- `app/src/main/java/com/smartreimburse/viewmodel`：MVVM 状态、OCR 填表、保存校验、导出协调。
- `app/src/main/java/com/smartreimburse/ocr`：ML Kit 中文 OCR 和字段解析。
- `app/src/main/java/com/smartreimburse/camera`：CameraX 图片保存和附件私有存储。
- `app/src/main/java/com/smartreimburse/export`：Apache POI `.xlsx` 导出。
- `app/src/main/java/com/smartreimburse/share`：FileProvider + ShareCompat 分享。
- `app/src/main/java/com/smartreimburse/ui`：Material 3 Compose 主题、导航、首页、表单、详情、拍照页。

## 构建

用 Android Studio 打开当前目录并同步 Gradle。项目配置：

- `minSdk = 26`
- `targetSdk = 36`
- Kotlin + Jetpack Compose + Navigation Compose
- Room + CameraX + ML Kit 中文 OCR + Coil + Apache POI

## 关键实现

- 发票号码通过 `OcrParser` 中的正则匹配“发票号码/发票代码/No/№”后跟 8-12 位数字或字母。
- Excel 导出在 `ExcelExporter` 中使用 Apache POI 写出 OOXML `.xlsx`，再由 FileProvider 生成只读分享 Uri。
- 无发票记录保存前会校验付款截图；有发票记录保存前会校验发票原图。
- 附件保存到应用私有目录，删除记录或编辑移除附件后会清理本地文件。

## 多端同步

Android 在“多端同步”页面生成六位配对码，用户在已登录的微信小程序首页批准后即可同步项目、支出和附件。详细设计见 `docs/architecture/unified-backend.md`，部署、索引、定时清理和告警要求见 `docs/cloud-operations.md`。
