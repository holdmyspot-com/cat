package io.github.cowwoc.cat.common.optimizer.delivery;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.ConcurrentEdit;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Conflicted;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Delivered;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.UnownedChange;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.VerificationFailed;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Create;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Edit;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryPlan;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryDiff;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.DeliveryJournal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Delivers a selected candidate to the base worktree and its authorized external targets.
 * <p>
 * Delivery runs in four steps:
 * <ol>
 *   <li>It checks that the optimization worktree holds only content that CAT recorded, and materializes the selected
 *   candidate there if it is not the working candidate.</li>
 *   <li>It plans the delivery against a fresh scan of the base worktree and the external targets, and stops before
 *   writing anything if the plan has conflicts.</li>
 *   <li>It writes the delivery journal, then applies each change after checking its target's live state, recording
 *   each applied change in the journal. A restarted delivery resumes from the journal and applies each change at most
 *   once.</li>
 *   <li>It checks that every target holds the candidate's state and reports the Git diff of the base worktree, the
 *   external change record, the paths that only the base or external state changed, and whether the result is
 *   current or historical.</li>
 * </ol>
 * Delivery never commits, stages or moves a branch in the base worktree; the user reviews the uncommitted changes.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. One process at a time may deliver a campaign.
 */
public final class Delivery
{
	private static final String TEMPORARY_SUFFIX = ".cat-delivery";
	private final CampaignStore store;
	private final WorkspacePort workspace;
	private final Consumer<DeliveryChange> afterWrite;

	/**
	 * Creates a delivery executor.
	 *
	 * @param store the store that holds the campaign's delivery journal
	 * @param workspace the optimization worktree
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Delivery(CampaignStore store, WorkspacePort workspace)
	{
		this(store, workspace, _ ->
		{
		});
	}

	/**
	 * Creates a delivery executor that reports each written change.
	 *
	 * @param store the store that holds the campaign's delivery journal
	 * @param workspace the optimization worktree
	 * @param afterWrite receives each change after its target is written and before the journal records it; an
	 *   exception that it throws stops the delivery at that point, as a crash would
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Delivery(CampaignStore store, WorkspacePort workspace, Consumer<DeliveryChange> afterWrite)
	{
		requireThat(store, "store").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		requireThat(afterWrite, "afterWrite").isNotNull();
		this.store = store;
		this.workspace = workspace;
		this.afterWrite = afterWrite;
	}

	/**
	 * Delivers a candidate, or resumes its interrupted delivery.
	 *
	 * @param campaignId the campaign's identity
	 * @param request the user's delivery request, which names the selected candidate and the label it carries
	 * @param selected the selected candidate's overlay; its fork's root is the base worktree
	 * @param working the working candidate's overlay, which the optimization worktree holds unless the selected
	 *   candidate was already materialized there
	 * @param promptSources the participating prompt sources: paths relative to the base worktree, or absolute paths of
	 *   external targets; a base-only change at or below one of them makes the result historical
	 * @return the result
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the request's label never delivers a candidate, if the request names a
	 *   different candidate than {@code selected}, or if {@code selected} and {@code working} have different forks
	 * @throws IllegalStateException if the campaign already journals the delivery of another candidate
	 * @throws IOException if a file cannot be read or written, or if Git fails
	 */
	public DeliveryResult deliver(String campaignId, DeliveryRequest request, Overlay selected, Overlay working,
		Set<Path> promptSources) throws IOException
	{
		requireThat(campaignId, "campaignId").isNotNull();
		requireThat(request, "request").isNotNull();
		requireThat(selected, "selected").isNotNull();
		requireThat(working, "working").isNotNull();
		requireThat(promptSources, "promptSources").isNotNull();
		requireDeliverable(request.label());
		requireThat(request.candidate().snapshot(), "request.candidate().snapshot()").
			isEqualTo(selected.identity(), "selected.identity()");
		requireThat(working.fork(), "working.fork()").isEqualTo(selected.fork(), "selected.fork()");

		Optional<DeliveryJournal> recorded = store.loadJournal(campaignId);
		if (recorded.isPresent() && !recorded.get().candidate().equals(selected.identity()))
		{
			throw new IllegalStateException("Campaign " + campaignId + " already journals the delivery of candidate " +
				recorded.get().candidate().hex() + ", not of " + selected.identity().hex() + ".");
		}

		Optional<UnownedChange> unowned = prepareWorktree(selected, working);
		if (unowned.isPresent())
			return unowned.get();

		Path base = selected.fork().root();
		Set<Path> externalPaths = externalPaths(selected);
		DeliveryJournal journal;
		if (recorded.isPresent())
			journal = recorded.get();
		else
		{
			DeliveryPlan plan = DeliveryPlan.of(selected, InventoryScanner.scan(base, externalPaths));
			if (!plan.conflicts().isEmpty())
				return new Conflicted(plan.conflicts());
			journal = new DeliveryJournal(selected.identity(), plan.changes(), 0);
			store.writeJournal(campaignId, journal);
		}

		for (int i = journal.applied(); i < journal.changes().size(); ++i)
		{
			DeliveryChange change = journal.changes().get(i);
			Path file = base.resolve(change.target());
			Optional<FileEntry> live = liveState(file);
			if (!live.equals(change.postimage().map(FileEntry.class::cast)))
			{
				if (!live.equals(change.preimage().map(FileEntry.class::cast)))
					return new ConcurrentEdit(change, i);
				apply(change, file);
				afterWrite.accept(change);
			}
			journal = journal.withApplied(i + 1);
			store.writeJournal(campaignId, journal);
		}
		return verify(request.label(), selected, journal, externalPaths, promptSources);
	}

	/**
	 * Rejects a label that never accompanies a delivered candidate.
	 *
	 * @param label the label of the delivery request
	 * @throws IllegalArgumentException if {@code label} is not {@code certified}, {@code improved, not certified} or
	 *   {@code not certified}
	 */
	private static void requireDeliverable(Outcome label)
	{
		requireThat(label, "label").isNotNull();
		boolean deliverable = switch (label)
		{
			case CERTIFIED, IMPROVED_NOT_CERTIFIED, NOT_CERTIFIED -> true;
			case BELOW_MINIMUM, INCONCLUSIVE, PLATEAU, STOPPED_FOR_CONTRACT_REVISION, TESTING_ENVIRONMENT_CHANGED,
				UNSUPPORTED_INSTRUCTION_SOURCE, INELIGIBLE_TRIAL_EFFECT, UNSUPPORTED_OUTPUT, UNSUPPORTED_TEST_INTERACTION,
				INVALID_STATISTICAL_ASSUMPTIONS -> false;
		};
		if (!deliverable)
		{
			throw new IllegalArgumentException("A delivered candidate is labelled \"certified\", \"improved, not " +
				"certified\" or \"not certified\", but the request is labelled \"" + label.label() + "\".");
		}
	}

	/**
	 * Checks that the optimization worktree holds only recorded content, and materializes the selected candidate there
	 * if the worktree holds the working candidate instead.
	 *
	 * @param selected the selected candidate
	 * @param working the working candidate
	 * @return the unrecorded differences between the working candidate and the worktree, or empty if there are none
	 * @throws IOException if the worktree cannot be read or written
	 */
	private Optional<UnownedChange> prepareWorktree(Overlay selected, Overlay working) throws IOException
	{
		Map<Path, FileEntry> worktree = projectEntries(workspace.scanWorktree());
		if (worktree.equals(projectEntries(selected.snapshot())))
			return Optional.empty();
		Map<Path, FileEntry> expected = projectEntries(working.snapshot());
		if (!worktree.equals(expected))
		{
			Path root = selected.fork().root();
			List<PathChange> changes = InventoryDiff.between(new FileInventory(root, expected),
				new FileInventory(root, worktree)).changes();
			return Optional.of(new UnownedChange(changes));
		}
		workspace.materialize(selected);
		return Optional.empty();
	}

	/**
	 * Returns the entries of an inventory that lie inside the project. The optimization worktree holds only these; the
	 * external targets exist once, outside every worktree.
	 *
	 * @param inventory the inventory
	 * @return the entries keyed by a relative path
	 */
	private static Map<Path, FileEntry> projectEntries(FileInventory inventory)
	{
		Map<Path, FileEntry> entries = new HashMap<>();
		for (Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			if (!entry.getKey().isAbsolute())
				entries.put(entry.getKey(), entry.getValue());
		}
		return entries;
	}

	/**
	 * Returns the external paths that a scan of the base must cover: every external file that the fork recorded or
	 * that the candidate changes.
	 *
	 * @param selected the selected candidate
	 * @return the absolute paths
	 */
	private static Set<Path> externalPaths(Overlay selected)
	{
		Set<Path> paths = new TreeSet<>();
		for (Path path : selected.fork().entries().keySet())
		{
			if (path.isAbsolute())
				paths.add(path);
		}
		for (Path path : selected.entries().keySet())
		{
			if (path.isAbsolute())
				paths.add(path);
		}
		return paths;
	}

	/**
	 * Returns the live state of a target, read without following symbolic links.
	 *
	 * @param file the target
	 * @return the regular file, a {@link SpecialFile} for any other kind of file, or empty if nothing exists there
	 * @throws IOException if the target cannot be read
	 */
	private static Optional<FileEntry> liveState(Path file) throws IOException
	{
		PosixFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		if (!attributes.isRegularFile())
			return Optional.of(new SpecialFile());
		return Optional.of(new RegularFile(Sha256.of(Files.readAllBytes(file)), attributes.permissions()));
	}

	/**
	 * Applies one change to a target whose live state matches the change's preimage.
	 *
	 * @param change the change
	 * @param file the target
	 * @throws IOException if the target cannot be written
	 */
	private static void apply(DeliveryChange change, Path file) throws IOException
	{
		switch (change)
		{
			case Create create ->
			{
				Files.createDirectories(file.getParent());
				replace(file, create.content(), create.permissions());
			}
			case Edit edit -> replace(file, edit.content(), edit.permissions());
			case Delete _ ->
			{
				Files.delete(file);
				force(file.getParent());
			}
		}
	}

	/**
	 * Replaces a file atomically: the content is written to a temporary sibling, forced to the storage device, given
	 * its mode, and renamed over the file. A crash leaves either the old or the new file; the next delivery run
	 * replaces a leftover temporary file.
	 *
	 * @param file the file
	 * @param content the file's new bytes
	 * @param permissions the file's new mode
	 * @throws IOException if the file cannot be written
	 */
	private static void replace(Path file, FileContent content, Set<PosixFilePermission> permissions)
		throws IOException
	{
		Path temporary = file.resolveSibling("." + file.getFileName() + TEMPORARY_SUFFIX);
		Files.write(temporary, content.bytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE);
		force(temporary);
		Files.setPosixFilePermissions(temporary, permissions);
		Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		force(file.getParent());
	}

	/**
	 * Forces a file or directory to the storage device.
	 *
	 * @param path the file or directory
	 * @throws IOException if it cannot be forced
	 */
	private static void force(Path path) throws IOException
	{
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ))
		{
			channel.force(true);
		}
	}

	/**
	 * Checks that every target holds the candidate's state and reports the delivered result.
	 *
	 * @param label the label that the delivered result carries
	 * @param selected the delivered candidate
	 * @param journal the completed journal
	 * @param externalPaths the external paths that a scan of the base covers
	 * @param promptSources the participating prompt sources
	 * @return the delivered result, or {@link VerificationFailed} if a target does not hold the candidate's state
	 * @throws IOException if a file cannot be read or Git fails
	 */
	private static DeliveryResult verify(Outcome label, Overlay selected, DeliveryJournal journal,
		Set<Path> externalPaths, Set<Path> promptSources) throws IOException
	{
		Path base = selected.fork().root();
		List<Path> mismatched = new ArrayList<>();
		for (DeliveryChange change : journal.changes())
		{
			if (!liveState(base.resolve(change.target())).equals(change.postimage().map(FileEntry.class::cast)))
				mismatched.add(change.target());
		}
		if (!mismatched.isEmpty())
			return new VerificationFailed(mismatched);

		FileInventory current = InventoryScanner.scan(base, externalPaths);
		List<Path> baseOnlyChanges = InventoryDiff.between(selected.snapshot(), current).changes().stream().
			map(PathChange::path).sorted().toList();
		List<Path> changedPromptSources = baseOnlyChanges.stream().
			filter(path -> promptSources.stream().anyMatch(path::startsWith)).toList();
		Applicability applicability;
		if (changedPromptSources.isEmpty())
			applicability = new Applicability(Applicability.Status.CURRENT, false, List.of());
		else
			applicability = new Applicability(Applicability.Status.HISTORICAL, true, List.of());
		return new Delivered(label, applicability, changedPromptSources, baseOnlyChanges, journal.changes(),
			diff(base, journal.changes()));
	}

	/**
	 * Returns the Git diff of the delivered project paths against the base worktree's {@code HEAD}, including created
	 * files. A temporary index inside the Git directory marks the created files as intended additions, so the user's
	 * index stays untouched.
	 *
	 * @param base the base worktree
	 * @param changes the delivered changes
	 * @return the diff, or an empty string if the candidate changes only external targets
	 * @throws IOException if Git fails
	 */
	private static String diff(Path base, List<DeliveryChange> changes) throws IOException
	{
		List<String> targets = new ArrayList<>();
		List<String> existing = new ArrayList<>();
		for (DeliveryChange change : changes)
		{
			if (change.target().isAbsolute())
				continue;
			targets.add(change.target().toString());
			if (change.postimage().isPresent())
				existing.add(change.target().toString());
		}
		if (targets.isEmpty())
			return "";
		Git git = new Git(base);
		Path gitDirectory = Path.of(git.run("rev-parse", "--absolute-git-dir").strip());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path index = cleanup.add(Files.createTempDirectory(gitDirectory, "cat-delivery-")).resolve("index");
			git.lines(index, "read-tree", "HEAD");
			if (!existing.isEmpty())
				git.lines(index, arguments(List.of("add", "--intent-to-add", "--force", "--"), existing));
			List<String> lines = git.lines(index, arguments(List.of("diff", "--no-color", "--no-ext-diff", "HEAD",
				"--"), targets));
			if (lines.isEmpty())
				return "";
			return String.join("\n", lines) + "\n";
		}
	}

	/**
	 * Concatenates a Git command and its paths.
	 *
	 * @param command the subcommand and options
	 * @param paths the paths
	 * @return the arguments
	 */
	private static String[] arguments(List<String> command, List<String> paths)
	{
		List<String> arguments = new ArrayList<>(command);
		arguments.addAll(paths);
		return arguments.toArray(String[]::new);
	}
}
