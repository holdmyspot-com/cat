package io.github.cowwoc.cat.common.git.replay.test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

/**
 * Supplies portable external commands for output capture, working-directory, and heartbeat integration tests.
 */
public final class GateCommandFixture
{
	/**
	 * The command behavior selected by a test.
	 */
	enum Mode
	{
		/** Prints successful gate output. */
		SUCCESS,
		/** Prints a failure diagnostic and exits with code seven. */
		FAILURE,
		/** Prints the feature bytes from the command's working directory. */
		CHECK_FEATURE,
		/** Waits for the heartbeat's marker in the command's working directory. */
		WAIT_HEARTBEAT
	}

	/**
	 * Prevents construction of command fixtures.
	 */
	private GateCommandFixture()
	{
	}

	/**
	 * Uses the current Java launcher to run one fixture behavior. The fixture uses only the JDK, so the command puts
	 * only the directory or archive that holds this class on the class path and starts with the fastest startup
	 * settings: resolving the test module's whole module path more than quadruples the time that each process takes to
	 * start.
	 *
	 * @param mode the selected command behavior
	 * @return the portable command and its arguments
	 */
	static List<String> command(Mode mode)
	{
		String java = ProcessHandle.current().info().command().orElseThrow();
		String classPath;
		try
		{
			classPath = Path.of(GateCommandFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).
				toString();
		}
		catch (URISyntaxException e)
		{
			throw new AssertionError(e);
		}
		return List.of(java, "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-XX:-UsePerfData", "-Xshare:auto",
			"--class-path", classPath, GateCommandFixture.class.getName(), mode.name());
	}

	/**
	 * Runs the selected command behavior.
	 *
	 * @param args the fixture mode
	 * @throws IOException if command input cannot be read or no heartbeat arrives
	 * @throws InterruptedException if waiting for a heartbeat is interrupted
	 */
	public static void main(String[] args) throws IOException, InterruptedException
	{
		switch (Mode.valueOf(args[0]))
		{
			case SUCCESS -> System.out.print("passed");
			case FAILURE ->
			{
				System.err.println("diagnostic");
				System.exit(7);
			}
			case CHECK_FEATURE -> System.out.print(Files.readString(Path.of("feature.txt")));
			case WAIT_HEARTBEAT -> awaitHeartbeat();
		}
	}

	/**
	 * Waits for a bounded filesystem notification, accounting for a marker created before registration.
	 *
	 * @throws IOException if no heartbeat marker arrives within the fixture deadline
	 * @throws InterruptedException if the filesystem wait is interrupted
	 */
	private static void awaitHeartbeat() throws IOException, InterruptedException
	{
		Path marker = Path.of("heartbeat");
		if (Files.isRegularFile(marker))
			return;
		try (WatchService events = marker.getFileSystem().newWatchService())
		{
			Path.of(".").register(events, ENTRY_CREATE);
			if (!Files.isRegularFile(marker))
				events.poll(500, TimeUnit.MILLISECONDS);
			if (!Files.isRegularFile(marker))
				throw new IOException("Command did not receive its heartbeat marker");
		}
	}
}
