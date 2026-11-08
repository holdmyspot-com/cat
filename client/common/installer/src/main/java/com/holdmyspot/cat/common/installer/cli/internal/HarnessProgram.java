package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import com.holdmyspot.cat.common.release.EntryCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Confirms that a harness program is the harness's program before install runs it.
 * <p>
 * Install runs the harness program outside any agent sandbox, and records it in the policy, from which every later
 * install runs it again, also outside the sandbox. A program that an agent planted, by writing into a folder on the
 * search path or by changing the search path of the process that starts the installer, would therefore escape the
 * agent's sandbox. So only a first install, which the user runs because it writes the harness home that the harness's
 * sandbox keeps an agent from writing, takes the program from the search path; every later install, whoever starts it,
 * reuses the recorded program with its recorded environment and refuses when that program can no longer be used,
 * instead of taking another one from the search path.
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
	 * Confirms that the harness program that install recorded can still be used: it exists, is executable and
	 * identifies itself as the harness's program.
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
		if (!Files.isRegularFile(program) || !Files.isExecutable(program))
		{
			throw new TrustException(Reason.MALFORMED_POLICY, "The " + harness + " program " + program + " that " +
				"install recorded no longer exists or is not executable, and install never takes another program from " +
				"PATH once it has recorded one; to start over, " +
				Locations.startOver(entryCommand, harness, policyFile) + ".");
		}
		try
		{
			requireIdentity(command, home, integration, cliFactory);
		}
		catch (ProgramStartException e)
		{
			throw new TrustException(Reason.MALFORMED_POLICY, e.getMessage() + " Install recorded this program, and " +
				"never takes another program from PATH once it has recorded one; to start over, " +
				Locations.startOver(entryCommand, harness, policyFile) + ".");
		}
		catch (IOException e)
		{
			throw new TrustException(Reason.MALFORMED_POLICY, "The program " + program + " that install recorded " +
				"is no longer the " + harness + " program (" + e.getMessage() + "), and install never takes another " +
				"program from PATH once it has recorded one; to start over, " +
				Locations.startOver(entryCommand, harness, policyFile) + ".");
		}
	}
}
