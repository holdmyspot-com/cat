package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.optimizer.runner.NativeHarness;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunSource;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.PrivateGenerationContext;
import io.github.cowwoc.cat.common.optimizer.runner.RetainedBridgeAdapter;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Starts Claude sessions and decodes their native results through the shared campaign bridge lifecycle. */
public final class ClaudeOptimizerAdapter extends RetainedBridgeAdapter
{
	private final CampaignStore store;
	private final HarnessRunManager manager;
	private final String executable;
	private final Map<String, String> environment;

	/**
	 * Binds the installed Claude runner to one pinned campaign.
	 *
	 * @param store campaign evidence repository
	 * @param binding pinned campaign identity
	 * @param manager owned Claude run manager
	 * @param artifact selected CAT runner artifact
	 * @param transcriptRoot directory for create-once bridge transcripts
	 * @param tempRoot owned temporary root for sterile evaluator inputs
	 * @param executable Claude executable
	 * @param environment host environment captured at the process boundary
	 */
	public ClaudeOptimizerAdapter(CampaignStore store, CampaignBinding binding, HarnessRunManager manager,
		Path artifact, Path transcriptRoot, Path tempRoot, String executable, Map<String, String> environment)
	{
		super(NativeHarness.CLAUDE, store, binding, manager, artifact, transcriptRoot, tempRoot);
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
		if (request.modelProfile().reasoningProfile().isPresent())
			throw new IOException("Claude adapter cannot observe the requested reasoning setting in native evidence");
	}

	@Override
	protected HarnessRunSession startPreparedNativeSession(NestedRunRequest request, Path verifiedArtifact,
		String runId, NativeRunSource source) throws IOException
	{
		PrivateGenerationContext generation = null;
		if (request.context() instanceof PrivateGenerationContext privateGeneration)
			generation = privateGeneration;
		return new ClaudeRunner().start(verifiedArtifact, source.project(), manager, executable, environment,
			request.modelProfile().modelId(), "", source.requiresCatInstallation(), source.requiresCatInstallation(),
			null, List.of(), runId, generation);
	}

	@Override
	protected NestedRunCompletion readCompleted(NestedRunRequest request, String runId, Path transcript,
		String runtimeManifestSha256) throws IOException
	{
		return RunResultReader.readCompleted(request, manager, runId, transcript, store, runtimeManifestSha256);
	}
}
