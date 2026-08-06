# `native-enhance-pptx` 兼容性矩阵（阶段 0 + 阶段 4 服务侧）

状态取值：`supported` / `supported-with-warning` / `unsupported` / `not-tested`。证据来自合成 fixture CLI 实测、上游工作流/脚本语义，或阶段 4 Java 包装器/单元测试；未覆盖项不得推断为 supported。

| 对象/条件 | 结论 | 证据与策略 |
| --- | --- | --- |
| 纯文本可见内容保真（notes-only / notes+audio） | supported | 6 页合成 deck；read-back 标题与关键字符串与源一致；页数不变 |
| 页数 / 页序冻结 | supported | init `slide_count=6`；导出回读 `Total slides: 6`；服务 `native-enhance-readback.json` 硬校验页数 |
| 源无备注页追加 notes | supported | 封面/表格意图页等；apply 后 read-back 出现新 Speaker Notes |
| 源已有备注 + notes 启用 | supported-with-warning | Slide 2 源备注被 `notes/002.md` **覆盖**；无「保留源备注」CLI 开关。确认 UI 须展示覆盖风险 |
| 源已有备注 + notes 禁用 | supported | 不调用 `_apply_notes`；源 notesSlide 保留（矩阵策略：禁用即保留） |
| notes 口播约束（无舞台指令/表格） | supported-with-warning | 上游硬规则；服务/Agent 须强制；图表/表格页建议短口播路径而非复述网格 |
| 表格/图表页备注长度建议 | supported-with-warning | 建议 1–3 句阅读路径；过长备注易导致 TTS 超时/包体膨胀；非硬失败 |
| audio stem 对齐 `NNN.md`→`NNN.mp3` | supported | 上游 CLI + 服务 `notes_to_audio` 包装；`AUDIO_GENERATED` 记录 coverage |
| 服务侧 audio 门禁 | supported | 仅在 `audio.enabled` 且 `AUDIO_VOICE_CONFIRMED` 后生成；关闭 audio 时跳过 |
| timings（ffprobe 时长 + padding） | supported | apply 嵌入音频后依赖 `ffprobe`；`NativeEnhanceReadinessContributor` 探测；timings 启用且 DOWN 时 fail-closed |
| transitions 白名单 effect | supported | `cover|fade|push|random|split|strips|wipe|none`；非法 effect 确认/apply 拒绝 |
| transition-only（无音频页） | supported-with-warning | 仅当确认计划 `apply_without_audio=true` 时传 `--apply-transition-without-audio`；默认 false |
| 受控 apply 参数 | supported | 服务传 `--overwrite` / `--transition` / `--transition-duration` / `--narration-padding`；**永不** `--force` |
| 结构化导出校验 | supported | `validation/native-enhance-readback.json`（页数、notes/media 存在性、visibleContentStability）；推进 `NATIVE_ENHANCE_OUTPUT_VALIDATED` |
| 计划未确认禁止 apply | supported | 实测 exit `1`；服务不得传 `--force` |
| validate 缺覆盖 exit `2` | supported | 移除 `006.md` 后 missing_notes=`[6]`，exit `2` |
| 16:9 源画布 | supported | fixture 13.333×7.5 in；本模式不改画布 |
| 禁止 SVG 管线 | supported | 闭环未创建 `svg_output/`；契约禁止相关脚本 |
| 字体缺失 | not-tested | delivery.check 为 Planned；备注/嵌入不依赖嵌入字体替换策略 |
| 隐藏页 | not-tested | fixture 无隐藏页；上游按 presentation 可见 slide parts 索引 |
| 母版/版式改写 | unsupported | 硬约束禁止；仅允许 notes/media/timings/transitions 相关补丁 |
| 超大源 PPTX / 超大音频包体 | not-tested | 阶段 2 已有源大小限制；阶段 5 需生命周期与压测 |
| 批量多文件上传 | unsupported | API 单文件契约 |
| 对象级动画 / 水印 / BGM / 媒体压缩 / delivery.check | unsupported | `not_in_v1` / Planned；请求须 400 或明确非目标 |
| 带 `svg_output/` 的 PPT Master 生成项目旁白 | unsupported | 应走上游 `generate-audio`，不是本模式 |
| beautify / template-fill 语义混用 | unsupported | 拒绝 template 字段；不做布局重做或槽位填充 |

## 阶段 0 / 4 已知限制

- fixture 用文本模拟「表格/图表页」备注阅读路径，不是真实 table/chart 形状回归集。
- transition-only 组合以确认计划标志 + 上游脚本语义为准；服务侧已对齐传参。
- 云 TTS（elevenlabs/minimax/qwen/cosyvoice）仅文档冻结 provider 枚举；默认 `edge`。
- 可见内容稳定性摘要为页数/部件级，非像素 diff（阶段 5 可加强诊断）。
- 上游仓库工作树可能 dirty；正式升级须以脚本哈希 + 重跑基线为准。
