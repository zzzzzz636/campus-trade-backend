# CampusTrade V3 第三阶段记录：可降级的 AI 辅助发布

完成日期：2026-10-30

## 1. 本阶段完成

- 新增独立 `ai` 业务模块，不让模型调用侵入商品创建事务。
- 登录用户可提交原始标题、原始描述和成色，获得标题、规范描述和现有分类建议。
- 前端发布表单增加“AI 帮我整理”，结果只回填可编辑字段，用户确认后仍调用原商品发布接口。
- 默认关闭 AI；未配置、超时、供应商异常或输出错误时返回清晰错误，手动发布保持可用。
- 使用 OpenAI Responses API 的 JSON Schema Structured Outputs，并在服务端对标题、描述和分类 ID 再次校验。
- 单次请求设置连接/响应超时；网络错误、HTTP 429 或供应商 5xx 最多重试一次。
- 基于登录用户 ID 做单实例每分钟限流，默认每分钟 5 次。
- 请求不记录 API Key 和完整商品原文，并显式设置 `store=false`。

本阶段没有数据库结构变化，不需要新增 Flyway 迁移。

## 2. 接口契约

```http
POST /api/ai/product-draft
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "rawTitle": "高数书",
  "rawDescription": "用过一个学期，有少量笔记，东门面交",
  "conditionLevel": "GOOD"
}
```

成功响应示例：

```json
{
  "title": "高等数学教材，有少量笔记",
  "description": "正常使用一个学期，书内有少量笔记，支持校内东门线下面交。",
  "categoryId": 1,
  "categoryName": "教材与书籍"
}
```

约束：`rawTitle` 最长 120 字，可为空；`rawDescription` 必填且最长 2000 字；`conditionLevel` 只接受 `NEW`、`LIKE_NEW`、`GOOD`、`FAIR`。AI 不生成价格，最终发布请求仍由用户确认。

主要错误：未登录 `401`、参数错误 `400`、用户调用过快 `429`、输出无法解析或供应商拒绝请求 `502`、未配置/供应商不可用 `503`、响应超时 `504`。所有失败都不会创建或修改商品。

## 3. 关键技术设计

调用链为 `AiProductDraftController -> ProductDraftService -> ProductDraftAiClient`。接口隔离供应商实现，当前 `OpenAiProductDraftClient` 使用 Java 17 自带 `HttpClient`；将来更换模型服务时无需修改商品模块。

模型只能从数据库实时读取的分类列表中选择 ID。请求通过 JSON Schema 把 `categoryId` 限制为允许值，响应回来后仍根据同一份分类快照检查 ID，并检查标题/描述是否为空及是否超长。结构化输出减少格式漂移，但不能替代业务校验。

AI 草稿和正式发布是两个接口。AI 接口不写数据库；用户确认后才调用已有 `POST /api/products`。因此 AI 失败、重复请求或超时不会留下半成品商品，也不会进入商品/订单事务。

当前限流使用进程内 `ConcurrentHashMap`，适合本阶段单实例开发环境。多实例部署时各实例计数不共享，应改为 Redis 原子计数和 TTL。重试仅对连接错误、429 和 5xx 执行一次；认证、参数等确定性错误不重试，避免放大故障与费用。

## 4. 安全与配置

默认配置：

```properties
campus.ai.enabled=${CAMPUS_AI_ENABLED:false}
campus.ai.base-url=${CAMPUS_AI_BASE_URL:https://api.openai.com/v1}
campus.ai.api-key=${CAMPUS_AI_API_KEY:}
campus.ai.model=${CAMPUS_AI_MODEL:gpt-4o-mini}
campus.ai.request-timeout=${CAMPUS_AI_REQUEST_TIMEOUT:12s}
campus.ai.rate-limit-per-minute=${CAMPUS_AI_RATE_LIMIT_PER_MINUTE:5}
```

API Key 只从运行环境读取，不能放入前端或仓库。日志只记录供应商 HTTP 状态，不记录 Authorization、完整请求体或完整响应体。原始商品信息仍会发送给配置的模型供应商，因此正式上线前需要在隐私说明中告知用户，并根据所用供应商的数据策略复核配置。

## 5. 自动化验证

- `OpenAiProductDraftClientTests` 使用本机临时 HTTP 服务验证严格结构化请求、正确解析、非法分类拒绝、一次瞬时重试和超时，不调用真实外部 API。
- `AiProductDraftIntegrationTests` 验证未登录拒绝、AI 默认关闭时返回清晰降级消息，以及降级后原商品接口仍能手动发布。
- `AiRequestRateLimiterTests` 验证同一用户的分钟额度和不同用户之间的隔离。
- `node --check src/main/resources/static/app.js` 验证前端脚本语法。

实际结果：

- AI 定向测试：7 项执行并通过，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。
- 完整普通回归：共发现 33 项，30 项执行并通过，3 项 MySQL 专项按开关跳过；0 失败、0 错误。Docker Redis + Mailpit 联调本次实际执行并通过。
- Testcontainers MySQL 8.4 专项：3 项执行并通过，0 失败、0 错误、0 跳过；Flyway 从空库迁移到 V2，订单并发测试保持通过。
- JavaScript：`node --check` 通过。

真实外部模型调用尚未验收，因为本机没有配置用户自己的 API Key；不能把本地桩服务测试表述为真实 OpenAI 调用成功。配置 Key 后应在页面人工验证一条不含敏感信息的商品草稿，并检查供应商用量记录。

## 6. 已知限制与下一步

- 进程内限流不适用于多实例，需要 Redis 化后再做分布式部署。
- 当前是同步 HTTP 调用，线程最多等待配置的 12 秒；若未来高并发使用，可评估异步任务、隔离线程池或响应式客户端，但本阶段没有为简历堆砌复杂度。
- AI 只处理文本，不读取商品图片，不做估价、审核或语义搜索。
- 尚未建立离线提示词评测集。下一步可收集脱敏样例，对“信息不编造、分类正确、描述完整”建立可重复的质量评测，而不是只凭主观体验调整提示词。
