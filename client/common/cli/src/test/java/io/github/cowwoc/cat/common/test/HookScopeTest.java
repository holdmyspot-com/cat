package io.github.cowwoc.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.HookScope;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
			try (HookScope scope = new HookScope(directory, directory))
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
		try (HookScope scope = new HookScope(Path.of("missing-plugin-root"), Path.of("home")))
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
			try (HookScope scope = new HookScope(plugin, plugin))
			{
				Path actual = scope.projectDir(scope.json().createObjectNode().put("cwd", harnessDirectory.toString()),
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
			try (HookScope scope = new HookScope(directory, directory))
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
			try (HookScope scope = new HookScope(directory, directory))
			{
				requireThat(scope.yaml() == scope.yaml(), "sameMapper").isTrue();
			}
		}
	}

	/**
	 * Verifies a scope created from a hook environment resolves the plugin root and the user's rule directory, and
	 * accepts a {@code CAT_INSTALL_DIR} that names the plugin root.
	 */
	@Test
	public void resolvesTheHookEnvironment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			Map<String, String> environment = Map.of("ROOT", plugin + " ", "USER_HOME", home.toString(),
				"CAT_INSTALL_DIR", plugin.toString(), "OTHER", "value");
			try (HookScope scope = HookScope.fromEnvironment(environment, "Harness", "ROOT", "USER_HOME"))
			{
				requireThat(scope.pluginRoot(), "pluginRoot").isEqualTo(plugin.toAbsolutePath().normalize());
				requireThat(scope.userRulesRoot(), "userRulesRoot").
					isEqualTo(home.toAbsolutePath().normalize().resolve(".cat/rules"));
			}
		}
	}

	/**
	 * Verifies that a scope accepts the variables in which a jlink launcher saves the caller's locale, which it sets
	 * whenever the caller's locale is not UTF-8.
	 */
	@Test
	public void acceptsTheCallersSavedLocale() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Map<String, String> environment = Map.of("ROOT", plugin.toString(), "USER_HOME", plugin.toString(),
				"CAT_CALLER_LC_ALL", "+C", "CAT_CALLER_LC_CTYPE", "-");
			try (HookScope scope = HookScope.fromEnvironment(environment, "Harness", "ROOT", "USER_HOME"))
			{
				requireThat(scope.pluginRoot(), "pluginRoot").isEqualTo(plugin.toAbsolutePath().normalize());
			}
		}
	}

	/**
	 * Verifies a hook environment without the plugin root is rejected with the harness and the variable's name.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "Harness ROOT is not set")
	public void rejectsAnEnvironmentWithoutTheRoot() throws IOException
	{
		HookScope.fromEnvironment(Map.of("ROOT", " ", "USER_HOME", "home"), "Harness", "ROOT", "USER_HOME");
	}

	/**
	 * Verifies a hook environment without the home directory is rejected with the harness and the variable's name.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "Harness USER_HOME is not set")
	public void rejectsAnEnvironmentWithoutTheHome() throws IOException
	{
		HookScope.fromEnvironment(Map.of("ROOT", "plugin"), "Harness", "ROOT", "USER_HOME");
	}

	/**
	 * Verifies a hook environment that sets a CAT variable other than a matching {@code CAT_INSTALL_DIR} is rejected.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "CAT_INSTALL_DIR conflicts with Harness ROOT")
	public void rejectsAConflictingCatVariable() throws IOException
	{
		HookScope.fromEnvironment(Map.of("ROOT", "plugin", "USER_HOME", "home", "CAT_INSTALL_DIR", "other"), "Harness",
			"ROOT", "USER_HOME");
	}

	/**
	 * Verifies an empty hook input is read as an empty event.
	 */
	@Test
	public void readsAnEmptyInputAsAnEmptyEvent() throws IOException
	{
		try (HookScope scope = new HookScope(Path.of("plugin"), Path.of("home")))
		{
			requireThat(scope.readEvent("Event", new ByteArrayInputStream(new byte[0])).isEmpty(), "isEmpty").isTrue();
		}
	}

	/**
	 * Verifies a hook input larger than 1 MiB is rejected with the event's name.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "Event input is too large")
	public void rejectsAnOversizedInput() throws IOException
	{
		try (HookScope scope = new HookScope(Path.of("plugin"), Path.of("home")))
		{
			scope.readEvent("Event", new ByteArrayInputStream(new byte[1_048_577]));
		}
	}

	/**
	 * Verifies a hook input that is not a JSON object is rejected with the event's name.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "Event input must be a JSON object")
	public void rejectsAnInputThatIsNotAnObject() throws IOException
	{
		try (HookScope scope = new HookScope(Path.of("plugin"), Path.of("home")))
		{
			scope.readEvent("Event", new ByteArrayInputStream("[]".getBytes(UTF_8)));
		}
	}

	/**
	 * Verifies the subagent type is blank unless the event names it as text.
	 */
	@Test
	public void returnsTheSubagentTypeOnlyWhenItIsText() throws IOException
	{
		try (HookScope scope = new HookScope(Path.of("plugin"), Path.of("home")))
		{
			String named = scope.subagentType(scope.json().readTree("{\"agent_type\":\"reviewer\"}"));
			String numeric = scope.subagentType(scope.json().readTree("{\"agent_type\":1}"));
			String absent = scope.subagentType(scope.json().createObjectNode());

			requireThat(named, "named").isEqualTo("reviewer");
			requireThat(numeric, "numeric").isEmpty();
			requireThat(absent, "absent").isEmpty();
		}
	}
}
