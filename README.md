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

## 运行（开发模式）

需要：JDK 21、Maven 3.9+

```bash
mvn quarkus:dev
```

打开：

- 配置页面：`http://localhost:8080/`
- OpenClash 订阅地址：`http://localhost:8080/sub/openclash.yaml?config=default`

配置保存位置（默认）：`./data/state.json`

可通过 `src/main/resources/application.properties` 中的 `substacker.data-dir` 修改。

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
