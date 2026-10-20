package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;
import com.holdmyspot.cat.distribution.bundle.secrets.JlinkImageBuilderSecrets;
import org.testng.annotations.Test;

/**
 * Tests {@link JlinkImageBuilder}.
 */
public final class JlinkImageBuilderTest
{
	/**
	 * The lines that run Java in a UTF-8 {@code LC_CTYPE} when the caller's is not UTF-8, keeping the caller's locale for
	 * the programs that Java starts.
	 */
	private static final String LOCALE_SETUP = """
		case ${LC_ALL:-${LC_CTYPE:-${LANG:-}}} in
		  *[Uu][Tt][Ff]-8* | *[Uu][Tt][Ff]8*) ;;
		  *)
		    CAT_CALLER_LC_ALL=${LC_ALL+"+$LC_ALL"}
		    CAT_CALLER_LC_CTYPE=${LC_CTYPE+"+$LC_CTYPE"}
		    export CAT_CALLER_LC_ALL="${CAT_CALLER_LC_ALL:--}"
		    export CAT_CALLER_LC_CTYPE="${CAT_CALLER_LC_CTYPE:--}"
		    unset LC_ALL
		    export LC_CTYPE=C.UTF-8
		    ;;
		esac
		""";

	/**
	 * The options of every launcher's JVM: warnings on standard error only, performance data kept in the process, UTF-8
	 * output on every platform, and native access for the common module.
	 */
	private static final String VM_OPTIONS = "-Xlog:all=off:stdout -Xlog:all=warning:stderr " +
		"-XX:+PerfDisableSharedMem -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 " +
		"--enable-native-access=com.holdmyspot.cat.common.cli";

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
	 * Verifies that launcher rewriting runs Java directly, without Bubblewrap, keeps the JVM's performance data in the
	 * process, and runs Java in a UTF-8 {@code LC_CTYPE}.
	 */
	@Test
	public void rewritesLauncherWithPrivatePerfData() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FakeToolProvider tool = new FakeToolProvider(0, "");
			Path output = cleanup.add(Files.createTempDirectory(Path.of("target"), "jlink-builder-test"));
			Files.delete(output);
			JlinkImageBuilder builder = JlinkImageBuilderSecrets.access().create(tool);
			builder.build(List.of(Path.of("app.jar")), Set.of("app"),
				List.of(new JlinkImageBuilder.Launcher("read-part", "jdk.compiler", "com.sun.tools.javac.Main")), output);

			String launcher = Files.readString(output.resolve("bin/read-part"));
			requireThat(launcher, "launcher").
				contains("JLINK_VM_OPTIONS='" + VM_OPTIONS + "'").
				contains(LOCALE_SETUP + "exec \"$DIR/java\" -m jdk.compiler/com.sun.tools.javac.Main \"$@\"\n").
				doesNotContain("bwrap");
		}
	}

	/**
	 * Verifies that the batch launcher that jlink writes on Windows gets the same JVM options, and quotes the assignment
	 * of its folder, whose name may hold a character that {@code cmd.exe} reads as a command separator.
	 */
	@Test
	public void rewritesBatchLauncher() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FakeToolProvider tool = new FakeToolProvider(0, "");
			Path output = cleanup.add(Files.createTempDirectory(Path.of("target"), "jlink-builder-test"));
			Files.delete(output);
			JlinkImageBuilder builder = JlinkImageBuilderSecrets.access().create(tool);
			builder.build(List.of(Path.of("app.jar")), Set.of("app"),
				List.of(new JlinkImageBuilder.Launcher("read-part", "app", "example.Main")), output);

			requireThat(Files.readString(output.resolve("bin/read-part.bat")), "launcher").isEqualTo("""
				@echo off\r
				set JLINK_VM_OPTIONS=%s\r
				set "DIR=%%~dp0"\r
				"%%DIR%%\\java" %%JLINK_VM_OPTIONS%% -m app/example.Main %%*\r
				""".formatted(VM_OPTIONS));
		}
	}

	/**
	 * Verifies that removing the POSIX launchers fails when a launcher has no batch form to run instead.
	 */
	@Test
	public void removePosixLaunchersRequiresBatchForm() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path output = cleanup.add(Files.createTempDirectory(Path.of("target"), "jlink-builder-test"));
			Files.delete(output);
			JlinkImageBuilder.Launcher launcher = new JlinkImageBuilder.Launcher("read-part", "app", "example.Main");
			JlinkImageBuilderSecrets.access().create(new FakeToolProvider(0, "")).build(List.of(Path.of("app.jar")),
				Set.of("app"), List.of(launcher), output);
			Files.delete(output.resolve("bin/read-part.bat"));
			try
			{
				JlinkImageBuilder.removePosixLaunchers(List.of(launcher), output);
				throw new AssertionError("Expected a failure for the missing batch launcher");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains("no batch form");
			}
		}
	}

	/**
	 * Verifies that a symbolic link in an image becomes a regular file with its target's content, and that the target
	 * stays in place.
	 */
	@Test
	public void replacesLinksWithCopies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path image = cleanup.add(Files.createTempDirectory(Path.of("target"), "jlink-builder-test"));
			Path target = image.resolve("legal/java.base/LICENSE");
			Files.createDirectories(target.getParent());
			Files.writeString(target, "license text");
			Path link = image.resolve("legal/java.xml/LICENSE");
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, Path.of("../java.base/LICENSE"));

			JlinkImageBuilder.replaceLinksWithCopies(image);

			requireThat(Files.isSymbolicLink(link), "isSymbolicLink").isFalse();
			requireThat(Files.readString(link), "copy").isEqualTo("license text");
			requireThat(Files.readString(target), "target").isEqualTo("license text");
		}
	}

	/**
	 * Verifies that a symbolic link whose target is missing is reported instead of being copied or kept.
	 */
	@Test
	public void rejectsBrokenLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path image = cleanup.add(Files.createTempDirectory(Path.of("target"), "jlink-builder-test"));
			Path link = image.resolve("legal/java.xml/LICENSE");
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, Path.of("../java.base/LICENSE"));
			try
			{
				JlinkImageBuilder.replaceLinksWithCopies(image);
				throw new AssertionError("Expected a failure for the broken link");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains(link.toString());
			}
		}
	}

	/**
	 * Verifies that a moved launcher, in both forms, starts the image's Java from its new folder, that its batch form
	 * takes the extension {@code .cmd}, and that the launchers that do not move stay in the image.
	 */
	@Test
	public void movedLaunchersStartImageJava() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory(Path.of("target"), "jlink-builder-test"));
			Path image = plugin.resolve("client");
			JlinkImageBuilderSecrets.access().create(new FakeToolProvider(0, "")).build(List.of(Path.of("app.jar")),
				Set.of("app"), List.of(new JlinkImageBuilder.Launcher("read-part", "app", "example.Main"),
					new JlinkImageBuilder.Launcher("session-start", "app", "example.Hook")), image);

			JlinkImageBuilder.moveLaunchers(Set.of("read-part"), image, plugin.resolve("bin"));

			requireThat(Files.readString(plugin.resolve("bin/read-part")), "posixLauncher").
				contains("\nexec \"$DIR/../client/bin/java\" ").doesNotContain("\"$DIR/java\"");
			requireThat(Files.readString(plugin.resolve("bin/read-part.cmd")), "batchLauncher").
				contains("\"%DIR%\\..\\client\\bin\\java\" %JLINK_VM_OPTIONS% -m app/example.Main %*\r\n");
			requireThat(Files.exists(image.resolve("bin/read-part")), "imagePosixLauncher").isFalse();
			requireThat(Files.exists(image.resolve("bin/read-part.bat")), "imageBatchLauncher").isFalse();
			requireThat(image.resolve("bin/session-start"), "keptLauncher").isRegularFile();
		}
	}

	/**
	 * Verifies that moving a launcher that the image lacks fails.
	 */
	@Test
	public void moveLaunchersRequiresLauncher() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory(Path.of("target"), "jlink-builder-test"));
			Path image = plugin.resolve("client");
			JlinkImageBuilderSecrets.access().create(new FakeToolProvider(0, "")).build(List.of(Path.of("app.jar")),
				Set.of("app"), List.of(new JlinkImageBuilder.Launcher("read-part", "app", "example.Main")), image);
			try
			{
				JlinkImageBuilder.moveLaunchers(Set.of("run-part"), image, plugin.resolve("bin"));
				throw new AssertionError("Expected a failure for the missing launcher");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains("no launcher named run-part");
			}
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
						// jlink writes this launcher only on Windows, in this form
						Files.writeString(launcher.resolveSibling(name + ".bat"), "@echo off\r\nset JLINK_VM_OPTIONS=\r\n" +
							"set DIR=%~dp0\r\n\"%DIR%\\java\" %JLINK_VM_OPTIONS% -m " + target + " %*\r\n");
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
