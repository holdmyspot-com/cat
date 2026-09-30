package io.github.cowwoc.cat.codex.runner;

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
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Tests the Codex adapter's durable pre-dispatch boundary. */
public final class CodexOptimizerAdapterTest
{
	/** Creates the test fixture. */
	public CodexOptimizerAdapterTest()
	{
	}

	/**
	 * A missing indexed pre-start authority cannot create a native run or bridge transcript.
	 *
	 * @throws IOException if the test-owned campaign cannot be initialized
	 */
	@Test
	public void refusesDispatchWithoutPreStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-adapter-"));
			CampaignStore store = CampaignStore.initialize(root.resolve("campaign"), "campaign",
				new ModelProfile("gpt-6-sol", Optional.empty(), Map.of()));
			CampaignBinding binding = store.binding();
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			Path transcripts = root.resolve("transcripts");
			CodexOptimizerAdapter adapter = new CodexOptimizerAdapter(store, binding, manager,
				root.resolve("artifact"), transcripts, root, "codex", Map.of());
			NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT,
				new ProjectRunContext(new ProjectExecutionSnapshot(root.resolve("project"),
					new EvidenceRef("snapshots", "a".repeat(64))), ProjectWorkspaceMode.ISOLATED_COPY,
					Optional.empty(), Optional.empty()), List.of("hello"), SessionMode.SINGLE_TURN,
				new ModelProfile("gpt-6-sol", Optional.empty(), Map.of()), List.of());

			try
			{
				adapter.start(request, "test-1");
				throw new AssertionError("Codex must not start without retained call authority");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingAuthority").contains("slot");
			}
			requireThat(manager.observe("codex", "test-1"), "nativeRun").isEmpty();
			requireThat(Files.notExists(transcripts), "transcripts").isTrue();
		}
	}
}
