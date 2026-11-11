package domi.argenticpptmaster.domain;

/**
 * 单个模型在任务内的 chat token 累计。
 *
 * @param inputTokens  累计 prompt / input tokens
 * @param outputTokens 累计 completion / output tokens
 * @param totalTokens  input + output
 * @param callCount    成功计入 usage 的调用次数
 */
public record PptModelTokenUsage(long inputTokens, long outputTokens, long totalTokens, long callCount) {

    public static PptModelTokenUsage zero() {
        return new PptModelTokenUsage(0L, 0L, 0L, 0L);
    }

    public PptModelTokenUsage plus(long inputDelta, long outputDelta) {
        long nextInput = inputTokens + Math.max(0L, inputDelta);
        long nextOutput = outputTokens + Math.max(0L, outputDelta);
        return new PptModelTokenUsage(nextInput, nextOutput, nextInput + nextOutput, callCount + 1L);
    }
}
