# `native-enhance-pptx` 阶段 0 上游与服务契约

本文件冻结后续 Java 接入可依赖的上游命令、产物 schema、退出码与服务侧 API/门禁规则。证据来自配置的 `ppt-master.repo-path` 上对合成 fixture 的 CLI 实测；**不代表**生产服务已实现 `workflowMode=native-enhance-pptx`。

## 冻结环境（摘要）

| 项 | 值 |
| --- | --- |
| 上游 commit | `f824d5d277d95642b93cf4754f8b2fe63a459d9e` |
| Python | `3.12.3`（配置 `ppt-master.python-command`） |
| 关键依赖 | `edge-tts==7.2.8`；`python-pptx` 用于合成 fixture |
| 外部二进制 | `ffprobe` 6.1.1（timings 测音频时长） |
| 稳定 CLI 入口 | `skills/ppt-master/scripts/native_enhance_pptx.py`（委托 `native_narration_pptx.py`） |
| read-back | `skills/ppt-master/scripts/source_to_md/ppt_to_md.py` |
| 音频 | `skills/ppt-master/scripts/notes_to_audio.py` |
| 上游工作树 | dirty，但变更仅在 image generation 路径，未命中本链路脚本/工作流 |

脚本 SHA-256（本基线绑定）：

| 文件 | SHA-256 |
| --- | --- |
| `native_enhance_pptx.py` | `a69428821b1e1d21a65b01cf9785ee9ebee92d592a8763a615f57bd812fc236d` |
| `native_narration_pptx.py` | `d0441772aa5d2f76bfc5873b2788ca8efbcd8b6f6bdddd126778c6b699fbd212` |
| `notes_to_audio.py` | `e289edf36435c6343b13f072fb95143c245ee0df0a2728e3fd236b0658cd9ea9` |
| `source_to_md/ppt_to_md.py` | `8cc0afb3faca656cbfce207358f43ecf81b49aa4773f05625593fdbc7ce60163` |
| `workflows/native-enhance-pptx.md` | `4697df4c749a37681dd0f37e86dca909a5be99bf75b6a1afc3bd98f16ba4b445` |

升级上游或 Python 环境后必须重跑阶段 0，不能只沿用 commit 字符串。

## 冻结命令清单

相对路径均相对于 `ppt-master` 仓库根；`<python>` 为配置的解释器。

| 步骤 | 命令 | 成功退出码 | 关键失败码 |
| --- | --- | --- | --- |
| init | `<python> skills/ppt-master/scripts/native_enhance_pptx.py init <source.pptx> [--name <slug>] [--project-dir <dir>] [--transition fade] [--transition-duration 0.5] [--narration-padding 0.4] [--apply-transition-without-audio]` | `0` | `1`（非 pptx / 目录非空等）；`ppt_to_md` 失败时透传其非零码 |
| plan | `... plan <project>`（可覆盖 transition / padding / apply-without-audio） | `0` | `1`（非 enhancement 项目） |
| validate | `... validate <project>` | `0`（已启用模块无缺失） | `1`（非项目）；**`2`**（缺 notes/audio 覆盖） |
| apply | `... apply <project> [-o out] [--overwrite] [--transition ...] [--narration-padding ...] [--apply-transition-without-audio]` | `0` | `1`（未确认 plan、输出已存在且无 `--overwrite`、非项目等） |
| notes→audio | `<python> .../notes_to_audio.py <project> --voice <ShortName> [--rate +0%] [--provider edge]` | `0` | 非零（依赖/网络/后端失败） |
| read-back | `<python> .../source_to_md/ppt_to_md.py <exports/*_enhanced.pptx> -o <project>/validation/readback.md` | `0` | 非零 |

**服务硬规则**：包装器 **不得** 传递 `--force`（上游虽提供该开关以跳过 plan 确认）。不得调用 `pptx_template_import.py` / `finalize_svg.py` / `svg_to_pptx.py`，不得创建 `svg_output/`。

`--name` slug 语义对齐上游 `_sanitize_slug`：非 `[0-9A-Za-z_-]` 替换为 `_`，空则 `native_enhance`。服务侧 `projectName` 须在调用前规范化。

默认导出名：`exports/<source_stem>_enhanced.pptx`。输出已存在时须显式 `--overwrite`（服务应受控决定是否覆盖）。

Transition 白名单（init/apply）：`cover`、`fade`、`push`、`random`、`split`、`strips`、`wipe`；plan/apply 另允许 `none`。默认 `fade` / `0.5s`；`narration_padding` 默认 `0.4`。

## 项目目录与命名

| 路径 | 用途 |
| --- | --- |
| `project.json` | 项目元数据 |
| `sources/<file>.pptx` | 归档源（`source_import.mode`=`copy`\|`move`\|`reuse`） |
| `sources/<stem>.md` | init 内 `ppt_to_md` 产出 |
| `analysis/slide_index.json` | 页序与 part 映射 |
| `analysis/enhancement_plan.json` | 模块计划（draft → confirmed） |
| `notes/NNN.md` | 逐页口播备注（三位补零） |
| `audio/NNN.mp3` | 与 notes stem 对齐 |
| `exports/<stem>_enhanced.pptx` | 增强产出 |
| `validation/report.json` | validate 报告 |
| `validation/readback.md` | 导出回读 |

## 最小消费 schema

上游可增加未知字段；下列为服务后续阶段可依赖的最小契约。

### `native_pptx_enhancement_project.v1`（`project.json`）

必需语义：`schema`、`kind=native_pptx_enhancement`、`modules`（含 notes/audio/timings/transitions）、`source_pptx`、`source_markdown`、`slide_count`、`notes_dir`/`audio_dir`/`exports_dir`、`transition.{effect,duration}`、`audio.{provider,voice,rate}`（确认前可为空串）、`source_import.{mode,original_path}`。

**Legacy 读取兼容**：`schema=native_narration_pptx_project.v1` 仍被上游 `plan`/`validate`/`apply` 接受。新项目 MUST 只写 `native_pptx_enhancement_project.v1`。

### `native_pptx_enhancement_plan.v1`（`analysis/enhancement_plan.json`）

必需：`schema`、`status`（`draft`\|`confirmed`）、`source_pptx`、`slide_count`、`modules.{notes,audio,timings,transitions}`（每项含 `enabled`；禁用模块保留键且 `enabled=false`，不得删除）、`not_in_v1[]`。

notes/audio 含 `coverage.{ready,total}`；timings 含 `narration_padding`、`source`；transitions 含 `effect`、`duration`、`apply_without_audio`。

### `native_pptx_enhancement_slide_index.v1`

必需：`schema`、`source_pptx`、`slide_count`、`slides[]`（`index` 1 基、`note_file`、`audio_stem`、`part_name`、`slide_number`）。

### `native_pptx_enhancement_validation.v1`（`validation/report.json`）

必需：`schema`、`slide_count`、`plan_status`、`enabled_modules`、`notes_required`、`audio_required`、`notes_count`、`audio_count`、`missing_notes`、`missing_audio`。缺覆盖时进程退出码 `2`。

## 后续 API 冻结规则

| 条件 | 后续服务行为 |
| --- | --- |
| `workflowMode=native-enhance-pptx` 或 `native_enhance_pptx` | 规范化为未来的 `NATIVE_ENHANCE_PPTX` |
| `files` 为空、多个文件或唯一文件非 `.pptx` | HTTP 400；拒绝创建 |
| `templateFile` 或非空 `templateConstraints` | HTTP 400 |
| `enhanceModules` JSON 非法或含 V1 外模块键（如 `animation`） | HTTP 400 |
| 未传 `enhanceModules` | 对齐上游默认偏好：四模块倾向启用；transition `fade`/`0.5`；`narrationPadding=0.4`；最终以确认后的 plan 为准 |
| audio 依赖 notes | 首版：`notes` 未启用则不得启用 `audio` |
| plan 未确认 | 不得生成音频（若需）、不得 `apply`；确认 stage=`enhancement_plan_confirmation` |
| `audio.enabled` | 额外 stage=`audio_voice_confirmation`；未确认不得跑 `notes_to_audio.py` |
| init/plan/validate/apply/输出校验失败 | 建议错误码：`NATIVE_ENHANCE_INIT_FAILED`、`NATIVE_ENHANCE_PLAN_FAILED`、`NATIVE_ENHANCE_VALIDATE_FAILED`、`NATIVE_ENHANCE_APPLY_FAILED`、`NATIVE_ENHANCE_OUTPUT_INVALID` |
| `--force` / SVG 管线 | 服务包装器禁止；确认载荷不得注入 force |

安全摘要字段名冻结见集成规划 §3.1（`sourcePageCount`、`enhancementPlanStatus`、`enabledModules`、`notesCoverage`、`audioCoverage`、`audioVoiceStatus`、`transitionSummary`、`validateReportSummary`、`outputValidationSummary`、`exportFileName`）；响应禁止绝对工作区路径、备注全文、原始音频路径。

建议 `enhanceModules` 形状：

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

以上为冻结设计输入，不代表当前生产接口已经支持这些字段或错误码。
