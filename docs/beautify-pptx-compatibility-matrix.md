# `beautify-pptx` 兼容性矩阵（阶段 4 + 阶段 5 生产注记）

| 对象/条件 | 结论 | 证据与策略 |
| --- | --- | --- |
| 纯文本与页序 | supported | 逐字符串与 1:1 页映射；SVG 契约 + 导出 read-back |
| 提供图片 | supported-with-warning | 复用 `images/` + `image_manifest.json` 重排；crop/rotation/opacity 列入 `ignored`，不做像素级保真 |
| 普通表格 | supported | 无合并单元格表格从冻结 rows 重生为 SVG；read-back 校验单元格文本 |
| 普通簇状柱图 | supported | categories/series/values 冻结重生；read-back 校验数据摘要 |
| 普通折线/饼图（可分类） | supported-with-warning | 与柱图同级冻结重生；样式差异可 warning |
| 密排页 | supported-with-warning | 保留单页；只改善层级/留白，不拆页 |
| 近空分隔页 | supported-with-warning | 保持单独页面；意图列入 `needs_confirmation` |
| 16:9 / 4:3 | supported | identity `1.7777→ppt169`、`1.3333→ppt43` |
| 非标准画布 | supported-with-warning | 进入最近标准格式；详情展示源尺寸风险 |
| 字体缺失 / Aptos 等 | supported-with-warning | 确认时锁定 PPT-safe 字体栈 |
| combo、双轴、waterfall 图 | supported-with-warning | 进入 `needs_confirmation`；确认接受降级后可继续并保留告警；未确认则阻断成功导出 |
| 合并单元格、多级表头 | supported-with-warning | 进入 `needs_confirmation`；确认降级或 fail-closed |
| 隐藏页、母版文本 | supported-with-warning | 默认列入 `ignored` 并在确认中展示，不纳入正文 |
| 无法分类 / `unsupported_objects` | fail-closed | `BEAUTIFY_UNSUPPORTED_CONTENT`，分析阶段失败 |
| 批量多 deck | unsupported | v1 范围外 |
| 原地 OOXML patch、字节级/像素级复刻 | unsupported | 仅生成新的原生 PPTX；验收做结构/内容契约 |
| 导出 read-back | supported | `validation/beautify-readback.json`；通过后完成 `BEAUTIFY_OUTPUT_VALIDATED` |

## 阶段 5 生产注记

| 能力 | 默认 | 说明 |
| --- | --- | --- |
| Production creation flag | **off** (`beautify-pptx.production.enabled=false`) | 新建任务在 ID/工作区副作用前由 `BeautifyPptxRolloutPolicy` fail-closed |
| Auth / ownership | required when enabled | 可信 `PptAccessContext`；同 tenant owner 或 beautify admin；历史无 owner 任务不对普通调用方开放 |
| Lifecycle | on when jobs created with ownership | `lifecycle/manifest.json`；保留截止冻结；清理 dry-run 默认开、删除默认关 |
| Diagnostics | **off** | `GET /api/ppt-jobs/{id}/beautify-pptx/diagnostics`；白名单 ZIP；排除源 PPTX/图片/identity/inventory/plan 全文/SVG/导出/OOXML/路径/stdout |
| Download | authorized + validated | 仅完成且 read-back 就绪后 `downloadReady`；详情/下载受授权器保护 |
| First-party page | `/beautify-pptx/` | 单文件上传；硬约束确认；SSE 退避；确认携带 version/digest |

运维手册：[`ops/beautify-pptx-runbook.md`](ops/beautify-pptx-runbook.md)、[`ops/beautify-pptx-upgrade-rollback.md`](ops/beautify-pptx-upgrade-rollback.md)、[`ops/beautify-pptx-frontend-contract.md`](ops/beautify-pptx-frontend-contract.md)、[`ops/beautify-pptx-canary-acceptance.md`](ops/beautify-pptx-canary-acceptance.md)。
