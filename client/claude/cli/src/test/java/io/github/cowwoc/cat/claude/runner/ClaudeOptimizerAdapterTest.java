package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectExecutionSnapshot;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Checks Claude capability and indexed authorization boundaries without launching a native process. */
public final class ClaudeOptimizerAdapterTest
{
	/** Creates a test instance. */
	public ClaudeOptimizerAdapterTest()
	{
	}

	/**
	 * Requested reasoning settings cannot be accepted when native evidence does not expose them.
	 *
	 * @throws IOException if the fixture cannot be created or the supported model is rejected
	 */
	@Test
	public void rejectsUnobservableReasoningSettings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-adapter-settings-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			ClaudeOptimizerAdapter adapter = new ClaudeOptimizerAdapter(store,
				new CampaignBinding("campaign", root.resolve("campaign")), manager,
				root.resolve("artifact"), root.resolve("transcripts"), root, "claude", Map.of());
			adapter.validateHarnessRequest(request(root, Optional.empty()));
			IOException failure = Assert.expectThrows(IOException.class, () ->
				adapter.validateHarnessRequest(request(root, Optional.of("high"))));
			Assert.assertTrue(failure.getMessage().contains("reasoning"));
			Assert.assertTrue(Files.notExists(root.resolve("runs")));
			Assert.assertTrue(Files.notExists(root.resolve("transcripts")));
		}
	}

	/**
	 * Missing indexed pre-start authority cannot create a native run or bridge transcript.
	 *
	 * @throws IOException if the test-owned campaign cannot be initialized
	 */
	@Test
	public void refusesDispatchWithoutPreStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-adapter-authority-"));
			CampaignStore store = CampaignStore.initialize(root.resolve("campaign"), "campaign",
				new ModelProfile("alias", Optional.empty(), Map.of()));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			ClaudeOptimizerAdapter adapter = new ClaudeOptimizerAdapter(store, store.binding(), manager,
				root.resolve("artifact"), root.resolve("transcripts"), root, "claude", Map.of());
			IOException failure = Assert.expectThrows(IOException.class, () ->
				adapter.start(request(root, Optional.empty()), "test-1"));
			Assert.assertTrue(failure.getMessage().contains("slot"));
			Assert.assertTrue(manager.observe("claude", "test-1").isEmpty());
			Assert.assertTrue(Files.notExists(root.resolve("transcripts")));
		}
	}

	/**
	 * Supplies only the context and model settings needed by the boundary under test.
	 *
	 * @param root fixture directory
	 * @param reasoning requested reasoning setting
	 * @return one isolated native call
	 */
	private static NestedRunRequest request(Path root, Optional<String> reasoning)
	{
		return new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT,
			new ProjectRunContext(new ProjectExecutionSnapshot(root.resolve("project"),
				new EvidenceRef("snapshot-manifests", "a".repeat(64))), ProjectWorkspaceMode.ISOLATED_COPY,
				Optional.empty(), Optional.empty()), List.of("hello"), SessionMode.SINGLE_TURN,
			new ModelProfile("alias", reasoning, Map.of()), List.of());
	}
}
