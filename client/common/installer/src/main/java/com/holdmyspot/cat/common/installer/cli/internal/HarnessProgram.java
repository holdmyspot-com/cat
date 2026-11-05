package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
	 * Confirms that a program is the harness's program by running {@code <program> --version} exactly as registration
	 * will run it.
	 *
	 * @param command     the program's command and environment
	 * @param home        the real harness home
	 * @param integration the harness's integration
	 * @param cliFactory  runs the program
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the program cannot be run, or does not identify itself as the harness's program
	 */
	public static void requireIdentity(HarnessCommand command, Path home, HarnessIntegration integration,
		HarnessCliFactory cliFactory) throws IOException
	{
		requireThat(command, "command").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(integration, "integration").isNotNull();
		requireThat(cliFactory, "cliFactory").isNotNull();
		CommandResult version = cliFactory.create(command, home).run(List.of("--version"));
		integration.requireIdentity(version);
	}

	/**
	 * Confirms that the harness program that install recorded can still be used: it exists, is executable and
	 * identifies itself as the harness's program.
	 *
	 * @param command     the recorded command and environment
	 * @param home        the real harness home
	 * @param policyFile  the policy that records the program
	 * @param integration the harness's integration
	 * @param cliFactory  runs the program
	 * @throws NullPointerException if any of the arguments are null
	 * @throws TrustException       if the program can no longer be used; the message tells the user how to start over
	 */
	public static void requireRecorded(HarnessCommand command, Path home, Path policyFile,
		HarnessIntegration integration, HarnessCliFactory cliFactory) throws TrustException
	{
		requireThat(policyFile, "policyFile").isNotNull();
		String harness = integration.harness();
		// On Windows a .cmd program follows the cmd.exe prefix that runs it
		Path program = Path.of(command.command().getLast());
		if (!Files.isRegularFile(program) || !Files.isExecutable(program))
		{
			throw new TrustException(Reason.MALFORMED_POLICY, "The " + harness + " program " + program + " that " +
				"install recorded no longer exists or is not executable, and install never takes another program from " +
				"PATH once it has recorded one; to start over, " + Locations.startOver(harness, policyFile) + ".");
		}
		try
		{
			requireIdentity(command, home, integration, cliFactory);
		}
		catch (IOException e)
		{
			throw new TrustException(Reason.MALFORMED_POLICY, "The program " + program + " that install recorded " +
				"is no longer the " + harness + " program (" + e.getMessage() + "), and install never takes another " +
				"program from PATH once it has recorded one; to start over, " +
				Locations.startOver(harness, policyFile) + ".");
		}
	}
}
