package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.claude.policy.ClaudeOption.Arity;
import com.holdmyspot.cat.claude.policy.ClaudeOption.Effect;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.holdmyspot.cat.claude.policy.ClaudeOption.Arity.FLAG;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Arity.OPTIONAL_VALUE;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Arity.VALUE;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Arity.VARIADIC;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Effect.ADD_DIRECTORIES;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Effect.DENIED_TOOLS;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Effect.NONE;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Effect.PERMISSION_MODE;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Effect.RESTRICTED;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Effect.SETTINGS;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Effect.SETTING_SOURCES;
import static com.holdmyspot.cat.claude.policy.ClaudeOption.Effect.UNSUPPORTED;

/**
 * The facts about the Claude Code release that CAT reads a session's sandbox policy for: its command-line options,
 * where it looks for settings, and what its sandbox lets commands write without any setting. Each fact was read from
 * that release's {@code --help} output or its bundled program. A later release may change them; correct them here.
 */
final class ClaudeRelease
{
	/**
	 * The Claude Code release that the facts describe.
	 */
	static final String VERSION = "2.1.318";
	/**
	 * The command-line options. Each option of the release's {@code --help} output is listed, followed by the hidden
	 * options that hosts of the Claude Agent SDK pass.
	 */
	static final List<ClaudeOption> OPTIONS = List.of(
		option(VARIADIC, ADD_DIRECTORIES, "--add-dir"),
		option(VALUE, NONE, "--agent"),
		option(VALUE, NONE, "--agents"),
		option(FLAG, NONE, "--allow-dangerously-skip-permissions"),
		option(VARIADIC, NONE, "--allowedTools", "--allowed-tools"),
		option(VALUE, NONE, "--append-system-prompt"),
		option(VALUE, NONE, "--autocompact"),
		option(FLAG, NONE, "--ax-screen-reader"),
		option(FLAG, NONE, "--bg", "--background"),
		// Skips hooks and changes which settings load in ways the help text does not fully state
		option(FLAG, UNSUPPORTED, "--bare"),
		option(VARIADIC, NONE, "--betas"),
		option(FLAG, NONE, "--brief"),
		option(FLAG, NONE, "--chrome"),
		// Runs the session somewhere else, under a sandbox that this computer's settings do not describe
		option(OPTIONAL_VALUE, UNSUPPORTED, "--cloud"),
		option(FLAG, NONE, "-c", "--continue"),
		option(FLAG, NONE, "--dangerously-skip-permissions"),
		option(OPTIONAL_VALUE, NONE, "-d", "--debug"),
		option(VALUE, NONE, "--debug-file"),
		option(FLAG, UNSUPPORTED, "--desktop"),
		option(FLAG, NONE, "--disable-slash-commands"),
		option(VARIADIC, DENIED_TOOLS, "--disallowedTools", "--disallowed-tools"),
		option(VALUE, NONE, "--effort"),
		option(VALUE, UNSUPPORTED, "--environment"),
		option(FLAG, NONE, "--exclude-dynamic-system-prompt-sections"),
		option(VALUE, NONE, "--fallback-model"),
		option(VARIADIC, NONE, "--file"),
		option(FLAG, NONE, "--fork-session"),
		option(FLAG, NONE, "--forward-subagent-text"),
		option(OPTIONAL_VALUE, NONE, "--from-pr"),
		option(FLAG, NONE, "-h", "--help"),
		option(FLAG, NONE, "--ide"),
		option(FLAG, NONE, "--include-hook-events"),
		option(FLAG, NONE, "--include-partial-messages"),
		option(VALUE, NONE, "--input-format"),
		option(VALUE, NONE, "--json-schema"),
		option(VALUE, NONE, "--max-budget-usd"),
		option(VARIADIC, NONE, "--mcp-config"),
		option(VALUE, NONE, "--model"),
		option(VALUE, NONE, "-n", "--name"),
		option(FLAG, NONE, "--no-chrome"),
		option(FLAG, NONE, "--no-session-persistence"),
		option(VALUE, NONE, "--output-format"),
		option(VALUE, PERMISSION_MODE, "--permission-mode"),
		option(VALUE, NONE, "--permission-prompts"),
		option(VALUE, NONE, "--plugin-dir"),
		option(VALUE, NONE, "--plugin-url"),
		option(FLAG, NONE, "-p", "--print"),
		option(OPTIONAL_VALUE, NONE, "--prompt-suggestions"),
		option(OPTIONAL_VALUE, NONE, "--remote-control"),
		option(VALUE, NONE, "--remote-control-session-name-prefix"),
		option(FLAG, NONE, "--replay-user-messages"),
		option(FLAG, RESTRICTED, "--restricted"),
		option(OPTIONAL_VALUE, NONE, "-r", "--resume"),
		// Disables customizations; whether the user's sandbox settings still apply is not stated
		option(FLAG, UNSUPPORTED, "--safe-mode"),
		option(VALUE, NONE, "--session-id"),
		option(VALUE, SETTING_SOURCES, "--setting-sources"),
		option(VALUE, SETTINGS, "--settings"),
		option(FLAG, NONE, "--strict-mcp-config"),
		option(VALUE, NONE, "--system-prompt"),
		option(VALUE, NONE, "--system-prompt-snapshot"),
		option(OPTIONAL_VALUE, UNSUPPORTED, "--teleport"),
		option(FLAG, UNSUPPORTED, "--tmux"),
		option(VARIADIC, NONE, "--tools"),
		option(FLAG, NONE, "--verbose"),
		option(FLAG, NONE, "-v", "--version"),
		// Moves the session into a new folder that the hook environment may not name
		option(OPTIONAL_VALUE, UNSUPPORTED, "-w", "--worktree"),
		// Hidden options
		option(FLAG, NONE, "-d2e", "--debug-to-stderr"),
		option(FLAG, NONE, "--enable-auth-status"),
		option(VALUE, NONE, "--max-thinking-tokens"),
		option(VALUE, NONE, "--max-turns"),
		option(VALUE, NONE, "--permission-prompt-tool"),
		option(VALUE, NONE, "--resume-session-at"),
		option(VALUE, NONE, "--system-prompt-file"),
		option(VALUE, NONE, "--append-system-prompt-file"),
		option(VALUE, UNSUPPORTED, "--managed-settings"),
		option(VALUE, UNSUPPORTED, "--project-config-root"));
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
	 * The settings of each scope that Claude Code loads, by the name that {@code --setting-sources} gives it.
	 */
	static final Map<String, SettingsScope> SETTING_SOURCE_NAMES = Map.of("user", SettingsScope.USER, "project",
		SettingsScope.PROJECT, "local", SettingsScope.LOCAL);

	/**
	 * Prevents instantiation.
	 */
	private ClaudeRelease()
	{
	}

	/**
	 * Returns an option.
	 *
	 * @param arity  how many of the following arguments the option takes
	 * @param effect what the option does to the session's sandbox policy
	 * @param names  the spellings of the option
	 * @return the option
	 */
	private static ClaudeOption option(Arity arity, Effect effect, String... names)
	{
		return new ClaudeOption(List.of(names), arity, effect);
	}
}
