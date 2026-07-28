package domi.argenticpptmaster.domain;

/** Beautify 分析失败的有界底层分类。 */
public enum BeautifyPptxFailureKind {
    TIMEOUT,
    PROCESS_EXIT,
    SCHEMA_INVALID,
    UNSUPPORTED_CONTENT,
    RESOURCE_LIMIT,
    INTERNAL
}
