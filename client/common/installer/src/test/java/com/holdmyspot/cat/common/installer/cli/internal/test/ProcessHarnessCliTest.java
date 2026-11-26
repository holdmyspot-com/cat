package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessHarnessCli;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

/**
 * Tests that the recorded harness program runs with exactly its recorded command and environment.
 */
public final class ProcessHarnessCliTest
{
	/**
	 * Creates the test.
	 */
	public ProcessHarnessCliTest()
	{
	}

	/**
	 * The harness program receives the recorded prefix followed by the arguments, runs in the harness home with only
	 * the recorded environment and a closed standard input, and its exit status and combined output are returned.
	 *
	 * @throws IOException if the fake program cannot be run
	 */
	@Test
	public void runsRecordedProgram() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("harness-cli-")).toRealPath();
			Path home = Files.createDirectory(root.resolve("home"));
			Map<String, String> environment = new HashMap<>();
			environment.put("CAT_TEST_VALUE", "recorded");
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot != null)
				environment.put("SystemRoot", systemRoot);
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			HarnessCommand command = new HarnessCommand(List.of(java.toString(), "-XX:+PerfDisableSharedMem", "-cp",
				testClasses().toString(),
				FakeHarnessProgram.class.getName(), "fixed"), environment);

			CommandResult result = new ProcessHarnessCli(command, home).run(List.of("plugin", "a b"));

			requireThat(result.exitCode(), "exitCode").isEqualTo(4);
			String output = result.output();
			requireThat(output, "output").contains("arg=fixed").contains("arg=plugin").contains("arg=a b").
				contains("directory=" + home).contains("value=recorded").contains("path=null").
				contains("input=-1").contains("diagnostic");
			requireThat(output.indexOf("arg=fixed"), "fixedPosition").isLessThan(output.indexOf("arg=plugin"));
			requireThat(output.indexOf("arg=plugin"), "pluginPosition").isLessThan(output.indexOf("arg=a b"));
		}
	}

	/**
	 * A started harness program receives the recorded prefix followed by the arguments and runs in the harness home
	 * with only the recorded environment. It reads what the caller writes to its standard input, and the caller reads
	 * its standard output, without its standard error.
	 *
	 * @throws IOException          if the fake program cannot be run
	 * @throws InterruptedException if the test is interrupted while the program runs
	 */
	@Test
	public void startsRecordedProgram() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("harness-cli-")).toRealPath();
			Path home = Files.createDirectory(root.resolve("home"));
			Map<String, String> environment = new HashMap<>();
			environment.put("CAT_TEST_VALUE", "recorded");
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot != null)
				environment.put("SystemRoot", systemRoot);
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			HarnessCommand command = new HarnessCommand(List.of(java.toString(), "-XX:+PerfDisableSharedMem", "-cp",
				testClasses().toString(), FakeHarnessProgram.class.getName(), "fixed"), environment);

			try (Process process = new ProcessHarnessCli(command, home).start(List.of("app-server")))
			{
				try (OutputStream input = process.getOutputStream())
				{
					input.write('x');
				}
				String output = new String(process.getInputStream().readAllBytes(), UTF_8);
				requireThat(process.waitFor(1, TimeUnit.MINUTES), "exited").isTrue();

				requireThat(process.exitValue(), "exitCode").isEqualTo(4);
				requireThat(output, "output").contains("arg=fixed").contains("arg=app-server").
					contains("directory=" + home).contains("value=recorded").contains("path=null").
					contains("input=" + (int) 'x').doesNotContain("diagnostic");
			}
		}
	}

	/**
	 * Returns the directory that holds the compiled test classes, which include the fake harness program.
	 *
	 * @return the directory
	 */
	private static Path testClasses()
	{
		try
		{
			return Path.of(FakeHarnessProgram.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (URISyntaxException e)
		{
			throw new AssertionError(e);
		}
	}
}
