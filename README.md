# IndusFlow

IndusFlow 是基于 Spring Boot 的工业设备售后服务履约管理平台，覆盖维修申请、预付款、服务受理和维修完成。核心能力是 SLA 受理超时监控与自动状态流转，模拟支付用于启动受理时限。本项目不是完整 MES 系统。现有 Java 包名、数据表和 API 路径继续沿用。

## 环境

- JDK 17、Maven 3.9.x、MySQL 8.0.16+
- Spring Boot 3.5.16、MyBatis-Plus 3.5.18、PageHelper Starter 2.1.1

在 IDEA 中以 Maven 项目打开 `pom.xml`。在 **Project Structure → Project** 选择 JDK 17，并在 **Settings → Build Tools → Maven → Runner** 将 JRE 设为 JDK 17。若 PowerShell 找不到 `mvn`，可在 IDEA 中使用内置 Maven，也可在 PowerShell 中调用本机 `mvn.cmd` 的绝对路径。

## 本地开发配置

公共配置在 `src/main/resources/application.yml`。本机连接配置使用项目根目录下的 `config/application-dev.yml`，这个文件已被 Git 忽略。首次检出项目时，可以复制模板。

```powershell
if (-not (Test-Path .\config\application-dev.yml)) {
    Copy-Item .\config\application-dev.example.yml .\config\application-dev.yml
}
```

在本机文件中填写已有 MySQL 账号和密码。运行维修业务和集成测试时，JDBC URL 指向已初始化并迁移的 `order_flow` 数据库。启动和测试的工作目录均设为项目根目录，Spring Boot 会从该目录下的 `config/` 读取对应 Profile 的配置。

## 数据库迁移

已运行旧版的环境只需在 IDEA 的 Database 面板选择业务 schema，确认当前库和表定义，再审核并手动执行一次 [003_add_fulfillment_sla.sql](sql/003_add_fulfillment_sla.sql)。从空 schema 初始化时，按 `sql/` 中 `001`、`002`、`003` 的顺序执行。迁移保留历史申请及其支付信息，历史记录的新增列保持 `NULL`。旧版已支付申请可以开始处理，但在受理截止时间为空时不会被扫描任务判定为超时。详情见 [数据库设计](docs/database-design.md)。

## 编译和启动

```powershell
mvn -version
mvn compile
# 设置签名密钥后启动，见下方登录和角色验证。
mvn '-Dspring-boot.run.profiles=dev' spring-boot:run
```

`mvn -version` 应显示 Java 17。在 IDEA 中编辑 `OrderFlowApplication` 的运行配置，将 **Active profiles** 设为 `dev`。如果没有该选项，在 **Program arguments** 填入 `--spring.profiles.active=dev`。日志应显示 `dev` 已激活和 `Started OrderFlowApplication`。

## 验证 MySQL 连接

在项目根目录执行以下命令。

```powershell
mvn '-Dspring.profiles.active=dev' '-Dtest=MySqlConnectionTest' test
```

在 IDEA 中也可运行 `MySqlConnectionTest`，并在该 JUnit 运行配置的 **VM options** 填入 `-Dspring.profiles.active=dev`。测试通过 Spring 配置的数据源执行 `SELECT 1`，预期返回 `1`。只验证 Spring 容器启动时，可执行 `mvn '-Dspring.profiles.active=dev' '-Dtest=OrderFlowApplicationTests' test`。

## 登录和角色验证

配置 `dev` 数据源并确认本机库已建好 `accounts` 后，在项目根目录运行以下命令。

```powershell
mvn '-Dspring.profiles.active=dev' '-Dtest=AuthIntegrationTests' test
```

测试会向真实 MySQL 写入临时客户与管理员账号，通过 MockMvc 发起登录和身份查询。测试结束后会回滚账号记录。这里的测试密钥仅供测试上下文使用。

本机启动继续使用 `config/application-dev.yml` 中的 `orderflow.auth.jwt-secret`。首次配置时保存至少 32 个随机字节经 Base64 编码得到的密钥。更换密钥会使旧令牌失效。签名密钥与账号密码均留在本机，长期使用的账号沿用 `accounts` 中已配置的记录。

- `POST /api/auth/login` 接收 JSON 中的 `username` 和 `password`，返回有效期为 30 分钟的 `accessToken`。
- `GET /api/auth/me` 接收 `Authorization: Bearer <accessToken>`，返回当前账号的 `accountId` 和 `role`。

## 维修服务申请接口

以下接口使用 `Authorization: Bearer <accessToken>`。

| 接口 | 角色 | 用途 |
| --- | --- | --- |
| `POST /api/orders` | `USER` | 提交待支付的维修申请 |
| `GET /api/orders/{orderNo}` | `USER` | 查询自己的维修申请详情 |
| `GET /api/orders` | `USER` | 分页查询自己的维修申请，可按状态筛选 |
| `POST /api/orders/{orderNo}/pay` | `USER` | 模拟支付自己的维修预付款，启动 SLA |
| `POST /api/orders/{orderNo}/cancel` | `USER` | 取消自己的未支付申请 |
| `POST /api/admin/orders/{orderNo}/start` | `ADMIN` | 开始处理已支付或已超时的申请，发送空请求体，成功返回 `204 No Content` |
| `POST /api/admin/orders/{orderNo}/complete` | `ADMIN` | 完成正在处理的申请，发送空请求体，成功返回 `204 No Content` |

新申请需要提交 `equipmentCode`、`faultDescription` 和 `payAmount`。设备编号与故障描述必须为非空文本，分别最多 64 和 500 个字符。申请编号继续用 `orderNo` 表示。一个创建请求示例为 `{"equipmentCode":"PUMP-034","faultDescription":"启动后无法升压","payAmount":200.00}`。

## SLA 受理超时

支付成功时，受理截止时间按 `paid_at + indusflow.fulfillment.acceptance-sla` 计算。默认时限为 `PT2H`，支付和截止时间在同一事务内写入。受理以管理员记录的 `started_at` 为终点，时间统一按 `Asia/Shanghai` 处理。

首次扫描和相邻两批之间默认等待 `60000` 毫秒，每批最多处理 100 条截止时间已到的 `PAID` 申请。`indusflow.fulfillment.overdue-scan-interval-ms` 调整间隔，`indusflow.fulfillment.overdue-scan-batch-size` 调整每批上限。`indusflow.fulfillment.scan-enabled` 默认开启，可在测试时关闭定时触发并直接调用扫描入口。扫描按申请分别提交，单条失败可以独立回滚。`overdue_at` 记录首次成功流转为 `OVERDUE` 的业务时间，并在之后开始或完成时保留。

状态流转为 `PENDING_PAYMENT → PAID → IN_PROGRESS → COMPLETED`、`PENDING_PAYMENT → CANCELLED` 或 `PAID → OVERDUE → IN_PROGRESS → COMPLETED`。如果管理员在扫描前受理一条已到截止时间的申请，系统在同一事务先记录超时，再记录开始处理，以保留完整的时间和操作历史。

条件更新检查订单 ID、原状态和原版本，超时更新还检查截止时间。更新成功才写状态日志，日志失败会回滚该次状态变更。`version` 乐观锁保护单条业务记录。未来的分布式锁可协调多个实例的调度执行，业务更新仍由 `version` 检查保护。

## 维修业务集成测试

将 `dev` 数据源指向本机已完成 `003` 迁移的 `order_flow` 数据库。从项目根目录使用 JDK 17 运行测试。

```powershell
mvn '-Dspring.profiles.active=dev' test
```

最近一次全量测试使用隔离的 MySQL 8.0.46，87 项全部通过。SLA 场景使用受控的时间，直接验证扫描、并发和回滚结果。本机开发业务库的迁移留待审核后执行。详见 [项目进度](docs/PROJECT_STATUS.md)。

## 后续阶段

后续可在开始处理、超时、完成的事务内写入 `FULFILLMENT_STARTED`、`FULFILLMENT_OVERDUE`、`FULFILLMENT_COMPLETED` Outbox 事件，再由中继任务向 Kafka 发送。消费端以事件 ID 做幂等处理，并逐步构建 Redis 履约看板读模型。当前阶段的状态和日志仍以 MySQL 为准。

## 切换数据库环境

未来新增环境时，在运行目录的 `config/` 放入对应的 `application-test.yml`、`application-vm.yml` 或 `application-prod.yml`，启动时选择对应 Profile，例如将 `SPRING_PROFILES_ACTIVE` 设为 `vm`，或用 `--spring.profiles.active=vm`。`SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD` 可覆盖对应 YAML 中的值。
