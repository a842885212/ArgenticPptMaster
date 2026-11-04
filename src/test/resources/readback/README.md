# Read-back parity fixture corpus

PPTX 语料用于 `PptxMarkdownReadbackParityTests` 双跑对比（Python `ppt_to_md.py` vs Java `PptxToMarkdownConverter`）。

| 文件 | 来源 | 覆盖特性 |
| --- | --- | --- |
| `hyperlinks.pptx` | `scripts/generate_readback_fixtures.py` 生成 | run 级外链（含 URL 编码）、`hlinksldjump` 页内跳转、shape 级 click_action（外链 + named slide）、危险 scheme 丢弃、同 URL 连续 run 合并 |
| `charts.pptx` | 同上 | COLUMN_CLUSTERED / LINE_MARKERS / PIE chart 的 categories + series 转录 |
| `tables-images.pptx` | 同上 | 原生表格（单元格转义）、同一 PNG 跨页复用（manifest 去重 + occurrences）、speaker notes、多段落项目符号 |
| `kubernetes_blueprint_2026.pptx` | ppt-master 仓库 `examples/ppt169_kubernetes_blueprint_2026/exports/`，源 commit `f824d5d2` | 真实世界 deck：10 页全 speaker notes、文本密度 |

重新生成合成 deck：`python scripts/generate_readback_fixtures.py`（需 `python-pptx<0.7` + Pillow）。
