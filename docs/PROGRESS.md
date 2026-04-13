# 项目进展

## 2026-04-13

- 完成订阅配置档能力的第一版落地：
  - 后端生成器支持 `config` 参数
  - 状态文件新增默认配置档持久化
  - 首页增加配置档下拉框
- 新增远程模板能力：
  - 可保存远程 `.ini` 模板 URL 到下拉框
  - `/sub/openclash.yaml?config=<URL>` 可直接使用远程模板
- 当前已补齐外部 INI 常见语法：
  - `!!import`
  - 相对路径
  - `ruleset` 尾部 `interval`
  - `[template]` 局部变量
  - 条件块
  - `clash_rule_base`
  - `!!GROUPID` / `!!GROUP` / `!!PROVIDER`
- 暂未实现对外部 `subconverter` INI 配置文件全部高级语法的完整兼容，尤其是 `script:` / `!!INSERT` / `ssid`
- 新增页面级开关：可将地区类 `proxy group` 自动切换为 `url-test`
- README 已补充私有仓库拉取、开发启动、Docker Compose 启动的详细步骤
