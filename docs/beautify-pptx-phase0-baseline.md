# `beautify-pptx` 阶段 0 基线报告

## 结论

阶段 0 基线通过。合成 fixture 已在隔离工作区完成 `beautify_identity`、`init`、`import-sources`、inventory、显式计划确认、SVG 校验、`finalize_svg`、原生 PPTX 导出和 `ppt_to_md` 回读。输出保留 7 页页数与页序，逐页文本、表格单元格、图表 categories/series 名称和值均通过结构比较。

本结论只证明当前冻结环境中的上游闭环可行；服务仍未实现 `workflowMode=beautify-pptx`，也尚未接入生产 API。

## 冻结环境

| 项 | 值 |
| --- | --- |
| 上游 commit | `f824d5d277d95642b93cf4754f8b2fe63a459d9e` |
| Python | `3.12.3` |
| 依赖快照 | 88 个条目，SHA-256 `68b662dcba37d18df1e98e651fd29bb5e7e075c3dce8b87e35bef6085209bc26` |
| LibreOffice | `26.2.4.2` |
| 上游工作树 | dirty；变更仅位于 image generation 文档/实现，未命中本基线冻结的 workflow 或脚本路径 |
| 字体探测 | 请求 `Aptos` 时解析为 `Noto Sans Regular`；属于字体替换告警 |

参与文件的 SHA-256 由 [run_beautify_phase0_baseline.py](../scripts/run_beautify_phase0_baseline.py) 每次运行记录；升级上游或 Python 环境后必须重新跑基线，不能只沿用本表中的 commit。

## 固定样例与验证结果

[phase0-baseline.pptx](../src/test/resources/beautify-pptx/phase0-baseline.pptx) 是完全合成的 7 页 deck，逐页清单在 [expected.json](../src/test/resources/beautify-pptx/expected.json)。覆盖纯文本、图片、普通表格、普通簇状柱图、密排页和近空分隔页；不包含个人信息、客户数据或外部图片。

| 门禁 | 结果 | 证据 |
| --- | --- | --- |
| 画布探测 | 通过 | 16:9=`1.7777` → `ppt169`；4:3=`1.3333` → `ppt43`；`1.25` 记录为最近标准格式分支 |
| intake | 通过 | 自动生成 source Markdown、identity、slide library、source profile、image manifest；未重复调用 `pptx_intake.py` |
| inventory | 通过 | 7 页账本、1 张图片、1 张普通表格、1 张普通柱图 |
| 计划门禁 | 通过 | draft 时不存在最终 spec；确认的 version=1/digest 已保存；请求拆页的负例被标记 rejected |
| SVG | 通过 | 7/7 文件通过 `svg_quality_checker`，无 spec lock drift |
| 导出与回读 | 通过 | 原生 PPTX 导出成功；7 页回读，文本、单元格、categories、series 名称和值均匹配 |
| 失败返回码 | 通过 | 缺失 `slide_library` 的 `beautify_inventory.py` 调用返回 exit code 2 |

## 冻结命令与复跑

运行目录必须是空目录，且不应位于生产 `var/ppt-master/jobs` 下：

```bash
./mvnw -q -Dtest=BeautifyPptxFixturesTests test
PPT_MASTER_REPO=<ppt-master.repo-path>
PPT_MASTER_PYTHON=<ppt-master.python-command>
"$PPT_MASTER_PYTHON" scripts/generate_beautify_phase0_fixture.py
RUN_ROOT=$(mktemp -d /tmp/beautify-phase0.XXXXXX)
"$PPT_MASTER_PYTHON" scripts/run_beautify_phase0_baseline.py \
  --repo "$PPT_MASTER_REPO" \
  --python "$PPT_MASTER_PYTHON" \
  --fixture src/test/resources/beautify-pptx/phase0-baseline.pptx \
  --expected src/test/resources/beautify-pptx/expected.json \
  --work-root "$RUN_ROOT"
```

成功运行会输出 `baseline-summary.json` 与 `upstream-snapshot.json`。这些运行产物保留在隔离目录中，不提交原始 Markdown、完整 JSON、导出 PPTX 或绝对路径；仓库只保存 fixture、运行器及下列契约文档。

## 已知限制与阶段 1 前置条件

- `Aptos` 未在当前导出环境中可用，后续确认页须展示并锁定 PPT-safe 字体替换策略。
- combo/双轴/waterfall 图、合并单元格、隐藏页、母版文本和非标准画布尚未在完整导出链路中覆盖，不能标记为已支持。
- 本基线实测 SVG 导出使用 `--no-notes`；因此 `notes/total.md` 不是该闭环的前置条件，后续是否启用 notes 节点由阶段 1 的 Agent 工具契约决定。
- 阶段 1 开始前必须复用 [上游契约](./beautify-pptx-upstream-contract.md) 的单文件、画布、分析产物及确认门禁规则。
