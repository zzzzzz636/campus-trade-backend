# V6 查询与性能记录

## 当前测量状态

截至 2026-11-02，本机 Docker Desktop 引擎不可连接，且当前进程未获得 MySQL 凭据。**V6 尚未执行 MySQL `EXPLAIN ANALYZE`，没有可报告的实际查询耗时或优化比例。** 不能把 H2 查询时间、历史 V5 测试或预期执行计划写成 MySQL 性能结论。

可在有代表性数据的 MySQL 8.4 库执行 [`sql/v6_explain.sql`](sql/v6_explain.sql)，保存三张表的行数、完整执行计划、MySQL 版本、硬件和冷热缓存条件。必须先备份，再使用测试库，且替换脚本中的 `@buyer_id`、`@conversation_id` 为数据量有代表性的 ID。

## 现有 SQL 与索引假设

| 场景 | 源码 | 现有索引 | 待验证点 |
|---|---|---|---|
| 在售商品按新发布排序 | `ProductMapper.listOnSale` | `(status, created_at, id)` | 过滤后能否顺序读；价格排序可能需要排序阶段。 |
| 商品关键词 | `ProductMapper.listOnSale` | 同上 | `LOWER(...) LIKE '%词%'` 无法有效使用普通 B-tree 前缀索引；先测数据规模与延迟，再考虑全文索引。 |
| 买入/卖出订单页 | `OrderMapper.listForBuyer/listForSeller` | 订单用户与创建时间索引，见 V3 迁移 | 状态过滤是否走复合索引；深页 offset 成本。 |
| 私信历史 | `MessageMapper.listPage` | `(conversation_id, id)` | `beforeId` 倒序分页是否为范围扫描。 |

订单页上限 1000、每页最多 50 条，属于目前场景的合理 offset 限制。未取得测量结果前，不增加新索引、不声称某个索引有性能收益。记录模板：数据行数 ___；查询参数 ___；优化前计划/耗时 ___；变更 SQL ___；优化后计划/耗时 ___；重复次数和中位数 ___。
