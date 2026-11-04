package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Copies a directory tree as release activation does: links inside the tree stay links, a link that leaves the tree is
 * refused, and the executable bits of files are kept.
 */
public final class TreeCopy
{
	/**
	 * Prevents construction.
	 */
	private TreeCopy()
	{
	}

	/**
	 * Copies a tree into a directory that does not exist yet.
	 *
	 * @param source the tree
	 * @param target the new directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the copy fails, a link leaves the tree, or the tree has too many entries
	 */
	public static void copy(Path source, Path target) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		ActivationTransaction.copyTree(source, target);
	}
}
