package com.holdmyspot.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.credential.ClaudeCredentialFile;
import com.holdmyspot.cat.claude.credential.CredentialRefusedException;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code claude-credential} command, which saves, reports and removes CAT's Claude credential file of a source
 * home, as {@link ClaudeCredentialFile} reads and writes it. It never takes the token as an argument and never prints
 * it.
 */
public final class ClaudeCredentialCli
{
	/**
	 * The command's name, as its user runs it.
	 */
	private static final String COMMAND = "claude-credential";
	/**
	 * The option that names the source home.
	 */
	private static final String HOME_OPTION = "--home";
	/**
	 * The option, always the last argument, that asks for one JSON line instead of a report for a person.
	 */
	private static final String JSON_OPTION = "--json";
	/**
	 * The exit status of a form that saved the token, found a usable file or left no file.
	 */
	private static final int SUCCESS = 0;
	/**
	 * The exit status of a read or write that failed, which leaves the earlier file unchanged.
	 */
	private static final int FAILED = 1;
	/**
	 * The exit status of a refusal before any change, and of a {@code status} that finds no usable file.
	 */
	private static final int REFUSED = 2;
	/**
	 * The command's usage, which {@code --help} prints and a refusal of its arguments repeats.
	 */
	private static final String USAGE = """
		Usage: claude-credential <form> [--home <dir>] [--json]

		Saves, reports and removes CAT's Claude credential file, .cat/claude/.credentials.json in the source home,
		whose token CAT's Claude sessions use when the source home's Claude settings name no other credential.

		Forms:
		  set     Reads one token, an Anthropic API key (sk-ant-api...) or a token that claude setup-token printed
		          (sk-ant-oat...), and saves it. From a terminal it asks without showing what you paste; otherwise it
		          reads standard input. It never takes the token as an argument.
		          Example: claude-credential set
		  status  Reports whether a launch from the source home can use the file.
		          Example: claude-credential status --home /home/user
		  remove  Deletes the file, and .cat/claude when that leaves it empty.
		          Example: claude-credential remove --json

		Options:
		  --home <dir>  The source home, an absolute path. The default is your home folder; CLAUDE_CONFIG_DIR does not
		                change it.
		  --json        As the last argument: print one JSON line instead of a report.

		Exit status: 0 when set saved the token, status found a usable file or remove left no file; 2 for a refusal
		before any change, or when status finds no usable file; 1 when a read or write failed.
		""";
	/**
	 * What the terminal asks for when standard input is a terminal.
	 */
	private static final String PROMPT = "Paste the token that claude setup-token printed, or an Anthropic API key: ";

	/**
	 * Prevents construction.
	 */
	private ClaudeCredentialCli()
	{
	}

	/**
	 * Runs one form of the command and exits with its status.
	 *
	 * @param args the command's arguments
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		Platform platform = Platform.current();
		Console console = System.console();
		// A console whose input or output is redirected cannot hide what the user types
		if (console != null && !console.isTerminal())
			console = null;
		System.exit(run(args, platform.environment(System.getenv()), platform, console, System.in,
			FileReplacer.retrying(Pause::sleeping), System.out, System.err));
	}

	/**
	 * Runs one form of the command:
	 * <ul>
	 *   <li>{@code set} reads one token, from {@code terminal} without echoing it or else from {@code input} to its end,
	 *   at most {@value ClaudeCredentialFile#MAXIMUM_BYTES} bytes, removes surrounding whitespace, line ends included,
	 *   and saves it, reporting its class, the file's path and whether it replaced an earlier token.</li>
	 *   <li>{@code status} reports {@code usable} with the token's class, {@code absent}, or {@code unusable} with the
	 *   reason that a launch's refusal would give.</li>
	 *   <li>{@code remove} deletes the file, and {@code .cat/claude} when that leaves it empty, reporting
	 *   {@code removed} or {@code absent}.</li>
	 * </ul>
	 * The source home is the absolute folder that {@code --home <dir>} names, or else the home folder that the
	 * environment names; {@code CLAUDE_CONFIG_DIR} never changes it. With {@code --json} as the last argument, every
	 * outcome is one JSON line on {@code stdout} with {@code result}, {@code path} and, as the outcome gives them,
	 * {@code login_class}, {@code replaced} or {@code reason}; otherwise a success's report goes to {@code stdout} and
	 * a refusal's or failure's reason to {@code stderr}. No output holds the token, and a refusal never repeats an
	 * argument.
	 *
	 * @param args        the command's arguments
	 * @param environment the command's environment
	 * @param platform    the platform whose home variable names the default source home
	 * @param terminal    the terminal that asks for the token without echoing it, or {@code null} if standard input is
	 *                    not a terminal
	 * @param input       standard input, which holds the token when {@code terminal} is null
	 * @param replacer    renames the saved file over the earlier one
	 * @param stdout      receives the report, or the JSON line
	 * @param stderr      receives the reason for a refusal or a failure without {@code --json}
	 * @return {@code 0} when {@code set} saved the token, {@code status} found a usable file or {@code remove} left no
	 * 	file; {@code 2} for a refusal before any change, inside a nested session included, or when {@code status} finds
	 * 	no usable file; {@code 1} when a read or write failed, which leaves the earlier file unchanged
	 * @throws NullPointerException if any argument other than {@code terminal} is null
	 */
	public static int run(String[] args, Map<String, String> environment, Platform platform, Console terminal,
		InputStream input, FileReplacer replacer, PrintStream stdout, PrintStream stderr)
	{
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(stdout, "stdout").isNotNull();
		requireThat(stderr, "stderr").isNotNull();
		boolean json = args.length > 0 && args[args.length - 1].equals(JSON_OPTION);
		Report report = new Report(json, JsonMapper.builder().build(), stdout, stderr);

		if (inNestedSession(environment))
		{
			return report.refused(null, COMMAND + " does not run in a nested session: the session must not choose the " +
				"credential that its parent's later launches bill. Run it in your own terminal.");
		}
		if (args.length == 1 && args[0].equals("--help"))
		{
			stdout.print(USAGE);
			return SUCCESS;
		}
		Request request;
		try
		{
			request = Request.parse(args, json, environment, platform);
		}
		catch (IllegalArgumentException e)
		{
			int status = report.refused(null, e.getMessage());
			if (!json)
				stderr.print(System.lineSeparator() + USAGE);
			return status;
		}
		return switch (request.form())
		{
			case SET -> set(request.home(), terminal, input, replacer, report);
			case STATUS -> status(request.home(), report);
			case REMOVE -> remove(request.home(), report);
		};
	}

	/**
	 * Returns whether the command runs in a nested session, as the runner launchers detect one: the nested session's
	 * runner marks the environment of its harness process and the commands that it runs, and names the requests folder
	 * of a nested session that may request sessions.
	 *
	 * @param environment the command's environment
	 * @return {@code true} if the command runs in a nested session
	 */
	private static boolean inNestedSession(Map<String, String> environment)
	{
		boolean marked = HarnessEnvironment.NESTED_SESSION_VALUE.equals(
			environment.get(HarnessEnvironment.NESTED_SESSION_VARIABLE));
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		boolean mayRequest = requests != null && !requests.isBlank();
		return marked || mayRequest;
	}

	/**
	 * Reads one token and saves it.
	 *
	 * @param home     the source home
	 * @param terminal the terminal that asks for the token, or {@code null} to read {@code input}
	 * @param input    standard input
	 * @param replacer renames the saved file over the earlier one
	 * @param report   prints the outcome
	 * @return the exit status
	 */
	private static int set(Path home, Console terminal, InputStream input, FileReplacer replacer, Report report)
	{
		Path file = ClaudeCredentialFile.path(home);
		String token;
		if (terminal == null)
		{
			byte[] bytes;
			try
			{
				bytes = input.readNBytes(ClaudeCredentialFile.MAXIMUM_BYTES + 1);
			}
			catch (IOException e)
			{
				return report.failed(file, "could not read the token from standard input: " + describe(e));
			}
			if (bytes.length > ClaudeCredentialFile.MAXIMUM_BYTES)
			{
				return report.refused(file, "Standard input holds more than " + ClaudeCredentialFile.MAXIMUM_BYTES +
					" bytes, more than any token. Pass only the token.");
			}
			token = new String(bytes, UTF_8);
		}
		else
		{
			char[] typed = terminal.readPassword("%s", PROMPT);
			if (typed == null)
				token = "";
			else
			{
				token = new String(typed);
				Arrays.fill(typed, ' ');
			}
		}

		ClaudeCredentialFile.Saved saved;
		try
		{
			saved = ClaudeCredentialFile.save(report.json(), home, token.strip(), replacer);
		}
		catch (CredentialRefusedException e)
		{
			return report.refused(file, e.getMessage());
		}
		catch (IOException e)
		{
			return report.failed(file, "could not save the token: " + describe(e) + ". The earlier file is " +
				"unchanged.");
		}
		String replacing = "";
		if (saved.replaced())
			replacing = ", replacing the earlier token";
		ObjectNode line = report.line("saved", saved.file());
		line.put("login_class", loginClass(saved.loginClass()));
		line.put("replaced", saved.replaced());
		return report.success(line, "Saved " + kind(saved.loginClass()) + ", class " + loginClass(saved.loginClass()) +
			", to " + saved.file() + replacing + ".");
	}

	/**
	 * Reports whether a launch from the source home can use its credential file.
	 *
	 * @param home   the source home
	 * @param report prints the outcome
	 * @return the exit status
	 */
	private static int status(Path home, Report report)
	{
		Path file = ClaudeCredentialFile.path(home);
		Optional<ClaudeCredentialFile> credential;
		try
		{
			credential = ClaudeCredentialFile.read(report.json(), home);
		}
		catch (CredentialRefusedException e)
		{
			ObjectNode line = report.line("unusable", file);
			line.put("reason", e.getMessage());
			return report.outcome(REFUSED, line, "A launch from " + home + " cannot use CAT's Claude credential " +
				"file: " + e.getMessage());
		}
		catch (IOException e)
		{
			return report.failed(file, "could not read " + file + ": " + describe(e));
		}
		if (credential.isEmpty())
		{
			return report.outcome(REFUSED, report.line("absent", file), "No CAT Claude credential file is at " +
				file + ". Save a token with " + COMMAND + " set " + HOME_OPTION + " " + home + ".");
		}
		HostLogin loginClass = credential.get().loginClass();
		ObjectNode line = report.line("usable", file);
		line.put("login_class", loginClass(loginClass));
		return report.success(line, file + " holds " + kind(loginClass) + ", class " + loginClass(loginClass) +
			", which a launch from " + home + " uses when that home's Claude settings name no other credential.");
	}

	/**
	 * Removes the source home's credential file.
	 *
	 * @param home   the source home
	 * @param report prints the outcome
	 * @return the exit status
	 */
	private static int remove(Path home, Report report)
	{
		Path file = ClaudeCredentialFile.path(home);
		boolean removed;
		try
		{
			removed = ClaudeCredentialFile.remove(home);
		}
		catch (CredentialRefusedException e)
		{
			return report.refused(file, e.getMessage());
		}
		catch (IOException e)
		{
			return report.failed(file, "could not remove " + file + ": " + describe(e));
		}
		if (removed)
			return report.success(report.line("removed", file), "Removed CAT's Claude credential file " + file + ".");
		return report.success(report.line("absent", file), "No CAT Claude credential file was at " + file + ".");
	}

	/**
	 * Returns how a report names a token of a class.
	 *
	 * @param loginClass the token's class
	 * @return the token's kind, with its article
	 */
	private static String kind(HostLogin loginClass)
	{
		if (loginClass == HostLogin.SUBSCRIPTION)
			return "a claude setup-token token";
		return "an Anthropic API key";
	}

	/**
	 * Returns the name of a class as {@code CAT_HOST_LOGIN} and the JSON line spell it.
	 *
	 * @param loginClass the class
	 * @return {@code api_key} or {@code subscription}
	 */
	private static String loginClass(HostLogin loginClass)
	{
		return loginClass.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Describes a failed read or write by the path and the operating system's error, without the token, which no
	 * file-system error holds.
	 *
	 * @param failure the failure
	 * @return the description
	 */
	private static String describe(IOException failure)
	{
		if (failure instanceof FileSystemException fileSystem && fileSystem.getFile() != null)
		{
			String reason = fileSystem.getReason();
			if (reason == null)
			{
				reason = switch (failure)
				{
					case AccessDeniedException _ -> "permission denied";
					case NoSuchFileException _ -> "no such file or folder";
					default -> failure.getClass().getSimpleName();
				};
			}
			return fileSystem.getFile() + ": " + reason;
		}
		return String.valueOf(failure.getMessage());
	}

	/**
	 * The form that the command runs.
	 */
	private enum Form
	{
		/**
		 * Saves a token.
		 */
		SET,
		/**
		 * Reports whether a launch can use the file.
		 */
		STATUS,
		/**
		 * Removes the file.
		 */
		REMOVE
	}

	/**
	 * The form and the source home that the arguments select.
	 *
	 * @param form the form
	 * @param home the absolute source home, which exists
	 */
	private record Request(Form form, Path home)
	{
		/**
		 * Parses the command's arguments.
		 *
		 * @param args        the arguments
		 * @param json        {@code true} if the last argument is {@code --json}
		 * @param environment the command's environment, which names the default source home
		 * @param platform    the platform whose home variable names the default source home
		 * @return the request
		 * @throws IllegalArgumentException if the arguments name no form, hold an argument that is neither an option
		 *                                  nor the source home's path, name the source home twice, without a path or by
		 *                                  a relative path, or hold the JSON option before the last argument, or if the
		 *                                  source home is unknown or not a folder; the message repeats no argument
		 */
		private static Request parse(String[] args, boolean json, Map<String, String> environment, Platform platform)
		{
			int count = args.length;
			if (json)
				count = args.length - 1;
			if (count == 0)
				throw new IllegalArgumentException(COMMAND + " needs a form: set, status or remove.");
			Form form = switch (args[0])
			{
				case "set" -> Form.SET;
				case "status" -> Form.STATUS;
				case "remove" -> Form.REMOVE;
				default -> throw new IllegalArgumentException(COMMAND + " takes set, status or remove as its first " +
					"argument, and the first argument is none of them.");
			};
			Path home = null;
			for (int index = 1; index < count; ++index)
			{
				String argument = args[index];
				if (argument.equals(JSON_OPTION))
				{
					throw new IllegalArgumentException(JSON_OPTION + " must be the last argument of " + COMMAND +
						".");
				}
				if (!argument.equals(HOME_OPTION))
				{
					throw new IllegalArgumentException(COMMAND + " refuses argument " + (index + 1) + ", which is not " +
						"one of its options. It never takes the token as an argument, which process listings and " +
						"shell history would show: run " + COMMAND + " set and paste the token when it asks, or pass " +
						"the token on standard input.");
				}
				if (home != null)
					throw new IllegalArgumentException(HOME_OPTION + " may be given once.");
				if (index + 1 >= count)
					throw new IllegalArgumentException(HOME_OPTION + " needs the source home's absolute path.");
				++index;
				try
				{
					home = Path.of(args[index]);
				}
				catch (InvalidPathException _)
				{
					// The exception's message quotes the argument, which may be a token
					throw new IllegalArgumentException(HOME_OPTION + " names no valid path. Name the source home by " +
						"its absolute path.");
				}
				if (!home.isAbsolute())
				{
					throw new IllegalArgumentException(HOME_OPTION + " names a relative path. Name the source home " +
						"by its absolute path.");
				}
			}
			if (home == null)
			{
				try
				{
					home = platform.homeDirectory(environment);
				}
				catch (IOException e)
				{
					throw new IllegalArgumentException(e.getMessage() + ", so " + COMMAND + " has no default source " +
						"home. Name one with " + HOME_OPTION + " <dir>.", e);
				}
				if (!home.isAbsolute())
				{
					throw new IllegalArgumentException(platform.homeVariable() + " names a relative path. Name the " +
						"source home with " + HOME_OPTION + " <dir>.");
				}
			}
			home = home.normalize();
			if (!Files.isDirectory(home))
			{
				throw new IllegalArgumentException("The source home " + home + " is not a folder. Name an existing " +
					"home folder with " + HOME_OPTION + " <dir>.");
			}
			return new Request(form, home);
		}
	}

	/**
	 * Prints the command's outcome as a report for a person or as one JSON line.
	 *
	 * @param asJson {@code true} to print one JSON line on {@code stdout}
	 * @param json   writes the JSON line and reads and writes the credential file
	 * @param stdout receives the report or the JSON line
	 * @param stderr receives the reason for a refusal or a failure without JSON
	 */
	private record Report(boolean asJson, JsonMapper json, PrintStream stdout, PrintStream stderr)
	{
		/**
		 * Starts the JSON line of an outcome.
		 *
		 * @param result the outcome's result
		 * @param path   the credential file
		 * @return the line, which the caller may extend
		 */
		private ObjectNode line(String result, Path path)
		{
			ObjectNode line = json.createObjectNode();
			line.put("result", result);
			line.put("path", path.toString());
			return line;
		}

		/**
		 * Prints a success.
		 *
		 * @param line   the JSON line
		 * @param report the report for a person
		 * @return {@code 0}
		 */
		private int success(ObjectNode line, String report)
		{
			return outcome(SUCCESS, line, report);
		}

		/**
		 * Prints an outcome on {@code stdout}.
		 *
		 * @param status the exit status
		 * @param line   the JSON line
		 * @param report the report for a person
		 * @return {@code status}
		 */
		private int outcome(int status, ObjectNode line, String report)
		{
			if (asJson)
				stdout.println(json.writeValueAsString(line));
			else
				stdout.println(report);
			return status;
		}

		/**
		 * Prints a refusal before any change.
		 *
		 * @param path   the credential file, or {@code null} if the arguments did not select one
		 * @param reason the reason, which holds neither the token nor an argument
		 * @return {@code 2}
		 */
		private int refused(Path path, String reason)
		{
			return problem(REFUSED, "refused", path, reason);
		}

		/**
		 * Prints a read or write that failed.
		 *
		 * @param path   the credential file
		 * @param reason what failed, with the path and the operating system's error
		 * @return {@code 1}
		 */
		private int failed(Path path, String reason)
		{
			return problem(FAILED, "failed", path, COMMAND + " " + reason);
		}

		/**
		 * Prints a refusal or a failure: the JSON line on {@code stdout}, or the reason on {@code stderr}.
		 *
		 * @param status the exit status
		 * @param result the JSON line's result
		 * @param path   the credential file, or {@code null} if the arguments did not select one
		 * @param reason the reason
		 * @return {@code status}
		 */
		private int problem(int status, String result, Path path, String reason)
		{
			if (asJson)
			{
				ObjectNode line = json.createObjectNode();
				line.put("result", result);
				if (path != null)
					line.put("path", path.toString());
				line.put("reason", reason);
				stdout.println(json.writeValueAsString(line));
			}
			else
				stderr.println(reason);
			return status;
		}
	}
}
