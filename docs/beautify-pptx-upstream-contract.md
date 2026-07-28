# `beautify-pptx` 阶段 0 上游与服务契约

## 最小消费 schema

下表是服务后续阶段可依赖的最小字段。上游新增未知字段兼容；任一必需字段缺失、类型改变或 `slide_index` 从 1 基改为其他语义时视为契约失败。

| 产物 | 路径（相对项目根） | 最小字段/语义 |
| --- | --- | --- |
| identity | `analysis/<stem>.identity.json` | `slide_count:number`、`canvas.{width_px,height_px,aspect}`、`theme`、`observed` |
| slide library | `analysis/<stem>.slide_library.json` | `schema`、`slide_count:number`、`canvas_px`、`slides[]`；slide 的 `slide_index` 为 1 基 |
| source profile | `analysis/source_profile.json` | `schema`、`deck_count:number`、`decks[]` 与 `<stem>` artifacts 映射 |
| image manifest | `images/image_manifest.json` | 数组；每项具有 `filename`、`occurrences[].slide_index` 与 `usage_count` |
| beautify inventory | `analysis/beautify_inventory.json` | `schema=beautify_inventory.v1`、`slide_count:number`、`canvas_px`、`slides[]`；每页含 text/table/chart/image 账本及 `ignored` / `needs_confirmation` |
| read-back | `validation-readback.md` 或等价摘要 | `## Slide N` 顺序、文本字符串与导出页数；图表/表格数据由输出 SVG 文本与源 inventory 共同校验 |

文件 stem 来自源 PPTX 文件名，服务不得硬编码 `phase0-baseline`。无图片源 deck 合法地缺少 image manifest；此时 inventory 命令必须省略 `--images`。

## 画布与命令契约

1. 在 `init` 前运行 `beautify_identity.py <source.pptx>`，读取 `canvas.aspect`。
2. `aspect≈1.778` 使用 `ppt169`；`aspect≈1.333` 使用 `ppt43`；其他值选择最近标准格式并保存原始尺寸摘要。
3. 运行 `project_manager.py init` 后使用 `import-sources --copy`。该命令已产生 PPTX intake；只有缺失 identity 或 slide library 时才运行一次 `pptx_intake.py`。
4. 使用 `beautify_inventory.py <slide_library> [--images <manifest>] -o analysis/beautify_inventory.json`。
5. 仅在 plan 的 version/digest 确认后才能写 `design_spec.md` 与 `spec_lock.md`，再走 SVG → `finalize_svg.py` → `svg_to_pptx.py` → `ppt_to_md.py`。

## 后续 API 冻结规则

| 条件 | 后续服务行为 |
| --- | --- |
| `workflowMode=beautify-pptx` 或 `beautify_pptx` | 规范化为未来的 `BEAUTIFY_PPTX` |
| `files` 为空、多个文件或唯一文件非 `.pptx` | HTTP 400；拒绝创建 |
| `templateFile` 或非空 `templateConstraints` | HTTP 400；拒绝与 beautify 混用 |
| 请求 `format` 与身份探测不符 | 不阻断；详情返回 source-resolved format，并标示请求值未主导画布 |
| identity/intake/inventory 失败 | 后续映射稳定错误语义：`BEAUTIFY_IDENTITY_FAILED`、`BEAUTIFY_INTAKE_FAILED`、`BEAUTIFY_INVENTORY_FAILED` |
| plan 未确认或 digest/version 不匹配 | 不得写最终 spec 或导出；后续确认 stage 固定为 `beautify_plan_confirmation` |

以上是冻结设计输入，不代表当前生产接口已经支持这些字段或错误码。
