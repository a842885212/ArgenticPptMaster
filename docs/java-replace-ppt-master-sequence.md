# 用 Java 替换 ppt-master Python 脚本的顺序

目标是脱离 `https://github.com/hugohe3/ppt-master` 的 Python 脚本，由本服务用 Java 完成同样的产物。按脚本逐个替换，SVG 转 PPTX 放在最后。

上游转换器固定在 `cc535363b8cf6e3abd0543d578d32b4f4cc3cf98`。该版本仍包含本服务调用的 `template_fill_pptx.py` 与 `native_enhance_pptx.py`。上游 `main` 随后删除过这两个入口，跟着 `main` 会把契约测丢。

## 当前调用面

Java 服务只编排任务。脚本通过 `PptMasterCommandExecutor.runPythonScript` 执行，入口集中在这些类：

- `DefaultAgentScopeWorkflowAgentFactory`
- `PptTemplateFillCommandExecutor`
- `BeautifyPptxAnalysisExecutor`
- `BeautifyPptxOutputVerifier`
- `PptNativeEnhanceCommandExecutor`

| 脚本 | 规模（固定版本） | 性质 |
| --- | --- | --- |
| `project_manager.py`、`total_md_split.py`、`svg_quality_checker.py` | 小到中 | 目录、备注切分、规则检查 |
| `template_fill_pptx.py` | 约 5 千行 | 改已有 PPTX 的 OOXML |
| `native_enhance_pptx.py`、`notes_to_audio.py` | 入口小，音频脚本大 | 备注、旁白、计时 |
| `beautify_identity.py`、`beautify_inventory.py`、`pptx_intake.py` | 各约 1.1 万到 1.4 万行 | 读现有 PPTX |
| `finalize_svg.py` | 约 1.8 万行 | SVG 后处理 |
| `svg_to_pptx/` | 50 个文件，约 5.1 万行 | SVG 到可编辑 DrawingML |
| `image_gen.py` | 约 5.2 万行 | 模型出图，不是转换器 |

`svg_to_pptx/` 是整条链路里唯一真正绑住上游的部分。

## 替换顺序

### 1. 把调用点收成接口

在 `PptMasterCommandExecutor.runPythonScript` 后面按能力分成 Java 接口，例如 `ProjectWorkspace`、`TemplateFillEngine`、`SvgToPptxExporter`。每个接口先保留 Python 实现，再换 Java 实现。同一份输入同时跑两边，用 PPTX 里的 slide XML 做对比。

### 2. 换文件型脚本

`project_manager` 的 init 和 import-sources、`total_md_split`、`svg_quality_checker` 不碰 DrawingML，作为第一批。

**第 2 步完成状态（当前分支）**

| 能力 | 生产 Bean | 状态 |
| --- | --- | --- |
| `project_manager` init / import-sources（纯文件） | `RoutingProjectWorkspace` → `JavaProjectWorkspace`；Office/PDF/URL import 回落 Python | 已接线 |
| `total_md_split` | `JavaSpeakerNotesSplitter` | 已接线 |
| `svg_quality_checker` | `RoutingSvgQualityChecker`：`JavaSvgQualityChecker` 子集 dual-run + **`PythonSvgQualityChecker` 权威门禁** | 部分移植；**禁止**把 Java 子集单独当生产门禁 |

`JavaSvgQualityChecker` 已覆盖服务路径上自包含的阻塞子集：viewBox、forbidden elements（不含依赖 `svg_to_pptx` 的 geometry/`<use>` 展开）、authoring-property contract、font-size 语法、外部图引用、icon placeholder。

仍由 Python 权威门禁覆盖（Java 未移植或强依赖 DrawingML / native_objects / register_template / spec_lock / animations）：paint/opacity/transform/freeform/geometry length、unsupported visuals、preset geometry、native markers、pptx structure、spec_lock 对齐、illustration/animation/communication contracts、template-mode 等。完成定义：Java 与 pin 上 `project --format X` 的退出码与关键 ERROR 对齐后，才可把 Bean 切成纯 Java。

### 3. 换 OOXML 改写

模板填充和 native enhance 在已有 PPTX 上克隆页、改文字、表、图、备注。这条路线不经过 SVG。用 JDK 的 ZIP 加 StAX/DOM 直接写 OOXML。Apache POI 适合读文本和页数，上游很多形状、动画、图表 XML 超出 POI 的写入模型，POI 不作为 DrawingML 写入器。

**第 3 步整体完成（当前分支）**

按文档范围，OOXML 改写路径的生产权威已切到 Java；`notes_to_audio.py` / `image_gen.py` 仍属「不移植」。

| 能力 | 生产 Bean | 状态 |
| --- | --- | --- |
| 共享 OOXML 包工具（ZIP / Content Types / relationships / notes XML / slide motion） | `engine.ooxml.*`（含 `nativeenhance.*`，供 NE / template-fill 复用） | 已落地 |
| `native_enhance` init / validate / apply（notes·audio 嵌入·timings·transitions） | `RoutingNativeEnhanceEngine` → **`JavaNativeEnhanceEngine` 权威**；可选 dual-run 时 Python 仅诊断（默认关） | **已完成（Java 权威）** |
| `native_enhance` delivery 审计 | Java lite（ZIP 打开 + slide 计数 / 隐藏页占位） | 完整 `pptx_delivery_check` 未全量移植（非阻断） |
| `native_enhance` TTS（`notes_to_audio.py`） | `NotesAudioGateway` / Python | **不移植**（见下文）；音频文件假定已落在项目 `audio/` |
| `template_fill` analyze / check-plan / apply / validate | `RoutingTemplateFillEngine` → **`JavaTemplateFillEngine` 权威**；可选 dual-run 时 Python 仅诊断（默认关） | **已完成（Java 权威）**；slide 克隆 + text/table/chart/notes/transitions |
| `template_fill` SmartArt 编辑 / `ppt_to_md` 读回 | 不移植；validate 用 OOXML 文本读回 | SmartArt 仅 inventory |

`JavaNativeEnhanceEngine` 委托 `engine.ooxml.nativeenhance.NativeEnhanceOps`：init（解包/归档/`slide_index`/`project.json`/draft plan）、validate（notes+audio 材料就绪与 plan/transition 校验）、apply（notes、OOXML 音频嵌入、timings、服务白名单 transitions）。`JavaTemplateFillEngine` 已实现 analyze / check-plan / apply / validate 全接口（JDK ZIP + DOM，不用 POI 写 DrawingML）；成功/失败与产物以 Java 为准。

### 4. 换美化分析

`beautify_identity.py`、`beautify_inventory.py`、`pptx_intake.py` 读取 PPTX 并输出 JSON。Java 读 OPC 包即可，这一阶段仍不必实现 SVG 编译器。

**第 4 步整体完成（当前分支）**

按文档范围，美化分析路径的生产权威已切到 Java；SVG / finalize / image_gen / notes_to_audio 仍属后续或不移植。

| 能力 | 生产 Bean | 状态 |
| --- | --- | --- |
| 共享 OOXML 读工具（theme clrMap / clrScheme 最小解析） | `engine.ooxml.beautify.*` | 已落地 |
| `beautify_identity` identify（canvas / theme / observed / layout_sizes） | `RoutingBeautifyAnalysisEngine` → **`JavaBeautifyAnalysisEngine` 权威**；可选 dual-run 时 Python 仅诊断（默认关） | **已完成（Java 权威）**；`identify()` 将 JSON 放在 `EngineResult.output` stdout 供 `resolveFormat` |
| `pptx_intake`（`<stem>.identity.json` + `<stem>.slide_library.json` + `source_profile.json` upsert） | 同上 Java 权威；`slide_library` 复用 `TfAnalyzer.analyzePptx`；NIO `FileChannel.lock` 序列化索引 | **已完成（Java 权威）** |
| `beautify_inventory` builder（per-slide text/tables/charts/diagrams/images；`ignored` / `needs_confirmation` 空数组） | 同上 Java 权威；不移植 `--summary` / `--page` 视图模式 | **已完成（Java 权威）** |
| 服务门禁 `BeautifyPptxAnalysisExecutor.validateAnalysisArtifacts` | 现有 executor；fixtures `pure-text-mvp.pptx` / `phase0-baseline.pptx` 的 Java 产物通过契约 | **已完成** |

`JavaBeautifyAnalysisEngine` 委托 `BeautifyIdentityExtractor` / `BeautifyIntakeOps` / `BeautifyInventoryBuilder`：JDK ZIP + DOM，不用 POI 写 DrawingML；theme 仅移植 identity 所需的 scheme 解析（srgb/sys lastClr + clrMap），不移植完整 `pptx_to_svg` 渲染器。成功/失败与产物以 Java 为准。

### 5. 换 `finalize_svg`

这一步做图标嵌入、`<use>` 展开、`<tspan>` 压平。输入是本服务生成的那种子集 SVG，用 XML DOM 处理。

### 6. 移植 `svg_to_pptx`

按现有 Python 包的边界搬：坐标（1 SVG px = 9525 EMU）、形状、文本、图片、表格、图表、公式、动画。每搬一块，就用固定的 `svg_output/` 让 Python 和 Java 各导出一次，忽略时间戳和关系 ID 后比较 slide XML。

## 不移植

`image_gen.py` 和 `notes_to_audio.py` 留在转换器之外。出图由 Java 直接调现有模型接口。旁白继续用外部 TTS。

## 验收

每换一个脚本，就拿 `cc535363` 上对应 Python 命令的输出做金样本。比较对象是 slide XML 或该脚本写出的 JSON，而不是整个 PPTX 压缩包的字节。

`ppt-master` 是 MIT 许可证。移植代码时保留 Hugo He 的版权声明。
