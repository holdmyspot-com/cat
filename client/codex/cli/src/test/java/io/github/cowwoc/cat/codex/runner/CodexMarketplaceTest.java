package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexMarketplace}.
 */
public final class CodexMarketplaceTest
{
	/**
	 * Verifies the Codex-specific integration creates its marketplace metadata outside the common module.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void createsMarketplaceMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
			Files.writeString(artifact.resolve("README.md"), "artifact");
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));

			Path marketplace = CodexMarketplace.create(artifact, home);
			Path plugin = CodexMarketplace.pluginRoot(home);

			requireThat(plugin, "pluginRoot").isEqualTo(marketplace.resolve("plugins/cat"));
			requireThat(Files.readString(plugin.resolve("README.md")), "artifactContents").
				isEqualTo("artifact");
			requireThat(Files.readString(marketplace.resolve(".agents/plugins/marketplace.json")), "metadata").isEqualTo("""
			{
			\t"name": "cat",
			\t"interface":
			\t{
			\t\t"displayName": "CAT"
			\t},
			\t"plugins":
			\t[
			\t\t{
			\t\t\t"name": "cat",
			\t\t\t"source":
			\t\t\t{
			\t\t\t\t"source": "local",
			\t\t\t\t"path": "./plugins/cat"
			\t\t\t},
			\t\t\t"policy":
			\t\t\t{
			\t\t\t\t"installation": "INSTALLED_BY_DEFAULT",
			\t\t\t\t"authentication": "ON_INSTALL"
			\t\t\t},
			\t\t\t"category": "Productivity"
			\t\t}
			\t]
			}
			""");
		}
	}

	/**
	 * Verifies the documented shell action assigns CAT_PLUGIN_ROOT before expanding it to read an installed rule.
	 *
	 * @throws IOException if the plugin or shell action cannot be read or run
	 * @throws InterruptedException if the shell action is interrupted
	 */
	@Test
	public void readsInstalledRuleWithDocumentedShell() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
			Files.createDirectories(artifact.resolve("rules/codex"));
			Files.writeString(artifact.resolve("rules/codex/skill-env.md"), "installed rule");
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			CodexMarketplace.create(artifact, home);

			String instructions;
			try (InputStream input = ClassLoader.getSystemResourceAsStream("rules/codex/skill-env.md"))
			{
				requireThat(input, "skillEnvironment").isNotNull();
				instructions = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			}
			String marker = "# Read an installed CAT file in an ordinary Codex tool shell\n";
			int start = instructions.indexOf(marker);
			requireThat(start, "shellActionStart").isNotNegative();
			int end = instructions.indexOf("\n```", start);
			requireThat(end, "shellActionEnd").isGreaterThan(start);
			String command = instructions.substring(start + marker.length(), end);

			ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
			builder.environment().put("CODEX_HOME", home.toString());
			builder.environment().remove("CAT_PLUGIN_ROOT");
			try (Process process = builder.start())
			{
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				requireThat(process.waitFor(), "shellExitCode").isEqualTo(0);
				requireThat(output, "shellOutput").isEqualTo("installed rule");
			}
		}
	}
}
