# 0001 阶段一技术栈与开发分工

状态：已确认。

## 技术栈

| 组件 | 版本或选择 |
| --- | --- |
| JDK | 17 |
| Maven | 3.9.x |
| Spring Boot | 3.5.16 |
| MyBatis-Plus | 3.5.18，使用 `com.baomidou:mybatis-plus-spring-boot3-starter` |
| MySQL | 8.0，使用 Spring Boot 管理的 `com.mysql:mysql-connector-j` |

工程沿用现有 Spring Boot 项目。MyBatis-Plus 使用 Spring Boot 3 专用 starter，避免同时引入原生 `mybatis-spring-boot-starter` 造成依赖冲突。阶段一通过真实 MySQL 执行 `SELECT 1` 验证连接。本地连接配置使用 `dev` Profile 的外部 YAML，可通过 Spring Boot 的标准环境变量覆盖。

## 开发分工

AI 负责 Maven 配置、数据源配置、连接测试、运行说明和工程文档。用户负责本机数据库连接信息，在 IDEA 中复现验证，并参与后续表结构、业务规则、统计口径、一致性方案和关键测试场景的设计与确认。
