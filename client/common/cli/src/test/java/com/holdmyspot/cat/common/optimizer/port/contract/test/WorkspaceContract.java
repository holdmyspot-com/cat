package com.holdmyspot.cat.common.optimizer.port.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
		String digest = port.copyPluginBase(subject.pluginDirectory());
		TrialPlugin installed = new TrialPlugin("tools", digest, new TrialPlugin.Installed(), List.of(), null);
		TrialPlugin built = new TrialPlugin("cat", digest, new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(), null);
		Overlay candidate = candidate();
		Path source = port.stage(candidate, Set.of());
		Path otherSource = port.stage(Overlay.of(subject.fork()), Set.of());
		PluginRemovals removals = PluginRemovals.of(List.of(new Dependent(Dependent.Location.PLUGIN, "cat",
			Path.of("bin/read-part"), new Dependent.WholeFile(), "Reads the part that the rule names.")),
			JsonMapper.builder().build());

		requireThat(port.pluginCopy(installed, source, PluginRemovals.NONE), "installedCopy").
			isEqualTo(port.pluginBase(digest));
		Path copy = port.pluginCopy(built, source, PluginRemovals.NONE);
		requireThat(copy, "builtCopy").isNotEqualTo(port.pluginBase(digest));
		requireThat(port.pluginCopy(built, source, PluginRemovals.NONE), "repeatedCopy").isEqualTo(copy);
		requireThat(port.pluginCopy(built, otherSource, PluginRemovals.NONE), "otherSourceCopy").isNotEqualTo(copy);
		Path withoutDependents = port.pluginCopy(built, source, removals);
		requireThat(withoutDependents, "copyWithRemovals").isNotEqualTo(copy);
		port.deleteStaged(candidate.identity());
		requireThat(Files.exists(copy), "copyAfterDeletion").isFalse();
		requireThat(Files.exists(withoutDependents), "copyWithRemovalsAfterDeletion").isFalse();
	}

	/**
	 * Checks that the optimization worktree is an absolute directory.
	 */
	public void locatesTheWorktree()
	{
		requireThat(subject.port().worktree(), "worktree").isAbsolute();
	}
}
