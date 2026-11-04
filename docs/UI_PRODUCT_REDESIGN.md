# CampusTrade 全站 UI 改造记录

开发分支：`feature/ui-product-redesign`，基于 V6 稳定代码增量开发。前端继续使用原生 HTML、CSS 和 JavaScript 模块；商品、订单、私信、鉴权和图片业务 API 均未改动。未合并到 `main`。

## 设计系统

设计 token 位于 [`styles.css`](../src/main/resources/static/styles.css)：森林绿承担品牌与主要操作，暖白作为页面底色，珊瑚红用于价格，灰绿用于边线和次级文字。间距使用 4/8/12/16/24/32px 阶梯，控件最小高度 44px。商品照片优先；缺图时展示统一的线条照片占位，不编造图片。减少无意义卡片、渐变和 Emoji。

## 实际完成

- **商品广场**：真实在售商品卡片、图片优先、收藏入口、分类快捷筛选、关键词/分类/价格/排序组合、自由输入价格和快捷区间。删除零商品时由前端插入的三张演示卡片；空结果、加载中和请求失败有独立状态。
- **商品详情**：桌面双栏画廊与交易信息，手机纵向详情；成色、价格、卖家、状态、说明和收藏/私信/购买入口清楚排列。卖家仍只能从个人中心管理自己的在售商品。
- **发布闲置**：实物照片上传区与本地缩略图、字段重排、价格校验、AI 辅助区域、发布前本地预览。预览不向服务器写数据，正式发布仍调用原有 API。
- **个人中心**：账号身份、内容导航、发布/购买/售出/收藏/私信/通知的列表层级和空状态统一。商品管理按钮仍受原有后端权限和状态限制。
- **订单详情**：状态进度、当前提示、商品摘要、交易信息、交易操作及事件时间线分层呈现。交易状态转换和权限逻辑未改动。
- **私信与认证**：私信沿用 V5.1 的 grid 行、增量轮询与发送状态逻辑，仅统一视觉；认证页去除 Emoji 装饰并修复 768px 溢出。通知有明确未读状态。

## 浏览器截图对比

截图由 `V4BrowserFlowTests` 在真实 Edge + Spring Boot 测试服务器上生成；测试使用隔离 H2 与自动创建的双账号。图中的商品名称、邮箱和订单属于测试数据。`before/` 为改造前，`final/` 为最终版；最终版每个状态包含 390、768、1280、1440px。

| 页面 | 改造前 | 最终版 |
|---|---|---|
| 登录 | [390px](ui-screenshots/before/auth-390.png) · [1280px](ui-screenshots/before/auth-1280.png) | [390px](ui-screenshots/final/auth-390.png) · [1280px](ui-screenshots/final/auth-1280.png) |
| 商品广场 | [390px](ui-screenshots/before/plaza-390.png) · [1280px](ui-screenshots/before/plaza-1280.png) | [390px](ui-screenshots/final/plaza-390.png) · [1280px](ui-screenshots/final/plaza-1280.png) |
| 商品详情 | [390px](ui-screenshots/before/detail-390.png) · [1280px](ui-screenshots/before/detail-1280.png) | [390px](ui-screenshots/final/detail-390.png) · [1280px](ui-screenshots/final/detail-1280.png) |
| 发布商品 | [390px](ui-screenshots/before/publish-390.png) · [1280px](ui-screenshots/before/publish-1280.png) | [390px](ui-screenshots/final/publish-390.png) · [1280px](ui-screenshots/final/publish-1280.png) |
| 个人中心 | [390px](ui-screenshots/before/profile-390.png) · [1280px](ui-screenshots/before/profile-1280.png) | [390px](ui-screenshots/final/profile-390.png) · [1280px](ui-screenshots/final/profile-1280.png) |
| 订单详情 | [390px](ui-screenshots/before/order-390.png) · [1280px](ui-screenshots/before/order-1280.png) | [390px](ui-screenshots/final/order-390.png) · [1280px](ui-screenshots/final/order-1280.png) |
| 私信 | [390px](ui-screenshots/before/chat-390.png) · [1280px](ui-screenshots/before/chat-1280.png) | [390px 消息会话](ui-screenshots/final/chat-filled-390.png) · [1280px 消息会话](ui-screenshots/final/chat-filled-1280.png) |

最终版另外留有通知、我的购买、我的卖出、我的收藏和会话列表截图，见 [`final/`](ui-screenshots/final/)。

## 验收结果与边界

2026-11-04，Java 17.0.19、Windows Edge，`./mvnw.cmd -q test` 退出码 0；Surefire 共 **55** 个测试，**0** 失败、**0** 错误、**8** 跳过，实际执行 47 个。双账号 Playwright 流程实际完成注册、商品发布、搜索与价格快捷筛选、空结果、收藏、私信收发/轮询/历史消息、购买申请、卖家接受、买家确认成交、订单/通知/收藏查看和刷新后登录状态。四档宽度对 13 个页面状态检查了横向溢出；私信另外检查了消息区、输入区和发送按钮尺寸。

跳过的 8 个是需要外部服务的 `MySqlOrderConcurrencyIntegrationTests`（6 个）和 `DockerMailRedisIntegrationTests`（2 个）。本次 UI 验收未连真实 MySQL、Redis、SMTP 或真实 AI 模型，也未在公网部署。`local` 配置会注入演示商品作为开发与测试数据；生产商品列表由真实数据库返回，前端不再插入伪商品。

## 重现截图

在项目根目录设置 Java 17 后运行：

```powershell
.\mvnw.cmd '-Dtest=V4BrowserFlowTests#twoBrowserUsersApplyMessageFavoriteAcceptCompleteRefreshAndLogout' '-Dcampus.ui.capture=final' test
```

测试会覆写 `docs/ui-screenshots/final/` 的同名图片；Windows 若有图片查看器锁住文件，请先关闭查看器，或改用新的截图阶段目录。正常启动方法见 [`quickstart.md`](quickstart.md)。

## 后续事项

- 在带真实照片和更多商品的隔离数据集上再检查图片密度、长标题和多页列表；本轮截图主要覆盖缺图占位及少量测试商品。
- 在真实 MySQL/Redis/SMTP 环境做端到端验收；本轮通过的是 H2 浏览器流程。
- 评估键盘焦点恢复、对话框焦点圈定和更完整的屏幕阅读器检查。
