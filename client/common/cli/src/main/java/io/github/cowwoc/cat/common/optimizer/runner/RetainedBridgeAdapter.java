package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CallPreStartRecord;
import io.github.cowwoc.cat.common.optimizer.state.CallSlot;
import io.github.cowwoc.cat.common.runner.HarnessArtifact;
import io.github.cowwoc.cat.common.runner.HarnessBridgeCall;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunObservation;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Owns indexed authorization, retained bridge execution, and cold reconciliation for a native campaign call.
 */
public abstract class RetainedBridgeAdapter implements Adapter
{
	private final CampaignStore store;
	private final CampaignBinding binding;
	private final HarnessRunManager manager;
	private final Path artifact;
	private final Path transcriptRoot;
	private final Path tempRoot;
	private final NativeHarness harness;

	/**
	 * Binds a native bridge lifecycle to one pinned campaign.
	 *
	 * @param harness constrained native harness identity
	 * @param store immutable campaign repository
	 * @param binding pinned campaign identity
	 * @param manager owned-run repository
	 * @param artifact selected installed runner artifact
	 * @param transcriptRoot create-once transcript directory
	 * @param tempRoot owned temporary root for sterile evaluator inputs
	 */
	protected RetainedBridgeAdapter(NativeHarness harness, CampaignStore store, CampaignBinding binding,
		HarnessRunManager manager, Path artifact, Path transcriptRoot, Path tempRoot)
	{
		this.harness = Objects.requireNonNull(harness, "harness");
		this.store = Objects.requireNonNull(store, "store");
		this.binding = Objects.requireNonNull(binding, "binding");
		this.manager = Objects.requireNonNull(manager, "manager");
		this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
		this.transcriptRoot = Objects.requireNonNull(transcriptRoot, "transcriptRoot").toAbsolutePath().normalize();
		this.tempRoot = Objects.requireNonNull(tempRoot, "tempRoot").toAbsolutePath().normalize();
	}

	@Override
	public AdapterCall start(NestedRunRequest request, String runId) throws IOException
	{
		Objects.requireNonNull(request, "request");
		HarnessRun.requireValidId(runId);
		CallPreStartRecord preStart = store.readCallPreStartRecord(binding, request.logicalCallId()).
			orElseThrow(() -> new IOException(harness.displayName() + " call has no indexed pre-start authority"));
		if (!preStart.runId().equals(runId))
			throw new IOException(harness.displayName() + " call run ID differs from indexed pre-start authority");
		CallSlot slot = store.readCallSlotClaim(binding, request.logicalCallId()).slot();
		if (slot.state() != CallSlot.State.STARTING || slot.cancellationRequested())
			throw new IOException(harness.displayName() + " call has no current uncancelled start authorization");
		if (request.context() instanceof ProjectRunContext project &&
			project.mode() != ProjectWorkspaceMode.ISOLATED_COPY ||
			request.context() instanceof SterileWorkspaceContext sterile &&
			sterile.kind() != SterileWorkspaceKind.EVALUATOR)
			throw new IOException(harness.displayName() +
				" adapter requires an isolated trial, private generation, or sterile evaluator");
		validateHarnessRequest(request);
		Path verifiedArtifact = HarnessArtifact.validate(artifact, harness.pluginManifest());
		if (!RunnerRuntimeManifest.fingerprint(verifiedArtifact).equals(preStart.runtimeManifestSha256()))
			throw new IOException(harness.displayName() + " runner differs from the retained pre-start runtime identity");
		Files.createDirectories(transcriptRoot);
		if (!Files.isDirectory(transcriptRoot, LinkOption.NOFOLLOW_LINKS))
			throw new IOException(harness.displayName() + " transcript root is not an owned directory: " + transcriptRoot);
		Path transcript = transcript(runId);
		HarnessBridgeCall bridge = HarnessBridgeCall.start(() -> startNativeSession(request, verifiedArtifact,
			runId), manager, runId,
			transcript, request.turns(), ignored -> {});
		CompletionStage<NestedRunCompletion> settled = settleBridgeCompletion(bridge.completion(), request,
			runId, transcript, preStart.runtimeManifestSha256());
		return new LiveCall(runId, bridge, settled);
	}

	/**
	 * Produces one terminal completion, retaining reader evidence even when the native bridge failed.
	 *
	 * @param bridgeCompletion actual bridge terminal result
	 * @param request original logical call
	 * @param runId preallocated native identity
	 * @param transcript retained bridge transcript
	 * @param runtimeManifestSha256 prestart runtime identity
	 * @return settled or exceptionally completed stage; unchecked reader defects never leave it pending
	 */
	CompletionStage<NestedRunCompletion> settleBridgeCompletion(CompletionStage<HarnessRun> bridgeCompletion,
		NestedRunRequest request, String runId, Path transcript, String runtimeManifestSha256)
	{
		return bridgeCompletion.handle((completedRun, thrown) ->
		{
			try
			{
				return readCompleted(request, runId, transcript, runtimeManifestSha256);
			}
			catch (IOException exception)
			{
				if (thrown != null)
					return new NestedRunCompletion.Failed(failure(NestedRunFailureKind.TRANSPORT_FAILED,
						request, runId, thrown.toString()));
				return new NestedRunCompletion.Failed(failure(NestedRunFailureKind.RECEIPT_FAILED,
					request, runId, exception.toString()));
			}
		});
	}

	/**
	 * Validates additional harness-specific model capabilities before starting a bridge.
	 *
	 * @param request original logical call
	 * @throws IOException if the requested native settings cannot be enforced and observed
	 */
	protected abstract void validateHarnessRequest(NestedRunRequest request) throws IOException;

	/**
	 * Requires the tool and exposed-setting capabilities shared by the supported native adapters.
	 *
	 * @param request original logical call
	 * @throws IOException if unsupported host tools or exposed settings were requested
	 */
	protected final void requireSupportedToolsAndSettings(NestedRunRequest request) throws IOException
	{
		if (!request.requiredHostTools().isEmpty() || !request.modelProfile().exposedSettings().isEmpty())
			throw new IOException(harness.displayName() +
				" adapter cannot enforce the requested host tools or model settings");
	}

	/**
	 * Retains the prepared source until synchronous native copying finishes and closes a session if cleanup fails.
	 *
	 * @param request authorized native call
	 * @param verifiedArtifact validated installed artifact
	 * @param runId preallocated identity
	 * @return live native session
	 * @throws IOException if native setup fails
	 */
	protected final HarnessRunSession startNativeSession(NestedRunRequest request, Path verifiedArtifact,
		String runId) throws IOException
	{
		HarnessRunSession session = null;
		try (NativeRunSource source = NativeRunSource.prepare(request, store, tempRoot))
		{
			session = startPreparedNativeSession(request, verifiedArtifact, runId, source);
			return session;
		}
		catch (IOException | RuntimeException failure)
		{
			if (session != null)
			{
				try
				{
					session.close();
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

	/**
	 * Starts the native session using a source owned by the shared startup scope.
	 *
	 * @param request authorized native call
	 * @param verifiedArtifact validated installed artifact
	 * @param runId preallocated identity
	 * @param source prepared input available until this method returns
	 * @return live native session after synchronous source copying
	 * @throws IOException if native setup fails
	 */
	protected abstract HarnessRunSession startPreparedNativeSession(NestedRunRequest request, Path verifiedArtifact,
		String runId, NativeRunSource source) throws IOException;

	/**
	 * Reads typed settlement through the responsible harness's evidence boundary.
	 *
	 * @param request original logical call
	 * @param runId retained native identity
	 * @param transcript retained bridge transcript
	 * @param runtimeManifestSha256 prestart runtime identity
	 * @return proven terminal settlement
	 * @throws IOException if retained observations cannot establish settlement
	 */
	protected abstract NestedRunCompletion readCompleted(NestedRunRequest request, String runId,
		Path transcript, String runtimeManifestSha256) throws IOException;

	@Override
	public AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
		throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(knownRunId, "knownRunId");
		CallPreStartRecord preStart = store.readCallPreStartRecord(binding, request.logicalCallId()).
			orElseThrow(() -> new IOException(harness.displayName() + " call has no indexed pre-start authority"));
		return reconcileVerified(request, knownRunId, preStart);
	}

	/**
	 * Reconciles one already verified pre-start identity without creating a native session.
	 *
	 * @param request the original logical call
	 * @param knownRunId the native identity previously observed by the caller, if any
	 * @param preStart the verified indexed pre-start authority
	 * @return the retained native state
	 * @throws IOException if native observation cannot be read
	 */
	protected final AdapterCallReconciliation reconcileVerified(NestedRunRequest request, Optional<String> knownRunId,
		CallPreStartRecord preStart) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(knownRunId, "knownRunId");
		Objects.requireNonNull(preStart, "preStart");
		if (!preStart.logicalCallId().equals(request.logicalCallId()))
			return unknown(request, preStart.runId(), "Retained pre-start identifies another logical call");
		return observeRetained(request, knownRunId, preStart);
	}

	/**
	 * Observes native state after the pre-start record has been verified.
	 *
	 * @param request the original logical call
	 * @param knownRunId the caller's previously observed native identity
	 * @param preStart the verified indexed pre-start authority
	 * @return the retained native state
	 * @throws IOException if native observation cannot be read
	 */
	private AdapterCallReconciliation observeRetained(NestedRunRequest request, Optional<String> knownRunId,
		CallPreStartRecord preStart) throws IOException
	{
		String runId = preStart.runId();
		if (knownRunId.isPresent() && !knownRunId.orElseThrow().equals(runId))
			return unknown(request, runId, "Known run ID differs from indexed pre-start authority");
		Optional<HarnessRunObservation> observed = manager.observe(harness.id(), runId);
		if (observed.isEmpty())
			return unknown(request, runId, "No retained " + harness.displayName() +
				" run exists for indexed pre-start authority");
		if (observed.orElseThrow().state() == HarnessRunObservation.State.RUNNING)
			return unknown(request, runId, "Retained " + harness.displayName() + " run is not completed");
		try
		{
			NestedRunCompletion completion = readCompleted(request, runId, transcript(runId),
				preStart.runtimeManifestSha256());
			return new AdapterCallReconciliation.Settled(completion);
		}
		catch (IOException exception)
		{
			return unknown(request, runId, exception.toString());
		}
	}

	/**
	 * Returns the create-once bridge transcript path for one native run.
	 *
	 * @param runId the retained native run identity
	 * @return the named bridge transcript path
	 */
	private Path transcript(String runId)
	{
		return transcriptRoot.resolve(runId + ".jsonl");
	}

	/**
	 * Returns a failure that retains the exact logical and native call identities.
	 *
	 * @param kind the observed native failure kind
	 * @param request the original logical call
	 * @param runId the retained native run identity
	 * @param reason the observed failure condition
	 * @return the typed failure
	 */
	private static NestedRunFailure failure(NestedRunFailureKind kind, NestedRunRequest request, String runId,
		String reason)
	{
		return new NestedRunFailure(kind, request.logicalCallId(), Optional.of(runId), reason,
			List.of(), Optional.empty(), Optional.empty());
	}

	/**
	 * Returns a conservative native state when retained evidence cannot prove a settlement.
	 *
	 * @param request the original logical call
	 * @param runId the retained native run identity
	 * @param reason the missing or conflicting evidence
	 * @return an unknown native state
	 */
	private static AdapterCallReconciliation.Unknown unknown(NestedRunRequest request, String runId, String reason)
	{
		return new AdapterCallReconciliation.Unknown(failure(NestedRunFailureKind.NATIVE_STATE_UNKNOWN,
			request, runId, reason));
	}

	/**
	 * An exclusive live bridge handle with one stable typed settlement.
	 *
	 * @param runId the retained native run identity
	 * @param bridge the active native bridge conversation
	 * @param completion the typed terminal settlement
	 */
	private record LiveCall(String runId, HarnessBridgeCall bridge, CompletionStage<NestedRunCompletion> completion)
		implements AdapterCall
	{
		@Override
		public void interrupt()
		{
			bridge.interrupt();
		}

		@Override
		public void close()
		{
			bridge.close();
		}
	}
}
