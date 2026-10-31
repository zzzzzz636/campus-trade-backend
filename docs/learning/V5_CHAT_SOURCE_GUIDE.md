# V5 私信源码学习指南

## 1. 前端请求从哪里发出

商品详情中的“联系卖家”位于 `src/main/resources/static/app.js`，订单详情中的联系按钮位于 `js/orders-v5.js`。它们都调用 `js/messaging.js#openConversation(contextType, contextId)`：

1. `POST /api/conversations` 查找或创建会话。
2. `GET /api/conversations/{id}/messages` 获取最近消息。
3. `POST /api/conversations/{id}/read` 标记对方消息已读。
4. 发送表单调用 `POST /api/conversations/{id}/messages`。

前端从不提交 `senderId`。刷新后重新调用会话和消息查询，内容来自 MySQL，而不是 `localStorage`。

## 2. Controller 与 Service

`MessagingController` 从 JWT subject 得到当前用户 ID，并把分页参数限制为合理范围。核心判断位于 `MessagingService`：

- `start`：商品上下文自动选择商品卖家；订单上下文只允许订单买家和卖家。
- `requireParticipant`：每次列消息、发送、已读前都用当前用户查询会话。
- `send`：先校验参与者和速率，再保存消息并更新会话时间。
- `messages`：使用 `beforeId` 游标向前分页，避免页码在新消息插入时漂移。

发送者身份由服务端设置为当前 JWT 用户。即使攻击者修改前端 JavaScript，也无法伪造第三人的 senderId。

## 3. Mapper 和 MySQL 表

`ConversationMapper` 操作 `conversation`：

- 唯一键 `(context_type, context_id, participant_low_id, participant_high_id)` 防止重复会话。
- 两个参与者 ID 始终按小到大存入 low/high，因此 A-B 与 B-A 是同一组合。
- 会话列表查询关联对方昵称、上下文标题、最后消息和未读数。

`MessageMapper` 操作 `private_message`：

- `listPage` 使用 `conversation_id + id` 索引和 `id < beforeId`。
- `markRead` 只更新“发送者不是当前用户且 read_at 为空”的记录。
- `insert` 成功后才 `touch` 会话；两步位于 `@Transactional` 方法，任一步失败都会回滚。

## 4. 限流设计

`MessageRateLimiter` 使用每用户一分钟滑动窗口，默认 20 条。它避免单实例下的简单刷屏，不记录消息正文。当前方案故意保持轻量；如果多实例部署，各实例内存不共享，应把窗口迁移到 Redis Lua 或令牌桶。

## 5. 权限与关闭订单

商品会话建立后与商品状态解耦；订单会话建立后也不会因订单完成、拒绝或取消而删除，所以双方仍能查看合法历史沟通。权限由会话参与者字段决定，不会因为知道 conversationId 就获得读取权。

## 6. IDEA 断点建议

一次发送消息可依次设置：

1. `MessagingController#send`：确认 JWT 用户。
2. `MessagingService#send`：观察参与者校验和限流。
3. `MessageRateLimiter#check`：观察窗口淘汰。
4. `MessageMapper#insert`：查看最终 SQL 参数。
5. `ConversationMapper#touch`：确认会话排序时间更新。

越权排查可在 `MessagingService#requireParticipant` 断点，使用第三个账号访问测试会话，预期查询结果为 null 并抛出 403。

## 7. 浏览器与日志排错

F12 → Network 中筛选 `conversations`：

- `403 ACCESS_DENIED`：当前账号不是参与者。
- `429 RATE_LIMITED`：一分钟发送次数过多。
- `400 VALIDATION_FAILED`：空消息、超长消息或分页参数非法。
- 发送 `201` 但界面后续刷新失败时，先检查发送请求本身是否成功，再单独检查列表请求；前端不会把二者混成“发送失败”。

响应中的 `requestId` 可关联服务端日志。日志禁止打印私信正文、JWT 和密码。

## 8. 为什么先用 HTTP

V5 的首要目标是持久化、权限和正确性。HTTP 已能稳定支持会话列表、历史分页和发送；WebSocket 只改善实时体验，不能替代 MySQL 写入。未来加入 WebSocket 时，应在消息事务提交后推送，并在断线重连后继续以 HTTP/数据库补偿历史消息。
