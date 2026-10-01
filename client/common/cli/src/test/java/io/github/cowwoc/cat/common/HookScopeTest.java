package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link HookScope}.
 */
public final class HookScopeTest
{
	/**
	 * Creates a test instance.
	 */
	public HookScopeTest()
	{
	}

	/**
	 * Verifies the scope returns the normalized CAT installation directory for its hook invocation.
	 */
	@Test
	public void returnsNormalizedPluginRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			try (HookScope scope = new HookScope(directory))
			{
				requireThat(scope.pluginRoot(), "pluginRoot").isEqualTo(directory.toAbsolutePath().normalize());
			}
		}
	}

	/**
	 * Verifies a hook scope rejects a plugin root that is not a directory.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "CAT installation directory is not a directory")
	public void rejectsInvalidPluginRoot() throws IOException
	{
		try (HookScope scope = new HookScope(Path.of("missing-plugin-root")))
		{
			scope.pluginRoot();
		}
	}

	/**
	 * Verifies the scope prefers the harness SessionStart directory over the configured project directory.
	 */
	@Test
	public void prefersHarnessProjectRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Path harnessDirectory = cleanup.add(Files.createTempDirectory("cat-harness-project-"));
			try (HookScope scope = new HookScope(plugin))
			{
				Path actual = scope.projectDir(scope.json().readTree("{\"cwd\":\"" + harnessDirectory + "\"}"),
					Path.of("."));

				requireThat(actual, "projectRoot").isEqualTo(harnessDirectory.toAbsolutePath().normalize());
			}
		}
	}

	/**
	 * Verifies the scope shares one JSON mapper for its entire hook invocation.
	 */
	@Test
	public void sharesJsonMapper() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			try (HookScope scope = new HookScope(directory))
			{
				requireThat(scope.json() == scope.json(), "sameMapper").isTrue();
			}
		}
	}

	/**
	 * Verifies the scope reuses one YAML mapper for its hook invocation.
	 */
	@Test
	public void sharesYamlMapper() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			try (HookScope scope = new HookScope(directory))
			{
				requireThat(scope.yaml() == scope.yaml(), "sameMapper").isTrue();
			}
		}
	}
}
