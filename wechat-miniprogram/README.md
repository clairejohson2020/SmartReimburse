# SmartReimburse 微信小程序

这是 SmartReimburse 的微信小程序端，使用微信云开发作为统一后端保存用户、项目、支出、附件和导出文件。Android App 可通过一次性配对码绑定当前微信账号，并进行离线优先同步。

## 打开工程

1. 使用微信开发者工具打开 `wechat-miniprogram/`。
2. 将 `project.config.json` 中的 `appid` 替换为真实小程序 AppID。
3. 将 `miniprogram/env.js` 中的 `cloudEnv` 替换为真实云开发环境 ID。
4. 在开发者工具中开通云开发，并创建/确认以下集合：
   - `users`
   - `user_openids`
   - `projects`
   - `expenses`
   - `attachments`
5. 上传并部署所有云函数，部署时选择“云端安装依赖”：
   - `loginOrRegister`
   - `bindPhone`
   - `dataApi`
   - `ocrReceipt`
   - `exportExcel`
   - `syncApi`
   - `maintenance`

## 自动上传到微信小程序后台

仓库已配置 GitHub Actions：`.github/workflows/wechat-miniprogram-release.yml`。

需要先在 GitHub 仓库 Secrets 中配置：

- `WECHAT_MINIPROGRAM_APPID`：真实小程序 AppID。
- `WECHAT_MINIPROGRAM_PRIVATE_KEY`：微信公众平台“小程序代码上传密钥”的私钥完整内容。
- `WECHAT_MINIPROGRAM_CLOUD_ENV`：微信云开发环境 ID。

本地触发上传：

```powershell
.\scripts\publish-miniprogram.ps1 -Version 1.2 -CommitMessage "Release mini program v1.2"
```

脚本会创建并推送 `mini-v1.2` 标签，GitHub Actions 随后会安装 `miniprogram-ci`，上传云函数，并上传小程序代码到微信小程序后台。

也可以在 GitHub Actions 页面手动运行 `WeChat Mini Program Release`，填写版本号、描述和上传机器人编号。

注意：自动化会把代码上传到微信后台的开发版本/体验版。正式发布仍需要按微信平台规则完成审核发布；审核通过后的正式发布可在微信公众平台操作。

## 功能

- 统一登录：小程序启动后按微信 `openid` 自动注册/恢复账号。
- 手机号绑定：使用微信手机号授权组件，不接短信验证码。
- 账号合并：同一手机号对应多个微信号时，自动合并到同一个 `userId`。
- 项目化报销：项目切换、项目新建/改名/删除、项目备用金、独立统计。
- 支出管理：新增、编辑、删除、筛选支出；上传发票、付款截图和其他图片附件。
- Excel 导出：云函数生成当前项目 `.xlsx`，下载后调用微信文件分享能力发送给好友或群。
- Android 同步：在首页输入 Android 显示的六位配对码，批准设备访问当前账号。

## OCR 配置

`ocrReceipt` 已保留云函数入口。第一版在未配置 OCR 服务时会返回“请手动补录”，不会阻塞保存支出。

要启用真实 OCR，请在 `cloudfunctions/ocrReceipt/index.js` 中接入腾讯云 OCR 或微信当前可用的 OCR 服务，并通过云函数环境变量保存服务配置，避免把密钥暴露在小程序前端。

## 数据模型

- `users`：`userId`、`openids`、`phoneNumber`、`phoneVerifiedAt`、创建/更新时间。
- `user_openids`：`openid` 到 `userId` 的映射，用于登录恢复和手机号合并。
- `projects`：`userId`、项目名称、备用金、创建/更新时间。
- `expenses`：`userId`、项目 ID、名称、型号、数量、金额、日期、发票信息、链接和备注。
- `attachments`：`userId`、项目 ID、支出 ID、云存储 `fileID`、附件类型、文件名、大小。
