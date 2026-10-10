package io.github.cowwoc.cat.common.git.replay.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.git.replay.test.ReplayFixtures.addedPath;
import static io.github.cowwoc.cat.common.git.replay.test.TopicReplayRepository.invocationManifest;
import static io.github.cowwoc.cat.common.git.replay.test.TopicReplayReviewFixtures.manifestJson;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.replay.CandidateUniverse;
import io.github.cowwoc.cat.common.git.replay.CandidateUniverseEntry;
import io.github.cowwoc.cat.common.git.replay.CandidateUniverseRequirements;
import io.github.cowwoc.cat.common.git.replay.CandidateUniverseUnit;
import io.github.cowwoc.cat.common.git.replay.CommandRunner;
import io.github.cowwoc.cat.common.git.replay.EntryPointFamily;
import io.github.cowwoc.cat.common.git.replay.EntryPoints;
import io.github.cowwoc.cat.common.git.replay.ExposedEntryPoint;
import io.github.cowwoc.cat.common.git.replay.GitTopicReplayEditor;
import io.github.cowwoc.cat.common.git.replay.History;
import io.github.cowwoc.cat.common.git.replay.ReplayJson;
import io.github.cowwoc.cat.common.git.replay.ReviewEvidence;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "inventory-"));
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
	 * Verifies a silent external command reports periodic progress while it runs. The command prints nothing and
	 * keeps running until the first heartbeat creates a marker file in its working directory, so it exits
	 * successfully only if a heartbeat arrived while it was still running. A bounded filesystem notification wait
	 * makes the command fail if no heartbeat arrives.
	 *
	 * @throws IOException if the command cannot run
	 */
	@Test
	public void reportsHeartbeatForSilentCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workingDirectory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"cat-command-runner-"));
			Path marker = workingDirectory.resolve("heartbeat");
			AtomicInteger heartbeatCount = new AtomicInteger();
			Runnable heartbeat = () ->
			{
				if (heartbeatCount.incrementAndGet() != 1)
					return;
				try
				{
					Files.createFile(marker);
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			};

			int exitCode = new CommandRunner(workingDirectory).run(
				GateCommandFixture.command(GateCommandFixture.Mode.WAIT_HEARTBEAT),
				workingDirectory.resolve("command.log"), Duration.ofMillis(1), heartbeat);

			requireThat(exitCode, "exitCode").isEqualTo(0);
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
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "inventory-"));
			Path manifest = invocationManifest(repository);
			Files.writeString(manifest, manifestJson());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			new GitTopicReplayEditor(repository).writeOriginCandidates(manifest, output);

			JsonNode candidates = ReplayJson.newMapper().readTree(output.toString(StandardCharsets.UTF_8));

			requireThat(candidates.size(), "candidateCount").isEqualTo(1);
			requireThat(candidates.get(0).path("path").stringValue(), "candidatePath").isEqualTo("feature.txt");
		}
	}

	/**
	 * Verifies CAT combines equivalent Codex and Claude agent declarations by their shared selectable name.
	 *
	 * @throws IOException if valid immutable declarations are rejected
	 */
	@Test
	public void inventoriesCustomAgentEntryPoint() throws IOException
	{
		String codexPath = "client/codex/plugin/src/main/resources/agents/codex/architecture-high.toml";
		String claudePath = "client/claude/plugin/src/main/resources/agents/claude/architecture-high.md";
		List<History.InventoryEntry> captured = List.of(addedPath(codexPath, "codex"), addedPath(claudePath, "claude"));
		Map<String, String> manifests = Map.of("codex", "name = \"cat-architecture-high\"\n",
			"claude", "---\nname: architecture-high\n---\n");

		List<ExposedEntryPoint> inventory = EntryPoints.collect(captured, manifests::get);

		requireThat(inventory.size(), "entryPointCount").isEqualTo(1);
		requireThat(inventory.getFirst().id(), "entryPointId").isEqualTo("agent:architecture-high");
		requireThat(inventory.getFirst().paths(), "entryPointPaths").isEqualTo(List.of(claudePath, codexPath));
	}

	/**
	 * Verifies candidate validation rejects an agent entry point that no candidate owns.
	 *
	 * @throws IOException for the expected missing entry-point mapping
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*map every exposed entry point.*")
	public void rejectsUnmappedAgentEntryPoint() throws IOException
	{
		CandidateUniverseRequirements requirements = new CandidateUniverseRequirements(Set.of("agent"),
			List.of("source"), Set.of("agent:architecture-high"), false);
		CandidateUniverseEntry candidate = new CandidateUniverseEntry("agent", "Architecture review is selectable",
			"Agent declarations expose architecture review", "Architecture review", "Select architecture review",
			"Review is usable independently", List.of(new CandidateUniverseUnit("agent", "selectable agent")), List.of());
		new ReviewEvidence(ReplayJson.newMapper()).validateCandidateUniverse(requirements,
			new CandidateUniverse("reviewer", List.of(candidate), List.of()), Path.of("candidate-universe.json"));
	}

	/**
	 * Verifies candidate validation rejects a reviewer-named candidate identifier that is not snake_case.
	 *
	 * @throws IOException if the identifier is not snake_case
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		"Candidate-universe id \"agent-review\" must be snake_case.*such as \"agent_review\".*candidate-universe\\.json")
	public void rejectsNonSnakeCaseCandidateUniverseId() throws IOException
	{
		CandidateUniverseRequirements requirements = new CandidateUniverseRequirements(Set.of("agent"),
			List.of("source"), Set.of(), false);
		CandidateUniverseEntry candidate = new CandidateUniverseEntry("agent-review", "Architecture review is selectable",
			"Agent declarations expose architecture review", "Architecture review", "Select architecture review",
			"Review is usable independently", List.of(new CandidateUniverseUnit("agent", "selectable agent")), List.of());
		new ReviewEvidence(ReplayJson.newMapper()).validateCandidateUniverse(requirements,
			new CandidateUniverse("reviewer", List.of(candidate), List.of()), Path.of("candidate-universe.json"));
	}

	/**
	 * Verifies CAT normalizes cross-harness skills into one entry point and classifies supported installer flows.
	 *
	 * @throws IOException if captured product paths are rejected
	 */
	@Test
	public void inventoriesDeclaredProductEntries() throws IOException
	{
		String codexSkill = "client/codex/skills/src/main/resources/skills/help/SKILL.md";
		String claudeSkill = "client/claude/skills/src/main/resources/skills/help/SKILL.md";
		List<History.InventoryEntry> captured = List.of(addedPath(codexSkill, "codex"), addedPath(claudeSkill, "claude"),
			addedPath("client/lifecycle/src/main/resources/install/install-codex.sh", "installer"));
		Map<String, String> manifests = Map.of();

		List<ExposedEntryPoint> inventory = EntryPoints.collect(captured, manifests::get);

		requireThat(inventory.size(), "entryPointCount").isEqualTo(2);
		requireThat(inventory.get(0).id(), "firstEntryPointId").isEqualTo("installer:install-codex");
		requireThat(inventory.get(1).id(), "secondEntryPointId").isEqualTo("skill:help");
		requireThat(inventory.get(1).paths(), "skillPaths").isEqualTo(List.of(claudeSkill, codexSkill));
	}

	/**
	 * Verifies family metadata cannot combine a command with an installer action.
	 *
	 * @throws IOException for the expected incompatible selectable-family rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*one selectable behavior family.*")
	public void rejectsMixedCommandAndInstallerFamily() throws IOException
	{
		List<String> entries = List.of("command:git-topic-replay", "installer:install-codex");
		CandidateUniverseRequirements requirements = new CandidateUniverseRequirements(Set.of("command", "installer"),
			List.of("source"), Set.copyOf(entries), false);
		CandidateUniverseEntry candidate = new CandidateUniverseEntry("release", "Release surfaces are selectable",
			"Captured command and installer declarations", "Release surfaces", "Select complete release surface",
			"Release surface activation", List.of(new CandidateUniverseUnit("command", "command selection"),
				new CandidateUniverseUnit("installer", "installer selection")), entries);
		EntryPointFamily family = new EntryPointFamily("release", entries, "fixture release surface", "fixture release",
			"fixture release acceptance");
		new ReviewEvidence(ReplayJson.newMapper()).validateCandidateUniverse(requirements,
			new CandidateUniverse("reviewer", List.of(candidate), List.of(family)), Path.of("candidate-universe.json"));
	}

	/**
	 * Verifies generic bootstrap evidence cannot retain planning, helper, skill, and build artifacts in one root topic.
	 *
	 * @throws IOException for the expected inadequate root-evidence rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*generic bootstrap labels.*")
	public void rejectsGenericMultiArtifactRootCandidate() throws IOException
	{
		CandidateUniverseRequirements requirements = new CandidateUniverseRequirements(
			Set.of("planning", "helper", "skill", "build"), List.of("root"), Set.of(), true);
		CandidateUniverseEntry candidate = new CandidateUniverseEntry("bootstrap",
			"Clean Maven/Java build and foundational developer configuration", "Captured artifacts",
			"Owns the observable contract: bootstrap.", "fixture build",
			"Separate unless an exact prerequisite prevents independent activation.", List.of(
				new CandidateUniverseUnit("planning", "planning artifact"),
				new CandidateUniverseUnit("helper", "helper artifact"),
				new CandidateUniverseUnit("skill", "skill artifact"),
				new CandidateUniverseUnit("build", "build artifact")), List.of());
		new ReviewEvidence(ReplayJson.newMapper()).validateCandidateUniverse(requirements,
			new CandidateUniverse("reviewer", List.of(candidate), List.of()), Path.of("candidate-universe.json"));
	}
}
