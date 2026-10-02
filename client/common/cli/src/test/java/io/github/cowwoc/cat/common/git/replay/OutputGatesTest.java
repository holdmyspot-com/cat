package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationDirectory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests retained-output Git topic-replay gates.
 */
public final class OutputGatesTest
{
	/**
	 * Creates the test fixture.
	 */
	public OutputGatesTest()
	{
	}

	/**
	 * Verifies an output gate rejects a no-op command before materializing a worktree.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsNoOpCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "cat-topic-replay-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			OutputGates gates = new OutputGates(new Git(directory),
				(ignored, prefix) -> directory.resolve(prefix));

			try
			{
				gates.run(manifest, "output-build", List.of(new OutputGates.Target("output-1",
					"tree", List.of("true"))));
				throw new AssertionError("Expected no-op output gate to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("no-op command");
			}
		}
	}

	/**
	 * Verifies a draft-output gate rejects a no-op command before creating its temporary commit.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsNoOpDraftCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "cat-topic-replay-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			OutputGates gates = new OutputGates(new Git(directory),
				(ignored, prefix) -> directory.resolve(prefix));

			try
			{
				gates.runDraft(manifest, "draft-output-build", new OutputGates.Target("output-1", "tree",
					List.of("true")));
				throw new AssertionError("Expected no-op draft-output gate to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("no-op command");
			}
		}
	}
	/**
	 * Verifies selected and complete retained gates materialize each exact tree, retain logs, and remove worktrees.
	 *
	 * @throws IOException if the isolated repository or output gates cannot run
	 */
	@Test
	public void retainedOutputWorktreeLifecycle() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "retained-gates-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String first = commitFeature(git, repository, "first\n");
			String second = commitFeature(git, repository, "second\n");
			Path manifest = invocationDirectory(repository, "replay").resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			OutputGates gates = gates(git, cleanup);
			List<OutputWitness> witnesses = List.of(new OutputWitness("first", History.EMPTY_TREE, first),
				new OutputWitness("second", first, second));
			List<String> command = GateCommandFixture.command(GateCommandFixture.Mode.CHECK_FEATURE);

			gates.run(manifest, "per-output-build", GitTopicReplayEditor.selectOutputTargets(witnesses, "first", command));
			requireThat(Files.readString(manifest.resolveSibling("gates/per-output-build-first.log")), "selectedLog").
				isEqualTo("first\n");
			requireThat(Files.exists(manifest.resolveSibling("gates/per-output-build-second.log")), "unselectedLog").
				isFalse();
			gates.run(manifest, "per-output-build", GitTopicReplayEditor.selectOutputTargets(witnesses, null, command));
			requireThat(Files.readString(manifest.resolveSibling("gates/per-output-build-second.log")), "secondLog").
				isEqualTo("second\n");

			Map<String, OutputWitness> byOutput = Map.of("first", witnesses.getFirst(), "second", witnesses.getLast());
			for (SemanticCandidate candidate : DeclaredOutputAcceptance.selectCandidates(
				List.of(candidate("first"), candidate("second")), null))
			{
				gates.run(manifest, "per-output-acceptance", List.of(DeclaredOutputAcceptance.target(candidate, byOutput)));
			}
			requireThat(Files.readString(manifest.resolveSibling("gates/per-output-acceptance-first.log")),
				"firstAcceptance").
				isEqualTo("first\n");
			requireThat(Files.readString(manifest.resolveSibling("gates/per-output-acceptance-second.log")),
				"secondAcceptance").
				isEqualTo("second\n");
			requireThat(git.lines("worktree", "list", "--porcelain").stream().filter(line -> line.startsWith("worktree ")).
				count(), "remainingWorktrees").isEqualTo(1L);
		}
	}

	/**
	 * Verifies draft acceptance materializes proposed trees including an uncommitted tree and removes worktrees.
	 *
	 * @throws IOException if the isolated repository or draft gates cannot run
	 */
	@Test
	public void draftOutputWorktreeLifecycle() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "draft-gates-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String first = commitFeature(git, repository, "first\n");
			Files.writeString(repository.resolve("feature.txt"), "proposed\n");
			git.lines("add", "feature.txt");
			String proposed = git.lines("write-tree").getFirst();
			Path manifest = invocationDirectory(repository, "replay").resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			OutputGates gates = gates(git, cleanup);
			Map<String, String> trees = Map.of("first", first, "second", proposed);

			gates.runDraft(manifest, "draft-per-output-acceptance", DraftOutputAcceptance.target(candidate("first"), trees));
			gates.runDraft(manifest, "draft-per-output-acceptance", DraftOutputAcceptance.target(candidate("second"), trees));

			requireThat(Files.readString(manifest.resolveSibling("gates/draft-per-output-acceptance-first.log")),
				"firstLog").
				isEqualTo("first\n");
			requireThat(Files.readString(manifest.resolveSibling("gates/draft-per-output-acceptance-second.log")),
				"draftLog").
				isEqualTo("proposed\n");
			requireThat(git.lines("worktree", "list", "--porcelain").stream().filter(line -> line.startsWith("worktree ")).
				count(), "remainingWorktrees").isEqualTo(1L);
		}
	}

	/**
	 * Captures a reachable tree with distinct feature content.
	 *
	 * @param git Git access for the fixture
	 * @param repository the fixture directory
	 * @param content the feature bytes
	 * @return the committed tree identity
	 * @throws IOException if the feature cannot be captured
	 */
	private static String commitFeature(Git git, Path repository, String content) throws IOException
	{
		Files.writeString(repository.resolve("feature.txt"), content);
		git.lines("add", "feature.txt");
		git.lines("commit", "--quiet", "-m", "test: feature");
		return git.lines("rev-parse", "HEAD^{tree}").getFirst();
	}

	/**
	 * Creates a gate executor whose temporary worktrees also belong to the test cleanup owner.
	 *
	 * @param git Git access for the fixture
	 * @param cleanup the test's temporary-resource owner
	 * @return the gate executor
	 */
	private static OutputGates gates(Git git, TemporaryDirectories cleanup)
	{
		return new OutputGates(git,
			(manifest, prefix) -> cleanup.add(Files.createTempDirectory(manifest.getParent(), prefix)));
	}

	/**
	 * Declares acceptance against the feature bytes in a target's working directory.
	 *
	 * @param output the behavior's destination
	 * @return a captured acceptance declaration
	 */
	private static SemanticCandidate candidate(String output)
	{
		return new SemanticCandidate(output, "feature", "reviewed feature", "separately usable", List.of(output),
			output, List.of(), GateCommandFixture.command(GateCommandFixture.Mode.CHECK_FEATURE), List.of(), List.of(),
			List.of(), "reviewed feature");
	}
}
