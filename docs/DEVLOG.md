# 开发记录

## 2026-04-13

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
- 更强的 `proxy-groups` 生成：按地区/标签分组、策略组模板、规则集/分流
- 上游健康检查与错误可视化（哪个上游拉取失败、解析失败原因）
- 手动重命名/排序、节点过滤（正则、关键字、延迟阈值）
- 缓存与定时刷新（避免 OpenClash 频繁拉取导致上游限流）
