package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.engine.MigrationRunner;
import com.holdmyspot.cat.common.installer.engine.ProcessMigrationRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests that a data migration script runs in its own directory with the variables that install passes, and that
 * its exit status and output reach install. The scripts are POSIX shell scripts, which the runner starts with
 * {@code /bin/sh}, and on Windows {@code .ps1} scripts, which it starts with Windows PowerShell.
 */
public final class ProcessMigrationRunnerTest
{
	/**
	 * Creates the test.
	 */
	public ProcessMigrationRunnerTest()
	{
	}

	/**
	 * A script receives the variables, runs in its own directory, and reports its exit status and output.
	 *
	 * @throws IOException if the fixture cannot be created or the script cannot be run
	 */
	@Test
	public void runsScript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("migration-process-")).toRealPath();
			Path scripts = Files.createDirectories(root.resolve("plugin/install/migrations"));
			Path data = Files.createDirectories(root.resolve("data"));
			Path script;
			if (Platform.current().isWindows())
			{
				script = Files.writeString(scripts.resolve("split.ps1"), """
					[System.IO.File]::WriteAllText("$env:CAT_MIGRATION_DATA_DIR\\migrated", \
						"$env:CAT_MIGRATION_FROM_VERSION $env:CAT_MIGRATION_TO_VERSION")
					[Console]::Out.Write("ran in " + (Get-Location).Path + "`n")
					exit 3
					""");
			}
			else
			{
				script = Files.writeString(scripts.resolve("split.sh"), """
					printf '%s %s' "$CAT_MIGRATION_FROM_VERSION" "$CAT_MIGRATION_TO_VERSION" > \
					"$CAT_MIGRATION_DATA_DIR/migrated"
					echo "ran in $(pwd)"
					exit 3
					""");
			}

			CommandResult result = new ProcessMigrationRunner().run(script, Map.of("CAT_MIGRATION_DATA_DIR",
				data.toString(), "CAT_MIGRATION_FROM_VERSION", "1.0", "CAT_MIGRATION_TO_VERSION", "1.1"));

			requireThat(result.exitCode(), "exitCode").isEqualTo(3);
			requireThat(result.output(), "output").isEqualTo("ran in " + scripts + "\n");
			requireThat(Files.readString(data.resolve("migrated")), "migrated").isEqualTo("1.0 1.1");
		}
	}

	/**
	 * Verifies that a script that fails reports its exit status and output.
	 *
	 * @throws IOException if the fixture cannot be created or the script cannot be run
	 */
	@Test
	public void reportsFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract().reportsFailure(cleanup.add(testDirectory("migration-process-")).toRealPath());
		}
	}

	/**
	 * Verifies that a script that succeeds silently reports exit status zero and no output.
	 *
	 * @throws IOException if the fixture cannot be created or the script cannot be run
	 */
	@Test
	public void reportsSuccess() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract().reportsSuccess(cleanup.add(testDirectory("migration-process-")).toRealPath());
		}
	}

	/**
	 * Verifies that a script receives the variables that install passes in its environment.
	 *
	 * @throws IOException if the fixture cannot be created or the script cannot be run
	 */
	@Test
	public void passesVariables() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract().passesVariables(cleanup.add(testDirectory("migration-process-")).toRealPath());
		}
	}

	/**
	 * Creates the contract over the runner, with scripts for the current platform that print the output, record each
	 * variable in a file beside the script and exit with the status.
	 *
	 * @return the contract
	 */
	private static MigrationRunnerContract contract()
	{
		MigrationRunner runner = new ProcessMigrationRunner();
		return new MigrationRunnerContract(new MigrationRunnerContract.Subject()
		{
			@Override
			public MigrationRunner runner()
			{
				return runner;
			}

			@Override
			public Path script(Path directory, CommandResult result, Set<String> variables) throws IOException
			{
				boolean windows = Platform.current().isWindows();
				List<String> lines = new ArrayList<>();
				for (String variable : variables)
				{
					if (windows)
					{
						lines.add("[System.IO.File]::WriteAllText('" + value(directory, variable) + "', $env:" +
							variable + ")");
					}
					else
						lines.add("printf '%s' \"$" + variable + "\" > '" + value(directory, variable) + "'");
				}
				if (windows)
					lines.add("[Console]::Out.Write('" + result.output() + "')");
				else
					lines.add("printf '%s' '" + result.output() + "'");
				lines.add("exit " + result.exitCode());
				String name = "migrate.sh";
				if (windows)
					name = "migrate.ps1";
				return Files.writeString(directory.resolve(name), String.join("\n", lines) + "\n");
			}

			@Override
			public Optional<String> received(Path script, String variable) throws IOException
			{
				Path value = value(script.getParent(), variable);
				if (Files.notExists(value))
					return Optional.empty();
				return Optional.of(Files.readString(value));
			}
		});
	}

	/**
	 * Returns the file in which a script records the value of a variable.
	 *
	 * @param directory the directory that holds the script
	 * @param variable  the name of the variable
	 * @return the file
	 */
	private static Path value(Path directory, String variable)
	{
		return directory.resolve(variable + ".value");
	}
}
