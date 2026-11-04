# PPTX→Markdown read-back 双跑阈值与处置

read-back 校验（beautify `validation/beautify-readback.md`、native-enhance `validation/readback.md`）
的权威实现为服务端 Java 转换（`PptxToMarkdownConverter`，输出对齐 `source_to_md/ppt_to_md.py`）。
开关 `ppt-master.engine.dual-run-enabled=true` 时，Python `ppt_to_md.py` 作为附属对比跑执行，
报告写入 `<markdownOut>.dual-run.json`（与 markdown 同目录）。

## 报告字段

| 字段 | 含义 |
| --- | --- |
| `comparisonStatus` | `MATCHED` / `DIVERGED` / `FAILED` / `SKIPPED` |
| `pythonSlideCount` / `javaSlideCount` | 两侧 `## Slide N` 节数 |
| `pythonRan` | Python 对比跑是否成功执行（false = 脚本缺失/退出非零/超时） |
| `perSlideTextCoverage` | Python 每页规范化文本行在 Java 同页命中的平均比例（1.0 = 全命中） |
| `pythonImageRefCount` | Python 侧图片引用计数（Java 侧报告缺省时与 markdown 中 `![` 计数对照） |
| `manifestMatch` | 两侧 `image_manifest.json` 规范化后是否一致（双侧均无 manifest 记 true） |

## 阈值

| 指标 | 告警阈值 | 处置 |
| --- | --- | --- |
| `comparisonStatus` | 非 `MATCHED` | 按下列分支处置 |
| `perSlideTextCoverage` | `< 1.0` 连续出现 ≥ 3 个任务 | 建缺陷：提取语义漂移，附 `*.dual-run.json` 与导出物 |
| `pythonSlideCount` ≠ `javaSlideCount` | 任意一次 | 立即建缺陷（页数不稳是硬保真风险） |
| `manifestMatch` = false | 任意一次且 deck 含图片 | 建缺陷：图与页绑定漂移 |

## 分支处置

- `SKIPPED` / `pythonRan=false`：环境缺失（python-pptx 未装、脚本缺失、超时）。**不告警**——
  权威结论不受影响；仅在所有部署持续出现时检查 Python 环境健康。
- `FAILED`：对比跑自身异常。同上，不告警；重复出现时检查 `ppt_to_md.py` 变更（pin 漂移）。
- `DIVERGED`：真实差异。按阈值表建缺陷；观察期（去 Python 化完成前）同一 fixture 形态反复
  DIVERGED 时应暂停该 deck 形态的 beautify 灰度并回填白名单说明。

## 观察期出口

同时满足以下条���，可在去 Python 化变更中移除 Python 对比跑与相关类：

1. 全部生产部署 `dual-run-enabled=true` 连续 ≥ 2 周无非 SKIPPED/FAILED 告警；
2. `DIVERGED` 工单全部闭环（修复或白名单记录原因）；
3. 双跑 parity 测试（`PptxToMarkdownParityTests`）白名单为空且全绿。
