package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The bounds that a session request's archive must stay within, both when its sender packs it and when its receiver
 * copies and unpacks it. The manifest, a prompt and a long-name header keep their own fixed bounds at every level,
 * because the receiver reads them into memory.
 *
 * @param entries      the most archive entries, counting directories and physical headers
 * @param entryBytes   the most content bytes of one entry
 * @param totalBytes   the most content bytes across all entries
 * @param archiveBytes the most bytes of the archive file, including tar headers, padding and metadata in addition to
 *                     the content
 */
public record RequestLimits(int entries, long entryBytes, long totalBytes, long archiveBytes)
{
	/**
	 * The bounds of a request made inside a run: 10,000 entries, 64 MiB per entry, 256 MiB of content and 300,000,000
	 * archive bytes.
	 */
	public static final RequestLimits IN_RUN = new RequestLimits(10_000, 67_108_864, 268_435_456, 300_000_000);
	/**
	 * The bounds of a request made at session level, which no archive reaches: such a request carries a project of any
	 * size, as a run started outside a session copies one, and only the free disk space bounds it.
	 */
	public static final RequestLimits UNBOUNDED = new RequestLimits(Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
		Long.MAX_VALUE);

	/**
	 * Creates the bounds.
	 *
	 * @param entries      the most archive entries, counting directories and physical headers
	 * @param entryBytes   the most content bytes of one entry
	 * @param totalBytes   the most content bytes across all entries
	 * @param archiveBytes the most bytes of the archive file, including tar headers, padding and metadata in addition
	 *                     to the content
	 * @throws IllegalArgumentException if a bound is not positive
	 */
	public RequestLimits
	{
		requireThat(entries, "entries").isPositive();
		requireThat(entryBytes, "entryBytes").isPositive();
		requireThat(totalBytes, "totalBytes").isPositive();
		requireThat(archiveBytes, "archiveBytes").isPositive();
	}
}
