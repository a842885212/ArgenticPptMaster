# `native-enhance-pptx` 接入规划

## 1. 目标与边界

目标是在现有 `BASIC` / `IMAGE_ENHANCED` markdown-first/SVG 导出链路、`TEMPLATE_FILL` 模板填充链路、以及 `BEAUTIFY_PPTX` 布局重做链路完全兼容的前提下，新增「原生增强」能力：用户上传**一份已完成**的 `.pptx`；服务在**可见幻灯片内容与布局保持稳定**的前提下，以 **OOXML 追加/补丁**方式写入演讲者备注、旁白音频、自动换页计时与页面切换效果，最终产出一份仍可在 PowerPoint 中打开的增强 PPTX。

该能力应对齐上游 `ppt-master` 的 `native-enhance-pptx` 工作流（部署时以配置项 `ppt-master.repo-path` 下的 `skills/ppt-master/workflows/native-enhance-pptx.md` 为准）。兼容入口 `native-narration-pptx.md` 已明确废弃独立执行路径，统一走稳定 CLI：

```text
native_enhance_pptx.py init <source.pptx> [--name <slug>]
native_enhance_pptx.py plan <project>
# 用户确认 enhancement_plan 后：
#   Agent/服务生成 notes/*.md（若启用 notes）
#   用户确认语音后端/音色后：notes_to_audio.py（若启用 audio）
native_enhance_pptx.py validate <project>
native_enhance_pptx.py apply <project>   # 可选 --transition / --overwrite 等
# 导出后 read-back：
ppt_to_md.py <exports/*_enhanced.pptx> -o validation/readback.md
```

实现上，`native_enhance_pptx.py` 是对 `native_narration_pptx.py` 的稳定包装入口；服务侧应调用前者，避免绑定已退役工作流名。

本规划**不包含**以下范围：

- 对源 PPTX 做布局重做或 SVG 重生（那是 [`beautify-pptx`](./beautify-pptx-integration-plan.md)）。
- 克隆模板页并替换文案（那是已落地的 [`template-fill-pptx`](./template-fill-pptx-integration-plan.md) / `TEMPLATE_FILL`）。
- 允许拆页 / 合页 / 改页序 / 重写大纲的「主流程重构」（应走 `ppt_to_md` + `BASIC`/`IMAGE_ENHANCED`）。
- 对已由 PPT Master 生成、具备 `svg_output/` 的项目做旁白（上游应走 `generate-audio`，不是本模式）。
- V1 之外的 planned 模块：对象级动画、可见水印/页脚/logo、背景音乐、媒体压缩、delivery.check 等（上游文档标为 Planned；本规划仅预留扩展点）。
- 改造已有 `basic`、`image-enhanced`、`template-fill`、`beautify-pptx` 的既有行为语义。

### 1.1 三项硬约束

| 约束 | 含义 |
| --- | --- |
| 内容与布局稳定 | 不得改写既有可见文本、形状、图片、图表、表格、母版或版式；备注/旁白可解释幻灯片内容，但不得编造无依据事实 |
| OOXML 追加式补丁 | 仅可追加/更新 notes、media、timings、transitions、关系与 content-type 记录；不得整页重生或破坏非目标关系 |
| 禁止 SVG 管线 | 不得运行 `pptx_template_import.py`、不得创建 `svg_output/`、不得执行 `finalize_svg.py` / `svg_to_pptx.py` |

上述三项**不可**被确认阶段放宽。确认只决定「启用哪些模块、语音后端/音色、切换效果与时长、是否在无音频页也写 transition」等可协商范围。

### 1.2 与相邻能力的区分

| 能力 | 输入意图 | 内容 | 页结构 | 视觉 | 实现路径 |
| --- | --- | --- | --- | --- | --- |
| **native-enhance-pptx（本规划）** | 「成品上加备注 / 旁白 / 自动播放 / 切换」 | 可见内容冻结 | 页数/页序冻结 | 布局保持稳定 | 独立模式 + **直接 OOXML patch** |
| **beautify-pptx** | 「美化 / 重新排版，内容别动」 | 冻结 | 1:1 冻结 | 布局重做；身份确认后继承 | 独立模式 + **复用** Agent/SVG/finalize/export |
| **template-fill-pptx** | 「用这份模板填新内容」 | 新内容填入模板槽位 | 由填充计划决定选页/顺序 | **保留**模板设计壳 | `TEMPLATE_FILL`，OOXML `analyze → apply`，**不**走 SVG |
| **主流程（BASIC / IMAGE_ENHANCED）** | 资料成稿 / 允许重构故事线 | 可改写、可重大纲 | 可拆合重排 | 八确认或大纲确认后新建视觉 | 现有 markdown → SVG 主链路 |
| **generate-audio（上游）** | 已有 SVG 生成项目需要旁白 | 基于 notes 的音频 | 沿用已生成项目 | 不改版式 | 仅适用于带 `svg_output/` 的 PPT Master 项目，**不**作为本服务新模式 |

路由判定（对齐上游 `routing.md` §3）：

- 成品 PPTX + 仅要备注/旁白/自动换页/页面切换，且布局保持稳定 → **本模式**。
- 成品 PPTX + 美化/重排版 → `beautify-pptx`。
- 模板 PPTX + 新内容填充 → `template-fill-pptx`。
- 允许改页数/页序/大纲 → 主流程，**不是**本模式。

### 1.3 明确非目标（Non-goals）

- **不做 beautify 语义**：不重排版、不重生 SVG、不改 visible design。
- **不做 template-fill 语义**：不选模板页、不填槽位、不接受 `templateFile` / `templateConstraints`。
- **不做主流程大纲/八确认**：不生成 `design_spec.md` / `spec_lock.md`，不走 `OUTLINE_*` / `PLAN_CONFIRMED`。
- **不把 BASIC 上传的 `.pptx` 自动改道**：必须显式 `workflowMode=native-enhance-pptx`。
- **V1 不做对象级动画 / 水印 / 背景音乐**：用户若提出，应明确为未来模块或另立任务，不得 silently 改 visible slides。

---

## 2. 现状与设计结论

### 2.1 当前服务状态

| 组件 | 现状 |
| --- | --- |
| `PptWorkflowMode` | 已有 `BASIC`、`IMAGE_ENHANCED`、`TEMPLATE_FILL`、`BEAUTIFY_PPTX`、`NATIVE_ENHANCE_PPTX`；`from()` 识别 `native-enhance-pptx` / `native_enhance_pptx` |
| `PptWorkflowService.createJob` | `TEMPLATE_FILL` 分角色存储并分析；`BEAUTIFY_PPTX` 走托管分析 + 灰度门禁；`NATIVE_ENHANCE_PPTX` 单文件校验后托管 init/draft 并等待 enhancement plan 确认；其余模式统一 `files` → `uploads/` 并启动 Agent |
| `PptJobNode` | BASIC/IMAGE_ENHANCED 使用大纲 + SVG 节点；`TEMPLATE_FILL` 使用 `TEMPLATE_*` / `FILL_PLAN_*`；`BEAUTIFY_PPTX` 使用 `BEAUTIFY_*` + SVG 链；`NATIVE_ENHANCE_PPTX` 使用 `NATIVE_ENHANCE_*` / `ENHANCEMENT_PLAN_*` / `NOTES_GENERATED` / `AUDIO_VOICE_CONFIRMED` |
| `DefaultAgentScopeWorkflowAgentFactory` | `TEMPLATE_FILL` / `BEAUTIFY_PPTX` / `NATIVE_ENHANCE_PPTX` 已有独立 prompt/工具；BASIC/IMAGE_ENHANCED 共用 SVG 工具 |
| `PptConfirmationStagePolicy` / `PptConfirmationStageResolver` | 已支持 `outline_confirmation`、`plan_confirmation`、图片相关 stage、`template_fill_plan`、`beautify_plan_confirmation`、`enhancement_plan_confirmation`、`audio_voice_confirmation` |
| 上游脚本可达性 | 依赖配置的 `ppt-master.repo-path`；需确认其中包含 `native_enhance_pptx.py`（包装）、`native_narration_pptx.py`（实现）、`notes_to_audio.py`、`ppt_to_md.py`，以及 `ffprobe`（音频时长计时） |

当前即使上传 `.pptx`，在 `BASIC` / `BEAUTIFY_PPTX` / `TEMPLATE_FILL` 下也不会执行 native enhance 的 `init → plan → notes/audio → apply` 契约。beautify 规划文档此前将该能力标注为「本服务暂不接入」——本规划即为其正式接入蓝图；**阶段 1–5 已完成**（含 notes-only、托管分析、双确认门禁、完整 V1 旁白模块与生产灰度/运维契约）。

### 2.2 设计结论

建议新增独立工作流模式 **`NATIVE_ENHANCE_PPTX`**（API 字符串 `native-enhance-pptx`），而不是给 `BASIC` / `BEAUTIFY_PPTX` / `TEMPLATE_FILL` 增加条件分支。理由：

- **输入契约不同**：必须且只能有一个源 `.pptx`；拒绝 `templateFile` / `templateConstraints`；语义是「成品增强」而非「资料导入」或「模板填充」。
- **项目模型不同**：上游 `project.json` 的 `schema` 为 `native_pptx_enhancement_project.v1`，`kind` 为 `native_pptx_enhancement`；产物目录含 `notes/`、`audio/`、`analysis/enhancement_plan.json`、`analysis/slide_index.json`，**没有** `svg_output/`。
- **确认对象不同**：确认的是模块开关（notes/audio/timings/transitions）与（若启用音频）语音后端/音色，而不是内容大纲、模板选页计划或 beautify 身份候选。
- **硬约束不同**：可见幻灯片冻结；执行路径是 OOXML unzip → patch → rezip，禁止 SVG 重生。
- **与 template-fill 虽同属 OOXML，但语义对立**：template-fill 克隆/替换槽位内容；native-enhance 只追加备注与媒体，不改可见页内容。

推荐实现形态：

1. 服务端托管 `init` / `plan` / `validate` / `apply`（确定性、可恢复）；`ppt_to_md` 在 init 内或随后写入 `sources/<stem>.md` 供备注生成理解。
2. Agent（或服务侧规划器）仅负责：基于 slide markdown 起草忠实备注、填充 `enhancement_plan` 草案摘要、触发确认门禁；**不得**调用 SVG 工具或 template-fill 命令。
3. 音频生成调用 `notes_to_audio.py`（默认 edge 音色，可确认覆盖）；`apply` 前必须 `status=confirmed`，服务端禁止 Agent 使用 `--force`。

---

## 3. 目标接口契约

首版保留现有接口的向后兼容性，仅在 `workflowMode=native-enhance-pptx` 时启用下列约束：

```text
POST /api/ppt-jobs (multipart/form-data)
  files: List<MultipartFile>            # 必填；必须且只能包含一个 .pptx
  workflowMode: native-enhance-pptx     # 或 native_enhance_pptx（解析时规范化）
  projectName: string?                  # 沿用；映射上游 --name slug（需 ASCII 化规则与阶段 0 冻结）
  instruction: string?                  # 软偏好（语气、是否要旁白等）；不得覆盖硬约束
  format: string?                       # 首版：可记录但不主导版式；画布继承源 PPTX
  enhanceModules: string?               # 可选 JSON：模块开关与默认 transition 提示（见下）
  templateFile: 禁止                    # 出现则 400
  templateConstraints: 禁止             # 出现则 400
```

可选 `enhanceModules`（建议名，阶段 0 冻结正式字段名）示例：

```json
{
  "notes": true,
  "audio": true,
  "timings": true,
  "transitions": {
    "enabled": true,
    "effect": "fade",
    "duration": 0.5,
    "applyWithoutAudio": false
  },
  "narrationPadding": 0.4
}
```

未传时对齐上游 V1 默认：四模块均倾向启用；`transitions.effect=fade`、`duration=0.5`、`narrationPadding=0.4`。字段仅为**创建时偏好**；最终以确认后的 `enhancement_plan.json` 为准。

校验规则：

| 条件 | 处理 |
| --- | --- |
| `workflowMode` 为 `native-enhance-pptx` / `native_enhance_pptx` | 解析为 `NATIVE_ENHANCE_PPTX` |
| `files` 为空、多个文件、或唯一文件不是 `.pptx` | 400，拒绝创建 |
| 同时传入 `templateFile` | 400 |
| 同时传入非空 `templateConstraints` | 400 |
| `enhanceModules` JSON 非法或含 V1 外模块键（如 `animation`） | 400，并提示非目标范围 |
| `format` 与源画布不一致 | 不阻断创建；详情中提示 `format` 未参与版式转换 |

模式解析扩展点（规划）：在 `PptWorkflowMode.from` 增加：

```text
case "native-enhance-pptx", "native_enhance_pptx" -> NATIVE_ENHANCE_PPTX;
```

创建后执行路径（规划）：类似 `TEMPLATE_FILL` / `BEAUTIFY_PPTX`——校验通过后由服务端异步准备项目（`init` + draft `plan`），再进入确认与（可选）Agent 备注/音频生成，而不是启动 SVG 主 Agent。

### 3.1 任务响应中的安全摘要字段

在 `PptJobResponse`（及必要时 SSE 事件）中新增摘要，**禁止**返回工作区绝对路径、备注全文或原始音频路径：

| 字段（建议名） | 含义 |
| --- | --- |
| `sourcePageCount` | 源幻灯片页数（来自 `slide_index` / validate report） |
| `enhancementPlanStatus` | `none` / `draft` / `confirmed` / `rejected` |
| `enabledModules` | 已确认或草案中 `enabled=true` 的模块名列表 |
| `notesCoverage` | `{ ready, total }`；未启用 notes 时可省略 |
| `audioCoverage` | `{ ready, total }`；未启用 audio 时可省略 |
| `audioVoiceStatus` | `none` / `pending_confirmation` / `confirmed` / `skipped` |
| `transitionSummary` | 脱敏：effect、duration、是否 applyWithoutAudio（不含路径） |
| `validateReportSummary` | `plan_status`、missing notes/audio 计数（不含逐页全文） |
| `outputValidationSummary` | 导出后：页数是否匹配、可见内容未故意改动、备注/媒体存在性摘要 |
| `exportFileName` | 最终 `*_enhanced.pptx` 文件名（若已导出） |

可附带脱敏的源文件元数据：原始文件名、大小、内容类型（与 beautify / template-fill 摘要同风格）。既有 `sources` 字段可继续暴露上传列表，但语义上该模式下唯一条目即增强源。

---

## 4. 确认阶段与 Checkpoint

### 4.1 确认阶段 `enhancement_plan_confirmation`

新增 stage 字符串：`enhancement_plan_confirmation`。

| 可确认项 | 推荐来源 | 默认倾向 |
| --- | --- | --- |
| `notes` 启用与否 | `enhancement_plan.json` | 启用；从幻灯片内容生成口述备注 |
| `audio` 启用与否 | 同上 | 用户要旁白/自动播放时启用 |
| `timings` 启用与否 | 同上 | 与 audio 同开；按音频时长 + padding 自动换页 |
| `transitions` 效果 / 时长 / 无音频页是否应用 | 同上 + 上游 `TRANSITIONS` 白名单 | 默认 `fade` 0.5s；`apply_without_audio` 默认 false |
| `narration_padding` | plan `timings` | 默认 `0.4` |

**不可由确认放宽**：

- 任意可见文本/形状/图片/图表/表格改写
- 页数或页序变更
- 启用 V1 外模块（对象动画、水印、背景音乐等）
- 通过确认载荷静默注入 `--force` 或跳过 plan 确认

实现上对齐 `template_fill_plan` / `beautify_plan_confirmation` 门禁模式：

- Agent/服务仅能写入 `status=draft` 的 `analysis/enhancement_plan.json`（禁用模块保留且 `enabled=false`，不得删除键）。
- `/confirm` 经 `PptConfirmationStagePolicy` 校验 mode、version、digest、前置节点后，服务端提升为 `confirmed`，并推进 `ENHANCEMENT_PLAN_CONFIRMED`。
- 驳回进入修订 attempt，不得静默继续生成 notes/audio 或 `apply`。
- `PptConfirmationStageResolver` 增加：`case "enhancement_plan_confirmation" -> ENHANCEMENT_PLAN_CONFIRMED`。

### 4.2 确认阶段 `audio_voice_confirmation`（当 `audio.enabled`）

新增 stage 字符串：`audio_voice_confirmation`（仅 audio 模块启用时出现）。

| 可确认项 | 推荐来源 | 默认倾向 |
| --- | --- | --- |
| TTS 后端 | `notes_to_audio` / `generate-audio` 同款交互 | 默认 `edge`，除非用户指定云厂商或克隆音色 |
| 音色 `voice` | `--list-voices --locale <locale>` | 按备注主语言推荐（如 `zh-CN-YunjianNeural`） |
| `rate` / 其它设置 | 上游默认 | 如 `+0%` |
| 是否写回嵌入 PPTX | 工作流默认 | V1 默认嵌入（本模式目标即增强后的 PPTX） |

**⛔ BLOCKING**：未确认不得运行 `notes_to_audio.py`。确认结果写入 `project.json` 的 `audio` 段（对齐上游）。

### 4.3 建议 Checkpoint 链

```text
PROJECT_READY
  → NATIVE_ENHANCE_INITIALIZED
  → ENHANCEMENT_PLAN_DRAFTED
  → ENHANCEMENT_PLAN_CONFIRMED
  → NOTES_GENERATED                 # 仅 notes.enabled
  → AUDIO_VOICE_CONFIRMED           # 仅 audio.enabled
  → AUDIO_GENERATED                 # 仅 audio.enabled
  → NATIVE_ENHANCE_VALIDATED        # validate coverage
  → PPT_EXPORTED                    # apply → exports/*_enhanced.pptx
  → NATIVE_ENHANCE_OUTPUT_VALIDATED # ppt_to_md read-back
```

节点语义：

| 节点 | 适用模式 | 证据（规划） |
| --- | --- | --- |
| `PROJECT_READY` | 扩展包含 `NATIVE_ENHANCE_PPTX` | 上传已落盘；可开始 `init` |
| `NATIVE_ENHANCE_INITIALIZED` | 仅 native-enhance | `project.json`、`sources/*.pptx`、`sources/*.md`、`analysis/slide_index.json`、草案 `enhancement_plan.json` 就绪 |
| `ENHANCEMENT_PLAN_DRAFTED` | 仅 native-enhance | 计划草案已写入（含模块覆盖率摘要） |
| `ENHANCEMENT_PLAN_CONFIRMED` | 仅 native-enhance（需确认） | `/confirm` 批准且 digest/version 匹配 |
| `NOTES_GENERATED` | 条件节点 | `notes/001.md`… 覆盖目标页；口播文案无舞台指令/表格 |
| `AUDIO_VOICE_CONFIRMED` | 条件节点 | 语音配置已确认并写入 `project.json` |
| `AUDIO_GENERATED` | 条件节点 | `audio/001.mp3`… 与 notes stem 对齐 |
| `NATIVE_ENHANCE_VALIDATED` | 仅 native-enhance | `validation/report.json` 无缺失 notes/audio（对已启用模块） |
| `PPT_EXPORTED` | 扩展包含本模式 | `exports/<stem>_enhanced.pptx` 唯一产出 |
| `NATIVE_ENHANCE_OUTPUT_VALIDATED` | 仅 native-enhance | read-back：页数一致、可见内容无故意变更、备注/媒体存在性摘要 |

与现有节点关系：

- **不**使用 `OUTLINE_*`、`PLAN_CONFIRMED`、`IMAGES_*`、`TEMPLATE_*` / `FILL_PLAN_*`、`BEAUTIFY_*`、`DESIGN_SPEC_*`、`SVG_*`、`OUTPUT_VALIDATED`（后者专属 template-fill）。
- 恢复逻辑必须按「当前模式适用节点集合」前进（已有 `TEMPLATE_FILL` / `BEAUTIFY_PPTX` 先例）。
- 若某次任务禁用 audio，则跳过 `AUDIO_*` 节点；若禁用 notes，则跳过 `NOTES_GENERATED`（且不得启用依赖 notes 的 audio，除非阶段 0 明确允许「仅预置音频文件」——首版建议：audio 依赖 notes）。

---

## 5. 分阶段计划（由简单到复杂）

> **文档状态**：阶段 0–5 已完成。阶段 5 已落地 production feature flag、生命周期/审计清理、低基数遥测、脱敏诊断、`/native-enhance-pptx/` 静态入口与 `docs/ops/` canary 契约；M4 已完成。

### 阶段 0：可行性基线与契约冻结（已完成）

目的：在改 Java 生产代码前，用固定样例手工跑通上游闭环，冻结脚本版本与产物 schema。

工作项：

1. 用一份脱敏样例 PPTX（建议含：封面、正文、表格/图表页、已有备注页、无备注页）在 `ppt-master` 仓库按 `native-enhance-pptx.md` 手工执行：`init` → `plan` → 人工确认计划 → 编写 notes →（可选）语音确认 → `notes_to_audio` → `validate` → `apply` → `ppt_to_md` 回读。
2. 固化命令版本、Python 依赖、`ffprobe` 依赖、输出目录与命名（`project.json`、`enhancement_plan.json`、`slide_index.json`、`notes/NNN.md`、`audio/NNN.mp3`、`exports/*_enhanced.pptx`、`validation/report.json`）、失败返回码（如 validate 缺覆盖时 exit `2`）。
3. 冻结 schema：`native_pptx_enhancement_project.v1`、`native_pptx_enhancement_plan.v1`、`native_pptx_enhancement_validation.v1`、`native_pptx_enhancement_slide_index.v1`；记录与 legacy `native_narration_pptx_project.v1` 的兼容边界。
4. 确认服务配置的 `ppt-master.repo-path` 指向的版本包含 `native_enhance_pptx.py` 及依赖。
5. 冻结 API 字段名、错误码、单文件契约、双确认门禁（plan + audio voice）、以及「禁止 `--force` / 禁止 SVG」规则。
6. 形成兼容矩阵：源已含备注时的覆盖策略（替换 vs 保留）、无音频时 transition-only、超大媒体、字体/隐藏页等（delivery.check 仍为未来模块，仅记录已知限制）。

完成标准：端到端手工样例可导出且回读通过；形成兼容性/限制清单与上游契约文档；**不修改**生产 Java 代码。

**阶段 0 完成记录**：上游 CLI 在配置的 `ppt-master.repo-path` 上对合成 fixture 实测通过（notes-only + edge 音频全链路）；OpenSpec change `native-enhance-pptx-phase-0` 已归档。产出：

- [`docs/native-enhance-pptx-upstream-contract.md`](./native-enhance-pptx-upstream-contract.md)
- [`docs/native-enhance-pptx-compatibility-matrix.md`](./native-enhance-pptx-compatibility-matrix.md)
- [`docs/native-enhance-pptx-phase0-baseline.md`](./native-enhance-pptx-phase0-baseline.md)
- fixture：`src/test/resources/native-enhance-pptx/`

### 阶段 1：模式接入与备注-only MVP（已完成）

目的：新增模式与单 PPTX 校验，先跑通「仅 notes 模块」的 OOXML 增强闭环（可暂不启用 audio/timings）。

工作项：

1. `PptWorkflowMode` 增加 `NATIVE_ENHANCE_PPTX`，扩展 `from()`；`createJob` 校验唯一 `.pptx`，拒绝 `templateFile`/`templateConstraints`。
2. `PptJobNode`：扩展 `PROJECT_READY` / `PPT_EXPORTED` 的 `applicableTo`；新增至少 `NATIVE_ENHANCE_INITIALIZED`、`ENHANCEMENT_PLAN_*`、`NOTES_GENERATED`、`NATIVE_ENHANCE_VALIDATED`、`NATIVE_ENHANCE_OUTPUT_VALIDATED` 骨架（MVP 可将 plan 确认做成调试入口或人工写入 confirmed，完整门禁放到阶段 3）。
3. 新增受限命令执行器（类比 `PptTemplateFillCommandExecutor`）：仅允许在工作区内运行 `native_enhance_pptx.py` 与只读 `ppt_to_md.py`；`ProcessBuilder` 参数数组，不拼接 shell。
4. MVP 路径：服务端 `init` → 写入/接受 confirmed plan（notes=true，audio/timings=false）→ 注入预置或最小生成的 `notes/*.md` → `validate` → `apply` → 下载 `*_enhanced.pptx`。
5. Agent 可后置；首版允许内部调试接口提交已确认计划（对齐 template-fill 早期做法），但生产路径不得暴露 `--force`。

完成标准：API 可创建 `native-enhance-pptx` 任务，备注-only 样例可下载结果且页数一致、可见文本回读未改、目标页含备注；`basic` / `image-enhanced` / `template-fill` / `beautify-pptx` 回归通过。

**阶段 1 完成记录**：OpenSpec change `native-enhance-pptx-phase-1` 已实现并归档。落地：`NATIVE_ENHANCE_PPTX` 模式与单文件契约、`PptNativeEnhanceCommandExecutor` notes-only MVP（服务端写入 confirmed plan，永不 `--force`）、相关节点骨架与单元/服务测试。

### 阶段 2：服务端托管分析、Checkpoint 与可观察性（已完成）

目的：`init`/`plan`/`validate` 由服务端托管，具备结构化进度、失败恢复、超时与错误码。

工作项：

1. 落地上节 checkpoint 链骨架（至少到 `NATIVE_ENHANCE_INITIALIZED` / `ENHANCEMENT_PLAN_DRAFTED` 与导出后校验节点）。
2. 创建后异步：`init`（copy 源进 `sources/`，生成 md + slide_index + draft plan）→ 写节点证据与第 3.1 节安全摘要。
3. 调整 resume：按 native-enhance 节点集合恢复；失败回到 `PROJECT_READY` 或 init 前，不覆盖源上传。
4. 增加文件大小限制、子进程超时、并发限制；错误码示例：`NATIVE_ENHANCE_INIT_FAILED`、`NATIVE_ENHANCE_PLAN_FAILED`、`NATIVE_ENHANCE_VALIDATE_FAILED`、`NATIVE_ENHANCE_APPLY_FAILED`、`NATIVE_ENHANCE_OUTPUT_INVALID`。
5. SSE/详情暴露安全字段，不泄露绝对路径。

完成标准：分析/初始化失败可定位；可从 `NATIVE_ENHANCE_INITIALIZED` 恢复；状态足以支撑前端展示。

**阶段 2 完成记录**：OpenSpec change `native-enhance-pptx-phase-2` 已实现。落地：托管 init/draft plan checkpoint、checkpoint-aware resume（保留 uploads）、`NativeEnhancePptxProperties` 资源边界、任务详情 `nativeEnhanceAnalysis` 安全摘要；notes-only MVP 仍可在 draft 后服务端写入 confirmed（完整 `/confirm` 门禁属阶段 3）。

### 阶段 3：Enhancement Plan / 语音确认闭环（已完成）

目的：模块计划确认 +（可选）语音确认后，再生成 notes/audio 并允许 `apply`。

工作项：

1. 为 `NATIVE_ENHANCE_PPTX` 创建独立 Agent profile：只读 `sources/*.md` / `slide_index` / draft plan；只写 `notes/*.md` 与 draft plan 修订；禁止 SVG、template-fill、beautify、image_gen 工具。
2. 通过 `request_plan_confirmation`，`stage=enhancement_plan_confirmation`，携带版本、digest、有界摘要（页数、模块开关、覆盖率、transition 摘要）。
3. 扩展 `PptConfirmationStagePolicy` / `Resolver`；未确认不得生成音频或 `apply`。
4. 当 `audio.enabled`：增加 `audio_voice_confirmation`；确认后写 `project.json.audio`。
5. 服务端校验：确认载荷不得把「改可见内容 / 改页数」类字段写入计划；禁用模块必须保留 `enabled=false`。
6. 驳回修订路径：保留 init 产物，重置 draft/confirmed 节点与 confirmationId。

完成标准：典型样例能出可解释模块计划；未确认无法导出；语音门禁在 audio 开启时强制；驳回修订可用。

**阶段 3 完成记录**：OpenSpec change `native-enhance-pptx-phase-3` 已实现。落地：停止 draft 后自动 confirmed；服务端打开 `enhancement_plan_confirmation`；`/confirm` 批准后 notes→validate→apply；`audio_voice_confirmation` → `AUDIO_VOICE_CONFIRMED` 与 `project.json.audio` 写入；受限 Agent profile；永不 `--force`。完整 TTS 仍属阶段 4。

### 阶段 4：音频、计时、切换与导出校验（已完成）

目的：覆盖完整 V1 narration 模块，并做导出后 read-back。

工作项：

1. `notes_to_audio.py` 包装：音色/语速来自确认配置；stem 对齐 `001.md` → `001.mp3`；记录超时与失败码。
2. `apply`：按已确认模块补丁 notes / media / timings / transitions；支持 `--transition` / `--narration-padding` / `--overwrite` 的服务端受控传参（**永不**传 `--force`）。
3. timings：依赖 `ffprobe`；运维 readiness 检查探测可用性。
4. transitions：仅允许上游白名单 effect；`apply_without_audio` 行为与确认计划一致。
5. 导出后 `ppt_to_md`（或等价 OOXML 回读）写入 `validation/native-enhance-readback.json`（建议名），校验页数、可见内容稳定性摘要、备注/媒体存在性；推进 `NATIVE_ENHANCE_OUTPUT_VALIDATED`。
6. 维护支持矩阵（备注覆盖、已有备注替换策略、无音频 transition-only、图表页备注长度建议等）。

完成标准：notes/audio/timings/transitions 均有明确支持或明确失败；校验报告可被前端/运维读取。

**阶段 4 完成记录**：OpenSpec change `native-enhance-pptx-phase-4` 已实现。落地：`AUDIO_GENERATED` + `notes_to_audio` 包装（语音确认后）、受控 apply 参数、`NativeEnhanceReadinessContributor`（ffprobe）、`NativeEnhanceOutputVerifier` → `validation/native-enhance-readback.json`；notes-only 路径保持可用。
### 阶段 5：生产化、灰度与运维（已完成）

目的：可安全灰度并可长期维护。

工作项：

1. Feature flag（如 `ppt-master.native-enhance-pptx-enabled`）；初期限测试租户/管理员（对齐 template-fill / beautify 灰度模式）。
2. 源 PPTX / 生成音频生命周期：保留期、下载权限、清理、审计；音频可能含敏感口述内容，禁止跨任务暴露。
3. 前端入口：单文件上传 + 文案「不改版式与可见内容，仅追加备注/旁白/切换」；展示模块计划与语音确认 UI。
4. 遥测：创建量、init/确认/notes/audio/apply 耗时、失败码、确认拒绝率、validate 失败率、恢复成功率；低基数标签，禁止 jobId/租户明文作高基数 label。
5. 脱敏诊断包与运维手册（依赖检查含 `ffprobe`、上游升级/回滚、兼容矩阵）。
6. 灰度阈值与 canary 验收清单（对齐 beautify / template-fill 阶段五做法，另立 `docs/ops/` 文档，不在本文件展开实现细节）。

完成标准：灰度指标达标、故障可诊断可回滚、前后端与运维契约完整。

**阶段 5 完成记录**：OpenSpec change `native-enhance-pptx-phase-5` 已实现。落地：默认关闭的 production feature flag + tenant/admin 灰度、owner/tenant 生命周期与 dry-run 清理、低基数遥测、脱敏诊断包、`/native-enhance-pptx/` 静态入口，以及 `docs/ops/native-enhance-pptx-*` 运维/canary 契约。notes-only 与 full-audio 路径共用同一门禁；永不 `--force`。
---

## 6. 建议的端到端执行流

```text
创建任务（唯一 source.pptx，workflowMode=native-enhance-pptx）
  → 校验单文件 / 拒绝 template 字段
  → native_enhance_pptx.py init（归档源、ppt_to_md、slide_index、draft enhancement_plan）
  → [NATIVE_ENHANCE_INITIALIZED] / [ENHANCEMENT_PLAN_DRAFTED]
  → 用户确认模块计划（enhancement_plan_confirmation）
  → [ENHANCEMENT_PLAN_CONFIRMED]
  → （若 notes）基于 sources/*.md 生成 notes/NNN.md → [NOTES_GENERATED]
  → （若 audio）语音确认 → [AUDIO_VOICE_CONFIRMED]
  → （若 audio）notes_to_audio.py → [AUDIO_GENERATED]
  → native_enhance_pptx.py validate → [NATIVE_ENHANCE_VALIDATED]
  → native_enhance_pptx.py apply → exports/*_enhanced.pptx → [PPT_EXPORTED]
  → ppt_to_md read-back → [NATIVE_ENHANCE_OUTPUT_VALIDATED]
  → 下载增强 PPTX
```

失败恢复原则：

| 失败点 | 恢复策略 |
| --- | --- |
| init / slide_index / draft plan | 从 `PROJECT_READY` 或 init 前重试；不覆盖原始上传 |
| 计划校验 / 用户驳回 | 回到计划修订；保留 init 产物与已有 notes（除非用户要求重写） |
| notes 质量问题 | 重置 `NOTES_GENERATED`；保留确认后的模块开关 |
| 语音确认驳回 | 停留在 voice 门禁；不生成音频 |
| audio 生成失败 | 保留 notes；从 `AUDIO_VOICE_CONFIRMED` 或生成前重试 |
| validate 覆盖不足 | 补 notes/audio 后重跑 validate；不得 `apply` |
| apply / 输出校验失败 | 保留诊断与部分产物；按原因回到 validate 前或计划修订；不得宣称成功 |

Agent / 服务包装器 **不得**用 `--force` 或改写确认状态绕过门禁。

---

## 7. 测试策略

| 层级 | 覆盖重点 |
| --- | --- |
| 单元测试 | 模式解析（`native-enhance-pptx`/`native_enhance_pptx`）、单文件契约、拒绝 template 字段、节点 `applicableTo`、确认 stage 映射、digest/version 防篡改、模块 JSON 校验、错误码 |
| 服务集成测试 | multipart 创建、子进程参数（ProcessBuilder 数组）、超时、SSE 安全字段、下载、resume、feature flag 拒绝创建 |
| 上游契约测试 | 固定样例的 project/plan/validation schema、`init` 目录布局、validate exit code、apply 输出命名 |
| 端到端测试 | notes-only；notes+audio+timings+transitions；禁用部分模块；用户驳回再确认；validate 缺覆盖；已有备注页；transition-only（无音频） |
| 回归测试 | `basic`、`image-enhanced`、`template-fill`、`beautify-pptx` 的创建/确认/导出/恢复不受影响 |
| 安全测试 | 路径穿越、伪造扩展名、超大文件/超大音频、恶意文件名、跨任务访问、命令注入、禁止 `--force` 暴露 |

样例必须脱敏。黄金断言以页数、可见关键文本回读、备注/媒体存在性、关系完整性为主，避免脆弱的整文件字节对比。

---

## 8. 风险与决策点

| 风险/决策 | 建议 |
| --- | --- |
| 与 beautify / template-fill / 主流程路由混淆 | API 显式 `workflowMode`；前端文案对照第 1.2 / 1.3 节；服务不做模糊自动改道 |
| Agent 越权改可见内容或走 SVG | 模式专属 prompt + 工具白名单；命令执行器拒绝 SVG/finalize/template-fill 脚本 |
| `--force` 绕过确认 | 服务端 apply 包装永不传 `--force`；调试入口也要求服务端已批准记录 |
| 音频依赖与 `ffprobe` 缺失 | readiness 探测；缺依赖时明确失败码，不做静默跳过 timings |
| 备注幻觉 / 编造数据 | 备注生成约束「忠实于可见内容」；评审抽检；确认摘要不含全文但可抽样展示 |
| 源 PPT 已有备注 / 已有媒体 | 阶段 0 冻结替换策略；在 plan/确认中展示覆盖风险 |
| 超大音频导致包体膨胀 | 上传/生成大小限制 + 兼容矩阵；未来 `media` 压缩模块不在 V1 |
| 上游 schema / CLI 漂移 | 固定经验证的 ppt-master 版本；契约测试后再升级；只调用 `native_enhance_pptx.py` 稳定入口 |
| `native-narration` 旧名残留 | 文档与代码一律使用 native-enhance；仅在兼容说明中提及旧脚本 |
| 与 `generate-audio` 职责重叠 | 本模式仅服务「外部成品 PPTX」；带 `svg_output/` 的项目不走本模式 |

---

## 9. 推荐里程碑

1. **M0：可行性通过** — 阶段 0 完成，兼容矩阵与冻结契约已形成。（**已完成**）
2. **M1：备注 MVP** — 阶段 1 完成，notes-only 可创建、导出、回读。（**已完成**）
3. **M2：可确认 Beta** — 阶段 2–3 完成，具备托管 init/plan、双确认门禁、恢复与观测。（**已完成**）
4. **M3：V1 模块完整** — 阶段 4 完成，覆盖 audio/timings/transitions 与导出校验。（**已完成**）
5. **M4：生产发布** — 阶段 5 的灰度、权限、指标与运维条件达标。（**已完成**）

实施时每个阶段应独立提交、独立测试和独立审查；涉及 API/任务状态变化时，按项目约定在改动前后执行文档同步流程。

---

## 10. 关键扩展点清单（实施索引）

便于实现时对照现有代码，**本规划阶段不修改这些文件**，仅列出预期触点：

| 扩展点 | 预期改动方向 |
| --- | --- |
| `PptWorkflowMode` | 新增 `NATIVE_ENHANCE_PPTX`；`from` 识别 `native-enhance-pptx` |
| `PptWorkflowService.createJob` / resume | 单 PPTX 校验；可选 `enhanceModules`；异步 init/plan；resume 按模式节点集；灰度门禁钩子 |
| `PptJobNode` | 新节点 + 扩展共享节点（如 `PROJECT_READY`、`PPT_EXPORTED`）的 `applicableTo` |
| 新执行器（建议名 `NativeEnhancePptxCommandExecutor`） | 包装 `native_enhance_pptx.py` / `notes_to_audio.py` / read-back |
| `DefaultAgentScopeWorkflowAgentFactory` | native-enhance 专用 prompt/工具白名单（备注生成 + 计划草案） |
| `PptConfirmationStagePolicy` | `enhancement_plan_confirmation`、`audio_voice_confirmation` 评估 |
| `PptConfirmationStageResolver` | stage → `ENHANCEMENT_PLAN_CONFIRMED` / `AUDIO_VOICE_CONFIRMED` |
| `PptJobResponse` | 第 3.1 节安全摘要字段 |
| 配置 / feature flag | 类似 `templateFillEnabled` / beautify 开关的 native-enhance 开关 |
| Readiness / 遥测 / 生命周期 / 诊断 | 对齐 template-fill / beautify 生产化模式，但独立组件与指标名 |
| 静态前端（可选） | `/native-enhance-pptx/` 一等页面（阶段 5） |

上游只读事实源（部署仓库内）：

- `skills/ppt-master/workflows/native-enhance-pptx.md`
- `skills/ppt-master/workflows/native-narration-pptx.md`（兼容说明 only）
- `skills/ppt-master/workflows/routing.md`
- `skills/ppt-master/workflows/generate-audio.md`（语音确认交互参考；非本模式入口）
- `skills/ppt-master/scripts/native_enhance_pptx.py`
- `skills/ppt-master/scripts/native_narration_pptx.py`
- `skills/ppt-master/scripts/notes_to_audio.py`
- `skills/ppt-master/scripts/source_to_md/ppt_to_md.py`

相邻服务文档：

- [`beautify-pptx-integration-plan.md`](./beautify-pptx-integration-plan.md)
- [`template-fill-pptx-integration-plan.md`](./template-fill-pptx-integration-plan.md)
- [`PPT_OUTLINE_INTEGRATION_PLAN.md`](./PPT_OUTLINE_INTEGRATION_PLAN.md)

---

## 11. 文档状态

| 项 | 状态 |
| --- | --- |
| 规划文档 | **本文档（PLAN；阶段 0–5 已落地）** |
| 阶段 0 | **已完成**（上游契约 / 兼容矩阵 / 基线报告已冻结） |
| 阶段 1 | **已完成**（模式接入 + notes-only MVP；计划确认门禁已由阶段 3 替换） |
| 阶段 2 | **已完成**（托管 init/plan、checkpoint 恢复、安全摘要与资源边界） |
| 阶段 3 | **已完成**（enhancement plan / audio voice 确认门禁 + 受限 Agent profile） |
| 阶段 4 | **已完成**（音频/timings/transitions、ffprobe readiness、导出 read-back） |
| 阶段 5 | **已完成**（生产灰度、生命周期、遥测、诊断、静态入口与运维契约） |
| OpenSpec change | `native-enhance-pptx-phase-0` … `phase-5` 已归档（见 `openspec/changes/archive/`） |
| Java 生产代码 | **阶段 5 已落地生产化组件**；默认 flag 关闭，canary 观察后再扩 allowlist |

本文档结构与分阶段深度对齐 [`beautify-pptx-integration-plan.md`](./beautify-pptx-integration-plan.md)，OOXML 模式先例参考 [`template-fill-pptx-integration-plan.md`](./template-fill-pptx-integration-plan.md)，语气参考 [`PPT_OUTLINE_INTEGRATION_PLAN.md`](./PPT_OUTLINE_INTEGRATION_PLAN.md)。上游行为以部署的 `ppt-master` 仓库中 `native-enhance-pptx` 工作流与脚本为准；若与本文冲突，先更新阶段 0 契约文档再改实现计划。
