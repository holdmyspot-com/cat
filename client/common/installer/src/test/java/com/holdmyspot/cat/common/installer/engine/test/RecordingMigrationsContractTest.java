package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.engine.MigrationRunner;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the {@link MigrationRunnerContract} against {@link RecordingMigrations}, so that the runner that install tests
 * use in place of migration scripts answers as {@code ProcessMigrationRunnerTest} shows that real scripts do.
 */
public final class RecordingMigrationsContractTest
{
	/**
	 * Creates the test.
	 */
	public RecordingMigrationsContractTest()
	{
	}

	/**
	 * Verifies that a script that the test makes fail reports its exit status and output.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract().reportsFailure(cleanup.add(testDirectory("migration-recording-")));
		}
	}

	/**
	 * Verifies that a script succeeds silently unless the test makes it fail.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsSuccess() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract().reportsSuccess(cleanup.add(testDirectory("migration-recording-")));
		}
	}

	/**
	 * Verifies that the recorded run holds the variables that install passed.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void passesVariables() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			contract().passesVariables(cleanup.add(testDirectory("migration-recording-")));
		}
	}

	/**
	 * Creates the contract over a new runner.
	 *
	 * @return the contract
	 */
	private static MigrationRunnerContract contract()
	{
		RecordingMigrations migrations = new RecordingMigrations();
		return new MigrationRunnerContract(new MigrationRunnerContract.Subject()
		{
			@Override
			public MigrationRunner runner()
			{
				return migrations;
			}

			@Override
			public Path script(Path directory, CommandResult result, Set<String> variables) throws IOException
			{
				Path script = Files.writeString(directory.resolve("migrate.sh"), "");
				if (!result.equals(new CommandResult(0, "")))
					migrations.fail(script.getFileName().toString(), result);
				return script;
			}

			@Override
			public Optional<String> received(Path script, String variable)
			{
				return migrations.runs().stream().filter(run -> run.script().equals(script)).
					map(run -> Optional.ofNullable(run.variables().get(variable))).reduce((_, last) -> last).
					flatMap(value -> value);
			}
		});
	}
}
