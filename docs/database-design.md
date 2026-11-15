# IndusFlow 数据库设计

工业设备维修服务申请沿用 `orders`，履约历史沿用 `order_status_log`。基础结构见 [001_create_order_tables.sql](../sql/001_create_order_tables.sql)，履约字段、状态约束和扫描索引见 [003_add_fulfillment_sla.sql](../sql/003_add_fulfillment_sla.sql)。

## orders 订单主表

| 字段 | 类型 | 可空 | 用途 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增主键，用于表内标识及日志关联 |
| `order_no` | `VARCHAR(32)` | 否 | 唯一业务订单编号 |
| `user_id` | `BIGINT` | 否 | 用户编号 |
| `pay_amount` | `DECIMAL(12,2)` | 否 | 最终应付金额，创建后不可修改 |
| `status` | `VARCHAR(24)` | 否 | 当前订单状态 |
| `payment_method` | `VARCHAR(24)` | 是 | 支付方式 |
| `payment_transaction_no` | `VARCHAR(64)` | 是 | 唯一模拟支付交易号 |
| `paid_at` | `DATETIME(3)` | 是 | 预付款成功时间，作为受理 SLA 起点 |
| `version` | `INT` | 否 | 乐观锁版本号 |
| `created_at` | `DATETIME(3)` | 否 | 创建时间 |
| `updated_at` | `DATETIME(3)` | 否 | 更新时间 |
| `equipment_code` | `VARCHAR(64)` | 是 | 设备编号，新申请接口要求提供 |
| `fault_description` | `VARCHAR(500)` | 是 | 故障描述，新申请接口要求提供 |
| `acceptance_deadline_at` | `DATETIME(3)` | 是 | 支付时确定的受理截止时间 |
| `started_at` | `DATETIME(3)` | 是 | 管理员实际开始处理的时间，作为 SLA 终点 |
| `overdue_at` | `DATETIME(3)` | 是 | 系统首次成功将申请流转为 `OVERDUE` 的业务时间，后续流转保留 |

| 索引 | 列 | 用途 |
| --- | --- | --- |
| 主键 | `(id)` | 按内部主键定位订单 |
| 唯一索引 | `(order_no)` | 防止业务订单编号重复，支持按编号查询 |
| 唯一索引 | `(payment_transaction_no)` | 防止已填写的模拟支付交易号重复 |
| 普通索引 | `(user_id, created_at)` | 按用户查询，并支持以创建时间为条件 |
| 普通索引 | `(created_at, status)` | 支持按创建时间范围查询，便于后续按状态统计 |
| 普通索引 | `(status, acceptance_deadline_at)` | 按待受理状态和截止时间范围查找候选申请 |

MySQL 的唯一索引允许多个 `NULL`。尚无交易号时保存 `NULL`，已填写的交易号受到唯一性约束。[MySQL CREATE TABLE](https://dev.mysql.com/doc/refman/8.0/en/create-table.html)

## order_status_log 订单状态日志表

| 字段 | 类型 | 可空 | 用途 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 否 | 自增主键 |
| `order_id` | `BIGINT` | 否 | 逻辑关联 `orders.id` |
| `from_status` | `VARCHAR(24)` | 是 | 变更前状态 |
| `to_status` | `VARCHAR(24)` | 否 | 变更后状态 |
| `operator_type` | `VARCHAR(16)` | 否 | `USER`、`ADMIN` 或 `SYSTEM` |
| `operator_id` | `BIGINT` | 仅 `SYSTEM` 可为空 | 操作人 ID |
| `remark` | `VARCHAR(255)` | 否 | 变更原因 |
| `created_at` | `DATETIME(3)` | 否 | 状态变更时间 |

主键为 `(id)`。普通索引 `(order_id, created_at)` 支持查询同一订单的状态历史。日志只记录状态变化，金额和支付信息保存在订单主表。第一版不设 `payment_record` 表。

## 数据库约束与业务边界

- `CHECK` 限制金额为非负值。创建接口要求金额大于零且最多两位小数。
- 主表状态和日志的非空状态值必须精确匹配 `PENDING_PAYMENT`、`PAID`、`IN_PROGRESS`、`OVERDUE`、`COMPLETED`、`CANCELLED`。日志的操作人类型也按字面匹配。使用二进制比较保持大小写和尾空格的区分。
- 状态取值约束只检查写入值。Service 负责校验状态能否流转，维护订单状态与日志的事务一致性，限制创建后的金额修改。业务接口不提供订单物理删除。关联约定见 [ADR-002](decisions/0002-no-physical-foreign-keys.md)。
- `CHECK` 从 MySQL 8.0.16 起强制执行。[MySQL CHECK 约束](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)
- `DATETIME(3)` 存储毫秒精度的本地日期时间。应用按 `Asia/Shanghai` 生成和解释时间值及日期边界。DDL 不设置数据库会话时区，也不自动生成时间值。[MySQL DATETIME 类型](https://dev.mysql.com/doc/refman/8.0/en/datetime.html)

## 受理 SLA 与状态机

| 原状态 | 新状态 | 执行方 |
| --- | --- | --- |
| `PENDING_PAYMENT` | `PAID` | 客户支付预付款，同时以 `paid_at` 加配置时限计算 `acceptance_deadline_at` |
| `PENDING_PAYMENT` | `CANCELLED` | 客户取消未支付申请 |
| `PAID` | `IN_PROGRESS` | 管理员在截止前开始处理，或受理无截止时间的历史申请 |
| `PAID` | `OVERDUE` | 系统记录已经到达截止时间、仍未受理的申请 |
| `OVERDUE` | `IN_PROGRESS` | 管理员开始处理，保留首次 `overdue_at` |
| `IN_PROGRESS` | `COMPLETED` | 管理员确认维修完成 |

扫描只选择 `PAID`、受理截止时间非空且不晚于扫描时间的记录。写入时再次核对订单 ID、原状态、原版本和截止时间，成功时自增 `version`。每笔更新前单独取得流转时间，用于首次 `overdue_at`、本次 `updated_at` 和系统日志的 `created_at`。SLA 截止时间单独保存在 `acceptance_deadline_at`。超时日志使用 `SYSTEM` 且 `operator_id` 为 `NULL`。管理员在扫描前受理过期申请时，超时和开始处理两次流转共用本次操作时间，在同一事务内记录。

每次业务流转和对应日志共享一个事务，后续可在该事务中插入 Outbox 事件。MySQL 的 `version` 负责保护业务记录的并发写入，可选的分布式锁负责协调多实例调度执行。

## 增量迁移

`003` 迁移在现有 schema 上添加可空字段、替换主表和日志的状态值约束，并新增扫描索引。历史数据保留，新列初始为 `NULL`，历史 `PAID` 申请可开始处理。迁移保留主键、既有唯一索引和普通索引，并沿用逻辑关联。已初始化的库只需在审核后手动执行 `003`，执行前可用 `SELECT DATABASE()` 和 `SHOW CREATE TABLE` 核对目标和旧约束名称。脚本包含两条独立 DDL，任意一条失败时先核对数据库实况再处理。[MySQL ALTER TABLE](https://dev.mysql.com/doc/refman/8.0/en/alter-table.html)

## accounts 登录账号表

建表脚本见 [002_create_accounts.sql](../sql/002_create_accounts.sql)。

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `id` | `BIGINT` | 自增主键 |
| `username` | `VARCHAR(64)` | 唯一登录名，比较时忽略大小写、区分重音 |
| `password_hash` | `VARCHAR(255)` | BCrypt 密码哈希 |
| `role` | `VARCHAR(16)` | 单一角色，`CHECK` 允许 `USER` 或 `ADMIN` |
| `created_at` | `DATETIME(3)` | 按 `Asia/Shanghai` 写入的账号创建时间 |

五个字段必填。第一版账号预置，账号禁用留到后续。账号 ID 与 `orders.user_id` 及用户、管理员的 `operator_id` 通过逻辑关联对应。客户下单时，订单归属和初始日志操作人都取已登录的账号 ID。管理员操作已有订单时，日志记管理员 ID，订单仍归原客户。

## 验收

基础订单表设计已由用户验收。账号表定义已在本机 MySQL 核对，登录测试覆盖客户和管理员账号。履约迁移的验证和验收进度见 [项目进度](PROJECT_STATUS.md)。
