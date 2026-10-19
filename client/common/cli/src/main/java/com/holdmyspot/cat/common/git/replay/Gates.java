package com.holdmyspot.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs and retains the ordinary quality gates owned by a topic replay.
 */
public final class Gates
{
	private final CommandRunner commands;

	/**
	 * Creates the gate executor.
	 *
	 * @param commands the command boundary for the replay repository
	 */
	public Gates(CommandRunner commands)
	{
		requireThat(commands, "commands").isNotNull();
		this.commands = commands;
	}

	/**
	 * Runs one reviewer-selected quality gate and retains its complete output beside the replay manifest.
	 *
	 * @param manifestFile the replay manifest that owns the gate evidence
	 * @param gateName the stable name used for the gate's output file
	 * @param command the exact command and arguments selected for the gate
	 * @throws IOException if the gate name is unsafe, the command cannot run, or the gate fails
	 */
	public void run(Path manifestFile, String gateName, List<String> command) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(gateName, "gateName").isNotBlank();
		requireThat(command, "command").isNotEmpty();
		if (!isName(gateName))
		{
			throw new IOException("Topic-replay gate names must use lowercase letters, digits, and hyphens: " + gateName +
				"; choose a stable descriptive name such as full-build");
		}
		Path outputDirectory = manifestFile.toAbsolutePath().normalize().getParent().resolve("gates");
		Files.createDirectories(outputDirectory);
		Path outputFile = outputDirectory.resolve(gateName + ".log");
		int exitCode;
		try
		{
			exitCode = commands.run(command, outputFile);
		}
		catch (IOException e)
		{
			throw new IOException("Could not run topic-replay gate " + gateName + "; inspect " + outputFile +
				" and rerun the gate after resolving the problem", e);
		}
		if (exitCode != 0)
		{
			throw new IOException("Topic-replay gate " + gateName + " failed with exit code " + exitCode + "; inspect " +
				outputFile + ", correct the reported problem, and rerun the same gate before finalizing the replay");
		}
		System.out.println("gate=" + gateName);
		System.out.println("gateLog=" + outputFile);
	}

	/**
	 * Indicates whether a gate name can safely form one manifest-relative log filename.
	 *
	 * @param gateName the candidate gate name
	 * @return {@code true} if the name contains only lowercase letters, digits, and hyphens
	 */
	static boolean isName(String gateName)
	{
		for (int index = 0; index < gateName.length(); index += 1)
		{
			char character = gateName.charAt(index);
			if (!Character.isLowerCase(character) && !Character.isDigit(character) && character != '-')
				return false;
		}
		return true;
	}
}
