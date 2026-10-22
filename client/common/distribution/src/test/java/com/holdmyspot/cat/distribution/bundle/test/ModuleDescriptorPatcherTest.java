package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.ModuleDescriptorPatcher;
import com.holdmyspot.cat.distribution.bundle.secrets.ModuleDescriptorPatcherAccess;
import com.holdmyspot.cat.distribution.bundle.secrets.ModuleDescriptorPatcherSecrets;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link ModuleDescriptorPatcher}.
 */
public final class ModuleDescriptorPatcherTest
{
	/** Creates a test instance. */
	public ModuleDescriptorPatcherTest()
	{
	}

	/**
	 * Verifies injected JDK tools are invoked in process.
	 */
	@Test
	public void invokesInjectedJdkToolsInProcess() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-module-patch-"));
		Path source = Files.writeString(temporary.resolve("source.jar"), "jar");
		Path destination = temporary.resolve("modules/dependency.jar");
		FakeToolProvider javac = new FakeToolProvider("javac", 0, "");
		FakeToolProvider jar = new FakeToolProvider("jar", 0, "");

		Path result = access().patch(javac, jar, source, destination, "example.module",
			Set.of("example.two", "example.one"));

		requireThat(result, "result").isEqualTo(destination.toAbsolutePath().normalize());
		requireThat(Files.readString(result), "patchedJarContent").isEqualTo("jar");
		Path workspace = result.getParent().resolve(".module-info/example.module");
		requireThat(javac.arguments, "javacArguments").isEqualTo(List.of("--patch-module", "example.module=" + result,
			"-d", workspace.resolve("classes").toString(), workspace.resolve("module-info.java").toString()));
		requireThat(jar.arguments, "jarArguments").isEqualTo(List.of("--update", "--file", result.toString(), "-C",
			workspace.resolve("classes").toString(), "module-info.class"));
			requireThat(Files.readString(workspace.resolve("module-info.java")), "moduleDescriptor").isEqualTo("""
			module example.module
			{
			\texports example.one;
			\texports example.two;
			}
			""".replace("\n", System.lineSeparator()));
		}
	}

	/**
	 * Verifies compiler diagnostics stop the JAR update.
	 */
	@Test
	public void compilerFailureStopsJarUpdate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-module-patch-failure-"));
		Path source = Files.writeString(temporary.resolve("source.jar"), "jar");
		FakeToolProvider jar = new FakeToolProvider("jar", 0, "");
		try
		{
			access().patch(new FakeToolProvider("javac", 2, "bad descriptor"), jar, source,
				temporary.resolve("dependency.jar"), "example.module", Set.of("example.api"));
			throw new AssertionError("Expected javac failure");
		}
		catch (IOException failure)
		{
			requireThat(failure.getMessage(), "failureMessage").contains("javac failed with exit code 2").
				contains("bad descriptor");
		}

			requireThat(jar.arguments, "jarArguments").isEmpty();
		}
	}

	/**
	 * Returns the controlled test access point.
	 *
	 * @return the module-descriptor patcher access point
	 */
	private static ModuleDescriptorPatcherAccess access()
	{
		return ModuleDescriptorPatcherSecrets.getModuleDescriptorPatcherAccess();
	}

	/**
	 * A controllable in-process JDK tool.
	 */
	private static final class FakeToolProvider implements ToolProvider
	{
		private final String name;
		private final int exitCode;
		private final String diagnostic;
		private List<String> arguments = List.of();

		/**
		 * Creates a tool provider with a predetermined result.
		 *
		 * @param name the tool name
		 * @param exitCode the exit code to return
		 * @param diagnostic the diagnostic text to write
		 */
		private FakeToolProvider(String name, int exitCode, String diagnostic)
		{
			assert that(name, "name").isNotBlank().elseThrow();
			assert that(diagnostic, "diagnostic").isNotNull().elseThrow();
			this.name = name;
			this.exitCode = exitCode;
			this.diagnostic = diagnostic;
		}

		@Override
		public String name()
		{
			return name;
		}

		@Override
		public int run(PrintWriter out, PrintWriter err, String... args)
		{
			requireThat(out, "out").isNotNull();
			requireThat(err, "err").isNotNull();
			requireThat(args, "args").isNotNull();
			arguments = new ArrayList<>(List.of(args));
			err.print(diagnostic);
			return exitCode;
		}
	}
}
