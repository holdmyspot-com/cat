package com.holdmyspot.cat.common.installer.engine.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.engine.MigrationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stands in for the scripts of data migrations: it records each script that install runs, with the variables that
 * install passes, and succeeds unless a test makes a script fail.
 */
public final class RecordingMigrations implements MigrationRunner
{
	private final List<Run> runs = new ArrayList<>();
	private final Map<String, CommandResult> failures = new HashMap<>();

	/**
	 * Creates a runner whose scripts all succeed.
	 */
	public RecordingMigrations()
	{
	}

	/**
	 * Makes a script fail.
	 *
	 * @param script the script's file name
	 * @param result the script's exit status and output
	 * @return this runner
	 */
	public RecordingMigrations fail(String script, CommandResult result)
	{
		failures.put(script, result);
		return this;
	}

	/**
	 * Makes a script succeed again.
	 *
	 * @param script the script's file name
	 * @return this runner
	 */
	public RecordingMigrations heal(String script)
	{
		failures.remove(script);
		return this;
	}

	/**
	 * Returns the scripts that ran.
	 *
	 * @return the runs, in order
	 */
	public List<Run> runs()
	{
		return List.copyOf(runs);
	}

	@Override
	public CommandResult run(Path script, Map<String, String> variables)
	{
		requireThat(Files.isRegularFile(script), "Files.isRegularFile(script)").isTrue();
		runs.add(new Run(script, Map.copyOf(variables)));
		return failures.getOrDefault(script.getFileName().toString(), new CommandResult(0, ""));
	}

	/**
	 * One script that ran.
	 *
	 * @param script    the script
	 * @param variables the variables that install passed
	 */
	public record Run(Path script, Map<String, String> variables)
	{
		/**
		 * Returns the script's file name with the versions that it migrated between.
		 *
		 * @return the file name, then the data version before and after it, separated by spaces
		 */
		public String summary()
		{
			return script.getFileName() + " " + variables.get("CAT_MIGRATION_FROM_VERSION") + " " +
				variables.get("CAT_MIGRATION_TO_VERSION");
		}
	}
}
