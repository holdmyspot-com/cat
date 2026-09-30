package io.github.cowwoc.cat.common.optimizer.runner;
import java.util.concurrent.CompletionStage;
/**
 * Exclusive handle to one native session owned by an adapter.
 */
public interface AdapterCall extends AutoCloseable
{
    /**
     * runId at the native runner boundary.
      *
      * @return the resulting value
     */
    String runId();
    /**
     * completion at the native runner boundary.
      *
      * @return the resulting value
     */
    CompletionStage<NestedRunCompletion> completion();
    /**
     * interrupt at the native runner boundary.
     */
    void interrupt();
    @Override void close() throws java.io.IOException;
}
