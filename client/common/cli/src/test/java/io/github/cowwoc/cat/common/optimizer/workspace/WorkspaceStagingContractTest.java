package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.FakeWorkspacePort;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies the staged file contents used by driver measurements in both workspace implementations.
 */
public final class WorkspaceStagingContractTest
{
	/**
	 * The workspace implementations under the same contract.
	 */
	public enum Kind
	{
		FAKE,
		FILES
	}

	/**
	 * Creates the test.
	 */
	public WorkspaceStagingContractTest()
	{
	}

	/**
	 * Returns the implementations.
	 *
	 * @return the implementations
	 */
	@DataProvider
	public Object[][] kinds()
	{
		return new Object[][] {{Kind.FAKE}, {Kind.FILES}};
	}

	/**
	 * Verifies that staging reflects writes, creations and deletions from the candidate.
	 *
	 * @param kind the implementation
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(dataProvider = "kinds")
	public void stagesCandidateBytes(Kind kind) throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("workspace-staging"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("prompt.md"), "original", UTF_8);
			Files.writeString(project.resolve("deleted.md"), "delete", UTF_8);
			Path storage = root.resolve("campaign");
			ForkTree tree = ForkTree.record(project, storage, Set.of(Path.of("prompt.md"), Path.of("deleted.md")));
			WorkspacePort workspace = switch (kind)
			{
				case FAKE -> new FakeWorkspacePort(root.resolve("fake"), tree.inventory(), project);
				case FILES -> new Workspace(tree, storage, root.resolve("worktree"));
			};
			Overlay candidate = Overlay.of(tree.inventory()).write(Path.of("prompt.md"),
				new FileContent("edited".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--")).
				write(Path.of("new.md"), new FileContent("created".getBytes(UTF_8)),
					PosixFilePermissions.fromString("rw-r--r--")).delete(Path.of("deleted.md"));
			Path staged = workspace.stage(candidate);
			requireThat(Files.readString(staged.resolve("prompt.md"), UTF_8), "edited").isEqualTo("edited");
			requireThat(Files.readString(staged.resolve("new.md"), UTF_8), "created").isEqualTo("created");
			requireThat(Files.exists(staged.resolve("deleted.md")), "deleted").isFalse();
		}
	}
}
