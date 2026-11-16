package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.CodexHookApprovals;
import com.holdmyspot.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link CodexHookApprovals}.
 */
public final class CodexHookApprovalsTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * A hook hash in the form that Codex records.
	 */
	private static final String HASH = "sha256:" + "a".repeat(64);
	/**
	 * The hooks of the plugin {@code docs}: two SessionStart handlers in one group and one PostToolUse handler.
	 */
	private static final String HOOKS = """
		{
			"hooks":
			{
				"SessionStart":
				[
					{
						"hooks":
						[
							{"type": "command", "command": "./start.sh"},
							{"type": "command", "command": "./log.sh"}
						]
					}
				],
				"PostToolUse":
				[
					{
						"matcher": "^Bash$",
						"hooks": [{"type": "command", "command": "./check.sh"}]
					}
				]
			}
		}
		""";

	/**
	 * Creates the test.
	 */
	public CodexHookApprovalsTest()
	{
	}

	/**
	 * Verifies that each hook without a recorded approval is listed in declaration order, that an approval from any
	 * marketplace counts, and that an entry without a hash or an approval of another plugin does not.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-W12
	@Test
	public void listsHooksWithoutApproval() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Files.createDirectories(plugin.resolve(".codex-plugin"));
			Files.writeString(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\": \"docs\"}", UTF_8);
			Files.createDirectories(plugin.resolve("hooks"));
			Files.writeString(plugin.resolve("hooks/hooks.json"), HOOKS, UTF_8);
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			Files.writeString(home.resolve("config.toml"), """
				[hooks.state."docs@user-market:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "%1$s"

				[hooks.state."docs@user-market:hooks/hooks.json:session_start:0:1"]
				enabled = true

				[hooks.state."other@user-market:hooks/hooks.json:post_tool_use:0:0"]
				trusted_hash = "%1$s"
				""".formatted(HASH), UTF_8);

			List<String> unapproved = CodexHookApprovals.unapproved(plugin, home, JSON);

			requireThat(unapproved, "unapproved").isEqualTo(List.of("SessionStart `./log.sh`",
				"PostToolUse `./check.sh`"));
		}
	}

	/**
	 * Verifies that the hooks file that the manifest names replaces the default one in the hooks' approval keys.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-W12
	@Test
	public void readsManifestHooksPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Files.createDirectories(plugin.resolve(".codex-plugin"));
			Files.writeString(plugin.resolve(".codex-plugin/plugin.json"),
				"{\"name\": \"docs\", \"hooks\": \"./config/hooks.json\"}", UTF_8);
			Files.createDirectories(plugin.resolve("config"));
			Files.writeString(plugin.resolve("config/hooks.json"), HOOKS, UTF_8);
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			Files.writeString(home.resolve("config.toml"), """
				[hooks.state."docs@docs:config/hooks.json:session_start:0:0"]
				trusted_hash = "%1$s"

				[hooks.state."docs@docs:config/hooks.json:session_start:0:1"]
				trusted_hash = "%1$s"
				""".formatted(HASH), UTF_8);

			List<String> unapproved = CodexHookApprovals.unapproved(plugin, home, JSON);

			requireThat(unapproved, "unapproved").isEqualTo(List.of("PostToolUse `./check.sh`"));
		}
	}

	/**
	 * Verifies that malformed Codex configuration fails before hook approvals are read.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsInvalidApprovalConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Files.createDirectories(plugin.resolve(".codex-plugin"));
			Files.writeString(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\": \"docs\"}", UTF_8);
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			Files.writeString(home.resolve("config.toml"), """
				[hooks.state."docs@market:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "%1$s"
				invalid = [
				""".formatted(HASH), UTF_8);

			CodexHookApprovals.unapproved(plugin, home, JSON);
		}
	}

	/**
	 * Verifies that the required plugin manifest rejects absence and any present non-object or invalid JSON.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void rejectsInvalidManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Path manifest = Files.createDirectories(plugin.resolve(".codex-plugin")).resolve("plugin.json");
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			for (String invalid : List.of("", "null", "[]", "{not json", "{} {}"))
			{
				Files.writeString(manifest, invalid, UTF_8);
				IOException failure = null;
				try
				{
					CodexHookApprovals.unapproved(plugin, home, JSON);
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "manifestFailure").isNotNull();
				requireThat(failure.getMessage(), "manifestMessage").contains(
					manifest.toAbsolutePath().normalize().toString());
			}
			Files.delete(manifest);
			IOException missing = null;
			try
			{
				CodexHookApprovals.unapproved(plugin, home, JSON);
			}
			catch (IOException e)
			{
				missing = e;
			}
			requireThat(missing, "missingManifest").isNotNull();
			requireThat(missing.getMessage(), "missingMessage").contains(
				manifest.toAbsolutePath().normalize().toString());
		}
	}

	/**
	 * Verifies that present malformed hooks fail, while an absent hooks file named by the manifest is skipped.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void rejectsMalformedHooksFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Files.createDirectories(plugin.resolve(".codex-plugin"));
			Files.writeString(plugin.resolve(".codex-plugin/plugin.json"),
				"{\"name\": \"docs\", \"hooks\": \"hooks.json\"}", UTF_8);
			Path hooks = plugin.resolve("hooks.json");
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			requireThat(CodexHookApprovals.unapproved(plugin, home, JSON), "missingHooks").isEmpty();
			Files.writeString(hooks, "{not json", UTF_8);
			IOException malformed = null;
			try
			{
				CodexHookApprovals.unapproved(plugin, home, JSON);
			}
			catch (IOException e)
			{
				malformed = e;
			}
			requireThat(malformed, "malformedHooks").isNotNull();
			requireThat(malformed.getMessage(), "malformedHooksMessage").contains(
				hooks.toAbsolutePath().normalize().toString());
			Files.delete(hooks);
			Files.createDirectory(hooks);
			IOException nonregular = null;
			try
			{
				CodexHookApprovals.unapproved(plugin, home, JSON);
			}
			catch (IOException e)
			{
				nonregular = e;
			}
			requireThat(nonregular, "nonregularHooks").isNotNull();
			requireThat(nonregular.getMessage(), "nonregularHooksMessage").contains(
				hooks.toAbsolutePath().normalize().toString());
		}
	}

	/**
	 * Verifies that the existing one-megabyte plugin manifest and hooks limits remain enforced.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void rejectsOversizedPluginFiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Path manifest = Files.createDirectories(plugin.resolve(".codex-plugin")).resolve("plugin.json");
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			Files.writeString(manifest, "{}" + " ".repeat(1_048_576), UTF_8);
			IOException oversizedManifest = null;
			try
			{
				CodexHookApprovals.unapproved(plugin, home, JSON);
			}
			catch (IOException e)
			{
				oversizedManifest = e;
			}
			requireThat(oversizedManifest, "oversizedManifest").isNotNull();
			requireThat(oversizedManifest.getMessage(), "manifestMessage").contains(
				manifest.toAbsolutePath().normalize().toString());

			Files.writeString(manifest, "{\"name\": \"docs\", \"hooks\": \"hooks.json\"}", UTF_8);
			Path hooks = plugin.resolve("hooks.json");
			Files.writeString(hooks, "{}" + " ".repeat(1_048_576), UTF_8);
			IOException oversizedHooks = null;
			try
			{
				CodexHookApprovals.unapproved(plugin, home, JSON);
			}
			catch (IOException e)
			{
				oversizedHooks = e;
			}
			requireThat(oversizedHooks, "oversizedHooks").isNotNull();
			requireThat(oversizedHooks.getMessage(), "hooksMessage").contains(
				hooks.toAbsolutePath().normalize().toString());
		}
	}

	/**
	 * Verifies that a plugin without hooks has no unapproved hook, even when the user's configuration is absent.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-W12
	@Test
	public void pluginWithoutHooksHasNone() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-"));
			Files.createDirectories(plugin.resolve(".codex-plugin"));
			Files.writeString(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\": \"docs\"}", UTF_8);
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));

			requireThat(CodexHookApprovals.unapproved(plugin, home, JSON), "unapproved").isEmpty();
		}
	}
}
