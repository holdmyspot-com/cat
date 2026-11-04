package com.holdmyspot.cat.common.updater.engine.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for the stable launcher's part of a runtime replacement, which runs after the updater's JVM has exited:
 * it reads the swap request that the updater left in the installation's state directory and renames the runtime
 * directories, rewrites {@code current} and deletes the request, as the launcher does after a successful check of the
 * new runtime. It can also leave a request as a launcher that stopped would.
 */
public final class LauncherSwap
{
	/**
	 * The installation-relative swap request.
	 */
	private static final String REQUEST = "state/runtime-swap";
	/**
	 * The swap request line that names the process that finishes the replacement.
	 */
	private static final int LAUNCHER_LINE = 3;

	/**
	 * Prevents construction.
	 */
	private LauncherSwap()
	{
	}

	/**
	 * Finishes the requested replacement as the stable launcher does once the new runtime runs.
	 *
	 * @param data the installation's updater data directory
	 * @throws IOException if no request is pending or a rename fails
	 */
	public static void finish(Path data) throws IOException
	{
		List<String> lines = Files.readAllLines(data.resolve(REQUEST), UTF_8);
		renameOnly(data);
		Files.writeString(data.resolve("current"), lines.get(2) + "\n", UTF_8);
		Files.delete(data.resolve(REQUEST));
		Files.deleteIfExists(data.resolve("state/runtime-swap-result.json"));
	}

	/**
	 * Renames the runtime directories as the stable launcher does but leaves {@code current} and the request, as a
	 * launcher that stopped before it rewrote {@code current} would.
	 *
	 * @param data the installation's updater data directory
	 * @throws IOException if no request is pending or a rename fails
	 */
	public static void renameOnly(Path data) throws IOException
	{
		if (!Files.exists(data.resolve(REQUEST)))
			throw new IOException("No runtime replacement is pending in " + data);
		Files.move(data.resolve("runtime"), data.resolve("runtime.previous"));
		Files.move(data.resolve("runtime.next"), data.resolve("runtime"));
	}

	/**
	 * Records that the process that was to finish the pending replacement no longer runs, as when the launcher stopped
	 * or failed.
	 *
	 * @param data the installation's updater data directory
	 * @throws IOException if no request is pending
	 */
	public static void abandon(Path data) throws IOException
	{
		// No operating system assigns the largest process identifier
		setLauncher(data, Long.MAX_VALUE, "-");
	}

	/**
	 * Records that the running test process is to finish the pending replacement, as a launcher that is still running
	 * would be.
	 *
	 * @param data the installation's updater data directory
	 * @throws IOException if no request is pending
	 */
	public static void claim(Path data) throws IOException
	{
		ProcessHandle self = ProcessHandle.current();
		setLauncher(data, self.pid(), self.info().startInstant().map(Object::toString).orElse("-"));
	}

	/**
	 * Replaces the launcher identity in the pending request.
	 *
	 * @param data  the installation's updater data directory
	 * @param pid   the launcher's process identifier
	 * @param start the launcher's start instant, or {@code -}
	 * @throws IOException if no request is pending
	 */
	private static void setLauncher(Path data, long pid, String start) throws IOException
	{
		List<String> lines = new ArrayList<>(Files.readAllLines(data.resolve(REQUEST), UTF_8));
		lines.set(LAUNCHER_LINE, Long.toString(pid));
		lines.set(LAUNCHER_LINE + 1, start);
		Files.writeString(data.resolve(REQUEST), String.join("\n", lines) + "\n", UTF_8);
	}

	/**
	 * Writes a pending request whose launcher no longer runs, as an updater, or an earlier build's updater, could
	 * leave it.
	 *
	 * @param data      the installation's updater data directory
	 * @param operation the request's operation
	 * @param from      the version that {@code current} selects
	 * @param to        the version that the request selects
	 * @throws IOException if the request cannot be written
	 */
	public static void request(Path data, String operation, String from, String to) throws IOException
	{
		Files.createDirectories(data.resolve(REQUEST).getParent());
		Files.writeString(data.resolve(REQUEST), operation + "\n" + from + "\n" + to + "\n" + Long.MAX_VALUE +
			"\n-\n-\n", UTF_8);
	}
}
