package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeSandboxSettings.Setting;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessSandbox;
import com.holdmyspot.cat.common.runner.policy.SeatbeltProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides where CAT starts a Claude session, as {@link HarnessSandbox} describes, and which sessions it starts without
 * a sandbox. Claude Code confines a session's commands in a sandbox on Linux and macOS, and has none on native Windows.
 * A session whose model is offered only {@link #READ_ONLY_TOOLS} has no command to confine, so it runs without a
 * sandbox, and the platform decision does not apply to it.
 */
public final class ClaudeSessionSandbox
{
	/**
	 * The most bytes that the path of a temporary folder may have on Linux, so that
	 * {@code <folder>/claude-http-<16 hex digits>.sock} fits the 108-byte limit of a Unix socket path, with its
	 * terminating zero byte.
	 */
	private static final int LINUX_FOLDER_LIMIT = 72;
	/**
	 * The most bytes that the path of a temporary folder may have on macOS, whose socket path limit is 104 bytes.
	 */
	private static final int MACOS_FOLDER_LIMIT = 68;
	/**
	 * The start of every refusal of a launch whose sandbox would not start.
	 */
	private static final String START_PREFIX = "CAT does not start the session: ";
	/**
	 * The tools that read and search files, and the only tools that a session that runs without a sandbox may be
	 * offered: none of them runs a command or writes a file, so Claude Code's permission rules, which govern them
	 * without a sandbox, are the whole of what keeps them out of the paths that the session must not read.
	 */
	public static final List<String> READ_ONLY_TOOLS = List.of("Read", "Grep", "Glob");

	/**
	 * Returns why CAT refuses to start a session that runs inside a sandbox on a platform.
	 *
	 * @param platform the platform that the session would run on
	 * @return the reason, or empty if CAT starts sandboxed Claude sessions on {@code platform}
	 * @throws NullPointerException if {@code platform} is null
	 */
	public static Optional<String> refusal(Platform platform)
	{
		requireThat(platform, "platform").isNotNull();
		if (platform.isWindows())
		{
			return Optional.of(HarnessSandbox.nativeWindowsRefusal("Claude Code",
				"Claude Code has no sandbox on native Windows"));
		}
		return Optional.empty();
	}

	/**
	 * Returns the folder in which Claude Code's sandbox creates its bridge sockets on Linux and macOS: the first of
	 * {@code TMPDIR}, {@code TMP} and {@code TEMP} that the Claude process's environment sets, else {@code /tmp}.
	 *
	 * @param processEnvironment the Claude process's environment
	 * @return the folder
	 * @throws NullPointerException if {@code processEnvironment} is null
	 */
	public static Path temporaryFolder(Map<String, String> processEnvironment)
	{
		requireThat(processEnvironment, "processEnvironment").isNotNull();
		for (String variable : List.of("TMPDIR", "TMP", "TEMP"))
		{
			String value = processEnvironment.get(variable);
			if (value != null && !value.isBlank())
				return Path.of(value);
		}
		return Path.of("/tmp");
	}

	/**
	 * Returns the program and arguments that try to start a sandbox where Claude Code's does: on Linux
	 * {@code bwrap --unshare-user --dev-bind / / true}, on macOS {@code sandbox-exec} with a permissive profile.
	 *
	 * @param platform          the platform that the session would run on
	 * @param path              the Claude process's {@code PATH}
	 * @param bwrapPath         the {@code sandbox.bwrapPath} setting, or {@code null} if no settings file sets it
	 * @param sandboxExecutable the macOS program that applies a Seatbelt profile
	 * @return the command, or empty if the platform has no such program, or the program is missing or not executable
	 * @throws NullPointerException if any of the arguments other than {@code bwrapPath} are null
	 */
	public static Optional<List<String>> probeCommand(Platform platform, String path, Setting bwrapPath,
		Path sandboxExecutable)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(path, "path").isNotNull();
		requireThat(sandboxExecutable, "sandboxExecutable").isNotNull();
		if (platform.isWindows())
			return Optional.empty();
		if (platform.isLinux())
		{
			return bubblewrap(path, bwrapPath).map(bwrap -> List.of(bwrap.toString(), "--unshare-user", "--dev-bind", "/",
				"/", "true"));
		}
		if (!Files.isExecutable(sandboxExecutable))
			return Optional.empty();
		return Optional.of(List.of(sandboxExecutable.toString(), "-p", SeatbeltProfile.PERMISSIVE, "/usr/bin/true"));
	}

	/**
	 * Returns why CAT refuses to start a session whose Claude Code sandbox would not start. A launch is refused, with a
	 * message that names the check, the value found and the fix, for the first of these causes, in this order. On native
	 * Windows, which has no sandbox, it is refused as {@link #refusal} says. On Linux, WSL2 included, and on macOS: a
	 * temporary folder, or a {@code TMPDIR} of a settings file, longer than the sandbox's bridge sockets allow. On Linux:
	 * no executable {@code bwrap}, or a {@code sandbox.bwrapPath} that names no executable file; no executable
	 * {@code socat} in a directory of the {@code PATH}; a temporary folder in which no file could be created; and a
	 * {@code bwrap} that cannot start a sandbox. On macOS: a missing {@code sandbox-exec}, and one that cannot apply a
	 * profile.
	 *
	 * @param platform                the platform that the session would run on
	 * @param path                    the Claude process's {@code PATH}
	 * @param temporaryFolder         the Claude process's temporary folder, as {@link #temporaryFolder} gives it
	 * @param temporaryFolderSetting  the {@code env.TMPDIR} setting of a project or managed settings file, or
	 *                                {@code null} if none sets it
	 * @param bwrapPath               the {@code sandbox.bwrapPath} setting, or {@code null} if none sets it
	 * @param sandboxExecutable       the macOS program that applies a Seatbelt profile
	 * @param temporaryFileFailure    the failure to create a file in {@code temporaryFolder}, or {@code null} if a
	 *                                file could be created
	 * @param probe                   the result of {@link #probeCommand}'s program, or {@code null} if it was not run
	 * @return the reason, or empty if nothing stops the sandbox from starting
	 * @throws NullPointerException if any of the arguments other than {@code temporaryFolderSetting},
	 *   {@code bwrapPath}, {@code temporaryFileFailure} and {@code probe} are null
	 */
	public static Optional<String> startRefusal(Platform platform, String path, Path temporaryFolder,
		Setting temporaryFolderSetting, Setting bwrapPath, Path sandboxExecutable, IOException temporaryFileFailure,
		ProbeResult probe)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(path, "path").isNotNull();
		requireThat(temporaryFolder, "temporaryFolder").isNotNull();
		requireThat(sandboxExecutable, "sandboxExecutable").isNotNull();
		if (platform.isWindows())
			return refusal(platform);
		int limit = MACOS_FOLDER_LIMIT;
		if (platform.isLinux())
			limit = LINUX_FOLDER_LIMIT;
		int length = byteLength(temporaryFolder.toString());
		if (length > limit)
		{
			return Optional.of(START_PREFIX + "Claude Code's sandbox puts its bridge sockets in the temporary folder, and " +
				"its path must be at most " + limit + " bytes long, but the temporary folder " + temporaryFolder + " is " +
				length + " bytes long. Use a shorter temporary folder, then start the session again.");
		}
		if (temporaryFolderSetting != null)
		{
			int settingLength = byteLength(temporaryFolderSetting.value());
			if (settingLength > limit)
			{
				return Optional.of(START_PREFIX + "Claude Code's sandbox may put its bridge sockets in the folder that " +
					"env.TMPDIR names, and its path must be at most " + limit + " bytes long, but " +
					temporaryFolderSetting.file() + " sets env.TMPDIR to " + temporaryFolderSetting.value() + ", which is " +
					settingLength + " bytes long. Remove env.TMPDIR from that file or set it to a shorter folder, then " +
					"start the session again.");
			}
		}
		if (platform.isLinux())
			return linuxRefusal(path, temporaryFolder, bwrapPath, temporaryFileFailure, probe);
		return macosRefusal(sandboxExecutable, probe);
	}

	/**
	 * Returns why CAT refuses to start a session on Linux, beyond the length of the temporary folder.
	 *
	 * @param path                 the Claude process's {@code PATH}
	 * @param temporaryFolder      the Claude process's temporary folder
	 * @param bwrapPath            the {@code sandbox.bwrapPath} setting, or {@code null}
	 * @param temporaryFileFailure the failure to create a file in {@code temporaryFolder}, or {@code null}
	 * @param probe                the result of the bubblewrap probe, or {@code null} if it was not run
	 * @return the reason, or empty if nothing stops the sandbox from starting
	 */
	private static Optional<String> linuxRefusal(String path, Path temporaryFolder, Setting bwrapPath,
		IOException temporaryFileFailure, ProbeResult probe)
	{
		if (bubblewrap(path, bwrapPath).isEmpty())
		{
			if (bwrapPath != null)
			{
				return Optional.of(START_PREFIX + "Claude Code's sandbox needs bubblewrap, but sandbox.bwrapPath in " +
					bwrapPath.file() + " names " + bwrapPath.value() + ", which is not an executable file. Remove the " +
					"setting or make it name an executable bubblewrap file, then start the session again.");
			}
			return Optional.of(START_PREFIX + "Claude Code's sandbox needs bwrap, but none of the PATH directories " + path +
				" holds an executable bwrap file. Install your distribution's bubblewrap package, then start the session " +
				"again.");
		}
		if (HarnessEnvironment.findExecutable("socat", path).isEmpty())
		{
			return Optional.of(START_PREFIX + "Claude Code's sandbox needs socat, but none of the PATH directories " +
				path + " holds an executable socat file. Install your distribution's socat package, then start the " +
				"session again.");
		}
		if (temporaryFileFailure != null)
		{
			return Optional.of(START_PREFIX + "Claude Code's sandbox needs a temporary folder in which it can create a " +
				"file, but CAT could not create a file in the temporary folder " + temporaryFolder + ": " +
				describe(temporaryFileFailure) + ". Create the folder or make it writable, then start the session again.");
		}
		if (probe == null || probe.exitStatus() == 0)
			return Optional.empty();
		return Optional.of(START_PREFIX + "bubblewrap could not start a sandbox: " +
			probeOutcome("bwrap --unshare-user --dev-bind / / true", probe) + ". Claude Code's sandbox needs unprivileged " +
			"user namespaces. On Ubuntu 24.04 and later, AppArmor keeps bubblewrap from creating the namespaces that the " +
			"sandbox needs; let bubblewrap create user namespaces, for example with an AppArmor profile for bwrap, then " +
			"start the session again.");
	}

	/**
	 * Returns why CAT refuses to start a session on macOS, beyond the length of the temporary folder.
	 *
	 * @param sandboxExecutable the program that applies a Seatbelt profile
	 * @param probe             the result of the {@code sandbox-exec} probe, or {@code null} if it was not run
	 * @return the reason, or empty if nothing stops the sandbox from starting
	 */
	private static Optional<String> macosRefusal(Path sandboxExecutable, ProbeResult probe)
	{
		if (!Files.isExecutable(sandboxExecutable))
		{
			return Optional.of(START_PREFIX + "Claude Code's sandbox on macOS needs " + sandboxExecutable + ", which is " +
				"missing or not executable. Restore the file, which macOS provides, then start the session again.");
		}
		if (probe == null || probe.exitStatus() == 0)
			return Optional.empty();
		return Optional.of(START_PREFIX + "Seatbelt could not apply a profile: " +
			probeOutcome("sandbox-exec -p '" + SeatbeltProfile.PERMISSIVE + "' /usr/bin/true", probe) +
			". Claude Code's sandbox needs Seatbelt to apply a profile where CAT runs; start the session from a " +
			"process that may run sandbox-exec, then start the session again.");
	}

	/**
	 * Describes what a probe did.
	 *
	 * @param command the command that the probe ran, as the message shows it
	 * @param probe   the probe's result
	 * @return the description
	 */
	private static String probeOutcome(String command, ProbeResult probe)
	{
		if (probe.exitStatus() == ProbeResult.NOT_FINISHED)
			return "`" + command + "` did not finish: " + probe.output();
		return "`" + command + "` exited " + probe.exitStatus() + " with: " + probe.output();
	}

	/**
	 * Returns the bubblewrap file that Claude Code's sandbox runs: the file that a settings file's
	 * {@code sandbox.bwrapPath} names, as written, else the first {@code bwrap} of the {@code PATH}.
	 *
	 * @param path      the Claude process's {@code PATH}
	 * @param bwrapPath the {@code sandbox.bwrapPath} setting, or {@code null}
	 * @return the executable file, or empty if there is none
	 */
	private static Optional<Path> bubblewrap(String path, Setting bwrapPath)
	{
		if (bwrapPath == null)
			return HarnessEnvironment.findExecutable("bwrap", path);
		try
		{
			Path file = Path.of(bwrapPath.value());
			if (Files.isRegularFile(file) && Files.isExecutable(file))
				return Optional.of(file);
		}
		catch (InvalidPathException _)
		{
			// The value names no file
		}
		return Optional.empty();
	}

	/**
	 * Returns the number of bytes of a path in UTF-8, which is what Linux's and macOS's socket path limits count.
	 *
	 * @param path the path
	 * @return the number of bytes
	 */
	private static int byteLength(String path)
	{
		return path.getBytes(StandardCharsets.UTF_8).length;
	}

	/**
	 * Describes a failure.
	 *
	 * @param failure the failure
	 * @return its type and message
	 */
	private static String describe(IOException failure)
	{
		String message = failure.getMessage();
		if (message == null)
			return failure.getClass().getSimpleName();
		return failure.getClass().getSimpleName() + ": " + message;
	}

	/**
	 * The outcome of a program that tries to start a sandbox.
	 *
	 * @param exitStatus the program's exit status, or {@link #NOT_FINISHED} if it could not be started or did not end
	 * @param output     the program's output, or the reason that it did not finish
	 */
	public record ProbeResult(int exitStatus, String output)
	{
		/**
		 * The exit status of a program that could not be started or did not end in time.
		 */
		public static final int NOT_FINISHED = -1;

		/**
		 * Creates a result.
		 *
		 * @param exitStatus the program's exit status, or {@link #NOT_FINISHED}
		 * @param output     the program's output, or the reason that it did not finish
		 * @throws NullPointerException if {@code output} is null
		 */
		public ProbeResult
		{
			requireThat(output, "output").isNotNull();
		}
	}

	/**
	 * Returns why CAT refuses to start a session without a sandbox, given the tools that its model is offered.
	 *
	 * @param tools the only tools that the session's model is offered, or none if it is offered every tool
	 * @return the reason, which names each tool beyond {@link #READ_ONLY_TOOLS}, or empty if the session may run
	 *   without a sandbox
	 * @throws NullPointerException if {@code tools} is null
	 */
	public static Optional<String> unsandboxedRefusal(List<String> tools)
	{
		requireThat(tools, "tools").isNotNull();
		String offered;
		if (tools.isEmpty())
			offered = "every tool";
		else
		{
			List<String> beyond = new ArrayList<>();
			for (String tool : tools)
			{
				if (!READ_ONLY_TOOLS.contains(tool))
					beyond.add(tool);
			}
			if (beyond.isEmpty())
				return Optional.empty();
			offered = String.join(", ", beyond);
		}
		return Optional.of("CAT starts a Claude Code session without a sandbox only when its model is offered no tool " +
			"beyond " + String.join(", ", READ_ONLY_TOOLS) + ", but this session's model is offered " + offered +
			", so CAT does not start the session.");
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionSandbox()
	{
	}
}
