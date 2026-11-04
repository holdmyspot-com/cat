package com.holdmyspot.cat.common.updater.cli.internal.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.CommandResult;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.cli.internal.ProcessHarnessCli;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	 * @throws IOException if the fake program cannot be written or run
	 */
	@Test
	public void runsRecordedProgram() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("harness-cli-")).toRealPath();
			Path home = Files.createDirectory(root.resolve("home"));
			Path program = root.resolve("Fake.java");
			Files.writeString(program, """
				public class Fake
				{
					public static void main(String[] args) throws Exception
					{
						for (String argument : args)
							System.out.println("arg=" + argument);
						System.out.println("directory=" + java.nio.file.Path.of("").toAbsolutePath());
						System.out.println("value=" + System.getenv("CAT_TEST_VALUE"));
						System.out.println("path=" + System.getenv("PATH"));
						System.out.println("input=" + System.in.read());
						System.err.println("diagnostic");
						System.exit(4);
					}
				}
				""");
			Map<String, String> environment = new HashMap<>();
			environment.put("CAT_TEST_VALUE", "recorded");
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot != null)
				environment.put("SystemRoot", systemRoot);
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			HarnessCommand command = new HarnessCommand(List.of(java.toString(), program.toString(), "fixed"),
				environment);

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
}
