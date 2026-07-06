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
| GET | `/api/ppt-jobs/{jobId}/download` | 下载已完成任务的 PPTX 产物 |

## 请求

### 创建任务

`POST /api/ppt-jobs`

表单字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `files` | 是 | 一个或多个源文件，支持 `md`、`pdf`、`ppt/pptx`、`doc/docx`、`xls/xlsx/xlsm`、`csv/tsv`、`html` |
| `projectName` | 否 | 项目名，默认 `ppt_project` |
| `format` | 否 | ppt-master 画布格式，默认 `ppt169` |
| `instruction` | 否 | 用户对 agent 的额外生成要求 |

### 提交确认

`POST /api/ppt-jobs/{jobId}/confirm`

```json
{
  "confirmationId": "active-confirmation-id",
  "approved": true,
  "answers": {},
  "comment": "确认继续"
}
```

## 响应

成功创建任务返回 `202 Accepted`，响应体为 `PptJobResponse`：

| 字段 | 说明 |
|---|---|
| `id` | 任务 ID |
| `status` | `ACCEPTED`、`PREPARING`、`WAITING_CONFIRMATION`、`RUNNING_AGENT`、`EXPORTING`、`COMPLETED`、`FAILED`、`CANCELLED` |
| `sources` | 已落盘源文件 |
| `currentConfirmationId` | 当前待确认 ID，只有 `WAITING_CONFIRMATION` 状态存在 |
| `confirmationPayload` | agent 给前端展示的确认信息；`WAITING_CONFIRMATION` 时也会通过 SSE 事件 `CONFIRMATION_REQUIRED.data.confirmationPayload` 下发 |
| `events` | 任务事件历史 |
| `artifactReady` | 是否已进入可下载状态；只有 `COMPLETED` 且产物路径存在时为 `true` |
| `downloadUrl` | 只有 `COMPLETED` 且产物路径存在时才返回下载地址，否则为空 |

## 业务流程

1. 接收上传文件，校验扩展名并保存到 `ppt-master.workspace-path/jobs/{jobId}/uploads`。
2. 创建内存任务记录并异步启动 agent 编排。
3. `AgentScopePptAgentRunner` 基于 AgentScope Java v2 `ReActAgent.streamEvents()` 驱动真实事件流，使用 `JsonFileAgentStateStore` 按 `(serviceUserId, jobId)` 持久化会话状态。
4. agent 通过本地工具完成 `init_ppt_project`、`import_job_sources`、`collect_source_markdown`、`inspect_project_info`、`read_project_text_file` 等步骤，先走 markdown-first 路线分析资料与生成计划；当需要人工确认方案时，会调用外部执行工具 `request_plan_confirmation`，服务侧将 `RequireExternalExecutionEvent` 转成 `confirmationPayload`。
5. 任务进入 `WAITING_CONFIRMATION`，服务会在 SSE 事件 `CONFIRMATION_REQUIRED` 的 `data.confirmationPayload` 中直接下发确认 UI 所需信息；前端可直接渲染确认面板，只有在 SSE 异常或恢复场景下才需要回退查询任务快照。
6. 用户提交确认后，服务把确认结果封装为 `ToolResultMessage` 回喂给同一 AgentScope session，agent 从中断点继续执行；若再次触发人工确认，会生成新的 `confirmationId`。
7. agent 在确认后可写入 `design_spec.md`、`spec_lock.md`、`notes/total.md`、`svg_output/*.svg`，再执行 `validate_svg_output`、`split_speaker_notes`、`finalize_project_svg` 和 `export_project_pptx`；任务完成时设置产物并通过下载接口获取 PPTX。若 agent 正常结束但未产出导出物，任务会失败并保留最终总结信息。

## 服务调用

| 服务 | 方法 | 说明 |
|---|---|---|
| `PptWorkflowService` | `createJob` | 创建任务、保存上传文件、触发异步 agent |
| `PptWorkflowService` | `submitConfirmation` | 校验确认 ID 并恢复 agent |
| `PptJobEventPublisher` | `subscribe` | 建立 SSE 订阅 |
| `PptAgentRunner` | `start` / `resume` | AgentScope 驱动适配层 |

## 业务规则

- 创建任务必须至少包含一个非空源文件。
- 不支持的源文件扩展名返回 `400 Bad Request`。
- 只有 `WAITING_CONFIRMATION` 状态允许提交确认。
- `confirmationId` 必须匹配当前任务的活动确认 ID。
- `CONFIRMATION_REQUIRED` 事件会携带 `confirmationId` 与完整 `confirmationPayload`，用于前端直接弹出确认 UI。
- 下载接口只允许 `COMPLETED` 状态访问。
- 即使后台已生成临时产物，只要任务状态未收敛到 `COMPLETED`，响应也不会提前暴露 `artifactReady=true` 或 `downloadUrl`。
- 响应不暴露服务器本地绝对路径，产物通过 `downloadUrl` 获取。

## 设计决策

- 当前任务仓库使用内存实现，先稳定 API 和状态机；后续可替换为数据库持久化。
- Controller 不直接依赖 AgentScope 类型，AgentScope 细节收敛在 `PptAgentRunner` 适配层，降低框架版本升级影响。
- 首版保留 ppt-master Python 脚本为确定性工具，AgentScope 负责目标驱动、人机确认和长链路恢复。
- 当前版本优先实现 markdown-first 路线：先把上传资料归一化到 `sources/*.md`，再由 agent 生成 `design_spec.md`、`spec_lock.md`、`notes/total.md` 和 `svg_output/*.svg`，最后走 `quality-check -> split -> finalize -> export`。
- `pptx` 模板填充路线暂不接入主流程，后续单独编排，避免与 markdown 路线共享不一致的生成契约。
