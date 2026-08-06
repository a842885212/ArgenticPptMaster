# `native-enhance-pptx` 兼容性矩阵（阶段 0）

状态取值：`supported` / `supported-with-warning` / `unsupported` / `not-tested`。证据来自合成 fixture CLI 实测或上游工作流/脚本语义；未覆盖项不得推断为 supported。

| 对象/条件 | 结论 | 证据与策略 |
| --- | --- | --- |
| 纯文本可见内容保真（notes-only / notes+audio） | supported | 6 页合成 deck；read-back 标题与关键字符串与源一致；页数不变 |
| 页数 / 页序冻结 | supported | init `slide_count=6`；导出回读 `Total slides: 6` |
| 源无备注页追加 notes | supported | 封面/表格意图页等；apply 后 read-back 出现新 Speaker Notes |
| 源已有备注 + notes 启用 | supported-with-warning | Slide 2 源备注被 `notes/002.md` **覆盖**；无「保留源备注」CLI 开关。确认 UI 须展示覆盖风险 |
| 源已有备注 + notes 禁用 | supported | 不调用 `_apply_notes`；源 notesSlide 保留（矩阵策略：禁用即保留） |
| notes 口播约束（无舞台指令/表格） | supported-with-warning | 上游硬规则；服务/Agent 须强制，阶段 0 人工编写合规 notes |
| 表格/图表页备注 | supported-with-warning | fixture 用文本模拟表格/图表阅读路径；真实 OOXML table/chart 形状保真依赖「不改可见 shapes」硬规则，未做复杂对象专项负例 |
| audio stem 对齐 `NNN.md`→`NNN.mp3` | supported | `notes_to_audio` 生成 6/6；validate 通过 |
| timings（ffprobe 时长 + padding） | supported | apply 嵌入音频后依赖 `ffprobe`；缺 `ffprobe` 时应 fail-closed（运维 readiness，阶段 4/5） |
| transitions 白名单 effect | supported | `cover|fade|push|random|split|strips|wipe`；默认 fade 0.5s |
| transition-only（无音频页） | supported-with-warning | 仅当 `apply_without_audio=true` / `--apply-transition-without-audio`；默认 false。阶段 0 未单独导出 transition-only 负例，行为来自上游脚本 |
| 计划未确认禁止 apply | supported | 实测 exit `1`；服务不得传 `--force` |
| validate 缺覆盖 exit `2` | supported | 移除 `006.md` 后 missing_notes=`[6]`，exit `2` |
| 16:9 源画布 | supported | fixture 13.333×7.5 in；本模式不改画布 |
| 禁止 SVG 管线 | supported | 闭环未创建 `svg_output/`；契约禁止相关脚本 |
| 字体缺失 | not-tested | delivery.check 为 Planned；备注/嵌入不依赖嵌入字体替换策略 |
| 隐藏页 | not-tested | fixture 无隐藏页；上游按 presentation 可见 slide parts 索引 |
| 母版/版式改写 | unsupported | 硬约束禁止；仅允许 notes/media/timings/transitions 相关补丁 |
| 超大源 PPTX / 超大音频包体 | not-tested | 阶段 2/5 需大小与超时限制；阶段 0 未压测 |
| 批量多文件上传 | unsupported | API 单文件契约 |
| 对象级动画 / 水印 / BGM / 媒体压缩 / delivery.check | unsupported | `not_in_v1` / Planned；请求须 400 或明确非目标 |
| 带 `svg_output/` 的 PPT Master 生成项目旁白 | unsupported | 应走上游 `generate-audio`，不是本模式 |
| beautify / template-fill 语义混用 | unsupported | 拒绝 template 字段；不做布局重做或槽位填充 |

## 阶段 0 已知限制

- fixture 用文本模拟「表格/图表页」备注阅读路径，不是真实 table/chart 形状回归集；阶段 1+ 可追加 OOXML 对象样例。
- transition-only 与「禁用 notes 保留源备注」主要依据脚本语义与部分实测，完整组合矩阵留给阶段 4。
- 云 TTS（elevenlabs/minimax/qwen/cosyvoice）仅文档冻结 provider 枚举；实测使用默认 `edge`。
- 上游仓库工作树可能 dirty；正式升级须以脚本哈希 + 重跑基线为准。
