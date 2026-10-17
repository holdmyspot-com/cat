package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A symbolic link in a prompt test's {@code workspace/} directory whose target is a file or directory of the project
 * outside every test root. Each trial receives a copy of the target as the trial's candidate stages it.
 *
 * @param path   where the copy lands, relative to the trial's workspace root, with {@code /} separators; it may contain
 *               placeholders
 * @param target the link's resolved target, relative to the project
 */
public record ProjectLink(String path, Path target)
{
	/**
	 * Creates a project link.
	 *
	 * @param path   where the copy lands, relative to the trial's workspace root, with {@code /} separators; it may
	 *               contain placeholders
	 * @param target the link's resolved target, relative to the project
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code path} is blank, or {@code target} is absolute, empty or leaves the
	 *                                  project
	 */
	public ProjectLink
	{
		requireThat(path, "path").isNotBlank();
		requireThat(target.isAbsolute(), "target.isAbsolute()").isFalse();
		Path normalized = target.normalize();
		if (normalized.toString().isEmpty() || normalized.startsWith(".."))
			throw new IllegalArgumentException("target must name a file or directory inside the project: " + target);
	}
}
