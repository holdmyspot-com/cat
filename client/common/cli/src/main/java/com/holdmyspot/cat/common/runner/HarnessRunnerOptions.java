package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.HomeProject;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Represents command-line settings for a JSONL bridge that serves one or more turns through one harness process.
 *
 * @param artifact the complete harness artifact
 * @param project the source project
 * @param runRoot the absolute owned-run root
 * @param model the selected model
 * @param effort the selected reasoning effort, or {@value #NO_REASONING} for none
 * @param output the optional durable bridge-transcript destination
 * @param keepRun indicates whether successful runs are retained
 * @param receipt the optional retained-evidence receipt destination
 * @param sessionTranscripts indicates whether the harness writes its native session transcripts, which the run copies
 *   into its kept artifacts. A run leaves no harness transcript on disk otherwise.
 * @param installCat indicates whether CAT is installed in the isolated harness home
 * @param maximumConcurrency the shared CAT harness capacity ceiling
 * @param deniedReads the files and directories that the harness sandbox denies the session reading
 * @param readOnlyEvidence the completed evidence files and directories that the session may read but not change, even
 *   where {@code deniedReads} would deny them
 * @param workspace the optional directory whose files are copied into the project on top of {@code project}, refusing
 *   any path that the project holds already
 * @param plugins the trial plugins, plugin directories that are installed into the isolated harness home under their
 *   manifests' names, beside CAT when {@code installCat} is also set
 * @param sessionRequests how many sessions the session may request over the run; {@code 0} allows none
 * @param capacityWait the longest wait for a capacity slot, or {@code null} to wait until one is free
 * @param requestDepth how many levels of requested sessions below the session may request sessions in turn, each
 *   with the session's own {@code sessionRequests}; {@code 0} lets the session's requested sessions request none
 * @param input the file that holds the run's JSON Lines records, or {@code null} to read them from standard input
 * @param conversation the folder of a {@link Conversation} whose messages are the run's records, and to which the
 *   bridge's records go too, or {@code null} if the run holds none
 * @param policyBound the file of the policy that bounds the session's sandbox policy, which the session launcher
 *   writes, or {@code null} if the launch names none
 * @param capacityHeld indicates whether the requester holds the session's capacity slot itself, so that the runner
 *   takes none
 * @param gitName the Git user name of the copied project, or {@code null} to read the source project's
 * @param gitEmail the Git user email of the copied project, or {@code null} to read the source project's
 * @param extraPermissions the file that lists the writes and hosts that the session needs beyond its harness's, in the
 *   format of {@link ExtraPermissions}, or {@code null} if the launch asks for none
 */
public record HarnessRunnerOptions(Path artifact, Path project, Path runRoot, String model, String effort,
	Path output, boolean keepRun, Path receipt, boolean sessionTranscripts, boolean installCat, int maximumConcurrency,
	List<Path> deniedReads, List<Path> readOnlyEvidence, Path workspace, List<Path> plugins, int sessionRequests,
	Duration capacityWait, int requestDepth, Path input, Path conversation, Path policyBound, boolean capacityHeld,
	String gitName, String gitEmail, Path extraPermissions)
{
	/**
	 * The reasoning effort that selects no reasoning, for every model. Each runner passes it to its harness as that
	 * harness's explicit setting for no reasoning, so that the harness never applies its default effort.
	 */
	public static final String NO_REASONING = "none";
	/**
	 * The exit status of a runner launcher that refuses its launch before it does anything else.
	 */
	public static final int REFUSED_LAUNCH_STATUS = 2;
	/**
	 * The option that chooses the CAT artifact that a bridge runs. A runner launcher refuses it, since it runs outside
	 * the sandbox of the agent that starts it.
	 */
	private static final String OVERRIDE_OPTION = "--artifact";
	/**
	 * The options that take a value.
	 */
	private static final Set<String> VALUE_OPTIONS = Set.of("--artifact", "--project", "--run-root", "--model",
		"--effort", "--output", "--receipt", "--deny-read", "--read-only-evidence", "--workspace", "--plugin", "--input",
		"--session-requests", "--request-depth", "--capacity-wait", "--policy-bound", "--git-name", "--git-email",
		ExtraPermissions.OPTION, Conversation.OPTION);
	/**
	 * The name that a candidate CAT's manifest gives it.
	 */
	private static final String CAT_NAME = "cat";

	/**
	 * Parses the legacy {@code serve} bridge command while runners migrate to unprefixed bridge options.
	 *
	 * @param args the legacy command and its options
	 * @param harness the harness that the bridge runs, such as {@code claude} or {@code codex}
	 * @param temporaryRoot where the runs are kept when the command line names no {@code --run-root}
	 * @return parsed runner options
	 * @throws IllegalArgumentException if the command is not {@code serve}, if its options are invalid, or if it names
	 *   no {@code --run-root} outside a harness session
	 */
	public static HarnessRunnerOptions parseServe(String[] args, String harness, HarnessTemporaryRoot temporaryRoot)
	{
		requireThat(args, "args").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		if (args.length == 0 || !args[0].equals("serve"))
			throw new InvalidArgumentsException("Usage: <runner> serve --artifact <path> [options]");
		return parse(Arrays.copyOfRange(args, 1, args.length), harness, temporaryRoot);
	}

	/**
	 * Parses one bridge invocation. Input is always the JSONL turn/complete protocol on standard input, so a one-turn
	 * invocation sends one {@code turn} message followed by {@code complete}.
	 *
	 * @param args command-line arguments
	 * @param harness the harness that the bridge runs, such as {@code claude} or {@code codex}
	 * @param temporaryRoot where the runs are kept when the command line names no {@code --run-root}
	 * @return parsed runner options
	 * @throws IllegalArgumentException if arguments are unsupported, incomplete or invalid, if they name no
	 *   {@code --model} or no {@code --effort}, or if they name no {@code --run-root} outside a harness session
	 */
	public static HarnessRunnerOptions parse(String[] args, String harness, HarnessTemporaryRoot temporaryRoot)
	{
		requireThat(args, "args").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		Path project = Path.of("").toAbsolutePath().normalize();
		String explicitRunRoot = null;
		String model = "";
		String effort = "";
		ParsedOptions options = new ParsedOptions();
		int index = 0;
		while (index < args.length)
		{
			String option = args[index];
			if (option.equals("--keep-run"))
			{
				options.keepRun = true;
				index += 1;
				continue;
			}
			if (option.equals("--session-transcripts"))
			{
				options.sessionTranscripts = true;
				index += 1;
				continue;
			}
			if (option.equals("--install-cat"))
			{
				options.installCat = true;
				index += 1;
				continue;
			}
			if (option.equals("--capacity-held"))
			{
				options.capacityHeld = true;
				index += 1;
				continue;
			}
			if (option.equals("--max-concurrency"))
			{
				if (index + 1 >= args.length)
					throw new InvalidArgumentsException("Missing value for --max-concurrency");
				options.maximumConcurrency = parseInteger(option, args[index + 1]);
				if (options.maximumConcurrency < 1 ||
					options.maximumConcurrency > HarnessCapacity.MAXIMUM_CONCURRENCY)
					throw new InvalidArgumentsException("--max-concurrency must be between 1 and " +
						HarnessCapacity.MAXIMUM_CONCURRENCY);
				index += 2;
				continue;
			}
			if (!VALUE_OPTIONS.contains(option))
				throw new InvalidArgumentsException("Unsupported option: " + option);
			if (option.equals("--read-only-evidence") &&
				(index + 1 >= args.length || args[index + 1].startsWith("--")))
				throw new InvalidArgumentsException("Missing path after --read-only-evidence");
			if (index + 1 >= args.length)
				throw new InvalidArgumentsException("Missing value for " + option);
			String value = args[index + 1];
			if (option.equals("--session-requests"))
			{
				options.sessionRequests = parseInteger(option, value);
				if (options.sessionRequests < 0)
					throw new InvalidArgumentsException("--session-requests must not be negative: " + value);
				index += 2;
				continue;
			}
			if (option.equals("--request-depth"))
			{
				options.requestDepth = parseInteger(option, value);
				if (options.requestDepth < 0)
					throw new InvalidArgumentsException("--request-depth must not be negative: " + value);
				index += 2;
				continue;
			}
			if (option.equals("--capacity-wait"))
			{
				long seconds = parseLong(option, value);
				if (seconds < 1)
					throw new InvalidArgumentsException("--capacity-wait must be at least 1 second: " + value);
				options.capacityWait = Duration.ofSeconds(seconds);
				index += 2;
				continue;
			}
			if (option.equals("--artifact"))
				options.artifact = Path.of(value);
			else if (option.equals("--project"))
				project = Path.of(value);
			else if (option.equals("--run-root"))
				explicitRunRoot = value;
			else if (option.equals("--model"))
				model = value;
			else if (option.equals("--effort"))
				effort = value;
			else if (option.equals("--output"))
				options.output = Path.of(value);
			else if (option.equals("--receipt"))
				options.receipt = Path.of(value);
			else if (option.equals("--deny-read"))
				options.deniedReads.add(Path.of(value));
			else if (option.equals("--read-only-evidence"))
				options.readOnlyEvidence.add(Path.of(value));
			else if (option.equals("--workspace"))
				options.workspace = Path.of(value);
			else if (option.equals("--plugin"))
				options.plugins.add(Path.of(value));
			else if (option.equals("--policy-bound"))
				options.policyBound = Path.of(value);
			else if (option.equals("--git-name"))
				options.gitName = value;
			else if (option.equals("--git-email"))
				options.gitEmail = value;
			else if (option.equals(ExtraPermissions.OPTION))
				options.extraPermissions = Path.of(value);
			else if (option.equals(Conversation.OPTION))
				options.conversation = Path.of(value).toAbsolutePath();
			else
				options.input = Path.of(value);
			index += 2;
		}
		if (options.artifact == null)
			throw new InvalidArgumentsException("Missing --artifact");
		if (options.receipt != null && (!options.keepRun || options.output == null))
			throw new InvalidArgumentsException("--receipt requires --keep-run and --output");
		if (options.sessionTranscripts && !options.keepRun)
			throw new InvalidArgumentsException("--session-transcripts requires --keep-run");
		if (options.requestDepth > 0 && options.sessionRequests == 0)
			throw new InvalidArgumentsException("--request-depth requires --session-requests");
		if (options.input != null && options.conversation != null)
			throw new InvalidArgumentsException(Conversation.OPTION + " and --input cannot be used together");
		requireProfile(harness, model, effort);
		Path runRoot = temporaryRoot.runRoot(explicitRunRoot);
		return new HarnessRunnerOptions(options.artifact, project, runRoot, model, effort, options.output,
			options.keepRun, options.receipt, options.sessionTranscripts, options.installCat, options.maximumConcurrency,
			options.deniedReads, options.readOnlyEvidence, options.workspace, options.plugins, options.sessionRequests,
			options.capacityWait, options.requestDepth, options.input, options.conversation, options.policyBound,
			options.capacityHeld, options.gitName, options.gitEmail, options.extraPermissions);
	}

	/**
	 * Reads the file of extra permissions that a launch names.
	 *
	 * @param json the document mapper
	 * @param file the file that follows {@value ExtraPermissions#OPTION}, or {@code null} if the launch names none
	 * @return the permissions that the file lists, or none if {@code file} is {@code null}
	 * @throws NullPointerException      if {@code json} is null
	 * @throws InvalidArgumentsException if the file does not exist, cannot be read, or is not valid, such as when it
	 *                                   names an unknown field, a relative path or a host pattern
	 */
	public static ExtraPermissions readExtraPermissions(JsonMapper json, Path file)
	{
		requireThat(json, "json").isNotNull();
		if (file == null)
			return ExtraPermissions.NONE;
		try
		{
			return ExtraPermissions.read(json, file.toAbsolutePath());
		}
		catch (IOException e)
		{
			throw new InvalidArgumentsException(e.getMessage());
		}
	}

	/**
	 * Parses the whole-number value of an option.
	 *
	 * @param option the option
	 * @param value  the option's value
	 * @return the number
	 * @throws InvalidArgumentsException if {@code value} is not a whole number
	 */
	public static int parseInteger(String option, String value)
	{
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException _)
		{
			throw new InvalidArgumentsException(option + " requires a whole number: " + value);
		}
	}

	/**
	 * Parses the whole-number value of an option.
	 *
	 * @param option the option
	 * @param value  the option's value
	 * @return the number
	 * @throws InvalidArgumentsException if {@code value} is not a whole number
	 */
	public static long parseLong(String option, String value)
	{
		try
		{
			return Long.parseLong(value);
		}
		catch (NumberFormatException _)
		{
			throw new InvalidArgumentsException(option + " requires a whole number: " + value);
		}
	}

	/**
	 * A runner launcher's launch, which ends with an exit status.
	 */
	@FunctionalInterface
	public interface Launch
	{
		/**
		 * Runs the launch.
		 *
		 * @return the launch's exit status
		 * @throws InvalidArgumentsException if the launch's options are invalid
		 * @throws IOException                if the launch fails
		 */
		int run() throws IOException;
	}

	/**
	 * Runs a runner launcher's launch. A launch whose options are invalid is refused: only the reason is printed, as
	 * one line without a stack trace, and the launch ends with status {@value #REFUSED_LAUNCH_STATUS}, as every other
	 * refused launch does. Any other failure, including an {@link IllegalArgumentException} that the running launch
	 * raises, propagates.
	 *
	 * @param launch the launch
	 * @param errors receives the reason for a refusal
	 * @return the launch's exit status, or {@value #REFUSED_LAUNCH_STATUS} if its options are invalid
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the launch fails
	 */
	public static int refusingInvalidArguments(Launch launch, PrintWriter errors) throws IOException
	{
		requireThat(launch, "launch").isNotNull();
		requireThat(errors, "errors").isNotNull();
		try
		{
			return launch.run();
		}
		catch (InvalidArgumentsException e)
		{
			errors.println(e.getMessage());
			return REFUSED_LAUNCH_STATUS;
		}
	}

	/**
	 * Refuses a runner launcher's launch that names {@code --artifact}. A runner launcher runs outside the sandbox of
	 * the agent that starts it, so it always runs its own CAT artifact: the option would let the agent run code of its
	 * choice outside its sandbox.
	 *
	 * @param harness the harness whose runner the launcher runs, such as {@code claude} or {@code codex}
	 * @param args    the launch's arguments
	 * @param errors  receives the reason for a refusal
	 * @return {@code true} if the launch names the option, after the reason is written to {@code errors}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public static boolean refusesOverride(String harness, String[] args, PrintWriter errors)
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(errors, "errors").isNotNull();
		for (String arg : args)
		{
			if (arg.equals(OVERRIDE_OPTION))
			{
				errors.println(harness + "-runner refuses " + arg + ": it always runs its own CAT artifact and the " +
					harness + " command that the PATH finds. Remove " + arg + ".");
				return true;
			}
		}
		return false;
	}

	/**
	 * Refuses a runner launcher's launch whose project is the user's home folder or contains it, which
	 * {@link HomeProject} refuses as a CAT project. The project is the folder that the launch's {@code --project}
	 * names, resolved against {@code workingDirectory}, or {@code workingDirectory} if the launch names none. A launch
	 * whose environment names no home folder is not refused.
	 *
	 * @param args             the launch's arguments
	 * @param workingDirectory the directory from which the launcher was started
	 * @param environment      the launcher's environment
	 * @param platform         the platform that the launcher runs on, whose home variable names the home folder
	 * @param errors           receives the reason for a refusal
	 * @return {@code true} if the launch is refused, after the reason is written to {@code errors}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean refusesHomeProject(String[] args, Path workingDirectory, Map<String, String> environment,
		Platform platform, PrintWriter errors)
	{
		requireThat(args, "args").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(errors, "errors").isNotNull();
		Optional<String> refusal = homeProjectRefusal(args, workingDirectory, environment, platform);
		if (refusal.isEmpty())
			return false;
		errors.println(refusal.get());
		return true;
	}

	/**
	 * Returns why a runner launcher refuses a launch whose project is the user's home folder or contains it, as
	 * {@link #refusesHomeProject} decides.
	 *
	 * @param args             the launch's arguments
	 * @param workingDirectory the folder against which the launch's {@code --project} resolves, and the project if
	 *                         the launch names none
	 * @param environment      the launcher's environment
	 * @param platform         the platform that the launcher runs on, whose home variable names the home folder
	 * @return the reason, or empty if the launch is not refused
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Optional<String> homeProjectRefusal(String[] args, Path workingDirectory,
		Map<String, String> environment, Platform platform)
	{
		requireThat(args, "args").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		String home = environment.get(platform.homeVariable());
		if (home == null || home.isBlank())
			return Optional.empty();
		Path project = workingDirectory;
		for (int index = 0; index + 1 < args.length; ++index)
		{
			if (args[index].equals("--project"))
				project = workingDirectory.resolve(args[index + 1]);
		}
		return HomeProject.refusal(project, Path.of(home));
	}

	/**
	 * Returns a runner command that names an artifact.
	 *
	 * @param args     the runner command, optionally starting with {@code serve}
	 * @param artifact the artifact to name when the command names none
	 * @return {@code args} if it names {@code --artifact}; otherwise {@code args} with {@code --artifact <artifact>}
	 *   after its command
	 * @throws NullPointerException if any argument is null
	 */
	public static String[] withArtifact(String[] args, Path artifact)
	{
		requireThat(args, "args").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		if (Arrays.asList(args).contains("--artifact"))
			return args;
		List<String> result = new ArrayList<>(Arrays.asList(args));
		int position = 0;
		if (!result.isEmpty() && result.getFirst().equals("serve"))
			position = 1;
		result.addAll(position, List.of("--artifact", artifact.toString()));
		return result.toArray(String[]::new);
	}

	/**
	 * Refuses a launch that selects a CAT agent definition with {@code --agent} and also names {@code --model} or
	 * {@code --effort}, because the agent definition supplies the model and the effort.
	 *
	 * @param args the launch's options
	 * @throws NullPointerException     if {@code args} is null
	 * @throws IllegalArgumentException if {@code args} names {@code --model} or {@code --effort}
	 */
	public static void requireNoProfileBesideAgent(String[] args)
	{
		requireThat(args, "args").isNotNull();
		for (String arg : args)
		{
			if (arg.equals("--model") || arg.equals("--effort"))
			{
				throw new InvalidArgumentsException("--agent cannot be combined with --model or --effort: the CAT agent " +
					"definition supplies the model and the effort.");
			}
		}
	}

	/**
	 * Returns the artifact that a launch names.
	 *
	 * @param args the launch's options
	 * @return the path after {@code --artifact}, or {@code null} if the launch names none
	 * @throws NullPointerException if {@code args} is null
	 */
	public static Path artifactOption(String[] args)
	{
		requireThat(args, "args").isNotNull();
		for (int index = 0; index + 1 < args.length; ++index)
		{
			if (args[index].equals("--artifact"))
				return Path.of(args[index + 1]);
		}
		return null;
	}

	/**
	 * Returns a launch's options with the model and effort that its CAT agent definition names.
	 *
	 * @param args   the launch's options, without {@code --agent}, {@code --model} and {@code --effort}
	 * @param model  the agent definition's model
	 * @param effort the agent definition's reasoning effort
	 * @return {@code args} followed by {@code --model <model> --effort <effort>}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code args} names {@code --model} or {@code --effort}, or {@code model}
	 *                                  or {@code effort} are blank
	 */
	public static String[] withAgentProfile(String[] args, String model, String effort)
	{
		requireNoProfileBesideAgent(args);
		requireThat(model, "model").isNotBlank();
		requireThat(effort, "effort").isNotBlank();
		List<String> result = new ArrayList<>(Arrays.asList(args));
		result.addAll(List.of("--model", model, "--effort", effort));
		return result.toArray(String[]::new);
	}

	/**
	 * Refuses a launch that names no model or no reasoning effort, before it takes a capacity slot or starts a harness.
	 * Neither CAT nor the runner chooses either one for the caller.
	 *
	 * @param harness the harness that the launch starts, such as {@code claude} or {@code codex}
	 * @param model   the model that the launch names, or an empty string if it names none
	 * @param effort  the reasoning effort that the launch names, or an empty string if it names none
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code model} or {@code effort} are blank. The message names each missing
	 *                                  option and where to list the values that the harness supports.
	 */
	public static void requireProfile(String harness, String model, String effort)
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(model, "model").isNotNull();
		requireThat(effort, "effort").isNotNull();
		String missing;
		if (model.isBlank() && effort.isBlank())
			missing = "--model and --effort";
		else if (model.isBlank())
			missing = "--model";
		else if (effort.isBlank())
			missing = "--effort";
		else
			return;
		String values = switch (harness)
		{
			case "claude" -> " `claude --help` lists the models under --model and the efforts under --effort.";
			case "codex" -> " `codex debug models` lists the models and the reasoning efforts that each accepts.";
			default -> "";
		};
		throw new InvalidArgumentsException("Missing " + missing + ". A run requires both --model and --effort." +
			values);
	}

	/**
	 * Returns bridge command usage for one concrete launcher.
	 *
	 * @param launcherName the executable users invoke
	 * @return the complete usage text
	 */
	public static String usage(String launcherName)
	{
		requireThat(launcherName, "launcherName").isNotBlank();
		return "Usage: " + launcherName + " [--project <path>] [--run-root <path>] " +
			"(--model <name> --effort <level> | --agent <name>) [--output <path>] [--keep-run] [--receipt <path>] " +
			"[--session-transcripts] [--install-cat] [--max-concurrency <1..16>] " +
			"[--deny-read <path>]... [--read-only-evidence <path>]... [--workspace <path>] [--plugin <path>]... " +
			"[--session-requests <count>] [--request-depth <levels>] [--input <file> | --conversation <folder>] " +
			"[--capacity-wait <seconds>] [--extra-permissions <file>]";
	}

	/**
	 * Validates parsed runner options.
	 *
	 * @param artifact the complete harness artifact
	 * @param project the source project
	 * @param runRoot the absolute owned-run root
	 * @param model the selected model
	 * @param effort the selected reasoning effort
	 * @param output the optional durable bridge-transcript destination
	 * @param keepRun whether successful runs are retained
	 * @param receipt the optional retained-evidence receipt destination
	 * @param sessionTranscripts whether the harness writes its native session transcripts for the run to copy
	 * @param installCat whether CAT is installed in the isolated harness home
	 * @param maximumConcurrency the shared CAT harness capacity ceiling
	 * @param deniedReads the files and directories that the harness sandbox denies the session reading
	 * @param readOnlyEvidence the completed evidence files and directories that the session may read but not change
	 * @param workspace the optional directory whose files are copied into the project on top of {@code project}
	 * @param plugins the trial plugins, plugin directories that are installed into the isolated harness home under
	 *   their manifests' names
	 * @param sessionRequests how many sessions the session may request over the run
	 * @param capacityWait the longest wait for a capacity slot, or {@code null} to wait until one is free
	 * @param requestDepth how many levels of requested sessions below the session may request sessions in turn
	 * @param input the file that holds the run's JSON Lines records, or {@code null} to read standard input
	 * @param conversation the folder of a conversation whose messages are the run's records, or {@code null} if the
	 *   run holds none
	 * @param policyBound the file of the policy that bounds the session's sandbox policy, or {@code null} if the
	 *   launch names none
	 * @param capacityHeld whether the requester holds the session's capacity slot itself
	 * @param gitName the Git user name of the copied project, or {@code null} to read the source project's
	 * @param gitEmail the Git user email of the copied project, or {@code null} to read the source project's
	 * @param extraPermissions the file that lists the writes and hosts that the session needs beyond its harness's, or
	 *   {@code null} if the launch asks for none
	 * @throws NullPointerException if any of the arguments are null, other than {@code output}, {@code receipt},
	 *   {@code workspace}, {@code capacityWait}, {@code input}, {@code conversation}, {@code policyBound},
	 *   {@code gitName}, {@code gitEmail} and {@code extraPermissions}
	 * @throws IllegalArgumentException if {@code model} or {@code effort} are blank, {@code runRoot} or an element of
	 *   {@code deniedReads} or {@code readOnlyEvidence} is not absolute, {@code sessionRequests} or
	 *   {@code requestDepth} is negative, {@code requestDepth} is positive while {@code sessionRequests} is zero, or
	 *   {@code capacityWait} is not positive
	 */
	public HarnessRunnerOptions
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(model, "model").isNotBlank();
		requireThat(effort, "effort").isNotBlank();
		if (!runRoot.isAbsolute())
			throw new IllegalArgumentException("runRoot must be absolute");
		requireThat(deniedReads, "deniedReads").isNotNull();
		for (Path deniedRead : deniedReads)
		{
			if (!deniedRead.isAbsolute())
				throw new IllegalArgumentException("Each denied read must be absolute: " + deniedRead);
		}
		deniedReads = List.copyOf(deniedReads);
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		for (Path evidence : readOnlyEvidence)
		{
			if (!evidence.isAbsolute())
				throw new IllegalArgumentException("Each read-only evidence path must be absolute: " + evidence);
		}
		readOnlyEvidence = List.copyOf(readOnlyEvidence);
		plugins = List.copyOf(plugins);
		requireThat(sessionRequests, "sessionRequests").isNotNegative();
		if (capacityWait != null)
			requireThat(capacityWait, "capacityWait").isGreaterThan(Duration.ZERO);
		requireThat(requestDepth, "requestDepth").isNotNegative();
		if (requestDepth > 0 && sessionRequests == 0)
			throw new IllegalArgumentException("--request-depth requires --session-requests");
	}

	/**
	 * Returns the artifact that the session installs as CAT. A launch that installs CAT and whose first plugin's
	 * manifest names it {@value #CAT_NAME} trials that plugin as a candidate CAT, which the session installs in place
	 * of {@link #artifact()}, and whose agent definitions it runs. The runner itself always runs {@link #artifact()}.
	 *
	 * @param manifest the path of a plugin's manifest, relative to the plugin directory
	 * @return the first plugin if it is a candidate CAT; otherwise {@link #artifact()}
	 * @throws NullPointerException if {@code manifest} is null
	 * @throws IOException          if the first plugin's manifest cannot be read or names no valid plugin
	 */
	public Path catArtifact(Path manifest) throws IOException
	{
		if (isCandidateCat(installCat, plugins, manifest))
			return plugins.getFirst();
		return artifact;
	}

	/**
	 * Returns the artifact that a launch's session installs as CAT, as {@link #catArtifact(Path)} does, before the
	 * launch's options are parsed.
	 *
	 * @param args     the launch's options
	 * @param manifest the path of a plugin's manifest, relative to the plugin directory
	 * @return the launch's first plugin if it is a candidate CAT; otherwise the path after {@code --artifact}, or
	 *         {@code null} if the launch names none
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the first plugin's manifest cannot be read or names no valid plugin
	 */
	public static Path catArtifact(String[] args, Path manifest) throws IOException
	{
		requireThat(args, "args").isNotNull();
		List<String> arguments = Arrays.asList(args);
		List<Path> plugins = new ArrayList<>();
		int plugin = arguments.indexOf("--plugin");
		if (plugin >= 0 && plugin + 1 < args.length)
			plugins.add(Path.of(args[plugin + 1]));
		if (isCandidateCat(arguments.contains("--install-cat"), plugins, manifest))
			return plugins.getFirst();
		return artifactOption(args);
	}

	/**
	 * Returns the trial plugins that the session installs beside CAT: the plugins other than a candidate CAT, in
	 * order.
	 *
	 * @param manifest the path of a plugin's manifest, relative to the plugin directory
	 * @return the plugins after the first if it is a candidate CAT; otherwise every plugin
	 * @throws NullPointerException if {@code manifest} is null
	 * @throws IOException          if the first plugin's manifest cannot be read or names no valid plugin
	 * @see #catArtifact(Path)
	 */
	public List<Path> trialPlugins(Path manifest) throws IOException
	{
		if (isCandidateCat(installCat, plugins, manifest))
			return plugins.subList(1, plugins.size());
		return plugins;
	}

	/**
	 * Indicates whether a launch trials its first plugin as a candidate CAT.
	 *
	 * @param installCat {@code true} if the launch installs CAT
	 * @param plugins    the launch's plugins, in order
	 * @param manifest   the path of a plugin's manifest, relative to the plugin directory
	 * @return {@code true} if the launch installs CAT and its first plugin's manifest names it {@value #CAT_NAME}
	 * @throws NullPointerException if {@code manifest} is null
	 * @throws IOException          if the first plugin's manifest cannot be read or names no valid plugin
	 */
	private static boolean isCandidateCat(boolean installCat, List<Path> plugins, Path manifest) throws IOException
	{
		requireThat(manifest, "manifest").isNotNull();
		return installCat && !plugins.isEmpty() && PluginManifest.name(plugins.getFirst(), manifest).equals(CAT_NAME);
	}

	/**
	 * Holds the mutable values accumulated while parsing one bridge command.
	 */
	private static final class ParsedOptions
	{
		private Path artifact;
		private Path output;
		private Path receipt;
		private int maximumConcurrency = HarnessCapacity.MAXIMUM_CONCURRENCY;
		private boolean keepRun;
		private boolean sessionTranscripts;
		private boolean installCat;
		private final List<Path> deniedReads = new ArrayList<>();
		private final List<Path> readOnlyEvidence = new ArrayList<>();
		private Path workspace;
		private final List<Path> plugins = new ArrayList<>();
		private int sessionRequests;
		private Duration capacityWait;
		private int requestDepth;
		private Path input;
		private Path conversation;
		private Path policyBound;
		private boolean capacityHeld;
		private String gitName;
		private String gitEmail;
		private Path extraPermissions;
	}
}
