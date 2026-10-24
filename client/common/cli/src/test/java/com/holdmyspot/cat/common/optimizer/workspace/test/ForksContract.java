package com.holdmyspot.cat.common.optimizer.workspace.test;

import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.file;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;
import com.holdmyspot.cat.common.test.ProgramFixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks the same captured-file, selection, adoption, lifecycle, refusal and project-query outcomes for a real
 * fork adapter and its in-memory fake. The caller supplies an isolated project and campaign storage. Selection,
 * captured-file and lifecycle checks use an already-created current fork; creation-refusal checks use a campaign
 * directory with no fork. Project-query checks need no fork. Selection checks require {@code prompt.md} with text
 * prompt and {@code ignored.md} with text ignored, with only the prompt recorded at creation. Captured-file checks
 * require the same texts, the tracked executable fixture program and an external participant holding text shared.
 * The caller owns cleanup. These checks create no Git repository; operations use the supplied adapter, which may
 * invoke Git. Passing them validates the fake's covered assumptions; the fake run is test-support coverage and the
 * real run checks production behavior.
 */
public final class ForksContract
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
	 * @param campaign the fork storage directory; not yet populated for creation-refusal checks
	 */
	public ForksContract(Forks forks, Path base, Path campaign)
	{
		this.forks = forks;
		this.base = base;
		this.campaign = campaign;
	}

	/**
	 * Verifies a dirty project is refused with recovery advice before campaign storage is created.
	 *
	 * @throws IOException if creation fails for another reason
	 */
	public void refusesDirtyBase() throws IOException
	{
		try
		{
			forks.create(base, campaign, Set.of());
			throw new AssertionError("A dirty base must be refused");
		}
		catch (WorkspaceException e)
		{
			requireThat(e.getMessage(), "message").contains("commit, stash or remove");
		}
		requireThat(Files.exists(campaign), "campaignExists").isFalse();
	}

	/**
	 * Verifies the creation result reports whether it added the exclude, while a reopened view reports no addition.
	 *
	 * @param created the view returned by creation
	 * @param added whether this fixture's creation added the exclude
	 * @throws IOException if the recorded fork cannot be reopened
	 */
	public void reportsExcludeAdditionOnlyAtCreation(ForkView created, boolean added) throws IOException
	{
		requireThat(created.excludeAdded(), "excludeAdded").isEqualTo(added);
		requireThat(forks.open(base, campaign).excludeAdded(), "reopenedExcludeAdded").isFalse();
	}

	/**
	 * Verifies a project outside a Git repository reports no tracked symbolic links.
	 *
	 * @throws IOException if the adapter cannot inspect the project
	 */
	public void hasNoTrackedLinksOutsideRepository() throws IOException
	{
		requireThat(forks.trackedLinks(base), "unmanagedLinks").isEmpty();
	}

	/**
	 * Verifies that an empty selection produces an empty diff without requiring a Git repository.
	 *
	 * @throws IOException if the adapter cannot report the diff
	 */
	public void emptySelectionHasNoDiff() throws IOException
	{
		requireThat(forks.diff(base, List.of()), "diff").isEmpty();
	}

	/**
	 * Verifies that a created fork exists, reopens with its inventory and exposes that inventory through its worktree.
	 *
	 * @param created the view returned by creation
	 * @throws IOException if the fork or worktree cannot be read
	 */
	public void createdForkCanBeReopened(ForkView created) throws IOException
	{
		requireThat(forks.exists(campaign), "createdExists").isTrue();
		ForkView reopened = forks.open(base, campaign);
		requireThat(reopened.inventory(), "reopened").isEqualTo(created.inventory());
		requireThat(reopened.workspace().worktree(), "worktree").isEqualTo(campaign.resolve("worktree"));
		requireThat(reopened.workspace().scanWorktree().entries(), "worktreeEntries").
			isEqualTo(created.inventory().entries());
	}

	/**
	 * Verifies captured regular-file contents and modes, participant inclusion, absence of campaign files, reopened
	 * inventory and physical worktree files. The fork was created with the ignored and external participants.
	 *
	 * @param created the view that creation returned
	 * @param external the external participant, holding text {@code shared}
	 * @throws IOException if fixture files or recorded inventory cannot be read
	 */
	public void recordsTrackedAndParticipantFiles(ForkView created, Path external) throws IOException
	{
		createdForkCanBeReopened(created);
		FileInventory inventory = created.inventory();
		requireThat(inventory.root(), "root").isEqualTo(base);
		requireThat(inventory.entries().get(Path.of("prompt.md")), "prompt").isEqualTo(file("prompt"));
		requireThat(inventory.entries().get(Path.of(ProgramFixture.name("run"))), "executable").
			isEqualTo(file(ProgramFixture.TEXT, EXECUTABLE));
		requireThat(inventory.entries().get(Path.of("ignored.md")), "ignored").isEqualTo(file("ignored"));
		requireThat(inventory.entries().get(external), "external").isEqualTo(file("shared"));
		requireThat(inventory.entries().keySet().stream().filter(path -> path.startsWith(".cat")).toList(),
			"campaignFiles").isEmpty();
		Path worktree = campaign.resolve("worktree");
		requireThat(Files.readString(worktree.resolve("prompt.md"), UTF_8), "worktreePrompt").isEqualTo("prompt");
		requireThat(Files.readString(worktree.resolve("ignored.md"), UTF_8), "worktreeParticipant").isEqualTo("ignored");
		requireThat(Files.readString(worktree.resolve(ProgramFixture.name("run")), UTF_8), "worktreeProgram").
			isEqualTo(ProgramFixture.TEXT);
		requireThat(PortableFiles.mode(worktree.resolve(ProgramFixture.name("run"))), "worktreeProgramMode").
			isEqualTo(EXECUTABLE);
	}

	/**
	 * Verifies that a missing participant refuses creation before a worktree or fork record appears.
	 *
	 * @throws IOException if creation fails for another reason
	 */
	public void missingParticipantCreatesNoWorktree() throws IOException
	{
		try
		{
			forks.create(base, campaign, Set.of(Path.of("missing.md")));
			throw new AssertionError("A missing participant must stop the fork");
		}
		catch (WorkspaceException e)
		{
			requireThat(e.getMessage(), "message").contains("missing.md");
		}
		requireThat(Files.exists(campaign.resolve("worktree")), "worktreeExists").isFalse();
		requireThat(Files.exists(campaign.resolve("fork")), "forkExists").isFalse();
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
		requireThat(reopened.version(new FileInventory(base, Map.of()).identity()), "unknownVersion").isEmpty();
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
		requireThat(Files.readString(external, UTF_8), "externalContent").isEqualTo("shared");
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

	/**
	 * Verifies that removing a fork deletes its worktree, makes it absent and impossible to open, and can be repeated.
	 *
	 * @throws IOException if the worktree or its records cannot be removed
	 */
	public void removesExistingWorktree() throws IOException
	{
		requireThat(forks.exists(campaign), "existsBefore").isTrue();

		forks.remove(base, campaign);

		requireThat(Files.exists(campaign.resolve("worktree")), "worktreeExists").isFalse();
		requireThat(forks.exists(campaign), "existsAfter").isFalse();
		refusesOpening();
		forks.remove(base, campaign);
		requireThat(forks.exists(campaign), "existsAfterRepeatedRemoval").isFalse();
	}

	/**
	 * Verifies that a worktree removed outside the adapter makes the fork absent and impossible to open.
	 *
	 * @throws IOException if the worktree cannot be removed or its records cannot be read
	 */
	public void missingWorktreeIsNotAFork() throws IOException
	{
		requireThat(forks.exists(campaign), "existsBefore").isTrue();

		TemporaryDirectories.deleteRecursively(campaign.resolve("worktree"));

		requireThat(forks.exists(campaign), "existsAfter").isFalse();
		refusesOpening();
	}

	/**
	 * Verifies the public refusal when the campaign no longer holds a worktree.
	 *
	 * @throws IOException if reading the recorded fork fails for another reason
	 */
	private void refusesOpening() throws IOException
	{
		try
		{
			forks.open(base, campaign);
			throw new AssertionError("A campaign without a worktree must not open as a fork");
		}
		catch (WorkspaceException e)
		{
			requireThat(e.getMessage(), "message").contains(campaign.toString());
		}
	}
}
