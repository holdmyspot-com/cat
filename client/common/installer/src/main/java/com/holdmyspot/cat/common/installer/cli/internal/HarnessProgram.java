package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.WritablePathEntries;
import com.holdmyspot.cat.common.WritablePathEntries.Writable;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import com.holdmyspot.cat.common.release.EntryCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Confirms that a harness program is the harness's program before install runs it.
 * <p>
 * Install runs the harness program outside any agent sandbox, and records it in the policy, from which every later
 * install runs it again, also outside the sandbox. A program that an agent planted, by writing into a folder on the
 * search path or by changing the search path of the process that starts the installer, would therefore escape the
 * agent's sandbox. So a later install, whoever starts it, reuses the recorded program with its recorded environment
 * for as long as that program exists and is executable, and refuses when it exists but cannot be used. Only when the
 * recorded program is gone does install take the first program of the harness from the search path instead, as a first
 * install does, and record it; it then warns when the program lies in a folder that a harness's sandbox lets its
 * commands write, because a command of that sandbox may have planted it.
 */
public final class HarnessProgram
{
	/**
	 * Prevents construction.
	 */
	private HarnessProgram()
	{
	}

	/**
	 * The exit statuses with which a shell reports that it could not run a command: 126 when the command is not
	 * executable, and 127 when it, or the interpreter that it names, is not found.
	 */
	private static final Set<Integer> NOT_STARTED = Set.of(126, 127);

	/**
	 * Confirms that a program is the harness's program by running {@code <program> --version} exactly as registration
	 * will run it.
	 *
	 * @param command     the program's command and environment
	 * @param home        the real harness home
	 * @param integration the harness's integration
	 * @param cliFactory  runs the program
	 * @throws NullPointerException  if any of the arguments are null
	 * @throws ProgramStartException if the program could not start in its environment; the message names the
	 *                               product, the program, and the exit status and output or the launch failure
	 * @throws IOException           if the program does not finish or its output cannot be read, or the program
	 *                               does not identify itself as the harness's program
	 */
	public static void requireIdentity(HarnessCommand command, Path home, HarnessIntegration integration,
		HarnessCliFactory cliFactory) throws IOException
	{
		requireThat(command, "command").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(integration, "integration").isNotNull();
		requireThat(cliFactory, "cliFactory").isNotNull();
		// On Windows a .cmd program follows the cmd.exe prefix that runs it
		String notStarted = integration.productName() + " at " + command.command().getLast() + " could not start " +
			"in this environment";
		CommandResult version;
		try
		{
			version = cliFactory.create(command, home).run(List.of("--version"));
		}
		catch (ProgramStartException e)
		{
			throw new ProgramStartException(notStarted + ": " + sentence(e.getMessage()), e);
		}
		if (NOT_STARTED.contains(version.exitCode()))
		{
			String output = version.output().strip();
			String message = notStarted + " (status " + version.exitCode() + ").";
			if (!output.isEmpty())
				message += " Its output: " + sentence(output);
			throw new ProgramStartException(message, null);
		}
		integration.requireIdentity(version);
	}

	/**
	 * Ends text as a sentence.
	 *
	 * @param text the text
	 * @return the text, followed by a period unless it ends with one
	 */
	private static String sentence(String text)
	{
		if (text.endsWith("."))
			return text;
		return text + ".";
	}

	/**
	 * Confirms that the harness program that install recorded, which exists and is executable, can still be used: it
	 * identifies itself as the harness's program and starts in its recorded environment.
	 *
	 * @param command      the recorded command and environment
	 * @param home         the real harness home
	 * @param policyFile   the policy that records the program
	 * @param integration  the harness's integration
	 * @param cliFactory   runs the program
	 * @param entryCommand writes the CAT entry command that the message tells the user to run to start over
	 * @throws NullPointerException if any of the arguments are null
	 * @throws TrustException       if the program can no longer be used; the message tells the user how to start over
	 */
	public static void requireRecorded(HarnessCommand command, Path home, Path policyFile,
		HarnessIntegration integration, HarnessCliFactory cliFactory, EntryCommand entryCommand) throws TrustException
	{
		requireThat(policyFile, "policyFile").isNotNull();
		requireThat(entryCommand, "entryCommand").isNotNull();
		String harness = integration.harness();
		// On Windows a .cmd program follows the cmd.exe prefix that runs it
		Path program = Path.of(command.command().getLast());
		try
		{
			requireIdentity(command, home, integration, cliFactory);
		}
		catch (ProgramStartException e)
		{
			throw new TrustException(Reason.MALFORMED_POLICY, e.getMessage() + " Install recorded this program, and " +
				"takes another program from PATH only when the recorded one does not exist; to start over, " +
				Locations.startOver(entryCommand, harness, policyFile) + ".");
		}
		catch (IOException e)
		{
			throw new TrustException(Reason.MALFORMED_POLICY, "The program " + program + " that install recorded " +
				"is no longer the " + harness + " program (" + e.getMessage() + "), and install takes another " +
				"program from PATH only when the recorded one does not exist; to start over, " +
				Locations.startOver(entryCommand, harness, policyFile) + ".");
		}
	}

	/**
	 * Indicates whether the harness program that install recorded is gone: it is not a regular file, or it is not
	 * executable.
	 *
	 * @param command the recorded command and environment
	 * @return {@code true} if the recorded program no longer exists or cannot be executed
	 * @throws NullPointerException if {@code command} is null
	 */
	public static boolean isGone(HarnessCommand command)
	{
		requireThat(command, "command").isNotNull();
		// On Windows a .cmd program follows the cmd.exe prefix that runs it
		Path program = Path.of(command.command().getLast());
		return !Files.isRegularFile(program) || !Files.isExecutable(program);
	}

	/**
	 * Finds the harness program on the user's search path and confirms that it is the harness's program.
	 *
	 * @param platform    the platform that the installer runs on
	 * @param integration the harness's integration
	 * @param home        the real harness home
	 * @param environment the user's environment
	 * @param cliFactory  runs the program
	 * @param notFound    the text that precedes the message that says that the program is not on the search path
	 * @return the command and environment to record
	 * @throws NullPointerException if any of the arguments are null
	 * @throws TrustException       if the program is not on the user's search path, or could not start in the user's
	 *                              environment; the message tells the user to install the harness or to run the
	 *                              installer with the environment of the harness's sessions
	 * @throws IOException          if the program cannot be run, or does not identify itself as the harness's program
	 */
	public static HarnessCommand discover(Platform platform, HarnessIntegration integration, Path home,
		Map<String, String> environment, HarnessCliFactory cliFactory, String notFound)
		throws TrustException, IOException
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(notFound, "notFound").isNotNull();
		HarnessCommand command;
		try
		{
			command = new ExecutableDiscovery(platform).find(integration.programName(), environment,
				integration.programEnvironment(home, platform.environment(environment)));
		}
		catch (ProgramNotFoundException e)
		{
			// The same environment finds no program again, so only the user can change the outcome
			throw new TrustException(Reason.MALFORMED_POLICY, notFound + e.getMessage() + " Install " +
				integration.productName() + ", or run the installer with the PATH your " + integration.productName() +
				" sessions use, then run the same command again.");
		}
		try
		{
			requireIdentity(command, home, integration, cliFactory);
		}
		catch (ProgramStartException e)
		{
			// The same environment fails the same way, so only the user can change the outcome
			throw new TrustException(Reason.MALFORMED_POLICY, e.getMessage() + " Run the installer with the " +
				"environment your " + integration.productName() + " sessions use.");
		}
		catch (IOException e)
		{
			throw new IOException("The program " + command.command().getLast() + " that install found on PATH is " +
				"not the " + integration.harness() + " program: " + e.getMessage(), e);
		}
		return command;
	}

	/**
	 * Finds the program that replaces a recorded program that is gone, and confirms that it is the harness's program.
	 *
	 * @param recorded    the recorded program, which is gone
	 * @param platform    the platform that the installer runs on
	 * @param integration the harness's integration
	 * @param home        the real harness home
	 * @param environment the user's environment
	 * @param cliFactory  runs the program
	 * @return the command and environment to record
	 * @throws NullPointerException if any of the arguments are null
	 * @throws TrustException       if the user's search path holds no program of the harness, or the first one could
	 *                              not start in the user's environment
	 * @throws IOException          if the program cannot be run, or does not identify itself as the harness's program
	 */
	public static HarnessCommand replacement(HarnessCommand recorded, Platform platform, HarnessIntegration integration,
		Path home, Map<String, String> environment, HarnessCliFactory cliFactory) throws TrustException, IOException
	{
		requireThat(recorded, "recorded").isNotNull();
		return discover(platform, integration, home, environment, cliFactory, "The " + integration.productName() +
			" program " + recorded.command().getLast() + " that install recorded no longer exists or is not " +
			"executable, and install found no other: ");
	}

	/**
	 * Returns what install tells the user when it replaced a recorded program that is gone: both programs, and a
	 * warning for each harness whose sandbox lets its commands write the replacement's folder.
	 *
	 * @param recorded    the program that was recorded
	 * @param replacement the program that replaces it
	 * @param policyFile  the policy that now records the replacement
	 * @param integration the harness's integration
	 * @param home        the real harness home
	 * @param others      the integrations of the other harnesses
	 * @param environment the user's environment, which selects the other harnesses' homes
	 * @param userHome    the user's home directory
	 * @param workingDirectory the absolute folder that the user ran the installer from, or null if it is not known
	 * @return the notice
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String replacementNotice(HarnessCommand recorded, HarnessCommand replacement, Path policyFile,
		HarnessIntegration integration, Path home, List<HarnessIntegration> others, Map<String, String> environment,
		Path userHome, Path workingDirectory)
	{
		requireThat(recorded, "recorded").isNotNull();
		requireThat(replacement, "replacement").isNotNull();
		requireThat(policyFile, "policyFile").isNotNull();
		requireThat(integration, "integration").isNotNull();
		requireThat(others, "others").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		String product = integration.productName();
		Path program = Path.of(replacement.command().getLast());
		StringJoiner notice = new StringJoiner(" ");
		notice.add("The " + product + " program " + recorded.command().getLast() + " that install recorded no " +
			"longer exists or is not executable, so install ran " + program + ", the first " +
			integration.programName() + " on PATH, and recorded it in " + policyFile + ".");
		Path folder = program.getParent();
		if (folder == null)
			return notice.toString();
		Map<HarnessIntegration, Path> homes = new LinkedHashMap<>();
		homes.put(integration, home);
		for (HarnessIntegration other : others)
		{
			Path otherHome = other.installation(environment, userHome).home();
			if (Files.isDirectory(otherHome))
				homes.put(other, otherHome);
		}
		boolean covered = false;
		for (Map.Entry<HarnessIntegration, Path> entry : homes.entrySet())
		{
			HarnessIntegration sandboxed = entry.getKey();
			try
			{
				SandboxWrites writes = sandboxed.sandboxWrites(entry.getValue(), userHome, workingDirectory);
				for (Writable writable : WritablePathEntries.find(folder.toString(), userHome, writes))
				{
					covered = true;
					if (writable.grant().possible())
					{
						notice.add("Warning: " + writable.folder() + ", the folder of " + program + ", may be writable " +
							"by the sandbox of " + sandboxed.productName() + ", because " + writable.grant().setting() +
							" may grant it and CAT cannot tell whether the sandbox applies it, so a command of that " +
							"sandbox may have planted the program. Check it before you rely on it, and take the grant " +
							"out of the setting or the folder out of PATH.");
					}
					else
					{
						notice.add("Warning: " + writable.folder() + ", the folder of " + program + ", is one that " +
							"the sandbox of " + sandboxed.productName() + " lets its commands write, granted by " +
							writable.grant().setting() + ", so a command of that sandbox may have planted the " +
							"program. Check it before you rely on it, and take the grant out of the setting or the " +
							"folder out of PATH.");
					}
				}
				if (!writes.problems().isEmpty())
				{
					covered = true;
					notice.add("CAT could not read everything that can let the sandbox of " + sandboxed.productName() +
						" write " + folder + ", so it may be writable: " + String.join("; ", writes.problems()) + ".");
				}
			}
			catch (IOException e)
			{
				covered = true;
				notice.add("CAT could not check whether the sandbox of " + sandboxed.productName() + " lets its " +
					"commands write " + folder + ": " + e.getMessage() + ".");
			}
		}
		if (covered)
			notice.add(WritablePathEntries.COVERAGE);
		return notice.toString();
	}
}
