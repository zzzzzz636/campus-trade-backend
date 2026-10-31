# V5 订单源码学习指南

## 1. 一次购买申请如何穿过系统

前端入口在 `src/main/resources/static/app.js` 的商品详情渲染逻辑。“提交购买申请”调用 `orders-v5.js` 的 `openPurchaseDialog(product)`。表单提交发生在 `bindOrderEvents()`：前端组装 `productId`、`requestKey`、`fulfillmentType` 和履约信息，通过 `api.js` 向 `POST /api/orders` 发送 JSON。

后端入口是 `OrderController#create`。Controller 只完成参数校验和从 JWT `sub` 获取买家 ID，不接收前端传来的买家 ID。随后调用 `OrderService#create(CreateOrderRequest,long)`。

Service 的关键顺序是：

1. 用 `buyerId + requestKey` 查找幂等订单。
2. `ProductMapper#findAnyByIdForUpdate` 对商品行执行 `SELECT ... FOR UPDATE`。
3. 校验不是自己的商品、状态仍为 `ON_SALE`。
4. `ProductMapper#reserveForOrder` 执行条件更新：只有 `status='ON_SALE'` 才能改为 `RESERVED`。
5. 把标题、描述和价格复制到 `trade_order` 快照字段。
6. 写订单、`order_event` 和卖家通知。

整个方法标有 `@Transactional`。任何一步抛出运行时异常，商品预订、订单、事件和通知一起回滚。事务只保证同一数据库事务内的一致性；并发唯一性仍依赖行锁、条件更新和唯一约束。

## 2. 状态操作的源码路径

前端 `orders-v5.js#renderOrderDetail` 根据 `viewerRole + status` 生成允许的按钮，真正权限仍由后端决定：

- 卖家接受：`OrderController#accept` → `OrderService#accept` → `OrderMapper#acceptPending`。
- 卖家拒绝：`reject` → `rejectPending`，随后 `ProductMapper#releaseAfterCancellation`。
- 卖家发货：`ship` → `markShipped`。
- 买家取消：`cancel` → `cancelPending`，随后释放商品。
- 买家确认：`complete` → `completeFulfillment`，随后 `ProductMapper#markSold`。

每个 Mapper 更新都在 SQL `WHERE` 中包含预期旧状态和操作者 ID。例如接受订单不是先在 Java 中判断后无条件更新，而是只更新 `PENDING_SELLER_CONFIRMATION`。这样即使两个请求同时通过早期读取，数据库也最多允许一个状态转换成功。

## 3. 为什么旧订单不会被误判为新申请

迁移文件 `src/main/resources/db/migration/mysql/V3__v5_trading_messaging_favorites.sql` 把 V4 的 `PENDING_MEETUP` 映射为 `WAITING_HANDOFF`。V4 的该状态已经代表商品被买家选定，不能退回成“等待卖家确认”。`COMPLETED` 和 `CANCELLED` 保持原值，并为所有历史订单写入 `V5_MIGRATED` 事件。

## 4. 超时释放

`OrderExpiryJob` 通过 `@Scheduled` 调用 `OrderService#expirePendingOrders`。Mapper 只选择过期的 `PENDING_SELLER_CONFIRMATION`，状态更新同样带条件。任务重复执行时，第一次已改成 `EXPIRED`，后续更新行数为 0，因此不会重复释放或重复记事件。

## 5. 订单详情和隐私

前端 `orders-v5.js#openOrderDetail` 请求 `GET /api/orders/{id}`。`OrderService#findDetail` 先执行 `requireParticipant`。对快递订单，买家始终可查看自己的收货资料；卖家只在接受后（`WAITING_SHIPMENT`、`SHIPPED`、`COMPLETED`）得到最小必要信息。日志只记录 orderId、actorId 和状态，不记录地址、电话或备注正文。

## 6. IDEA 断点建议

按下列顺序设置断点，可以看到一次申请的完整调用链：

1. `OrderController#create`：检查 JWT subject 与请求 DTO。
2. `OrderService#create(CreateOrderRequest,long)`：观察幂等判断、商品状态和事务。
3. `ProductMapper#findAnyByIdForUpdate` 及 `reserveForOrder`：打开 MyBatis SQL 日志时观察 SQL。
4. `OrderMapper#insert`：观察订单快照。
5. `OrderEventMapper#insert`：确认事件与订单处于同一事务。

调试并发测试时，在 `MySqlOrderConcurrencyIntegrationTests#innodbAllowsOnlyOneBuyerAndRollsBackDuplicateRequestReservation` 的线程任务内断点可能改变时序，建议先正常运行确认结果，再使用条件断点限定某个 buyerId。

## 7. F12 与日志排错

浏览器 F12 → Network：

- 找到 `/api/orders` 或状态接口。
- 检查 Request Payload 是否包含交易方式和必要字段。
- 检查状态码；`409` 多为状态已变化，`403` 是身份不匹配。
- 复制响应头/响应体中的 `X-Request-Id` 或 `requestId`。

后端日志格式包含 `[requestId=...]`。用该值搜索即可关联某次请求和状态转换。不要把 JWT、地址或电话复制到公开 issue。

## 8. 为什么没有引入更复杂方案

当前系统是模块化单体、单 MySQL 事务边界。订单和商品在同一数据库，声明式事务与 InnoDB 条件更新已经能可靠解决核心一致性；引入消息队列或分布式事务会增加失败模式，却没有当前业务收益。未来若通知需要异步扩展，可在订单事务中加入 outbox，而不是直接在事务内发送不可回滚的外部消息。
