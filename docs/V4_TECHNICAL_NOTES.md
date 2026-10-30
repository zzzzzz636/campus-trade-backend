# CampusTrade V4 技术说明：多用户业务闭环与前端交互重构

更新日期：2026-10-30

## 1. 本版目标与边界

V4 不改变模块化单体架构，不迁移微服务，也不重写现有后端。重点是验证并固化两个真实账号之间的权限隔离和交易闭环，同时让商品广场、发布页和个人中心各自承担清晰职责。

本版没有数据库结构变更或新增 Flyway 迁移。MySQL、Redis、Flyway、Spring Security、订单事务、图片存储和 AI 辅助发布均沿用 V3 的实现。

## 2. 页面职责

### 商品广场

- 只展示公开的 `ON_SALE` 商品。
- 支持关键词、分类、价格、排序和分页。
- 商品卡片进入详情；买家可以创建线下面交订单。
- 广场不直接提供编辑、下架和图片管理。卖家查看自己的商品时只显示前往“我发布的”的入口。

### 发布闲置

- 创建商品时填写分类、成色、标题、描述、价格并选择最多 6 张图片。
- 保留 AI 文本整理；AI 失败不会阻止手动发布。
- 编辑复用相同表单，但只有从个人中心的“我发布的”进入，保存或返回后回到该列表。

### 个人中心

- “我发布的”展示当前用户全部商品状态，并提供符合状态规则的管理入口。
- “我买到的”只查询当前 JWT 用户作为买家的订单。
- “我卖出的”只查询当前 JWT 用户作为卖家的订单。
- 数据隔离由后端 JWT 身份和 SQL 条件保证，前端隐藏按钮只用于改善体验，不构成权限控制。

## 3. 后端权限矩阵

| 操作 | 未登录 | 商品卖家 | 其他登录用户 | 关键后端校验 |
| --- | --- | --- | --- | --- |
| 浏览在售商品 | 允许 | 允许 | 允许 | 公开查询仅返回 `ON_SALE` |
| 发布商品 | 拒绝 | 允许 | 允许 | JWT 用户作为 `seller_id` |
| 编辑/下架 | 拒绝 | 仅 `ON_SALE` 允许 | 403 | Service 所有权校验 + SQL `seller_id/status` 条件更新 |
| 上传/删除图片 | 拒绝 | 仅 `ON_SALE` 允许 | 403 | 锁商品行并校验 `seller_id` |
| 购买商品 | 拒绝 | 禁止购买自己商品 | 允许 | 商品行锁、状态条件更新、事务 |
| 查看订单 | 拒绝 | 仅自己的售出订单 | 仅自己的买入订单 | 查询固定使用 JWT 用户 ID |
| 取消/确认成交 | 拒绝 | 不允许代买家操作 | 仅订单买家 | 买家身份和订单状态机校验 |

商品进入 `RESERVED` 后不可编辑、下架或管理图片；订单取消后商品恢复 `ON_SALE`；确认成交后商品进入 `SOLD`，不再出现在公开广场。

## 4. 认证验证结果

`V4MultiUserSecurityIntegrationTests` 使用真实 HTTP 请求验证：

- 不存在账号和错误密码均不能登录，且不泄露账号是否存在。
- 畸形 JWT、已过期但签名正确的 JWT、引用不存在用户的签名 JWT 均不能访问私有接口。
- 有效 JWT 仍会查询数据库中的用户状态；用户不存在或不为 `ACTIVE` 时拒绝访问。
- A 发布的商品，B 可以浏览和购买，但 B 编辑、下架、上传或删除图片均被后端拒绝。
- A、B 的发布列表、买入订单和卖出订单互相隔离。

关键位置：

- `auth/service/AuthService.java`
- `auth/security/SecurityConfig.java`
- `auth/security/JwtService.java`
- `product/service/ProductService.java`
- `product/mapper/ProductMapper.java`
- `product/service/ProductImageService.java`
- `order/service/OrderService.java`

## 5. 前端渐进式模块化

`index.html` 现在以 ES Module 方式加载 `app.js`，Spring Security 对 `/js/**` 放行静态资源。拆分结果如下：

| 文件 | 职责 |
| --- | --- |
| `static/js/core.js` | 页面状态、DOM 辅助、金额/时间/状态格式化和 Toast |
| `static/js/api.js` | Fetch 请求、JSON 错误解析、Bearer Token 和统一 401 处理 |
| `static/js/auth.js` | 登录、注册、验证码、退出和 sessionStorage 身份恢复 |
| `static/js/orders.js` | 买入/卖出订单渲染与取消、确认成交操作 |
| `static/js/profile.js` | 个人中心加载、标签切换和“我发布的”管理入口 |
| `static/app.js` | 页面导航、商品广场、详情、发布/编辑、图片和 AI 草稿编排 |

本次没有直接迁移 Vue 3，原因是现有原生页面规模仍可控，直接换框架会同时重写路由、状态、请求层和构建链，扩大回归面，却不能直接增强后端业务深度。当前拆分先建立稳定的 API 与状态边界；后续若增加收藏、聊天、通知和复杂详情路由，再按“请求层与 DTO → 公共组件 → 单页路由”的顺序迁移 Vue，更容易逐页验收。

### 浏览器测试发现并修复的真实问题

发布表单原先在异步 `await` 之后继续读取 `event.currentTarget`。真实浏览器结束事件分发后会清空该值，导致商品接口已返回 201、数据库已创建商品，但前端在重置表单时抛错并停止跳转。现在提交处理器会在异步调用前保存表单元素引用。

## 6. 自动化验收

### API 多用户集成测试

`V4MultiUserSecurityIntegrationTests` 覆盖认证失败、无效/过期 JWT、A/B 商品所有权、图片权限、订单数据隔离、取消恢复和确认售出。

### 真实浏览器测试

`V4BrowserFlowTests` 启动随机端口 Spring Boot 和隔离 H2 数据库，创建两个独立 Edge `BrowserContext`，通过页面完成：

1. A 请求验证码、注册并登录。
2. A 发布商品；广场详情没有管理按钮，个人中心可以编辑。
3. A 刷新页面，sessionStorage 身份恢复。
4. B 注册、搜索并打开 A 的商品。
5. B 直接请求编辑接口得到 403，证明权限不是只靠隐藏按钮。
6. B 通过页面下单并在“我买到的”看到订单。
7. A 在“我卖出的”看到同一订单。
8. B 确认成交，A 的商品状态变成 `SOLD`。
9. B 退出后私有接口返回 401，刷新后仍保持退出状态。

测试使用 `LocalConsoleMailSender` 读取本地测试验证码，不增加测试专用 HTTP 后门。浏览器测试显式复用 Windows 已安装的 Edge，并禁止 Playwright 重复下载 Chromium；非 Windows 系统会跳过该用例。

运行命令：

```powershell
$env:JAVA_HOME='C:\Users\zw\.jdks\ms-17.0.19'
.\mvnw.cmd '-Dtest=V4BrowserFlowTests' test
```

真实 MySQL 并发安全仍由 `MySqlOrderConcurrencyIntegrationTests` 单独验证：8 个线程竞争同一商品时最多一笔有效订单。该测试需要 Docker，并通过 `-Dcampus.mysql.tests=true` 显式启用。

### 2026-10-30 实际执行结果

- `node --check`：`app.js` 和 5 个 ES Module 全部通过。
- `mvnw.cmd test`：36 个测试，0 失败、0 错误、3 个跳过，`BUILD SUCCESS`，总耗时 56.340 秒。3 个跳过项正是默认关闭的 `MySqlOrderConcurrencyIntegrationTests`。
- `mvnw.cmd -Dtest=V4BrowserFlowTests test`：1 个真实 Edge 业务流程测试通过，0 失败、0 错误。
- `mvnw.cmd -Dcampus.mysql.tests=true -Dtest=MySqlOrderConcurrencyIntegrationTests test`：3 个 MySQL 8.4 测试通过，0 失败、0 错误、0 跳过；Flyway 从空库成功执行 2 个迁移。

MySQL 专项测试结束时，Surefire 在等待 fork JVM 退出 30 秒后执行了强制清理并打印错误级提示，但测试和 Maven 构建结果均为成功。这是尚待排查的测试进程退出延迟，不能把该日志描述为完全无警告。

## 7. 主要修改文件

- `src/main/resources/static/index.html`
- `src/main/resources/static/app.js`
- `src/main/resources/static/js/core.js`
- `src/main/resources/static/js/api.js`
- `src/main/resources/static/js/auth.js`
- `src/main/resources/static/js/orders.js`
- `src/main/resources/static/js/profile.js`
- `src/main/java/com/campus/campus_trade_backend/auth/security/SecurityConfig.java`
- `src/main/java/com/campus/campus_trade_backend/config/OpenApiConfig.java`
- `src/test/java/com/campus/campus_trade_backend/V4MultiUserSecurityIntegrationTests.java`
- `src/test/java/com/campus/campus_trade_backend/V4BrowserFlowTests.java`
- `pom.xml`

## 8. 当前限制与下一步

- 个人资料修改、收藏、举报审核、密码找回尚未实现。
- 没有站内聊天和联系方式隐私治理；真实上线前需要补充。
- 图片仍为单机文件存储；多实例部署前应迁移 MinIO 或云对象存储。
- 浏览器自动化当前复用 Windows Edge；CI 需要安装 Playwright Chromium，或提供跨平台浏览器执行路径。
- 浏览器测试使用 H2 和本地验证码发送器；真实 MySQL 并发、Redis 验证码和 SMTP 分别由集成环境验证，不能把 H2 浏览器通过等同于所有生产基础设施均已验收。
- 前端尚无历史路由，刷新可以恢复身份和首页数据，但不能恢复到刷新前的详情/个人中心子页。
- 仍需补充部署环境的日志采集、健康检查、备份恢复和真实压测数据，才能形成更完整的简历工程化证据。

## 9. Git 交付状态

V4 在 `feature/v4-multi-user-business-flow` 分支开发，不自动合并到 `main`。仓库开始 V4 前已有一处未提交的 `README.md` 修改，本版未覆盖也未纳入 V4 提交。
