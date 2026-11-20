package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The launch options that a run's start fixed, which a run keeps in {@value #FILE_NAME} beside its manifest, so that a
 * later runner can continue the run's harness session as the run started it. The options are those that decide what
 * the session's project, plugins, sandbox and tools are; the model, the effort and the options that only describe one
 * launch, such as its output, may differ from one launch of the run to the next.
 * <p>
 * The file holds a JSON object of exactly two fields: {@code schema_version}, which is {@code 1}, and
 * {@code arguments}, the fixed options and their values in the order the launch named them.
 */
public final class NestedSessionLaunch
{
	/**
	 * The name of the file, in the run's directory, that records the launch.
	 */
	public static final String FILE_NAME = "launch.json";
	/**
	 * The options that the run's start fixed and that take a value.
	 */
	public static final Set<String> FIXED_VALUE_OPTIONS = Set.of("--project", "--plugin", "--workspace",
		"--read-only-evidence", "--deny-read", "--instruction-files", "--session-role", "--tool-output-token-limit",
		"--agent");
	/**
	 * The options that the run's start fixed and that take no value.
	 */
	public static final Set<String> FIXED_FLAG_OPTIONS = Set.of("--install-cat");
	private static final int SCHEMA_VERSION = 1;

	/**
	 * Prevents instantiation.
	 */
	private NestedSessionLaunch()
	{
	}

	/**
	 * Returns the options of a launch that its start fixes, with their values, in the order the launch names them.
	 *
	 * @param args the launch's options
	 * @return the fixed options and their values
	 * @throws NullPointerException if {@code args} is null
	 */
	public static List<String> fixedArguments(String[] args)
	{
		requireThat(args, "args").isNotNull();
		List<String> fixed = new ArrayList<>();
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			if (FIXED_FLAG_OPTIONS.contains(option))
				fixed.add(option);
			else if (FIXED_VALUE_OPTIONS.contains(option) && index + 1 < args.length)
			{
				fixed.add(option);
				fixed.add(args[index + 1]);
				++index;
			}
		}
		return fixed;
	}

	/**
	 * Returns the options of a runner that continues a run's harness session: the options that the run's start fixed,
	 * which its {@value #FILE_NAME} records, followed by the runner's own. A runner that names no
	 * {@value RunnerArguments#RESUME_SLOT_OPTION} gets its own options back.
	 *
	 * @param json    the mapper
	 * @param harness the run's harness
	 * @param args    the runner's options
	 * @return the options that the runner runs with
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws InvalidArgumentsException if the runner resumes a run but names no {@code --results} or
	 *                                   {@value RunnerArguments#SLOT_ID_OPTION}, or names an option that the run fixed
	 *                                   at its start
	 * @throws IOException               if the run records no launch, or its record is malformed
	 */
	public static String[] resumedArguments(JsonMapper json, String harness, String[] args) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(args, "args").isNotNull();
		List<String> options = List.of(args);
		if (!options.contains(RunnerArguments.RESUME_SLOT_OPTION))
			return args;
		List<String> fixed = fixedArguments(args);
		if (!fixed.isEmpty())
		{
			throw new InvalidArgumentsException(RunnerArguments.RESUME_SLOT_OPTION + " continues a run that fixed " +
				fixed.getFirst() + " at its start, so the runner cannot name it");
		}
		String resultsFolder = value(options, "--results");
		String slotId = value(options, RunnerArguments.SLOT_ID_OPTION);
		if (resultsFolder == null || slotId == null || !NestedSessionManager.isSlotId(slotId))
		{
			throw new InvalidArgumentsException(RunnerArguments.RESUME_SLOT_OPTION + " requires --results and " +
				RunnerArguments.SLOT_ID_OPTION);
		}
		List<String> resumed = new ArrayList<>(read(json, Path.of(resultsFolder).resolve(harness).resolve(slotId)));
		resumed.addAll(options);
		return resumed.toArray(String[]::new);
	}

	/**
	 * Returns the value of an option.
	 *
	 * @param options the options
	 * @param option  the option
	 * @return the value that follows the option's last occurrence, or {@code null} if no value follows it
	 */
	private static String value(List<String> options, String option)
	{
		int index = options.lastIndexOf(option);
		if (index < 0 || index + 1 >= options.size())
			return null;
		return options.get(index + 1);
	}

	/**
	 * Records a run's fixed launch options in its directory.
	 *
	 * @param json      the mapper
	 * @param directory the run's directory
	 * @param arguments the fixed options and their values
	 * @param replacer  replaces the file in one step
	 * @throws IOException if the file cannot be written
	 */
	static void write(JsonMapper json, Path directory, List<String> arguments, FileReplacer replacer)
		throws IOException
	{
		assert json != null;
		assert directory != null;
		assert arguments != null;
		assert replacer != null;
		ObjectNode launch = json.createObjectNode();
		launch.put("schema_version", SCHEMA_VERSION);
		ArrayNode values = launch.putArray("arguments");
		for (String argument : arguments)
			values.add(argument);
		Path temporary = Files.createTempFile(directory, FILE_NAME, ".tmp");
		try
		{
			Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(launch) + "\n", UTF_8);
			replacer.replace(temporary, directory.resolve(FILE_NAME));
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Reads a run's fixed launch options.
	 *
	 * @param json      the mapper
	 * @param directory the run's directory
	 * @return the fixed options and their values
	 * @throws IOException if the run records no launch, or its record is malformed
	 */
	static List<String> read(JsonMapper json, Path directory) throws IOException
	{
		assert json != null;
		assert directory != null;
		Path file = directory.resolve(FILE_NAME);
		if (Files.notExists(file))
			throw new IOException("The run records no launch: " + file + " does not exist");
		JsonNode launch;
		try
		{
			launch = json.readTree(Files.readString(file, UTF_8));
		}
		catch (JacksonException e)
		{
			throw new IOException("Malformed " + file + ": " + e.getOriginalMessage(), e);
		}
		if (!launch.isObject() || !Set.copyOf(launch.propertyNames()).equals(Set.of("schema_version", "arguments")))
		{
			throw new IOException("Malformed " + file + ": it must hold exactly the fields schema_version and " +
				"arguments");
		}
		JsonNode version = launch.path("schema_version");
		if (!version.isInt() || version.intValue() != SCHEMA_VERSION)
			throw new IOException("Unsupported " + file + ": schema_version must be " + SCHEMA_VERSION);
		JsonNode values = launch.path("arguments");
		if (!values.isArray())
			throw new IOException("Malformed " + file + ": arguments must be an array of strings");
		List<String> arguments = new ArrayList<>();
		for (JsonNode value : values)
		{
			if (!value.isString())
				throw new IOException("Malformed " + file + ": arguments must be an array of strings");
			arguments.add(value.stringValue());
		}
		return List.copyOf(arguments);
	}
}
