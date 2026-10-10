package io.github.cowwoc.cat.common.optimizer.workspace.test;

import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.content;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.file;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.ControlSource;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkTree;
import io.github.cowwoc.cat.common.optimizer.workspace.StagedSources;
import io.github.cowwoc.cat.common.optimizer.workspace.Workspace;
import io.github.cowwoc.cat.common.optimizer.workspace.WorkspaceException;
import io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.LinkedProject;
import io.github.cowwoc.cat.common.secrets.ForkTreeSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.testng.annotations.Test;

/**
 * Verifies that a staged source holds a candidate's files with every link replaced by a copy of its target, excludes
 * CAT's work directory, and is built once and deleted on request.
 */
public final class StagedSourceTest
{
	/**
	 * Creates the test.
	 */
	public StagedSourceTest()
	{
	}

	/**
	 * Verifies that a staged source holds the fork's files, the recorded fixtures, and the candidate's overlay.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void stagesForkFixturesAndOverlay() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));
			Overlay overlay = Overlay.of(project.tree().inventory()).
				write(Path.of("prompt.md"), content("edited"), READ_WRITE).
				write(Path.of("new.md"), content("new"), READ_WRITE).
				delete(Path.of("rules/b.md"));

			Path staged = sources.stage(overlay, Set.of());

			Map<Path, FileEntry> entries = InventoryScanner.scan(staged, Set.of()).entries();
			requireThat(entries.get(Path.of("prompt.md")), "prompt").isEqualTo(file("edited"));
			requireThat(entries.get(Path.of("new.md")), "created").isEqualTo(file("new"));
			requireThat(entries.get(Path.of("run.sh")), "executable").isEqualTo(file("#!/bin/sh\n", EXECUTABLE));
			requireThat(entries.get(Path.of("rules/a.md")), "rule").isEqualTo(file("rule a"));
			requireThat(entries.containsKey(Path.of("rules/b.md")), "deletedRuleRecorded").isFalse();
			requireThat(entries.get(Path.of("fixtures/data.txt")), "fixture").isEqualTo(file("fixture data"));
			requireThat(entries.get(Path.of(".cat/rules/r.md")), "catRule").isEqualTo(file("cat rule"));
		}
	}

	/**
	 * Verifies that a staged source leaves out the excluded paths, including files that a link places below them, and
	 * that sources of one candidate with different exclusions are separate and deleted together.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void leavesOutExcludedPaths() throws IOException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));
			Overlay overlay = Overlay.of(project.tree().inventory()).
				write(Path.of(".cat/tests/r/case/scenario.md"), content("scenario"), READ_WRITE).
				write(Path.of(".cat/tests/r/kept.md"), content("kept"), READ_WRITE);

			Path whole = sources.stage(overlay, Set.of());
			Path excluding = sources.stage(overlay, Set.of(Path.of(".cat/tests/r/case"), Path.of("docs")));

			Map<Path, FileEntry> entries = InventoryScanner.scan(excluding, Set.of()).entries();
			requireThat(entries.containsKey(Path.of(".cat/tests/r/case/scenario.md")), "excludedScenario").isFalse();
			requireThat(entries.containsKey(Path.of("docs/a.md")), "excludedThroughLink").isFalse();
			requireThat(entries.get(Path.of(".cat/tests/r/kept.md")), "kept").isEqualTo(file("kept"));
			requireThat(entries.get(Path.of("rules/a.md")), "rule").isEqualTo(file("rule a"));
			requireThat(whole.resolve(".cat/tests/r/case/scenario.md"), "wholeScenario").isRegularFile();
			requireThat(excluding, "excluding").isNotEqualTo(whole);

			sources.delete(overlay.identity());
			requireThat(Files.exists(whole), "wholeExists").isFalse();
			requireThat(Files.exists(excluding), "excludingExists").isFalse();
		}
	}

	/**
	 * Verifies that every link becomes a regular copy of its resolved target, as the target stands in the candidate,
	 * including links to directories and to external files.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void replacesEveryLinkWithCopyOfItsTarget() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));
			Overlay overlay = Overlay.of(project.tree().inventory()).
				write(Path.of("prompt.md"), content("edited"), READ_WRITE).
				delete(Path.of("rules/b.md"));

			Path staged = sources.stage(overlay, Set.of());

			Map<Path, FileEntry> entries = InventoryScanner.scan(staged, Set.of()).entries();
			requireThat(entries.values().stream().filter(SymbolicLink.class::isInstance).toList(), "links").isEmpty();
			requireThat(entries.get(Path.of("alias.md")), "fileLink").isEqualTo(file("edited"));
			requireThat(entries.get(Path.of("docs/a.md")), "directoryLink").isEqualTo(file("rule a"));
			requireThat(entries.containsKey(Path.of("docs/b.md")), "deletedThroughDirectoryLink").isFalse();
			requireThat(entries.get(Path.of("shared.md")), "externalFileLink").isEqualTo(file("shared"));
			requireThat(entries.get(Path.of("ext-dir/x.md")), "externalDirectoryLink").isEqualTo(file("external x"));
			requireThat(entries.get(Path.of("ext-dir/y.md")), "externalDirectoryLinkSibling").
				isEqualTo(file("external y"));
		}
	}

	/**
	 * Verifies that a write through a staged link path changes only the staged copy, never the project, the fork
	 * storage or a live external target.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void stagedLinkWritesReachNoLiveTarget() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));
			Path staged = sources.stage(Overlay.of(project.tree().inventory()), Set.of());

			Files.writeString(staged.resolve("shared.md"), "trial write", UTF_8);
			Files.writeString(staged.resolve("alias.md"), "trial write", UTF_8);

			requireThat(Files.readString(project.external().resolve("shared.md"), UTF_8), "liveExternal").
				isEqualTo("shared");
			requireThat(Files.readString(staged.resolve("prompt.md"), UTF_8), "stagedTarget").isEqualTo("prompt");
			requireThat(Files.readString(project.base().resolve("prompt.md"), UTF_8), "project").isEqualTo("prompt");
			requireThat(Files.readString(ForkTreeSecrets.access().physical(project.tree(), Path.of("prompt.md")), UTF_8),
				"forkStorage").isEqualTo("prompt");
		}
	}

	/**
	 * Verifies that CAT's work directory is excluded, whether it comes from the project or from an overlay.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void excludesWorkDirectory() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));
			Overlay overlay = Overlay.of(project.tree().inventory()).
				write(Path.of(".cat/work/leak.txt"), content("leak"), READ_WRITE);

			Path staged = sources.stage(overlay, Set.of());

			requireThat(Files.exists(staged.resolve(".cat/work")), "workDirectoryExists").isFalse();
			requireThat(staged.resolve(".cat/rules/r.md"), "catRule").isRegularFile();
		}
	}

	/**
	 * Verifies that the control source empties every in-project prompt file to zero bytes, including the files that
	 * prompt-source links reach, and keeps every other file. External prompt-source targets are read-only, so they
	 * keep their content.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void controlSourceEmptiesEveryPromptFile() throws IOException
	{
		// Requirement: R-W10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			FileInventory fork = project.tree().inventory();
			Authority authority = new Authority(Set.of(Path.of("alias.md"), Path.of("docs"), Path.of("shared.md")),
				Set.of(), fork);
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));

			Path staged = sources.stage(ControlSource.of(fork, authority), Set.of());

			requireThat(ControlSource.promptFiles(fork, authority), "promptFiles").
				isEqualTo(Set.of(Path.of("prompt.md"), Path.of("rules/a.md"), Path.of("rules/b.md")));
			Map<Path, FileEntry> entries = InventoryScanner.scan(staged, Set.of()).entries();
			requireThat(entries.get(Path.of("prompt.md")), "prompt").isEqualTo(file(""));
			requireThat(entries.get(Path.of("alias.md")), "fileLink").isEqualTo(file(""));
			requireThat(entries.get(Path.of("rules/a.md")), "ruleA").isEqualTo(file(""));
			requireThat(entries.get(Path.of("docs/b.md")), "directoryLink").isEqualTo(file(""));
			requireThat(entries.get(Path.of("shared.md")), "externalFileLink").isEqualTo(file("shared"));
			requireThat(entries.get(Path.of("run.sh")), "executable").isEqualTo(file("#!/bin/sh\n", EXECUTABLE));
			requireThat(entries.get(Path.of(".cat/rules/r.md")), "catRule").isEqualTo(file("cat rule"));
		}
	}

	/**
	 * Verifies that a candidate is staged once, that deleting its source removes it, that deleting a source that does
	 * not exist does nothing, and that a deleted source is rebuilt on demand.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void buildsOnceAndDeletesOnRequest() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			Path storage = project.campaign().resolve("staged");
			StagedSources sources = new StagedSources(project.tree(), storage);
			Overlay overlay = Overlay.of(project.tree().inventory());
			Overlay other = overlay.write(Path.of("prompt.md"), content("other"), READ_WRITE);

			Path first = sources.stage(overlay, Set.of());
			writeFile(first.resolve("marker.txt"), "built once", READ_WRITE);
			Path second = sources.stage(overlay, Set.of());
			Path different = sources.stage(other, Set.of());

			requireThat(second, "second").isEqualTo(first);
			requireThat(second.resolve("marker.txt"), "marker").isRegularFile();
			requireThat(first, "first").isEqualTo(storage.resolve(overlay.identity().hex()));
			requireThat(different, "different").isNotEqualTo(first);

			sources.delete(overlay.identity());
			sources.delete(overlay.identity());
			requireThat(Files.exists(first), "deletedExists").isFalse();
			requireThat(different, "otherSurvives").isDirectory();

			Path rebuilt = sources.stage(overlay, Set.of());
			requireThat(rebuilt, "rebuilt").isEqualTo(first);
			requireThat(Files.exists(rebuilt.resolve("marker.txt")), "markerRebuilt").isFalse();
			requireThat(rebuilt.resolve("prompt.md"), "rebuiltPrompt").isRegularFile();
		}
	}

	/**
	 * Verifies that concurrent requests to stage one candidate all return the same complete source.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 * @throws InterruptedException if the test is interrupted while it waits
	 * @throws ExecutionException if a staging request fails
	 */
	@Test
	public void concurrentRequestsShareOneSource() throws IOException, InterruptedException, ExecutionException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ExecutorService executor = Executors.newFixedThreadPool(4))
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));
			Overlay overlay = Overlay.of(project.tree().inventory());

			List<Future<Path>> requests = List.of(executor.submit(() -> sources.stage(overlay, Set.of())),
				executor.submit(() -> sources.stage(overlay, Set.of())),
				executor.submit(() -> sources.stage(overlay, Set.of())),
				executor.submit(() -> sources.stage(overlay, Set.of())));

			Path expected = project.campaign().resolve("staged").resolve(overlay.identity().hex());
			for (Future<Path> request : requests)
				requireThat(request.get(), "staged").isEqualTo(expected);
			requireThat(InventoryScanner.scan(expected, Set.of()).entries().get(Path.of("docs/a.md")), "linkCopy").
				isEqualTo(file("rule a"));
			requireThat(childCount(project.campaign().resolve("staged")), "directories").isEqualTo(1L);
		}
	}

	/**
	 * Verifies that a link cycle blocks staging, names the link, and leaves nothing behind.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void linkCycleBlocksStaging() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			Path base = Files.createDirectories(root.resolve("project"));
			writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
			Files.createSymbolicLink(base.resolve("loop-a"), Path.of("loop-b"));
			Files.createSymbolicLink(base.resolve("loop-b"), Path.of("loop-a"));
			Files.createDirectories(base.resolve("docs"));
			writeFile(base.resolve("docs/a.md"), "a", READ_WRITE);
			Files.createSymbolicLink(base.resolve("docs/self"), Path.of("."));
			ForkTree tree = ForkTree.record(base, root.resolve("campaign"),
				Set.of(Path.of("prompt.md"), Path.of("loop-a"), Path.of("loop-b"), Path.of("docs")));
			Path storage = root.resolve("campaign/staged");
			StagedSources sources = new StagedSources(tree, storage);

			try
			{
				sources.stage(Overlay.of(tree.inventory()), Set.of());
				throw new AssertionError("Staging a link cycle must fail");
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("loop-a").contains("docs/self").contains("cycle");
			}
			requireThat(childCount(storage), "leftovers").isEqualTo(0L);
		}
	}

	/**
	 * Verifies that a link to a missing target blocks staging and names the link and its target.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void missingTargetBlocksStaging() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			Path base = Files.createDirectories(root.resolve("project"));
			writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
			Files.createSymbolicLink(base.resolve("gone.md"), Path.of("nowhere.md"));
			ForkTree tree = ForkTree.record(base, root.resolve("campaign"),
				Set.of(Path.of("prompt.md"), Path.of("gone.md")));
			StagedSources sources = new StagedSources(tree, root.resolve("campaign/staged"));

			try
			{
				sources.stage(Overlay.of(tree.inventory()), Set.of());
				throw new AssertionError("Staging a missing link target must fail");
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("gone.md").contains("nowhere.md").
					contains("missing target");
			}
		}
	}

	/**
	 * Verifies that an overlay on a different fork is rejected.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*fork.*")
	public void rejectsOverlayOnOtherFork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));
			Path otherBase = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-staged-other-")));
			writeFile(otherBase.resolve("other.md"), "other", READ_WRITE);
			ForkTree other = ForkTree.record(otherBase, otherBase.resolveSibling(otherBase.getFileName() + "-campaign"),
				Set.of(Path.of("other.md")));
			cleanup.add(otherBase.resolveSibling(otherBase.getFileName() + "-campaign"));

			sources.stage(Overlay.of(other.inventory()), Set.of());
		}
	}

	/**
	 * Verifies that the workspace stages a candidate through the port into its staged-source storage.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void workspaceStagesThroughPort() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));
			Overlay overlay = Overlay.of(project.tree().inventory());

			Path staged = port.stage(overlay, Set.of());

			requireThat(staged, "staged").isEqualTo(project.campaign().resolve("staged").resolve(overlay.identity().hex()));
			requireThat(staged.resolve("alias.md"), "aliasCopy").isRegularFile();
			port.deleteStaged(overlay.identity());
			requireThat(Files.exists(staged), "deletedExists").isFalse();
		}
	}

	/**
	 * Counts the entries of a directory.
	 *
	 * @param directory the directory, which may not exist
	 * @return the number of entries, or 0 if the directory does not exist
	 * @throws IOException if the directory cannot be read
	 */
	private static long childCount(Path directory) throws IOException
	{
		if (Files.notExists(directory))
			return 0;
		try (Stream<Path> children = Files.list(directory))
		{
			return children.count();
		}
	}
}
