package io.github.cowwoc.cat.common.optimizer.runner;
import java.util.Objects;
/**
 * Adapter-private view of an earlier logical call.
 */
public sealed interface AdapterCallReconciliation permits AdapterCallReconciliation.Settled,
    AdapterCallReconciliation.Active, AdapterCallReconciliation.Unknown
{
    /**
     * Settled native-call state.
     *
     * @param completion the completion
     */
    record Settled(NestedRunCompletion completion) implements AdapterCallReconciliation
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
    record Active(AdapterCall call) implements AdapterCallReconciliation
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
    record Unknown(NestedRunFailure failure) implements AdapterCallReconciliation
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
