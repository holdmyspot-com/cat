package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Represents command-line settings for a JSONL bridge that serves one or more turns through one harness process.
 *
 * @param artifact the complete harness artifact
 * @param project the source project
 * @param runRoot the absolute owned-run root
 * @param executable the harness executable
 * @param model the optional selected model
 * @param effort the optional selected reasoning effort
 * @param output the optional durable bridge-transcript destination
 * @param keepRun whether successful runs are retained
 * @param receipt the optional retained-evidence receipt destination
 * @param installCat whether CAT is installed in the isolated harness home
 * @param includeUserRules whether CAT copies the parent's personal rules into the isolated harness home
 * @param maximumConcurrency the shared CAT harness capacity ceiling
 */
public record HarnessRunnerOptions(Path artifact, Path project, Path runRoot, String executable, String model,
	String effort,
	Path output, boolean keepRun, Path receipt, boolean installCat, boolean includeUserRules, int maximumConcurrency)
{
	/**
	 * Parses the legacy {@code serve} bridge command while runners migrate to unprefixed bridge options.
	 *
	 * @param args the legacy command and its options
	 * @param defaultExecutable the harness executable name
	 * @param temporaryRoot the CAT work temp directory from the owning tool scope
	 * @return parsed runner options
	 * @throws IllegalArgumentException if the command is not {@code serve} or its options are invalid
	 */
	public static HarnessRunnerOptions parseServe(String[] args, String defaultExecutable, Path temporaryRoot)
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
	 * @param temporaryRoot the CAT work temp directory from the owning tool scope
	 * @return parsed runner options
	 * @throws IllegalArgumentException if arguments are incomplete or invalid
	 */
	public static HarnessRunnerOptions parse(String[] args, String defaultExecutable, Path temporaryRoot)
	{
		requireThat(args, "args").isNotNull();
		requireThat(defaultExecutable, "defaultExecutable").isNotBlank();
		Path project = Path.of("").toAbsolutePath().normalize();
		Path runRoot = HarnessTemporaryRoot.defaultRunRoot(temporaryRoot);
		String executable = defaultExecutable;
		String model = "";
		String effort = "";
		requireThat(executable, "executable").isNotBlank();
		requireThat(model, "model").isNotNull();
		requireThat(effort, "effort").isNotNull();
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
			if (index + 1 >= args.length)
				throw new IllegalArgumentException("Missing value for " + option);
			String value = args[index + 1];
			if (option.equals("--artifact"))
				options.artifact = Path.of(value);
			else if (option.equals("--project"))
				project = Path.of(value);
			else if (option.equals("--run-root"))
				runRoot = HarnessTemporaryRoot.explicitRunRoot(Path.of(value));
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
			else
				throw new IllegalArgumentException("Unsupported option: " + option);
			index += 2;
		}
		if (options.artifact == null)
			throw new IllegalArgumentException(usage(defaultExecutable + "-runner"));
		if (options.receipt != null && (!options.keepRun || options.output == null))
			throw new IllegalArgumentException("--receipt requires --keep-run and --output");
		if (!options.installCat && !options.includeUserRules)
			throw new IllegalArgumentException("--exclude-user-rules requires --install-cat");
		return new HarnessRunnerOptions(options.artifact, project, runRoot, executable, model, effort, options.output,
			options.keepRun, options.receipt, options.installCat, options.includeUserRules, options.maximumConcurrency);
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
		return "Usage: " + launcherName + " --artifact <path> [--project <path>] [--run-root <path>] " +
			"[--executable <path>] [--model <name>] [--effort <level>] [--output <path>] [--keep-run] [--receipt <path>] " +
			"[--install-cat] [--exclude-user-rules] [--max-concurrency <1..16>]";
	}

	/**
	 * Validates parsed runner options.
	 *
	 * @param artifact the complete harness artifact
	 * @param project the source project
	 * @param runRoot the absolute owned-run root
	 * @param executable the harness executable
	 * @param model the optional selected model
	 * @param effort the optional selected reasoning effort
	 * @param output the optional durable bridge-transcript destination
	 * @param keepRun whether successful runs are retained
	 * @param receipt the optional retained-evidence receipt destination
	 */
	public HarnessRunnerOptions
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(model, "model").isNotNull();
		requireThat(effort, "effort").isNotNull();
		if (!runRoot.isAbsolute())
			throw new IllegalArgumentException("runRoot must be absolute");
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
		private boolean installCat;
		private boolean includeUserRules = true;
	}
}
