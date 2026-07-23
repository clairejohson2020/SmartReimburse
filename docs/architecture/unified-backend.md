# 统一后端架构决策

## 决策

SmartReimburse 以微信云开发作为唯一远程数据源。微信小程序继续通过 `openid` 登录；Android 不保存微信凭据，而是通过一次性六位配对码，由已登录的小程序账号批准设备绑定。Android 获得的随机设备令牌只返回一次，服务端仅保存 SHA-256 摘要，本机使用 Android Keystore 加密存储。

这取代“两套独立数据”的旧路线。Room 仍作为 Android 离线缓存和待同步队列，而不是第二个权威数据源。

## 数据流

1. Android 调用 `syncApi /pairings` 生成配对码和仅本机持有的随机配对密钥。
2. 用户在小程序首页输入配对码，`dataApi.approveDevicePair` 将设备绑定到当前 `userId`。
3. Android 用配对密钥交换可撤销设备令牌。
4. Android 先推送本地项目、支出、附件和删除记录，再分别拉取项目、支出、附件及删除墓碑。
5. 服务端 `version` 与 Android `syncVersion` 不一致时返回冲突；客户端标记 `CONFLICT`，不静默覆盖。

## 金额和字段规范

- 金额权威字段为整数分：`advanceFundCents`、`priceCents`、`amountCents`。
- `advanceFund`、`price`、`totalAmount` 暂时保留为兼容展示字段。
- 数量必须是正整数，金额必须大于零。
- 有发票记录必须包含 `invoice` 附件；无发票记录必须包含 `payment` 附件。
- 名称最长 100 字，型号最长 100 字，链接和备注最长 2000 字。

## 冲突与删除

- 所有远程实体带单调递增的 `version`。
- 推送必须携带 `baseVersion` 和稳定的 `clientMutationId`，服务端对重试去重。
- 并发更新由用户在 Android 同步页选择“保留本机”或“采用云端”；远程删除采用删除优先策略。
- 删除在同一数据库事务中写入 `sync_tombstones` 后再删除业务记录。
- 云文件删除失败会写入 `maintenance_tasks`，由定时维护函数重试并最终进入 `dead_letter`。
- 删除墓碑保留 180 天；超过 180 天未同步的设备应执行一次完整同步。

## 附件安全

- 新附件的 `fileID` 必须位于 `attachments/{userId}/`。
- 更新已有附件时，服务端同时验证附件 ID、用户 ID、支出 ID 和原 `fileID`。
- Android 下载附件前，服务端再次验证附件归属，然后签发短期下载地址。
- 单个 Android HTTP 上传附件限制为 5MB，类型限制为 JPEG、PNG、WebP。

## 暂缓项

真实小程序 OCR 仍保持关闭，等待 API 凭据和供应商最终确认；当前入口已经补齐用户及文件归属校验。
