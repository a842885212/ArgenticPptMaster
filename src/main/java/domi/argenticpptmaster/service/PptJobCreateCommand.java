package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptConfirmationMode;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 创建 PPT 任务所需的上传和元数据。
 *
 * @param files                   内容来源文件
 * @param templateFile            模板填充模式的唯一模板文件
 * @param projectName             项目名称
 * @param format                  画布格式
 * @param instruction             用户指令
 * @param workflowMode            工作流模式
 * @param templateConstraintsJson 可选模板页硬约束 JSON（仅 TEMPLATE_FILL）
 * @param confirmationMode        确认推进模式；null 视为 INTERACTIVE
 */
public record PptJobCreateCommand(
        List<MultipartFile> files,
        MultipartFile templateFile,
        String projectName,
        String format,
        String instruction,
        String workflowMode,
        String templateConstraintsJson,
        PptConfirmationMode confirmationMode) {

    public PptJobCreateCommand(
            List<MultipartFile> files,
            MultipartFile templateFile,
            String projectName,
            String format,
            String instruction,
            String workflowMode) {
        this(files, templateFile, projectName, format, instruction, workflowMode, null, PptConfirmationMode.INTERACTIVE);
    }

    public PptJobCreateCommand(
            List<MultipartFile> files,
            MultipartFile templateFile,
            String projectName,
            String format,
            String instruction,
            String workflowMode,
            String templateConstraintsJson) {
        this(
                files,
                templateFile,
                projectName,
                format,
                instruction,
                workflowMode,
                templateConstraintsJson,
                PptConfirmationMode.INTERACTIVE);
    }

    public PptConfirmationMode resolvedConfirmationMode() {
        return confirmationMode == null ? PptConfirmationMode.INTERACTIVE : confirmationMode;
    }
}
