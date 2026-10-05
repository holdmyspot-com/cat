package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.git.replay.ReplayFixtures.addedPath;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

/**
 * Tests CAT's interpretation of immutable history facts and its actual Git inventory-format integration.
 */
public final class HistoryTest
{
	/**
	 * Creates the test instance required by TestNG.
	 */
	public HistoryTest()
	{
	}

	/**
	 * Verifies an orphan-root replay preserves its explicit base and receives CAT's empty-tree identity without Git.
	 *
	 * @throws IOException if the root sentinel unexpectedly reaches Git
	 */
	@Test
	public void resolvesOrphanRootBase() throws IOException
	{
		Path repository = Path.of("unused-history-repository");
		History history = new History(repository, new Git(repository));

		requireThat(history.resolveBase("--root"), "base").isEqualTo("--root");
		requireThat(history.baseTree("--root"), "tree").isEqualTo(
			"4b825dc642cb6eb9a060e54bf8d69288fbee4904");
	}

	/**
	 * Verifies planning rejects parsed tracked changes after its source tip is captured.
	 *
	 * @throws IOException for the expected tracked-change rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*planning requires no tracked worktree or index changes.*")
	public void rejectsDirtyPlanningRepository() throws IOException
	{
		PlanningState.rejectTrackedChanges(List.of(" M rules/example.md"));
	}

	/**
	 * Verifies CAT reads Git's actual inventory format as immutable prior and resulting path identities.
	 *
	 * @throws IOException if the isolated repository or its inventory cannot be created
	 */
	@Test
	public void inventoriesSourcePathStates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "history-"));
			TopicReplayRepository.initializeRepository(repository);
			Git git = new Git(repository);
			TopicReplayRepository.commit(git, repository, "rules/example.md", "first\n", "add rule");
			String source = TopicReplayRepository.commit(git, repository, "rules/example.md", "second\n", "revise rule");

			History.InventoryEntry entry = new History(repository, git).inventory(source).getFirst();

			requireThat(entry.id(), "id").isEqualTo(source + ":1");
			requireThat(entry.source(), "source").isEqualTo(source);
			requireThat(entry.path(), "path").isEqualTo("rules/example.md");
			requireThat(entry.beforeMode(), "beforeMode").isEqualTo("100644");
			requireThat(entry.beforeBlob(), "beforeBlob").isNotEqualTo(entry.afterBlob());
			requireThat(entry.afterMode(), "afterMode").isEqualTo("100644");
		}
	}

	/**
	 * Verifies CAT derives stable skill and custom-agent entry points from captured paths and manifest contents.
	 *
	 * @throws IOException if a valid captured agent declaration is rejected
	 */
	@Test
	public void discoversProductEntryPoints() throws IOException
	{
		List<History.InventoryEntry> inventory = List.of(
			addedPath("client/codex/skills/src/main/resources/skills/diagnose/SKILL.md", "skill"),
			addedPath("client/codex/plugin/src/main/resources/agents/codex/reviewer.toml", "agent"));
		Map<String, String> manifests = Map.of("agent", "name = \"cat-reviewer\"\n");

		List<ExposedEntryPoint> entries = EntryPoints.collect(inventory, manifests::get);

		requireThat(entries.stream().map(ExposedEntryPoint::id).toList(), "entryPoints").
			isEqualTo(List.of("agent:reviewer", "skill:diagnose"));
		requireThat(entries.getFirst().kind(), "agentKind").isEqualTo("custom_agent");
	}

	/**
	 * Verifies CAT rereads a movable symbolic revision and retains the state already read for a full object identity.
	 * The reader supplies path facts directly; its invocation count observes CAT's caching decision.
	 *
	 * @throws IOException if supplied path facts cannot be read
	 */
	@Test
	public void rereadsSymbolicRevisionAfterItMoves() throws IOException
	{
		PathStates states = new PathStates();
		String first = "a".repeat(40);
		PathState firstState = new PathState("100644", "first");
		AtomicReference<PathState> current = new AtomicReference<>(firstState);
		AtomicInteger reads = new AtomicInteger();
		PathStates.Reader reader = () ->
		{
			reads.incrementAndGet();
			return current.get();
		};
		requireThat(states.read(first, "rules/example.md", reader), "firstState").isEqualTo(firstState);
		requireThat(states.read("HEAD", "rules/example.md", reader), "headBeforeMove").isEqualTo(firstState);

		current.set(new PathState("100644", "second"));

		requireThat(states.read("HEAD", "rules/example.md", reader), "headAfterMove").isNotEqualTo(firstState);
		requireThat(states.read(first, "rules/example.md", reader), "firstAfterMove").isEqualTo(firstState);
		requireThat(reads.get(), "pathReads").isEqualTo(3);
	}
}
