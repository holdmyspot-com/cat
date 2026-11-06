package com.holdmyspot.cat.claude.policy;

import java.nio.file.Path;
import java.util.List;

/**
 * The facts about the Claude Code release that CAT reads a session's sandbox policy for: what it tells the commands
 * that it runs, where it looks for settings, and what its sandbox lets commands write without any setting. Each fact
 * was read from that release's bundled program or observed in the commands that it runs. A later release may change
 * them; correct them here.
 */
final class ClaudeRelease
{
	/**
	 * The Claude Code release that the facts describe.
	 */
	static final String VERSION = "2.1.318";
	/**
	 * The folder of the managed settings on Linux.
	 */
	static final Path LINUX_MANAGED_DIRECTORY = Path.of("/etc/claude-code");
	/**
	 * The folder of the managed settings on macOS.
	 */
	static final Path MACOS_MANAGED_DIRECTORY = Path.of("/Library/Application Support/ClaudeCode");
	/**
	 * The folder of the Windows managed settings, which Claude Code also reads on WSL when an administrator source
	 * asks it to.
	 */
	static final Path WSL_WINDOWS_MANAGED_DIRECTORY = Path.of("/mnt/c/Program Files/ClaudeCode");
	/**
	 * The managed settings file, inside a managed settings folder.
	 */
	static final String MANAGED_FILE = "managed-settings.json";
	/**
	 * The folder of managed settings drop-in files, inside a managed settings folder. Claude Code merges its
	 * {@code .json} files onto the managed settings file in the order of their names.
	 */
	static final String MANAGED_DROP_INS = "managed-settings.d";
	/**
	 * The macOS managed preferences that may hold managed settings, as {@code %s} for the user's name. CAT cannot read
	 * them.
	 */
	static final List<String> MACOS_MANAGED_PREFERENCES = List.of(
		"/Library/Managed Preferences/com.anthropic.claudecode.plist",
		"/Library/Managed Preferences/%s/com.anthropic.claudecode.plist");
	/**
	 * The copy of the settings that the user's organization manages from its server, inside Claude Code's
	 * configuration folder. It is read as managed settings whose precedence over the managed files is not known.
	 */
	static final String REMOTE_SETTINGS = "remote-settings.json";
	/**
	 * The settings key that makes Claude Code on WSL apply the Windows policy chain, including registry policies that
	 * CAT cannot read.
	 */
	static final String WSL_INHERITS_WINDOWS_SETTINGS = "wslInheritsWindowsSettings";
	/**
	 * The folders that the sandbox lets commands write without any setting, other than the working directories.
	 */
	static final List<Path> BUILT_IN_WRITES = List.of(Path.of("/tmp/claude"), Path.of("/private/tmp/claude"));
	/**
	 * The environment variable that names the temporary folder that Claude Code gives commands, which the sandbox lets
	 * them write.
	 */
	static final String TEMPORARY_DIRECTORY_VARIABLE = "CLAUDE_CODE_TMPDIR";
	/**
	 * The paths inside each folder that commands may write that the sandbox keeps them from writing.
	 */
	static final List<String> BUILT_IN_WRITE_DENIALS = List.of(".claude", ".git/hooks", ".git/config");
	/**
	 * The environment variable that Claude Code sets to {@code 1} in every command that it runs.
	 */
	static final String HARNESS_VARIABLE = "CLAUDECODE";
	/**
	 * The environment variable that Claude Code sets to {@code 1} in a command that its sandbox runs.
	 */
	static final String SANDBOX_VARIABLE = "SANDBOX_RUNTIME";
	/**
	 * The environment variable that names the ID of the Claude Code process in the commands that it runs.
	 */
	static final String PROCESS_ID_VARIABLE = "CLAUDE_PID";
	/**
	 * The environment variable that names the session's identifier in the commands that Claude Code runs.
	 */
	static final String SESSION_ID_VARIABLE = "CLAUDE_CODE_SESSION_ID";
	/**
	 * The folder, inside Claude Code's configuration folder, that holds a record of each running session, in a file
	 * named after the ID of its Claude Code process with the suffix {@code .json}. The record's {@code sessionId} is the
	 * session's identifier and its {@code cwd} is the folder that the session started in.
	 */
	static final String SESSION_RECORDS = "sessions";

	/**
	 * Prevents instantiation.
	 */
	private ClaudeRelease()
	{
	}
}
