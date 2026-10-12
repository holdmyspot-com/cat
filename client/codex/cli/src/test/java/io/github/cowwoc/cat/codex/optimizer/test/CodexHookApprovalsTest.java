package io.github.cowwoc.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.optimizer.CodexHookApprovals;
import io.github.cowwoc.cat.common.TemporaryDirectories;

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
