package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents command-line settings for a JSONL bridge that serves one or more turns through one harness process.
 *
 * @param artifact the complete harness artifact
 * @param project the source project
 * @param runRoot the absolute owned-run root
 * @param executable the harness executable
 * @param model the selected model
 * @param effort the selected reasoning effort, or {@value #NO_REASONING} for none
 * @param output the optional durable bridge-transcript destination
 * @param keepRun indicates whether successful runs are retained
 * @param receipt the optional retained-evidence receipt destination
 * @param sessionTranscripts indicates whether the harness writes its native session transcripts, which the run copies
 *   into its kept artifacts. A run leaves no harness transcript on disk otherwise.
 * @param installCat indicates whether CAT is installed in the isolated harness home
 * @param includeUserRules indicates whether CAT copies the parent's personal rules into the isolated harness home
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
 */
public record HarnessRunnerOptions(Path artifact, Path project, Path runRoot, String executable, String model,
	String effort,
	Path output, boolean keepRun, Path receipt, boolean sessionTranscripts, boolean installCat,
	boolean includeUserRules, int maximumConcurrency, List<Path> deniedReads, List<Path> readOnlyEvidence,
	Path workspace, List<Path> plugins, int sessionRequests, Duration capacityWait, int requestDepth, Path input)
{
	/**
	 * The reasoning effort that selects no reasoning, for every model. Each runner passes it to its harness as that
	 * harness's explicit setting for no reasoning, so that the harness never applies its default effort.
	 */
	public static final String NO_REASONING = "none";

	/**
	 * Parses the legacy {@code serve} bridge command while runners migrate to unprefixed bridge options.
	 *
	 * @param args the legacy command and its options
	 * @param defaultExecutable the harness executable name
	 * @param temporaryRoot where the runs are kept when the command line names no {@code --run-root}
	 * @return parsed runner options
	 * @throws IllegalArgumentException if the command is not {@code serve}, if its options are invalid, or if it names
	 *   no {@code --run-root} outside a harness session
	 */
	public static HarnessRunnerOptions parseServe(String[] args, String defaultExecutable,
		HarnessTemporaryRoot temporaryRoot)
	{
		requireThat(args, "args").isNotNull();
		requireThat(defaultExecutable, "defaultExecutable").isNotBlank();
		if (args.length == 0 || !args[0].equals("serve"))
			throw new IllegalArgumentException("Usage: <runner> serve --artifact <path> [options]");
		return parse(Arrays.copyOfRange(args, 1, args.length), defaultExecutable, temporaryRoot);
	}

	/**
	 * Parses one bridge invocation. Input is always the JSONL turn/complete protocol on standard input, so a one-turn
	 * invocation sends one {@code turn} message followed by {@code complete}.
	 *
	 * @param args command-line arguments
	 * @param defaultExecutable the harness executable name
	 * @param temporaryRoot where the runs are kept when the command line names no {@code --run-root}
	 * @return parsed runner options
	 * @throws IllegalArgumentException if arguments are incomplete or invalid, if they name no {@code --model} or no
	 *   {@code --effort}, or if they name no {@code --run-root} outside a harness session
	 */
	public static HarnessRunnerOptions parse(String[] args, String defaultExecutable,
		HarnessTemporaryRoot temporaryRoot)
	{
		requireThat(args, "args").isNotNull();
		requireThat(defaultExecutable, "defaultExecutable").isNotBlank();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		Path project = Path.of("").toAbsolutePath().normalize();
		String explicitRunRoot = null;
		String executable = defaultExecutable;
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
			if (option.equals("--exclude-user-rules"))
			{
				options.includeUserRules = false;
				index += 1;
				continue;
			}
			if (option.equals("--max-concurrency"))
			{
				if (index + 1 >= args.length)
					throw new IllegalArgumentException("Missing value for --max-concurrency");
				options.maximumConcurrency = Integer.parseInt(args[index + 1]);
				if (options.maximumConcurrency < 1 ||
					options.maximumConcurrency > HarnessCapacity.MAXIMUM_CONCURRENCY)
					throw new IllegalArgumentException("--max-concurrency must be between 1 and " +
						HarnessCapacity.MAXIMUM_CONCURRENCY);
				index += 2;
				continue;
			}
			if (option.equals("--read-only-evidence") &&
				(index + 1 >= args.length || args[index + 1].startsWith("--")))
				throw new IllegalArgumentException("Missing path after --read-only-evidence");
			if (index + 1 >= args.length)
				throw new IllegalArgumentException("Missing value for " + option);
			String value = args[index + 1];
			if (option.equals("--session-requests"))
			{
				options.sessionRequests = Integer.parseInt(value);
				if (options.sessionRequests < 0)
					throw new IllegalArgumentException("--session-requests must not be negative: " + value);
				index += 2;
				continue;
			}
			if (option.equals("--request-depth"))
			{
				options.requestDepth = Integer.parseInt(value);
				if (options.requestDepth < 0)
					throw new IllegalArgumentException("--request-depth must not be negative: " + value);
				index += 2;
				continue;
			}
			if (option.equals("--capacity-wait"))
			{
				long seconds = Long.parseLong(value);
				if (seconds < 1)
					throw new IllegalArgumentException("--capacity-wait must be at least 1 second: " + value);
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
			else if (option.equals("--executable"))
				executable = value;
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
			else if (option.equals("--input"))
				options.input = Path.of(value);
			else
				throw new IllegalArgumentException("Unsupported option: " + option);
			index += 2;
		}
		if (options.artifact == null)
			throw new IllegalArgumentException(usage(defaultExecutable + "-runner"));
		if (options.receipt != null && (!options.keepRun || options.output == null))
			throw new IllegalArgumentException("--receipt requires --keep-run and --output");
		if (options.sessionTranscripts && !options.keepRun)
			throw new IllegalArgumentException("--session-transcripts requires --keep-run");
		if (!options.installCat && !options.includeUserRules)
			throw new IllegalArgumentException("--exclude-user-rules requires --install-cat");
		if (options.requestDepth > 0 && options.sessionRequests == 0)
			throw new IllegalArgumentException("--request-depth requires --session-requests");
		requireProfile(defaultExecutable, model, effort);
		Path runRoot = temporaryRoot.runRoot(explicitRunRoot);
		return new HarnessRunnerOptions(options.artifact, project, runRoot, executable, model, effort, options.output,
			options.keepRun, options.receipt, options.sessionTranscripts, options.installCat, options.includeUserRules,
			options.maximumConcurrency, options.deniedReads, options.readOnlyEvidence, options.workspace, options.plugins,
			options.sessionRequests, options.capacityWait, options.requestDepth, options.input);
	}

	/**
	 * Returns a runner command that names an artifact.
	 *
	 * @param args     the runner command, optionally starting with {@code serve}
	 * @param artifact the artifact to name when the command names none
	 * @return {@code args} if it names {@code --artifact}; otherwise {@code args} with {@code --artifact <artifact>}
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
				throw new IllegalArgumentException("--agent cannot be combined with --model or --effort: the CAT agent " +
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
		throw new IllegalArgumentException("Missing " + missing + ". A run requires both --model and --effort." +
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
		return "Usage: " + launcherName + " [--artifact <path>] [--project <path>] [--run-root <path>] " +
			"[--executable <path>] (--model <name> --effort <level> | --agent <name>) [--output <path>] [--keep-run] " +
			"[--receipt <path>] " +
			"[--session-transcripts] [--install-cat] [--exclude-user-rules] [--max-concurrency <1..16>] " +
			"[--deny-read <path>]... [--read-only-evidence <path>]... [--workspace <path>] [--plugin <path>]... " +
			"[--session-requests <count>] [--request-depth <levels>] [--input <file>] [--capacity-wait <seconds>]";
	}

	/**
	 * Validates parsed runner options.
	 *
	 * @param artifact the complete harness artifact
	 * @param project the source project
	 * @param runRoot the absolute owned-run root
	 * @param executable the harness executable
	 * @param model the selected model
	 * @param effort the selected reasoning effort
	 * @param output the optional durable bridge-transcript destination
	 * @param keepRun whether successful runs are retained
	 * @param receipt the optional retained-evidence receipt destination
	 * @param sessionTranscripts whether the harness writes its native session transcripts for the run to copy
	 * @param installCat whether CAT is installed in the isolated harness home
	 * @param includeUserRules whether CAT copies the parent's personal rules into the isolated harness home
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
	 * @throws NullPointerException if any of the arguments are null, other than {@code output}, {@code receipt},
	 *   {@code workspace}, {@code capacityWait} and {@code input}
	 * @throws IllegalArgumentException if {@code executable}, {@code model} or {@code effort} is blank, {@code runRoot}
	 *   or an element of {@code deniedReads} or {@code readOnlyEvidence} is not absolute, {@code sessionRequests} or
	 *   {@code requestDepth} is negative, {@code requestDepth} is positive while {@code sessionRequests} is zero, or
	 *   {@code capacityWait} is not positive
	 */
	public HarnessRunnerOptions
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(executable, "executable").isNotBlank();
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
		private boolean includeUserRules = true;
		private final List<Path> deniedReads = new ArrayList<>();
		private final List<Path> readOnlyEvidence = new ArrayList<>();
		private Path workspace;
		private final List<Path> plugins = new ArrayList<>();
		private int sessionRequests;
		private Duration capacityWait;
		private int requestDepth;
		private Path input;
	}
}
