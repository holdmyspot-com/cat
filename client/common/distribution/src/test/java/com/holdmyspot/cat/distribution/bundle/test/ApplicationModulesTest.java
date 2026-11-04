package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.distribution.bundle.ApplicationModules;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests {@link ApplicationModules}.
 */
public final class ApplicationModulesTest
{
	/** Creates a test instance. */
	public ApplicationModulesTest()
	{
	}

	/**
	 * Verifies that an application ships the modules that its root requires but not a provider that only service
	 * binding reaches, and that it needs exactly the JDK modules that its shipped modules require: none that only an
	 * unshipped module requires, and none that only service binding reaches.
	 */
	@Test
	public void separatesApplicationAndJdkModules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("application-modules-"));
			Path library = compile(directory, "library", Map.of(
				"module-info.java", "module library { requires java.net.http; exports library; }",
				"library/Service.java", "package library; public interface Service {}"));
			Path app = compile(directory, "app", Map.of(
				"module-info.java", "module app { requires java.logging; requires library; uses library.Service; }"),
				library);
			Path provider = compile(directory, "provider", Map.of(
				"module-info.java", "module provider { requires library; requires java.sql; " +
					"provides library.Service with provider.Implementation; }",
				"provider/Implementation.java", "package provider; public final class Implementation " +
					"implements library.Service {}"), library);
			Path unrelated = compile(directory, "unrelated", Map.of(
				"module-info.java", "module unrelated { requires java.sql; }"));

			ApplicationModules modules = ApplicationModules.resolve(List.of(unrelated, provider, library, app),
				Set.of("app"));

			requireThat(modules.jars(), "jars").isEqualTo(List.of(app, library));
			requireThat(modules.systemModules(), "systemModules").isEqualTo(new TreeSet<>(Set.of("java.base",
				"java.logging", "java.net.http")));
		}
	}

	/**
	 * Verifies that an application whose root is missing is reported.
	 */
	@Test
	public void reportsMissingModule()
	{
		try
		{
			ApplicationModules.resolve(List.of(), Set.of("no.such.module"));
			throw new AssertionError("Expected a failure for the missing module");
		}
		catch (IOException failure)
		{
			requireThat(failure.getMessage(), "failureMessage").contains("no.such.module");
		}
	}

	/**
	 * Verifies that the runtime requirement names the build's Java feature release and the application's JDK modules.
	 */
	@Test
	public void writesRuntimeRequirement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path file = cleanup.add(Files.createTempDirectory("application-modules-")).resolve("requirement.json");
			new ApplicationModules(List.of(), new TreeSet<>(Set.of("java.xml", "java.base"))).
				writeRequirement(file, scope.json());

			JsonNode requirement = scope.json().readTree(file.toFile());
			requireThat(requirement.get("format").asInt(), "format").isEqualTo(1);
			requireThat(requirement.get("java_feature").asInt(), "javaFeature").isEqualTo(Runtime.version().feature());
			requireThat(requirement.get("modules").toString(), "modules").isEqualTo("[\"java.base\",\"java.xml\"]");
			requireThat(Files.readString(file), "content").endsWith("\n");
		}
	}

	/**
	 * Compiles one module into an exploded module directory.
	 *
	 * @param directory  the test's directory
	 * @param name       the module's name
	 * @param sources    the module's source files by path
	 * @param modulePath the modules that the module requires
	 * @return the exploded module
	 * @throws IOException if the module cannot be compiled
	 */
	private static Path compile(Path directory, String name, Map<String, String> sources, Path... modulePath)
		throws IOException
	{
		Path source = directory.resolve("source").resolve(name);
		List<String> arguments = new ArrayList<>(List.of("-d", directory.resolve(name).toString()));
		if (modulePath.length > 0)
		{
			arguments.add("--module-path");
			arguments.add(String.join(File.pathSeparator, Arrays.stream(modulePath).
				map(Path::toString).toList()));
		}
		for (Map.Entry<String, String> file : sources.entrySet())
		{
			Path path = source.resolve(file.getKey());
			Files.createDirectories(path.getParent());
			Files.writeString(path, file.getValue());
			arguments.add(path.toString());
		}
		ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, UTF_8))
		{
			int status = javac.run(err, err, arguments.toArray(String[]::new));
			requireThat(status, "javacStatus").withContext(diagnostics.toString(UTF_8),
				"diagnostics").isEqualTo(0);
		}
		return directory.resolve(name);
	}
}
