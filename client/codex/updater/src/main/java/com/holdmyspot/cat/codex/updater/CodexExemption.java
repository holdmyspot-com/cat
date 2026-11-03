package com.holdmyspot.cat.codex.updater;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.cli.SandboxExemption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports the execution-policy rule that the user adds for routine Codex updates.
 * <p>
 * The rule permits the fixed launcher followed by {@code update --harness codex}. Codex permits trailing arguments
 * after this prefix, so the updater must validate its complete argument list before performing an update.
 * <p>
 * This provider returns text for the user to install; it does not create a permission file or alter configuration.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexExemption implements SandboxExemption
{
	/**
	 * The user-managed file that holds the updater's execution-policy rule.
	 */
	private static final Path RULE_FILE = Path.of("rules/cat-updater.rules");
	/**
	 * Quotes launcher paths as string literals in the execution-policy rule.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Creates the permission provider used by service discovery.
	 */
	public CodexExemption()
	{
	}

	@Override
	public String harness()
	{
		return CodexActivation.HARNESS;
	}

	/**
	 * Formats one execution-policy rule for the recorded home and stable launcher, normalizing both absolute paths.
	 *
	 * @throws NullPointerException     if either argument is null
	 * @throws IllegalArgumentException if either argument is relative
	 */
	@Override
	public Map<String, List<String>> entries(Path stableLauncher, Path harnessHome)
	{
		requireThat(stableLauncher, "stableLauncher").isAbsolute();
		requireThat(harnessHome, "harnessHome").isAbsolute();

		String rule = """
			prefix_rule(pattern=[%s, "update", "--harness", %s], decision="allow")
			""".formatted(MAPPER.writeValueAsString(stableLauncher.normalize().toString()),
			MAPPER.writeValueAsString(harness())).stripTrailing();
		return Map.of(harnessHome.normalize().resolve(RULE_FILE).toString(), List.of(rule));
	}
}
