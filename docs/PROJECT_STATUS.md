# OrderFlow 项目进度

## 当前阶段

阶段一，项目初始化。工程改造已完成，等待真实 MySQL 连接验收。基础技术栈与分工见 [技术栈决策](decisions/0001-initial-technology-stack.md)。

### 验收项

- [x] Maven 3.9.16 实际运行于 JDK 17.0.12。
- [x] Maven 下载并解析依赖，Java 17 编译与打包通过。依赖树确认 MySQL Connector/J 9.7.0。
- [x] Spring 容器启动测试通过，可执行 JAR 在本机启动并响应 HTTP 请求。
- [x] Git 仓库已初始化，忽略规则已验证，提交数为 0。
- [x] 激活 `dev` 后，Spring 加载 `config/application-dev.yml`，容器启动测试与 Maven 打包通过。本机配置文件未进入 JAR。
- [ ] 激活 `dev` Profile，使用本机 MySQL 凭据运行 `MySqlConnectionTest`，`SELECT 1` 返回 `1`。

本机开发配置的账号和密码尚未填写。按 [运行说明](../README.md) 配置后运行连接测试，验证通过后再确认进入阶段二。订单表、订单状态日志表和业务规则将在阶段二共同设计。
