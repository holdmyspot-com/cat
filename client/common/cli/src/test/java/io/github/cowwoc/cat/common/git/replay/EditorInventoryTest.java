package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.commit;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationManifest;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayReviewFixtures.manifestJson;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayCandidateUniverseFixtures.recordCandidateUniverseEvidence;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayAttestations.recordIndependentReviewAttestation;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRootCandidateFixture.record;

import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests replay command retirement, inventory, and topic-planning behavior.
 */
public final class EditorInventoryTest
{
	/**
	 * Creates the test fixture.
	 */
	public EditorInventoryTest()
	{
	}

	/**
	 * Verifies a default replay workspace remains beneath the repository's CAT work root.
	 *
	 * @throws IOException if the test workspace cannot be created
	 */
	@Test
	public void defaultsTemporaryRootToRepositoryCatWork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			Files.createDirectory(repository.resolve(".cat"));

			requireThat(GitTopicReplayEditor.temporaryRoot(repository), "temporaryRoot").isEqualTo(
				repository.resolve(".cat/work/temp"));
		}
	}

	/**
	 * Verifies that the retired interactive-rebase planning command cannot start the current replay workflow.
	 *
	 * @throws IOException if the command unexpectedly reaches filesystem work
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Usage: GitTopicReplayEditor.*")
	public void rejectsRetiredReviewSourceCommand() throws IOException
	{
		GitTopicReplayEditor.main(new String[] {"review-source", "manifest.json", "source", "fold", "output",
			"evidence"});
	}

	/**
	 * Verifies a silent external command reports periodic progress while it runs.
	 *
	 * @throws IOException if the command cannot run
	 */
	@Test
	public void reportsHeartbeatForSilentCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workingDirectory = cleanup.add(Files.createTempDirectory("cat-command-runner-"));
			AtomicInteger heartbeatCount = new AtomicInteger();

			new CommandRunner(workingDirectory).run(List.of("/bin/sleep", "0.2"),
				workingDirectory.resolve("command.log"), Duration.ofMillis(1), heartbeatCount::incrementAndGet);

			requireThat(heartbeatCount.get(), "heartbeatCount").isGreaterThan(0);
		}
	}

	/**
	 * Verifies that replay planning cannot record an incomplete one-source review outside the atomic review file.
	 *
	 * @throws IOException if the command unexpectedly reaches filesystem work
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Usage: GitTopicReplayEditor.*")
	public void rejectsRetiredSingleSourceReviewCommand() throws IOException
	{
		GitTopicReplayEditor.main(new String[] {"record-source-review", "manifest.json", "source", "whole",
			"counterfactual", "evidence"});
	}

	/**
	 * Verifies that retired cross-invocation review import cannot start the replay workflow.
	 *
	 * @throws IOException if the command unexpectedly reaches filesystem work
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Usage: GitTopicReplayEditor.*")
	public void rejectsReviewEvidenceImport() throws IOException
	{
		GitTopicReplayEditor.main(new String[] {"import-review-evidence", "previous.json", "replacement.json"});
	}

	/**
	 * Verifies predecessor comparison excludes a path first added by the source being reviewed.
	 *
	 * @throws IOException if the manifest or command output cannot be created
	 */
	@Test
	public void excludesAddedPathsFromOrigins() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			Path manifest = invocationManifest(repository);
			Files.writeString(manifest, manifestJson());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			new GitTopicReplayEditor(repository).writeOriginCandidates(manifest, output);

			try (ToolScope scope = new ToolScope())
			{
				JsonNode candidates = scope.json().readTree(output.toString(StandardCharsets.UTF_8));

				requireThat(candidates.size(), "candidateCount").isEqualTo(1);
				requireThat(candidates.get(0).path("path").stringValue(), "candidatePath").isEqualTo("feature.txt");
			}
		}
	}

	/**
	 * Verifies the immutable exposure inventory combines equivalent Codex and Claude agent declarations by their shared
	 * selectable name.
	 *
	 * @throws IOException if the fixture repository or inventory cannot be created
	 */
	@Test
	public void inventoriesCustomAgentEntryPoint() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository,
				"client/codex/plugin/src/main/resources/agents/codex/architecture-high.toml",
				"name = \"cat-architecture-high\"\n", "Add Codex agent");
			String tip = commit(git, repository,
				"client/claude/plugin/src/main/resources/agents/claude/architecture-high.md",
				"---\nname: architecture-high\n---\n", "Add Claude agent");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, tip, manifest);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeExposureInventory(manifest, output);

			try (ToolScope scope = new ToolScope())
			{
				JsonNode inventory = scope.json().readTree(output.toString(StandardCharsets.UTF_8));
				requireThat(inventory.size(), "entryPointCount").isEqualTo(1);
				requireThat(inventory.get(0).path("id").stringValue(), "entryPointId").isEqualTo(
					"agent:architecture-high");
				requireThat(inventory.get(0).path("paths").size(), "entryPointPathCount").isEqualTo(2);
			}
			recordCandidateUniverseEvidence(manifest);
			Path outputs = manifest.getParent().resolve("topic-outputs.json");
			Files.writeString(outputs, "[{\"id\":\"agent\",\"subject\":\"feature: add architecture agent\"}]\n");
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject an unmapped agent entry point");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("map every exposed entry point");
			}
		}
	}

	/**
	 * Verifies the immutable exposure inventory includes supported skills and installer flows, while normalizing a
	 * cross-harness skill into one selectable entry point.
	 *
	 * @throws IOException if the fixture repository or inventory cannot be created
	 */
	@Test
	public void inventoriesDeclaredProductEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "client/codex/skills/src/main/resources/skills/help/SKILL.md", "help\n",
				"Add Codex help skill");
			commit(git, repository, "client/claude/skills/src/main/resources/skills/help/SKILL.md", "help\n",
				"Add Claude help skill");
			String tip = commit(git, repository, "client/lifecycle/src/main/resources/install/install-codex.sh",
				"#!/bin/sh\n", "Add Codex installer");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, tip, manifest);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeExposureInventory(manifest, output);

			try (ToolScope scope = new ToolScope())
			{
				JsonNode inventory = scope.json().readTree(output.toString(StandardCharsets.UTF_8));
				requireThat(inventory.size(), "entryPointCount").isEqualTo(2);
				requireThat(inventory.get(0).path("id").stringValue(), "firstEntryPointId").isEqualTo(
					"installer:install-codex");
				requireThat(inventory.get(1).path("id").stringValue(), "secondEntryPointId").isEqualTo("skill:help");
				requireThat(inventory.get(1).path("paths").size(), "skillPathCount").isEqualTo(2);
			}
		}
	}

	/**
	 * Verifies that family metadata cannot combine a command with an installer action.
	 *
	 * @throws IOException if the fixture repository or review evidence cannot be created
	 */
	@Test
	public void rejectsMixedCommandAndInstallerFamily() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository,
				"client/common/cli/src/main/java/io/github/cowwoc/cat/common/git/replay/GitTopicReplayEditor.java",
				"class GitTopicReplayEditor {}\n", "Add topic replay command");
			String tip = commit(git, repository,
				"client/lifecycle/src/main/resources/install/install-codex.sh", "#!/bin/sh\n", "Add Codex installer");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, tip, manifest);
			recordCandidateUniverseEvidence(manifest);
			for (String prefix : List.of(".", ".independent-"))
			{
				Path universe = manifest.resolveSibling(manifest.getFileName() + prefix + "candidate-universe.json");
				String candidate = "planner-0";
				if (prefix.equals(".independent-"))
					candidate = "independent-0";
				Files.writeString(universe, Files.readString(universe).
					replaceFirst("\\\"exposedEntryPoints\\\":\\[\\]",
						"\\\"exposedEntryPoints\\\":[\\\"command:git-topic-replay\\\",\\\"installer:install-codex\\\"]").
					replace("\"entryPointFamilies\":[]", "\"entryPointFamilies\":[{\"candidateId\":\"" + candidate +
						"\",\"members\":[\"command:git-topic-replay\",\"installer:install-codex\"],\"selectionSurface\":" +
						"\"fixture release surface\",\"sharedPurpose\":\"fixture release\",\"acceptanceCheck\":" +
						"\"fixture release acceptance\"}]"));
			}
			recordIndependentReviewAttestation(manifest);
			Path outputs = manifest.getParent().resolve("topic-outputs.json");
			Files.writeString(outputs, "[{\"id\":\"agents\",\"subject\":\"feature: add custom agents\"}]\n");
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject a family that combines a command and installer");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("one selectable behavior family");
			}
		}
	}

	/**
	 * Verifies a non-empty root cannot retain planning, helper, and build artifacts under generic bootstrap evidence.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsGenericMultiArtifactRootCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			Files.createDirectories(repository.resolve(".cat/issues"));
			Files.createDirectories(repository.resolve("client/common/cli/src/main/java/example"));
			Files.createDirectories(repository.resolve("client/common/skills/src/main/resources/skills"));
			Files.writeString(repository.resolve("client/pom.xml"), "<project/>\n");
			Files.writeString(repository.resolve("client/common/cli/src/main/java/example/Scope.java"), "class Scope {}\n");
			Files.writeString(repository.resolve("client/common/skills/src/main/resources/skills/shared-first-use-route.md"),
				"Shared body.\n");
			Files.writeString(repository.resolve(".cat/issues/plan.md"), "Plan.\n");
			git.run("add", ".");
			git.run("commit", "--quiet", "--message", "initial composite");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare("--root", "HEAD", manifest);
			record(manifest);
			Path outputs = manifest.getParent().resolve("topic-outputs.json");
			Files.writeString(outputs, "[{\"id\":\"baseline\",\"subject\":\"config: build baseline\"}]\n");

			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected generic root bootstrap evidence to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("generic bootstrap labels");
			}
		}
	}
}
