# 微信云后端部署与运维

## 必需集合

除原有 `users`、`user_openids`、`projects`、`expenses`、`attachments` 外，需创建：

- `device_pairings`
- `api_tokens`
- `sync_mutations`
- `sync_tombstones`
- `maintenance_tasks`
- `merge_operations`
- `export_files`
- `pending_uploads`

建议关闭所有集合的客户端直接读写权限，只允许云函数访问。小程序所有业务访问均通过云函数完成。

## 建议索引

- `user_openids`: `openid`
- `users`: `userId`、`phoneNumber`
- `projects`: `userId + updatedAt`
- `expenses`: `userId + projectId + date`、`userId + updatedAt`
- `attachments`: `userId + expenseId`、`userId + projectId`、`userId + updatedAt`
- `device_pairings`: `code + status`、`deviceId + createdAt`
- `api_tokens`: `tokenHash + status`
- `sync_mutations`: 唯一索引 `userId + clientMutationId`
- `sync_tombstones`: `userId + deletedAt`
- `export_files`: `userId + expiresAt`
- `pending_uploads`: `userId + fileID`、`expiresAt`
- `maintenance_tasks`: `status + updatedAt`

## HTTP 同步入口

为 `syncApi` 配置微信云开发 HTTP 访问服务，并把最终 HTTPS 基础地址写入：

- GitHub Secret `SMART_REIMBURSE_SYNC_API_URL`
- 本地正式构建环境变量 `SMART_REIMBURSE_SYNC_API_URL`

只允许 HTTPS。网关应限制请求体大小，并为 `/pairings` 增加 IP 级限流。业务 API 仍会执行设备令牌和对象归属校验。

## 定时维护

为 `maintenance` 配置每日定时触发器，在云函数环境变量设置高熵 `MAINTENANCE_SECRET`，并让定时事件携带相同的 `secret` 字段。维护任务负责：

- 重试失败的云文件删除；
- 删除七天到期的 Excel 导出；
- 清理过期设备令牌和配对请求；
- 清理 30 天前的幂等请求记录；
- 清理 180 天前的删除墓碑。

## 监控告警

云函数统一输出单行 JSON 日志。建议在微信云监控建立以下告警：

- `sync_api_failure`：五分钟内错误率超过 5%；
- `data_api_failure`：五分钟内超过 10 次；
- `user_merge_failed`：出现一次即告警；
- `cloud_file_cleanup_queued`：十五分钟内超过 20 次；
- `maintenance_failure`：出现一次即告警；
- `excel_export_failure`：十五分钟内超过 5 次；
- `maintenance_tasks.status = dead_letter`：出现记录即人工处理。

日志不得包含设备令牌、配对密钥、手机号、附件内容或 OpenAI API 密钥。

## Android 正式发布 Secrets

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `SMART_REIMBURSE_SYNC_API_URL`

工作流会执行单元测试、Release Lint、R8、签名 APK/AAB 构建和 `apksigner` 验证。缺少任一必要 Secret 时会直接失败，不再发布 Debug APK。
