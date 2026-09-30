package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.candidate.LiveGitInventory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Checks that a private attempt reproduces selected Git and file state without editing the selected worktree.
 */
public final class SelectedWorktreeReplayTest
{
	/**
	 * Creates the test fixture.
	 */
	public SelectedWorktreeReplayTest()
	{
	}

	/**
	 * A private attempt receives selected changes and refuses to overwrite a changed private file mode.
	 *
	 * @throws IOException if fixture Git or private replay fails
	 */
	@Test
	public void replaysSelectedChanges() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("selected-replay-")).toRealPath();
			Path base = Files.createDirectory(root.resolve("base"));
			git(base, "init");
			git(base, "config", "user.email", "test@example.invalid");
			git(base, "config", "user.name", "Replay Test");
			Files.writeString(base.resolve("prompt.md"), "original\n");
			Files.writeString(base.resolve("retired.md"), "renamed content\n");
			Files.writeString(base.resolve("removed.md"), "deleted content\n");
			Files.createSymbolicLink(base.resolve("alias.md"), Path.of("prompt.md"));
			git(base, "add", ".");
			git(base, "commit", "-m", "initial");

			Path campaign = Files.createDirectory(root.resolve("workflow")).resolve("campaign");
			VerifiedCleanFork.Identity fork = VerifiedCleanFork.preflight(
				new CampaignLookupKey(base, Path.of("prompt.md")), campaign);
			VerifiedCleanFork.ensure(fork);
			Path selected = fork.optimizationRoot();
			Files.writeString(selected.resolve("prompt.md"), "staged\n");
			git(selected, "add", "prompt.md");
			Files.move(selected.resolve("retired.md"), selected.resolve("renamed.md"));
			Files.delete(selected.resolve("removed.md"));
			git(selected, "add", "-A");
			Files.writeString(selected.resolve("prompt.md"), "working\n");
			Files.setPosixFilePermissions(selected.resolve("prompt.md"),
				PosixFilePermissions.fromString("rw-r-----"));
			Files.createDirectory(selected.resolve("added"));
			Files.writeString(selected.resolve("added/extra.md"), "untracked\n");
			Files.setPosixFilePermissions(selected.resolve("added/extra.md"),
				PosixFilePermissions.fromString("rw-r-----"));
			Files.createSymbolicLink(selected.resolve("added/alias.md"), Path.of("extra.md"));
			LiveGitInventory expected = LiveGitInventory.capture(selected);

			Path privateWorktree = root.resolve("private");
			VerifiedCleanFork.ensureAttemptAt(fork, privateWorktree, selected, expected);
			VerifiedCleanFork.ensureAttemptAt(fork, privateWorktree, selected, expected);

			LiveGitInventory observed = LiveGitInventory.capture(privateWorktree);
			requireThat(observed.headCommit(), "privateHead").isEqualTo(expected.headCommit());
			requireThat(observed.indexSha256(), "privateIndex").isEqualTo(expected.indexSha256());
			requireThat(Files.readString(privateWorktree.resolve("prompt.md")), "privateWorkingContent").
				isEqualTo("working\n");
			requireThat(Files.readString(privateWorktree.resolve("added/extra.md")), "privateUntrackedContent").
				isEqualTo("untracked\n");
			requireThat(Files.readString(privateWorktree.resolve("renamed.md")), "privateStagedRename").
				isEqualTo("renamed content\n");
			requireThat(Files.notExists(privateWorktree.resolve("retired.md")), "oldRenamePath").isTrue();
			requireThat(Files.notExists(privateWorktree.resolve("removed.md")), "stagedDeletion").isTrue();
			requireThat(Files.readSymbolicLink(privateWorktree.resolve("added/alias.md")), "privateLinkText").
				isEqualTo(Path.of("extra.md"));
			requireThat(observed.paths().stream().filter(path -> path.path().equals(Path.of("prompt.md"))).
				findFirst().orElseThrow().permissions(), "privateMode").isEqualTo("rw-r-----");
			requireThat(LiveGitInventory.capture(selected), "selectedUnchanged").isEqualTo(expected);

			Path interrupted = root.resolve("interrupted");
			VerifiedCleanFork.ensureAttemptAt(fork, interrupted);
			Files.writeString(interrupted.resolve("prompt.md"), "staged\n");
			Files.move(interrupted.resolve("retired.md"), interrupted.resolve("renamed.md"));
			Files.delete(interrupted.resolve("removed.md"));
			git(interrupted, "add", "-A");
			VerifiedCleanFork.ensureAttemptAt(fork, interrupted, selected, expected);
			requireThat(LiveGitInventory.capture(interrupted).indexSha256(), "resumedIndex").
				isEqualTo(expected.indexSha256());
			requireThat(Files.readString(interrupted.resolve("prompt.md")), "resumedWorkingContent").
				isEqualTo("working\n");

			Path afterWorkingEdit = root.resolve("after-working-edit");
			VerifiedCleanFork.ensureAttemptAt(fork, afterWorkingEdit);
			Files.writeString(afterWorkingEdit.resolve("prompt.md"), "staged\n");
			Files.move(afterWorkingEdit.resolve("retired.md"), afterWorkingEdit.resolve("renamed.md"));
			Files.delete(afterWorkingEdit.resolve("removed.md"));
			git(afterWorkingEdit, "add", "-A");
			Files.writeString(afterWorkingEdit.resolve("prompt.md"), "working\n");
			VerifiedCleanFork.ensureAttemptAt(fork, afterWorkingEdit, selected, expected);
			requireThat(Files.readString(afterWorkingEdit.resolve("added/extra.md")),
				"resumedUntrackedContent").isEqualTo("untracked\n");

			Path afterPartialCopy = root.resolve("after-partial-copy");
			VerifiedCleanFork.ensureAttemptAt(fork, afterPartialCopy);
			Files.writeString(afterPartialCopy.resolve("prompt.md"), "staged\n");
			Files.move(afterPartialCopy.resolve("retired.md"), afterPartialCopy.resolve("renamed.md"));
			Files.delete(afterPartialCopy.resolve("removed.md"));
			git(afterPartialCopy, "add", "-A");
			Files.writeString(afterPartialCopy.resolve("prompt.md"), "working\n");
			Files.createDirectory(afterPartialCopy.resolve("added"));
			Files.writeString(afterPartialCopy.resolve("added/extra.md"), "untracked\n");
			Files.setPosixFilePermissions(afterPartialCopy.resolve("added/extra.md"),
				PosixFilePermissions.fromString("rwxrwxrwx"));
			Assert.expectThrows(IOException.class, () ->
				VerifiedCleanFork.ensureAttemptAt(fork, afterPartialCopy, selected, expected));
			requireThat(Files.getPosixFilePermissions(afterPartialCopy.resolve("added/extra.md")),
				"changedPrivateModePreserved").isEqualTo(PosixFilePermissions.fromString("rwxrwxrwx"));
			Files.setPosixFilePermissions(afterPartialCopy.resolve("added/extra.md"),
				Files.getPosixFilePermissions(selected.resolve("added/extra.md")));
			VerifiedCleanFork.ensureAttemptAt(fork, afterPartialCopy, selected, expected);
			requireThat(Files.readSymbolicLink(afterPartialCopy.resolve("added/alias.md")),
				"resumedUntrackedLink").isEqualTo(Path.of("extra.md"));

			Path outsideEdit = Files.writeString(privateWorktree.resolve("other.md"), "unowned\n");
			try
			{
				VerifiedCleanFork.ensureAttemptAt(fork, privateWorktree, selected, expected);
				Assert.fail("An extra private file must prevent replay");
			}
			catch (IOException expectedFailure)
			{
				requireThat(expectedFailure.getMessage(), "extraFileRejection").contains("unowned");
			}
			requireThat(Files.readString(outsideEdit), "unownedFilePreserved").isEqualTo("unowned\n");

			Files.delete(privateWorktree.resolve("prompt.md"));
			IOException incompleteReplay = Assert.expectThrows(IOException.class, () ->
				VerifiedCleanFork.ensureAttemptAt(fork, privateWorktree, selected, expected));
			requireThat(incompleteReplay.getMessage(), "incompleteReplayRejection").contains("unowned");
			requireThat(Files.readString(outsideEdit), "incompleteReplayFilePreserved").isEqualTo("unowned\n");
			requireThat(Files.exists(privateWorktree.resolve("prompt.md")), "missingPromptRemainsAbsent").
				isFalse();
		}
	}

	/**
	 * Runs a fixture Git operation in the selected repository.
	 *
	 * @param project Git worktree used by this test
	 * @param arguments Git arguments to run there
	 * @throws IOException if Git fails or the test thread is interrupted
	 */
	private static void git(Path project, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(project.toFile()).
			redirectErrorStream(true).start())
		{
			byte[] output = process.getInputStream().readAllBytes();
			if (process.waitFor() != 0)
				throw new IOException("Fixture Git failed: " + command + ": " +
					new String(output, StandardCharsets.UTF_8));
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Fixture Git was interrupted: " + command, e);
		}
	}
}
