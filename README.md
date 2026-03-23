# SubStacker

机场订阅聚合器：将多个上游订阅（不同格式）聚合为一个 OpenClash 可直接订阅的 Clash YAML。

## 功能

- Web 页面配置上游（增删改）
- 聚合并去重节点，生成 OpenClash 可用的 `openclash.yaml`
- 上游格式（当前版本）
  - `CLASH_YAML_URL`：上游为 Clash YAML（读取 `proxies:`）
  - `URI_LIST_URL`：上游为 URI 列表或 base64(URI 列表)，支持 `ss://` / `vmess://` / `trojan://`

## 运行（开发模式）

需要：JDK 21、Maven 3.9+

```bash
mvn quarkus:dev
```

打开：

- 配置页面：`http://localhost:8080/`
- OpenClash 订阅地址：`http://localhost:8080/sub/openclash.yaml`

配置保存位置（默认）：`./data/state.json`

可通过 `src/main/resources/application.properties` 中的 `substacker.data-dir` 修改。

## API（简要）

- `GET /api/upstreams`
- `POST /api/upstreams`
- `PUT /api/upstreams/{id}`
- `DELETE /api/upstreams/{id}`
- `GET /sub/openclash.yaml`

## 开发记录

见：`docs/DEVLOG.md`

