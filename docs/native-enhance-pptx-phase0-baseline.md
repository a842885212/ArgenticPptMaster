# `native-enhance-pptx` 阶段 0 基线报告

## 结论

阶段 0 基线通过。合成脱敏 fixture 已在隔离临时目录完成上游闭环：

`init` → `plan` →（验证未确认 `apply` 失败）→ 确认 notes-only plan → 编写 `notes/*.md` → `validate` → `apply` → `ppt_to_md` 回读。

并额外跑通音频分支：确认全模块 plan → 写入 `project.json.audio` → `notes_to_audio.py`（edge / `zh-CN-YunjianNeural`）→ `validate` → `apply` → 回读。

本结论只证明当前冻结环境中的上游 CLI 可行；服务仍未实现 `workflowMode=native-enhance-pptx`，也未修改生产 Java。

## 冻结环境

| 项 | 值 |
| --- | --- |
| 上游 commit | `f824d5d277d95642b93cf4754f8b2fe63a459d9e` |
| Python | `3.12.3` |
| pip freeze 条目数 / SHA-256 | 88 / `8f6bcce4c93ac09c84cc08be1852434e5609ddeaead5a77d19c2078821366b8b` |
| edge-tts | `7.2.8` |
| ffprobe | `6.1.1-3ubuntu5` |
| 上游 dirty | 是；仅 image generation 相关路径，未命中 native-enhance 脚本/工作流 |
| 脚本哈希 | 见 [upstream-contract](./native-enhance-pptx-upstream-contract.md) |

## 固定样例

[phase0-baseline.pptx](../src/test/resources/native-enhance-pptx/phase0-baseline.pptx) 为完全合成的 6 页 16:9 deck；清单见 [expected.json](../src/test/resources/native-enhance-pptx/expected.json)。含封面、正文（含源备注）、表格意图文、图表意图文、密排说明、结束页；无个人信息或外部图片。

## 门禁结果

| 门禁 | 结果 | 证据 |
| --- | --- | --- |
| 脚本可达性 | 通过 | `native_enhance_pptx.py` 及依赖脚本 `--help` 可调用；`ffprobe` 可用 |
| init 产物 | 通过 | `project.json` schema v1、slide_index、draft plan、sources pptx+md；无 `svg_output/` |
| 未确认 apply | 通过 | exit `1`；提示需 confirmed 或 `--force`（服务禁止 force） |
| notes-only validate/apply/read-back | 通过 | validate `0`；导出 `phase0-baseline_enhanced.pptx`；页数 6；关键可见文本保留；源备注被生成备注覆盖 |
| validate 缺覆盖 | 通过 | 缺 `006.md` → exit `2`，`missing_notes:[6]` |
| audio 全链路 | 通过 | 6/6 mp3；validate `0`；apply 嵌入 6 段音频；ffprobe 样例时长约 6.58s |
| 硬约束 | 通过 | 未使用 `--force`；未跑 SVG 管线 |

## 复跑说明

在空的隔离目录执行（勿写入生产 `var/ppt-master/jobs`）：

```bash
PPT_MASTER_REPO=<ppt-master.repo-path>
PPT_MASTER_PYTHON=<ppt-master.python-command>
SCR="$PPT_MASTER_REPO/skills/ppt-master/scripts"
FIX=src/test/resources/native-enhance-pptx/phase0-baseline.pptx
RUN_ROOT=$(mktemp -d /tmp/native-enhance-phase0.XXXXXX)
PROJ="$RUN_ROOT/phase0_native_enhance"

"$PPT_MASTER_PYTHON" "$SCR/native_enhance_pptx.py" init "$FIX" \
  --name phase0_baseline --project-dir "$PROJ" --projects-root "$RUN_ROOT/projects"
"$PPT_MASTER_PYTHON" "$SCR/native_enhance_pptx.py" plan "$PROJ"
# 将 analysis/enhancement_plan.json 的 status 设为 confirmed，并按需开关 modules
# 写入 notes/001.md … notes/006.md
"$PPT_MASTER_PYTHON" "$SCR/native_enhance_pptx.py" validate "$PROJ"
"$PPT_MASTER_PYTHON" "$SCR/native_enhance_pptx.py" apply "$PROJ"
"$PPT_MASTER_PYTHON" "$SCR/source_to_md/ppt_to_md.py" \
  "$PROJ/exports/phase0-baseline_enhanced.pptx" -o "$PROJ/validation/readback.md"
```

仓库只提交 fixture、期望清单与契约文档；不提交隔离目录中的导出 PPTX、音频或含绝对路径的原始日志。

## 阶段 1 前置

- 复用 [upstream-contract](./native-enhance-pptx-upstream-contract.md) 的单文件、schema、双确认与禁止 force/SVG 规则。
- 复用 [compatibility-matrix](./native-enhance-pptx-compatibility-matrix.md) 的备注覆盖与模块边界。
- 生产 Java 仍不得在阶段 0 声称已支持该模式。
