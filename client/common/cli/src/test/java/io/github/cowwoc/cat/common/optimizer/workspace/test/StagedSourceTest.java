package io.github.cowwoc.cat.common.optimizer.workspace.test;

import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.allowListing;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.content;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.createLinks;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.denyListing;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.file;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.PromptConfiguration;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.PluginRemovals;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.workspace.ControlSource;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkTree;
import io.github.cowwoc.cat.common.optimizer.workspace.StagedSources;
import io.github.cowwoc.cat.common.optimizer.workspace.Workspace;
import io.github.cowwoc.cat.common.optimizer.workspace.WorkspaceException;
import io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.LinkedProject;
import io.github.cowwoc.cat.common.secrets.ForkTreeSecrets;
import io.github.cowwoc.cat.common.test.ProgramFixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that a staged source holds a candidate's files with every link replaced by a copy of its target, excludes
 * CAT's work directory, and is built once and deleted on request.
 */
public final class StagedSourceTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String HOOKS = """
		{"hooks": {
		  "SessionStart": [{"hooks": [{"type": "command", "command": "${PLUGIN_ROOT}/hooks/session-start.sh"}]}],
		  "PostToolUse": [{"matcher": "^Bash$", "hooks": [
		    {"type": "command", "command": "${PLUGIN_ROOT}/hooks/post-tool-use.sh"}]}]
		}}
		""";

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
			requireThat(entries.get(Path.of(ProgramFixture.name("run"))), "executable").
				isEqualTo(file(ProgramFixture.TEXT, EXECUTABLE));
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
	 * Verifies that the control source empties only the target prompt file, which a link to it also shows empty, and
	 * keeps every other file, including the other in-project prompt files that a candidate may edit.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void controlSourceEmptiesOnlyTarget() throws IOException
	{
		// Requirement: R-W10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			FileInventory fork = project.tree().inventory();
			Authority authority = new Authority(Set.of(Path.of("alias.md"), Path.of("docs"), Path.of("shared.md")),
				Set.of(), fork);
			StagedSources sources = new StagedSources(project.tree(), project.campaign().resolve("staged"));
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));

			Path staged = sources.stage(ControlSource.of(port, fork, Path.of("prompt.md"), List.of(), JSON), Set.of());

			requireThat(authority.promptFiles(fork), "promptFiles").
				isEqualTo(Set.of(Path.of("prompt.md"), Path.of("rules/a.md"), Path.of("rules/b.md")));
			Map<Path, FileEntry> entries = InventoryScanner.scan(staged, Set.of()).entries();
			requireThat(entries.get(Path.of("prompt.md")), "target").isEqualTo(file(""));
			requireThat(entries.get(Path.of("alias.md")), "fileLink").isEqualTo(file(""));
			requireThat(entries.get(Path.of("rules/a.md")), "otherPromptFile").isEqualTo(file("rule a"));
			requireThat(entries.get(Path.of("docs/b.md")), "directoryLink").isEqualTo(file("rule b"));
			requireThat(entries.get(Path.of("shared.md")), "externalFileLink").isEqualTo(file("shared"));
			requireThat(entries.get(Path.of(ProgramFixture.name("run"))), "executable").
				isEqualTo(file(ProgramFixture.TEXT, EXECUTABLE));
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
				requireThat(e.getMessage(), "message").contains("loop-a").contains(Path.of("docs/self").toString()).
					contains("cycle");
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
	 * Verifies that a recorded link to an existing file outside the project and a recorded link to an existing
	 * directory outside the project are staged with their targets' content, without the targets being named as
	 * paths to record.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void stagesLinksToExternalTargets() throws IOException
	{
		// Requirement: R-W2, R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			Path base = Files.createDirectories(root.resolve("project"));
			Path rules = writeFile(root.resolve("outside/reply-rules.md"), "reply rules", READ_WRITE);
			Path kit = root.resolve("outside/kit");
			writeFile(kit.resolve("guide.md"), "guide", READ_WRITE);
			Set<Path> links = createLinks(base, Map.of("docs/reply-rules.md", rules, "kit", kit));
			ForkTree tree = ForkTree.record(base, root.resolve("campaign"), links);
			StagedSources sources = new StagedSources(tree, root.resolve("campaign/staged"));

			Path staged = sources.stage(Overlay.of(tree.inventory()), Set.of());

			requireThat(Files.readString(staged.resolve("docs/reply-rules.md"), UTF_8), "fileLink").
				isEqualTo("reply rules");
			requireThat(Files.readString(staged.resolve("kit/guide.md"), UTF_8), "directoryLink").isEqualTo("guide");
			requireThat(tree.inventory().entries().get(rules), "recordedFile").isEqualTo(file("reply rules"));
		}
	}

	/**
	 * Verifies that recording follows links that leave the project through recorded external targets: an external
	 * file that is itself an absolute link, and an external directory holding a relative link that leaves it.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void stagesExternalTargetsReachedTransitively() throws IOException
	{
		// Requirement: R-W2, R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			Path base = Files.createDirectories(root.resolve("project"));
			Path outside = root.resolve("outside");
			Path kit = outside.resolve("kit");
			writeFile(kit.resolve("guide.md"), "guide", READ_WRITE);
			writeFile(outside.resolve("common/shared.md"), "shared", READ_WRITE);
			Files.createSymbolicLink(kit.resolve("shared.md"), Path.of("../common/shared.md"));
			Path last = writeFile(outside.resolve("final.md"), "final", READ_WRITE);
			Path alias = Files.createSymbolicLink(outside.resolve("alias.md"), last);
			Set<Path> links = createLinks(base, Map.of("kit", kit, "chain.md", alias));
			ForkTree tree = ForkTree.record(base, root.resolve("campaign"), links);
			StagedSources sources = new StagedSources(tree, root.resolve("campaign/staged"));

			Path staged = sources.stage(Overlay.of(tree.inventory()), Set.of());

			requireThat(Files.readString(staged.resolve("kit/shared.md"), UTF_8), "relativeLinkLeavingTarget").
				isEqualTo("shared");
			requireThat(Files.readString(staged.resolve("chain.md"), UTF_8), "externalLinkChain").isEqualTo("final");
		}
	}

	/**
	 * Verifies that links whose targets cannot be recorded still block staging as missing targets: a link to a file
	 * that does not exist, a link to a directory that contains the project, and a link to an external directory that
	 * cannot be read. The unreadable directory is empty so that the link is unstageable whether or not the process
	 * can read it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void unrecordableExternalTargetsStillBlock() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			Path base = Files.createDirectories(root.resolve("project"));
			writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
			Path missing = root.resolve("outside/missing.md");
			Path sealed = Files.createDirectories(root.resolve("outside/sealed"));
			Set<Path> paths = new HashSet<>(createLinks(base, Map.of("missing.md", missing, "up", root,
				"sealed", sealed)));
			paths.add(Path.of("prompt.md"));
			denyListing(sealed);
			try
			{
				ForkTree tree = ForkTree.record(base, root.resolve("campaign"), paths);
				StagedSources sources = new StagedSources(tree, root.resolve("campaign/staged"));
				try
				{
					sources.stage(Overlay.of(tree.inventory()), Set.of());
					throw new AssertionError("Links to unrecordable targets must block staging");
				}
				catch (WorkspaceException e)
				{
					requireThat(e.getMessage(), "message").
						contains("missing.md -> " + missing + ": missing target").
						contains("up -> " + root + ": missing target").
						contains("sealed -> " + sealed + ": missing target");
				}
			}
			finally
			{
				allowListing(sealed);
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
	 * Verifies that a project-built plugin's copy for a candidate holds the candidate's edit of a mapped file, that the
	 * copy for the control source holds the emptied file, that both keep the base's other files, that the base is
	 * unchanged, and that deleting the candidate deletes its copy.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	// Requirement: R-W12
	@Test
	public void builtPluginCopyHoldsStagedFiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			LinkedProject project = ProjectFixtures.linkedProject(root);
			Path base = pluginBase(root.resolve("plugin-base"));
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));
			TrialPlugin plugin = builtPlugin("cat", port.copyPluginBase(base));
			FileInventory fork = project.tree().inventory();
			Overlay candidate = Overlay.of(fork).write(Path.of("rules/a.md"), content("candidate rule a"), READ_WRITE);
			Overlay control = ControlSource.of(port, fork, Path.of("rules/a.md"), List.of(), JSON);

			Path candidateCopy = port.pluginCopy(plugin, port.stage(candidate, Set.of()), PluginRemovals.NONE);
			Path controlCopy = port.pluginCopy(plugin, port.stage(control, Set.of()), PluginRemovals.NONE);

			Map<Path, FileEntry> candidateFiles = InventoryScanner.scan(candidateCopy, Set.of()).entries();
			requireThat(candidateFiles.get(Path.of("rules/codex/a.md")), "candidateEdit").
				isEqualTo(file("candidate rule a"));
			requireThat(candidateFiles.get(Path.of("rules/codex/b.md")), "candidateUnedited").isEqualTo(file("rule b"));
			requireThat(candidateFiles.get(Path.of("skills/plan.md")), "candidateBaseFile").
				isEqualTo(file("installed skill"));
			Map<Path, FileEntry> controlFiles = InventoryScanner.scan(controlCopy, Set.of()).entries();
			requireThat(controlFiles.get(Path.of("rules/codex/a.md")), "controlEmptied").isEqualTo(file(""));
			requireThat(controlFiles.get(Path.of("rules/codex/b.md")), "controlUnemptied").isEqualTo(file("rule b"));
			requireThat(controlFiles.get(Path.of("skills/plan.md")), "controlBaseFile").
				isEqualTo(file("installed skill"));
			requireThat(Files.readString(base.resolve("rules/codex/a.md")), "base").isEqualTo("installed rule a");
			requireThat(Files.readString(port.pluginBase(plugin.baseDigest()).resolve("rules/codex/a.md")),
				"baseCopy").isEqualTo("installed rule a");

			port.deleteStaged(candidate.identity());
			requireThat(Files.exists(candidateCopy), "deletedCopyExists").isFalse();
			requireThat(controlCopy, "controlCopy").isDirectory();
		}
	}

	/**
	 * Verifies that a built plugin's copy with removals leaves out each dependent, as a deleted file or an
	 * unregistered hook, while the copy without removals and the base keep them.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void builtPluginCopyRemovesDependents() throws IOException
	{
		// Requirement: R-W10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			LinkedProject project = ProjectFixtures.linkedProject(root);
			Path base = pluginBase(root.resolve("plugin-base"));
			writeFile(base.resolve("bin/read-part"), "#!/bin/sh\n", EXECUTABLE);
			writeFile(base.resolve("hooks/hooks.json"), HOOKS, READ_WRITE);
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));
			TrialPlugin plugin = builtPlugin("cat", port.copyPluginBase(base));
			Path source = port.stage(Overlay.of(project.tree().inventory()), Set.of());
			PluginRemovals removals = PluginRemovals.of(List.of(
				new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.WholeFile(),
					"Reads the part that the rule names."),
				new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("hooks/hooks.json"),
					new Dependent.HookRegistration("PostToolUse", "${PLUGIN_ROOT}/hooks/post-tool-use.sh"),
					"Repairs the output of read-part.")), JSON);

			Path withDependents = port.pluginCopy(plugin, source, PluginRemovals.NONE);
			Path withoutDependents = port.pluginCopy(plugin, source, removals);

			requireThat(withoutDependents, "withoutDependents").isNotEqualTo(withDependents);
			requireThat(Files.exists(withoutDependents.resolve("bin/read-part")), "removedScript").isFalse();
			JsonNode hooks = JSON.readTree(Files.readString(withoutDependents.resolve("hooks/hooks.json"))).get("hooks");
			requireThat(hooks.has("PostToolUse"), "removedHook").isFalse();
			requireThat(hooks.has("SessionStart"), "otherHook").isTrue();
			requireThat(withoutDependents.resolve("rules/codex/b.md"), "mappedFile").isRegularFile();
			requireThat(withDependents.resolve("bin/read-part"), "keptScript").isRegularFile();
			requireThat(Files.readString(withDependents.resolve("hooks/hooks.json")), "keptHooks").isEqualTo(HOOKS);
			requireThat(base.resolve("bin/read-part"), "baseScript").isRegularFile();
			requireThat(Files.readString(base.resolve("hooks/hooks.json")), "baseHooks").isEqualTo(HOOKS);
		}
	}

	/**
	 * Verifies that a built plugin's copy is refused when the base does not hold a dependent that the copy removes, so
	 * that control trials never run with a dependent that the contract claims they lack.
	 *
	 * @throws IOException if the fixture cannot be created, or because the base lacks the dependent
	 */
	@Test(expectedExceptions = WorkspaceException.class, expectedExceptionsMessageRegExp = ".*bin[/\\\\]run-part.*")
	public void refusesMissingPluginDependent() throws IOException
	{
		// Requirement: R-W10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			LinkedProject project = ProjectFixtures.linkedProject(root);
			Path base = pluginBase(root.resolve("plugin-base"));
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));
			TrialPlugin plugin = builtPlugin("cat", port.copyPluginBase(base));
			Path source = port.stage(Overlay.of(project.tree().inventory()), Set.of());

			port.pluginCopy(plugin, source, PluginRemovals.of(List.of(new Dependent(Dependent.Location.PLUGIN, "cat",
				Path.of("bin/run-part"), new Dependent.WholeFile(), "Runs the part that the rule names.")), JSON));
		}
	}

	/**
	 * Verifies that each project-built plugin gets its own copy, holding the staged files that its own mappings cover,
	 * and that a plugin that the project does not build is installed from its base's copy.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	// Requirement: R-W12
	@Test
	public void copyPerProjectBuiltPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-staged-"));
			LinkedProject project = ProjectFixtures.linkedProject(root);
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));
			TrialPlugin cat = builtPlugin("cat", port.copyPluginBase(pluginBase(root.resolve("cat-base"))));
			Path docsBase = root.resolve("docs-base");
			writeFile(docsBase.resolve(".codex-plugin/plugin.json"), "{\"name\": \"docs\"}", READ_WRITE);
			writeFile(docsBase.resolve("prompt.md"), "installed prompt", READ_WRITE);
			TrialPlugin docs = new TrialPlugin("docs", port.copyPluginBase(docsBase),
				new TrialPlugin.Build(List.of("build"), Path.of("out")),
				List.of(new TrialPlugin.Mapping(Path.of("prompt.md"), Path.of("prompt.md"))));
			Path toolsBase = root.resolve("tools-base");
			writeFile(toolsBase.resolve(".codex-plugin/plugin.json"), "{\"name\": \"tools\"}", READ_WRITE);
			TrialPlugin tools = new TrialPlugin("tools@market", port.copyPluginBase(toolsBase),
				new TrialPlugin.Installed(), List.of());
			Overlay candidate = Overlay.of(project.tree().inventory()).
				write(Path.of("rules/a.md"), content("candidate rule a"), READ_WRITE).
				write(Path.of("prompt.md"), content("candidate prompt"), READ_WRITE);
			Path source = port.stage(candidate, Set.of());

			Path catCopy = port.pluginCopy(cat, source, PluginRemovals.NONE);
			Path docsCopy = port.pluginCopy(docs, source, PluginRemovals.NONE);
			Path toolsCopy = port.pluginCopy(tools, source, PluginRemovals.NONE);

			requireThat(catCopy, "catCopy").isNotEqualTo(docsCopy);
			requireThat(Files.readString(catCopy.resolve("rules/codex/a.md")), "catEdit").isEqualTo("candidate rule a");
			requireThat(Files.exists(catCopy.resolve("prompt.md")), "catHoldsDocs").isFalse();
			requireThat(Files.readString(docsCopy.resolve("prompt.md")), "docsEdit").isEqualTo("candidate prompt");
			requireThat(Files.exists(docsCopy.resolve("rules")), "docsHoldsCat").isFalse();
			requireThat(toolsCopy, "toolsCopy").isEqualTo(port.pluginBase(tools.baseDigest()));
		}
	}

	/**
	 * Verifies that a staged source leaves out the target's prompt test configuration, which names the trial plugins,
	 * along with the prompt tests.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	// Requirement: R-W12
	@Test
	public void leavesOutPromptConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));
			Path target = Path.of("prompt.md");
			Path configuration = PromptConfiguration.location(target);
			Overlay overlay = Overlay.of(project.tree().inventory()).
				write(configuration, content("{\"codex\": {\"plugins\": []}}"), READ_WRITE);

			Path source = port.stage(overlay, PromptTestLocations.excluded(ContractFixtures.contract(), target,
				project.base()));

			requireThat(Files.exists(source.resolve(configuration)), "configuration").isFalse();
			requireThat(source.resolve(target), "target").isRegularFile();
		}
	}

	/**
	 * Verifies that a trial plugin's copy is refused when campaign storage holds no copy of its base, so that trials
	 * never install a base that {@code start} did not copy.
	 *
	 * @throws IOException if the fixture cannot be created, or because the base has no copy
	 */
	// Requirement: R-W12
	@Test(expectedExceptions = WorkspaceException.class, expectedExceptionsMessageRegExp =
		"Campaign storage holds no base of the trial plugin cat at .*\\. Run start so that CAT copies the plugin's " +
			"base again\\.")
	public void refusesUncopiedPluginBase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-staged-")));
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));
			Path source = port.stage(Overlay.of(project.tree().inventory()), Set.of());

			port.pluginCopy(builtPlugin("cat", "0".repeat(64)), source, PluginRemovals.NONE);
		}
	}

	/**
	 * Returns a trial plugin that the project builds, whose {@code rules} directory maps to {@code rules/codex}.
	 *
	 * @param name the plugin's name
	 * @param digest the digest of its base's files
	 * @return the plugin
	 */
	private static TrialPlugin builtPlugin(String name, String digest)
	{
		return new TrialPlugin(name, digest, new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(new TrialPlugin.Mapping(Path.of("rules"), Path.of("rules/codex"))));
	}

	/**
	 * Creates a built plugin's base: a Codex manifest, two installed rules and a skill.
	 *
	 * @param base the directory to create
	 * @return {@code base}
	 * @throws IOException if a file cannot be written
	 */
	private static Path pluginBase(Path base) throws IOException
	{
		writeFile(base.resolve(".codex-plugin/plugin.json"), "{\"name\": \"cat\"}", READ_WRITE);
		writeFile(base.resolve("rules/codex/a.md"), "installed rule a", READ_WRITE);
		writeFile(base.resolve("rules/codex/b.md"), "installed rule b", READ_WRITE);
		writeFile(base.resolve("skills/plan.md"), "installed skill", READ_WRITE);
		return base;
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
