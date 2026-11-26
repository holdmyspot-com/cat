package com.holdmyspot.cat.common.installer.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.secrets.ProcessHarnessCliSecrets;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs a harness program through the installer's own runner, with each started process replaced by the process that
 * {@link Answers#process} returns: by default an {@link ExitedProgram} whose answer a test scripts from the harness
 * program's recorded or documented behavior.
 */
public final class HarnessProgram
{
	/**
	 * The program that the runner is told to start; no process ever starts it.
	 */
	private static final String PROGRAM = "harness";

	/**
	 * Prevents instantiation.
	 */
	private HarnessProgram()
	{
	}

	/**
	 * Returns a runner of the harness program whose processes answer as {@code answers} scripts.
	 *
	 * @param answers answers each command line, without the program, as the harness program would
	 * @return the runner
	 * @throws NullPointerException if {@code answers} is null
	 */
	public static HarnessCli cli(Answers answers)
	{
		requireThat(answers, "answers").isNotNull();
		Path root = FileSystems.getDefault().getRootDirectories().iterator().next();
		HarnessCommand command = new HarnessCommand(List.of(root.resolve(PROGRAM).toString()), Map.of());
		int prefix = command.command().size();
		return ProcessHarnessCliSecrets.getProcessHarnessCliAccess().create(command, root, builder ->
		{
			List<String> line = builder.command();
			return answers.process(List.copyOf(line.subList(prefix, line.size())));
		});
	}

	/**
	 * Supplies a harness program's stateful answers for one home directory.
	 */
	public interface Factory
	{
		/**
		 * Returns the harness identifier used to select this factory.
		 *
		 * @return the harness identifier
		 */
		String harness();

		/**
		 * Creates the answers for a harness program in one home directory.
		 *
		 * @param home the harness home
		 * @return the stateful answers
		 */
		Answers answers(Path home);
	}

	/**
	 * Answers the harness program's command lines.
	 */
	@FunctionalInterface
	public interface Answers
	{
		/**
		 * Answers one command line, changing the harness's files as the harness program would.
		 *
		 * @param arguments the arguments after the program
		 * @return the exit status and combined output
		 * @throws IOException if the harness's files cannot be changed
		 */
		CommandResult answer(List<String> arguments) throws IOException;

		/**
		 * Returns the process that stands in for the harness program on one command line. A program that the installer
		 * converses with, through its standard input and output, overrides this method for that command line.
		 *
		 * @param arguments the arguments after the program
		 * @return by default, a process that has exited with {@link #answer}'s exit status and output
		 * @throws IOException if the harness's files cannot be changed
		 */
		default Process process(List<String> arguments) throws IOException
		{
			return new ExitedProgram(answer(arguments));
		}
	}
}
