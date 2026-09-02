package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;
import io.github.cowwoc.cat.distribution.bundle.secrets.JlinkImageBuilderSecrets;
import org.testng.annotations.Test;

/**
 * Tests {@link JlinkImageBuilder}.
 */
public final class JlinkImageBuilderTest
{
	/** Creates a test instance. */
	public JlinkImageBuilderTest()
	{
	}

	/**
	 * Verifies sorted, deterministic jlink arguments are passed to the injected tool.
	 */
	@Test
	public void passesSortedJlinkArgumentsToTool() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FakeToolProvider tool = new FakeToolProvider(0, "");
			Path output = cleanup.add(Files.createTempDirectory("jlink-builder-test"));
			Files.delete(output);
			JlinkImageBuilder builder = JlinkImageBuilderSecrets.access().create(tool);
			builder.build(List.of(Path.of("b.jar"), Path.of("a.jar")), Set.of("second", "first"),
				List.of(new JlinkImageBuilder.Launcher("plugin-info", "first", "example.Main")), output);

			requireThat(tool.arguments, "jlinkArguments").isEqualTo(List.of("--module-path",
				"a.jar" + File.pathSeparator + "b.jar", "--add-modules", "first,second", "--launcher",
				"plugin-info=first/example.Main", "--output", output.toAbsolutePath().normalize().toString(),
				"--strip-debug", "--no-header-files", "--no-man-pages"));
		}
	}

	/**
	 * Verifies generated launchers preserve caller paths while isolating process and device namespaces.
	 */
	@Test
	public void preservesPathsAndKeepsPerfData() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FakeToolProvider tool = new FakeToolProvider(0, "");
			Path output = cleanup.add(Files.createTempDirectory(Path.of("target"), "jlink-builder-test"));
			Files.delete(output);
			JlinkImageBuilder builder = JlinkImageBuilderSecrets.access().create(tool);
			builder.build(List.of(Path.of("app.jar")), Set.of("app"),
				List.of(new JlinkImageBuilder.Launcher("plugin-info", "jdk.compiler", "com.sun.tools.javac.Main")), output);

			String launcher = Files.readString(output.resolve("bin/plugin-info"));
			requireThat(launcher, "launcher").
				contains("bwrap --bind / / --proc /proc --dev /dev --").
				doesNotContain("--tmpfs /tmp").
				doesNotContain("dirname").
				contains("JLINK_VM_OPTIONS='-Xlog:all=off:stdout -Xlog:all=warning:stderr'").
				doesNotContain("PerfDisableSharedMem");
			try (Process first = new ProcessBuilder(output.resolve("bin/plugin-info").toString(), "-version").
				redirectErrorStream(true).start();
				 Process second = new ProcessBuilder(output.resolve("bin/plugin-info").toString(), "-version").
					redirectErrorStream(true).start())
			{
				String firstOutput = awaitSuccessfulLauncher(first);
				String secondOutput = awaitSuccessfulLauncher(second);
				requireThat(firstOutput, "firstOutput").contains("javac").doesNotContain("perf,memops").
					doesNotContain("hsperfdata");
				requireThat(secondOutput, "secondOutput").contains("javac").doesNotContain("perf,memops").
					doesNotContain("hsperfdata");
			}
		}
	}

	/**
	 * Verifies concurrent Maven-wrapper JVMs retain HotSpot performance data without sharing its backing directory.
	 */
	@Test
	public void isolatesMavenWrapperPerformanceData() throws IOException
	{
		Path mavenWrapper = Path.of("../..", "mvnw").toAbsolutePath().normalize();
		try (Process first = new ProcessBuilder(mavenWrapper.toString(), "--version").redirectErrorStream(true).start();
			 Process second = new ProcessBuilder(mavenWrapper.toString(), "--version").redirectErrorStream(true).start())
		{
			String firstOutput = awaitSuccessfulLauncher(first);
			String secondOutput = awaitSuccessfulLauncher(second);
			requireThat(firstOutput, "firstOutput").contains("Apache Maven").doesNotContain("perf,memops").
				doesNotContain("hsperfdata");
			requireThat(secondOutput, "secondOutput").contains("Apache Maven").doesNotContain("perf,memops").
				doesNotContain("hsperfdata");
		}
	}

	/**
	 * Waits for one packaged launcher and returns its combined diagnostic output.
	 *
	 * @param process the started launcher
	 * @return the launcher's output
	 * @throws IOException if waiting is interrupted or the launcher fails
	 */
	private static String awaitSuccessfulLauncher(Process process) throws IOException
	{
		try
		{
			int exitCode = process.waitFor();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (exitCode != 0)
				throw new IOException("Isolated launcher failed: " + output);
			return output;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while verifying the isolated launcher", e);
		}
	}

	/**
	 * Verifies jlink diagnostics are included in failures.
	 */
	@Test
	public void reportsToolDiagnostics() throws IOException
	{
		try
		{
			JlinkImageBuilderSecrets.access().create(new FakeToolProvider(7, "bad module")).build(List.of(Path.of("app.jar")),
				Set.of("app"), List.of(), Path.of("target/runtime"));
			throw new AssertionError("Expected jlink failure");
		}
		catch (IOException failure)
		{
			requireThat(failure.getMessage(), "failureMessage").contains("exit code 7").contains("bad module");
		}
	}

	/**
	 * A controllable in-process jlink tool.
	 */
	private static final class FakeToolProvider implements ToolProvider
	{
		private final int exitCode;
		private final String diagnostic;
		private List<String> arguments = List.of();

		/**
		 * Creates a tool provider with a predetermined failure result.
		 *
		 * @param exitCode the exit code to return
		 * @param diagnostic the diagnostic text to write
		 */
		private FakeToolProvider(int exitCode, String diagnostic)
		{
			assert that(diagnostic, "diagnostic").isNotNull().elseThrow();
			this.exitCode = exitCode;
			this.diagnostic = diagnostic;
		}

		@Override
		public String name()
		{
			return "jlink";
		}

		@Override
		public int run(PrintWriter out, PrintWriter err, String... args)
		{
			requireThat(out, "out").isNotNull();
			requireThat(err, "err").isNotNull();
			requireThat(args, "args").isNotNull();
			arguments = new ArrayList<>(List.of(args));
			err.print(diagnostic);
			if (exitCode == 0)
			{
				try
				{
					Path output = Path.of(arguments.get(arguments.indexOf("--output") + 1));
					Files.createDirectories(output.resolve("bin"));
					Path java = output.resolve("bin/java");
					Files.createSymbolicLink(java, Path.of(System.getProperty("java.home"), "bin/java"));
					for (int index = 0; index < arguments.size(); ++index)
					{
						if (!arguments.get(index).equals("--launcher"))
							continue;
						index += 1;
						String specification = arguments.get(index);
						String name = specification.substring(0, specification.indexOf('='));
						String target = specification.substring(specification.indexOf('=') + 1);
						Path launcher = output.resolve("bin").resolve(name);
						Files.writeString(launcher,
							"#!/bin/sh\nJLINK_VM_OPTIONS=\nDIR=`dirname $0`\n$DIR/java -m " + target + " \"$@\"\n");
						requireThat(launcher.toFile().setExecutable(true), "launcherExecutable").isTrue();
					}
				}
				catch (IOException exception)
				{
					throw new AssertionError(exception);
				}
			}
			return exitCode;
		}
	}
}
