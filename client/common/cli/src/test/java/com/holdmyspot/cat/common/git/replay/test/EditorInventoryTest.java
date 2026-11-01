package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.cat.common.git.replay.test.ReplayFixtures.addedPath;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayRepository.invocationManifest;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayReviewFixtures.manifestJson;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.replay.CandidateUniverse;
import com.holdmyspot.cat.common.git.replay.CandidateUniverseEntry;
import com.holdmyspot.cat.common.git.replay.CandidateUniverseRequirements;
import com.holdmyspot.cat.common.git.replay.CandidateUniverseUnit;
import com.holdmyspot.cat.common.git.replay.CommandRunner;
import com.holdmyspot.cat.common.git.replay.EntryPointFamily;
import com.holdmyspot.cat.common.git.replay.EntryPoints;
import com.holdmyspot.cat.common.git.replay.ExposedEntryPoint;
import com.holdmyspot.cat.common.git.replay.History;
import com.holdmyspot.cat.common.git.replay.ReplayJson;
import com.holdmyspot.cat.common.git.replay.ReviewEvidence;
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
	 * Runs a command that the editor no longer accepts.
	 *
	 * @param args the command and its arguments
	 * @throws IOException if the command unexpectedly reaches filesystem work
	 */
	private static void rejectCommand(String... args) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "retired-"));
			ReplayFixtures.editor(repository).execute(args);
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
		rejectCommand("review-source", "manifest.json", "source", "fold", "output", "evidence");
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workingDirectory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
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
		rejectCommand("record-source-review", "manifest.json", "source", "whole", "counterfactual", "evidence");
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
		rejectCommand("import-review-evidence", "previous.json", "replacement.json");
	}

	/**
	 * Verifies predecessor comparison excludes a path first added by the source being reviewed.
	 *
	 * @throws IOException if the manifest or command output cannot be created
	 */
	@Test
	public void excludesAddedPathsFromOrigins() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "inventory-"));
			Path manifest = invocationManifest(repository);
			Files.writeString(manifest, manifestJson());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ReplayFixtures.editor(repository).writeOriginCandidates(manifest, output);

			JsonNode candidates = ReplayJson.newMapper().readTree(output.toString(StandardCharsets.UTF_8));

			requireThat(candidates.size(), "candidateCount").isEqualTo(1);
			requireThat(candidates.get(0).path("path").stringValue(), "candidatePath").isEqualTo("feature.txt");
		}
	}

	/**
	 * Verifies equivalent host declarations share one logical agent and retain their custom-agent kind.
	 *
	 * @throws IOException if valid immutable declarations are rejected
	 */
	@Test
	public void inventoriesCustomAgentEntryPoint() throws IOException
	{
		String firstPath = "client/host-a/plugin/src/main/resources/agents/host-a/architecture-high.toml";
		String secondPath = "client/host-b/plugin/src/main/resources/agents/host-b/architecture-high.md";
		List<History.InventoryEntry> captured = List.of(addedPath(firstPath, "first"), addedPath(secondPath, "second"));
		Map<String, String> manifests = Map.of("first", "name = \"cat-architecture-high\"\n",
			"second", "---\nname: architecture-high\n---\n");

		List<ExposedEntryPoint> inventory = EntryPoints.collect(captured, manifests::get);

		requireThat(inventory.size(), "entryPointCount").isEqualTo(1);
		requireThat(inventory.getFirst().id(), "entryPointId").isEqualTo("agent:architecture-high");
		requireThat(inventory.getFirst().kind(), "entryPointKind").isEqualTo("custom_agent");
		requireThat(inventory.getFirst().paths(), "entryPointPaths").isEqualTo(List.of(firstPath, secondPath));
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
	 * Verifies inventory classifies commands, skills, and current lifecycle paths while excluding installer helpers.
	 *
	 * @throws IOException if captured product paths are rejected
	 */
	@Test
	public void inventoriesDeclaredProductEntries() throws IOException
	{
		String firstSkill = "client/host-a/skills/src/main/resources/skills/help/SKILL.md";
		String secondSkill = "client/host-b/skills/src/main/resources/skills/help/SKILL.md";
		String installer = "client/host-a/lifecycle/src/main/resources/install/install-host-a.sh";
		String powershellInstaller = "client/host-a/lifecycle/src/main/resources/install/install-host-a.ps1";
		List<History.InventoryEntry> captured = List.of(addedPath(firstSkill, "first"), addedPath(secondSkill, "second"),
			addedPath(installer, "installer"), addedPath(powershellInstaller, "powershell"),
			addedPath("client/common/lifecycle/src/main/resources/install/bootstrap.sh", "bootstrap"),
			addedPath("client/common/lifecycle/src/main/resources/install/common.sh", "helper"),
			addedPath("client/common/cli/src/main/java/com/holdmyspot/cat/common/git/replay/GitTopicReplayEditor.java",
				"command"));
		Map<String, String> manifests = Map.of();

		List<ExposedEntryPoint> inventory = EntryPoints.collect(captured, manifests::get);

		requireThat(inventory.stream().map(ExposedEntryPoint::id).toList(), "entryPointIds").isEqualTo(List.of(
			"command:git-topic-replay", "installer:bootstrap", "installer:install-host-a", "skill:help"));
		requireThat(inventory.stream().map(ExposedEntryPoint::kind).toList(), "entryPointKinds").isEqualTo(List.of(
			"command", "installer", "installer", "skill"));
		requireThat(inventory.get(2).paths(), "installerPaths").isEqualTo(List.of(powershellInstaller, installer));
		requireThat(inventory.get(3).paths(), "skillPaths").isEqualTo(List.of(firstSkill, secondSkill));
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
		List<String> entries = List.of("command:git-topic-replay", "installer:install-host-a");
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
