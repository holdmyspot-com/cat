package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.testng.annotations.Test;

/**
 * Tests proof-backed retries and ownership of a native adapter call.
 */
public final class NestedAgentTest
{
    private static final String RUN_ID = "run-001";

    /**
     * Creates a test instance.
     */
    public NestedAgentTest()
    {
    }

    /**
     * Verifies a rehashed, identity-matched no-dispatch receipt permits retry.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     */
    @Test
    public void acceptsVerifiedNoDispatchProof() throws NoSuchAlgorithmException, NestedRunException
    {
        NativeDispatchCompletion receipt = new NativeDispatchCompletion(1, "call", Optional.of(RUN_ID),
            Instant.parse("2026-01-01T00:00:00Z"));
        byte[] bytes = receipt.bytes();
        EvidenceRef reference = new EvidenceRef("native-dispatch-completions",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call",
            Optional.of(RUN_ID), "failed before dispatch", List.of(), Optional.empty(),
            Optional.of(new NoNativeTurnDispatchProof("call", Optional.of(RUN_ID), reference)));
        requireThat(failure.retrySafety(), "unverifiedRetrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
        FakeAdapter adapter = new FakeAdapter(new NestedRunCompletion.Failed(failure));
        NestedRunCompletion completed = new NestedAgent(adapter, ignored -> bytes).start(request(), RUN_ID).
            completion().toCompletableFuture().join();
        NestedRunFailure observed = ((NestedRunCompletion.Failed) completed).failure();
        requireThat(observed.retrySafety(), "retrySafety").isEqualTo(RetrySafety.PROVEN_OUTCOME_FREE);
    }

    /**
     * Verifies a corrupt claimed receipt loses retry authority and retains its original failure kind in the reason.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     */
    @Test
    public void rejectsCorruptNoDispatchProof() throws NoSuchAlgorithmException, NestedRunException
    {
        byte[] bytes = new NativeDispatchCompletion(1, "call", Optional.of(RUN_ID),
            Instant.parse("2026-01-01T00:00:00Z")).bytes();
        EvidenceRef reference = new EvidenceRef("native-dispatch-completions",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call",
            Optional.of(RUN_ID), "transport failed", List.of(), Optional.empty(),
            Optional.of(new NoNativeTurnDispatchProof("call", Optional.of(RUN_ID), reference)));
        NestedRunCompletion completed = new NestedAgent(new FakeAdapter(new NestedRunCompletion.Failed(failure)),
            ignored -> new byte[]{1, 2, 3}).start(request(), RUN_ID).completion().toCompletableFuture().join();
        NestedRunFailure observed = ((NestedRunCompletion.Failed) completed).failure();
        requireThat(observed.kind(), "failureKind").isEqualTo(NestedRunFailureKind.RECEIPT_FAILED);
        requireThat(observed.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
        requireThat(observed.reason(), "reason").contains("TRANSPORT_FAILED");
    }

    /**
     * Verifies a terminal semantic marker contradicts an otherwise valid no-dispatch receipt.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     * @throws NestedRunException if native start fails
     */
    @Test
    public void rejectsProofWithTerminalMarker() throws NoSuchAlgorithmException, NestedRunException
    {
        byte[] bytes = new NativeDispatchCompletion(1, "call", Optional.of(RUN_ID),
            Instant.parse("2026-01-01T00:00:00Z")).bytes();
        EvidenceRef reference = new EvidenceRef("native-dispatch-completions",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call",
            Optional.of(RUN_ID), "transport failed", List.of(),
            Optional.of(new EvidenceRef("responses", "b".repeat(64))),
            Optional.of(new NoNativeTurnDispatchProof("call", Optional.of(RUN_ID), reference)));
        NestedRunCompletion completed = new NestedAgent(new FakeAdapter(new NestedRunCompletion.Failed(failure)),
            ignored -> bytes).start(request(), RUN_ID).completion().toCompletableFuture().join();
        NestedRunFailure observed = ((NestedRunCompletion.Failed) completed).failure();
        requireThat(observed.kind(), "failureKind").isEqualTo(NestedRunFailureKind.RECEIPT_FAILED);
        requireThat(observed.terminalResponseEvidence(), "terminalMarker").
            isEqualTo(failure.terminalResponseEvidence());
        requireThat(observed.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
    }

    /**
     * Verifies a rehashed but identity-mismatched receipt cannot authorize retry.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     * @throws NestedRunException if native start fails
     */
    @Test
    public void rejectsWrongReceiptIdentity() throws NoSuchAlgorithmException, NestedRunException
    {
        byte[] bytes = new NativeDispatchCompletion(1, "other", Optional.of(RUN_ID),
            Instant.parse("2026-01-01T00:00:00Z")).bytes();
        EvidenceRef reference = new EvidenceRef("native-dispatch-completions",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call",
            Optional.of(RUN_ID), "transport failed", List.of(), Optional.empty(),
            Optional.of(new NoNativeTurnDispatchProof("call", Optional.of(RUN_ID), reference)));
        NestedRunCompletion completed = new NestedAgent(new FakeAdapter(new NestedRunCompletion.Failed(failure)),
            ignored -> bytes).start(request(), RUN_ID).completion().toCompletableFuture().join();
        NestedRunFailure observed = ((NestedRunCompletion.Failed) completed).failure();
        requireThat(observed.kind(), "failureKind").isEqualTo(NestedRunFailureKind.RECEIPT_FAILED);
        requireThat(observed.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
    }

    /**
     * Verifies loss after possible native dispatch cannot be retried without positive no-dispatch proof.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void postDispatchLossIsNotRetryable() throws NestedRunException
    {
        NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call",
            Optional.of(RUN_ID), "turn write may have started", List.of(), Optional.empty(), Optional.empty());
        NestedRunCompletion completed = new NestedAgent(new FakeAdapter(new NestedRunCompletion.Failed(failure)),
            ignored -> new byte[0]).start(request(), RUN_ID).completion().toCompletableFuture().join();
        NestedRunFailure observed = ((NestedRunCompletion.Failed) completed).failure();
        requireThat(observed.kind(), "kind").isEqualTo(NestedRunFailureKind.TRANSPORT_FAILED);
        requireThat(observed.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
    }

    /**
     * Verifies receipt loss after a semantic response retains the marker and cannot be retried.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void postResponseReceiptLossIsNotRetryable() throws NestedRunException
    {
        EvidenceRef terminal = new EvidenceRef("responses", "a".repeat(64));
        NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.RECEIPT_FAILED, "call",
            Optional.of(RUN_ID), "receipt lost", List.of(), Optional.of(terminal), Optional.empty());
        NestedRunCompletion completed = new NestedAgent(new FakeAdapter(new NestedRunCompletion.Failed(failure)),
            ignored -> new byte[0]).start(request(), RUN_ID).completion().toCompletableFuture().join();
        NestedRunFailure observed = ((NestedRunCompletion.Failed) completed).failure();
        requireThat(observed.kind(), "kind").isEqualTo(NestedRunFailureKind.RECEIPT_FAILED);
        requireThat(observed.terminalResponseEvidence(), "terminalMarker").isEqualTo(Optional.of(terminal));
        requireThat(observed.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
    }

    /**
     * Verifies completion is stable and repeated interruption and close reach the adapter only once.
     */
    @Test
    public void ownsOneAdapterCall() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        NestedAgent agent = new NestedAgent(adapter, ignored -> new byte[0]);
        try (ActiveCall call = agent.start(request(), RUN_ID))
        {
            CompletionStage<NestedRunCompletion> stage = call.completion();
            requireThat(call.completion(), "stableCompletion").isEqualTo(stage);
            call.interrupt();
            call.interrupt();
        }
        requireThat(adapter.starts, "starts").isEqualTo(1);
        requireThat(adapter.requestedRunId, "requestedRunId").isEqualTo(RUN_ID);
        requireThat(adapter.call.interrupts, "interrupts").isEqualTo(1);
        requireThat(adapter.call.closes, "closes").isEqualTo(1);
    }

    /**
     * Verifies an invalid preallocated ID is rejected before native dispatch.
     *
     * @throws NestedRunException if the invalid ID unexpectedly reaches native start
     */
    @Test
    public void rejectsInvalidPreallocatedRunIdentity() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        NestedAgent agent = new NestedAgent(adapter, ignored -> new byte[0]);
        try
        {
            agent.start(request(), "run");
            throw new AssertionError("invalid native run ID was accepted");
        }
        catch (IllegalArgumentException expected)
        {
            requireThat(expected.getMessage(), "message").contains("Run id");
        }
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies the native adapter receives the retained ID and a different handle is closed before exposure.
     */
    @Test
    public void rejectsRunIdentityDifferentFromPreStart()
    {
        FakeAdapter adapter = new FakeAdapter(null);
        NestedAgent agent = new NestedAgent(adapter, ignored -> new byte[0]);
        try
        {
            agent.start(request(), "different-001");
            throw new AssertionError("different native run ID was accepted");
        }
        catch (NestedRunException exception)
        {
            requireThat(exception.failure().kind(), "kind").isEqualTo(NestedRunFailureKind.START_FAILED);
            requireThat(exception.failure().runId(), "runId").isEqualTo(Optional.of("different-001"));
        }
        requireThat(adapter.requestedRunId, "requestedRunId").isEqualTo("different-001");
        requireThat(adapter.call.interrupts, "interrupts").isEqualTo(1);
        requireThat(adapter.call.closes, "closes").isEqualTo(1);
    }

    /**
     * Verifies a mismatched settled run becomes unknown without starting a fresh native session.
     */
    @Test
    public void settledReconciliationRejectsRunMismatch()
    {
        NestedRunCompletion completion = new NestedRunCompletion.Failed(new NestedRunFailure(
            NestedRunFailureKind.TRANSPORT_FAILED, "call", Optional.of(RUN_ID), "transport",
            List.of(), Optional.empty(), Optional.empty()));
        FakeAdapter adapter = new FakeAdapter(completion);
        adapter.reconciled = new AdapterCallReconciliation.Settled(completion);
        NativeCallReconciliation reconciliation = new NestedAgent(adapter, ignored -> new byte[0]).reconcile(
            request(), Optional.of("different-001"));
        requireThat(reconciliation instanceof NativeCallReconciliation.Unknown, "unknown").isTrue();
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies a sterile manifest must be rehashed before the native adapter starts.
     */
    @Test
    public void rejectsCorruptSterileManifestBeforeStart()
    {
        FakeAdapter adapter = new FakeAdapter(null);
        NestedAgent agent = new NestedAgent(adapter, ignored -> new byte[]{1, 2, 3});
        NestedRunRequest sterile = new NestedRunRequest("call", EvidencePurpose.VALIDATION,
            new SterileWorkspaceContext(SterileWorkspaceKind.CONTRACT_DERIVATION,
                new EvidenceRef("sterile-workspace-manifests", "a".repeat(64))),
            List.of("turn"), SessionMode.SINGLE_TURN,
            new ModelProfile("model", Optional.empty(), Map.of()), List.of());
        try
        {
            agent.start(sterile, RUN_ID);
            throw new AssertionError("invalid manifest was accepted");
        }
        catch (NestedRunException exception)
        {
            requireThat(exception.failure().kind(), "kind").isEqualTo(NestedRunFailureKind.START_FAILED);
        }
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies a matching immutable sterile manifest admits exactly one native start.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     * @throws NestedRunException if the request cannot start
     */
    @Test
    public void acceptsMatchingSterileManifest() throws NoSuchAlgorithmException, NestedRunException
    {
        String turnHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            "turn".getBytes(StandardCharsets.UTF_8)));
        SterileWorkspaceManifest manifest = new SterileWorkspaceManifest(1,
            SterileWorkspaceKind.CONTRACT_DERIVATION, new EvidenceRef("semantic-requests", "b".repeat(64)),
            List.of(turnHash), List.of());
        byte[] bytes = manifest.bytes();
        EvidenceRef manifestRef = new EvidenceRef("sterile-workspace-manifests",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        NestedRunRequest sterile = new NestedRunRequest("call", EvidencePurpose.VALIDATION,
            new SterileWorkspaceContext(SterileWorkspaceKind.CONTRACT_DERIVATION, manifestRef),
            List.of("turn"), SessionMode.SINGLE_TURN,
            new ModelProfile("model", Optional.empty(), Map.of()), List.of());
        FakeAdapter adapter = new FakeAdapter(null);
        try (ActiveCall ignored = new NestedAgent(adapter, reference -> bytes).start(sterile, RUN_ID))
        {
            requireThat(ignored.runId(), "runId").isEqualTo(RUN_ID);
            requireThat(adapter.starts, "starts").isEqualTo(1);
        }
    }

    /**
     * Verifies that an evaluator cannot start when a declared authority file is absent or has changed.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     */
    @Test
    public void rejectsMissingOrChangedSterileInput() throws NoSuchAlgorithmException
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] authority = "frozen evaluator rubric".getBytes(StandardCharsets.UTF_8);
        String authorityHash = HexFormat.of().formatHex(digest.digest(authority));
        EvidenceRef authorityRef = new EvidenceRef("sterile-inputs", authorityHash);
        String turnHash = HexFormat.of().formatHex(digest.digest("evaluate".getBytes(StandardCharsets.UTF_8)));
        SterileWorkspaceManifest manifest = new SterileWorkspaceManifest(1, SterileWorkspaceKind.EVALUATOR,
            new EvidenceRef("evaluator-requests", "b".repeat(64)), List.of(turnHash),
            List.of(new SterileWorkspaceEntry(Path.of("authority/rubric.txt"),
                SterileWorkspaceInputRole.AUTHORITY, authorityRef, authorityHash)));
        byte[] manifestBytes = manifest.bytes();
        EvidenceRef manifestRef = new EvidenceRef("sterile-workspace-manifests",
            HexFormat.of().formatHex(digest.digest(manifestBytes)));
        NestedRunRequest request = new NestedRunRequest("evaluate-call", EvidencePurpose.DEVELOPMENT,
            new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, manifestRef),
            List.of("evaluate"), SessionMode.SINGLE_TURN,
            new ModelProfile("model", Optional.empty(), Map.of()), List.of());

        FakeAdapter missingAdapter = new FakeAdapter(null);
        NestedAgent missingInput = new NestedAgent(missingAdapter, reference ->
        {
            if (reference.equals(manifestRef))
                return manifestBytes;
            throw new IOException("declared authority file is missing");
        });
        try
        {
            missingInput.start(request, RUN_ID);
            throw new AssertionError("missing evaluator authority was accepted");
        }
        catch (NestedRunException expected)
        {
            requireThat(expected.failure().kind(), "failureKind").isEqualTo(NestedRunFailureKind.START_FAILED);
        }
        requireThat(missingAdapter.starts, "missingInputStarts").isEqualTo(0);

        FakeAdapter changedAdapter = new FakeAdapter(null);
        NestedAgent changedInput = new NestedAgent(changedAdapter, reference ->
        {
            if (reference.equals(manifestRef))
                return manifestBytes;
            return "changed rubric".getBytes(StandardCharsets.UTF_8);
        });
        try
        {
            changedInput.start(request, RUN_ID);
            throw new AssertionError("changed evaluator authority was accepted");
        }
        catch (NestedRunException expected)
        {
            requireThat(expected.failure().kind(), "failureKind").isEqualTo(NestedRunFailureKind.START_FAILED);
        }
        requireThat(changedAdapter.starts, "changedInputStarts").isEqualTo(0);
    }

    /**
     * Verifies cleanup still closes the native handle after interruption fails.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void closesHandleAfterInterruptFailure() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        adapter.call.interruptFailure = true;
        try (ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            requireThat(call.runId(), "runId").isEqualTo(RUN_ID);
        }
        catch (NestedRunException exception)
        {
            requireThat(exception.failure().kind(), "kind").isEqualTo(NestedRunFailureKind.CLEANUP_FAILED);
        }
        requireThat(adapter.call.closes, "closes").isEqualTo(1);
    }

    /**
     * Verifies unknown reconciliation retains native evidence without starting a replacement session.
     */
    @Test
    public void preservesUnknownNativeEvidence()
    {
        FakeAdapter adapter = new FakeAdapter(null);
        EvidenceRef evidence = new EvidenceRef("events", "a".repeat(64));
        NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.NATIVE_STATE_UNKNOWN, "call",
            Optional.of(RUN_ID), "native state unknown", List.of(evidence), Optional.empty(), Optional.empty());
        adapter.reconciled = new AdapterCallReconciliation.Unknown(failure);
        NativeCallReconciliation result = new NestedAgent(adapter, ignored -> new byte[0]).reconcile(
            request(), Optional.of(RUN_ID));
        NestedRunFailure observed = ((NativeCallReconciliation.Unknown) result).failure();
        requireThat(observed.nativeEvidence(), "nativeEvidence").contains(evidence);
        requireThat(observed.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies active reconciliation exposes a caller-owned handle without a new native start.
     *
     * @throws NestedRunException if native cleanup fails
     */
    @Test
    public void reconcilesActiveCallWithoutRestart() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        NativeCallReconciliation result = new NestedAgent(adapter, ignored -> new byte[0]).reconcile(
            request(), Optional.of(RUN_ID));
        requireThat(result instanceof NativeCallReconciliation.Active, "active").isTrue();
        try (ActiveCall call = ((NativeCallReconciliation.Active) result).call())
        {
            requireThat(call.runId(), "runId").isEqualTo(RUN_ID);
        }
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies an invalid reconciled handle is interrupted and closed before reporting unknown native state.
     */
    @Test
    public void closesInvalidReconciledHandle()
    {
        FakeAdapter adapter = new FakeAdapter(null);
        adapter.call.badRunId = true;
        NativeCallReconciliation result = new NestedAgent(adapter, ignored -> new byte[0]).reconcile(
            request(), Optional.of(RUN_ID));
        requireThat(result instanceof NativeCallReconciliation.Unknown, "unknown").isTrue();
        requireThat(adapter.call.interrupts, "interrupts").isEqualTo(1);
        requireThat(adapter.call.closes, "closes").isEqualTo(1);
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies a settled starting slot can be reconciled without a known run identity.
     */
    @Test
    public void reconcilesSettledStartingCall()
    {
        NestedRunCompletion completed = new NestedRunCompletion.Succeeded(
            successfulResult(Path.of("copy").toAbsolutePath().normalize()));
        FakeAdapter adapter = new FakeAdapter(completed);
        adapter.reconciled = new AdapterCallReconciliation.Settled(completed);
        NativeCallReconciliation result = new NestedAgent(adapter, ignored -> new byte[0]).reconcile(
            request(), Optional.empty());
        requireThat(result instanceof NativeCallReconciliation.Settled, "settled").isTrue();
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies adapter reconciliation exceptions become typed unknown state without restarting.
     */
    @Test
    public void mapsReconcileExceptionToUnknown()
    {
        FakeAdapter adapter = new FakeAdapter(null);
        adapter.reconcileFailure = true;
        NativeCallReconciliation result = new NestedAgent(adapter, ignored -> new byte[0]).reconcile(
            request(), Optional.of(RUN_ID));
        NestedRunFailure failure = ((NativeCallReconciliation.Unknown) result).failure();
        requireThat(failure.kind(), "kind").isEqualTo(NestedRunFailureKind.NATIVE_STATE_UNKNOWN);
        requireThat(failure.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies active-worktree edit authority is rehashed before a native call can start.
     */
    @Test
    public void rejectsCorruptActiveAuthority()
    {
        FakeAdapter adapter = new FakeAdapter(null);
        ProjectExecutionSnapshot snapshot = new ProjectExecutionSnapshot(
            Path.of("project").toAbsolutePath().normalize(), new EvidenceRef("snapshots", "a".repeat(64)));
        ProjectRunContext context = new ProjectRunContext(snapshot, ProjectWorkspaceMode.ACTIVE_WORKTREE,
            Optional.of(new EvidenceRef("roots", "a".repeat(64))),
            Optional.of(new EvidenceRef("checkpoints", "b".repeat(64))));
        NestedRunRequest active = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT,
            context, List.of("turn"), SessionMode.SINGLE_TURN,
            new ModelProfile("model", Optional.empty(), Map.of()), List.of());
        try
        {
            new NestedAgent(adapter, ignored -> new byte[]{1}).start(active, RUN_ID);
            throw new AssertionError("corrupt active authority was accepted");
        }
        catch (NestedRunException exception)
        {
            requireThat(exception.failure().kind(), "kind").isEqualTo(NestedRunFailureKind.START_FAILED);
        }
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * Verifies an isolated-copy call cannot claim that it executed in the live project root.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void rejectsCopyResultAtLiveRoot() throws NestedRunException
    {
        NestedRunResult result = successfulResult(Path.of("project").toAbsolutePath().normalize());
        NestedAgent agent = new NestedAgent(new FakeAdapter(new NestedRunCompletion.Succeeded(result)),
            ignored -> new byte[0]);
        NestedRunCompletion completion = agent.start(request(), RUN_ID).completion().toCompletableFuture().join();
        NestedRunFailure failure = ((NestedRunCompletion.Failed) completion).failure();
        requireThat(failure.kind(), "kind").isEqualTo(NestedRunFailureKind.RECEIPT_FAILED);
        requireThat(failure.nativeEvidence().size(), "nativeEvidenceCount").isEqualTo(1);
    }

    /**
     * Verifies an active call reports the pinned live root after its edit authority is rehashed.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     * @throws NestedRunException if native start fails
     */
    @Test
    public void acceptsLiveRootForActiveCall() throws NoSuchAlgorithmException, NestedRunException
    {
        Path root = Path.of("project").toAbsolutePath().normalize();
        byte[] evidence = {1, 2, 3};
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence));
        ProjectRunContext context = new ProjectRunContext(new ProjectExecutionSnapshot(root,
            new EvidenceRef("snapshots", "a".repeat(64))), ProjectWorkspaceMode.ACTIVE_WORKTREE,
            Optional.of(new EvidenceRef("roots", hash)), Optional.of(new EvidenceRef("checkpoints", hash)));
        NestedRunRequest active = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, context,
            List.of("turn"), SessionMode.SINGLE_TURN,
            new ModelProfile("model", Optional.empty(), Map.of()), List.of());
        NestedAgent agent = new NestedAgent(new FakeAdapter(new NestedRunCompletion.Succeeded(successfulResult(root))),
            ignored -> evidence);
        NestedRunCompletion completed = agent.start(active, RUN_ID).completion().toCompletableFuture().join();
        requireThat(completed instanceof NestedRunCompletion.Succeeded, "succeeded").isTrue();
    }

    /**
     * A candidate generation receipt must name the private worktree, not the selected project.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     * @throws NestedRunException if native start fails
     */
    @Test
    public void rejectsGenerationResultAtSelectedRoot() throws NoSuchAlgorithmException, NestedRunException
    {
        byte[] evidence = {1, 2, 3};
        PrivateGenerationContext context = privateGeneration(evidence);
        NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, context,
            List.of("turn"), SessionMode.SINGLE_TURN, new ModelProfile("model", Optional.empty(), Map.of()),
            List.of());
        FakeAdapter adapter = new FakeAdapter(new NestedRunCompletion.Succeeded(
            successfulResult(context.selectedProject())));
        NestedRunCompletion completed = new NestedAgent(adapter, ignored -> evidence).start(request, RUN_ID).
            completion().toCompletableFuture().join();
        requireThat(completed instanceof NestedRunCompletion.Failed, "rejectedSelectedRoot").isTrue();
        requireThat(((NestedRunCompletion.Failed) completed).failure().kind(), "failureKind").
            isEqualTo(NestedRunFailureKind.RECEIPT_FAILED);
    }

    /**
     * A corrupt generation request cannot authorize a native start.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     */
    @Test
    public void rejectsCorruptGenerationAuthority() throws NoSuchAlgorithmException
    {
        PrivateGenerationContext context = privateGeneration(new byte[]{1, 2, 3});
        NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, context,
            List.of("turn"), SessionMode.SINGLE_TURN, new ModelProfile("model", Optional.empty(), Map.of()),
            List.of());
        FakeAdapter adapter = new FakeAdapter(null);
        try
        {
            new NestedAgent(adapter, ignored -> new byte[]{4, 5, 6}).start(request, RUN_ID);
            throw new AssertionError("corrupt generation authority was accepted");
        }
        catch (NestedRunException exception)
        {
            requireThat(exception.failure().kind(), "failureKind").isEqualTo(NestedRunFailureKind.START_FAILED);
        }
        requireThat(adapter.starts, "starts").isEqualTo(0);
    }

    /**
     * A matching private worktree result can settle after its authority is rehashed.
     *
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     * @throws NestedRunException if native start fails
     */
    @Test
    public void acceptsGenerationResultAtPrivateRoot() throws NoSuchAlgorithmException, NestedRunException
    {
        byte[] evidence = {1, 2, 3};
        PrivateGenerationContext context = privateGeneration(evidence);
        NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, context,
            List.of("turn"), SessionMode.SINGLE_TURN, new ModelProfile("model", Optional.empty(), Map.of()),
            List.of());
        NestedAgent agent = new NestedAgent(new FakeAdapter(new NestedRunCompletion.Succeeded(
            successfulResult(context.privateProject()))), ignored -> evidence);
        NestedRunCompletion completed = agent.start(request, RUN_ID).completion().toCompletableFuture().join();
        requireThat(completed instanceof NestedRunCompletion.Succeeded, "acceptedPrivateRoot").isTrue();
    }

    /**
     * Verifies a caller cannot complete the public stage before the native adapter settles.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void callerCannotForgeSettlement() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        NestedRunCompletion fake = new NestedRunCompletion.Failed(new NestedRunFailure(
            NestedRunFailureKind.INTERRUPTED, "call", Optional.of(RUN_ID), "caller supplied",
            List.of(), Optional.empty(), Optional.empty()));
        NestedRunCompletion actual = new NestedRunCompletion.Failed(new NestedRunFailure(
            NestedRunFailureKind.TRANSPORT_FAILED, "call", Optional.of(RUN_ID), "adapter supplied",
            List.of(), Optional.empty(), Optional.empty()));
        try (ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            CompletionStage<NestedRunCompletion> stage = call.completion();
            stage.toCompletableFuture().complete(fake);
            adapter.call.completion.complete(actual);
            NestedRunFailure observed = ((NestedRunCompletion.Failed) stage.toCompletableFuture().join()).failure();
            requireThat(observed.kind(), "kind").isEqualTo(NestedRunFailureKind.TRANSPORT_FAILED);
        }
    }

    /**
     * Verifies an exceptional adapter stage becomes a typed failure rather than an exceptional public stage.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void mapsExceptionalAdapterCompletion() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        try (ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            adapter.call.completion.completeExceptionally(new IOException("transport lost"));
            NestedRunCompletion settled = call.completion().toCompletableFuture().join();
            NestedRunFailure failure = ((NestedRunCompletion.Failed) settled).failure();
            requireThat(failure.kind(), "kind").isEqualTo(NestedRunFailureKind.TRANSPORT_FAILED);
            requireThat(failure.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
        }
    }

    /**
     * Verifies native cancellation settles the caller-facing stage with a typed failure.
     *
     * @throws NestedRunException if native start or cleanup fails
     */
    @Test
    public void mapsCancelledAdapterCompletion() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        try (ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            adapter.call.completion.cancel(true);
            NestedRunCompletion settled = call.completion().toCompletableFuture().join();
            NestedRunFailure failure = ((NestedRunCompletion.Failed) settled).failure();
            requireThat(failure.kind(), "kind").isEqualTo(NestedRunFailureKind.TRANSPORT_FAILED);
            requireThat(failure.retrySafety(), "retrySafety").isEqualTo(RetrySafety.NOT_PROVEN_OUTCOME_FREE);
        }
    }

    /**
     * Verifies a later adapter run-ID read failure cannot leave the public completion unsettled.
     *
     * @throws NestedRunException if native start or cleanup fails
     */
    @Test
    public void settlesAfterRunIdReadFailure() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        adapter.call.failLaterRunIdRead = true;
        try (ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            adapter.call.completion.completeExceptionally(new IOException("transport lost"));
            requireThat(call.completion().toCompletableFuture().isDone(), "settled").isTrue();
            NestedRunCompletion settled = call.completion().toCompletableFuture().join();
            NestedRunFailure failure = ((NestedRunCompletion.Failed) settled).failure();
            requireThat(failure.kind(), "kind").isEqualTo(NestedRunFailureKind.TRANSPORT_FAILED);
            requireThat(failure.runId(), "runId").isEqualTo(Optional.of(RUN_ID));
        }
    }

    /**
     * Verifies interruption remains a request and a valid result may settle afterward.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void retainsResultAfterInterruptRequest() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        adapter.call.onInterrupt = new NestedRunCompletion.Succeeded(
            successfulResult(Path.of("copy").toAbsolutePath().normalize()));
        try (ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            call.interrupt();
            NestedRunCompletion completed = call.completion().toCompletableFuture().join();
            requireThat(completed instanceof NestedRunCompletion.Succeeded, "succeeded").isTrue();
        }
        requireThat(adapter.call.interrupts, "interrupts").isEqualTo(1);
    }

    /**
     * Verifies a settled call never sends a late backend interruption.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void settledCallSkipsInterrupt() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(new NestedRunCompletion.Succeeded(
            successfulResult(Path.of("copy").toAbsolutePath().normalize())));
        try (ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            call.interrupt();
            requireThat(call.completion().toCompletableFuture().join() instanceof NestedRunCompletion.Succeeded,
                "succeeded").isTrue();
        }
        requireThat(adapter.call.interrupts, "interrupts").isEqualTo(0);
    }

    /**
     * Verifies concurrent interruption and close send one backend interrupt and retain a late settlement.
     *
     * @throws NestedRunException if native start or cleanup fails
     * @throws InterruptedException if the test thread is interrupted
     * @throws ExecutionException if a concurrent operation fails
     */
    @Test
    public void concurrentCloseRetainsLateResult()
        throws NestedRunException, InterruptedException, ExecutionException
    {
        FakeAdapter adapter = new FakeAdapter(null);
        NestedRunCompletion actual = new NestedRunCompletion.Failed(new NestedRunFailure(
            NestedRunFailureKind.TRANSPORT_FAILED, "call", Optional.of(RUN_ID), "late transport result",
            List.of(), Optional.empty(), Optional.empty()));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            Future<?> interrupt = executor.submit(() ->
            {
                start.await();
                call.interrupt();
                return null;
            });
            Future<?> close = executor.submit(() ->
            {
                start.await();
                closeCall(call);
                return null;
            });
            start.countDown();
            interrupt.get();
            close.get();
            adapter.call.completion.complete(actual);
            NestedRunFailure observed = ((NestedRunCompletion.Failed)
                call.completion().toCompletableFuture().join()).failure();
            requireThat(observed.kind(), "kind").isEqualTo(NestedRunFailureKind.TRANSPORT_FAILED);
        }
        requireThat(adapter.call.interrupts, "interrupts").isEqualTo(1);
        requireThat(adapter.call.closes, "closes").isEqualTo(1);
    }

    /**
     * Verifies cleanup failure is separately reported without replacing a settled success.
     *
     * @throws NestedRunException if native start fails
     */
    @Test
    public void cleanupFailureRetainsSuccess() throws NestedRunException
    {
        FakeAdapter adapter = new FakeAdapter(new NestedRunCompletion.Succeeded(
            successfulResult(Path.of("copy").toAbsolutePath().normalize())));
        adapter.call.closeFailure = true;
        CompletionStage<NestedRunCompletion> observed = null;
        boolean reportedCleanup = false;
        try (ActiveCall call = new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID))
        {
            observed = call.completion();
        }
        catch (NestedRunException exception)
        {
            reportedCleanup = true;
            requireThat(exception.failure().kind(), "cleanupKind").isEqualTo(NestedRunFailureKind.CLEANUP_FAILED);
            requireThat(observed.toCompletableFuture().join() instanceof NestedRunCompletion.Succeeded,
                "settledSuccess").isTrue();
        }
        requireThat(reportedCleanup, "reportedCleanup").isTrue();
    }

    /**
     * Verifies a malformed started call is closed before start reports failure.
     */
    @Test
    public void closesMalformedStartedCall()
    {
        FakeAdapter adapter = new FakeAdapter(null);
        adapter.call.badRunId = true;
        try
        {
            new NestedAgent(adapter, ignored -> new byte[0]).start(request(), RUN_ID);
            throw new AssertionError("malformed started call was accepted");
        }
        catch (NestedRunException exception)
        {
            requireThat(exception.failure().kind(), "kind").isEqualTo(NestedRunFailureKind.START_FAILED);
        }
        requireThat(adapter.call.closes, "closes").isEqualTo(1);
    }

    /**
     * Creates one complete native result in the supplied execution project.
     *
     * @param executionProject the runner-owned execution project
     * @return the native result
     */
    private static NestedRunResult successfulResult(Path executionProject)
    {
        EvidenceRef nativeRef = new EvidenceRef("events", "a".repeat(64));
        NativeEventEvidence event = new NativeEventEvidence("conversation", 1, nativeRef);
        RuntimePromptSource source = new RuntimePromptSource("conversation", 1, "prompt", Path.of("prompt.md"),
            PromptSourceOrigin.TARGET, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            nativeRef);
        List<ObservationCoverage> coverage = List.of(
            new ObservationCoverage(ObservationDomain.PROMPT_SOURCE, ObservationCoverageStatus.COMPLETE,
                Optional.empty(), List.of(nativeRef)),
            new ObservationCoverage(ObservationDomain.DIRECT_TOOL, ObservationCoverageStatus.COMPLETE,
                Optional.empty(), List.of()),
            new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE, ObservationCoverageStatus.COMPLETE,
                Optional.empty(), List.of()));
        return new NestedRunResult("harness", RUN_ID, executionProject,
            List.of(new TurnResult(1, "conversation", "response", event)), List.of(event),
            new RunnerIdentity("harness", "version", "b".repeat(64), Optional.of("provider"), Map.of(), List.of()),
            new ModelProfile("model", Optional.empty(), Map.of()), List.of(source), List.of(), List.of(), coverage);
    }

    /**
     * Closes one caller-owned native handle from a concurrent test operation.
     *
     * @param call the native handle
     * @throws NestedRunException if native cleanup fails
     */
    private static void closeCall(ActiveCall call) throws NestedRunException
    {
        call.close();
    }

    /**
     * Creates an isolated project request.
     *
     * @return the request
     */
    private static NestedRunRequest request()
    {
        ProjectExecutionSnapshot snapshot = new ProjectExecutionSnapshot(
            Path.of("project").toAbsolutePath().normalize(), new EvidenceRef("snapshots", "a".repeat(64)));
        ProjectRunContext context = new ProjectRunContext(snapshot, ProjectWorkspaceMode.ISOLATED_COPY,
            Optional.empty(), Optional.empty());
        return new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, context, List.of("turn"),
            SessionMode.SINGLE_TURN, new ModelProfile("model", Optional.empty(), Map.of()), List.of());
    }

    /**
     * Creates private authoring authority backed by one test-owned immutable value.
     *
     * @param bytes retained evidence returned to the native boundary
     * @return private generation context
     * @throws NoSuchAlgorithmException if the runtime lacks SHA-256
     */
    private static PrivateGenerationContext privateGeneration(byte[] bytes) throws NoSuchAlgorithmException
    {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new PrivateGenerationContext(Path.of("selected").toAbsolutePath().normalize(),
            Path.of("private").toAbsolutePath().normalize(),
            new EvidenceRef("candidate-generation-requests", hash),
            new EvidenceRef("generation-attempt-readiness", hash),
            new EvidenceRef("confirmed-editable-roots", hash));
    }

    private static final class FakeAdapter implements Adapter
    {
        private final FakeCall call;
        private int starts;
        private String requestedRunId;
        private AdapterCallReconciliation reconciled;
        private boolean reconcileFailure;

        /**
         * Creates a fake adapter settlement.
         *
         * @param completion the optional test settlement
         */
        private FakeAdapter(NestedRunCompletion completion)
        {
            call = new FakeCall(completion);
        }

        @Override
        public AdapterCall start(NestedRunRequest request, String runId)
        {
            starts = starts + 1;
            requestedRunId = runId;
            return call;
        }

        @Override
        public AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
        {
            if (reconcileFailure)
                throw new IllegalStateException("native reconciliation failed");
            if (reconciled != null)
                return reconciled;
            return new AdapterCallReconciliation.Active(call);
        }
    }

    private static final class FakeCall implements AdapterCall
    {
        private final CompletableFuture<NestedRunCompletion> completion = new CompletableFuture<>();
        private int interrupts;
        private int closes;
        private boolean interruptFailure;
        private boolean closeFailure;
        private boolean badRunId;
        private boolean failLaterRunIdRead;
        private int runIdReads;
        private NestedRunCompletion onInterrupt;

        /**
         * Creates a fake native call.
         *
         * @param result the optional initial settlement
         */
        private FakeCall(NestedRunCompletion result)
        {
            if (result != null)
                completion.complete(result);
        }

        @Override
        public String runId()
        {
            runIdReads = runIdReads + 1;
            if (failLaterRunIdRead && runIdReads > 1)
                throw new IllegalStateException("run ID no longer available");
            if (badRunId)
                return "";
            return RUN_ID;
        }

        @Override
        public CompletionStage<NestedRunCompletion> completion()
        {
            return completion;
        }

        @Override
        public void interrupt()
        {
            interrupts = interrupts + 1;
            if (onInterrupt != null)
                completion.complete(onInterrupt);
            if (interruptFailure)
                throw new IllegalStateException("interrupt failed");
        }

        @Override
        public void close() throws IOException
        {
            closes = closes + 1;
            if (closeFailure)
                throw new IOException("cleanup failed");
        }
    }
}
