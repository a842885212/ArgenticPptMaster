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

**第 3 步完成状态（当前分支，3a）**

| 能力 | 生产 Bean | 状态 |
| --- | --- | --- |
| 共享 OOXML 包工具（ZIP / Content Types / relationships / notes XML） | `engine.ooxml.*` | 已落地（够 notes 写入） |
| `native_enhance` notes 材料路径（apply / notes-only validate） | `RoutingNativeEnhanceEngine`：`JavaNativeEnhanceEngine` 子集 dual-run + **`PythonNativeEnhanceEngine` 权威门禁** | 部分移植；**禁止**把 Java 子集单独当生产门禁 |
| `native_enhance` init / audio·timings·transitions apply | 直委 Python | 未移植 |
| `template_fill` notes + 简单 text replacements（apply） | `RoutingTemplateFillEngine`：`JavaTemplateFillEngine` 子集 dual-run + **`PythonTemplateFillEngine` 权威门禁** | 部分移植；table/chart/clone 深拷贝 / analyze·check-plan·validate 仍 Python |
| `template_fill` analyze / check-plan / validate | 直委 Python | 未移植 |

`JavaNativeEnhanceEngine` 已覆盖服务路径上的 notes-only apply（解包 → 写 notesSlide / notesMaster / Content Types → 重打包）与 notes 材料就绪校验。`JavaTemplateFillEngine` 可在无 table/chart/per-slide transition 的 plan 上做 slide 克隆 + text replacements + notes。

仍由 Python 权威门禁覆盖：audio / timings / transitions、delivery 审计、template-fill 全量 JSON（table/chart/深克隆私有部件）、analyze / check-plan / validate。完成定义：Java apply 产物的 slide（及 notes）XML 与 pin `cc535363` 对齐后，才可把 Bean 切成纯 Java。

### 4. 换美化分析

`beautify_identity.py`、`beautify_inventory.py`、`pptx_intake.py` 读取 PPTX 并输出 JSON。Java 读 OPC 包即可，这一阶段仍不必实现 SVG 编译器。

### 5. 换 `finalize_svg`

这一步做图标嵌入、`<use>` 展开、`<tspan>` 压平。输入是本服务生成的那种子集 SVG，用 XML DOM 处理。

### 6. 移植 `svg_to_pptx`

按现有 Python 包的边界搬：坐标（1 SVG px = 9525 EMU）、形状、文本、图片、表格、图表、公式、动画。每搬一块，就用固定的 `svg_output/` 让 Python 和 Java 各导出一次，忽略时间戳和关系 ID 后比较 slide XML。

## 不移植

`image_gen.py` 和 `notes_to_audio.py` 留在转换器之外。出图由 Java 直接调现有模型接口。旁白继续用外部 TTS。

## 验收

每换一个脚本，就拿 `cc535363` 上对应 Python 命令的输出做金样本。比较对象是 slide XML 或该脚本写出的 JSON，而不是整个 PPTX 压缩包的字节。

`ppt-master` 是 MIT 许可证。移植代码时保留 Hugo He 的版权声明。
