# CampusTrade V5 数据库变更

更新日期：2026-10-31

## 1. 迁移文件

MySQL 使用 `src/main/resources/db/migration/mysql/V3__v5_trading_messaging_favorites.sql`。本地 H2 的 `db/schema.sql` 同步维护相同结构。迁移只新增字段、索引和表，并兼容转换 V4 订单；不删除用户、商品、图片或历史订单。

## 2. `trade_order` 新字段

- 商品快照：`product_title_snapshot`、`product_description_snapshot`、原有 `price`。
- 履约方式：`fulfillment_type`，值为 `CAMPUS_HANDOFF` 或 `SHIPPING`。
- 面交信息：`handoff_location`、`buyer_note`。
- 快递信息：`recipient_name`、`recipient_phone`、`shipping_address`、`shipping_carrier`、`tracking_number`。
- 操作时间：`expires_at`、`accepted_at`、`rejected_at`、`shipped_at`、`completed_at`、`cancelled_at`、`updated_at`。
- 卖家决定备注：`seller_decision_note`。

收货信息只允许订单参与者通过订单详情读取；卖家仅在接受快递订单后获得履约所需字段。日志、事件表和通知表均不保存这些敏感字段。

## 3. 新表

### `order_event`

保存订单状态转换审计记录。索引 `(order_id, created_at, id)` 支持详情页时间线查询。

### `conversation` 与 `private_message`

会话用 `context_type + context_id + 两个有序参与者 ID` 唯一约束避免重复。消息按 `(conversation_id, id)` 分页，未读查询使用 `(conversation_id, read_at, id)`。

### `product_favorite`

以 `(user_id, product_id)` 为主键，天然阻止重复收藏；保留对下架和售出商品的关联。

### `app_notification`

保存购买申请等非敏感通知，使用 `(user_id, read_at, created_at, id)` 查询用户未读消息。

## 4. 迁移前后检查

迁移前应备份 MySQL。迁移后检查：

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT status, COUNT(*) FROM trade_order GROUP BY status;
SHOW INDEX FROM trade_order;
SHOW INDEX FROM private_message;
SHOW INDEX FROM product_favorite;
```

不要重新执行旧的 `schema.sql` 或手工 V2 SQL；已有 MySQL 只应由 Flyway 自动执行 V3。
