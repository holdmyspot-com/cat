package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Preserves an unsuccessful call and its native evidence without inventing a semantic outcome.
 */
public final class NestedRunFailure
{
    private final NestedRunFailureKind kind;
    private final String logicalCallId;
    private final Optional<String> runId;
    private final String reason;
    private final List<EvidenceRef> nativeEvidence;
    private final Optional<EvidenceRef> terminalResponseEvidence;
    private final Optional<NoNativeTurnDispatchProof> noNativeTurnDispatchProof;
    private final boolean verifiedNoDispatch;

    /**
     * Creates an untrusted failure report. Only the native agent may validate its no-dispatch proof.
     *
     * @param kind the failure kind
     * @param logicalCallId the logical call identity
     * @param runId the optional native run identity
     * @param reason the nonblank failure reason
     * @param nativeEvidence retained native evidence
     * @param terminalResponseEvidence an optional terminal semantic response marker
     * @param noNativeTurnDispatchProof an optional claimed no-dispatch proof
     */
    public NestedRunFailure(NestedRunFailureKind kind, String logicalCallId, Optional<String> runId,
        String reason, List<EvidenceRef> nativeEvidence, Optional<EvidenceRef> terminalResponseEvidence,
        Optional<NoNativeTurnDispatchProof> noNativeTurnDispatchProof)
    {
        this(kind, logicalCallId, runId, reason, nativeEvidence, terminalResponseEvidence,
            noNativeTurnDispatchProof, false);
    }

    /**
     * Copies a validated or unvalidated failure report.
     *
     * @param kind the failure kind
     * @param logicalCallId the logical call identity
     * @param runId the optional native run identity
     * @param reason the nonblank reason
     * @param nativeEvidence retained native evidence
     * @param terminalResponseEvidence the optional terminal marker
     * @param noNativeTurnDispatchProof the optional no-dispatch proof
     * @param verifiedNoDispatch whether exact proof bytes have been verified
     */
    private NestedRunFailure(NestedRunFailureKind kind, String logicalCallId, Optional<String> runId,
        String reason, List<EvidenceRef> nativeEvidence, Optional<EvidenceRef> terminalResponseEvidence,
        Optional<NoNativeTurnDispatchProof> noNativeTurnDispatchProof, boolean verifiedNoDispatch)
    {
        this.kind = Objects.requireNonNull(kind, "kind");
        RunnerIdentity.ObservationRules.check(logicalCallId, "logicalCallId");
        this.logicalCallId = logicalCallId;
        this.runId = Objects.requireNonNull(runId, "runId");
        runId.ifPresent(value -> RunnerIdentity.ObservationRules.check(value, "runId"));
        RunnerIdentity.ObservationRules.check(reason, "reason");
        this.reason = reason;
        this.nativeEvidence = List.copyOf(nativeEvidence);
        this.terminalResponseEvidence = Objects.requireNonNull(terminalResponseEvidence, "terminalResponseEvidence");
        this.noNativeTurnDispatchProof = Objects.requireNonNull(noNativeTurnDispatchProof,
            "noNativeTurnDispatchProof");
        this.verifiedNoDispatch = verifiedNoDispatch;
    }

    /**
     * Returns a copy after the agent has verified exact immutable receipt bytes.
     *
     * @return a verified failure report
     */
    NestedRunFailure verified()
    {
        return new NestedRunFailure(kind, logicalCallId, runId, reason, nativeEvidence,
            terminalResponseEvidence, noNativeTurnDispatchProof, true);
    }

    /**
     * Returns the original failure kind.
     *
     * @return the failure kind
     */
    public NestedRunFailureKind kind()
    {
        return kind;
    }

    /**
     * Returns the logical call identity.
     *
     * @return the logical call identity
     */
    public String logicalCallId()
    {
        return logicalCallId;
    }

    /**
     * Returns the optional native run identity.
     *
     * @return the optional native run identity
     */
    public Optional<String> runId()
    {
        return runId;
    }

    /**
     * Returns the failure reason.
     *
     * @return the reason
     */
    public String reason()
    {
        return reason;
    }

    /**
     * Returns immutable native evidence references.
     *
     * @return the evidence references
     */
    public List<EvidenceRef> nativeEvidence()
    {
        return nativeEvidence;
    }

    /**
     * Returns the optional terminal response marker.
     *
     * @return the terminal response marker
     */
    public Optional<EvidenceRef> terminalResponseEvidence()
    {
        return terminalResponseEvidence;
    }

    /**
     * Returns the claimed no-dispatch proof, if any.
     *
     * @return the optional proof
     */
    public Optional<NoNativeTurnDispatchProof> noNativeTurnDispatchProof()
    {
        return noNativeTurnDispatchProof;
    }

    /**
     * Reports whether exact no-dispatch proof has been validated by the native agent.
     *
     * @return the supported retry safety
     */
    public RetrySafety retrySafety()
    {
        if (verifiedNoDispatch && noNativeTurnDispatchProof.isPresent() && terminalResponseEvidence.isEmpty())
            return RetrySafety.PROVEN_OUTCOME_FREE;
        return RetrySafety.NOT_PROVEN_OUTCOME_FREE;
    }
}
