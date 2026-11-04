# 商品广场参考图重构

## 打开页面

真实业务入口：启动 CampusTrade 后登录，访问 `/`。列表、搜索、分类、价格、排序、收藏及详情继续调用原有后端接口。

本地布局预览：在本机打开 `http://localhost:8080/?uiPreview=1`。只在 `localhost` 或 `127.0.0.1` 生效；12 件校园物品由 `js/market-preview-data.js` 提供，摄影素材位于 `js/assets/campus-preview-sheet.png`，不会写入数据库，也不支持收藏和购买。页面顶部明确标注“本地设计预览”。如需从其他设备访问，请使用真实账号和业务数据。

## 结构变化

| 项目 | 上版 | 本版 |
| --- | --- | --- |
| 首屏 | 大幅宣传区、搜索和横向筛选占主要空间 | 紧凑导航与搜索下方直接出现商品 |
| 桌面筛选 | 横排字段与分类按钮 | 210px 左侧栏，分类、价格和重置分组 |
| 商品列表 | 较宽的三列卡片 | 右侧四列等比图片网格，图片、标题、价格依次突出 |
| 手机 | 单列或较宽商品卡片 | 双列商品，筛选从右侧抽屉打开 |
| 无图片 | 大块旧式卡片占位 | 统一方图占位；真实图片使用 `object-fit: cover` |

## 浏览器截图

- 上版桌面：[旧商品广场](ui-screenshots/final/plaza-1280.png)
- 本版桌面：[12 件商品本地设计预览](ui-screenshots/market-reference-final/preview-1280.png)
- 本版手机：[12 件商品本地设计预览](ui-screenshots/market-reference-final/preview-390.png)
- 真实 API 数据：[桌面](ui-screenshots/market-real/plaza-1280.png)、[手机](ui-screenshots/market-real/plaza-390.png)；来自浏览器测试的临时 H2 数据，未写入正式 MySQL。

另外保存了 768px 和 1440px 截图，位于同一目录。

## 实现与验证

- `static/index.html`：广场采用介绍行、搜索、筛选侧栏、工具栏和商品网格；其他业务页面结构保留。
- `static/js/market-card.js`：新商品卡片，真实图片失败时显示统一占位；收藏按钮沿用原模块。
- `static/app.js`：原真实商品 API 保留；筛选抽屉和 localhost 专用预览入口分开处理。
- `static/styles.css`：广场样式限制在 `#home-view` 和 `.market-home-active` 范围；窄屏两列和抽屉。修复了从桌面宽度切换到手机宽度时抽屉短暂露出的过渡问题。
- `V4BrowserFlowTests`：在 Edge 中检查 390/768/1280/1440px 布局、无横向溢出、手机两列、价格输入错误、本地预览不访问商品 API；原双账号交易流程回归通过。

验证命令：`node --check src/main/resources/static/app.js`；`mvnw.cmd -q -Dtest=V4BrowserFlowTests test`。后一命令在本机 Java 17、Edge、Spring Boot 测试服务器与 H2 下执行，3 个测试通过。另运行了双账号测试并采集真实 API 页面截图，1 个测试通过。

## 设计依据与后续差距

- `ui-ux-pro-max`：优先处理信息层级、商品图片比例和移动触控尺寸。
- `ui-design`：改变页面骨架，移除占据首屏的宣传横幅与堆叠卡片。
- `design-system`：复用现有文字、色彩和间距基础，广场用统一方图、边线和按钮状态。
- `ui-styling`：采用暖白底色、克制分隔与可见的键盘焦点，避免大面积渐变和 Emoji 商品图。

与参考图差距最大的是正式业务商品的照片覆盖率：测试账号的商品没有上传照片，所以真实 API 截图仍显示统一占位；本地设计预览使用的是同一摄影风格的独立素材。真实商家上传的不同场景照片会更有二手平台的生活感。后端目前没有商品成色筛选参数，本轮只展示成色，不提供无法生效的筛选项。