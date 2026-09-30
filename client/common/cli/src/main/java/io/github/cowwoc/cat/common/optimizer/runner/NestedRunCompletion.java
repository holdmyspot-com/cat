package io.github.cowwoc.cat.common.optimizer.runner;
import java.util.Objects;
/**
 * A typed settlement of one native call.
 */
public sealed interface NestedRunCompletion permits NestedRunCompletion.Succeeded, NestedRunCompletion.Failed
{
    /**
     * Succeeded native-call state.
     *
     * @param result the result
     */
    record Succeeded(NestedRunResult result) implements NestedRunCompletion
    {
        /**
         * Validates the result.
         *
         * @param result the result
         */
        public Succeeded
        {
            Objects.requireNonNull(result, "result");
        }
    }
    /**
     * Failed native-call state.
     *
     * @param failure the failure
     */
    record Failed(NestedRunFailure failure) implements NestedRunCompletion
    {
        /**
         * Validates the failure.
         *
         * @param failure the failure
         */
        public Failed
        {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
