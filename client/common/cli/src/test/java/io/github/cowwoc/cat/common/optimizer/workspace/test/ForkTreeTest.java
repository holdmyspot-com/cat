package io.github.cowwoc.cat.common.optimizer.workspace.test;

import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.file;
import static io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkTree;
import io.github.cowwoc.cat.common.optimizer.workspace.WorkspaceException;
import io.github.cowwoc.cat.common.optimizer.workspace.test.ProjectFixtures.LinkedProject;
import io.github.cowwoc.cat.common.secrets.ForkTreeSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies that a fork tree records a project's files, modes and links, copies ignored, fixture and external files
 * into campaign storage, and never changes afterwards.
 */
public final class ForkTreeTest
{
	/**
	 * Creates the test.
	 */
	public ForkTreeTest()
	{
	}

	/**
	 * Verifies that the recorded inventory holds each file with its content hash and mode, each link with its stored
	 * destination, and each external file under its absolute path, and omits CAT's work directory.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void recordsPathsBytesModesAndLinks() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-tree-"));
			LinkedProject project = ProjectFixtures.linkedProject(root);
			Path external = project.external();

			Map<Path, FileEntry> expected = Map.ofEntries(
				Map.entry(Path.of("prompt.md"), file("prompt")),
				Map.entry(Path.of("run.sh"), file("#!/bin/sh\n", EXECUTABLE)),
				Map.entry(Path.of("rules/a.md"), file("rule a")),
				Map.entry(Path.of("rules/b.md"), file("rule b")),
				Map.entry(Path.of("fixtures/data.txt"), file("fixture data")),
				Map.entry(Path.of(".cat/rules/r.md"), file("cat rule")),
				Map.entry(Path.of("alias.md"), new SymbolicLink(Path.of("prompt.md"))),
				Map.entry(Path.of("docs"), new SymbolicLink(Path.of("rules"))),
				Map.entry(Path.of("shared.md"), new SymbolicLink(external.resolve("shared.md"))),
				Map.entry(Path.of("ext-dir"), new SymbolicLink(external.resolve("dir"))),
				Map.entry(external.resolve("shared.md"), file("shared")),
				Map.entry(external.resolve("dir/x.md"), file("external x")),
				Map.entry(external.resolve("dir/y.md"), file("external y")));
			requireThat(project.tree().inventory().root(), "root").isEqualTo(project.base());
			requireThat(project.tree().inventory().entries(), "entries").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that the tree holds its own copy of each file, so later edits to the project do not change it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void holdsCopiesThatLaterEditsDoNotChange() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-tree-"));
			LinkedProject project = ProjectFixtures.linkedProject(root);
			FileInventory before = project.tree().inventory();

			writeFile(project.base().resolve("prompt.md"), "edited", READ_WRITE);
			writeFile(project.external().resolve("shared.md"), "edited", READ_WRITE);

			requireThat(project.tree().inventory(), "inventory").isEqualTo(before);
			requireThat(Files.readString(ForkTreeSecrets.access().physical(project.tree(), Path.of("prompt.md")), UTF_8),
				"promptCopy").isEqualTo("prompt");
			Path shared = ForkTreeSecrets.access().physical(project.tree(), project.external().resolve("shared.md"));
			requireThat(Files.readString(shared, UTF_8), "externalCopy").isEqualTo("shared");
		}
	}

	/**
	 * Verifies that reopening recorded storage yields the same inventory without the original files.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reopensRecordedStorage() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-tree-"));
			LinkedProject project = ProjectFixtures.linkedProject(root);

			ForkTree reopened = ForkTree.open(project.base(), project.campaign());

			requireThat(reopened.inventory(), "inventory").isEqualTo(project.tree().inventory());
		}
	}

	/**
	 * Verifies that a path that does not exist stops the recording and leaves no fork storage behind.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void missingPathLeavesNoStorage() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-tree-"));
			Path base = Files.createDirectories(root.resolve("project"));
			Path campaign = root.resolve("campaign");
			writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);

			try
			{
				ForkTree.record(base, campaign, Set.of(Path.of("prompt.md"), Path.of("missing.md")));
				throw new AssertionError("Recording a missing path must fail");
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("missing.md");
			}
			requireThat(campaign.resolve("fork").toFile().exists(), "forkStorageExists").isFalse();
		}
	}

	/**
	 * Verifies that an absolute path inside the project is rejected, because the project's own paths record it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*inside\\.md.*")
	public void rejectsAbsolutePathInsideProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-tree-"));
			Path base = Files.createDirectories(root.resolve("project"));
			writeFile(base.resolve("inside.md"), "inside", READ_WRITE);

			ForkTree.record(base, root.resolve("campaign"), Set.of(base.resolve("inside.md")));
		}
	}
}
