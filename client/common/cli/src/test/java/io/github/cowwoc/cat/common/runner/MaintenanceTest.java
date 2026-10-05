package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;

/**
 * Tests the filesystem and output effects of each shared maintenance operation.
 */
public final class MaintenanceTest
{
	/**
	 * Creates the test.
	 */
	public MaintenanceTest()
	{
	}

	/**
	 * Verifies explicit deletion removes the selected terminal run.
	 *
	 * @throws IOException if fixture or maintenance I/O fails
	 */
	@Test
	public void deletesSelectedRun() throws IOException
	{
		try (ToolScope scope = new ToolScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(scope.temporaryWorkRoot()),
				"maintenance-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("harnessa");
			manager.markCompleted(run);

			execute(root, "--delete-run", "--run-id", run.id());

			requireThat(Files.notExists(run.directory()), "deleted").isTrue();
		}
	}

	/**
	 * Verifies the failure command records the supervisor's diagnostic and exit status.
	 *
	 * @throws IOException if fixture or maintenance I/O fails
	 */
	@Test
	public void recordsSupervisorFailure() throws IOException
	{
		try (ToolScope scope = new ToolScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(scope.temporaryWorkRoot()),
				"maintenance-"));
			HarnessRun run = new HarnessRunManager(root, Clock.systemUTC()).create("harnessa");
			Path manifest = run.directory().resolve("run.json");
			Files.writeString(manifest, Files.readString(manifest).replaceFirst(
				"\\\"owner_started_at\\\": \\\"[^\\\"]+\\\"", "\"owner_started_at\": \"1970-01-01T00:00:00Z\""));

			execute(root, "--fail-run", "--run-id", run.id(), "--diagnostic", "supervisor failure", "--exit-code", "17");

			requireThat(Files.readString(manifest), "manifest").contains("supervisor failure").
				contains("\"exit_code\": 17").contains("\"state\": \"failed\"");
		}
	}

	/**
	 * Verifies stale deletion removes an old terminal run and keeps a current run.
	 *
	 * @throws IOException if fixture or maintenance I/O fails
	 */
	@Test
	public void deletesOnlyStaleRuns() throws IOException
	{
		try (ToolScope scope = new ToolScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(scope.temporaryWorkRoot()),
				"maintenance-"));
			HarnessRunManager oldManager = new HarnessRunManager(root,
				Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC));
			HarnessRun old = oldManager.create("harnessa");
			oldManager.markCompleted(old);
			HarnessRun current = new HarnessRunManager(root, Clock.systemUTC()).create("harnessa");

			execute(root, "--delete-stale-runs", "--older-than", "PT1H");

			requireThat(Files.notExists(old.directory()), "oldDeleted").isTrue();
			requireThat(current.directory(), "currentRun").isDirectory();
		}
	}

	/**
	 * Verifies pruning retains running state and other harnesses while pruning the selected terminal runtime.
	 *
	 * @throws IOException if fixture or maintenance I/O fails
	 */
	@Test
	public void prunesSelectedTerminalRuntime() throws IOException
	{
		try (ToolScope scope = new ToolScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(scope.temporaryWorkRoot()),
				"maintenance-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun terminal = manager.create("harnessa");
			manager.markCompleted(terminal);
			HarnessRun running = manager.create("harnessa");
			HarnessRun other = manager.create("harnessb");
			manager.markCompleted(other);

			execute(root, "--prune-terminal-runtimes");

			requireThat(Files.notExists(terminal.directory().resolve("home")), "pruned").isTrue();
			requireThat(running.directory().resolve("home"), "runningRuntime").isDirectory();
			requireThat(other.directory().resolve("home"), "otherRuntime").isDirectory();
			requireThat(terminal.directory().resolve("project"), "evidence").isDirectory();
		}
	}

	/**
	 * Verifies cache verification creates and prints the supplied parent's npm cache.
	 *
	 * @throws IOException if fixture or maintenance I/O fails
	 */
	@Test
	public void verifiesDependencyCache() throws IOException
	{
		try (ToolScope scope = new ToolScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(Files.createDirectories(scope.temporaryWorkRoot()),
				"maintenance-"));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				HarnessRunnerMaintenance.execute(new String[]{"--verify-dependency-cache"}, "harnessa", home,
					Map.of("HOME", home.toString()), out);
			}

			requireThat(home.resolve(".npm"), "cache").isDirectory();
			requireThat(bytes.toString(StandardCharsets.UTF_8), "output").isEqualTo(home.resolve(".npm") +
				System.lineSeparator());
		}
	}

	/**
	 * Verifies unsupported commands do not select maintenance.
	 */
	@Test
	public void excludesUnsupportedCommand()
	{
		requireThat(HarnessRunnerMaintenance.isMaintenanceCommand(new String[]{"--prune-terminal-runtimes"}),
			"supported").isTrue();
		requireThat(HarnessRunnerMaintenance.isMaintenanceCommand(new String[]{"--unsupported"}), "excluded").isFalse();
	}

	/**
	 * Executes maintenance with isolated output and a caller-owned root.
	 *
	 * @param root the test-owned run root
	 * @param args the operation and its additional options
	 * @throws IOException if maintenance I/O fails
	 */
	private static void execute(Path root, String... args) throws IOException
	{
		String[] command = Arrays.copyOf(args, args.length + 2);
		command[args.length] = "--run-root";
		command[args.length + 1] = root.toString();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
		{
			HarnessRunnerMaintenance.execute(command, "harnessa", root, Map.of(), out);
		}
	}
}
