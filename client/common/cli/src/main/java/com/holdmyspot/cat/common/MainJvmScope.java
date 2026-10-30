package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.Map;

/**
 * Supplies production values for {@link JvmScope} from an explicitly supplied process environment.
 */
public final class MainJvmScope extends AbstractJvmScope
{
	/**
	 * Creates an open process scope. Temporary files use the directory that
	 * {@link #processTemporaryDirectory(Map)} returns.
	 *
	 * @param environment the process environment
	 * @param waiting how the process waits between the attempts of a retried operation; an application entry point
	 *   supplies {@code Pause::sleeping}
	 * @throws NullPointerException if any argument is null
	 */
	public MainJvmScope(Map<String, String> environment, Waiting waiting)
	{
		super(processTemporaryDirectory(environment), waiting);
	}

	/**
	 * Resolves the process temporary directory: {@code TMPDIR}, then {@code TEMP}, then {@code java.io.tmpdir}; empty
	 * environment values are skipped.
	 *
	 * @param environment the process environment
	 * @return the configured directory, which need not exist and need not be absolute
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static Path processTemporaryDirectory(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		for (String name : new String[]{"TMPDIR", "TEMP"})
		{
			String value = environment.get(name);
			boolean configured = value != null && !value.isEmpty();
			if (configured)
				return Path.of(value);
		}
		return Path.of(System.getProperty("java.io.tmpdir"));
	}
}
