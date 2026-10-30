package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.secrets.HarnessOneShotOptionsAccess;
import com.holdmyspot.cat.common.secrets.HarnessOneShotOptionsSecrets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Parsed options for one isolated harness turn.
 *
 * @param artifact the complete harness artifact
 * @param project the source project
 * @param promptFile the prompt file
 * @param model the requested model
 * @param effort the requested reasoning effort
 * @param output the durable output file
 * @param runRoot the absolute owned-run root
 * @param executable the harness executable
 * @param keepRun indicates whether to retain a successful run for investigation
 * @param installCat indicates whether CAT is installed in the isolated harness home
 */
public record HarnessOneShotOptions(Path artifact, Path project, Path promptFile, String model, String effort,
	Path output, Path runRoot, String executable, boolean keepRun, boolean installCat)
{
	static
	{
		HarnessOneShotOptionsSecrets.setHarnessOneShotOptionsAccess(new HarnessOneShotOptionsAccess()
		{
			@Override
			public int maximumPromptBytes()
			{
				return MAXIMUM_PROMPT_BYTES;
			}
		});
	}

	static final int MAXIMUM_PROMPT_BYTES = 1_048_576;

	/**
	 * Parses the {@code run} command.
	 *
	 * @param args command-line arguments
	 * @param defaultExecutable the harness executable name
	 * @param temporaryRoot where the runs are kept when the command line names no {@code --run-root}
	 * @return parsed one-shot options
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the arguments are incomplete or invalid, if they name no {@code --model} or
	 *   no {@code --effort}, or if they name no {@code --run-root} outside a harness session
	 */
	public static HarnessOneShotOptions parse(String[] args, String defaultExecutable,
		HarnessTemporaryRoot temporaryRoot)
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(args, "args").isNotNull();
		requireThat(defaultExecutable, "defaultExecutable").isNotBlank();
		if (args.length == 0 || !args[0].equals("run"))
		{
			throw new IllegalArgumentException(
				"Usage: <runner> run --artifact <path> --prompt-file <path> --output <path> [--project <path>] " +
					"--model <name> --effort <level> [--run-root <path>] [--executable <path>] [--keep-run] " +
					"[--install-cat]");
		}
		Map<String, String> values = new HashMap<>();
		boolean keepRun = false;
		boolean installCat = false;
		for (int index = 1; index < args.length;)
		{
			String option = args[index];
			if (option.equals("--keep-run"))
			{
				keepRun = true;
				index += 1;
				continue;
			}
			if (option.equals("--install-cat"))
			{
				installCat = true;
				index += 1;
				continue;
			}
			if (index + 1 >= args.length)
				throw new IllegalArgumentException("Missing value for " + option);
			if (!option.equals("--artifact") && !option.equals("--project") && !option.equals("--prompt-file") &&
				!option.equals("--model") && !option.equals("--effort") && !option.equals("--output") &&
				!option.equals("--run-root") && !option.equals("--executable"))
				throw new IllegalArgumentException("Unsupported option: " + option);
			String value = args[index + 1];
			values.put(option, value);
			index += 2;
		}
		Path artifact = path(values, "--artifact");
		Path project = Path.of(values.getOrDefault("--project", "")).toAbsolutePath().normalize();
		Path promptFile = path(values, "--prompt-file");
		String model = values.getOrDefault("--model", "");
		String effort = values.getOrDefault("--effort", "");
		HarnessRunnerOptions.requireProfile(defaultExecutable, model, effort);
		Path output = path(values, "--output");
		Path runRoot = temporaryRoot.runRoot(values.get("--run-root"));
		String executable = values.getOrDefault("--executable", defaultExecutable);
		requireThat(artifact, "artifact").isNotNull();
		requireThat(promptFile, "promptFile").isNotNull();
		requireThat(output, "output").isNotNull();
		return new HarnessOneShotOptions(artifact, project, promptFile, model, effort, output, runRoot, executable,
			keepRun, installCat);
	}

	/**
	 * Reads this invocation's bounded whole-prompt input.
	 *
	 * @return the UTF-8 prompt text
	 * @throws IOException if the prompt cannot be read or exceeds the supported size
	 */
	public String prompt() throws IOException
	{
		try (InputStream input = Files.newInputStream(promptFile))
		{
			byte[] bytes = input.readNBytes(MAXIMUM_PROMPT_BYTES + 1);
			if (bytes.length > MAXIMUM_PROMPT_BYTES)
			{
				throw new IOException("Prompt file exceeds the maximum supported size of " + MAXIMUM_PROMPT_BYTES +
					" bytes: " + promptFile);
			}
			return new String(bytes, UTF_8);
		}
	}

	/**
	 * Gets an optional path argument.
	 *
	 * @param values command-line values by option name
	 * @param option the option name
	 * @return the path value, or {@code null} when absent
	 */
	private static Path path(Map<String, String> values, String option)
	{
		assert values != null;
		assert option != null;
		String value = values.get(option);
		if (value == null)
			return null;
		return Path.of(value);
	}
}
