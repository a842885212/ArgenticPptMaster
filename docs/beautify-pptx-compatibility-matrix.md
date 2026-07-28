# `beautify-pptx` 阶段 0 兼容性矩阵

| 对象/条件 | 结论 | 阶段 0 证据与后续策略 |
| --- | --- | --- |
| 纯文本与页序 | supported | 7 页 SVG 导出后逐页回读通过；阶段 1 必须保持逐字符串与 1:1 比较 |
| 提供图片 | supported-with-warning | 1 张图片被 intake 绑定到第 3 页并在 finalize/export 后回读；原始 crop/rotation/opacity 不属于 v1 保真项，须列入 `ignored` |
| 普通表格 | supported | 3×3 无合并单元格表格的所有单元格在 output read-back 中匹配 |
| 普通簇状柱图 | supported | categories、series 名称与数值全部以 SVG 文本重建并在回读中匹配 |
| 密排页 | supported-with-warning | 第 6 页保留为单页；只能改善层级与留白，不能拆页 |
| 近空分隔页 | supported-with-warning | 第 7 页保持单独页面；须在 `needs_confirmation` 中明确保留意图 |
| 16:9 / 4:3 | supported | identity 实测 `1.7777→ppt169`、`1.3333→ppt43` |
| 非标准画布 | supported-with-warning | 实测 `1.25` 进入最近标准格式分支；后续须向用户显示源尺寸与风险 |
| 字体 `Aptos` | supported-with-warning | 当前 host 解析为 Noto Sans；阶段 1 需选择并锁定可用替代字体 |
| combo、双轴、waterfall 图 | not-tested | 上游 inventory 已定义为 `needs_confirmation` 候选；阶段 4 前不可承诺支持 |
| 合并单元格、多级表头 | not-tested | 未包含在固定 fixture；后续须显式确认或失败 |
| 隐藏页、母版文本 | not-tested | 不纳入正文默认范围；后续记录为 `ignored` 并展示给用户 |
| 批量多 deck | unsupported | v1 范围外 |
| 原地 OOXML patch、字节级/像素级复刻 | unsupported | 本流程只生成新的原生 PPTX，验收只做结构/内容契约 |
