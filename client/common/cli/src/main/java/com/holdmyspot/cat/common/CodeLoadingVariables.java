package com.holdmyspot.cat.common;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The environment variables that make a program load code that its caller chose: the dynamic linker's preloads, audit
 * libraries and library folders, the scripts that a non-interactive shell runs first, and the Java option variables.
 * A runner launcher that a harness session runs treats its environment as the session's, so its script removes these
 * before Java starts, and CAT removes them again from the environment of every program that it chooses to start,
 * through {@link #start(ProcessBuilder)} or {@link ExecutableFiles}. A command that the user or the session names, such
 * as one that a CAT tool runs for the session inside its sandbox, keeps them.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class CodeLoadingVariables
{
	/**
	 * The names of the variables, other than those that start with {@value #DYLD_PREFIX}.
	 */
	public static final Set<String> NAMES = Set.of("LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "BASH_ENV", "ENV",
		"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "JLINK_VM_OPTIONS");
	/**
	 * The prefix of the variables of the macOS dynamic linker, each of which is removed.
	 */
	public static final String DYLD_PREFIX = "DYLD_";

	/**
	 * Prevents instantiation.
	 */
	private CodeLoadingVariables()
	{
	}

	/**
	 * Removes the variables that make a program load code that its caller chose from an environment.
	 *
	 * @param environment the environment, such as a {@link ProcessBuilder#environment() process builder's}
	 * @throws NullPointerException          if {@code environment} is null
	 * @throws UnsupportedOperationException if {@code environment} holds such a variable and cannot be changed
	 */
	public static void removeFrom(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		boolean windows = Platform.current().isWindows();
		environment.keySet().removeIf(name ->
		{
			String key;
			if (windows)
				key = name.toUpperCase(Locale.ROOT);
			else
				key = name;
			return NAMES.contains(key) || key.startsWith(DYLD_PREFIX);
		});
	}

	/**
	 * Starts a process without the variables that make a program load code that its caller chose.
	 *
	 * @param builder the configured process builder, whose environment loses those variables
	 * @return the started process
	 * @throws NullPointerException if {@code builder} is null
	 * @throws IOException          if the process cannot be started
	 */
	public static Process start(ProcessBuilder builder) throws IOException
	{
		requireThat(builder, "builder").isNotNull();
		removeFrom(builder.environment());
		return builder.start();
	}
}
