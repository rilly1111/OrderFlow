# OrderFlow

基于 Spring Boot 的订单管理与运营统计项目。当前处于第一阶段，已搭建应用骨架和 MySQL 连接测试。

## 环境

- JDK 17、Maven 3.9.x、MySQL 8.0
- Spring Boot 3.5.16、MyBatis-Plus 3.5.18

在 IDEA 中以 Maven 项目打开 `pom.xml`。在 **Project Structure → Project** 选择 JDK 17，并在 **Settings → Build Tools → Maven → Runner** 将 JRE 设为 JDK 17。若 PowerShell 找不到 `mvn`，可在 IDEA 中使用内置 Maven，也可在 PowerShell 中调用本机 `mvn.cmd` 的绝对路径。

## 本地开发配置

公共配置在 `src/main/resources/application.yml`。本机连接配置使用项目根目录下的 `config/application-dev.yml`，这个文件已被 Git 忽略。首次检出项目时，可以复制模板。

```powershell
if (-not (Test-Path .\config\application-dev.yml)) {
    Copy-Item .\config\application-dev.example.yml .\config\application-dev.yml
}
```

在本机文件中填写已有 MySQL 账号和密码。阶段一使用 `jdbc:mysql://127.0.0.1:3306/` 即可执行 `SELECT 1`，无需创建数据库。启动和测试的工作目录均设为项目根目录，Spring Boot 会从该目录下的 `config/` 读取对应 Profile 的配置。

## 编译和启动

```powershell
mvn -version
mvn compile
mvn '-Dspring-boot.run.profiles=dev' spring-boot:run
```

`mvn -version` 应显示 Java 17。在 IDEA 中编辑 `OrderFlowApplication` 的运行配置，将 **Active profiles** 设为 `dev`。如果没有该选项，在 **Program arguments** 填入 `--spring.profiles.active=dev`。日志应显示 `dev` 已激活和 `Started OrderFlowApplication`。

## 验证 MySQL 连接

在项目根目录执行以下命令。

```powershell
mvn '-Dspring.profiles.active=dev' '-Dtest=MySqlConnectionTest' test
```

在 IDEA 中也可运行 `MySqlConnectionTest`，并在该 JUnit 运行配置的 **VM options** 填入 `-Dspring.profiles.active=dev`。测试通过 Spring 配置的数据源执行 `SELECT 1`，预期返回 `1`。只验证 Spring 容器启动时，可执行 `mvn '-Dspring.profiles.active=dev' '-Dtest=OrderFlowApplicationTests' test`。

## 切换数据库环境

未来新增环境时，在运行目录的 `config/` 放入对应的 `application-test.yml`、`application-vm.yml` 或 `application-prod.yml`，启动时选择对应 Profile，例如将 `SPRING_PROFILES_ACTIVE` 设为 `vm`，或用 `--spring.profiles.active=vm`。`SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD` 可覆盖对应 YAML 中的值。
