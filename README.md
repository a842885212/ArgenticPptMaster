# ArgenticPptMaster

`ArgenticPptMaster` 是一个基于 Spring Boot 的服务层，用来把本地 `ppt-master` 工作流包装成可调用的异步 PPT 生成服务。当前 agent 编排基于 `AgentScope Java 2.0.0-RC3`。

## 当前实现

- Agent 框架：`AgentScope Java`
- 会话恢复：`ReActAgent.streamEvents()` + `JsonFileAgentStateStore`
- 人机协同：`RequireExternalExecutionEvent` / `ToolResultMessage`
- 底层工具链：本地 `ppt-master` Python scripts

## 当前主路线：Markdown

当前优先打通的是 markdown-first 路线：

1. 上传 `md` / `pdf` / `ppt(x)` / `doc(x)` / `xls(x)` / `html` 等文件
2. `project_manager.py import-sources` 归档原始文件，并尽可能转换出 `sources/*.md`
3. agent 读取 `sources/` 与 `analysis/`，整理生成方案
4. 进入 Human-in-the-Loop 确认
5. 确认后由 agent 产出：
   - `design_spec.md`
   - `spec_lock.md`
   - `notes/total.md`
   - `svg_output/*.svg`
6. 服务侧执行：
   - `svg_quality_checker.py`
   - `total_md_split.py`
   - `finalize_svg.py`
   - `svg_to_pptx.py`

这条路线的目标是先把“资料解析 -> markdown 归一化 -> agent 生成项目骨架 -> 导出 pptx”跑通。

## 后续路线：PPTX 模板

`pptx` 模板路线暂不在当前主流程里执行，但已经确定作为下一阶段：

1. 基于 `pptx_template_import.py` / `template_fill_pptx.py`
2. 面向“已有模板 deck 或参考母版”的场景
3. 与 markdown 路线分开编排，避免当前阶段把两套生成契约混在一起

## 配置

关键配置位于 `src/main/resources/application.properties`：

- `ppt-master.repo-path`
- `ppt-master.workspace-path`
- `ppt-master.python-command`
- `agentscope.provider`
- `agentscope.model-name`
- `agentscope.api-key`
- `agentscope.base-url`

至少需要配置可用的 AgentScope 模型参数后，真实 agent 才能跑起来。
