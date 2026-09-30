package io.github.cowwoc.cat.common.optimizer.runner;
import java.util.Optional;
/**
 * Harness-specific native session provider using the installed runner infrastructure.
 */
public interface Adapter
{
    /**
     * Starts one fresh native session for the validated logical call and transfers its handle to the agent.
     *
     * @param request the native call request
     * @param runId the preallocated runner identity retained before native dispatch
     * @return the exclusive native session handle
     * @throws Exception if a native session cannot be started
     */
    AdapterCall start(NestedRunRequest request, String runId) throws Exception;
    /**
     * Finds an earlier call without starting a new session.
     * An active handle remains adapter-owned until the agent validates its identity and wraps it.
     *
     * @param request the original logical call request
     * @param knownRunId the native run identity if it was durably observed
     * @return the earlier call's native state
     * @throws Exception if native reconciliation cannot establish its state
     */
    AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId) throws Exception;
}
