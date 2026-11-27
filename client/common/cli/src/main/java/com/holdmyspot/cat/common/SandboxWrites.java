package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;

/**
 * Where a harness's sandbox lets its commands write.
 *
 * @param grants  the folders that the commands may write, each with the setting that grants it
 * @param denials the absolute paths inside the grants that the commands may not write
 */
public record SandboxWrites(List<WriteGrant> grants, List<Path> denials)
{
	/**
	 * Grants nothing.
	 */
	public static final SandboxWrites NONE = new SandboxWrites(List.of(), List.of());

	/**
	 * Creates the writes.
	 *
	 * @param grants  the folders that the commands may write, each with the setting that grants it
	 * @param denials the absolute paths inside the grants that the commands may not write
	 * @throws NullPointerException     if any argument is null or a list contains null
	 * @throws IllegalArgumentException if a denial is relative
	 */
	public SandboxWrites
	{
		grants = List.copyOf(grants);
		denials = List.copyOf(denials);
		for (Path denial : denials)
			requireThat(denial, "denial").isAbsolute();
	}
}
