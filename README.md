# SubStacker

机场订阅聚合器：将多个上游订阅（不同格式）聚合为一个 OpenClash 可直接订阅的 Clash YAML。

## 功能

- Web 页面配置上游（增删改）
- 聚合并去重节点，生成 OpenClash 可用的 `openclash.yaml`
- 支持通过下拉框切换订阅生成配置档，并写入订阅 URL 的 `config` 参数
- 支持保存远程 `.ini` 模板 URL，按 `subconverter` 的 `config` 思路生成订阅
- 支持按页面开关将“地区类 proxy group”从 `select` 自动改成 `url-test`
- 上游格式（当前版本）
  - `CLASH_YAML_URL`：上游为 Clash YAML（读取 `proxies:`）
  - `URI_LIST_URL`：上游为 URI 列表或 base64(URI 列表)，支持：
    - `ss://`（兼容传统整段 base64 和 SIP002 `userinfo@host:port` 写法，含常见 plugin 参数）
    - `ssr://`
    - `vmess://`（兼容标准 JSON 链接和 URI 风格链接）
    - `trojan://`
    - `vless://`
    - `hy2://` / `hysteria2://`
    - `tuic://`

## 私有仓库拉取与启动

如果这是 GitHub 私有仓库，推荐使用下面两种方式之一拉取代码。

### 方式一：SSH 拉取

前提：

- 你的 GitHub 账号已经配置好 SSH Key
- 当前机器能直接访问该私有仓库

拉取：

```bash
git clone git@github.com:qingmf/SubStacker.git
cd SubStacker
```

更新：

```bash
git pull origin main
```

### 方式二：HTTPS + Personal Access Token

前提：

- 你的 GitHub 账号已创建可访问该私有仓库的 PAT
- Token 至少具备仓库读取权限

拉取：

```bash
git clone https://github.com/qingmf/SubStacker.git
cd SubStacker
```

认证说明：

- `Username` 填你的 GitHub 用户名
- `Password` 不要填 GitHub 登录密码，要填你的 PAT

更新：

```bash
git pull origin main
```

## 运行（开发模式）

需要：JDK 21、Maven 3.9+

启动步骤：

1. 进入项目目录。
2. 确认本机安装 `JDK 21` 和 `Maven 3.9+`。
3. 执行开发模式启动命令。
4. 打开浏览器访问页面。

```bash
mvn quarkus:dev
```

打开：

- 配置页面：`http://localhost:8080/`
- OpenClash 订阅地址：`http://localhost:8080/sub/openclash.yaml?config=default`

配置保存位置（默认）：`./data/state.json`

可通过 `src/main/resources/application.properties` 中的 `substacker.data-dir` 修改。

常见开发命令：

```bash
# 启动开发模式
mvn quarkus:dev

# 打包 JVM 版本
mvn package -DskipTests

# 打包 Native 版本
mvn package -DskipTests -Dnative
```

## Docker Native 构建

仓库根目录已提供用于本地构建 Quarkus Native 的 `Dockerfile`。

构建：

```bash
docker build -t substacker-native .
```

运行：

```bash
docker run --rm -p 8080:8080 -v "$(pwd)/data:/work/data" substacker-native
```

说明：

- 构建阶段使用 `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21`
- 运行阶段使用 `ubi-micro`
- Dockerfile 默认执行 `mvn package -DskipTests -Dnative`

## Docker Compose

仓库根目录已提供 `docker-compose.yaml`，可直接使用：

启动步骤：

1. 确保 Docker Desktop 或 Docker daemon 已启动。
2. 确保外部网络 `macvlan_net` 已存在。
3. 在项目根目录执行启动命令。
4. 等待镜像构建和容器启动完成后访问页面。

```bash
docker compose up -d
```

停止：

```bash
docker compose down
```

说明：

- Compose 会自动基于当前目录 `Dockerfile` 构建镜像
- 默认映射端口 `8080:8080`
- 仓库内 `data/` 会被打进镜像，作为容器默认数据
- Compose 默认使用命名卷 `substacker-data` 挂载到 `/work/data`
- 默认接入外部 Docker 网络 `macvlan_net`
- 如需覆盖网络名，可在执行前设置环境变量 `SUBSTACKER_DOCKER_NETWORK`
- 使用前需确保对应外部网络已存在，否则 `docker compose up -d` 会直接失败
- 如果之前已经创建过旧的 `substacker-data` 卷，想重新灌入镜像内默认数据，可执行 `docker compose down -v` 后再 `docker compose up -d`

访问地址：

- 配置页面：`http://<你的主机IP>:8080/`
- OpenClash 订阅地址：`http://<你的主机IP>:8080/sub/openclash.yaml?config=default`

## API（简要）

- `GET /api/upstreams`
- `POST /api/upstreams`
- `PUT /api/upstreams/{id}`
- `DELETE /api/upstreams/{id}`
- `GET /api/subscription/settings`
- `PUT /api/subscription/settings`
- `POST /api/subscription/templates`
- `PUT /api/subscription/templates/{id}`
- `DELETE /api/subscription/templates/{id}`
- `GET /sub/openclash.yaml`
- `GET /sub/openclash.yaml?config=balance`
- `GET /sub/openclash.yaml?config=https%3A%2F%2Fcdn.jsdelivr.net%2Fgh%2FMrxia%2Fclash_ruls%40master%2Fxiaodandan.ini`

## 订阅配置档

参考了 `subconverter` 的 `config` 参数思路，当前支持两种方式：

- `default`：默认模板，包含 `SELECT` / `AUTO` / `GLOBAL`
- `fallback`：增加 `FALLBACK` 组，适合自动故障转移
- `balance`：增加 `BALANCE` 组，适合轮询分流
- 远程模板 URL：例如 `https://cdn.jsdelivr.net/gh/Mrxia/clash_ruls@master/xiaodandan.ini`

首页可以把远程模板保存到下拉框中；后端也支持在 `config=` 中直接传 URL。

当前优先兼容远程 INI 中 `[custom]` 段的以下能力：

- `custom_proxy_group` / `proxy_group`
- `ruleset`
- `ruleset` 的 `,interval` 尾参
- `!!import`
- 相对路径导入
- `[template]` 局部变量
- 模板条件块 `{% if ... %}`
- `clash_rule_base` / `rule_base`
- `!!GROUPID` / `!!GROUP` / `!!PROVIDER`

当前仍未承诺完整覆盖 `subconverter` 外部配置的全部高级语法，例如：

- `script:` 选择器
- `!!INSERT`
- `ssid` 组类型
- 更完整的模板表达式 / include 体系之外的高级模板能力

## 开发记录

见：`docs/DEVLOG.md`
