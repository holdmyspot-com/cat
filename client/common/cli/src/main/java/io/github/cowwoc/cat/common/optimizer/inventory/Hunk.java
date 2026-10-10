package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A changed portion of a file, identified by {@link Hunks#between(FileDelta)}.
 *
 * @param path the resolved file path
 * @param ordinal the hunk's position within its file, from 1
 * @param beforeStart the first replaced line in the working file
 * @param beforeEnd the line after the replaced range
 * @param afterStart the first replacement line in the prompt author file
 * @param afterEnd the line after the replacement range
 */
public record Hunk(Path path, int ordinal, int beforeStart, int beforeEnd, int afterStart, int afterEnd)
{
	/**
	 * The definition supplied to prompt authors and implemented by {@link Hunks}.
	 */
	public static final String DEFINITION = """
		A hunk is a maximal adjacent changed range with no unchanged context in the longest common line subsequence
		comparison of the working file and your edited file. A line includes its terminator; a changed final newline
		changes its line. When equal-length subsequences tie, consume a working line before a prompt author line.
		Number hunks from 1 in each file's order. A file create, delete, invalid UTF-8 or NUL-containing change is one
		whole-file hunk. Annotate each change with its resolved path and zero-based, half-open before_start..before_end and
		after_start..after_end line ranges. Annotations may include unchanged context to disambiguate repeated lines;
		CAT maps each contained hunk to that annotation's citations and cases. Cover every hunk exactly once.
		Whole-file hunks use all four coordinates equal to zero. Cite only the supplied failure ids.
		""";
	/**
	 * Creates a hunk.
	 *
	 * @param path the resolved file path
	 * @param ordinal the hunk's position within its file, from 1
	 * @param beforeStart the first replaced line
	 * @param beforeEnd the end of the replaced range
	 * @param afterStart the first replacement line
	 * @param afterEnd the end of the replacement range
	 * @throws NullPointerException if {@code path} is null
	 * @throws IllegalArgumentException if a path or range is invalid
	 */
	public Hunk
	{
		FileInventory.requireInventoryPath(path, "path");
		requireThat(ordinal, "ordinal").isPositive();
		requireThat(beforeStart, "beforeStart").isNotNegative();
		requireThat(beforeEnd, "beforeEnd").isGreaterThanOrEqualTo(beforeStart);
		requireThat(afterStart, "afterStart").isNotNegative();
		requireThat(afterEnd, "afterEnd").isGreaterThanOrEqualTo(afterStart);
	}

	/**
	 * Returns the stable manifest and report identifier for this hunk.
	 *
	 * @return the path followed by {@code #} and the ordinal
	 */
	public String id()
	{
		return path + "#" + ordinal;
	}
}
