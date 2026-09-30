package io.github.cowwoc.cat.common.optimizer.runner;
import java.util.Objects;
/**
 * Caller-facing view of an earlier logical native call.
 */
public sealed interface NativeCallReconciliation permits NativeCallReconciliation.Settled,
    NativeCallReconciliation.Active, NativeCallReconciliation.Unknown
{
    /**
     * Settled native-call state.
     *
     * @param completion the completion
     */
    record Settled(NestedRunCompletion completion) implements NativeCallReconciliation
    {
        /**
         * Validates the completion.
         *
         * @param completion the completion
         */
        public Settled
        {
            Objects.requireNonNull(completion, "completion");
        }
    }
    /**
     * Active native-call state.
     *
     * @param call the call
     */
    record Active(ActiveCall call) implements NativeCallReconciliation
    {
        /**
         * Validates the call.
         *
         * @param call the call
         */
        public Active
        {
            Objects.requireNonNull(call, "call");
        }
    }
    /**
     * Unknown native-call state.
     *
     * @param failure the failure
     */
    record Unknown(NestedRunFailure failure) implements NativeCallReconciliation
    {
        /**
         * Validates the failure.
         *
         * @param failure the failure
         */
        public Unknown
        {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
