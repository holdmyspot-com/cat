package io.github.cowwoc.cat.common.optimizer.inventory;

import java.util.List;

/**
 * Renders file changes as unified diffs using the same line comparison that defines prompt author hunks.
 */
public final class UnifiedDiff
{
	/**
	 * Prevents instantiation.
	 */
	private UnifiedDiff()
	{
	}

	/**
	 * Renders content changes with no context lines, including creates, deletes and missing final newlines.
	 *
	 * @param files the file changes in presentation order
	 * @return their unified diff, empty when no content changed
	 */
	public static String of(List<FileDelta> files)
	{
		StringBuilder diff = new StringBuilder(256);
		for (FileDelta file : files)
			append(diff, file);
		return diff.toString();
	}

	/**
	 * Appends one file's headers and changed line ranges, or a binary-difference notice.
	 *
	 * @param diff the output
	 * @param file the file change
	 */
	private static void append(StringBuilder diff, FileDelta file)
	{
		List<String> before = Hunks.lines(file.before()).orElse(null);
		List<String> after = Hunks.lines(file.after()).orElse(null);
		if (file.before() != null && before == null || file.after() != null && after == null)
		{
			diff.append("Binary files a/").append(file.path()).append(" and b/").append(file.path()).
				append(" differ\n");
			return;
		}
		if (before == null)
			before = List.of();
		if (after == null)
			after = List.of();
		diff.append("--- ");
		if (file.before() == null)
			diff.append("/dev/null");
		else
			diff.append("a/").append(file.path());
		diff.append("\n+++ ");
		if (file.after() == null)
			diff.append("/dev/null");
		else
			diff.append("b/").append(file.path());
		diff.append('\n');

		List<Hunk> hunks;
		if (file.before() == null || file.after() == null)
			hunks = List.of(new Hunk(file.path(), 1, 0, before.size(), 0, after.size()));
		else
			hunks = Hunks.between(file);
		for (Hunk hunk : hunks)
		{
			diff.append("@@ -").append(range(hunk.beforeStart(), hunk.beforeEnd())).append(" +").
				append(range(hunk.afterStart(), hunk.afterEnd())).append(" @@\n");
			appendLines(diff, '-', before.subList(hunk.beforeStart(), hunk.beforeEnd()));
			appendLines(diff, '+', after.subList(hunk.afterStart(), hunk.afterEnd()));
		}
	}

	/**
	 * Converts a zero-based half-open span to a unified-diff start and line count.
	 *
	 * @param start the span's first line
	 * @param end the exclusive span end
	 * @return its start and count
	 */
	private static String range(int start, int end)
	{
		int first = start;
		if (end > start)
			++first;
		return first + "," + (end - start);
	}

	/**
	 * Appends exact changed lines and marks an unterminated final line.
	 *
	 * @param diff the output
	 * @param prefix the added or removed line marker
	 * @param lines the changed lines, including their terminators
	 */
	private static void appendLines(StringBuilder diff, char prefix, List<String> lines)
	{
		for (String line : lines)
		{
			diff.append(prefix).append(line);
			if (!line.endsWith("\n"))
				diff.append("\n\\ No newline at end of file\n");
		}
	}
}
