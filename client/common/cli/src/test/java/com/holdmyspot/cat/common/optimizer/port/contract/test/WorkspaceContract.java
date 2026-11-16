package com.holdmyspot.cat.common.optimizer.port.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;
import com.holdmyspot.cat.common.optimizer.workspace.test.ContractWorkspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.json.JsonMapper;

/**
 * Shared checks for {@link WorkspacePort}, called by the fake and file-system adapter's test methods.
 */
public final class WorkspaceContract
{
	private final Subject subject;

	/**
	 * A workspace with owned inputs and observations of its directories.
	 */
	public interface Subject
	{
		/**
		 * Returns the workspace under test.
		 *
		 * @return the workspace under test
		 */
		WorkspacePort port();

		/**
		 * Returns the recorded fork with the files that the checks modify or retain.
		 *
		 * @return the recorded fork, with original, deleted and unchanged files
		 */
		FileInventory fork();

		/**
		 * Returns a candidate that edits the recorded target of the directory alias.
		 *
		 * @return the candidate with the edited target
		 */
		Overlay linkCandidate();

		/**
		 * Changes the live regular files that were recorded as link targets.
		 *
		 * @throws IOException if a live target cannot be changed
		 */
		void changeLiveLinkTargets() throws IOException;

		/**
		 * Indicates whether the candidate has a staged source.
		 *
		 * @param candidate the staged candidate
		 * @param directory the directory returned by staging
		 * @return whether the source exists
		 */
		boolean stagedExists(Sha256 candidate, Path directory);

		/**
		 * Indicates whether the authoring copy exists.
		 *
		 * @param copy an authoring copy
		 * @return whether the copy exists
		 */
		boolean copyExists(Path copy);

		/**
		 * Indicates whether the plugin copy exists for this subject.
		 *
		 * @param copy a plugin copy
		 * @return whether the copy exists
		 */
		boolean pluginCopyExists(Path copy);

		/**
		 * Reads a staged file, returning null if it does not exist.
		 *
		 * @param directory a staged source
		 * @param file the relative file
		 * @return its UTF-8 contents
		 * @throws IOException if it cannot be read
		 */
		String stagedContent(Path directory, Path file) throws IOException;

		/**
		 * Changes a staged source so reuse can be distinguished from rebuilding it.
		 *
		 * @param directory the staged source to change
		 * @throws IOException if it cannot be changed
		 */
		void changeStaged(Path directory) throws IOException;

		/**
		 * Changes the prompt in an authoring copy.
		 *
		 * @param copy the copy to edit
		 * @throws IOException if it cannot be edited
		 */
		void editCopy(Path copy) throws IOException;

		/**
		 * Supplies the effects of a session that edited its own copy of the project.
		 *
		 * @param copy the source that the session copied
		 * @return a distinct effects directory containing the prompt author's edit
		 * @throws IOException if it cannot be prepared
		 */
		Path promptAuthorEffects(Path copy) throws IOException;

		/**
		 * Returns an unchanged session effects tree copied from the authoring source.
		 *
		 * @param copy the source the session copies
		 * @return the effects directory
		 * @throws IOException if the effects cannot be prepared
		 */
		Path unchangedPromptAuthorEffects(Path copy) throws IOException;

		/**
		 * Changes the prompt in the worktree outside the workspace port.
		 *
		 * @throws IOException if the worktree cannot be changed
		 */
		void changeWorktree() throws IOException;

		/**
		 * Supplies explicit captured bytes to a fake workspace; real workspaces retain them in the recorded fork.
		 *
		 * @param file the recorded file
		 * @param content the captured bytes
		 */
		void supplyCapturedFile(Path file, FileContent content);

		/**
		 * Changes and deletes a retained file in the live project directory.
		 *
		 * @param file the retained file
		 * @throws IOException if the live file cannot be changed or deleted
		 */
		void changeAndDeleteProjectFile(Path file) throws IOException;

		/**
		 * Returns a plugin directory that holds a manifest and the script {@code bin/read-part}.
		 *
		 * @return the directory, which exists for the subject's lifetime
		 * @throws IOException if the plugin cannot be created
		 */
		Path pluginDirectory() throws IOException;
	}

	/**
	 * Creates checks for an implementation and its owned inputs.
	 *
	 * @param subject the implementation and its owned inputs
	 */
	public WorkspaceContract(Subject subject)
	{
		requireThat(subject, "subject").isNotNull();
		this.subject = subject;
	}

	/**
	 * Checks that staging preserves writes, creations, deletions and untouched files.
	 *
	 * @throws IOException if staging fails
	 */
	public void stagesCandidateBytes() throws IOException
	{
		Path staged = subject.port().stage(candidate(), Set.of());
		requireThat(subject.stagedContent(staged, Path.of("prompt.md")), "edited").isEqualTo("edited");
		requireThat(subject.stagedContent(staged, Path.of("new.md")), "created").isEqualTo("created");
		requireThat(subject.stagedContent(staged, Path.of("keep.md")), "untouched").isEqualTo("keep");
		requireThat(subject.stagedContent(staged, Path.of("deleted.md")), "deleted").isNull();
	}

	/**
	 * Checks that reads retain supplied or recorded bytes after the live project file changes and is deleted, and that
	 * an overlay write takes precedence.
	 *
	 * @throws IOException if reading the captured file fails
	 */
	public void readsCapturedFilesAfterProjectChanges() throws IOException
	{
		Path file = Path.of("keep.md");
		Overlay candidate = Overlay.of(subject.fork());
		subject.supplyCapturedFile(file, new FileContent("keep".getBytes(UTF_8)));
		subject.changeAndDeleteProjectFile(file);
		WorkspacePort port = subject.port();
		requireThat(new String(port.readFile(candidate, file).orElseThrow().bytes(), UTF_8), "capturedFile").
			isEqualTo("keep");

		RegularFile recorded = (RegularFile) candidate.snapshot().entries().get(file);
		Overlay edited = candidate.write(file, new FileContent("overlay write".getBytes(UTF_8)),
			recorded.permissions());
		requireThat(new String(port.readFile(edited, file).orElseThrow().bytes(), UTF_8), "overlayFile").
			isEqualTo("overlay write");
	}

	/**
	 * Checks that reconnecting an intact worktree preserves its externally changed inventory.
	 *
	 * @throws IOException if the worktree cannot be changed, scanned or reconnected
	 */
	public void reconnectPreservesIntactWorktree() throws IOException
	{
		WorkspacePort port = subject.port();
		subject.changeWorktree();
		FileInventory changed = port.scanWorktree();
		Overlay differentCandidate = candidate();
		requireThat(differentCandidate.snapshot(), "differentCandidate").isNotEqualTo(subject.fork());
		port.reconnectWorktree(differentCandidate);
		requireThat(port.scanWorktree(), "worktreeAfterReconnect").isEqualTo(changed);
	}

	/**
	 * Checks that staged aliases use candidate bytes at their destinations and become regular files.
	 *
	 * @throws IOException if staging fails
	 */
	public void stagesRecordedLinksWithCandidateBytes() throws IOException
	{
		Overlay candidate = subject.linkCandidate();
		subject.changeLiveLinkTargets();
		WorkspacePort port = subject.port();
		Path staged = port.stage(candidate, Set.of());
		requireStagedRegularFile(staged.resolve(ContractWorkspace.FILE_ALIAS));
		requireStagedRegularFile(staged.resolve(ContractWorkspace.CHAINED_ALIAS));
		requireStagedRegularFile(staged.resolve(ContractWorkspace.DIRECTORY_ALIAS).
			resolve(ContractWorkspace.DIRECTORY_TARGET.getFileName()));
		requireThat(subject.stagedContent(staged, ContractWorkspace.FILE_ALIAS), "fileAlias").
			isEqualTo("recorded source");
		requireThat(subject.stagedContent(staged, ContractWorkspace.CHAINED_ALIAS), "chainedAlias").
			isEqualTo("recorded source");
		requireThat(subject.stagedContent(staged, ContractWorkspace.DIRECTORY_ALIAS.
			resolve(ContractWorkspace.DIRECTORY_TARGET.getFileName())), "directoryAliasTarget").
			isEqualTo("candidate directory target");
		requireThat(Files.getPosixFilePermissions(staged.resolve(ContractWorkspace.FILE_ALIAS),
			LinkOption.NOFOLLOW_LINKS), "fileAliasMode").isEqualTo(
			PosixFilePermissions.fromString("rw-r--r--"));

		Path selected = port.stage(candidate, Set.of(),
			new SourceSelection(Set.of(ContractWorkspace.FILE_ALIAS, ContractWorkspace.CHAINED_ALIAS,
				ContractWorkspace.DIRECTORY_ALIAS), Set.of(), List.of()));
		requireStagedRegularFile(selected.resolve(ContractWorkspace.FILE_ALIAS));
		requireStagedRegularFile(selected.resolve(ContractWorkspace.CHAINED_ALIAS));
		requireStagedRegularFile(selected.resolve(ContractWorkspace.DIRECTORY_ALIAS).
			resolve(ContractWorkspace.DIRECTORY_TARGET.getFileName()));
		requireThat(subject.stagedContent(selected, ContractWorkspace.FILE_ALIAS), "selectedAlias").
			isEqualTo("recorded source");
		requireThat(subject.stagedContent(selected, ContractWorkspace.CHAINED_ALIAS), "selectedChainedAlias").
			isEqualTo("recorded source");
		requireThat(subject.stagedContent(selected, ContractWorkspace.DIRECTORY_ALIAS.
			resolve(ContractWorkspace.DIRECTORY_TARGET.getFileName())), "selectedDirectoryAliasTarget").
			isEqualTo("candidate directory target");
		requireThat(subject.stagedContent(selected, ContractWorkspace.LINK_SOURCE), "unselectedTarget").isNull();
	}

	/**
	 * Checks that cyclic links fail each staging attempt before a source can be reused.
	 *
	 * @throws IOException if staging fails for a reason other than the cycle
	 */
	public void rejectsCyclicLinksWithoutCachingSource() throws IOException
	{
		requireRepeatedLinkRefusal(ContractWorkspace.CYCLE_ONE, "link cycle", Set.of());
	}

	/**
	 * Checks that an excluded missing link fails each staging attempt before a source can be reused.
	 *
	 * @throws IOException if staging fails for a reason other than the missing target
	 */
	public void rejectsMissingLinksWithoutCachingSource() throws IOException
	{
		requireRepeatedLinkRefusal(ContractWorkspace.MISSING_ALIAS, "missing target",
			Set.of(ContractWorkspace.MISSING_ALIAS));
	}

	/**
	 * Checks that an unresolved link keeps failing rather than returning a source from an earlier attempt.
	 *
	 * @param link the recorded link that must be refused
	 * @param reason the reason that must appear in the refusal
	 * @param excluded the paths excluded from staging
	 * @throws IOException if staging fails for a different reason
	 */
	private void requireRepeatedLinkRefusal(Path link, String reason, Set<Path> excluded) throws IOException
	{
		Overlay candidate = Overlay.of(subject.fork());
		WorkspacePort port = subject.port();
		try
		{
			port.stage(candidate, excluded);
			throw new AssertionError("Staging accepted " + link);
		}
		catch (WorkspaceException e)
		{
			requireThat(e.getMessage(), "failureMessage").contains(link.toString()).contains(reason);
		}
		port.stage(candidate, excluded);
	}

	/**
	 * Checks that staging writes a regular file at a destination without retaining a symbolic link.
	 *
	 * @param file the staged destination
	 * @throws IOException if the destination cannot be inspected
	 */
	private static void requireStagedRegularFile(Path file) throws IOException
	{
		requireThat(Files.isSymbolicLink(file), "symbolicLink").isFalse();
		requireThat(file, "file").isRegularFile();
	}

	/**
	 * Checks that a staged source with a selection holds only the selected files, apart from the whole source, and
	 * is deleted with the candidate's other sources.
	 *
	 * @throws IOException if staging fails
	 */
	public void stagesSelectedFiles() throws IOException
	{
		Overlay candidate = candidate();
		WorkspacePort port = subject.port();
		Path whole = port.stage(candidate, Set.of());
		Path selected = port.stage(candidate, Set.of(),
			new SourceSelection(Set.of(Path.of("prompt.md")), Set.of(), List.of("new*")));
		requireThat(selected, "selected").isNotEqualTo(whole);
		requireThat(subject.stagedContent(selected, Path.of("prompt.md")), "edited").isEqualTo("edited");
		requireThat(subject.stagedContent(selected, Path.of("new.md")), "created").isEqualTo("created");
		requireThat(subject.stagedContent(selected, Path.of("keep.md")), "unselected").isNull();
		port.deleteStaged(candidate.identity());
		requireThat(subject.stagedExists(candidate.identity(), selected), "selectedAfterDeletion").isFalse();
	}

	/**
	 * Checks that excluded paths produce independent staged sources with and without an explicit selection.
	 *
	 * @throws IOException if staging fails
	 */
	public void stagesExcludedFiles() throws IOException
	{
		Overlay candidate = candidate();
		WorkspacePort port = subject.port();
		Set<Path> exclusions = new LinkedHashSet<>(List.of(Path.of("keep.md"), Path.of("deleted.md")));
		Set<Path> reversedExclusions = new LinkedHashSet<>(List.of(Path.of("deleted.md"), Path.of("keep.md")));
		Path whole = port.stage(candidate, Set.of());
		Path filtered = port.stage(candidate, exclusions);
		requireThat(filtered, "filtered").isNotEqualTo(whole);
		requireThat(subject.stagedContent(whole, Path.of("prompt.md")), "wholePrompt").isEqualTo("edited");
		requireThat(subject.stagedContent(filtered, Path.of("prompt.md")), "filteredPrompt").isEqualTo("edited");
		requireThat(subject.stagedContent(whole, Path.of("keep.md")), "wholeKeep").isEqualTo("keep");
		requireThat(subject.stagedContent(filtered, Path.of("keep.md")), "filteredKeep").isNull();
		requireThat(port.stage(candidate, reversedExclusions), "sameFilteredSource").isEqualTo(filtered);

		SourceSelection selection = new SourceSelection(
			Set.of(Path.of("prompt.md"), Path.of("keep.md")), Set.of(), List.of());
		Path selectedWhole = port.stage(candidate, Set.of(), selection);
		Path selectedFiltered = port.stage(candidate, exclusions, selection);
		requireThat(selectedWhole, "selectedWhole").isNotEqualTo(selectedFiltered);
		requireThat(subject.stagedContent(selectedWhole, Path.of("prompt.md")), "selectedWholePrompt").
			isEqualTo("edited");
		requireThat(subject.stagedContent(selectedFiltered, Path.of("prompt.md")), "selectedFilteredPrompt").
			isEqualTo("edited");
		requireThat(subject.stagedContent(selectedWhole, Path.of("keep.md")), "selectedWholeKeep").isEqualTo("keep");
		requireThat(subject.stagedContent(selectedFiltered, Path.of("keep.md")), "selectedFilteredKeep").isNull();
		requireThat(port.stage(candidate, reversedExclusions, selection), "sameSelectedFilteredSource").
			isEqualTo(selectedFiltered);

		port.deleteStaged(candidate.identity());
		requireThat(subject.stagedExists(candidate.identity(), whole), "wholeAfterDeletion").isFalse();
		requireThat(subject.stagedExists(candidate.identity(), filtered), "filteredAfterDeletion").isFalse();
		requireThat(subject.stagedExists(candidate.identity(), selectedWhole), "selectedWholeAfterDeletion").isFalse();
		requireThat(subject.stagedExists(candidate.identity(), selectedFiltered), "selectedFilteredAfterDeletion").
			isFalse();
	}

	/**
	 * Checks that deleting one candidate removes all its staged variants and preserves another candidate's source.
	 *
	 * @throws IOException if a workspace operation fails
	 */
	public void deletesVariantsKeepsOtherCandidates() throws IOException
	{
		Overlay candidate = candidate();
		WorkspacePort port = subject.port();
		Set<Path> exclusions = new LinkedHashSet<>(List.of(Path.of("keep.md"), Path.of("deleted.md")));
		Set<Path> reversedExclusions = new LinkedHashSet<>(List.of(Path.of("deleted.md"), Path.of("keep.md")));
		SourceSelection selection = new SourceSelection(
			Set.of(Path.of("prompt.md"), Path.of("keep.md")), Set.of(), List.of());
		Path whole = port.stage(candidate, Set.of());
		Path excluded = port.stage(candidate, exclusions);
		Path selected = port.stage(candidate, Set.of(), selection);
		Path selectedExcluded = port.stage(candidate, exclusions, selection);
		requireThat(excluded, "excluded").isNotEqualTo(whole);
		requireThat(selected, "selected").isNotEqualTo(whole).isNotEqualTo(excluded);
		requireThat(selectedExcluded, "selectedExcluded").isNotEqualTo(whole).isNotEqualTo(excluded).
			isNotEqualTo(selected);
		requireThat(subject.stagedExists(candidate.identity(), whole), "wholeExists").isTrue();
		requireThat(subject.stagedExists(candidate.identity(), excluded), "excludedExists").isTrue();
		requireThat(subject.stagedExists(candidate.identity(), selected), "selectedExists").isTrue();
		requireThat(subject.stagedExists(candidate.identity(), selectedExcluded), "selectedExcludedExists").isTrue();
		requireThat(port.stage(candidate, reversedExclusions), "sameExcluded").isEqualTo(excluded);
		requireThat(port.stage(candidate, reversedExclusions, selection), "sameSelectedExcluded").
			isEqualTo(selectedExcluded);

		Overlay otherCandidate = candidate.write(Path.of("prompt.md"), new FileContent("other candidate".getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--"));
		Path otherDirectory = port.stage(otherCandidate, Set.of());
		requireThat(otherCandidate.identity(), "otherIdentity").isNotEqualTo(candidate.identity());
		requireThat(subject.stagedExists(otherCandidate.identity(), otherDirectory), "otherExists").isTrue();

		port.deleteStaged(candidate.identity());
		requireThat(subject.stagedExists(candidate.identity(), whole), "wholeAfterDeletion").isFalse();
		requireThat(subject.stagedExists(candidate.identity(), excluded), "excludedAfterDeletion").isFalse();
		requireThat(subject.stagedExists(candidate.identity(), selected), "selectedAfterDeletion").isFalse();
		requireThat(subject.stagedExists(candidate.identity(), selectedExcluded), "selectedExcludedAfterDeletion").
			isFalse();
		requireThat(subject.stagedExists(otherCandidate.identity(), otherDirectory), "otherAfterDeletion").isTrue();
		port.deleteStaged(candidate.identity());
		requireThat(subject.stagedExists(otherCandidate.identity(), otherDirectory), "otherAfterRepeatedDeletion").
			isTrue();
		requireThat(port.stage(candidate, Set.of()), "rebuiltWhole").isEqualTo(whole);
		requireThat(subject.stagedExists(candidate.identity(), whole), "rebuiltWholeExists").isTrue();
	}

	/**
	 * Checks that repeated staging reuses the source until it is deleted.
	 *
	 * @throws IOException if staging fails
	 */
	public void reusesStagedSource() throws IOException
	{
		Overlay candidate = candidate();
		Path first = subject.port().stage(candidate, Set.of());
		subject.changeStaged(first);
		requireThat(subject.port().stage(candidate, Set.of()), "reusedSource").isEqualTo(first);
		requireThat(subject.stagedContent(first, Path.of("prompt.md")), "cachedContent").isEqualTo("cached");
	}

	/**
	 * Checks deletion, repeated deletion, and rebuilding the deleted source.
	 *
	 * @throws IOException if a workspace operation fails
	 */
	public void deletesAndRebuildsStagedSource() throws IOException
	{
		Overlay candidate = candidate();
		WorkspacePort port = subject.port();
		Path directory = port.stage(candidate, Set.of());
		requireThat(subject.stagedExists(candidate.identity(), directory), "stagedBeforeDeletion").isTrue();
		port.deleteStaged(candidate.identity());
		requireThat(subject.stagedExists(candidate.identity(), directory), "stagedAfterDeletion").isFalse();
		port.deleteStaged(candidate.identity());
		requireThat(port.stage(candidate, Set.of()), "rebuiltSource").isEqualTo(directory);
		requireThat(subject.stagedExists(candidate.identity(), directory), "rebuiltExists").isTrue();
	}

	/**
	 * Checks that each copy is independent and scans the working candidate under its own root.
	 *
	 * @throws IOException if a workspace operation fails
	 */
	public void preparesIndependentAuthoringCopies() throws IOException
	{
		Overlay candidate = candidate();
		WorkspacePort port = subject.port();
		Path first = port.prepareAuthoringCopy(candidate);
		Path second = port.prepareAuthoringCopy(candidate);
		requireThat(first, "firstCopy").isNotEqualTo(second);
		requireThat(subject.copyExists(first), "firstExists").isTrue();
		requireThat(port.scanAuthoringCopy(first), "firstInventory").
			isEqualTo(new FileInventory(first.toAbsolutePath().normalize(), candidate.snapshot().entries()));
		subject.editCopy(first);
		requireThat(port.scanAuthoringCopy(first).entries().get(Path.of("prompt.md")), "promptAuthorEdit").
			isEqualTo(file("copy edit"));
		requireThat(port.scanAuthoringCopy(second).entries(), "secondInventory").
			isEqualTo(candidate.snapshot().entries());
	}

	/**
	 * Checks that session effects are scanned separately from their unchanged source copy.
	 *
	 * @throws IOException if a workspace operation fails
	 */
	public void scansIndependentPromptAuthorEffects() throws IOException
	{
		Overlay candidate = candidate();
		WorkspacePort port = subject.port();
		Path copy = port.prepareAuthoringCopy(candidate);
		Path effects = subject.promptAuthorEffects(copy);
		FileInventory result = port.scanPromptAuthorEffects(copy, effects);
		requireThat(result.root(), "effectsRoot").isEqualTo(effects.toAbsolutePath().normalize());
		requireThat(result.entries().get(Path.of("prompt.md")), "effectsEdit").isEqualTo(file("effects edit"));
			requireThat(port.scanAuthoringCopy(copy).entries(), "sourceCopy").isEqualTo(candidate.snapshot().entries());
	}

	/**
	 * Checks that a session making no edits has the candidate's inventory under its own effects root.
	 *
	 * @throws IOException if the copy or effects cannot be prepared or scanned
	 */
	public void scansUnchangedPromptAuthorEffects() throws IOException
	{
		Overlay candidate = candidate();
		WorkspacePort port = subject.port();
		Path copy = port.prepareAuthoringCopy(candidate);
		Path effects = subject.unchangedPromptAuthorEffects(copy);
		requireThat(port.scanPromptAuthorEffects(copy, effects), "unchangedEffects").
			isEqualTo(new FileInventory(effects.toAbsolutePath().normalize(), candidate.snapshot().entries()));
	}

	/**
	 * Checks that deleting a copy leaves another copy available and permits repeated deletion.
	 *
	 * @throws IOException if a workspace operation fails
	 */
	public void deletesOnlySelectedAuthoringCopy() throws IOException
	{
		WorkspacePort port = subject.port();
		Path first = port.prepareAuthoringCopy(candidate());
		Path second = port.prepareAuthoringCopy(candidate());
		port.deleteAuthoringCopy(first);
		requireThat(subject.copyExists(first), "deletedCopy").isFalse();
		requireThat(subject.copyExists(second), "retainedCopy").isTrue();
		port.deleteAuthoringCopy(first);
		requireThat(port.scanAuthoringCopy(second).entries(), "retainedInventory").
			isEqualTo(candidate().snapshot().entries());
	}

	/**
	 * Checks that a scan observes a change made outside the workspace port.
	 *
	 * @throws IOException if the worktree cannot be scanned
	 */
	public void scansWorktreeChanges() throws IOException
	{
		requireThat(subject.port().scanWorktree().entries(), "initialWorktree").isEqualTo(subject.fork().entries());
		subject.changeWorktree();
		requireThat(subject.port().scanWorktree().entries().get(Path.of("prompt.md")), "unownedChange").
			isEqualTo(file("outside edit"));
	}

	/**
	 * Checks materialization of writes, creations, deletions and restoration of the original fork.
	 *
	 * @throws IOException if materialization fails
	 */
	public void materializesCandidateAndRestoresFork() throws IOException
	{
		WorkspacePort port = subject.port();
		Overlay candidate = candidate();
		port.materialize(candidate);
		requireThat(port.scanWorktree().entries(), "candidateWorktree").isEqualTo(candidate.snapshot().entries());
		port.materialize(Overlay.of(subject.fork()));
		requireThat(port.scanWorktree().entries(), "restoredWorktree").isEqualTo(subject.fork().entries());
	}

	/**
	 * Creates an overlay that modifies, creates and deletes files.
	 *
	 * @return an overlay that modifies, creates and deletes files
	 */
	private Overlay candidate()
	{
		return Overlay.of(subject.fork()).write(Path.of("prompt.md"), new FileContent("edited".getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--")).
			write(Path.of("new.md"), new FileContent("created".getBytes(UTF_8)),
				PosixFilePermissions.fromString("rw-r--r--")).delete(Path.of("deleted.md"));
	}

	/**
	 * Creates a regular-file inventory entry from its content.
	 *
	 * @param content UTF-8 content
	 * @return the inventory entry for a regular file
	 */
	private static RegularFile file(String content)
	{
		return new RegularFile(Sha256.of(content.getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--"));
	}

	/**
	 * Checks that a saved candidate's file is read as the candidate holds it: written and created files as written,
	 * untouched files as recorded, and deleted or absent files as empty.
	 *
	 * @throws IOException if a file cannot be read
	 */
	public void readsFilesOfASavedCandidate() throws IOException
	{
		WorkspacePort port = subject.port();
		Overlay candidate = candidate();

		requireThat(read(port, candidate, "prompt.md"), "written").isEqualTo(Optional.of("edited"));
		requireThat(read(port, candidate, "new.md"), "created").isEqualTo(Optional.of("created"));
		requireThat(read(port, candidate, "keep.md"), "untouched").isEqualTo(Optional.of("keep"));
		requireThat(read(port, candidate, "deleted.md"), "deleted").isEqualTo(Optional.empty());
		requireThat(read(port, candidate, "absent.md"), "absent").isEqualTo(Optional.empty());
	}

	/**
	 * Reads a file of a saved candidate as UTF-8 text.
	 *
	 * @param port      the workspace
	 * @param candidate the candidate
	 * @param file      the inventory path
	 * @return the text, or empty if the candidate holds no regular file at the path
	 * @throws IOException if the file cannot be read
	 */
	private static Optional<String> read(WorkspacePort port, Overlay candidate, String file) throws IOException
	{
		Optional<FileContent> content = port.readFile(candidate, Path.of(file));
		if (content.isEmpty())
			return Optional.empty();
		return Optional.of(new String(content.get().bytes(), UTF_8));
	}

	/**
	 * Checks that a plugin directory is copied into a base named by the digest of its files, once per set of files,
	 * and that a change to the directory makes a base of its own.
	 *
	 * @throws IOException if a copy fails
	 */
	public void copiesAPluginBaseByTheDigestOfItsFiles() throws IOException
	{
		WorkspacePort port = subject.port();
		Path directory = subject.pluginDirectory();

		String digest = port.copyPluginBase(directory);

		requireThat(digest, "digest").isNotBlank();
		Path base = port.pluginBase(digest);
		requireThat(base, "base").isAbsolute();
		requireThat(Files.readString(base.resolve("bin/read-part"), UTF_8), "copiedScript").
			isEqualTo(Files.readString(directory.resolve("bin/read-part"), UTF_8));
		requireThat(port.copyPluginBase(directory), "repeatedDigest").isEqualTo(digest);
		Files.writeString(directory.resolve("bin/read-part"), "#!/bin/sh\necho changed\n", UTF_8);
		String changed = port.copyPluginBase(directory);
		requireThat(changed, "changedDigest").isNotEqualTo(digest);
		requireThat(port.pluginBase(changed), "changedBase").isNotEqualTo(base);
		requireThat(Files.readString(base.resolve("bin/read-part"), UTF_8), "firstBase").isEqualTo("#!/bin/sh\n");
	}

	/**
	 * Checks that a plugin that the project does not build is installed from its base, and that a plugin that the
	 * project builds has a copy of its own for each staged source and each set of removals, which deleting the
	 * candidate removes.
	 *
	 * @throws IOException if a copy fails
	 */
	public void copiesAPluginPerStagedSourceAndRemovals() throws IOException
	{
		WorkspacePort port = subject.port();
		Path pluginDirectory = subject.pluginDirectory();
		String digest = port.copyPluginBase(pluginDirectory);
		TrialPlugin installed = new TrialPlugin("tools", digest, new TrialPlugin.Installed(), List.of(), null);
		TrialPlugin built = new TrialPlugin("cat", digest, new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(), null);
		Overlay candidate = candidate();
		Path source = port.stage(candidate, Set.of());
		Path otherSource = port.stage(Overlay.of(subject.fork()), Set.of());
		PluginRemovals removals = PluginRemovals.of(List.of(new Dependent(Dependent.Location.PLUGIN, "cat",
			Path.of("bin/read-part"), new Dependent.WholeFile(), "Reads the part that the rule names.")),
			JsonMapper.builder().build());
		PluginRemovals otherRemovals = PluginRemovals.of(List.of(new Dependent(Dependent.Location.PLUGIN, "cat",
			Path.of("bin/another-read-part"), new Dependent.WholeFile(), "Reads the other part.")),
			JsonMapper.builder().build());

		requireThat(port.pluginCopy(installed, source, PluginRemovals.NONE), "installedCopy").
			isEqualTo(port.pluginBase(digest));
		Path copy = port.pluginCopy(built, source, PluginRemovals.NONE);
		requireThat(copy, "builtCopy").isNotEqualTo(port.pluginBase(digest));
		requireThat(port.pluginCopy(built, source, PluginRemovals.NONE), "repeatedCopy").isEqualTo(copy);
		requireThat(port.pluginCopy(built, otherSource, PluginRemovals.NONE), "otherSourceCopy").isNotEqualTo(copy);
		Path withoutDependents = port.pluginCopy(built, source, removals);
		requireThat(withoutDependents, "copyWithRemovals").isNotEqualTo(copy);
		requireThat(port.pluginCopy(built, source, removals), "repeatedCopyWithRemovals").
			isEqualTo(withoutDependents);
		Path withOtherRemovals = port.pluginCopy(built, source, otherRemovals);
		requireThat(withOtherRemovals, "copyWithOtherRemovals").isNotEqualTo(withoutDependents);
		requireThat(subject.pluginCopyExists(copy), "copyExistsBeforeDeletion").isTrue();
		requireThat(subject.pluginCopyExists(withoutDependents), "copyWithRemovalsExistsBeforeDeletion").isTrue();
		requireThat(subject.pluginCopyExists(withOtherRemovals), "copyWithOtherRemovalsExistsBeforeDeletion").isTrue();
		Path otherCandidateCopy = port.pluginCopy(built, otherSource, PluginRemovals.NONE);
		requireThat(subject.pluginCopyExists(otherCandidateCopy), "otherCandidateCopyExistsBeforeDeletion").isTrue();
		port.deleteStaged(candidate.identity());
		requireThat(subject.pluginCopyExists(copy), "copyAfterDeletion").isFalse();
		requireThat(subject.pluginCopyExists(withoutDependents), "copyWithRemovalsAfterDeletion").isFalse();
		requireThat(subject.pluginCopyExists(withOtherRemovals), "copyWithOtherRemovalsAfterDeletion").isFalse();
		requireThat(subject.pluginCopyExists(otherCandidateCopy), "otherCandidateCopyAfterDeletion").isTrue();
		requireThat(Files.isDirectory(port.pluginBase(digest)), "copiedBaseAfterDeletion").isTrue();
	}

	/**
	 * Checks that mappings replace base files, leave unmapped and missing-source files intact, and preserve the stored
	 * base.
	 *
	 * @throws IOException if a plugin copy cannot be made or read
	 */
	public void copiesMappedStagedFilesIntoPlugins() throws IOException
	{
		WorkspacePort port = subject.port();
		String digest = port.copyPluginBase(subject.pluginDirectory());
		Path source = pluginSource();
		writePluginInput(source, Path.of("mapped/read-part"), "mapped read part");
		TrialPlugin plugin = builtPlugin(digest, List.of(
			new TrialPlugin.Mapping(Path.of("mapped/read-part"), Path.of("bin/read-part")),
			new TrialPlugin.Mapping(Path.of("missing-source"), Path.of("bin/another-read-part"))));

		Path copy = port.pluginCopy(plugin, source, PluginRemovals.NONE);

		requireThat(Files.readString(copy.resolve("bin/read-part"), UTF_8), "mappedFile").
			isEqualTo("mapped read part");
		requireThat(Files.readString(copy.resolve("bin/another-read-part"), UTF_8), "missingMappedFile").
			isEqualTo("#!/bin/sh\n");
		requireThat(Files.readString(copy.resolve("bin/unmapped"), UTF_8), "unmappedBaseFile").
			isEqualTo("unmapped base");
		requireThat(Files.readString(port.pluginBase(digest).resolve("bin/read-part"), UTF_8), "storedBaseFile").
			isEqualTo("#!/bin/sh\n");
	}

	/**
	 * Checks that removals apply to mapped files while unrelated base content and hook commands remain.
	 *
	 * @throws IOException if a plugin copy cannot be made or read
	 */
	public void appliesPluginRemovalsAfterMappings() throws IOException
	{
		WorkspacePort port = subject.port();
		String digest = port.copyPluginBase(subject.pluginDirectory());
		Path source = pluginSource();
		writePluginInput(source, Path.of("mapped/read-part"), "before remove-span after");
		writePluginInput(source, Path.of("mapped/removed"), "remove whole file");
		writePluginInput(source, Path.of("mapped/hooks.json"), """
			{"hooks":{"PostToolUse":[{"hooks":[{"command":"remove-command"},
			{"command":"keep-command"}]}]}}
			""");
		TrialPlugin plugin = builtPlugin(digest, List.of(
			new TrialPlugin.Mapping(Path.of("mapped/read-part"), Path.of("bin/read-part")),
			new TrialPlugin.Mapping(Path.of("mapped/removed"), Path.of("bin/removed")),
			new TrialPlugin.Mapping(Path.of("mapped/hooks.json"), Path.of("hooks/config.json"))));
		PluginRemovals removals = PluginRemovals.of(List.of(
			new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.Span("remove-span"),
				"Removes the mapped span."),
			new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/removed"), new Dependent.WholeFile(),
				"Removes the mapped file."),
			new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("hooks/config.json"),
				new Dependent.HookRegistration("PostToolUse", "remove-command"), "Removes the mapped hook.")),
			JsonMapper.builder().build());

		Path copy = port.pluginCopy(plugin, source, removals);

		requireThat(Files.readString(copy.resolve("bin/read-part"), UTF_8), "spanAfterMapping").
			isEqualTo("before  after");
		requireThat(Files.exists(copy.resolve("bin/removed")), "wholeFileAfterMapping").isFalse();
		requireThat(Files.readString(copy.resolve("bin/unmapped"), UTF_8), "unrelatedBaseFile").
			isEqualTo("unmapped base");
		String hooks = Files.readString(copy.resolve("hooks/config.json"), UTF_8);
		requireThat(hooks, "retainedHook").contains("keep-command").doesNotContain("remove-command");
	}

	/**
	 * Checks that an installed plugin cannot be returned after its copied base is removed.
	 *
	 * @throws IOException if a plugin base cannot be copied or the refusal check fails
	 */
	public void rejectsInstalledPluginWithoutBase() throws IOException
	{
		WorkspacePort port = subject.port();
		String digest = port.copyPluginBase(subject.pluginDirectory());
		TemporaryDirectories.deleteRecursively(port.pluginBase(digest));
		Path source = pluginSource();
		TrialPlugin plugin = new TrialPlugin("tools", digest, new TrialPlugin.Installed(), List.of(), null);

		requirePluginCopyRefusal(plugin, source, PluginRemovals.NONE, "trial plugin tools");
	}

	/**
	 * Checks that a project-built plugin cannot be returned after its copied base is removed.
	 *
	 * @throws IOException if a plugin base cannot be copied or the refusal check fails
	 */
	public void rejectsBuiltPluginWithoutBase() throws IOException
	{
		WorkspacePort port = subject.port();
		String digest = port.copyPluginBase(subject.pluginDirectory());
		TemporaryDirectories.deleteRecursively(port.pluginBase(digest));
		Path source = pluginSource();
		TrialPlugin plugin = builtPlugin(digest, List.of());

		requirePluginCopyRefusal(plugin, source, PluginRemovals.NONE, "trial plugin cat");
	}

	/**
	 * Checks that a built plugin copy refuses removals for a dependent absent from its base and mappings.
	 *
	 * @throws IOException if a plugin copy cannot be made or the refusal check fails
	 */
	public void rejectsMissingPluginDependent() throws IOException
	{
		WorkspacePort port = subject.port();
		String digest = port.copyPluginBase(subject.pluginDirectory());
		Path source = pluginSource();
		TrialPlugin plugin = builtPlugin(digest, List.of());
		PluginRemovals removals = PluginRemovals.of(List.of(new Dependent(Dependent.Location.PLUGIN, "cat",
			Path.of("absent-dependent"), new Dependent.WholeFile(), "The dependent is absent.")),
			JsonMapper.builder().build());

		requirePluginCopyRefusal(plugin, source, removals, "absent-dependent");
	}

	/**
	 * Checks that installed, uncached built and cached built plugins reject a stored base whose files changed.
	 *
	 * @throws IOException if a base cannot be copied or scanned
	 */
	public void rejectsChangedPluginBase() throws IOException
	{
		Path source = pluginSource();
		requireChangedPluginBaseRefusal("installed", false, false, source);
		requireChangedPluginBaseRefusal("uncached-built", true, false, source);
		requireChangedPluginBaseRefusal("cached-built", true, true, source);
	}

	/**
	 * Changes a stored base and requires the workspace to refuse the corresponding plugin copy.
	 *
	 * @param caseName identifies the kind of plugin copy
	 * @param built whether the project builds the plugin
	 * @param cached whether to establish a built copy before changing its base
	 * @param source a staged source
	 * @throws IOException if a base cannot be copied or scanned
	 */
	private void requireChangedPluginBaseRefusal(String caseName, boolean built, boolean cached, Path source)
		throws IOException
	{
		WorkspacePort port = subject.port();
		Path directory = subject.pluginDirectory();
		Files.writeString(directory.resolve("bin/unmapped"), "base " + caseName, UTF_8);
		String digest = port.copyPluginBase(directory);
		TrialPlugin plugin;
		if (built)
			plugin = builtPlugin(digest, List.of());
		else
			plugin = new TrialPlugin("tools", digest, new TrialPlugin.Installed(), List.of(), null);
		Path base = port.pluginBase(digest);
		if (cached)
		{
			Path copy = port.pluginCopy(plugin, source, PluginRemovals.NONE);
			requireThat(subject.pluginCopyExists(copy), "cachedCopyBeforeBaseChange").isTrue();
		}

		Files.writeString(base.resolve("bin/unmapped"), "changed " + caseName, UTF_8);
		String observed = InventoryScanner.scan(base, Set.of()).identity().hex();
		requireThat(observed, "observedBaseDigest").isNotEqualTo(digest);
		try
		{
			port.pluginCopy(plugin, source, PluginRemovals.NONE);
			throw new AssertionError("Plugin copy accepted changed base " + caseName);
		}
		catch (WorkspaceException e)
		{
			requireThat(e.getMessage(), "failureMessage").contains(base.toString()).contains(digest).
				contains(observed).contains("Run start").contains("copies the plugin's base again");
		}
	}

	/**
	 * Obtains a staged path for the physical input supplied to plugin-copy checks; this does not assert virtual staging
	 * bytes.
	 *
	 * @return a staged path that owns plugin-copy test input
	 * @throws IOException if staging fails
	 */
	private Path pluginSource() throws IOException
	{
		return subject.port().stage(candidate(), Set.of());
	}

	/**
	 * Writes a physical plugin-copy input below its staged path.
	 *
	 * @param source the staged path
	 * @param path the relative plugin mapping source
	 * @param content its UTF-8 contents
	 * @throws IOException if the input cannot be written
	 */
	private static void writePluginInput(Path source, Path path, String content) throws IOException
	{
		Path file = source.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, UTF_8);
	}

	/**
	 * Creates a project-built trial plugin with the given mappings.
	 *
	 * @param digest the plugin base's files' digest
	 * @param mappings its project-file mappings
	 * @return the project-built plugin
	 */
	private static TrialPlugin builtPlugin(String digest, List<TrialPlugin.Mapping> mappings)
	{
		return new TrialPlugin("cat", digest, new TrialPlugin.Build(List.of("build"), Path.of("out")), mappings,
			null);
	}

	/**
	 * Requires the workspace to refuse a plugin copy with the specified context.
	 *
	 * @param plugin the trial plugin
	 * @param source the staged source
	 * @param removals the dependents to remove
	 * @param context the failure message context
	 * @throws IOException if a plugin copy fails with another checked exception
	 */
	private void requirePluginCopyRefusal(TrialPlugin plugin, Path source, PluginRemovals removals, String context)
		throws IOException
	{
		try
		{
			subject.port().pluginCopy(plugin, source, removals);
			throw new AssertionError("Plugin copy accepted " + context);
		}
		catch (WorkspaceException e)
		{
			requireThat(e.getMessage(), "failureMessage").contains(context);
		}
	}

	/**
	 * Checks that the optimization worktree is an absolute directory.
	 */
	public void locatesTheWorktree()
	{
		requireThat(subject.port().worktree(), "worktree").isAbsolute();
	}
}
