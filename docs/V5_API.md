# CampusTrade V5 API

所有未特别说明的接口都要求 `Authorization: Bearer <JWT>`。请求可以携带 `X-Request-Id`；响应会回传同名头。错误响应采用 `application/problem+json`，包含 `status`、`detail`、`errorCode`、`requestId`。

## 订单

### `POST /api/orders`

创建等待卖家确认的购买申请。

```json
{
  "productId": 12,
  "requestKey": "9d247e13-632d-4b63-a028-7ad21fe318a3",
  "fulfillmentType": "CAMPUS_HANDOFF",
  "buyerNote": "希望周五下午交易",
  "handoffLocation": "东区食堂门口"
}
```

快递方式使用 `SHIPPING`，并必须提供 `recipientName`、`recipientPhone`、`shippingAddress`。成功返回 `201`。重复使用相同买家、商品和 `requestKey` 时返回原订单；请求键已用于另一商品返回 `409 STATE_CONFLICT`。

### 状态操作

| 接口 | 操作者 | 前置状态 | 结果 |
|---|---|---|---|
| `POST /api/orders/{id}/accept` | 卖家 | `PENDING_SELLER_CONFIRMATION` | 面交进入 `WAITING_HANDOFF`；快递进入 `WAITING_SHIPMENT` |
| `POST /api/orders/{id}/reject` | 卖家 | `PENDING_SELLER_CONFIRMATION` | `REJECTED`，商品恢复 `ON_SALE` |
| `POST /api/orders/{id}/cancel` | 买家 | `PENDING_SELLER_CONFIRMATION` | `CANCELLED`，商品恢复 `ON_SALE` |
| `POST /api/orders/{id}/ship` | 卖家 | `WAITING_SHIPMENT` | `SHIPPED` |
| `POST /api/orders/{id}/complete` | 买家 | `WAITING_HANDOFF` 或 `SHIPPED` | `COMPLETED`，商品变为 `SOLD` |

接受或拒绝可以提交 `{"note":"处理备注"}`。发货请求：

```json
{"carrier":"顺丰速运","trackingNumber":"SF123456789"}
```

重复操作和非法状态转换返回 `409`；非对应买家/卖家返回 `403`。

### 查询

- `GET /api/orders/purchases`：当前用户购买订单。
- `GET /api/orders/sales`：当前用户售出订单。
- `GET /api/orders/{id}`：参与者查看订单详情、快照和事件时间线。

卖家在接受快递申请前不会得到收件姓名、电话和地址。第三人查询订单详情返回 `403`。

## 站内私信

### `POST /api/conversations`

```json
{"contextType":"PRODUCT","contextId":12}
```

`contextType` 可以是 `PRODUCT` 或 `ORDER`。商品会话由当前用户与商品卖家建立；订单会话仅订单双方可建立。唯一约束保证同一上下文和同一对用户只生成一条会话。

### 会话与消息

- `GET /api/conversations?page=1&size=20`：会话列表、最后消息和未读数。
- `GET /api/conversations/{id}/messages?size=30&beforeId=100`：按消息 ID 向前翻页。
- `POST /api/conversations/{id}/messages`：请求体 `{"content":"消息内容"}`，成功返回 `201`。
- `POST /api/conversations/{id}/read`：把对方发送的未读消息标记为已读。

消息最长 1000 字；默认每用户每分钟最多 20 条。发送者 ID 来自 JWT，不能由请求体指定。非参与者访问返回 `403`。

## 收藏

- `POST /api/products/{productId}/favorite`：收藏，幂等，返回 `204`。
- `DELETE /api/products/{productId}/favorite`：取消收藏，幂等，返回 `204`。
- `GET /api/favorites?page=1&size=50`：收藏商品列表，包括已售出和已下架状态。
- `GET /api/favorites/product-ids`：当前用户收藏的商品 ID，用于商品卡片状态。

收藏主键为 `(user_id, product_id)`，不会生成重复记录。接口始终只操作 JWT 当前用户的数据。

## 通用错误示例

```json
{
  "type": "about:blank",
  "title": "请求未完成",
  "status": 409,
  "detail": "订单已经处理，不能重复接受",
  "instance": "/api/orders/21/accept",
  "errorCode": "STATE_CONFLICT",
  "requestId": "c7c9d4ac-d4b7-49c1-9db5-ab22ad4ddcf7"
}
```

常见错误码：`VALIDATION_FAILED`、`ACCESS_DENIED`、`RESOURCE_NOT_FOUND`、`STATE_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR`。
