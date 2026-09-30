package io.github.cowwoc.cat.common.optimizer.runner;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Owns one adapter call. Concurrent interruption and close request at most one backend interrupt.
 * <p><b>Thread Safety:</b> Public operations may be called concurrently; completion has one stable settlement.
 */
public final class ActiveCall implements AutoCloseable
{
    private final AdapterCall adapterCall;
    private final String logicalCallId;
    private final CompletableFuture<NestedRunCompletion> settled = new CompletableFuture<>();
    private final CompletionStage<NestedRunCompletion> publicCompletion = settled.minimalCompletionStage();
    private final String runId;
    private boolean interrupted;
    private boolean closed;

    /**
     * Owns one adapter call.
     *
     * @param logicalCallId the logical call identity
     * @param adapterCall the native call
     * @param runId the validated native run identity retained for settlement
     * @param validate the completion validator
     * @param failure the exceptional-completion mapper
     */
    ActiveCall(String logicalCallId, AdapterCall adapterCall, String runId,
        java.util.function.Function<NestedRunCompletion,
        NestedRunCompletion> validate, java.util.function.Function<Throwable, NestedRunCompletion> failure)
    {
        this.logicalCallId = logicalCallId;
        this.adapterCall = Objects.requireNonNull(adapterCall, "adapterCall");
        RunnerIdentity.ObservationRules.check(runId, "runId");
        this.runId = runId;
        adapterCall.completion().whenComplete((completion, thrown) ->
        {
            NestedRunCompletion value;
            try
            {
                if (thrown == null)
                value = validate.apply(completion);
            else
                value = failure.apply(thrown);
            }
            catch (Throwable exception)
            {
                value = failure.apply(exception);
            }
            settled.complete(value);
        });
    }

    /**
     * runId at the native runner boundary.
      *
      * @return the resulting value
     */
    public String runId()
    {
        return runId;
    }

    /**
     * completion at the native runner boundary.
      *
      * @return the resulting value
     */
    public CompletionStage<NestedRunCompletion> completion()
    {
        return publicCompletion;
    }

    /**
     * interrupt at the native runner boundary.
     */
    public void interrupt()
    {
        synchronized (this)
        {
            if (interrupted || settled.isDone())
                return;
            interrupted = true;
            adapterCall.interrupt();
        }
    }

    @Override
    public void close() throws NestedRunException
    {
        synchronized (this)
        {
            if (closed)
                return;
            closed = true;
            Exception cleanupFailure = null;
            try
            {
                interrupt();
            }
            catch (RuntimeException exception)
            {
                cleanupFailure = exception;
            }
            try
            {
                adapterCall.close();
            }
            catch (Exception exception)
            {
                if (cleanupFailure == null)
                    cleanupFailure = exception;
                else
                    cleanupFailure.addSuppressed(exception);
            }
            if (cleanupFailure != null)
            {
                NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.CLEANUP_FAILED, logicalCallId,
                    java.util.Optional.of(runId), cleanupFailure.toString(), java.util.List.of(),
                    java.util.Optional.empty(), java.util.Optional.empty());
                throw new NestedRunException(failure);
            }
        }
    }
}
