# CampusTrade V6 新增接口

沿用 [`V5_API.md`](V5_API.md) 的 JWT 与 `application/problem+json` 错误格式。所有订单、通知和资料接口从 JWT subject 获取用户 ID，请求体不能指定操作者。以下示例中的 `123` 是订单 ID，`7` 是通知 ID。

## 订单分页

- `GET /api/orders/purchases/page?page=1&size=20&status=WAITING_HANDOFF`
- `GET /api/orders/sales/page?page=1&size=20&status=PENDING_SELLER_CONFIRMATION`

`status` 可省略；支持 `PENDING_SELLER_CONFIRMATION`、`WAITING_HANDOFF`、`WAITING_SHIPMENT`、`SHIPPED`、`COMPLETED`、`CANCELLED`、`REJECTED`、`EXPIRED`。`page` 为 1–1000，`size` 为 1–50。按 `created_at DESC, id DESC` 稳定排序。原 `/purchases` 和 `/sales` 数组接口保留，默认只返回前 50 条。

```json
{"items":[{"id":123,"status":"WAITING_HANDOFF","productId":12}],"total":1,"page":1,"size":20,"hasMore":false}
```

参数越界或未知状态返回 `400`；未登录 `401`。卖家接受已到截止时间的申请返回 `409`，由超时任务随后释放商品。

## 协商取消

| 接口 | 请求 | 响应 | 约束 |
|---|---|---|---|
| `GET /api/orders/123/cancel-request` | 无 | 待处理请求 JSON；没有则 `null` | 仅双方可查 |
| `POST /api/orders/123/cancel-request` | `{"reason":"时间冲突"}` | `201` + 请求 JSON | 已接受且未发货/完成；同一时刻仅一条待处理 |
| `POST /api/orders/123/cancel-request/accept` | 无 | `200` + 状态 `CANCELLED` 的订单 | 仅另一方，商品恢复 `ON_SALE` |
| `POST /api/orders/123/cancel-request/reject` | 无 | `200` + 原状态的订单 | 仅另一方，交易继续 |

请求示例：

```json
{"id":9,"orderId":123,"requesterId":5,"status":"PENDING","reason":"时间冲突","createdAt":"2026-11-02T10:00:00","resolvedAt":null}
```

原因 1–300 字。非参与者 `403`，状态不允许、重复请求或发起者自行处理 `409`，请求体错误 `400`。同意取消、释放商品、更新请求、事件日志与通知在同一事务中完成；发货或完成在待处理期间返回 `409`。

## 通知中心

- `GET /api/notifications?page=1&size=20`：当前用户通知页；`page` 1–1000、`size` 1–50。返回 `{"items":[{"id":7,"type":"ORDER_APPLICATION","referenceId":123,"title":"收到新的购买申请","readAt":null}],"total":1,"page":1,"size":20,"hasMore":false}`。
- `GET /api/notifications/unread-count`：返回 `{"count":1}`。
- `POST /api/notifications/7/read`：标记已读，返回 `204`；重复请求保持成功。

通知类型包括 `ORDER_APPLICATION`、`ORDER_ACCEPTED`、`ORDER_REJECTED`、`ORDER_CANCELLED`、`ORDER_EXPIRED`、`ORDER_SHIPPED`、`ORDER_COMPLETED`、`CANCELLATION_REQUESTED`、`CANCELLATION_ACCEPTED`、`CANCELLATION_REJECTED`。只按 JWT 用户过滤，其他用户通知 ID 返回 `404`。未登录 `401`；分页越界 `400`。前端点击后打开关联订单详情。

## 用户资料与密码

| 接口 | 请求 JSON | 成功响应 | 常见错误 |
|---|---|---|---|
| `GET /api/auth/me` | 无 | `{"id":5,"email":"a@example.com","nickname":"同学"}` | 未登录/旧令牌 `401` |
| `PUT /api/auth/me` | `{"nickname":"新昵称"}` | 更新后的资料 JSON | 空或超长 `400` |
| `POST /api/auth/me/password` | `{"currentPassword":"旧密码","newPassword":"新密码至少八位"}` | `204` | 原密码错 `400`，并发修改 `409` |
| `POST /api/auth/password-reset-codes` | `{"email":"a@example.com"}` | `202`，`{"deliveryMode":"SMTP","expiresInSeconds":300}` | 账号不存在 `404`、频繁请求 `429`、邮件/Redis 故障 `503` |
| `POST /api/auth/password-reset` | `{"email":"a@example.com","code":"123456","newPassword":"新密码至少八位"}` | `204` | 错码 `400`、过期/重放 `410`、尝试过多 `429` |

改密与重置均使该用户已签发的 JWT 立即失效，需要重新登录。`local` profile 的 `deliveryMode` 为本地控制台模式，正式环境需要 Redis 和真实 SMTP 配置。

## 通用错误示例

```json
{"type":"about:blank","title":"请求未完成","status":409,"detail":"已有待处理的协商取消请求","errorCode":"STATE_CONFLICT","requestId":"..."}
```
