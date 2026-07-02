package domi.argenticpptmaster.domain;

public enum PptJobStatus {
    ACCEPTED,
    PREPARING,
    WAITING_CONFIRMATION,
    RUNNING_AGENT,
    EXPORTING,
    COMPLETED,
    FAILED,
    CANCELLED
}
