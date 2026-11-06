package com.holdmyspot.cat.common.runner.launcher.client;

import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The options of one runner launch that the session launcher serves, as the launch names them.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
final class LaunchOptions
{
	/**
	 * The options that take no value.
	 */
	private static final Set<String> FLAGS = Set.of("--keep-run", "--session-transcripts", "--install-cat");
	/**
	 * The options that take a value and may be repeated.
	 */
	private static final Set<String> REPEATED = Set.of("--plugin", "--deny-read", "--read-only-evidence");
	/**
	 * The options that take a value and may appear once.
	 */
	private static final Set<String> SINGLE = Set.of("--artifact", "--project", "--run-root", "--model", "--effort",
		"--agent", "--output", "--receipt", "--input", "--workspace", "--instruction-files", "--session-requests",
		"--request-depth", "--capacity-wait", "--max-concurrency", "--tool-output-token-limit");
	/**
	 * The directory against which relative paths resolve.
	 */
	private final Path workingDirectory;
	/**
	 * The artifact that the launch names, or empty.
	 */
	Optional<Path> artifact = Optional.empty();
	/**
	 * The project directory.
	 */
	Path project;
	/**
	 * The run root that the launch names, or empty.
	 */
	Optional<Path> runRoot = Optional.empty();
	/**
	 * The model, or empty.
	 */
	Optional<String> model = Optional.empty();
	/**
	 * The reasoning effort, or empty.
	 */
	Optional<String> effort = Optional.empty();
	/**
	 * The CAT agent definition, or empty.
	 */
	Optional<String> agent = Optional.empty();
	/**
	 * Where the transcript goes, or empty for the launch's output stream.
	 */
	Optional<Path> output = Optional.empty();
	/**
	 * Where the receipt goes, or empty.
	 */
	Optional<Path> receipt = Optional.empty();
	/**
	 * The file of runner records, or empty when the records come from the launch's input stream.
	 */
	Optional<Path> input = Optional.empty();
	/**
	 * The workspace directory, or empty.
	 */
	Optional<Path> workspace = Optional.empty();
	/**
	 * The instruction-file choice, or empty.
	 */
	Optional<String> instructionFiles = Optional.empty();
	/**
	 * The trial plugins, in order.
	 */
	final List<Path> plugins = new ArrayList<>();
	/**
	 * The denied reads, in order.
	 */
	final List<Path> deniedReads = new ArrayList<>();
	/**
	 * The read-only evidence, in order.
	 */
	final List<Path> readOnlyEvidence = new ArrayList<>();
	/**
	 * Indicates whether the run is kept.
	 */
	boolean keepRun;
	/**
	 * Indicates whether the native session transcripts are kept.
	 */
	boolean sessionTranscripts;
	/**
	 * Indicates whether CAT is installed in the child's home.
	 */
	boolean installCat;
	/**
	 * How many sessions the child may request.
	 */
	int sessionRequests;
	/**
	 * How many levels below the child may request sessions.
	 */
	int requestDepth;
	/**
	 * The longest wait for a capacity slot, or empty.
	 */
	Optional<Duration> capacityWait = Optional.empty();
	/**
	 * The capacity ceiling, or empty.
	 */
	Optional<Integer> maximumConcurrency = Optional.empty();
	/**
	 * The tool output token limit, or empty.
	 */
	Optional<Integer> toolOutputTokenLimit = Optional.empty();

	/**
	 * Creates empty options.
	 *
	 * @param workingDirectory the directory against which relative paths resolve
	 */
	private LaunchOptions(Path workingDirectory)
	{
		this.workingDirectory = workingDirectory;
		this.project = workingDirectory;
	}

	/**
	 * Parses a launch's options.
	 *
	 * @param args             the options
	 * @param workingDirectory the absolute directory against which relative paths resolve
	 * @return the options
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidArgumentsException if an option is unknown, repeated or lacks its value, or a count is invalid
	 */
	static LaunchOptions parse(String[] args, Path workingDirectory)
	{
		requireThat(args, "args").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		LaunchOptions options = new LaunchOptions(workingDirectory);
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			if (!REPEATED.contains(option) && !seen.add(option))
				throw new InvalidArgumentsException("Repeated option: " + option);
			if (FLAGS.contains(option))
			{
				options.flag(option);
				continue;
			}
			if (!REPEATED.contains(option) && !SINGLE.contains(option))
				throw new InvalidArgumentsException("Unsupported option: " + option);
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new InvalidArgumentsException("Missing value for " + option);
			index += 1;
			options.value(option, args[index]);
		}
		return options;
	}

	/**
	 * Applies an option that takes no value.
	 *
	 * @param option the option
	 */
	private void flag(String option)
	{
		switch (option)
		{
			case "--keep-run" -> keepRun = true;
			case "--session-transcripts" -> sessionTranscripts = true;
			case "--install-cat" -> installCat = true;
			default -> throw new AssertionError(option);
		}
	}

	/**
	 * Applies an option that takes a value.
	 *
	 * @param option the option
	 * @param value  its value
	 * @throws InvalidArgumentsException if a count is not a number
	 */
	private void value(String option, String value)
	{
		switch (option)
		{
			case "--artifact" -> artifact = Optional.of(path(value));
			case "--project" -> project = path(value);
			case "--run-root" -> runRoot = Optional.of(path(value));
			case "--model" -> model = Optional.of(value);
			case "--effort" -> effort = Optional.of(value);
			case "--agent" -> agent = Optional.of(value);
			case "--output" -> output = Optional.of(path(value));
			case "--receipt" -> receipt = Optional.of(path(value));
			case "--input" -> input = Optional.of(path(value));
			case "--workspace" -> workspace = Optional.of(path(value));
			case "--instruction-files" -> instructionFiles = Optional.of(value);
			case "--plugin" -> plugins.add(path(value));
			case "--deny-read" -> deniedReads.add(path(value));
			case "--read-only-evidence" -> readOnlyEvidence.add(path(value));
			case "--session-requests" -> sessionRequests = count(option, value);
			case "--request-depth" -> requestDepth = count(option, value);
			case "--capacity-wait" -> capacityWait = Optional.of(Duration.ofSeconds(seconds(option, value)));
			case "--max-concurrency" -> maximumConcurrency = Optional.of(count(option, value));
			case "--tool-output-token-limit" -> toolOutputTokenLimit = Optional.of(count(option, value));
			default -> throw new AssertionError(option);
		}
	}

	/**
	 * Parses the whole-number value of an option.
	 *
	 * @param option the option
	 * @param value  its value
	 * @return the number
	 * @throws InvalidArgumentsException if {@code value} is not a whole number
	 */
	private static int count(String option, String value)
	{
		return HarnessRunnerOptions.parseInteger(option, value);
	}

	/**
	 * Parses the whole-number value of an option.
	 *
	 * @param option the option
	 * @param value  its value
	 * @return the number
	 * @throws InvalidArgumentsException if {@code value} is not a whole number
	 */
	private static long seconds(String option, String value)
	{
		return HarnessRunnerOptions.parseLong(option, value);
	}

	/**
	 * Resolves a path option.
	 *
	 * @param value the option's value
	 * @return the absolute, normalized path
	 */
	private Path path(String value)
	{
		return workingDirectory.resolve(value).toAbsolutePath().normalize();
	}
}
