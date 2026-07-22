# `template-fill-pptx` 接入规划

## 1. 目标与边界

目标是在保持现有 markdown-first/SVG 流程完全兼容的前提下，新增原生 PPTX 模板填充能力：用户上传一份 `.pptx` 作为模板，再上传 Markdown、Office 文档、表格或其他资料作为内容源；服务选择模板中的合适页面，填入新内容，并输出可编辑的 `.pptx`。

该能力应调用上游 `ppt-master` 的 `template-fill-pptx` 工作流，而非把模板 PPTX 转为 SVG 后再走现有导出流程。其核心命令为：

```text
template_fill_pptx.py analyze → scaffold/生成 fill_plan → check-plan → apply → validate
```

本规划不包含以下范围：

- 将 PPTX 模板转成可复用 SVG 模板包（这是上游的 `create-template` 路线，可另立需求）。
- 自动判断任意多个 PPTX 中哪一个一定是模板；首版由 API 字段明确指定。
- 对动画时间线、母版、SmartArt、OLE 对象做任意重绘或修复。
- 改造已有 `basic` 与 `image-enhanced` 的行为。

## 2. 现状与设计结论

当前服务只有 `files` 上传字段，所有文件均被建模为 `PptSourceFile`，并由 `import_job_sources` 无差别导入 `sources/`。Agent 的初始指令也强制要求 markdown/SVG 路线，因此即使上传 `.pptx`，它当前也只是内容资料，而不是模板。

建议新增独立工作流模式 `TEMPLATE_FILL`，而不是给 `BASIC` 增加条件分支。理由：

- 输入契约不同：必须有且仅有一个模板 PPTX，且内容资料与模板角色不同。
- 中间产物不同：`analysis/<template>.slide_library.json`、`analysis/fill_plan.json` 和 `validation/` 是关键事实源，不需要 `svg_output/`、`svg_final/`。
- 人工确认对象不同：需要确认“选哪些模板页、按什么顺序、各槽位填什么”，而不只是确认内容大纲。
- 导出机制不同：直接 OOXML 克隆和替换，不能执行 `finalize_svg.py`、`svg_to_pptx.py`。

推荐以**显式角色字段 + 单个任务**实现：模板和内容文件仍在同一个任务工作区，但接口、领域模型和事件中必须能区分 `template` 与 `content`。

## 3. 目标接口契约

首版应保留现有接口的向后兼容性，并仅在 `workflowMode=template-fill` 时启用以下字段：

```text
POST /api/ppt-jobs (multipart/form-data)
  templateFile: MultipartFile           # 必填；扩展名必须为 .pptx
  files: List<MultipartFile>            # 必填；一个或多个内容源，不能包含模板文件
  workflowMode: template-fill
  projectName: string?                  # 沿用
  instruction: string?                  # 软偏好（语气等）；不得覆盖结构化硬约束
  templateConstraints: string?          # 可选 JSON：allowed/excludedTemplateSlides、preserveCover/Ending、maxSlides
  format: string?                       # 首版仅校验记录；实际画布继承模板 PPTX
```

校验规则：

| 条件 | 处理 |
| --- | --- |
| `templateFile` 缺失、为空或不是 `.pptx` | 400，拒绝创建任务 |
| 内容 `files` 为空或全为空 | 400，拒绝创建任务 |
| 内容中存在 `.pptx` | 首版允许其作为参考资料，但不将其当模板；在响应/事件中显式标记 |
| `workflowMode` 不是 `template-fill` | 不接受 `templateFile`，或明确忽略并记录；推荐 400 以避免误解 |
| `format` 与模板尺寸不一致 | 不阻断；记录实际模板尺寸，并在任务详情中提示 `format` 未参与版式转换 |

任务详情应新增但不泄露本地绝对路径的字段：`template`（原始文件名、大小、内容类型）、`contentSources`、`templateAnalysisReady`、`fillPlanStatus`。已有 `sources` 字段在过渡期可保留，标记为 deprecated 后再移除。

## 4. 分阶段计划（由简单到复杂）

### 阶段 0：可行性基线与契约冻结

目的：在接入服务前确认本地 `ppt-master` 的实际版本和模板填充的最小闭环可运行。

工作项：

1. 用一份无敏感信息的模板 PPTX 和一份 Markdown 资料，在 `ppt-master` 仓库手工执行 `init`、`import-sources`、`analyze`、`scaffold`、`check-plan`、`apply`、`validate`。
2. 固化命令版本、Python 依赖、输出目录、失败返回码、生成文件命名规则及可支持的模板对象范围。
3. 收集模板基准样本：纯文本页、表格、图表、图片、动画、复杂母版/SmartArt 各至少一份，并写明已知限制。
4. 确认现有服务配置的 `ppt-master.repo-path` 指向的版本包含 `template_fill_pptx.py` 及其依赖。
5. 冻结 API 字段名、错误码、模板/内容文件角色和“必须确认 fill plan 后才能 apply”的规则。

完成标准：端到端手工样例可生成并通过 `validate`；形成一份兼容性/限制清单；不修改生产 Java 代码。

### 阶段 1：最小可用的后端直连（不引入 AI 自动选页）

目的：先验证服务层能安全托管原生模板填充，不改变现有 Agent 主流程。

工作项：

1. 在 `PptWorkflowMode` 增加 `TEMPLATE_FILL`，并扩展模式解析以识别 `template-fill`。
2. 在领域层新增 `PptTemplateFile`（或带 `role` 的上传文件模型），明确模板与内容来源；禁止通过文件名猜测角色。
3. 改造创建任务接口及服务校验，分别持久化到 `<job>/uploads/template/` 和 `<job>/uploads/content/`；仍需做路径穿越防护、扩展名白名单和空文件校验。
4. 新增一个受限的执行服务/工具包装器，只允许在工作区内运行 `project_manager.py` 与 `template_fill_pptx.py`；命令参数使用 `ProcessBuilder` 参数数组，不拼接 shell 字符串。
5. 先提供内部或受保护的调试接口：输入经过人工编写且 `status=confirmed` 的 `fill_plan.json`，执行 `analyze → check-plan → apply → validate`，并把产物保存到标准 `analysis/exports/validation/`。
6. 把执行过程映射为任务事件和失败信息，支持下载最终产物。

完成标准：无需 Agent 参与即可通过 API 创建模板填充任务、提交已确认计划并下载结果；`basic`、`image-enhanced` 全量回归通过。

### 阶段 2：工作区准备、分析与可观察性

目的：让 `TEMPLATE_FILL` 拥有完整、可诊断、可恢复的前半段流程。

工作项：

1. 为 `TEMPLATE_FILL` 定义专属 checkpoint，建议为：`PROJECT_READY`、`TEMPLATE_ANALYZED`、`FILL_PLAN_DRAFTED`、`FILL_PLAN_CONFIRMED`、`FILL_PLAN_VALIDATED`、`PPT_EXPORTED`、`OUTPUT_VALIDATED`。
2. 不复用 SVG 专属节点（例如 `SVG_FINALIZED`）；调整恢复逻辑，使其按工作流实际节点集合继续而非按枚举顺序假定。
3. 在项目初始化时将模板与内容都导入 `sources/`，但对 `analyze` 显式传入模板文件路径；读取并记录 `slide_library.json` 的页数、尺寸、可填文本槽、表格/图表数量和分析版本。
4. 为 `PptJobResponse` 和 SSE 增加安全的结构化进度数据：当前模板页数、计划页数、校验告警/错误数、最终文件名；不要返回原始槽位全文或工作区绝对路径。
5. 增加文件大小、总上传大小、解压/解析超时、Python 子进程超时与并发限制；失败事件中返回可行动的错误码，例如 `TEMPLATE_ANALYSIS_FAILED`、`FILL_PLAN_INVALID`、`TEMPLATE_APPLY_FAILED`。

完成标准：模板分析失败可定位，任务可从 `TEMPLATE_ANALYZED` 或 `FILL_PLAN_VALIDATED` 恢复，状态和事件足以支撑前端展示。

### 阶段 3：接入 Agent 生成填充计划与人工确认

目的：实现用户所需的“上传模板 + 上传内容 → 自动形成填充方案”，但保留关键人工门禁。

工作项：

1. 为 `TEMPLATE_FILL` 创建独立的 Agent 系统提示和工具白名单，不复用“写 SVG、finalize、export SVG”的指令。
2. 增加只读工具：读取内容 Markdown、读取模板页库、读取表格/图表槽位摘要、检查已有 checkpoint；增加写入受限工具：仅允许写 `analysis/fill_plan.json` 和必要的计划说明文件。
3. 要求 Agent 按“内容逻辑匹配版式能力”选择模板页，而非按原幻灯片顺序机械替换；无合适版式时应说明删减/拆页建议，不能强塞内容或默认缩小字体。
4. 将计划以结构化 payload 提交 `request_plan_confirmation`：输出页序、每页引用的模板页、槽位映射、被省略的内容、容量风险、表格/图表处理方式和已接受告警。
5. 只有 `/confirm` 明确批准后，服务才把 `fill_plan.json.status` 置为 `confirmed` 或允许调用 `apply`；拒绝后将用户意见作为恢复上下文，要求 Agent 修订计划。
6. 确保 Agent 永不自行使用 `--force` 绕过确认门禁。

完成标准：典型模板和内容资料能自动生成可解释的计划；未确认计划无法产生 PPTX；拒绝后的修订路径可用。

**实现状态（2026-07-20）**：阶段三已落地。`TEMPLATE_FILL` 使用独立 Agent profile（`TemplateFillAgentTools` + `request_plan_confirmation`）；草案仅能写入 `status=draft`，服务端 compare-and-confirm（confirmationId/version/digest）后才提升为 `confirmed` 并调度 `check-plan → apply → validate`。调试 execute 入口要求已有服务端批准记录，拒绝请求体自声明 confirmed。修订走新 attempt/session 与有界反馈，分析产物缺失时恢复回退到 `PROJECT_READY` 而非静默复用。

### 阶段 4：原生内容填充完善

目的：提升文本之外的资料利用率与成品可编辑性。

工作项：

1. 支持 Agent 在计划中填充文本、演讲者备注、原生表格单元格与受支持图表数据；每类都基于 `slide_library.json` 暴露的稳定 ID。
2. 实施内容压缩/拆页策略：先改写、再选择容量更大的模板页、最后才允许受控字号调整；将容量告警保留在计划和事件中。
3. 支持页面级 transition 选项，默认沿用上游的 `fade` 策略；对象级动画仅保留模板原状，不承诺编辑。
4. 支持“只用指定模板页”“必须保留封面/结束页”“最多 N 页”“排除原模板某些页”等指令字段，并把它们写入计划约束。
5. 增加 `apply` 后的回读校验：页数、标题/正文关键字段、备注、文件可打开性及导出目录唯一性。

完成标准：文本、备注、表格、常见图表均有明确支持或明确失败提示；验证报告可被前端/运维读取。

**实现状态（2026-07-21）**：阶段四已落地（OpenSpec `enhance-template-fill-native-content`）。上游契约固定为 `template_fill_pptx_plan.v1`；创建支持结构化 `templateConstraints`；计划与 `fill_plan.service-meta.json` 联合 digest；确认 payload 由服务端生成有界摘要；`apply` 后独立 OOXML 回读写入 `validation/template-fill-readback.json`；字号调整因上游 `apply` 不支持而显式拒绝。支持矩阵见 `src/test/resources/template-fill/stage4-support-matrix.md`。

### 阶段 5：生产化、兼容性与用户体验

目的：可安全灰度发布并可长期维护。

工作项：

1. 用 feature flag（如 `ppt-master.template-fill-enabled`）保护新模式；初期只对测试租户或管理员开放。
2. 实现模板文件生命周期策略：任务结束后的保留期、下载权限、清理任务和审计日志；模板可能包含高敏感品牌素材，禁止跨任务复用或暴露。
3. 为前端增加两个明确上传区：`模板 PPTX` 与 `内容资料`，并在创建前提示“模板页会被选择/克隆，不会自由重绘”。展示模板分析摘要和可确认的填充计划。
4. 指标与告警：任务创建量、分析/应用/校验耗时、失败码、确认拒绝率、告警接受率、恢复成功率、模板对象不兼容率。
5. 提供运维手册：Python 依赖检查、常见 OOXML 兼容问题、如何导出脱敏诊断包、如何升级 `ppt-master` 及回滚。
6. 在稳定后评估是否接入上游 `create-template`，作为与 `TEMPLATE_FILL` 并列的另一种模式，避免两条路线语义混淆。

完成标准：灰度指标满足预设阈值、故障可诊断可回滚、文档和前端契约完整。

**实现状态（2026-07-21）**：阶段五已落地（OpenSpec `productionize-template-fill`）。默认关闭创建门禁 + 可信 tenant/admin 资格；任务归属授权；`lifecycle/manifest.json`、审计与 dry-run 优先清理；Actuator/Micrometer 低基数遥测与 readiness；脱敏诊断包；一等静态页 `/template-fill/`；运维文档见：

- [`docs/ops/template-fill-runbook.md`](ops/template-fill-runbook.md)
- [`docs/ops/template-fill-upgrade-rollback.md`](ops/template-fill-upgrade-rollback.md)
- [`docs/ops/template-fill-frontend-contract.md`](ops/template-fill-frontend-contract.md)
- [`docs/ops/template-fill-rollout-thresholds.md`](ops/template-fill-rollout-thresholds.md)
- [`docs/ops/template-fill-alerts.example.yml`](ops/template-fill-alerts.example.yml)
- [`docs/ops/create-template-evaluation.md`](ops/create-template-evaluation.md)（评估 only，不实现）
- [`docs/ops/template-fill-compatibility-matrix-stage5.md`](ops/template-fill-compatibility-matrix-stage5.md)
- [`docs/ops/template-fill-canary-acceptance.md`](ops/template-fill-canary-acceptance.md)

**阈值结论**：阻断阈值定义已入库；**生产扩大灰度 / 开启实际删除前**，必须以同一发布版本的 canary 样本窗口对照 [`template-fill-rollout-thresholds.md`](ops/template-fill-rollout-thresholds.md) 记录通过证据（见 canary 验收文档当前状态）。

## 5. 建议的执行流

```text
创建任务（templateFile + files）
  → 分角色保存上传文件
  → 初始化项目并导入资料
  → analyze(template.pptx)
  → Agent 读取内容与 slide_library，草拟 fill_plan
  → 用户确认/驳回计划
  → check-plan
  → apply(template.pptx, confirmed fill_plan)
  → validate(project)
  → 下载 exports/*.pptx
```

失败恢复原则：分析失败从导入后重试；计划校验失败回到计划修订；`apply` 或 `validate` 失败可从最近已确认且通过校验的计划重试。不得重跑或覆盖原模板文件。

## 6. 测试策略

| 层级 | 覆盖重点 |
| --- | --- |
| 单元测试 | 模式解析、模板/内容校验、文件角色隔离、节点适用性、错误码映射、计划确认门禁 |
| 服务集成测试 | multipart 创建、模板与内容持久化、子进程参数、超时/失败处理、SSE/下载/恢复 |
| `ppt-master` 契约测试 | 固定模板样例的 `analyze/check-plan/apply/validate` 结果及关键 JSON schema |
| 端到端测试 | 文本页、表格页、图表页、含备注/动画页、内容过长、模板不兼容、用户驳回后重提 |
| 回归测试 | `basic` 与 `image-enhanced` 的既有创建、确认、导出和恢复行为 |
| 安全测试 | 路径穿越、伪造扩展名、压缩炸弹/超大文件、恶意文件名、跨任务文件访问、命令注入 |

测试样例中的模板和内容必须脱敏，二进制黄金文件应以哈希、页数、关键文本回读和关系完整性校验为主，避免仅做不稳定的字节级比较。

## 7. 风险与决策点

| 风险/决策 | 建议 |
| --- | --- |
| 模板含 SmartArt、OLE、EMF/WMF 或不常见对象 | 在阶段 0 建立兼容矩阵；不可用时失败并保留诊断，不做静默降级 |
| 多个 PPTX 上传 | 接口只允许一个 `templateFile`；其他 PPTX 是内容/参考资料，Agent 不得把它误用于 `apply` |
| 内容超出版式 | `check-plan` 必须在 `apply` 前运行；优先改写、拆页或换页，不以缩小字体掩盖问题 |
| 模板尺寸与 `format` 冲突 | 模板填充以模板原生页面尺寸为准；后续若需尺寸转换，应成为单独模式 |
| Agent 幻觉或越权写文件 | 工具白名单、路径白名单、JSON schema 校验、确认门禁和服务端 `apply` 参数校验共同防护 |
| 上游脚本升级 | 固定经验证版本；为 `slide_library` 与 `fill_plan` 建立 schema/契约测试后再升级 |

## 8. 推荐里程碑

1. **M0：可行性通过** — 阶段 0 完成，输出兼容矩阵和冻结契约。
2. **M1：受控 MVP** — 阶段 1 完成，人工提供计划也能生成和下载。
3. **M2：可用 Beta** — 阶段 2 与阶段 3 完成，具备自动计划、用户确认、恢复和观测能力。
4. **M3：功能完整** — 阶段 4 完成，覆盖常见表格/图表/备注用例。
5. **M4：生产发布** — 阶段 5 的灰度、指标、权限与运维条件达标。

实施时每个阶段应独立提交、独立测试和独立审查；涉及 API/任务状态变化时，按项目约定在改动前后执行文档同步流程。
