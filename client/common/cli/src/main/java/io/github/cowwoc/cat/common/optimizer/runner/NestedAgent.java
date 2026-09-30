package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates the single native adapter boundary before exposing caller-facing settlements.
 */
public final class NestedAgent
{
    private final Adapter adapter;
    private final EvidenceReader evidenceReader;

        /**
     * Creates a validated native agent boundary.
     *
     * @param adapter the adapter
     * @param evidenceReader the evidenceReader
     */
    public NestedAgent(Adapter adapter, EvidenceReader evidenceReader)
    {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader");
    }

        /**
     * Handles one logical native call.
     *
     * @param request the request
     * @param runId the preallocated runner identity
     * @return the native boundary result
     */
    public ActiveCall start(NestedRunRequest request, String runId) throws NestedRunException
    {
        Objects.requireNonNull(request, "request");
        HarnessRun.requireValidId(runId);
        AdapterCall call = null;
        try
        {
            validateContextEvidence(request);
            call = adapter.start(request, runId);
            if (!runId.equals(call.runId()))
                throw new IOException("Native run identity differs from the retained pre-start record");
            return active(request, call, runId);
        }
        catch (Exception exception)
        {
            if (call != null)
                cleanupRejectedCall(call, exception);
            throw new NestedRunException(failure(NestedRunFailureKind.START_FAILED, request,
                Optional.of(runId), exception.toString()));
        }
    }

        /**
     * Handles one logical native call.
     *
     * @param request the request
     * @param knownRunId the knownRunId
     * @return the native boundary result
     */
    public NativeCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
    {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(knownRunId, "knownRunId");
        try
        {
            validateContextEvidence(request);
            AdapterCallReconciliation result = adapter.reconcile(request, knownRunId);
            if (result instanceof AdapterCallReconciliation.Settled settled)
            {
                if (settled.completion() instanceof NestedRunCompletion.Succeeded succeeded &&
                    knownRunId.isPresent() && !knownRunId.orElseThrow().equals(succeeded.result().runId()) ||
                    settled.completion() instanceof NestedRunCompletion.Failed failed &&
                    (!failed.failure().logicalCallId().equals(request.logicalCallId()) ||
                    knownRunId.isPresent() && !knownRunId.equals(failed.failure().runId())))
                    return unknown(request, knownRunId, "reconciled settlement identity differs");
                NestedRunCompletion completion = validate(request, knownRunId, settled.completion());
                return new NativeCallReconciliation.Settled(completion);
            }
            if (result instanceof AdapterCallReconciliation.Active active)
            {
                AdapterCall call = active.call();
                try
                {
                    String runId = call.runId();
                    if (knownRunId.isPresent() && !knownRunId.orElseThrow().equals(runId))
                        throw new IllegalStateException("reconciled run identity differs");
                    return new NativeCallReconciliation.Active(active(request, call, runId));
                }
                catch (RuntimeException exception)
                {
                    cleanupRejectedCall(call, exception);
                    throw exception;
                }
            }
            NestedRunFailure failure = ((AdapterCallReconciliation.Unknown) result).failure();
            if (!failure.logicalCallId().equals(request.logicalCallId()) ||
                knownRunId.isPresent() && !knownRunId.equals(failure.runId()))
                return unknown(request, knownRunId, "reconciled unknown identity differs");
            NestedRunFailure retained = new NestedRunFailure(NestedRunFailureKind.NATIVE_STATE_UNKNOWN,
                request.logicalCallId(), failure.runId(), failure.reason(), failure.nativeEvidence(),
                failure.terminalResponseEvidence(), Optional.empty());
            return new NativeCallReconciliation.Unknown(retained);
        }
        catch (Exception exception)
        {
            return unknown(request, knownRunId, exception.toString());
        }
    }

    /**
     * Releases an adapter handle whose ownership cannot be transferred to the caller.
     *
     * @param call rejected native handle
     * @param failure primary failure to retain cleanup failures on
     */
    private static void cleanupRejectedCall(AdapterCall call, Throwable failure)
    {
        try
        {
            call.interrupt();
        }
        catch (RuntimeException cleanupFailure)
        {
            failure.addSuppressed(cleanupFailure);
        }
        try
        {
            call.close();
        }
        catch (Exception cleanupFailure)
        {
            failure.addSuppressed(cleanupFailure);
        }
    }

        /**
     * Handles one logical native call.
     *
     * @param request the request
     * @param call the call
     * @param runId the validated native run identity
     * @return the native boundary result
     */
    private ActiveCall active(NestedRunRequest request, AdapterCall call, String runId)
    {
        return new ActiveCall(request.logicalCallId(), call, runId,
            completion -> validate(request, Optional.of(runId), completion),
            thrown -> new NestedRunCompletion.Failed(failure(NestedRunFailureKind.TRANSPORT_FAILED, request,
                Optional.of(runId), thrown.toString())));
    }

    /**
     * Rehashes active edit authority or binds a sterile manifest to the exact request turns.
     *
     * @param request the proposed native call
     * @throws IOException if immutable manifest evidence cannot be verified
     * @throws NoSuchAlgorithmException if SHA-256 is unavailable
     */
    private void validateContextEvidence(NestedRunRequest request) throws IOException, NoSuchAlgorithmException
    {
        if (request.context() instanceof ProjectRunContext project &&
            project.mode() == ProjectWorkspaceMode.ACTIVE_WORKTREE)
        {
            verifiedBytes(project.confirmedEditableRootsRef().orElseThrow());
            verifiedBytes(project.liveCheckpointRef().orElseThrow());
        }
        if (request.context() instanceof PrivateGenerationContext generation)
        {
            verifiedBytes(generation.generationRequestRef());
            verifiedBytes(generation.readinessRef());
            verifiedBytes(generation.confirmedRootsRef());
        }
        if (!(request.context() instanceof SterileWorkspaceContext context))
            return;
        byte[] bytes = verifiedBytes(context.manifestRef());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        SterileWorkspaceManifest manifest = SterileWorkspaceManifest.fromBytes(bytes);
        if (manifest.kind() != context.kind() || manifest.turnSha256().size() != request.turns().size())
            throw new IOException("sterile manifest does not match call kind or turn count");
        for (int index = 0; index < request.turns().size(); index = index + 1)
        {
            byte[] turn = request.turns().get(index).getBytes(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(digest.digest(turn));
            if (!hash.equals(manifest.turnSha256().get(index)))
                throw new IOException("sterile manifest turn hash mismatch");
        }
        for (SterileWorkspaceEntry entry : manifest.entries())
            verifiedBytes(entry.contentEvidence());
    }

    /**
     * Reads and rehashes one immutable evidence item.
     *
     * @param reference the content address to verify
     * @return the exact retained bytes
     * @throws IOException if the item is unreadable or its hash differs
     * @throws NoSuchAlgorithmException if SHA-256 is unavailable
     */
    private byte[] verifiedBytes(EvidenceRef reference)
        throws IOException
    {
        return VerifiedEvidence.read(evidenceReader, reference);
    }

        /**
     * Handles one logical native call.
     *
     * @param request the request
     * @param knownRunId the knownRunId
     * @param completion the completion
     * @return the native boundary result
     */
    private NestedRunCompletion validate(NestedRunRequest request, Optional<String> knownRunId,
        NestedRunCompletion completion)
    {
        try
        {
            Objects.requireNonNull(completion, "completion");
            if (completion instanceof NestedRunCompletion.Succeeded succeeded)
            {
                NestedRunResult result = succeeded.result();
                if (knownRunId.isPresent() && !knownRunId.orElseThrow().equals(result.runId()) ||
                    !request.modelProfile().acceptsObserved(result.observedModelProfile()) ||
                    result.turnResults().size() != request.turns().size() ||
                    request.context() instanceof ProjectRunContext project &&
                    (project.mode() == ProjectWorkspaceMode.ACTIVE_WORKTREE &&
                        !result.executionProject().equals(project.snapshot().projectRoot()) ||
                    project.mode() == ProjectWorkspaceMode.ISOLATED_COPY &&
                        result.executionProject().equals(project.snapshot().projectRoot())) ||
                    request.context() instanceof PrivateGenerationContext generation &&
                        !result.executionProject().equals(generation.privateProject()))
                {
                    List<EvidenceRef> nativeEvidence = result.nativeEventEvidence().stream().
                        map(NativeEventEvidence::evidence).toList();
                    NestedRunFailure invalid = new NestedRunFailure(NestedRunFailureKind.RECEIPT_FAILED,
                        request.logicalCallId(), Optional.of(result.runId()), "result does not match request",
                        nativeEvidence, Optional.empty(), Optional.empty());
                    return new NestedRunCompletion.Failed(invalid);
                }
                return completion;
            }
            NestedRunFailure original = ((NestedRunCompletion.Failed) completion).failure();
            if (!original.logicalCallId().equals(request.logicalCallId()) ||
                knownRunId.isPresent() && !knownRunId.equals(original.runId()))
            {
                NestedRunFailure invalid = new NestedRunFailure(NestedRunFailureKind.RECEIPT_FAILED,
                    request.logicalCallId(), original.runId(), "failure identity mismatch: " + original.reason(),
                    original.nativeEvidence(), original.terminalResponseEvidence(), Optional.empty());
                return new NestedRunCompletion.Failed(invalid);
            }
            if (original.noNativeTurnDispatchProof().isEmpty())
                return completion;
            try
            {
                NoNativeTurnDispatchProof proof = original.noNativeTurnDispatchProof().orElseThrow();
                if (!proof.logicalCallId().equals(request.logicalCallId()) || !proof.runId().equals(original.runId()) ||
                    original.terminalResponseEvidence().isPresent())
                    throw new IllegalArgumentException("contradictory dispatch proof");
                byte[] bytes = verifiedBytes(proof.completionRef());
                NativeDispatchCompletion receipt = NativeDispatchCompletion.fromBytes(bytes);
                if (!receipt.logicalCallId().equals(proof.logicalCallId()) || !receipt.runId().equals(proof.runId()))
                    throw new IOException("completion identity mismatch");
                return new NestedRunCompletion.Failed(original.verified());
            }
            catch (IOException | RuntimeException exception)
            {
                NestedRunFailure invalid = new NestedRunFailure(NestedRunFailureKind.RECEIPT_FAILED,
                    request.logicalCallId(), original.runId(), "invalid " + original.kind() + " proof: " + exception,
                    original.nativeEvidence(), original.terminalResponseEvidence(), Optional.empty());
                return new NestedRunCompletion.Failed(invalid);
            }
        }
        catch (RuntimeException exception)
        {
            return new NestedRunCompletion.Failed(failure(NestedRunFailureKind.RECEIPT_FAILED, request,
                knownRunId, exception.toString()));
        }
    }

        /**
     * Handles one logical native call.
     *
     * @param request the request
     * @param knownRunId the knownRunId
     * @param reason the reason
     * @return the native boundary result
     */
    private NativeCallReconciliation.Unknown unknown(NestedRunRequest request, Optional<String> knownRunId,
        String reason)
    {
        return new NativeCallReconciliation.Unknown(failure(NestedRunFailureKind.NATIVE_STATE_UNKNOWN,
            request, knownRunId, reason));
    }

        /**
     * Handles one logical native call.
     *
     * @param kind the kind
     * @param request the request
     * @param runId the runId
     * @param reason the reason
     * @return the native boundary result
     */
    private static NestedRunFailure failure(NestedRunFailureKind kind, NestedRunRequest request,
        Optional<String> runId, String reason)
    {
        return new NestedRunFailure(kind, request.logicalCallId(), runId, reason,
            List.of(), Optional.empty(), Optional.empty());
    }
}
