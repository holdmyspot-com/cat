package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests the description of how an installation trains a plugin's startup cache.
 */
public final class StartupCacheTrainingTest
{
	/**
	 * The input that a harness gives its session-start hook when a new session starts.
	 */
	private static final String INPUT = "{\"session_id\":\"00000000-0000-4000-8000-000000000000\"," +
		"\"hook_event_name\":\"SessionStart\",\"source\":\"startup\"}";

	/** Creates a test instance. */
	public StartupCacheTrainingTest()
	{
	}

	/**
	 * Returns each harness with the module and main class of its session-start program and the variable that names
	 * its plugin directory to its hooks.
	 *
	 * @return the harnesses
	 */
	@DataProvider
	public static Object[][] harnesses()
	{
		return new Object[][]
		{
			{"claude", "com.holdmyspot.cat.claude.cli/com.holdmyspot.cat.claude.ClaudeSessionStartHook",
				"CLAUDE_PLUGIN_ROOT"},
			{"codex", "com.holdmyspot.cat.codex.cli/com.holdmyspot.cat.codex.CodexSessionStartHook", "PLUGIN_ROOT"}
		};
	}

	/**
	 * Verifies that a plugin's training runs its session-start program as its launcher does, with the variable that
	 * names the plugin directory to the harness's hooks and the input of a new session.
	 *
	 * @param harness      the plugin's harness
	 * @param program      the session-start program's module and main class
	 * @param rootVariable the variable that names the plugin directory
	 * @throws IOException if the description cannot be written or read
	 */
	@Test(dataProvider = "harnesses")
	public void trainsSessionStartProgram(String harness, String program, String rootVariable) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("startup-cache-training-"));
			Path client = Files.createDirectories(plugin.resolve("client"));
			DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();

			access.writeStartupCacheTraining(client, harness, scope.json());

			JsonNode training = scope.json().readTree(Files.readString(plugin.resolve(StartupCache.TRAINING)));
			requireThat(training.propertyNames(), "members").
				containsExactly(List.of("format", "arguments", "plugin_root_variable", "input"));
			requireThat(training.get("format").intValue(), "format").isEqualTo(1);
			requireThat(scope.json().convertValue(training.get("arguments"), String[].class), "arguments").
				isEqualTo(new String[]{"--add-modules", "org.slf4j.nop", "-m", program});
			requireThat(training.get("plugin_root_variable").stringValue(), "pluginRootVariable").
				isEqualTo(rootVariable);
			requireThat(training.get("input").stringValue(), "input").isEqualTo(INPUT);
		}
	}
}
