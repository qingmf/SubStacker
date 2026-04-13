# 开发记录

## 2026-04-13

- 新增“订阅配置档”能力，支持通过首页下拉框切换 `config` 参数
- 新增“地区组自动测速”开关，可将“香港节点 / 台湾节点 / 日本节点”这类地区组从 `select` 转成 `url-test`
- `/sub/openclash.yaml` 与 `/sub/openclash.yml` 支持 `?config=<id>` 查询参数
- `/sub/openclash.yaml` 额外支持 `?config=<远程INI URL>`，可直接使用远程模板地址
- 增加三套内置配置档：
  - `default`
  - `fallback`
  - `balance`
- 增加远程模板管理：
  - 保存远程 `.ini` 模板到下拉框
  - 编辑、删除已保存模板
  - 当前默认兼容 `[custom]` 中的 `ruleset` / `custom_proxy_group`
  - 新增对 `!!import`、相对路径、`clash_rule_base`、`[template]` 局部变量、条件块以及 `!!GROUPID` / `!!GROUP` / `!!PROVIDER` 的支持
- 默认配置档会持久化到 `state.json`
- 扩展 `URI_LIST_URL` 解析能力，补齐真实机场订阅常见节点类型：
  - `ss://`（含 SIP002 / plugin 变体）
  - `ssr://`
  - `vmess://`（JSON / URI 两种写法）
  - `trojan://`
  - `vless://`
  - `hy2://` / `hysteria2://`
  - `tuic://`
- 上游抓取增加默认 `User-Agent: Clash.Meta`
- 非 2xx 上游响应现在会直接标记为失败，便于通过调试接口定位问题

## 2026-03-23

- 初始化项目骨架（JDK 21 + Maven + Quarkus 3）
- 增加上游配置存储（`./data/state.json`）
- 实现两类上游解析：
  - `CLASH_YAML_URL`：解析 Clash YAML 的 `proxies`
  - `URI_LIST_URL`：解析 `ss://` / `vmess://` / `trojan://`（以及 base64 包裹的 URI 列表）
- 实现 OpenClash 订阅输出：`GET /sub/openclash.yaml`
- 增加简单 Web 页面：`/`（配置上游 + 预览订阅）

## 待办（下一步）

- 支持更多订阅格式（如 sing-box、Hysteria1、WireGuard、HTTP/SOCKS 出站）
- 补齐更多 `subconverter` 外部配置语法兼容点
- 更强的 `proxy-groups` 生成：按地区/标签分组、策略组模板、规则集/分流
- 上游健康检查与错误可视化（哪个上游拉取失败、解析失败原因）
- 手动重命名/排序、节点过滤（正则、关键字、延迟阈值）
- 缓存与定时刷新（避免 OpenClash 频繁拉取导致上游限流）
