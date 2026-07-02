package domi.argenticpptmaster.web.dto;

import java.time.Instant;

/**
 * 标准 API 错误响应 DTO。
 * <p>
 * 用于在 REST API 返回错误时，向客户端提供统一的错误信息结构。
 * 包含时间戳、HTTP 状态码、错误标识和人类可读的错误描述。
 * 通过静态工厂方法 {@link #of(int, String, String)} 快速创建实例。
 * </p>
 *
 * @param timestamp 错误发生的时间戳
 * @param status    HTTP 状态码
 * @param error     错误标识（如 "Not Found"、"Bad Request"）
 * @param message   人类可读的错误描述信息
 */
public record ApiErrorResponse(Instant timestamp, int status, String error, String message) {

    /**
     * 创建标准 API 错误响应的静态工厂方法。
     *
     * @param status  HTTP 状态码
     * @param error   错误标识
     * @param message 错误描述信息
     * @return 包含当前时间戳的 ApiErrorResponse 实例
     */
    public static ApiErrorResponse of(int status, String error, String message) {
        return new ApiErrorResponse(Instant.now(), status, error, message);
    }
}
