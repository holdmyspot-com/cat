package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * The entry point that both harnesses' {@code prompt-optimizer} launchers share.
 */
public final class PromptOptimizerCli
{
	/**
	 * Creates the harness-specific resources for one command.
	 */
	@FunctionalInterface
	public interface ScopeFactory
	{
		/**
		 * Opens one command scope, whose ownership transfers to the caller.
		 *
		 * @return the open command scope
		 * @throws IOException if the harness environment cannot supply the scope
		 */
		AbstractOptimizerCommandScope open() throws IOException;
	}

	/**
	 * Prevents instantiation.
	 */
	private PromptOptimizerCli()
	{
	}

	/**
	 * Runs the launcher command, showing times in the JVM's default time zone, and exits the process when its exit
	 * code is nonzero.
	 *
	 * @param args the command and its arguments
	 * @param factory opens the selected harness's command scope
	 * @throws NullPointerException if any argument is null
	 */
	// Both CLI launchers delegate their process exit here so they use the same command exit code.
	@SuppressWarnings("PMD.DoNotTerminateVM")
	public static void main(String[] args, ScopeFactory factory)
	{
		args = CommandLineArguments.recover(args);
		int exitCode = run(args, factory, ZoneId.systemDefault(), System.out);
		if (exitCode != 0)
			System.exit(exitCode);
	}

	/**
	 * Opens a scope, runs a command and closes the scope; renders creation failures as command failures. A contract
	 * tool runs without a scope, since it needs no campaign and no harness, inside a contract author session's
	 * sandbox.
	 *
	 * @param args the command and its arguments
	 * @param factory opens the selected harness's command scope
	 * @param zone the user's time zone, in which the human summary shows times
	 * @param out receives the command's JSON result or failure
	 * @return 0 for a result, 1 for a refusal or failure, or 2 for invalid input
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, ScopeFactory factory, ZoneId zone, PrintStream out)
	{
		requireThat(args, "args").isNotNull();
		requireThat(factory, "factory").isNotNull();
		requireThat(zone, "zone").isNotNull();
		requireThat(out, "out").isNotNull();
		List<String> arguments = List.of(args);
		if (Commands.isContractTool(arguments))
			return Commands.executeContractTool(JsonMapper.builder().build(), arguments, out);
		try (AbstractOptimizerCommandScope scope = factory.open())
		{
			return run(scope, arguments, zone, Platform.current(), out);
		}
		catch (IOException e)
		{
			// Scope creation failed before a scope-owned mapper became available.
			return fail(JsonMapper.builder().build(), out, e.getMessage());
		}
	}

	/**
	 * Runs one command. A command that no harness session runs is refused before it reads or changes any state, unless
	 * it only asks for the usage text.
	 *
	 * @param scope    the command's scope
	 * @param args     the launcher's arguments
	 * @param zone     the user's time zone, in which the human summary shows times
	 * @param platform the platform that runs the command
	 * @param out      where the JSON goes
	 * @return the exit code: 0 for a result, 1 for a refusal or a failure, 2 for invalid input, for a command that
	 *   no harness session runs, or for a command that would start sessions inside a CAT run whose caller allows none
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(AbstractOptimizerCommandScope scope, List<String> args, ZoneId zone, Platform platform,
		PrintStream out)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(args, "args").isNotNull();
		requireThat(zone, "zone").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(out, "out").isNotNull();
		Commands commands = new Commands(new OptimizerCore(scope), scope.json(), zone, scope.trialSandbox(), platform);
		if (!Commands.isHelpRequest(args) && !Commands.isContractTool(args))
		{
			Optional<String> refusal = scope.noHarnessRefusal();
			if (refusal.isEmpty() && commands.startsSessions(args))
				refusal = scope.runRefusal();
			if (refusal.isPresent())
			{
				out.println(Commands.statusMessage(scope.json(), "refused", refusal.get()));
				return 2;
			}
		}
		return commands.execute(args, out);
	}

	/**
	 * Prints a failure that happened before a command could run.
	 *
	 * @param json the JSON mapper
	 * @param out where the JSON goes
	 * @param message what failed
	 * @return the exit code, which is 1
	 * @throws NullPointerException if any argument is null
	 */
	public static int fail(JsonMapper json, PrintStream out, String message)
	{
		requireThat(json, "json").isNotNull();
		requireThat(out, "out").isNotNull();
		requireThat(message, "message").isNotNull();
		out.println(Commands.statusMessage(json, "error", message));
		return 1;
	}
}
