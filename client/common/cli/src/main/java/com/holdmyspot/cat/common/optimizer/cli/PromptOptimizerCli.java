package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import java.util.ArrayList;
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
		 * @param authentication the command's choice of the source home and gateway login class that every session
		 *                       that the scope starts receives
		 * @return the open command scope
		 * @throws IOException if the harness environment cannot supply the scope
		 */
		AbstractOptimizerCommandScope open(AuthenticationOptions authentication) throws IOException;
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
	 * <p>
	 * A command that starts sessions may name {@value AuthenticationOptions#HOME_OPTION} and
	 * {@value AuthenticationOptions#LOGIN_CLASS_OPTION} anywhere after its name; the scope passes them to every session
	 * that the command starts, so that each takes its credential from that source home. A command that starts no
	 * session and names one of them is refused as invalid input before a scope opens.
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
		boolean evaluatorInfo = !arguments.isEmpty() && arguments.getFirst().equals(EvaluatorInfo.COMMAND);
		boolean evaluatorTrial = arguments.size() == 1 && arguments.getFirst().equals(EvaluatorTrial.COMMAND);
		AuthenticationOptions authentication = AuthenticationOptions.DEFAULT;
		if (!evaluatorInfo && !evaluatorTrial)
		{
			try
			{
				authentication = AuthenticationOptions.parse(args);
			}
			catch (InvalidArgumentsException e)
			{
				out.println(Commands.statusMessage(JsonMapper.builder().build(), "refused", e.getMessage()));
				return 2;
			}
			arguments = withoutAuthenticationOptions(arguments);
			if (!authentication.equals(AuthenticationOptions.DEFAULT) && !Commands.mayStartSessions(arguments))
			{
				out.println(Commands.statusMessage(JsonMapper.builder().build(), "refused",
					AuthenticationOptions.HOME_OPTION + " and " + AuthenticationOptions.LOGIN_CLASS_OPTION + " choose the " +
					"credential of the sessions that a command starts, and this command starts none. Run it again " +
					"without them."));
				return 2;
			}
		}
		try (AbstractOptimizerCommandScope scope = factory.open(authentication))
		{
			if (evaluatorInfo)
				return EvaluatorInfo.run(scope, arguments, out);
			if (evaluatorTrial && scope instanceof MainOptimizerCommandScope installed)
				return EvaluatorTrial.run(installed, System.in, out);
			return run(scope, arguments, zone, Platform.current(), out);
		}
		catch (IOException e)
		{
			// Scope creation failed before a scope-owned mapper became available.
			if (evaluatorInfo || evaluatorTrial)
				return fail(JsonMapper.builder().build(), out, "Could not initialize the installed evaluator.");
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
	 *   no harness session runs, or for a command that would start sessions inside a nested session whose caller allows
	 *   none
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
			if (refusal.isEmpty())
				refusal = nestedRefusal(scope, args);
			if (refusal.isPresent())
			{
				out.println(Commands.statusMessage(scope.json(), "refused", refusal.get()));
				return 2;
			}
		}
		return commands.execute(args, out);
	}

	/**
	 * Applies the nested launch preflight. An existing trial with a verified retained prefix can continue through a
	 * guarded resume after the nested session spends its allowance.
	 *
	 * @param scope the command's scope
	 * @param args the launcher's arguments
	 * @return the launch refusal, or empty if the command may run
	 */
	private static Optional<String> nestedRefusal(AbstractOptimizerCommandScope scope, List<String> args)
	{
		Optional<Command> parsed = Commands.sessionStartingCommand(args);
		if (parsed.isEmpty())
			return Optional.empty();
		Optional<String> refusal = scope.nestedSessionRefusal();
		if (refusal.isEmpty())
			return refusal;
		Command command = parsed.get();
		if (command instanceof Command.Background background)
			command = background.command();
		if (command instanceof Command.Run run && scope.hasRecoverableTrial(run.campaign()))
			return Optional.empty();
		return refusal;
	}

	/**
	 * Returns a command's arguments without the options that choose its sessions' credential.
	 *
	 * @param arguments the command and its arguments
	 * @return the arguments without {@value AuthenticationOptions#HOME_OPTION},
	 *   {@value AuthenticationOptions#LOGIN_CLASS_OPTION} and their values
	 */
	private static List<String> withoutAuthenticationOptions(List<String> arguments)
	{
		List<String> result = new ArrayList<>();
		for (int index = 0; index < arguments.size(); ++index)
		{
			String argument = arguments.get(index);
			if (argument.equals(AuthenticationOptions.HOME_OPTION) ||
				argument.equals(AuthenticationOptions.LOGIN_CLASS_OPTION))
			{
				index += 1;
				continue;
			}
			result.add(argument);
		}
		return result;
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
