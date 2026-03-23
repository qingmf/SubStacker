# 架构说明（通用模板）

本文档是一个可复用的后端服务架构模板，描述基于 `JDK 21 + Maven + Quarkus` 的常见分层、关键数据流与扩展点。
将其拷贝到其他项目时，只需把“具体业务名/路径/模型”替换为对应项目内容即可。

## 1. 总体结构

一个典型的轻量后端服务通常包含以下能力（按需取舍）：

1. 对外提供 HTTP API（查询/下载/写入/触发任务等）
2. 在 Service 层编排核心流程（校验、调用外部依赖、组装结果）
3. 与外部依赖交互（HTTP、消息、数据库、文件系统等）
4. 失败兜底（重试/回退/降级/缓存）与可观测性（日志、耗时、指标）
5. 可选的产物落盘（导出文件、快照、审计记录等）

## 2. 分层与包职责

建议包结构（以 `src/main/java/<groupId>` 为根）按职责拆分：

- `<groupId>.resource`：HTTP 接口层（JAX-RS Resource）。
  - 仅做参数解析、header/Content-Type、调用 Service 并返回
- `<groupId>.service`：业务编排层（核心流程）。
- `<groupId>.config`：配置映射与默认值（MP Config / `application.properties`）。
- `<groupId>.model.*`：数据模型（请求/响应 DTO、领域模型、外部依赖响应模型、内部快照模型等）。
- `<groupId>.exception`：异常与统一异常映射（`ExceptionMapper`）。
- `<groupId>.filter`：请求级过滤器/拦截器（如耗时统计、TraceId 等）。
- `<groupId>.repository`：持久层（可选，数据库/缓存等）。

## 3. 关键数据流

### 3.1 在线查询/下载（实时优先）

以 `GET /api/<resource>/<format>` 为例：

1. `<XxxResource>` 解析参数（含默认值与可选覆盖参数）
2. `<XxxService>`：
   - 调用外部依赖（HTTP/DB/文件/缓存）
   - 进行必要的校验/清洗/排序/去重
   - 生成目标格式内容（如 JSON/CSV/TXT/Binary）
   - 写入“最近一次成功”缓存或快照（可选）
3. `<XxxResource>` 以合适的 `Content-Type` 返回内容；若是下载类接口，建议设置：
   - `Content-Disposition: attachment; filename="..."`
   - 自定义响应头提示降级/回退信息（若需要）

### 3.2 触发生成/导出（批量产出）

以 `POST /api/<resource>/generate` 为例：

- 生成“带时间戳文件” + “latest 固定文件名”（便于人肉下载/回滚/外部系统读取）
- 返回结构化结果（建议包含路径、生成时间、产物数量、是否降级/回退、提示信息）

### 3.3 回退链路（失败兜底）

当实时处理失败时，建议按“成本由低到高”的顺序回退：

1. 回退到内存中最近一次成功的快照（进程内）
2. 若内存无数据，回退到最近一次成功生成的本地文件（或持久化快照）
3. 若仍不可用，则返回错误（由统一异常映射输出稳定的错误响应）

## 4. 配置与约定

配置入口建议统一放在 `src/main/resources/application.properties`，并与环境变量对齐（便于容器部署与 CI/CD）。

常见配置项分类：

- 外部依赖：`<APP>_API_BASE_URL`、`<APP>_API_TIMEOUT_SECONDS`、`<APP>_CONNECT_TIMEOUT_SECONDS`
- 产物输出：`<APP>_OUTPUT_DIR`
- 功能开关：`<APP>_FEATURE_X_ENABLED`
- 运行参数：端口、线程、限流等（优先使用框架原生配置项）

接口层可在“单次请求”层面支持覆盖某些配置（例如导出地址前缀、输出格式等），但要明确优先级：
请求参数 > 环境变量 > 默认值。

## 5. 扩展点（拆分建议）

当需求增长时，优先做结构演进而不是堆叠条件分支：

- 抽象“外部依赖客户端”（例如 `UpstreamClient`），把 URL/鉴权/重试/解析封装到独立组件
- 抽象“渲染/导出器”（例如 `Renderer` / `Exporter`），将格式化输出从业务编排中剥离
- 抽象“快照/缓存存储”（内存/文件/数据库），统一读写与回退逻辑
- 将“规则/策略”显式建模（例如 `DedupPolicy` / `FilterPolicy`），便于测试与组合
