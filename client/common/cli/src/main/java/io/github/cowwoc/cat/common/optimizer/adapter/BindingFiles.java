package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * Locates the files of a harness artifact that bind a trial profile.
 */
public final class BindingFiles
{
	/**
	 * The agent definition whose binding is the role profile: CAT's highest implementer tier.
	 */
	public static final String ROLE_AGENT = "optimize-xhigh";

	/**
	 * Prevents construction of utility class instances.
	 */
	private BindingFiles()
	{
	}

	/**
	 * Indicates whether a directory or one of its ancestors has a name.
	 *
	 * @param directory a directory, or {@code null}
	 * @param name the name
	 * @return {@code true} if a path element of {@code directory} is {@code name}
	 * @throws NullPointerException if {@code name} is null
	 */
	public static boolean hasAncestor(Path directory, String name)
	{
		requireThat(name, "name").isNotNull();
		if (directory == null)
			return false;
		for (Path element : directory)
		{
			if (element.toString().equals(name))
				return true;
		}
		return false;
	}
}
