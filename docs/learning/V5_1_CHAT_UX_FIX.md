# CampusTrade V5.1 私信布局与轮询教学说明

## 1. 布局错误为什么会发生

旧版 `.chat-drawer` 使用四行隐式顺序：

```css
grid-template-rows: auto auto 1fr auto;
```

DOM 顺序原本是标题、加载历史按钮、消息区、输入表单。当 `#load-older-messages` 带有 `hidden` 时，全局规则 `[hidden]{display:none!important}` 会让按钮退出 Grid 布局。Grid 自动放置随即把消息区放进第二个 `auto` 行，把输入表单放进第三个 `1fr` 行。因此不是消息区，而是表单获得了几乎全部剩余高度。

旧版 `.message-form button{align-self:stretch}` 会让按钮跟随被错误放大的 Grid 行一起拉伸。小屏规则还把表单改成单列，进一步放大了这个问题。

V5.1 为元素指定稳定的命名区域：

```css
grid-template-areas: "header" "older" "messages" "composer";
grid-template-rows: auto minmax(0,auto) minmax(0,1fr) auto;
```

消息区始终位于 `messages` 的 `1fr` 行，表单始终位于 `composer` 的 `auto` 行。即使历史按钮隐藏，其他元素也不会换行。发送按钮使用明确的 44px（手机 42px）高度，不再依赖拉伸。

关键代码：`src/main/resources/static/styles.css` 的 `V5.1 persistent private messaging` 区域。

## 2. 为什么选择增量 HTTP 轮询

本版仍以 MySQL 为唯一消息事实来源，没有引入 WebSocket、消息队列或额外服务。聊天窗口打开时，每 2.5 秒请求：

```text
GET /api/conversations/{id}/messages?afterId={latestMessageId}&size=100
```

`afterId` 只读取比当前最大消息 ID 更新的记录，避免重复下载全部历史。关闭窗口会清除定时器并增加轮询代次；即使旧请求稍后返回，也会因代次或会话 ID 不匹配而被忽略。页面切到后台时暂停请求，重新可见时立即补一次。

后端保留原 `beforeId` 历史分页，并新增互斥的 `afterId` 游标。两者不能同时传入。参与者校验仍由 `MessagingService#requireParticipant` 完成，前端无法绕过权限。

关键代码：

- `MessagingController#messages`
- `MessagingService#messages`
- `MessageMapper#listPage`
- `messaging.js#startPolling` 与 `pollNewMessages`

## 3. 发送成功与后续刷新失败必须分开

发送请求返回 201 后，消息已经持久化到数据库。此时即使会话列表刷新失败，也不能提示“发送失败”，否则用户可能重复发送。

V5.1 将流程拆成两个阶段：

1. `POST /messages` 失败：保留输入内容，显示“发送失败”，允许重试。
2. `POST /messages` 成功：立即把服务端返回消息加入界面并清空输入框；后续列表刷新失败只提示“消息已发送，但会话列表刷新失败”。

Playwright 通过拦截请求分别模拟了这两种情况，验证提示和输入框状态。

## 4. 历史消息为什么使用可见消息锚点

把旧消息插入列表顶部后，简单恢复 `scrollTop` 会让阅读位置跳动。只按 `scrollHeight` 差值补偿也不够，因为最后一页加载完成时，历史按钮会隐藏，消息视口自身的位置和高度也会改变。

V5.1 在插入前记录“第一条当前可见消息”及其屏幕 `top` 坐标；插入后找到同一个 DOM 节点，根据坐标差调整 `scrollTop`。这样无论插入多少条消息、按钮是否消失，用户正在看的消息都停留在原位置。

## 5. 键盘和可访问性交互

- Enter：发送。
- Shift+Enter：换行。
- `compositionstart` 到 `compositionend` 期间不响应 Enter 发送，避免中文输入法误触。
- 发送时按钮禁用并显示“发送中…”。
- Escape、关闭按钮、遮罩均可关闭聊天；关闭后焦点回到原入口。
- 关联商品或订单以可聚焦按钮展示，可直接打开对应详情。
- 消息区使用 `role="log"`，状态文本使用 `aria-live`。

## 6. 如何继续调试

浏览器 Network 面板筛选 `conversations`：

- 发送请求应为 `POST .../messages`，成功状态 201。
- 自动获取应包含 `afterId`，关闭聊天后不应继续出现。
- 历史加载应包含 `beforeId`。
- 知道会话 ID 但不是参与者时，后端应返回 403。

布局问题可在 Elements 面板查看 `.chat-drawer` 的 Grid overlay。消息区应占据第三个命名区域，输入表单底边应与视口底边重合，发送按钮高度应保持 42–44px。
