package domi.argenticpptmaster.web;

import domi.argenticpptmaster.exception.PptJobNotFoundException;
import domi.argenticpptmaster.exception.PptJobResumeException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptStorageException;
import domi.argenticpptmaster.web.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器。
 * <p>
 * 使用 {@link RestControllerAdvice} 拦截控制器层抛出的各类异常，
 * 将其转换为统一的 {@link ApiErrorResponse} 响应格式，并映射到合适的 HTTP 状态码。
 * 所有未预期的异常会记录错误日志后返回 500 内部服务器错误。
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 PPT 任务未找到异常，返回 HTTP 404 状态码。
     *
     * @param ex PPT 任务未找到异常
     * @return 包含 404 错误信息的响应
     */
    @ExceptionHandler(PptJobNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(PptJobNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(404, "Not Found", ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(404, "Not Found", ex.getMessage()));
    }

    /**
     * 处理 PPT 任务状态异常，返回 HTTP 400 状态码。
     *
     * @param ex PPT 任务状态异常（如对已完成任务执行不允许的操作）
     * @return 包含 400 错误信息的响应
     */
    @ExceptionHandler(PptJobStateException.class)
    ResponseEntity<ApiErrorResponse> handleBadRequest(PptJobStateException ex) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Bad Request", ex.getMessage()));
    }

    /**
     * 处理请求参数校验失败异常，返回 HTTP 400 状态码。
     * <p>从 {@link MethodArgumentNotValidException} 中提取第一个字段校验错误信息返回给客户端。</p>
     *
     * @param ex 方法参数校验失败异常
     * @return 包含 400 错误信息和具体校验失败的响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("validation failed");
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Bad Request", message));
    }

    /**
     * 处理文件存储异常，返回 HTTP 500 状态码。
     *
     * @param ex PPT 存储异常（如文件读写失败、磁盘空间不足等）
     * @return 包含 500 错误信息的响应
     */
    @ExceptionHandler(PptStorageException.class)
    ResponseEntity<ApiErrorResponse> handleStorage(PptStorageException ex) {
        log.error("ppt_storage_failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(500, "Internal Server Error", ex.getMessage()));
    }

    /**
     * 处理任务恢复异常，返回 HTTP 409 状态码。
     * <p>
     * 任务恢复请求被拒绝通常是因为当前状态不允许恢复，属于客户端不应重试的业务冲突。
     * </p>
     *
     * @param ex 任务恢复异常
     * @return 包含 409 错误信息的响应
     */
    @ExceptionHandler(PptJobResumeException.class)
    ResponseEntity<ApiErrorResponse> handleResume(PptJobResumeException ex) {
        log.warn("ppt_job_resume_rejected: jobId={}, message={}", ex.jobId(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(409, "Conflict", ex.getMessage()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        log.debug("client_disconnected: {}", ex.getMessage());
    }

    /**
     * 处理所有未预期的异常，返回 HTTP 500 状态码。
     * <p>作为兜底处理器，捕获未被其它异常处理方法匹配的所有异常，记录错误日志后返回通用错误信息。</p>
     *
     * @param ex 未预期的异常
     * @return 包含 500 通用错误信息的响应
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        log.error("unexpected_error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(500, "Internal Server Error", "internal server error"));
    }
}
