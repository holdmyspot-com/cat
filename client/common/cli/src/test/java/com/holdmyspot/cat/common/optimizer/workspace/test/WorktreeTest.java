package com.holdmyspot.cat.common.optimizer.workspace.test;

import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.content;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryDiff;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.workspace.Workspace;
import com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.LinkedProject;
import com.holdmyspot.cat.common.test.ProgramFixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

/**
 * Verifies that the workspace scans the optimization worktree and writes a candidate into it, using a plain
 * directory in place of the Git worktree.
 */
public final class WorktreeTest
{
	/**
	 * Creates the test.
	 */
	public WorktreeTest()
	{
	}

	/**
	 * Verifies that materializing the fork's own candidate writes every recorded file, mode and link, including the
	 * ignored and external participants, so that a scan equals the candidate's expected inventory.
	 *
	 * @throws IOException if the fixture cannot be created or written
	 */
	@Test
	public void materializesForkCandidate() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-worktree-")));
			Path worktree = project.campaign().resolve("worktree");
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), worktree);
			Overlay fork = Overlay.of(project.tree().inventory());

			port.materialize(fork);

			FileInventory scan = port.scanWorktree();
			requireThat(scan.root(), "root").isEqualTo(worktree);
			requireThat(scan.entries(), "entries").isEqualTo(fork.snapshot().entries());
			requireThat(Files.isSymbolicLink(worktree.resolve("alias.md")), "linkKept").isTrue();
			requireThat(InventoryDiff.between(fork.snapshot(), scan).isEmpty(), "noUnownedChange").isTrue();
		}
	}

	/**
	 * Verifies that materializing a candidate applies its edits, creates and deletes, writing edits to external
	 * targets into the mirror rather than the live target, and that materializing another candidate afterwards
	 * restores the first one's files and removes unowned files.
	 *
	 * @throws IOException if the fixture cannot be created or written
	 */
	@Test
	public void materializesAnyCandidate() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedProject project = ProjectFixtures.linkedProject(cleanup.add(Files.createTempDirectory("cat-worktree-")));
			Path worktree = project.campaign().resolve("worktree");
			WorkspacePort port = new Workspace(project.tree(), project.campaign(), worktree);
			Overlay fork = Overlay.of(project.tree().inventory());
			Overlay edited = fork.
				write(Path.of("prompt.md"), content("edited"), READ_WRITE).
				write(Path.of(ProgramFixture.name("new/created")), content(ProgramFixture.TEXT), EXECUTABLE).
				write(project.external().resolve("shared.md"), content("external edit"), READ_WRITE).
				delete(Path.of("rules/a.md"));
			port.materialize(fork);

			port.materialize(edited);

			FileInventory scan = port.scanWorktree();
			requireThat(scan.entries(), "editedEntries").isEqualTo(edited.snapshot().entries());
			requireThat(Files.readString(worktree.resolve("prompt.md"), UTF_8), "prompt").isEqualTo("edited");
			requireThat(Files.exists(worktree.resolve("rules/a.md")), "deleted").isFalse();
			requireThat(Files.readString(project.external().resolve("shared.md"), UTF_8), "liveExternal").
				isEqualTo("shared");

			writeFile(worktree.resolve("unowned.md"), "unowned", READ_WRITE);
			Files.writeString(worktree.resolve("rules/b.md"), "tampered", UTF_8);
			Files.delete(worktree.resolve(ProgramFixture.name("run")));
			requireThat(InventoryDiff.between(edited.snapshot(), port.scanWorktree()).changes().size(), "changes").
				isEqualTo(3);

			port.materialize(fork);

			requireThat(port.scanWorktree().entries(), "restoredEntries").isEqualTo(fork.snapshot().entries());
			requireThat(Files.exists(worktree.resolve("unowned.md")), "unowned").isFalse();
		}
	}
}
