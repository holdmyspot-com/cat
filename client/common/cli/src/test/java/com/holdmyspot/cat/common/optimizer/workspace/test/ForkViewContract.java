package com.holdmyspot.cat.common.optimizer.workspace.test;

import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.file;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Checks the same participant-selection and adoption outcomes for a real fork adapter and its in-memory test fake.
 * The caller supplies a project containing {@code prompt.md} with text {@code prompt} and {@code ignored.md} with
 * text {@code ignored}, and a current fork that records only {@code prompt.md}. These checks do not create Git
 * repositories or start processes. Passing them validates the fake's covered assumptions; its run is test-support
 * coverage, while the real adapter's run checks production behavior.
 */
public final class ForkViewContract
{
	/**
	 * The adapter under contract.
	 */
	private final Forks forks;
	/**
	 * The isolated project directory.
	 */
	private final Path base;
	/**
	 * The current fork's campaign storage directory.
	 */
	private final Path campaign;

	/**
	 * Creates checks for one caller-owned fixture.
	 *
	 * @param forks the adapter under contract
	 * @param base the project directory holding the contract files
	 * @param campaign the storage directory of the already-created fork
	 */
	public ForkViewContract(Forks forks, Path base, Path campaign)
	{
		this.forks = forks;
		this.base = base;
		this.campaign = campaign;
	}

	/**
	 * Verifies that deselection removes a participant from the inventory, worktree and reopened fork while retaining
	 * creation files, and that reselection restores the recorded content after the base file changes.
	 *
	 * @throws IOException if fixture files or fork storage cannot be used
	 */
	public void leavesOutDeselectedParticipants() throws IOException
	{
		ForkView fork = forks.open(base, campaign).selectParticipants(base, Set.of(Path.of("ignored.md")));

		ForkView narrowed = fork.selectParticipants(base, Set.of());

		requireThat(narrowed.inventory().entries().keySet(), "narrowed").doesNotContain(Path.of("ignored.md")).
			contains(Path.of("prompt.md"));
		requireThat(Files.exists(campaign.resolve("worktree/ignored.md")), "worktreeFile").isFalse();
		requireThat(forks.open(base, campaign).inventory(), "reopened").isEqualTo(narrowed.inventory());

		writeFile(base.resolve("ignored.md"), "changed", READ_WRITE);
		ForkView restored = narrowed.selectParticipants(base, Set.of(Path.of("ignored.md")));
		requireThat(restored.inventory().entries().get(Path.of("ignored.md")), "restored").
			isEqualTo(file("ignored"));
		requireThat(Files.readString(campaign.resolve("worktree/ignored.md"), UTF_8), "restoredWorktreeFile").
			isEqualTo("ignored");
	}

	/**
	 * Verifies that extension records a participant without changing the current fork or worktree, that inclusion uses
	 * recorded participants only, and that adoption preserves the earlier inventory as a readable version.
	 *
	 * @throws IOException if fixture files or fork storage cannot be used
	 */
	public void extensionBecomesCurrentWhenAdopted() throws IOException
	{
		FileInventory earlier = forks.open(base, campaign).inventory();

		ForkView extended = forks.open(base, campaign).extend(base, Set.of(Path.of("ignored.md")));

		requireThat(extended.inventory().entries().get(Path.of("ignored.md")), "ignored").
			isEqualTo(file("ignored"));
		requireThat(forks.open(base, campaign).inventory(), "current").isEqualTo(earlier);
		requireThat(Files.exists(campaign.resolve("worktree/ignored.md")), "worktreeFile").isFalse();
		writeFile(base.resolve("unrecorded.md"), "unrecorded", READ_WRITE);
		ForkView including = forks.open(base, campaign).including(Set.of(Path.of("ignored.md"),
			Path.of("unrecorded.md")));
		requireThat(including.inventory(), "including").isEqualTo(extended.inventory());

		extended.adopt();

		ForkView reopened = forks.open(base, campaign);
		requireThat(reopened.inventory(), "adopted").isEqualTo(extended.inventory());
		requireThat(reopened.version(earlier.identity()), "earlier").isEqualTo(Optional.of(earlier));
	}

	/**
	 * Verifies that selection records and materializes participants, preserves existing files, exposes the selected
	 * inventory when reopened, and refuses a missing participant by name.
	 *
	 * @throws IOException if fixture files or fork storage cannot be used
	 */
	public void addsParticipantsAfterCreation() throws IOException
	{
		Path external = writeFile(base.resolveSibling("external/shared.md"), "shared", READ_WRITE);
		ForkView fork = forks.open(base, campaign);

		ForkView extended = fork.selectParticipants(base, Set.of(Path.of("ignored.md"), Path.of("prompt.md"),
			external));

		FileInventory inventory = extended.inventory();
		requireThat(inventory.entries().get(Path.of("ignored.md")), "ignored").isEqualTo(file("ignored"));
		requireThat(inventory.entries().containsKey(external), "external").isTrue();
		requireThat(Files.readString(campaign.resolve("worktree/ignored.md"), UTF_8), "worktreeFile").
			isEqualTo("ignored");
		requireThat(extended.workspace().scanWorktree().entries(), "worktreeEntries").
			isEqualTo(inventory.entries());
		requireThat(forks.open(base, campaign).inventory(), "reopened").isEqualTo(inventory);
		requireThat(forks.exists(campaign), "exists").isTrue();
		requireThat(forks.exists(campaign.resolveSibling("c2")), "otherExists").isFalse();
		try
		{
			extended.selectParticipants(base, Set.of(Path.of("missing.md")));
			throw new AssertionError("A missing participant must be refused.");
		}
		catch (WorkspaceException e)
		{
			requireThat(e.getMessage(), "message").contains("missing.md");
		}
	}
}
