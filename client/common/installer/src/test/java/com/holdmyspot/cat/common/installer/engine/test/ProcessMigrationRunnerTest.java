package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.engine.ProcessMigrationRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests that a data migration script runs in its own directory with the variables that install passes, and that
 * its exit status and output reach install.
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
	 * A POSIX script receives the variables, runs in its own directory, and reports its exit status and output.
	 *
	 * @throws IOException if the fixture cannot be created or the script cannot be run
	 */
	@Test
	public void runsPosixScript() throws IOException
	{
		if (Platform.current().isWindows())
			throw new SkipException("POSIX shell scripts run only on POSIX platforms");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("migration-process-")).toRealPath();
			Path scripts = Files.createDirectories(root.resolve("plugin/install/migrations"));
			Path data = Files.createDirectories(root.resolve("data"));
			Path script = Files.writeString(scripts.resolve("split.sh"), """
				printf '%s %s' "$CAT_MIGRATION_FROM_VERSION" "$CAT_MIGRATION_TO_VERSION" > \
				"$CAT_MIGRATION_DATA_DIR/migrated"
				echo "ran in $(pwd)"
				exit 3
				""");

			CommandResult result = new ProcessMigrationRunner().run(script, Map.of("CAT_MIGRATION_DATA_DIR",
				data.toString(), "CAT_MIGRATION_FROM_VERSION", "1.0", "CAT_MIGRATION_TO_VERSION", "1.1"));

			requireThat(result.exitCode(), "exitCode").isEqualTo(3);
			requireThat(result.output(), "output").isEqualTo("ran in " + scripts + "\n");
			requireThat(Files.readString(data.resolve("migrated")), "migrated").isEqualTo("1.0 1.1");
		}
	}
}
