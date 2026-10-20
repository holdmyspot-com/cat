package com.holdmyspot.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The participants that a fork recorded after its creation, the targets outside the project that their links reached,
 * and which participants its files hold now.
 * <p>
 * A fork's files hold every path that it was created with, each selected participant and the paths that their links
 * reach. A participant that a later selection omits keeps its copy in storage but leaves the fork's files, and so do
 * the targets that only its links reach, so a selection that names it again restores the same copies rather than
 * reading the live files. After confirmation the fork only grows, and the selection of each fork that a campaign
 * moved off stays stored, so the candidates on it stay available.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param added every participant recorded after the fork's creation, and every target that their links reached
 * @param selected the added participants that the fork's files hold
 */
record ParticipantSelection(Set<Path> added, Set<Path> selected)
{
	/**
	 * The selection of a fork that recorded no participant after its creation.
	 */
	static final ParticipantSelection NONE = new ParticipantSelection(Set.of(), Set.of());
	private static final String FILE = "fork-participants";
	private static final String VERSIONS = "fork-versions";
	private static final char SELECTED = '+';
	private static final char LEFT_OUT = '-';

	/**
	 * Creates a selection.
	 *
	 * @param added every participant recorded after the fork's creation, and every target that their links reached
	 * @param selected the added participants that the fork's files hold
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code selected} holds a path that {@code added} lacks
	 */
	ParticipantSelection
	{
		requireThat(added, "added").isNotNull();
		requireThat(selected, "selected").isNotNull();
		requireThat(added.containsAll(selected), "added.containsAll(selected)").isTrue();
		added = Set.copyOf(added);
		selected = Set.copyOf(selected);
	}

	/**
	 * Reads the selection that a campaign stores for its fork.
	 *
	 * @param campaign the campaign storage directory
	 * @return the selection, or {@link #NONE} if the campaign stores none
	 * @throws IOException if the stored selection cannot be read or is malformed
	 */
	static ParticipantSelection read(Path campaign) throws IOException
	{
		Path file = campaign.resolve(FILE);
		if (Files.notExists(file, NOFOLLOW_LINKS))
			return NONE;
		return read(file, Files.readString(file, UTF_8));
	}

	/**
	 * Reads the selection of an earlier fork that a campaign ran on.
	 *
	 * @param campaign the campaign storage directory
	 * @param fork the earlier fork's identity
	 * @return the selection, or empty if the campaign stores none for that fork
	 * @throws IOException if the stored selection cannot be read or is malformed
	 */
	static Optional<ParticipantSelection> readVersion(Path campaign, Sha256 fork) throws IOException
	{
		Path file = campaign.resolve(VERSIONS).resolve(fork.hex());
		if (Files.notExists(file, NOFOLLOW_LINKS))
			return Optional.empty();
		return Optional.of(read(file, Files.readString(file, UTF_8)));
	}

	/**
	 * Parses a stored selection.
	 *
	 * @param file the file that holds it, for diagnostics
	 * @param text the file's content
	 * @return the selection
	 * @throws IOException if the selection is malformed
	 */
	private static ParticipantSelection read(Path file, String text) throws IOException
	{
		Set<Path> added = new HashSet<>();
		Set<Path> selected = new HashSet<>();
		for (String entry : text.split("\0"))
		{
			if (entry.isEmpty())
				continue;
			Path path = Path.of(entry.substring(1));
			added.add(path);
			switch (entry.charAt(0))
			{
				case SELECTED -> selected.add(path);
				case LEFT_OUT ->
				{
					// The fork recorded the participant, but its files leave it out
				}
				default -> throw new IOException("The participant selection " + file + " holds an entry that is " +
					"neither selected nor left out: " + entry);
			}
		}
		return new ParticipantSelection(added, selected);
	}

	/**
	 * Stores this selection for a campaign's fork, replacing the stored one in a single step.
	 *
	 * @param campaign the campaign storage directory
	 * @param replacer replaces the stored selection
	 * @throws IOException if the selection cannot be written
	 */
	void write(Path campaign, FileReplacer replacer) throws IOException
	{
		writeTo(campaign.resolve(FILE), replacer);
	}

	/**
	 * Stores this selection as that of an earlier fork that a campaign ran on, so that the candidates on that fork
	 * stay available after the campaign moves onto a larger one.
	 *
	 * @param campaign the campaign storage directory
	 * @param fork the identity of the fork that this selection gives
	 * @param replacer replaces the stored selection
	 * @throws IOException if the selection cannot be written
	 */
	void writeVersion(Path campaign, Sha256 fork, FileReplacer replacer) throws IOException
	{
		Path directory = campaign.resolve(VERSIONS);
		Files.createDirectories(directory);
		writeTo(directory.resolve(fork.hex()), replacer);
	}

	/**
	 * Stores this selection in a file, replacing it in a single step.
	 *
	 * @param file the file
	 * @param replacer replaces the file
	 * @throws IOException if the selection cannot be written
	 */
	private void writeTo(Path file, FileReplacer replacer) throws IOException
	{
		SortedSet<Path> paths = new TreeSet<>(added);
		StringBuilder text = new StringBuilder(paths.size() * 64);
		for (Path path : paths)
		{
			if (selected.contains(path))
				text.append(SELECTED);
			else
				text.append(LEFT_OUT);
			text.append(PortablePaths.text(path)).append('\0');
		}
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, text, UTF_8);
		replacer.replace(temporary, file);
	}

	/**
	 * Returns the selection that holds exactly the given participants beside the paths that the fork was created
	 * with.
	 *
	 * @param participants the participants to hold, none of which the fork was created with
	 * @return the new selection
	 */
	ParticipantSelection select(Set<Path> participants)
	{
		Set<Path> all = new HashSet<>(added);
		all.addAll(participants);
		return new ParticipantSelection(all, participants);
	}

	/**
	 * Returns this selection with more recorded participants, none of which it selects.
	 *
	 * @param participants the participants
	 * @return the new selection
	 */
	ParticipantSelection withAdded(Set<Path> participants)
	{
		Set<Path> all = new HashSet<>(added);
		all.addAll(participants);
		return new ParticipantSelection(all, selected);
	}

	/**
	 * Returns this selection with other recorded participants selected.
	 *
	 * @param participants the participants to hold, each of which this selection records
	 * @return the new selection
	 * @throws IllegalArgumentException if this selection does not record a participant
	 */
	ParticipantSelection withSelected(Set<Path> participants)
	{
		return new ParticipantSelection(added, participants);
	}

	/**
	 * Returns this selection with a target outside the project that a participant's link reached, recorded but not
	 * selected.
	 *
	 * @param target the absolute path of the target
	 * @return the new selection
	 */
	ParticipantSelection withTarget(Path target)
	{
		return withAdded(Set.of(target));
	}

	/**
	 * Indicates whether the fork's files hold a recorded path.
	 *
	 * @param key a key of the fork's recorded inventory
	 * @return {@code true} if the fork was created with the path or it lies at or below a selected participant; the
	 *   paths that their links reach are held as well
	 */
	boolean holds(Path key)
	{
		return isCreated(key) || selected.stream().anyMatch(key::startsWith);
	}

	/**
	 * Indicates whether a recorded path is one that the fork was created with.
	 *
	 * @param key a key of the fork's recorded inventory
	 * @return {@code true} if the path lies at or below no participant that was added after creation
	 */
	boolean isCreated(Path key)
	{
		return added.stream().noneMatch(key::startsWith);
	}
}
