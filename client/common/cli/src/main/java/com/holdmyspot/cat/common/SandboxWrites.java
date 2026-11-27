package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;

/**
 * Where a harness's sandbox lets its commands write, read so that it overestimates: a grant that CAT cannot rule out
 * is included and marked as possible, and a denial is included only when CAT is certain of it.
 *
 * @param grants   the folders that the commands may write, each with the setting that grants it
 * @param denials  the absolute paths inside the grants that the commands certainly may not write
 * @param problems what CAT could not read, each as a sentence that names the file or the setting, so the user can tell
 *                 that the grants are incomplete
 */
public record SandboxWrites(List<WriteGrant> grants, List<Path> denials, List<String> problems)
{
	/**
	 * Grants nothing.
	 */
	public static final SandboxWrites NONE = new SandboxWrites(List.of(), List.of(), List.of());

	/**
	 * Creates the writes.
	 *
	 * @param grants   the folders that the commands may write, each with the setting that grants it
	 * @param denials  the absolute paths inside the grants that the commands certainly may not write
	 * @param problems what CAT could not read
	 * @throws NullPointerException     if any argument is null or a list contains null
	 * @throws IllegalArgumentException if a denial is relative or a problem is blank
	 */
	public SandboxWrites
	{
		grants = List.copyOf(grants);
		denials = List.copyOf(denials);
		problems = List.copyOf(problems);
		for (Path denial : denials)
			requireThat(denial, "denial").isAbsolute();
		for (String problem : problems)
			requireThat(problem, "problem").isNotBlank();
	}

	/**
	 * Creates writes that report no problem.
	 *
	 * @param grants  the folders that the commands may write, each with the setting that grants it
	 * @param denials the absolute paths inside the grants that the commands certainly may not write
	 * @throws NullPointerException     if any argument is null or a list contains null
	 * @throws IllegalArgumentException if a denial is relative
	 */
	public SandboxWrites(List<WriteGrant> grants, List<Path> denials)
	{
		this(grants, denials, List.of());
	}
}
