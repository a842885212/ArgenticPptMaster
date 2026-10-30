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
| `image_gen.py` | 约 5.2 万行 | 模型出图，不是转换器；见第 7 步，改由 Java 直连 |

`svg_to_pptx/` 是整条链路里唯一真正绑住上游的部分。

## 替换顺序

### 1. 把调用点收成接口

在 `PptMasterCommandExecutor.runPythonScript` 后面按能力分成 Java 接口，例如 `ProjectWorkspace`、`TemplateFillEngine`、`SvgToPptxExporter`。每个接口先保留 Python 实现，再换 Java 实现。同一份输入同时跑两边，用 PPTX 里的 slide XML 做对比。

### 2. 换文件型脚本

`project_manager` 的 init 和 import-sources、`total_md_split`、`svg_quality_checker` 不碰 DrawingML，作为第一批。

**第 2 步完成状态（当前分支）**

| 能力 | 生产 Bean | 状态 |
| --- | --- | --- |
| `project_manager` init / import-sources | `RoutingProjectWorkspace` → **`JavaProjectWorkspace` 权威**（纯文件 + PPTX intake + PPTX/Excel/docx/html/PDF→MD；按源分流）；URL / pandoc 长尾仍 Python；`.ppt` Java 只归档；可选 `ppt-master.engine.import-python-dual-run` 诊断（默认关）。PDF 用 PDFBox（合同级对齐 `pdf_to_md` 导入路径；复杂表 / 矢量图栅格化为残差；近空与加密 PDF 硬失败） | **已完成（Java 子集权威）** |
| `total_md_split` | `JavaSpeakerNotesSplitter` | 已接线 |
| `svg_quality_checker` | `RoutingSvgQualityChecker` → **`JavaSvgQualityChecker` 权威**（写出 `validation/svg_quality_report.json`）；可选 dual-run 时 `PythonSvgQualityChecker` 仅诊断（默认关） | **已完成（Java 子集权威）** |

`JavaSvgQualityChecker` 已覆盖服务路径 **phase-1 + phase-2 + phase-3 blocking** 子集：viewBox、forbidden elements（含只读 geometry materialize + 非 `data-icon` local `<use>`）、authoring-property contract、font-size 语法、外部图引用、icon placeholder、legacy pptx 属性改名、unsupported visuals（白名单对齐 `SvgSlideConverter`）、opacity / geometry length / stroke **errors**、paint compatibility **errors**、clip/mask 与 definition / paint-reference / marker contracts、transform / freeform grammar / gradient·filter 子集 / image aspect-ratio·nested-crop / text-property **errors** / hyperlinks / pattern-fill presets / native replace·formula markers；以及 phase-3：仅当存在相关标记时的 preset geometry metadata/transform fail-closed、仅当存在 preserved 载荷时的 txBody 契约、module bounds **error** 路径、pin 对齐的 text bounds 溢出、`spec_lock` **字号 recurring-role（第 3 次）** blocking（颜色/字体漂移仍非门禁）。`check()` 通过/失败均落盘 gate 兼容的 `validation/svg_quality_report.json`（`schema` / `stage=final` / `categories.blocking` / `source_fingerprint`），供导出 `QualityReportGate` 使用。

产品取舍：导出门禁以 Java 已移植子集为准。**仍推迟（残差）**：完整 transform DML 语义 / thick-circle 边角、完整 freeform→DrawingML、完整 gradient geometry / imported-effect、authored-preset tree / preview-fingerprint 深比对、fragmented-tspan 与 missing-bounds 等 pin **warnings-only**、pptx structure、illustration/animation/communication contracts、template-mode，以及 paint/opacity/geometry/stroke/text 的 **canonical 拼写 warnings**。`spec_lock.md` 仍由导出器单独加载校验。`PythonSvgQualityChecker` 保留为 dual-run 诊断入口（Java 权威不变）。

### 3. 换 OOXML 改写

模板填充和 native enhance 在已有 PPTX 上克隆页、改文字、表、图、备注。这条路线不经过 SVG。用 JDK 的 ZIP 加 StAX/DOM 直接写 OOXML。Apache POI 适合读文本和页数，上游很多形状、动画、图表 XML 超出 POI 的写入模型，POI 不作为 DrawingML 写入器。

**第 3 步整体完成（当前分支）**

按文档范围，OOXML 改写路径的生产权威已切到 Java；`notes_to_audio.py` 仍属「不移植」，`image_gen.py` 已在第 7 步换成 Java 直连。

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

按文档范围，美化分析路径的生产权威已切到 Java；SVG / finalize / notes_to_audio 仍属后续或不移植（出图见第 7 步）。

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

**第 5 步整体完成（当前分支）**

按文档范围，`finalize_svg` 默认全量管线的生产权威已切到 Java；`svg_to_pptx` 仍属后续步骤。

| 能力 | 生产 Bean | 状态 |
| --- | --- | --- |
| 共享 finalize 助手（geometry / icons / local use / align-embed / flatten） | `engine.file.svgfinalize.*` | 已落地 |
| Geometry materialize（inline `style` → XML 几何属性） | `RoutingSvgFinalizer` → **`JavaSvgFinalizer` 权威**；可选 dual-run 时 Python 仅诊断（默认关） | **已完成（Java 权威）** |
| Icon embed（`project/icons/` 命名空间 `library/name`；不读 templates/icons；拒绝裸名） | 同上 Java 权威 | **已完成（Java 权威）** |
| Local `<use>` expand（同文档 `#id` / `<symbol>` viewBox） | 同上 Java 权威 | **已完成（Java 权威）** |
| Align + embed images（meet/slice；Base64；EMF/WMF 外链保留） | 同上 Java 权威；ImageIO 编码不必与 PIL 逐字节一致（测试对 data URI 做 MIME stub 比较） | **已完成（Java 权威）** |
| Flatten text（定位 `<tspan>` → 独立 `<text>`；默认 split，不启用 merge-paragraphs） | 同上 Java 权威 | **已完成（Java 权威）** |
| Atomic publish（失败不留下残缺 `svg_final`） | `SvgFinalizeOps` | **已完成** |
| 契约测试 | fixtures `finalize-svg/{text-tspan,icons-use,images-meet-slice}` + pin gold | **已完成** |

`JavaSvgFinalizer` 委托 `SvgFinalizeOps`：候选目录处理成功后再原子发布到 `svg_final/`。成功/失败与产物以 Java 为准。

### 6. 移植 `svg_to_pptx`

按现有 Python 包的边界搬：坐标（1 SVG px = 9525 EMU）、形状、文本、图片、表格、图表、公式、动画。每搬一块，就用固定的 `svg_output/` 让 Python 和 Java 各导出一次，忽略时间戳和关系 ID 后比较 slide XML。

**第 6 步整体完成（当前分支）**

按文档范围，服务 flat-release 导出路径（`SvgToPptxExporter.export(project)`，读 `svg_output/`，要求 `spec_lock.md` + 通过的 final quality report）的生产权威已切到 Java。JDK ZIP + DOM 写 OOXML；**不用** Apache POI 作 DrawingML 写入器；**不**在运行时依赖 python-pptx。

| 能力 | 生产 Bean | 状态 |
| --- | --- | --- |
| 坐标 / canvas（`EMU_PER_PX = 9525`；`spec_lock` viewBox / theme） | `RoutingSvgToPptxExporter` → **`JavaSvgToPptxExporter` 权威**；可选 dual-run 时 Python 仅诊断（默认关） | **已完成（Java 权威）** |
| 形状（rect/circle/ellipse/line/polyline/polygon；path 子集） | 同上；`engine.file.svgtopptx.drawingml.*` | **已完成（Java 权威）**；完整 freeform path 语法为残差 |
| 文本（`<text>` → `a:txBody`；pin 宽度估计 + headroom；theme 字体 token） | 同上 | **已完成（Java 权威）** |
| 图片（项目相对路径 + data-URI raster → media + `p:pic`） | 同上 | **已完成（Java 权威）** |
| 表格标记（`data-pptx-replace-with="table"`） | 默认服务路径保留 SVG fallback 子树转 DrawingML（对齐 pin 未传 `--native-charts-and-tables`） | **已完成（Java 权威）**；完整 native `<a:tbl>` / `--native-charts-and-tables` 为残差 |
| 图表标记（`data-pptx-replace-with="chart"`） | 同上默认 SVG fallback | **已完成（Java 权威）**；完整 chart XML + workbook / ChartEx 样式为残差 |
| 公式标记（`data-pptx-replace-with="formula"` → OMML） | 服务子集：字面量 / `=` / `^{}` 上标（如 `E=mc^{2}`） | **已完成（Java 权威）**；完整 LaTeX AST / mhchem 为残差 |
| 动画 | 默认 CLI：无对象动画（`-a none`）；页过渡 fade（pin 默认） | **已完成（Java 权威）**；完整 object animation / animations.json 为残差 |
| 包结构（`[Content_Types]`、presentation、slides、theme、notes） | `PptxFlatPackageWriter`（JDK ZIP） | **已完成（Java 权威）** |
| 门禁（缺 `spec_lock` / 缺或指纹不匹配的 final quality report → 非 0） | `QualityReportGate` + `SpecLock` | **已完成（Java 权威）** |
| 契约测试 | fixtures `svg-to-pptx/{shapes-text,images,formula,table-marker,chart-marker}` + pin gold；`SlideXmlComparator` | **已完成** |

**明确不在第 6 步「整体完成」内（已知缺口）**

- `--roundtrip` / authoring-svg-flat 源对象还原
- `--quick-generate`
- `--enable-dangerous-nonconforming-svg-export`
- `pptx_structure` 的 `structured` / `preserve` / 模板镜像
- `--native-charts-and-tables` 全量 native chart/table 样式与 workbook 对齐
- 完整 formula AST / mhchem；完整 freeform path / shape-boolean；完整 object animation 注册表
- （第 2 步）全量移植 Python 剩余 blocking 规则进 Java 子集（非当前 release 门禁阻断项）

### 7. 出图改 Java 直连（IMAGE_ENHANCED Path A）

`image_gen.py` 是多厂商模型出图适配器，不是 OOXML/SVG 转换器，因此**不整包移植**。本服务只实现自己需要的那一条路径：`JavaImageGenerationGateway` 读 `images/image_prompts.json`，并发直连 OpenAI 兼容 `POST {ppt.image.base-url}/images/generations`，写 `images/<filename>` 并逐条回写清单状态。

| 能力 | 生产 Bean | 状态 |
| --- | --- | --- |
| Path A 批量出图 | `ImageGenerationGateway` → **`JavaImageGenerationGateway` 权威** | **已完成（Java 权威）** |
| 回落 `image_gen.py --manifest` | `PythonImageGenerationGateway`，仅当 `ppt.image.python-fallback=true` | 保留为回滚开关 |

契约要点：

- 只处理 `Pending` / `Failed`，跳过 `Generated` / `Needs-Manual`；本次运行存在失败条目即非零结果，成功条目的文件与状态保持已落盘，与 Python 版语义一致。
- 请求体是 OpenAI 风格：发送 `size`，不发送 `aspect_ratio`。`aspect_ratio` 仅支持 1K 档固定映射（`1:1`→`1024x1024`、`16:9`→`1280x720`、`4:3`→`1024x768`、`3:4`→`768x1024`），表外比例不发请求，直接记 `Failed`；`quality` 固定 `medium`。
- 响应优先 `b64_json`；只有 `url` 时再下载该 URL，且不向图片主机透传出图密钥。仅 HTTP 429 走指数退避重试（最多 3 次），其它错误立即 `Failed`。
- 并发取 `ppt.image.concurrency`（默认 5），单条请求受 `ppt.image.item-timeout`（默认 180s）约束，整批仍受 `ppt-master.command-timeout` 兜底。
- 清单回写用进程内互斥 + 临时文件 `ATOMIC_MOVE`，不提供跨进程锁。
- `ppt.image.base-url` 必填，不复用对话侧 `agentscope.base-url`；`ppt.image.api-key` 缺省时回落对话密钥。
- Markdown / 冷 SVG 编排启动前置不再要求 `project_manager.py`、`finalize_svg.py`、`total_md_split.py`、`svg_quality_checker.py` 落盘（这些能力已由 Java 生产权威承接；Python 实现与 dual-run 钩子仍保留）。未启用 Python fallback 时也不再要求 `image_gen.py`；仅 `ppt.image.python-fallback=true`（出图权威为 Python 适配器）时才校验该脚本存在。

明确不做：多厂商 `IMAGE_BACKEND` 注册表、`--render-md` / `--summary` 视图、512px/2K/4K size preset、限流时的全局并发降级。

## 不移植

`notes_to_audio.py` 留在转换器之外，旁白继续用外部 TTS。`image_gen.py` 不整包移植：出图已由 `JavaImageGenerationGateway` 直连 OpenAI 兼容端点（见第 7 步），只在显式 `ppt.image.python-fallback=true` 时才回到该脚本。

## 验收

每换一个脚本，就拿 `cc535363` 上对应 Python 命令的输出做金样本。比较对象是 slide XML 或该脚本写出的 JSON，而不是整个 PPTX 压缩包的字节。

`ppt-master` 是 MIT 许可证。移植代码时保留 Hugo He 的版权声明。
