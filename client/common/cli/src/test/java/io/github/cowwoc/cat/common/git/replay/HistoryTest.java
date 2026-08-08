package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests Git history facts used to define a topic-replay range.
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
	 * Verifies an orphan-root replay preserves its explicit base and receives Git's empty tree.
	 *
	 * @throws IOException if the isolated repository directory cannot be created
	 */
	@Test
	public void resolvesOrphanRootBase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-history-"));
			History history = new History(repository, new Git(repository));

			requireThat(history.resolveBase("--root"), "base").isEqualTo("--root");
			requireThat(history.baseTree("--root"), "tree").isEqualTo(
				"4b825dc642cb6eb9a060e54bf8d69288fbee4904");
		}
	}

	/**
	 * Verifies planning rejects tracked worktree changes after its source tip is captured.
	 *
	 * @throws IOException if the isolated repository cannot be created or Git cannot prepare the fixture
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*planning requires no tracked worktree or index changes.*")
	public void rejectsDirtyPlanningRepository() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-history-"));
			TopicReplayRepository.initializeRepository(repository);
			Git git = new Git(repository);
			String tip = TopicReplayRepository.commit(git, repository, "rules/example.md", "original\n", "add rule");
			Files.writeString(repository.resolve("rules/example.md"), "changed\n");

			new History(repository, git).verifyPlanningRepositoryState(tip);
		}
	}

	/**
	 * Verifies history reads one committed path change with its prior and resulting blob identities.
	 *
	 * @throws IOException if the isolated repository cannot be created or Git cannot create its fixture commits
	 */
	@Test
	public void inventoriesSourcePathStates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-history-"));
			TopicReplayRepository.initializeRepository(repository);
			Git git = new Git(repository);
			TopicReplayRepository.commit(git, repository, "rules/example.md", "first\n", "add rule");
			String source = TopicReplayRepository.commit(git, repository, "rules/example.md", "second\n", "revise rule");

			History.InventoryEntry entry = new History(repository, git).inventory(source).
				getFirst();

			requireThat(entry.id(), "id").isEqualTo(source + ":1");
			requireThat(entry.source(), "source").isEqualTo(source);
			requireThat(entry.path(), "path").isEqualTo("rules/example.md");
			requireThat(entry.beforeMode(), "beforeMode").isEqualTo("100644");
			requireThat(entry.beforeBlob(), "beforeBlob").isNotEqualTo(entry.afterBlob());
			requireThat(entry.afterMode(), "afterMode").isEqualTo("100644");
		}
	}

	/**
	 * Verifies history derives the stable skill and custom-agent entry points from committed path facts.
	 *
	 * @throws IOException if the isolated repository cannot be created or Git cannot create its fixture commits
	 */
	@Test
	public void discoversProductEntryPoints() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-history-"));
			TopicReplayRepository.initializeRepository(repository);
			Git git = new Git(repository);
			String skill = TopicReplayRepository.commit(git, repository,
				"client/codex/skills/src/main/resources/skills/diagnose/SKILL.md", "---\nname: diagnose\n", "add skill");
			String agent = TopicReplayRepository.commit(git, repository,
				"client/codex/plugin/src/main/resources/agents/codex/reviewer.toml", "name = \"cat-reviewer\"\n",
				"add agent");
			History history = new History(repository, git);
			List<History.InventoryEntry> inventory = List.of(history.inventory(skill).getFirst(),
				history.inventory(agent).getFirst());

			List<ExposedEntryPoint> entries = history.exposedEntryPoints(inventory);

			requireThat(entries.stream().map(ExposedEntryPoint::id).toList(), "entryPoints").
				isEqualTo(List.of("agent:reviewer", "skill:diagnose"));
		}
	}
}
