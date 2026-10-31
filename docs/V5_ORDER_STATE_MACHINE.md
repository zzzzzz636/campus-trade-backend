# CampusTrade V5 订单状态机

更新日期：2026-10-31

## 1. 设计目标

V5 将 V4 的“买家下单后可立即确认成交”调整为真实的申请与履约流程。订单不代表付款成功，系统也不记录虚构的支付状态。商品从第一个有效申请开始进入 `RESERVED`，用数据库事务和条件更新阻止第二个买家成功锁定同一商品。

## 2. 状态与允许操作

| 状态 | 含义 | 买家操作 | 卖家操作 | 商品状态 |
| --- | --- | --- | --- | --- |
| `PENDING_SELLER_CONFIRMATION` | 等待卖家处理申请 | 取消、私信、查看 | 接受、拒绝、私信 | `RESERVED` |
| `WAITING_HANDOFF` | 卖家接受面交申请 | 确认收到、私信 | 私信 | `RESERVED` |
| `WAITING_SHIPMENT` | 卖家接受快递申请 | 私信 | 填写物流并发货、私信 | `RESERVED` |
| `SHIPPED` | 卖家已发货 | 确认收货、私信 | 查看、私信 | `RESERVED` |
| `COMPLETED` | 买家确认收到商品 | 查看历史 | 查看历史 | `SOLD` |
| `REJECTED` | 卖家拒绝申请 | 查看历史 | 查看历史 | 恢复 `ON_SALE` |
| `CANCELLED` | 买家在卖家接受前取消 | 查看历史 | 查看历史 | 恢复 `ON_SALE` |
| `EXPIRED` | 待确认申请超时 | 查看历史 | 查看历史 | 恢复 `ON_SALE` |

V5 采用明确规则：卖家接受后不再允许买家直接取消。双方需要先通过私信协商，后续版本可增加双方同意取消；已发货和已完成订单绝不允许取消。

## 3. 状态转换

```text
新申请
  └─> PENDING_SELLER_CONFIRMATION
       ├─卖家接受（面交）─> WAITING_HANDOFF ─买家确认收到─> COMPLETED
       ├─卖家接受（快递）─> WAITING_SHIPMENT ─卖家发货─> SHIPPED ─买家确认收货─> COMPLETED
       ├─卖家拒绝────────> REJECTED
       ├─买家取消────────> CANCELLED
       └─超时任务────────> EXPIRED
```

## 4. V4 历史订单迁移

V4 的 `PENDING_MEETUP` 已允许买家确认成交，语义上相当于卖家已经接受。因此 Flyway V3 将其迁移为 `WAITING_HANDOFF`，并把 `accepted_at` 设为原创建时间。`COMPLETED` 和 `CANCELLED` 保持原状态，不删除历史订单，也不会被改成新的待确认申请。

## 5. 并发与事务规则

- 创建申请先用 `SELECT ... FOR UPDATE` 锁定商品，再以 `status='ON_SALE'` 条件更新为 `RESERVED`，最后插入订单和事件；任一步失败整笔事务回滚。
- 接受、拒绝、取消、发货、确认收货都锁定订单行，并使用“当前状态 + 操作者身份”的条件更新。
- 拒绝、取消和过期必须在同一事务中把商品恢复为 `ON_SALE`。
- 完成必须在同一事务中把商品更新为 `SOLD`。
- 重复请求不会跨过状态条件；同一个 `requestKey` 只对应同一买家的一个订单。
- `order_event` 记录状态转换时间、操作者和非敏感备注；不记录地址、电话、私信正文或 Token。
