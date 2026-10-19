package com.holdmyspot.cat.common.optimizer.evidence;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryDiff;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange.Added;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange.Modified;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange.Removed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Lists every change that a trial made to the project's files since the trial started, omitting the same state as the
 * inventory scanner.
 */
public final class FileChanges
{
	private static final int MAX_DIFF_BYTES = 200_000;

	/**
	 * Prevents instantiation.
	 */
	private FileChanges()
	{
	}

	/**
	 * Renders each changed file in path order. Valid UTF-8 text without NUL bytes receives a unified diff;
	 * a file larger than 200,000 bytes on either side receives a note naming its effects path.
	 *
	 * @param initialProject the trial's project as it was when the trial started
	 * @param effects the trial's final working directory
	 * @return the file changes, or an explicit statement that no files changed
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if either path is not a directory
	 * @throws IOException if a file cannot be read
	 */
	public static String render(Path initialProject, Path effects) throws IOException
	{
		requireThat(initialProject, "initialProject").isNotNull();
		requireThat(effects, "effects").isNotNull();
		FileInventory before = InventoryScanner.scan(initialProject, Set.of());
		FileInventory after = InventoryScanner.scan(effects, Set.of());
		List<PathChange> changes = InventoryDiff.between(before, after).changes();
		if (changes.isEmpty())
			return "No file changes.\n";
		StringBuilder result = new StringBuilder();
		for (PathChange change : changes)
		{
			switch (change)
			{
				case Added added -> append(result, "Created", change.path(), null, added.after(), initialProject,
					effects);
				case Removed removed -> append(result, "Deleted", change.path(), removed.before(), null, initialProject,
					effects);
				case Modified modified -> append(result, "Modified", change.path(), modified.before(), modified.after(),
					initialProject, effects);
			}
		}
		return result.toString();
	}

	/**
	 * Appends one changed path with its diff, permissions or non-text note.
	 *
	 * @param result the destination
	 * @param operation the change kind
	 * @param path the path relative to the compared directories
	 * @param before the earlier entry, or null
	 * @param after the later entry, or null
	 * @param initialProject the trial's project as it was when the trial started
	 * @param effects the trial directory
	 * @throws IOException if file contents cannot be read
	 */
	private static void append(StringBuilder result, String operation, Path path, FileEntry before, FileEntry after,
		Path initialProject, Path effects) throws IOException
	{
		result.append(operation).append(": ").append(PortablePaths.text(path));
		Path initialFile = initialProject.resolve(path);
		Path effectsFile = effects.toAbsolutePath().normalize().resolve(path);
		if (oversized(initialFile, before) || oversized(effectsFile, after))
		{
			appendNote(result, "oversized", effectsFile, after);
			return;
		}
		String earlier = text(initialFile, before);
		String later = text(effectsFile, after);
		if (before != null && earlier == null || after != null && later == null)
		{
			appendNote(result, "binary or non-text", effectsFile, after);
			return;
		}
		result.append('\n');
		if (before instanceof RegularFile first && after instanceof RegularFile last &&
			!first.permissions().equals(last.permissions()))
		{
			result.append("Permissions: ").append(first.permissions().stream().sorted().toList()).append(" -> ").
				append(last.permissions().stream().sorted().toList()).append('\n');
		}
		if (!Objects.equals(earlier, later))
			appendDiff(result, path, earlier, later);
		result.append('\n');
	}

	/**
	 * Indicates whether a regular file is too large for an inline diff.
	 *
	 * @param file its path
	 * @param entry its inventory entry, or null
	 * @return whether its bytes exceed the inline diff limit
	 * @throws IOException if its size cannot be read
	 */
	private static boolean oversized(Path file, FileEntry entry) throws IOException
	{
		return entry instanceof RegularFile && Files.size(file) > MAX_DIFF_BYTES;
	}

	/**
	 * Returns exact UTF-8 file content or a link destination without following the link.
	 *
	 * @param file the path
	 * @param entry the inventory entry, or null
	 * @return the text, or null for an absent or non-text entry
	 * @throws IOException if file contents cannot be read
	 */
	private static String text(Path file, FileEntry entry) throws IOException
	{
		if (entry instanceof SymbolicLink link)
			return PortablePaths.text(link.target());
		if (!(entry instanceof RegularFile))
			return null;
		try
		{
			String text = UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
			if (text.indexOf('\0') >= 0)
				return null;
			return text;
		}
		catch (CharacterCodingException _)
		{
			return null;
		}
	}

	/**
	 * Appends a one-line notice with the exact location of the changed file in the effects directory.
	 *
	 * @param result the destination
	 * @param kind why the contents are not diffed
	 * @param effectsFile the effects path
	 * @param after its final entry, or null for a deletion
	 */
	private static void appendNote(StringBuilder result, String kind, Path effectsFile, FileEntry after)
	{
		result.append("; ").append(kind).append("; effects: ").append(effectsFile);
		if (after == null)
			result.append(" (deleted; absent from effects)");
		result.append('\n');
	}

	/**
	 * Appends a unified diff using one changed range after removing the shared leading and trailing lines.
	 * The comparison uses linear memory even when a file contains many short lines.
	 *
	 * @param result the destination
	 * @param path the changed path
	 * @param earlier the earlier text, or null for a creation
	 * @param later the later text, or null for a deletion
	 */
	private static void appendDiff(StringBuilder result, Path path, String earlier, String later)
	{
		result.append("--- ");
		if (earlier == null)
			result.append("/dev/null");
		else
			result.append("a/").append(PortablePaths.text(path));
		result.append("\n+++ ");
		if (later == null)
			result.append("/dev/null");
		else
			result.append("b/").append(PortablePaths.text(path));
		result.append('\n');

		List<String> before = lines(earlier);
		List<String> after = lines(later);
		int start = 0;
		while (start < before.size() && start < after.size() && before.get(start).equals(after.get(start)))
			++start;
		int beforeEnd = before.size();
		int afterEnd = after.size();
		while (beforeEnd > start && afterEnd > start && before.get(beforeEnd - 1).equals(after.get(afterEnd - 1)))
		{
			--beforeEnd;
			--afterEnd;
		}
		result.append("@@ -").append(range(start, beforeEnd)).append(" +").append(range(start, afterEnd)).
			append(" @@\n");
		appendLines(result, '-', before.subList(start, beforeEnd));
		appendLines(result, '+', after.subList(start, afterEnd));
	}

	/**
	 * Splits exact text into lines including their terminators.
	 *
	 * @param text the text, or null for an absent file
	 * @return its lines
	 */
	private static List<String> lines(String text)
	{
		if (text == null || text.isEmpty())
			return List.of();
		return List.of(text.split("(?<=\\n)"));
	}

	/**
	 * Converts a zero-based half-open range to a unified-diff start and count.
	 *
	 * @param start the range start
	 * @param end the range end
	 * @return its start and line count
	 */
	private static String range(int start, int end)
	{
		int first = start;
		if (end > start)
			++first;
		return first + "," + (end - start);
	}

	/**
	 * Appends added or removed lines and marks missing final newlines.
	 *
	 * @param result the destination
	 * @param prefix the addition or removal marker
	 * @param lines the exact changed lines
	 */
	private static void appendLines(StringBuilder result, char prefix, List<String> lines)
	{
		for (String line : lines)
		{
			result.append(prefix).append(line);
			if (!line.endsWith("\n"))
				result.append("\n\\ No newline at end of file\n");
		}
	}
}
