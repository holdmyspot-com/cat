package io.github.cowwoc.cat.codex.runner;

import io.github.cowwoc.cat.common.optimizer.runner.AdapterCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NativeHarness;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunSource;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.PrivateGenerationContext;
import io.github.cowwoc.cat.common.optimizer.runner.RetainedBridgeAdapter;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CallPreStartRecord;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Starts Codex sessions and decodes their native results through the shared campaign bridge lifecycle. */
public final class CodexOptimizerAdapter extends RetainedBridgeAdapter
{
	private final CampaignStore store;
	private final HarnessRunManager manager;
	private final String executable;
	private final Map<String, String> environment;

	/**
	 * Binds the installed Codex runner to one pinned campaign.
	 *
	 * @param store the campaign evidence repository
	 * @param binding the pinned campaign identity
	 * @param manager the owned Codex run manager
	 * @param artifact the selected CAT runner artifact
	 * @param transcriptRoot the directory for create-once bridge transcripts
	 * @param tempRoot the owned temporary root for sterile evaluator inputs
	 * @param executable the Codex executable
	 * @param environment the host environment captured at the process boundary
	 */
	public CodexOptimizerAdapter(CampaignStore store, CampaignBinding binding, HarnessRunManager manager,
		Path artifact, Path transcriptRoot, Path tempRoot, String executable,
		Map<String, String> environment)
	{
		super(NativeHarness.CODEX, store, binding, manager, artifact, transcriptRoot, tempRoot);
		this.store = store;
		this.manager = manager;
		if (executable == null || executable.isBlank())
			throw new IllegalArgumentException("executable must not be blank");
		this.executable = executable;
		this.environment = Map.copyOf(environment);
	}

	@Override
	protected void validateHarnessRequest(NestedRunRequest request) throws IOException
	{
		requireSupportedToolsAndSettings(request);
	}

	/**
	 * Starts Codex with the prepared source and exact generation authority when applicable.
	 *
	 * @param request the validated native call
	 * @param verifiedArtifact selected packaged runner
	 * @param runId exact preallocated native run identity
	 * @param source input retained by the shared startup scope
	 * @return live session after synchronous copying or exact private generation startup
	 * @throws IOException if native setup fails
	 */
	@Override
	protected HarnessRunSession startPreparedNativeSession(NestedRunRequest request, Path verifiedArtifact,
		String runId, NativeRunSource source) throws IOException
	{
		PrivateGenerationContext generation = null;
		if (request.context() instanceof PrivateGenerationContext privateGeneration)
			generation = privateGeneration;
		return new CodexRunner().start(verifiedArtifact, source.project(), manager, executable,
			environment, request.modelProfile().modelId(), request.modelProfile().reasoningProfile().orElse(""),
			source.requiresCatInstallation(), null, List.of(), runId, generation);
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
	AdapterCallReconciliation reconcileRetained(NestedRunRequest request, Optional<String> knownRunId,
		CallPreStartRecord preStart) throws IOException
	{
		return reconcileVerified(request, knownRunId, preStart);
	}

	@Override
	protected NestedRunCompletion readCompleted(NestedRunRequest request, String runId, Path transcript,
		String runtimeManifestSha256) throws IOException
	{
		return RunResultReader.readCompleted(request, manager, runId, transcript, store, runtimeManifestSha256);
	}
}
