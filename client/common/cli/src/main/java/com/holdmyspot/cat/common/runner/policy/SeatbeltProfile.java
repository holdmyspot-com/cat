package com.holdmyspot.cat.common.runner.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;

/**
 * Writes the Seatbelt profiles that CAT applies on macOS with {@code sandbox-exec}.
 */
public final class SeatbeltProfile
{
	/**
	 * The program that applies a Seatbelt profile on macOS, which Claude Code's sandbox also runs its commands through.
	 */
	public static final Path SANDBOX_EXECUTABLE = Path.of("/usr/bin/sandbox-exec");
	/**
	 * A profile that confines nothing, which shows that {@code sandbox-exec} can apply a profile where CAT runs.
	 */
	public static final String PERMISSIVE = "(version 1)(allow default)";
	/**
	 * The devices that every confined program may write, which the Java runtime and the tools around it open.
	 */
	private static final List<String> WRITABLE_DEVICES = List.of("/dev/null", "/dev/zero", "/dev/dtracehelper");

	/**
	 * Returns the profile that confines a program that CAT runs for its own use: no network, no write outside its scratch
	 * directory, and no read of the denied paths. Seatbelt lets the last rule that matches an operation decide, so each
	 * denial follows the allowance that it narrows, and the scratch directory's allowance follows the denial of all
	 * writes. Everything else stays as the system allows it, so the program reads its input directory and the runtime.
	 *
	 * @param scratch     the program's writable directory, as its canonical absolute path
	 * @param deniedReads the canonical absolute paths that the program may not read, each with everything below it
	 * @return the profile's text
	 * @throws NullPointerException     if an argument is null or {@code deniedReads} holds null
	 * @throws IllegalArgumentException if a path is relative or holds a line break
	 */
	public static String confining(Path scratch, List<Path> deniedReads)
	{
		requireThat(scratch, "scratch").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		StringBuilder profile = new StringBuilder(512);
		profile.append("(version 1)\n(allow default)\n(deny network*)\n(deny file-write*)\n(allow file-write*");
		for (String device : WRITABLE_DEVICES)
			profile.append(" (literal \"").append(device).append("\")");
		profile.append(" (subpath ").append(quote(scratch)).append("))\n");
		for (Path denied : deniedReads)
			profile.append("(deny file-read* (subpath ").append(quote(denied)).append("))\n");
		return profile.toString();
	}

	/**
	 * Quotes a path as a Seatbelt string, so that no character of the path ends the string or starts another rule.
	 *
	 * @param path the absolute path
	 * @return the quoted path
	 * @throws NullPointerException     if {@code path} is null
	 * @throws IllegalArgumentException if the path is relative or holds a line break
	 */
	private static String quote(Path path)
	{
		requireThat(path, "path").isNotNull();
		if (!path.isAbsolute())
			throw new IllegalArgumentException("A Seatbelt profile names absolute paths: " + path);
		String text = path.toString();
		if (text.indexOf('\n') != -1 || text.indexOf('\r') != -1)
			throw new IllegalArgumentException("A Seatbelt profile cannot name a path with a line break: " + path);
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/**
	 * Prevents construction.
	 */
	private SeatbeltProfile()
	{
	}
}
