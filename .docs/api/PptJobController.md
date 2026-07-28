---
type: api
class: domi.argenticpptmaster.web.PptJobController
language: java
---

# PptJobController

## 通信模式

**类型**: REST
**Content-Type**: `multipart/form-data`, `application/json`, `text/event-stream`

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ppt-jobs` | 上传源文件并创建 PPT 生成任务 |
| GET | `/api/ppt-jobs/{jobId}` | 查询任务状态、确认载荷、事件和产物路径 |
| GET | `/api/ppt-jobs/{jobId}/events` | 订阅任务 SSE 事件 |
| POST | `/api/ppt-jobs/{jobId}/confirm` | 提交当前 agent 确认结果 |
| POST | `/api/ppt-jobs/{jobId}/resume` | 从上一个成功业务节点恢复失败的任务 |
| POST | `/api/ppt-jobs/{jobId}/template-fill/execute` | 提交 confirmed 模板填充计划并异步执行 |
| GET | `/api/ppt-jobs/{jobId}/download` | 下载已完成任务的 PPTX 产物 |

## 请求

### 创建任务

`POST /api/ppt-jobs`

表单字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `files` | 是 | 一个或多个源文件，支持 `md`、`pdf`、`ppt/pptx`、`doc/docx`、`xls/xlsx/xlsm`、`csv/tsv`、`html` |
| `templateFile` | 模板填充模式必填 | 原始 `.pptx` 模板；保存于任务 `uploads/template/`，内容源保存于 `uploads/content/` |
| `projectName` | 否 | 项目名，默认 `ppt_project` |
| `format` | 否 | ppt-master 画布格式，默认 `ppt169` |
| `instruction` | 否 | 用户对 agent 的额外生成要求 |
| `workflowMode` | 否 | `BASIC`（默认）、`IMAGE_ENHANCED`、`TEMPLATE_FILL`、`BEAUTIFY_PPTX` |

### 提交确认

`POST /api/ppt-jobs/{jobId}/confirm`

```json
{
  "confirmationId": "active-confirmation-id",
  "approved": true,
  "answers": {},
  "comment": "确认继续",
  "action": "APPROVE",
  "overallComment": "",
  "slideEdits": []
}
```

`action` 可取 `APPROVE`、`REQUEST_REVISION`、`CANCEL`。省略时沿用兼容规则：`approved=true` 等同于 `APPROVE`，`approved=false` 等同于 `CANCEL`。逐页修订时使用 `REQUEST_REVISION`，`slideEdits` 为 `{ "slideNo": 2, "comment": "修改该页视觉方案" }` 数组；必须提供整体意见或至少一条页级意见，页码必须存在于当前大纲。前端新增页面但由 Agent 补全页面内容时，应把新增页要求合并到 `overallComment`，不要提交字段不完整的结构化 `ADD`；只有 `title`、`keyMessage`、`bullets`、`visualSuggestion` 均完整时才使用 `outlineEdits` 的 `ADD`。

### 恢复任务

`POST /api/ppt-jobs/{jobId}/resume`

请求体为空。仅当任务状态为 `FAILED` 时允许恢复；非 beautify 还需至少一个成功节点。beautify 无成功节点也可恢复分析。
超过最大恢复次数或任务正在等待确认时返回 `409 Conflict`。

### 执行模板填充

`POST /api/ppt-jobs/{jobId}/template-fill/execute`

请求头 `X-Template-Fill-Debug-Token` 必须与服务端配置一致（默认关闭）；请求体为 JSON，顶层 `status` 必须为 `confirmed`。门禁通过后同步返回 `202 Accepted`，命令阶段在后台执行。

## 响应

成功创建任务返回 `202 Accepted`，响应体为 `PptJobResponse`：

| 字段 | 说明 |
|---|---|
| `id` | 任务 ID |
| `status` | `ACCEPTED`、`PREPARING`、`WAITING_CONFIRMATION`、`RUNNING_AGENT`、`EXPORTING`、`COMPLETED`、`FAILED`、`CANCELLED` |
| `workflowMode` | `BASIC`、`IMAGE_ENHANCED`、`TEMPLATE_FILL`、`BEAUTIFY_PPTX` |
| `sources` | 已落盘源文件元数据（不含绝对路径） |
| `template` | 模板文件元数据（不含服务器路径） |
| `contentSources` | 模板填充模式下的内容源元数据 |
| `beautifyAnalysis` | 仅 beautify：页数、canvas/`resolvedFormat`、三类 readiness、告警数、计划状态、`outputValidationSummary` |
| `currentConfirmationId` | 当前待确认 ID，只有 `WAITING_CONFIRMATION` 状态存在 |
| `confirmationPayload` | agent 给前端展示的确认信息；`WAITING_CONFIRMATION` 时也会通过 SSE 事件 `CONFIRMATION_REQUIRED.data.confirmationPayload` 下发 |
| `events` | 任务事件历史；beautify 分析事件含安全 `stage`/`node`/`summary`，失败含 `errorCode`/`failureKind` |
| `artifactReady` | 是否已进入可下载状态；只有 `COMPLETED` 且产物路径存在时为 `true` |
| `downloadUrl` | 只有 `COMPLETED` 且产物路径存在时才返回下载地址，否则为空 |
| `currentNode` | 当前正在执行的业务节点；失败时可能为空 |
| `lastCompletedNode` | 最近一个成功完成的业务节点，用于确定恢复 checkpoint |
| `lastFailureNode` | 最近一次失败所在的业务节点 |
| `resumeCount` | 已执行的恢复次数，初始为 0 |
| `resumable` | 是否可恢复；beautify 在无成功节点时仍可恢复分析 |
| `nodeStates` | 各业务节点状态；阶段 2 beautify 的 `BEAUTIFY_OUTPUT_VALIDATED` 保持 pending |

## 业务流程

1. 接收上传文件，校验扩展名并保存到 `ppt-master.workspace-path/jobs/{jobId}/uploads`；模板填充模式隔离写入 `template/` 与 `content/`；`BEAUTIFY_PPTX` 另校验模式专属单文件大小上限。
2. 创建内存任务记录：`BASIC`/`IMAGE_ENHANCED` 异步启动通用 Agent；`TEMPLATE_FILL` 走模板填充编排；`BEAUTIFY_PPTX` 先提交服务端托管分析（identity→init/import→intake→inventory），仅在 `BEAUTIFY_ANALYZED` 完成后以任务级原子门禁启动 beautify Agent。
3. beautify 分析失败写入稳定 `errorCode` 与 `failureKind`，不向客户端回传绝对路径、命令行或 stdout/stderr。
4. `AgentScopePptAgentRunner` 基于 AgentScope Java v2 `ReActAgent.streamEvents()` 驱动真实事件流，使用 `JsonFileAgentStateStore` 持久化会话状态：初始 attempt 按 `(serviceUserId, jobId)` 存储；checkpoint 恢复后会启用新的 attempt session，按 `(serviceUserId, jobId-attempt-N)` 存储，避免旧失败上下文污染新执行。
5. BASIC/IMAGE_ENHANCED agent 通过本地工具完成 `init_ppt_project`、`import_job_sources` 等步骤并生成大纲确认；beautify Agent 仅暴露计划工具，不能推进 `PROJECT_READY`/`BEAUTIFY_ANALYZED`/`BEAUTIFY_OUTPUT_VALIDATED`，确认阶段为 `beautify_plan_confirmation`。
6. 任务进入 `WAITING_CONFIRMATION` 时，SSE `CONFIRMATION_REQUIRED` 下发 `confirmationPayload`。
7. 用户提交 `APPROVE` 后回喂同一 session 继续；beautify 阶段 2 导出完成后可下载，但 `BEAUTIFY_OUTPUT_VALIDATED` 保持 `PENDING` 且 `outputValidationSummary.status=not-run`。
8. 失败后可调用 `POST /api/ppt-jobs/{jobId}/resume`：beautify 在 `PROJECT_READY` 前重跑完整分析，在其后补齐 intake/inventory，在 `BEAUTIFY_ANALYZED` 后恢复 Agent；无成功节点的 beautify 失败也可恢复分析。
9. 模板填充模式只接受 `confirmed` 计划，按 `init/import-sources -> analyze -> check-plan -> apply -> validate` 顺序调用固定脚本；上游项目导出的唯一 `.pptx` 经验证后复制到任务根 `exports/`，才设置完成产物并开放下载。

补充说明：`request_plan_confirmation` 作为外部工具时，AgentScope 可能先发出一条 `tool_result`，其 `state=RUNNING`。这不是“用户已确认”，而是“工具已挂起并等待人工确认”；服务端会立即把任务切换到 `WAITING_CONFIRMATION`，阻止 agent 在未确认时继续执行。

## 服务调用

| 服务 | 方法 | 说明 |
|---|---|---|
| `PptWorkflowService` | `createJob` | 创建任务、保存上传文件；beautify 触发托管分析，其他模式触发既有异步路径 |
| `PptWorkflowService` | `submitConfirmation` | 校验确认 ID 并恢复 agent |
| `PptWorkflowService` | `resumeJob` | 按模式与 checkpoint 恢复分析或 Agent |
| `BeautifyPptxAnalysisExecutor` | `execute` | 服务端执行 identity/init/import/intake/inventory 与纯文本门禁 |
| `PptWorkflowAsyncRunner` | `analyzeBeautifyAndStartAgent` | beautify 专用有界队列分析，成功后原子启动 Agent |
| `PptJobEventPublisher` | `subscribe` | 建立 SSE 订阅 |
| `PptAgentRunner` | `start` / `resume` / `resumeFromCheckpoint` | AgentScope 驱动适配层 |
| `PptTemplateFillService` | `submitPlan` | 校验调试令牌、计划门禁及一次性启动 |
| `PptTemplateFillCommandExecutor` | `execute` | 编排固定 ppt-master 脚本并收敛阶段事件 |

## 业务规则

- 创建任务必须至少包含一个非空源文件。
- 不支持的源文件扩展名返回 `400 Bad Request`。
- 只有 `WAITING_CONFIRMATION` 状态允许提交确认。
- `confirmationId` 必须匹配当前任务的活动确认 ID。
- `CONFIRMATION_REQUIRED` 事件会携带 `confirmationId` 与完整 `confirmationPayload`，用于前端直接弹出确认 UI。
- 首次确认必须使用 `stage=plan_confirmation` 并包含合法 `ppt_outline`；缺失、未知阶段或字段不完整时不得进入用户确认状态。
- 下载接口只允许 `COMPLETED` 状态访问。
- 即使后台已生成临时产物，只要任务状态未收敛到 `COMPLETED`，响应也不会提前暴露 `artifactReady=true` 或 `downloadUrl`。
- 响应不暴露服务器本地绝对路径，产物通过 `downloadUrl` 获取。
- 只有 `FAILED` 状态且存在 `lastCompletedNode` 时允许恢复；`WAITING_CONFIRMATION` 任务应使用 `/confirm` 而非 `/resume`。
- 恢复次数存在上限（默认 5 次），超过上限后返回 `409 Conflict`。
- 恢复成功后会启动新的 attempt session，agent 从中断点继续而不是重写已完成的节点。
- 模板填充执行默认关闭，令牌不写入响应、日志或事件 payload；重复执行返回 `409 Conflict`。
- 模板填充命令的路径必须位于当前任务工作区，参数以 argv 传递，不使用 shell、`--force` 或 SVG/Agent 流程。

## 设计决策

- 当前任务仓库使用内存实现，先稳定 API 和状态机；后续可替换为数据库持久化。
- Controller 不直接依赖 AgentScope 类型，AgentScope 细节收敛在 `PptAgentRunner` 适配层，降低框架版本升级影响。
- 首版保留 ppt-master Python 脚本为确定性工具，AgentScope 负责目标驱动、人机确认和长链路恢复。
- 当前版本优先实现 markdown-first 路线：先把上传资料归一化到 `sources/*.md`，再由 agent 生成 `design_spec.md`、`spec_lock.md`、`notes/total.md` 和 `svg_output/*.svg`，最后走 `quality-check -> split -> finalize -> export`。
- `pptx` 模板填充路线作为独立 `TEMPLATE_FILL` 模式编排，保持 markdown-first 主流程不变；阶段一仅支持人工 confirmed plan，兼容上游附加时间戳的导出文件名。
