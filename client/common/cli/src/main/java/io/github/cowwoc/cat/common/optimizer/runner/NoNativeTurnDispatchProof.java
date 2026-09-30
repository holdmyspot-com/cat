package io.github.cowwoc.cat.common.optimizer.runner;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;
/**
 * Evidence that native semantic-turn dispatch never began.
 *
 * @param logicalCallId the recorded logicalCallId
 * @param runId the recorded runId
 * @param completionRef the recorded completionRef
 */
public record NoNativeTurnDispatchProof(String logicalCallId, Optional<String> runId, EvidenceRef completionRef)
{
    /**
     * operation at the native runner boundary.
     */
    public NoNativeTurnDispatchProof
    {
        RunnerIdentity.ObservationRules.check(logicalCallId, "logicalCallId");
        Objects.requireNonNull(runId, "runId");
        runId.ifPresent(value -> RunnerIdentity.ObservationRules.check(value, "runId"));
        Objects.requireNonNull(completionRef, "completionRef");
        if (!completionRef.collection().equals("native-dispatch-completions"))
            throw new IllegalArgumentException("wrong completion collection");
    }
}
