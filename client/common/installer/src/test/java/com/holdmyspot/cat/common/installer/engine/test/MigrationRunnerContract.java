package com.holdmyspot.cat.common.installer.engine.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.engine.MigrationRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks that a {@link MigrationRunner} reports the exit status and output of the script that it runs, and passes the
 * script the variables that install supplies. The caller supplies a directory for each check's script and owns its
 * cleanup.
 */
public final class MigrationRunnerContract
{
	/**
	 * A variable that install passes to every migration script.
	 */
	private static final String FROM_VERSION = "CAT_MIGRATION_FROM_VERSION";
	/**
	 * Another variable that install passes to every migration script.
	 */
	private static final String TO_VERSION = "CAT_MIGRATION_TO_VERSION";

	/**
	 * The runner under contract and the scripts that it runs.
	 */
	private final Subject subject;

	/**
	 * Creates checks of one runner.
	 *
	 * @param subject the runner under contract and the scripts that it runs
	 */
	public MigrationRunnerContract(Subject subject)
	{
		this.subject = subject;
	}

	/**
	 * Verifies that a script that fails reports its exit status and output.
	 *
	 * @param directory the directory that holds the script
	 * @throws IOException if the script cannot be arranged or run
	 */
	public void reportsFailure(Path directory) throws IOException
	{
		CommandResult failed = new CommandResult(3, "migration failed");
		Path script = subject.script(directory, failed, Set.of());

		requireThat(subject.runner().run(script, Map.of()), "result").isEqualTo(failed);
	}

	/**
	 * Verifies that a script that succeeds silently reports exit status zero and no output.
	 *
	 * @param directory the directory that holds the script
	 * @throws IOException if the script cannot be arranged or run
	 */
	public void reportsSuccess(Path directory) throws IOException
	{
		CommandResult succeeded = new CommandResult(0, "");
		Path script = subject.script(directory, succeeded, Set.of());

		requireThat(subject.runner().run(script, Map.of()), "result").isEqualTo(succeeded);
	}

	/**
	 * Verifies that a script receives the variables that install passes.
	 *
	 * @param directory the directory that holds the script
	 * @throws IOException if the script cannot be arranged or run
	 */
	public void passesVariables(Path directory) throws IOException
	{
		Path script = subject.script(directory, new CommandResult(0, ""), Set.of(FROM_VERSION, TO_VERSION));

		subject.runner().run(script, Map.of(FROM_VERSION, "1.0", TO_VERSION, "1.1"));

		requireThat(Map.of(FROM_VERSION, subject.received(script, FROM_VERSION), TO_VERSION,
			subject.received(script, TO_VERSION)), "received").isEqualTo(Map.of(FROM_VERSION, Optional.of("1.0"),
			TO_VERSION, Optional.of("1.1")));
	}

	/**
	 * A runner under contract and the scripts that it runs.
	 */
	public interface Subject
	{
		/**
		 * Returns the runner under contract.
		 *
		 * @return the runner
		 */
		MigrationRunner runner();

		/**
		 * Arranges a script that ends with an exit status and output and records the values of variables that it
		 * receives.
		 *
		 * @param directory the directory that holds the script
		 * @param result    the exit status and output that the script ends with
		 * @param variables the names of the variables whose values the script records
		 * @return the absolute path of the script
		 * @throws IOException if the script cannot be written
		 */
		Path script(Path directory, CommandResult result, Set<String> variables) throws IOException;

		/**
		 * Returns the value of a variable that a script recorded when it ran.
		 *
		 * @param script   the script
		 * @param variable the name of the variable
		 * @return the value, or empty if the script did not receive the variable
		 * @throws IOException if the recorded value cannot be read
		 */
		Optional<String> received(Path script, String variable) throws IOException;
	}
}
