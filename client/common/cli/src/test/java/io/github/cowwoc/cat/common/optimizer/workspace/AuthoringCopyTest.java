package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.content;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.file;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.AttemptReview;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryDiff;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange.TargetWrite;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.LinkedProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies that an authoring copy holds the working candidate with links replaced by copies, and that scanning it
 * before and after an author session captures the session's delta.
 */
public final class AuthoringCopyTest
{
	/**
	 * Creates the test.
	 */
	public AuthoringCopyTest()
	{
	}

	/**
	 * Verifies that a prepared copy holds the working candidate, including its overlay, with every link replaced by a
	 * regular copy, and that each copy is a separate directory under the campaign's authoring storage.
	 *
	 * @throws IOException if the fixture cannot be created or prepared
	 */
	@Test
	public void preparesCopyOfWorkingCandidate() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-authoring-")));
			AuthoringCopies copies = new AuthoringCopies(project.tree(), project.campaign().resolve("authoring"));
			Overlay working = Overlay.of(project.tree().inventory()).
				write(Path.of("prompt.md"), content("edited"), READ_WRITE);

			Path first = copies.prepare(working);
			Path second = copies.prepare(working);

			requireThat(first, "first").isNotEqualTo(second);
			requireThat(first.getParent(), "parent").isEqualTo(project.campaign().resolve("authoring"));
			FileInventory scan = copies.scan(first);
			requireThat(scan.root(), "root").isEqualTo(first);
			requireThat(scan.entries().get(Path.of("prompt.md")), "prompt").isEqualTo(file("edited"));
			requireThat(scan.entries().get(Path.of("alias.md")), "fileLink").isEqualTo(file("edited"));
			requireThat(scan.entries().get(Path.of("docs/a.md")), "directoryLink").isEqualTo(file("rule a"));
			requireThat(scan.entries().get(Path.of("ext-dir/x.md")), "externalLink").isEqualTo(file("external x"));
			requireThat(scan.entries().values().stream().filter(SymbolicLink.class::isInstance).toList(), "links").
				isEmpty();
			requireThat(scan.entries().containsKey(Path.of(".cat/work/state.json")), "workDirectory").isFalse();
		}
	}

	/**
	 * Verifies that scanning a copy before and after an edit captures the edit, and that an edit made at a link path
	 * is reviewed as an edit of the link's resolved target.
	 *
	 * @throws IOException if the fixture cannot be created, prepared or scanned
	 */
	@Test
	public void capturesDeltaOfAuthorSession() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-authoring-")));
			AuthoringCopies copies = new AuthoringCopies(project.tree(), project.campaign().resolve("authoring"));
			Path copy = copies.prepare(Overlay.of(project.tree().inventory()));
			FileInventory before = copies.scan(copy);

			Files.writeString(copy.resolve("docs/a.md"), "author edit", UTF_8);
			writeFile(copy.resolve("rules/c.md"), "created", READ_WRITE);
			FileInventory after = copies.scan(copy);

			InventoryDiff diff = InventoryDiff.between(before, after);
			requireThat(diff.changes().stream().map(PathChange::path).toList(), "changedPaths").
				isEqualTo(List.of(Path.of("docs/a.md"), Path.of("rules/c.md")));
			Authority authority = new Authority(Set.of(Path.of("rules"), Path.of("prompt.md")), Set.of(), Set.of());
			AttemptReview review = diff.review(project.tree().inventory(), authority);
			requireThat(review.isPermitted(), "isPermitted").isTrue();
			requireThat(review.changes().stream().anyMatch(change ->
				change instanceof TargetWrite write && write.target().equals(Path.of("rules/a.md")) &&
					write.after().equals(file("author edit"))), "targetWritten").isTrue();
		}
	}

	/**
	 * Verifies that deleting a copy removes it, that deleting a copy that does not exist does nothing, and that the
	 * workspace routes preparation, scanning and deletion through the port.
	 *
	 * @throws IOException if the fixture cannot be created or prepared
	 */
	@Test
	public void deletesCopyThroughPort() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-authoring-")));
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), project.campaign().resolve("worktree"));

			Path copy = port.prepareAuthoringCopy(Overlay.of(project.tree().inventory()));
			requireThat(port.scanAuthoringCopy(copy).entries().get(Path.of("prompt.md")), "prompt").
				isEqualTo(file("prompt"));
			port.deleteAuthoringCopy(copy);
			port.deleteAuthoringCopy(copy);

			requireThat(Files.exists(copy), "copyExists").isFalse();
		}
	}

	/**
	 * Verifies that scanning a directory that the workspace did not prepare is rejected.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*authoring.*")
	public void rejectsScanOfForeignDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-authoring-")));
			AuthoringCopies copies = new AuthoringCopies(project.tree(), project.campaign().resolve("authoring"));

			copies.scan(project.base());
		}
	}

	/**
	 * Verifies that a working candidate whose links cannot be copied is rejected and leaves no copy behind.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void linkCycleBlocksPreparation() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-authoring-"));
			Path base = Files.createDirectories(root.resolve("project"));
			Files.createSymbolicLink(base.resolve("loop"), Path.of("loop"));
			ForkTree tree = ForkTree.record(base, root.resolve("campaign"), Set.of(Path.of("loop")));
			Path storage = root.resolve("campaign/authoring");
			AuthoringCopies copies = new AuthoringCopies(tree, storage);

			try
			{
				copies.prepare(Overlay.of(tree.inventory()));
				throw new AssertionError("Preparing a copy with a link cycle must fail");
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("loop").contains("cycle");
			}
			requireThat(Files.notExists(storage), "storageCreated").isTrue();
		}
	}
}
