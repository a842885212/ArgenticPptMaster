# `beautify-pptx` 接入规划

## 1. 目标与边界

目标是在现有 `BASIC` / `IMAGE_ENHANCED` markdown-first/SVG 导出链路完全兼容的前提下，新增「美化 / 重新排版」能力：用户上传**一份已有** `.pptx`；服务在**逐字保留文本与图表/表格数据**、**页数与页序严格 1:1** 的前提下，仅重做布局、层级与留白，并继承经用户确认的源视觉身份（色板 / 字体 / 字号），最终通过标准 SVG 管线产出一份**全新生成**的原生 PPTX。

该能力应对齐上游 `ppt-master` 的 `beautify-pptx` 工作流（部署时以配置项 `ppt-master.repo-path` 下的 `skills/ppt-master/workflows/beautify-pptx.md` 为准），核心脚本与命令为：

```text
beautify_identity.py <source.pptx>          # 画布探测（init 前 stdout）
project_manager.py init / import-sources
pptx_intake.py …                             # 若 intake bundle 缺失则补齐
beautify_inventory.py … → beautify_inventory.json
（确认 beautify plan 后）design_spec.md / spec_lock.md
标准 SVG 管线：finalize_svg.py → svg_to_pptx.py
导出后 read-back：ppt_to_md.py
```

本规划**不包含**以下范围：

- 对源 PPTX 做原地 OOXML patch（那是上游 `native-enhance-pptx` 路线）。
- 克隆模板页并替换文案（那是已落地的 `template-fill-pptx` / `TEMPLATE_FILL`）。
- 允许拆页 / 合页 / 改页序 / 重写大纲的「主流程重构」（应走 `ppt_to_md` + `BASIC`/`IMAGE_ENHANCED`）。
- 批量多份 PPTX 美化（上游 v1 明确不支持）。
- 改造已有 `basic`、`image-enhanced`、`template-fill` 的既有行为语义。

### 1.1 三项硬约束

| 约束 | 含义 |
| --- | --- |
| 文本与数据逐字保留 | 源幻灯片全部文本字符串不可增删改写；图表 categories / series、表格单元格数值与文本冻结；仅允许重绘渲染样式 |
| 页数与页序严格 1:1 | 源第 N 页 → 输出第 N 页；禁止合并、拆分、丢弃、重排 |
| 布局-only 重设计 + 确认后的身份继承 | 自由度仅在布局 / 层级 / 留白 / 视觉节奏；色板、字体、字号等身份项由分析推荐、用户确认后锁定 |

上述三项**不可**被确认阶段放宽。确认只决定「继承哪套身份候选、忽略哪些非内容项、复杂对象如何处理、校验严格度」等可协商范围。

### 1.2 与相邻能力的区分

| 能力 | 输入意图 | 内容 | 页结构 | 视觉 | 实现路径 |
| --- | --- | --- | --- | --- | --- |
| **beautify-pptx（本规划）** | 「美化 / 重新排版，内容别动」 | 冻结 | 1:1 冻结 | 继承并确认后的身份；布局重做 | 独立模式 + **复用** Agent/SVG/finalize/export |
| **template-fill-pptx** | 「用这份模板填新内容」 | 新内容填入模板槽位 | 由填充计划决定选页/顺序 | **保留**模板设计壳 | `TEMPLATE_FILL`，OOXML `analyze → apply`，**不**走 SVG |
| **主流程（BASIC / IMAGE_ENHANCED）** | 资料成稿 / 允许重构故事线 | 可改写、可重大纲 | 可拆合重排 | 八确认或大纲确认后新建视觉 | 现有 markdown → SVG 主链路 |
| **native-enhance-pptx** | 成品上加备注 / 旁白 / 切换等 | 基本不动 | 基本不动 | 布局保持稳定 | 直接 OOXML patch（接入规划见 [`native-enhance-pptx-integration-plan.md`](./native-enhance-pptx-integration-plan.md)） |

路由判定（对齐上游 `routing.md`）：若用户要求拆密页、合稀疏页、改页序或重写大纲 → **不是** beautify，应路由到主流程；仅当明确要保留原页切分与措辞时进入本模式。

---

## 2. 现状与设计结论

### 2.1 当前服务状态

| 组件 | 现状 |
| --- | --- |
| `PptWorkflowMode` | 仅有 `BASIC`、`IMAGE_ENHANCED`、`TEMPLATE_FILL`；`from()` 尚不识别 `beautify-pptx` |
| `PptWorkflowService.createJob` | `TEMPLATE_FILL` 走模板/内容分角色存储与异步分析；其余模式统一 `files` → `uploads/` 并启动 Agent |
| `PptJobNode` | BASIC/IMAGE_ENHANCED 使用大纲 + SVG 节点；`TEMPLATE_FILL` 使用 `TEMPLATE_ANALYZED` / `FILL_PLAN_*` / `OUTPUT_VALIDATED`；尚无 beautify 专属节点 |
| `DefaultAgentScopeWorkflowAgentFactory` | `TEMPLATE_FILL` 独立 prompt/工具；BASIC/IMAGE_ENHANCED 共用上游核心规则 + 模式补丁 + SVG 工具 |
| `PptConfirmationStagePolicy` / `PptConfirmationStageResolver` | 已支持 `outline_confirmation`、`plan_confirmation`、图片相关 stage、`template_fill_plan`；尚无 `beautify_plan_confirmation` |
| 上游脚本可达性 | 依赖配置的 `ppt-master.repo-path`；需确认其中包含 `beautify_identity.py`、`beautify_inventory.py`、`pptx_intake.py` 及标准 `finalize_svg.py` / `svg_to_pptx.py` |

当前即使上传 `.pptx`，在 `BASIC` 下也只会被当作普通资料导入，**不会**执行 beautify 的身份抽取、inventory 冻结与 1:1 排版契约。

### 2.2 设计结论

建议新增独立工作流模式 **`BEAUTIFY_PPTX`**（API 字符串 `beautify-pptx`），而不是给 `BASIC` 增加条件分支。理由：

- **输入契约不同**：必须且只能有一个源 `.pptx`；拒绝 `templateFile` / `templateConstraints`。
- **预处理不同**：init 前需按源画布选 `format`；`import-sources` / `pptx_intake` 产出 identity、slide library、图片清单；再用 `beautify_inventory.py` 汇总逐页冻结账本。
- **确认对象不同**：确认的是身份候选、忽略项、复杂图表/表格处理、可选品牌矢量复用与校验严格度，而不是内容大纲或模板选页计划。
- **硬约束不同**：内容与页映射不可协商；Agent 禁止文生图替换源图、内容改写与模板填充命令。
- **导出路径相同但校验不同**：复用 SVG → finalize → export；额外增加导出后逐页 read-back（`BEAUTIFY_OUTPUT_VALIDATED`）。

同时明确：**不**走 `TEMPLATE_FILL` 的 OOXML 路径；**不**启用 `IMAGE_ENHANCED` 文生图阶段（`image_usage` 语义对齐上游 `provided`，复用 `images/` 中已抽取图片并重新排版）。

推荐实现形态：

1. 服务端托管 intake / inventory（确定性、可恢复）。
2. Agent 读取分析产物，起草 beautify plan，经 `beautify_plan_confirmation` 门禁后，再写严格 1:1 的 `design_spec.md` / `spec_lock.md`。
3. 之后复用现有 BASIC 的 SVG 工具链（validate → notes split → finalize → export）。

---

## 3. 目标接口契约

首版保留现有接口的向后兼容性，仅在 `workflowMode=beautify-pptx` 时启用下列约束：

```text
POST /api/ppt-jobs (multipart/form-data)
  files: List<MultipartFile>            # 必填；必须且只能包含一个 .pptx
  workflowMode: beautify-pptx           # 或 beautify_pptx（解析时规范化）
  projectName: string?                  # 沿用
  instruction: string?                  # 软偏好（疏密/强调等）；不得覆盖硬约束
  format: string?                       # 首版：可作提示；实际画布以源 PPTX 探测结果为准并写回任务详情
  templateFile: 禁止                    # 出现则 400
  templateConstraints: 禁止             # 出现则 400
```

校验规则：

| 条件 | 处理 |
| --- | --- |
| `workflowMode` 为 `beautify-pptx` / `beautify_pptx` | 解析为 `BEAUTIFY_PPTX` |
| `files` 为空、多个文件、或唯一文件不是 `.pptx` | 400，拒绝创建 |
| 同时传入 `templateFile` | 400（与现有非 `TEMPLATE_FILL` 行为一致并显式文案） |
| 同时传入非空 `templateConstraints` | 400 |
| `format` 与源画布不一致 | 不阻断创建；以 `beautify_identity` / identity 中的 `canvas.aspect` 映射为准（≈1.778→`ppt169`，≈1.333→`ppt43`，其他取最近标准格式），并在详情中提示请求 `format` 未主导版式 |

模式解析扩展点（规划）：在 `PptWorkflowMode.from` 增加：

```text
case "beautify-pptx", "beautify_pptx" -> BEAUTIFY_PPTX;
```

创建后执行路径（规划）：类似 BASIC——校验通过后异步启动 Agent；但 Agent 首步应先完成（或消费服务端已完成的）画布探测、init、import、intake、inventory，而不是直接做大纲确认。

### 3.1 任务响应中的安全摘要字段

在 `PptJobResponse`（及必要时 SSE 事件）中新增摘要，**禁止**返回工作区绝对路径或原始槽位全文：

| 字段（建议名） | 含义 |
| --- | --- |
| `sourcePageCount` | 源幻灯片页数 |
| `canvas` / `resolvedFormat` | 探测并采用的画布标识（如 `ppt169`）及可选像素尺寸摘要 |
| `identityReady` | `analysis/<stem>.identity.json` 是否就绪 |
| `inventoryReady` | `analysis/beautify_inventory.json` 是否就绪 |
| `slideLibraryReady` | `analysis/<stem>.slide_library.json` 是否就绪 |
| `complexObjectAlertCount` | inventory 中 `needs_confirmation` 条目数（或等价告警数） |
| `beautifyPlanStatus` | 如 `none` / `draft` / `confirmed` / `rejected` |
| `outputValidationSummary` | 导出后校验摘要：页数是否匹配、文本/数据保真是否通过、告警数（不含敏感全文） |

可附带脱敏的源文件元数据：原始文件名、大小、内容类型（与 `TEMPLATE_FILL` 的 `template` 摘要同风格）。既有 `sources` 字段可继续暴露上传列表，但语义上该模式下唯一条目即美化源。

---

## 4. 确认阶段与 Checkpoint

### 4.1 确认阶段 `beautify_plan_confirmation`

新增 stage 字符串：`beautify_plan_confirmation`。

| 可确认项 | 推荐来源 | 默认倾向 |
| --- | --- | --- |
| 身份来源（theme vs observed） | `<stem>.identity.json` | 主题驱动用 theme，大量 run 覆盖用 observed；UI/载荷中两者都作候选 |
| 色板 / 字体 / 字号（含 `body_size`） | identity + 上游 confirm UI 语义 | 默认「复刻源 PPT」；允许用户改选备选候选 |
| 忽略项 `ignored` | inventory | 显式列出隐藏页/形状、母版独占文本、未捕获的裁剪/透明度/旋转等 |
| 复杂图表 / 表格处理 | inventory `needs_confirmation` | combo / 双轴 / waterfall、合并单元格等：用户选择接受降级或标记人工处理 |
| 可选品牌矢量素材复用 | `source_svg_import` 候选（若启用） | 默认不提升；仅确认过的非文本品牌/logo/纹样可晋升到 `icons/` |
| 校验严格度 | 页数 / 风险 | 推荐逐页 read-back；用户可设严格 / 标准 |

**不可由确认放宽**：

- 任意文本改写、增删、重排措辞
- 图表/表格**数据值**变更
- 页数或页序变更（含「为了排得下而拆页」）

实现上对齐 `template_fill_plan` 门禁模式：

- Agent 仅能写入 `status=draft` 的计划证据（建议持久化为 `analysis/beautify_plan.json` 或等价结构 + digest/version）。
- `/confirm` 经 `PptConfirmationStagePolicy` 校验 mode、version、digest、前置节点后，服务端提升为 confirmed，并推进 `BEAUTIFY_PLAN_CONFIRMED`。
- 驳回进入修订 attempt，不得静默继续写 `design_spec.md`。
- `PptConfirmationStageResolver` 增加：`case "beautify_plan_confirmation" -> BEAUTIFY_PLAN_CONFIRMED`。

前端可用结构化摘要展示候选色板/字体；服务侧 headless 环境以聊天/确认 API 等价字段为准（上游亦允许 confirm UI 不可用时的 chat 回退）。

### 4.2 建议 Checkpoint 链

```text
PROJECT_READY
  → BEAUTIFY_ANALYZED
  → BEAUTIFY_PLAN_DRAFTED
  → BEAUTIFY_PLAN_CONFIRMED
  → DESIGN_SPEC_WRITTEN
  → SPEC_LOCK_WRITTEN
  → SVG_OUTPUT_VALIDATED
  → SPEAKER_NOTES_SPLIT
  → SVG_FINALIZED
  → PPT_EXPORTED
  → BEAUTIFY_OUTPUT_VALIDATED
```

节点语义：

| 节点 | 适用模式 | 证据（规划） |
| --- | --- | --- |
| `PROJECT_READY` | 扩展包含 `BEAUTIFY_PPTX` | 项目已 init，源 PPTX 已导入 `sources/` |
| `BEAUTIFY_ANALYZED` | 仅 beautify | identity、slide_library、inventory（及可选 image_manifest）就绪 |
| `BEAUTIFY_PLAN_DRAFTED` | 仅 beautify | beautify plan 草案已写入（含忽略项、needs_confirmation 处理建议、身份推荐） |
| `BEAUTIFY_PLAN_CONFIRMED` | 仅 beautify（需确认） | `/confirm` 批准且 digest/version 匹配 |
| `DESIGN_SPEC_WRITTEN` / `SPEC_LOCK_WRITTEN` | 扩展包含 beautify | 与 BASIC 相同产物；页数必须等于源页数，§IX 逐页 1:1 |
| `SVG_OUTPUT_VALIDATED` → `SPEAKER_NOTES_SPLIT` → `SVG_FINALIZED` → `PPT_EXPORTED` | 复用 BASIC 节点 | 现有 SVG 工具证据 |
| `BEAUTIFY_OUTPUT_VALIDATED` | 仅 beautify | 导出 PPTX 经 `ppt_to_md`（或等价 OOXML 回读）通过页数/文本/数据保真摘要 |

与现有节点关系：

- **不**使用 `OUTLINE_*`、`PLAN_CONFIRMED`、`IMAGES_*`、`TEMPLATE_*` / `FILL_PLAN_*`、`OUTPUT_VALIDATED`（后者专属 template-fill）。
- 恢复逻辑必须按「当前模式适用节点集合」前进，不能按枚举声明顺序假定下一节点（已有 `TEMPLATE_FILL` 先例）。
- 若复用的 Executor 工具链强制依赖 `notes/total.md`，可在 `SPEC_LOCK_WRITTEN` 与 `SVG_OUTPUT_VALIDATED` 之间**额外**纳入已有 `NOTES_TOTAL_WRITTEN`（仍仅对 beautify 启用），不改变上表主干语义；实施时以工具契约实测为准并在阶段 0 冻结。

---

## 5. 分阶段计划（由简单到复杂）

### 阶段 0：可行性基线与契约冻结（已完成）

目的：在改 Java 生产代码前，用固定样例手工跑通上游闭环，冻结脚本版本与产物 schema。

当前状态：已完成。固定的 7 页脱敏合成样例已跑通身份探测、项目初始化、源文件导入、inventory、显式计划确认、SVG 校验、原生 PPTX 导出和 read-back；页数、页序、逐页文本、表格单元格及普通图表数据均通过结构比较。详细证据见 [`beautify-pptx-phase0-baseline.md`](./beautify-pptx-phase0-baseline.md)，冻结的服务消费契约与限制分别见 [`beautify-pptx-upstream-contract.md`](./beautify-pptx-upstream-contract.md) 和 [`beautify-pptx-compatibility-matrix.md`](./beautify-pptx-compatibility-matrix.md)。

工作项：

1. 用一份脱敏样例 PPTX（建议含：纯文本页、含图页、表格页、图表页、密排页、近空分隔页）在 `ppt-master` 仓库按 `beautify-pptx.md` 手工执行：`beautify_identity` → `init`/`import-sources` → intake 检查 → `beautify_inventory` → 人工确认计划 → `design_spec`/`spec_lock` → SVG → `finalize_svg` → `svg_to_pptx` → `ppt_to_md` 回读。
2. 固化命令版本、Python 依赖、输出目录与命名（`<stem>.identity.json`、`<stem>.slide_library.json`、`beautify_inventory.json`、`source_profile.json`、`images/image_manifest.json` 等）、失败返回码。
3. 冻结画布映射规则与兼容矩阵（字体缺失、combo 图、合并单元格、母版文本、隐藏页等）。
4. 确认服务配置的 `ppt-master.repo-path` 指向的版本包含上述脚本。
5. 冻结 API 字段名、错误码、单文件契约、以及「必须确认 beautify plan 后才能写 spec」的规则。

完成标准：端到端手工样例可导出且回读通过；形成兼容性/限制清单；**不修改**生产 Java 代码。

### 阶段 1：模式接入与纯文本 1:1 MVP

目的：新增模式与单 PPTX 校验，复用主 Agent/SVG 执行链路，先跑通「纯文本页」美化闭环。

工作项：

1. `PptWorkflowMode` 增加 `BEAUTIFY_PPTX`，扩展 `from()`；`createJob` 校验唯一 `.pptx`，拒绝 `templateFile`/`templateConstraints`。
2. `PptJobNode`：扩展 `PROJECT_READY`、`DESIGN_SPEC_*`、SVG 链与 `PPT_EXPORTED` 的 `applicableTo`；新增 beautify 专属节点（可先实现子集：`BEAUTIFY_ANALYZED` + 复用后续 SVG 节点）。
3. `DefaultAgentScopeWorkflowAgentFactory`：为 beautify 增加独立系统提示与工具边界——允许读分析产物与写 spec/SVG；禁止 `image_gen`、template-fill 命令、内容改写指令。
4. 首版可由 Agent 在工具约束下调用 intake/inventory，或由服务端最小包装器执行确定性脚本后把摘要注入 Agent 上下文。
5. MVP 范围限定纯文本 1:1 重排；复杂图表/表格可失败并给出明确错误，不做静默糊弄。

完成标准：API 可创建 `beautify-pptx` 任务，纯文本样例可下载结果且页数/关键文本回读一致；`basic` / `image-enhanced` / `template-fill` 回归通过。

### 阶段 2：服务端托管分析、Checkpoint 与可观察性

目的：intake/inventory 由服务端托管，具备结构化进度、失败恢复、超时与错误码。

工作项：

1. 落地上节完整 checkpoint 链（至少到 `BEAUTIFY_ANALYZED` 与导出后 `BEAUTIFY_OUTPUT_VALIDATED` 的骨架）。
2. 创建后异步：探测画布 → init（正确 format）→ import-sources → 必要时 `pptx_intake` → `beautify_inventory`；写入节点证据与安全摘要字段。
3. 调整 resume：按 beautify 节点集合恢复；分析失败回到 `PROJECT_READY` 或分析前，不覆盖源上传。
4. 增加文件大小限制、子进程超时、并发限制；错误码示例：`BEAUTIFY_IDENTITY_FAILED`、`BEAUTIFY_INTAKE_FAILED`、`BEAUTIFY_INVENTORY_FAILED`、`BEAUTIFY_VALIDATION_FAILED`。
5. SSE/详情暴露第 3.1 节安全字段，不泄露绝对路径。

完成标准：分析失败可定位；可从 `BEAUTIFY_ANALYZED` 恢复；状态足以支撑前端展示。

### 阶段 3：Beautify Plan 确认闭环与身份锁定

目的：专属计划 + 确认/修订 + 身份候选锁定后再生成 spec/SVG。

工作项：

1. Agent 基于 inventory/identity 生成计划草案；填充 `ignored` / `needs_confirmation` 的判断（脚本只产出空数组，由计划阶段填写）。
2. 通过 `request_plan_confirmation`，`stage=beautify_plan_confirmation`，携带版本、digest、有界摘要（页数、身份推荐、告警列表，不含全文）。
3. 扩展 `PptConfirmationStagePolicy` / `Resolver`；未确认不得写最终 `design_spec.md`/`spec_lock.md`。
4. 确认后 Strategist 路径：页数锁定为源页数；文本从 `sources/<stem>.md` 逐页转录；跳过会改写内容的自由大纲流程。
5. 确保确认结果不能把内容/页映射「放宽」写入计划——服务端校验拒绝此类字段变更。

完成标准：典型样例能自动出可解释计划；未确认无法导出；驳回修订路径可用。

### 阶段 4：图片、表格、图表与导出校验

目的：覆盖多媒体对象，并做导出后逐页 read-back。

工作项：

1. 图片：复用 `images/` + `image_manifest.json` 绑定；按新布局重排位置/尺寸；不调用文生图；明确裁剪/旋转等信息可能丢失并记入 `ignored`。
2. 表格/图表：从 `slide_library` / inventory 内联冻结数据重生为原生 SVG；combo/双轴等按确认策略降级并保留告警。
3. 可选：`pptx_to_svg` + `extract_svg_assets` 生成只读风格参考；仅确认后的品牌类候选晋升 `icons/`。
4. 导出后执行 `ppt_to_md`（或服务内 OOXML 回读）写入 `validation/beautify-readback.json`（建议名），校验页数、文本保真、数据保真；推进 `BEAUTIFY_OUTPUT_VALIDATED`。
5. 维护支持矩阵文档（类似 template-fill stage4）。

完成标准：文本/图/表/常见图表均有明确支持或明确失败；校验报告可被前端/运维读取。

### 阶段 5：生产化、灰度与运维

目的：可安全灰度并可长期维护。

工作项：

1. Feature flag（如 `ppt-master.beautify-pptx-enabled`）；初期限测试租户/管理员。
2. 源 PPTX 生命周期：保留期、下载权限、清理、审计；禁止跨任务暴露品牌素材。
3. 前端入口：单文件上传区 + 文案说明「内容与页序冻结，仅重排版」；展示分析摘要与可确认计划。
4. 遥测：创建量、分析/确认/导出耗时、失败码、确认拒绝率、校验失败率、恢复成功率。
5. 脱敏诊断包与运维手册（依赖检查、字体问题、上游升级/回滚、兼容矩阵）。
6. 灰度阈值与 canary 验收清单（对齐 template-fill 阶段五做法，另立文档，不在本文件展开实现细节）。

完成标准：灰度指标达标、故障可诊断可回滚、前后端与运维契约完整。

---

## 6. 建议的端到端执行流

```text
创建任务（唯一 source.pptx，workflowMode=beautify-pptx）
  → 校验单文件 / 拒绝 template 字段
  → beautify_identity 探测画布 → init(匹配 format) → import-sources
  → 确认 intake bundle（identity / slide_library / md / images）
  → beautify_inventory 生成逐页冻结账本
  → [BEAUTIFY_ANALYZED]
  → Agent 读取产物，填充 ignored / needs_confirmation，起草 beautify plan
  → [BEAUTIFY_PLAN_DRAFTED] → 用户确认/驳回（beautify_plan_confirmation）
  → [BEAUTIFY_PLAN_CONFIRMED]
  → 写入严格 1:1 的 design_spec.md / spec_lock.md
  → （如需）notes/total.md
  → SVG 生成与校验 → 备注拆分 → finalize_svg → svg_to_pptx
  → [PPT_EXPORTED]
  → ppt_to_md / read-back 校验
  → [BEAUTIFY_OUTPUT_VALIDATED]
  → 下载 exports/*.pptx
```

失败恢复原则：

| 失败点 | 恢复策略 |
| --- | --- |
| 身份/intake/inventory | 从 `PROJECT_READY` 或分析前重试；不覆盖原始上传 |
| 计划校验 / 用户驳回 | 回到计划修订；保留分析产物 |
| SVG / finalize / export | 从最近成功的 spec lock 或 SVG 节点继续 |
| 输出校验失败 | 保留导出物与校验报告；按失败原因回到 SVG 或计划修订；不得宣称成功 |

Agent **不得**用 `--force` 或改写确认状态绕过门禁。

---

## 7. 测试策略

| 层级 | 覆盖重点 |
| --- | --- |
| 单元测试 | 模式解析（`beautify-pptx`/`beautify_pptx`）、单文件契约、拒绝 template 字段、节点 `applicableTo`、确认 stage 映射、确认防篡改（version/digest）、错误码 |
| 服务集成测试 | multipart 创建、子进程参数（ProcessBuilder 数组）、超时、SSE 安全字段、下载、resume |
| 上游契约测试 | 固定样例的 identity/inventory schema、画布映射、inventory 字段稳定性 |
| 端到端测试 | 纯文本 1:1、含图重排、表格/图表数据保真、密排页告警、用户驳回再确认、校验失败 |
| 回归测试 | `basic`、`image-enhanced`、`template-fill` 的创建/确认/导出/恢复不受影响 |
| 安全测试 | 路径穿越、伪造扩展名、超大文件、恶意文件名、跨任务访问、命令注入 |

样例必须脱敏。黄金断言以页数、关键文本回读、图表数据摘要、关系完整性为主，避免脆弱的整文件字节对比。

---

## 8. 风险与决策点

| 风险/决策 | 建议 |
| --- | --- |
| 源字号识别偏差（`observed.sizes_pt` 含标题/脚注噪声；`theme.sizes.body` 常偏大） | 阶段 0 固化 `body_size` 推荐算法与 sanity range；必须经确认，禁止静默乱缩 |
| 隐藏页 / 母版文本 | 列入 `ignored` 并在确认中展示；不默认为正文 |
| 复杂图表、合并单元格 | 兼容矩阵 + `needs_confirmation`；降级须用户可见 |
| 超密页在 1:1 下无法「变轻松」 | 明确 v1 天花板：只改善层级/留白，不拆页；告警后由用户接受或改走主流程 |
| 字体在导出环境缺失 | 确认时使用 PPT-safe 字体栈；运维手册记录替换策略 |
| 图片裁剪/透明度/旋转损失 | 记入 ignored；不做静默「看起来一样」的承诺 |
| 上游 schema 漂移 | 固定经验证的 ppt-master 版本；为 identity/inventory 做契约测试后再升级 |
| 与 template-fill / 主流程路由混淆 | API 显式 `workflowMode`；前端文案对照第 1.2 节；服务不做模糊自动改道 |
| Agent 越权改内容或调文生图 | 模式专属 prompt + 工具白名单 + 确认后服务端再校验页数/文本契约 |

---

## 9. 推荐里程碑

1. **M0：可行性通过（已达成）** — 阶段 0 已完成，兼容矩阵与冻结契约已形成。
2. **M1：文本 MVP** — 阶段 1 完成，纯文本 1:1 美化可创建、导出、回读。
3. **M2：可确认 Beta** — 阶段 2–3 完成，具备托管分析、beautify plan 确认、恢复与观测。
4. **M3：多媒体完整** — 阶段 4 完成，覆盖图片/表格/常见图表与导出校验。
5. **M4：生产发布** — 阶段 5 的灰度、权限、指标与运维条件达标。

实施时每个阶段应独立提交、独立测试和独立审查；涉及 API/任务状态变化时，按项目约定在改动前后执行文档同步流程。

---

## 10. 关键扩展点清单（实施索引）

便于实现时对照现有代码，**本规划阶段不修改这些文件**，仅列出预期触点：

| 扩展点 | 预期改动方向 |
| --- | --- |
| `PptWorkflowMode` | 新增 `BEAUTIFY_PPTX`；`from` 识别 `beautify-pptx` |
| `PptWorkflowService.createJob` / resume | 单 PPTX 校验；可选服务端预处理调度；resume 按模式节点集 |
| `PptJobNode` | 新节点 + 扩展共享节点的 `applicableTo` |
| `DefaultAgentScopeWorkflowAgentFactory` | beautify 专用 prompt/工具白名单 |
| `PptConfirmationStagePolicy` | `beautify_plan_confirmation` 评估 |
| `PptConfirmationStageResolver` | stage → `BEAUTIFY_PLAN_CONFIRMED` |
| `PptJobResponse` | 第 3.1 节安全摘要字段 |
| 配置 / feature flag | 类似 `templateFillEnabled` 的 beautify 开关 |

上游只读事实源（部署仓库内）：

- `skills/ppt-master/workflows/beautify-pptx.md`
- `skills/ppt-master/workflows/routing.md`
- `skills/ppt-master/scripts/beautify_identity.py`
- `skills/ppt-master/scripts/beautify_inventory.py`
- `skills/ppt-master/scripts/pptx_intake.py`
- `skills/ppt-master/scripts/project_manager.py`
- `skills/ppt-master/scripts/finalize_svg.py`
- `skills/ppt-master/scripts/svg_to_pptx.py`

---

## 11. 文档状态

| 项 | 状态 |
| --- | --- |
| 规划文档 | 本文档；已根据阶段 0 验收结果更新 |
| 阶段 0 | 已完成；基线、上游契约与兼容性矩阵已冻结 |
| 阶段 1–2 | 已完成并归档（text MVP + managed analysis） |
| 阶段 3 | 已完成并归档（`add-beautify-pptx-plan-confirmation`：计划确认闭环与身份锁定） |
| 阶段 4 | 已完成并归档（`2026-07-29-add-beautify-pptx-media-validation`：多媒体对象 + 导出 read-back） |
| 阶段 5 | 已完成实现（`productionize-beautify-pptx`：灰度门禁、授权、生命周期、遥测、诊断、第一方页面与运维资产）；生产 canary/扩容观察见任务 8.5 |
| OpenSpec change | 阶段 0–4 已归档；阶段 5 change `productionize-beautify-pptx` 实现完成（canary 现场观察待部署后签署） |

本文档结构对齐 [`template-fill-pptx-integration-plan.md`](./template-fill-pptx-integration-plan.md) 的分阶段深度，语气与目标表述参考 [`PPT_OUTLINE_INTEGRATION_PLAN.md`](./PPT_OUTLINE_INTEGRATION_PLAN.md)。
