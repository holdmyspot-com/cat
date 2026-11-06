package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Plays a SessionStart hook in a separate JVM for {@link SessionLauncherStartTest}: it starts a shell that keeps
 * running in place of a session launcher, writes the shell's process identifier, prints a line to its output and
 * error output, and ends.
 */
public final class DetachedStartProcess
{
	/**
	 * The stand-in launcher: it reports whether its input ended, writes to its error output, and keeps running.
	 */
	private static final String LAUNCHER_SCRIPT = "if read line; then echo stdin-had-input; else echo stdin-ended; " +
		"fi; echo launcher-error >&2; exec tail -f /dev/null";

	/**
	 * Prevents instantiation.
	 */
	private DetachedStartProcess()
	{
	}

	/**
	 * Starts the stand-in launcher and ends.
	 *
	 * @param args the launcher's log and the file that receives its process identifier
	 * @throws IOException if the launcher cannot start or its identifier cannot be written
	 */
	public static void main(String[] args) throws IOException
	{
		ProcessHandle launcher = SessionLauncherStart.PROCESSES.start(List.of("/bin/sh", "-c", LAUNCHER_SCRIPT),
			Path.of(args[0]));
		Files.writeString(Path.of(args[1]), String.valueOf(launcher.pid()));
		System.out.println("hook output");
		System.err.println("hook error");
	}
}
