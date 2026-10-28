package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The bounds that a session request's archive must stay within, both when the launcher packs it and when the runner
 * copies and unpacks it.
 *
 * @param entries      the most archive entries, counting directories and physical headers
 * @param totalBytes   the most content bytes across all entries
 * @param archiveBytes the most bytes of the archive file, including tar headers, padding and metadata in addition to
 *                     the content
 */
public record RequestLimits(int entries, long totalBytes, long archiveBytes)
{
	/**
	 * The bounds of every request: 10,000 entries, 256 MiB of content and 300,000,000 archive bytes.
	 */
	public static final RequestLimits DEFAULT = new RequestLimits(10_000, 268_435_456, 300_000_000);

	/**
	 * Creates the bounds.
	 *
	 * @param entries      the most archive entries, counting directories and physical headers
	 * @param totalBytes   the most content bytes across all entries
	 * @param archiveBytes the most bytes of the archive file, including tar headers, padding and metadata in addition
	 *                     to the content
	 * @throws IllegalArgumentException if a bound is not positive
	 */
	public RequestLimits
	{
		requireThat(entries, "entries").isPositive();
		requireThat(totalBytes, "totalBytes").isPositive();
		requireThat(archiveBytes, "archiveBytes").isPositive();
	}
}
