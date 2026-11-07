package com.holdmyspot.cat.codex.policy;

import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the options of a Codex 0.160.0 command line that decide the session's sandbox policy.
 * <p>
 * The reader knows every option of the {@code codex}, {@code exec}, {@code resume} and {@code fork} commands. It
 * refuses an option it does not know, because Codex could give that option an effect on the sandbox.
 */
public final class CodexCommandLine
{
	/**
	 * The number of values that each option takes, by the option's names.
	 */
	private static final Map<String, Arity> OPTIONS = options();
	/**
	 * The commands whose sessions the reader reads, below the top-level command.
	 */
	private static final Set<String> SESSION_COMMANDS = Set.of("exec", "e", "resume", "fork");
	/**
	 * The commands below {@code exec} whose sessions the reader reads.
	 */
	private static final Set<String> EXEC_COMMANDS = Set.of("resume", "fork");
	/**
	 * The other commands of the top-level command, which run no session that the reader reads.
	 */
	private static final Set<String> OTHER_COMMANDS = Set.of("agents", "tcp-tunnel", "review", "login", "logout",
		"mcp", "mcp-server", "plugin", "remote-control", "app", "completion", "update", "doctor", "sandbox", "debug",
		"execpolicy", "apply", "a", "queue", "archive", "delete", "migrate-rollouts", "unarchive", "cloud",
		"cloud-tasks", "responses-api-proxy", "stdio-to-uds", "exec-server", "features", "help");
	/**
	 * The fix of a refusal of the command line.
	 */
	private static final String START_PLAINLY = "Start Codex as an interactive session or with codex exec, without " +
		"that option, then start a new Codex session";

	/**
	 * Why the reader refuses a hook that runs in Codex's shared background server.
	 */
	private static final String SHARED_SERVER_REASON = "the hook runs in Codex's shared background server, which " +
		"serves more than one session, so CAT cannot tell which session's sandbox applies";
	/**
	 * The fix of the refusal of a hook that runs in Codex's shared background server.
	 */
	private static final String SHARED_SERVER_FIX = "Start Codex with --no-daemon, then start a new Codex session";

	/**
	 * The number of values that an option takes.
	 */
	private enum Arity
	{
		/**
		 * The option takes no value.
		 */
		FLAG,
		/**
		 * The option takes one value.
		 */
		VALUE,
		/**
		 * The option takes one or more values, up to the next option.
		 */
		VALUES
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexCommandLine()
	{
	}

	/**
	 * Returns the options' arities.
	 *
	 * @return the arity of each option, by its names
	 */
	private static Map<String, Arity> options()
	{
		Map<String, Arity> options = new HashMap<>();
		for (String name : List.of("-c", "--config", "-p", "--profile", "-s", "--sandbox", "-C", "--cd", "--add-dir",
			"--enable", "--disable", "-m", "--model", "--local-provider", "-a", "--ask-for-approval", "--remote",
			"--remote-auth-token-env", "--thread-source", "--output-schema", "--color", "-o", "--output-last-message"))
		{
			options.put(name, Arity.VALUE);
		}
		options.put("-i", Arity.VALUES);
		options.put("--image", Arity.VALUES);
		for (String name : List.of("--build-info", "--strict-config", "--oss", "--approve-for-me", "--not-so-yolo",
			"--dangerously-bypass-approvals-and-sandbox", "--yolo", "--dangerously-bypass-hook-trust", "--worktree",
			"--search", "--no-alt-screen", "--no-daemon", "-h", "--help", "-V", "--version", "--skip-git-repo-check",
			"--ephemeral", "--ignore-user-config", "--ignore-rules", "--json", "--experimental-json", "--last", "--all",
			"--include-non-interactive"))
		{
			options.put(name, Arity.FLAG);
		}
		return Map.copyOf(options);
	}

	/**
	 * Indicates whether a command line runs Codex's shared background server, which serves more than one session
	 * and outlives each of its clients.
	 *
	 * @param arguments the arguments of the Codex process after its executable
	 * @return {@code true} if the reader refuses the command line because it runs the shared server; {@code false}
	 *         if it reads, or refuses it for another reason
	 */
	public static boolean runsSharedServer(List<String> arguments)
	{
		try
		{
			parse(arguments);
			return false;
		}
		catch (PolicyRefusal e)
		{
			return e.reason().equals(SHARED_SERVER_REASON);
		}
	}

	/**
	 * Reads a command line.
	 *
	 * @param arguments the arguments of the Codex process after its executable
	 * @return the options that decide the sandbox policy
	 * @throws PolicyRefusal if the command line has an option that the reader does not know, runs against a remote
	 *                       server, or runs Codex's shared background server or another command that runs no session
	 */
	public static CodexLaunch parse(List<String> arguments) throws PolicyRefusal
	{
		List<Level> levels = new ArrayList<>();
		Level level = new Level();
		levels.add(level);
		// 0 at the top-level command, 1 below exec, 2 when the command takes no further subcommand
		int depth = 0;
		boolean onlyPositionals = false;
		for (int index = 0; index < arguments.size(); ++index)
		{
			String argument = arguments.get(index);
			if (onlyPositionals || !argument.startsWith("-") || argument.equals("-"))
			{
				if (depth == 2)
					continue;
				String command = subcommand(argument, depth);
				if (command.isEmpty())
				{
					// The prompt: later words are positional arguments of the same command
					depth = 2;
					continue;
				}
				level = new Level();
				levels.add(level);
				if (command.equals("exec") || command.equals("e"))
					depth = 1;
				else
					depth = 2;
				continue;
			}
			if (argument.equals("--"))
			{
				onlyPositionals = true;
				continue;
			}
			index = readOption(arguments, index, level);
		}
		return combine(levels);
	}

	/**
	 * Returns the subcommand that a positional argument selects.
	 *
	 * @param argument the argument
	 * @param depth    0 at the top-level command, 1 below {@code exec}
	 * @return the subcommand, or an empty string if the argument is the prompt
	 * @throws PolicyRefusal if the argument selects Codex's shared background server or a command that runs no
	 *                       session
	 */
	private static String subcommand(String argument, int depth) throws PolicyRefusal
	{
		if (depth == 1)
		{
			if (EXEC_COMMANDS.contains(argument))
				return argument;
			if (argument.equals("review"))
				throw unsupportedCommand("exec review");
			return "";
		}
		if (SESSION_COMMANDS.contains(argument))
			return argument;
		if (argument.equals(CodexAssumptions.SHARED_SERVER_SUBCOMMAND))
			throw new PolicyRefusal(SHARED_SERVER_REASON, SHARED_SERVER_FIX);
		if (OTHER_COMMANDS.contains(argument))
			throw unsupportedCommand(argument);
		return "";
	}

	/**
	 * Returns the refusal of a Codex command that runs no session that the reader reads.
	 *
	 * @param command the command
	 * @return the refusal
	 */
	private static PolicyRefusal unsupportedCommand(String command)
	{
		return new PolicyRefusal("the hook runs in Codex's \"" + command + "\" command, whose sandbox CAT does not " +
			"read", START_PLAINLY);
	}

	/**
	 * Reads an option and its values.
	 *
	 * @param arguments the arguments
	 * @param index     the index of the option
	 * @param level     the options of the command that the option belongs to
	 * @return the index of the option's last value, or of the option if it takes none
	 * @throws PolicyRefusal if the reader does not know the option, the option lacks a value, or it selects a remote
	 *                       server
	 */
	private static int readOption(List<String> arguments, int index, Level level) throws PolicyRefusal
	{
		String argument = arguments.get(index);
		String name;
		Optional<String> attached;
		int equals = argument.indexOf('=');
		if (argument.startsWith("--") && equals > 0)
		{
			name = argument.substring(0, equals);
			attached = Optional.of(argument.substring(equals + 1));
		}
		else if (!argument.startsWith("--") && argument.length() > 2)
		{
			name = argument.substring(0, 2);
			attached = Optional.of(argument.substring(2));
		}
		else
		{
			name = argument;
			attached = Optional.empty();
		}
		Arity arity = OPTIONS.get(name);
		if (arity == null || (arity == Arity.FLAG && attached.isPresent()))
		{
			throw new PolicyRefusal("Codex runs with the option \"" + argument + "\", which CAT does not know, so " +
				"CAT cannot tell whether it changes the session's sandbox", START_PLAINLY);
		}
		if (arity == Arity.FLAG)
		{
			level.flag(name);
			return index;
		}
		int next = index;
		String value;
		if (attached.isPresent())
			value = attached.get();
		else
		{
			next = index + 1;
			if (next >= arguments.size())
			{
				throw new PolicyRefusal("Codex runs with the option \"" + name + "\" without its value",
					START_PLAINLY);
			}
			value = arguments.get(next);
		}
		level.value(name, value);
		if (arity == Arity.VALUES)
		{
			while (next + 1 < arguments.size() && !arguments.get(next + 1).startsWith("-"))
				++next;
		}
		return next;
	}

	/**
	 * Combines the options of the top-level command and its subcommands the way Codex does.
	 *
	 * @param levels the options of each command, the top-level command first
	 * @return the combined options
	 * @throws PolicyRefusal if a subcommand's sandbox choice is unknown
	 */
	private static CodexLaunch combine(List<Level> levels) throws PolicyRefusal
	{
		List<String> overrides = new ArrayList<>();
		Optional<String> profile = Optional.empty();
		List<String> addDirs = new ArrayList<>();
		boolean ignoreUserConfig = false;
		// The deepest command that chooses a sandbox decides all of it: -s, --approve-for-me and the bypass flag
		Level sandboxChoice = levels.getFirst();
		for (Level level : levels)
		{
			overrides.addAll(level.overrides);
			overrides.addAll(level.toggles);
			if (level.profile.isPresent())
				profile = level.profile;
			addDirs.addAll(level.addDirs);
			ignoreUserConfig |= level.ignoreUserConfig;
			if (level.choosesSandbox())
				sandboxChoice = level;
		}
		if (sandboxChoice.approveForMe)
		{
			overrides.add("approvals_reviewer=\"auto_review\"");
			overrides.add("approval_policy=\"on-request\"");
			overrides.add("sandbox_mode=\"workspace-write\"");
		}
		Optional<String> sandboxMode = sandboxChoice.sandboxMode;
		if (sandboxMode.isPresent() && !Set.of("read-only", "workspace-write", "danger-full-access").
			contains(sandboxMode.get()))
		{
			throw new PolicyRefusal("Codex runs with the sandbox mode \"" + sandboxMode.get() + "\", which CAT does " +
				"not know", START_PLAINLY);
		}
		return new CodexLaunch(overrides, profile, sandboxMode, sandboxChoice.bypassSandbox, addDirs,
			ignoreUserConfig);
	}

	/**
	 * The options of one command of a command line.
	 */
	private static final class Level
	{
		private final List<String> overrides = new ArrayList<>();
		private final List<String> toggles = new ArrayList<>();
		private final List<String> addDirs = new ArrayList<>();
		private Optional<String> profile = Optional.empty();
		private Optional<String> sandboxMode = Optional.empty();
		private boolean approveForMe;
		private boolean bypassSandbox;
		private boolean ignoreUserConfig;

		/**
		 * Creates the options of a command.
		 */
		private Level()
		{
		}

		/**
		 * Records a flag.
		 *
		 * @param name the flag's name
		 */
		private void flag(String name)
		{
			switch (name)
			{
				case "--approve-for-me", "--not-so-yolo" -> approveForMe = true;
				case "--dangerously-bypass-approvals-and-sandbox", "--yolo" -> bypassSandbox = true;
				case "--ignore-user-config" -> ignoreUserConfig = true;
				default ->
				{
					// The flag does not change the sandbox
				}
			}
		}

		/**
		 * Records an option's value.
		 *
		 * @param name  the option's name
		 * @param value the value
		 * @throws PolicyRefusal if the option selects a remote server
		 */
		private void value(String name, String value) throws PolicyRefusal
		{
			switch (name)
			{
				case "-c", "--config" -> overrides.add(value);
				case "--enable" -> toggles.add("features." + value + "=true");
				case "--disable" -> toggles.add("features." + value + "=false");
				case "-p", "--profile" -> profile = Optional.of(value);
				case "-s", "--sandbox" -> sandboxMode = Optional.of(value);
				case "--add-dir" -> addDirs.add(value);
				case "--remote", "--remote-auth-token-env" -> throw new PolicyRefusal("Codex runs against a remote " +
					"server (--remote), whose sandbox CAT cannot read", "Start Codex without --remote, then start " +
					"a new Codex session");
				default ->
				{
					// The option does not change the sandbox
				}
			}
		}

		/**
		 * Indicates whether the command chooses the sandbox.
		 *
		 * @return {@code true} if the command has {@code -s}, {@code --approve-for-me} or the bypass flag
		 */
		private boolean choosesSandbox()
		{
			return sandboxMode.isPresent() || approveForMe || bypassSandbox;
		}
	}
}
