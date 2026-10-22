package com.holdmyspot.cat.common.optimizer.report.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.report.CertifiedEnvironment;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Tests {@link CertifiedEnvironment}.
 */
public final class CertifiedEnvironmentTest
{
	/**
	 * Creates the test.
	 */
	public CertifiedEnvironmentTest()
	{
	}

	/**
	 * A Codex certification with a project-built and an installed trial plugin, one cell and a settings file of the
	 * project reads as the block that names each of them.
	 */
	@Test
	public void namesTheProjectSettingsFiles()
	{
		// Requirement: R-M7
		List<TrialPlugin> plugins = List.of(
			new TrialPlugin("cat", "a".repeat(64), new TrialPlugin.Build(List.of("./build"), Path.of("out")),
				List.of(), null),
			new TrialPlugin("formatter@acme-tools", "b".repeat(64), new TrialPlugin.Installed(), List.of(),
				null));
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(MeasurementIdentity.ofPlugins(plugins, Map.of("cat", "1.0", "formatter@acme-tools", "2.3.1")).
			values());
		values.put(MeasurementCondition.PROVIDER, "codex");
		values.put(MeasurementCondition.HARNESS_VERSION, "codex-cli 0.160.0+41");
		values.put(MeasurementCondition.LOADER_FILES, ".codex/config.toml");
		values.put(MeasurementCondition.SESSION_ENVIRONMENT_RELEASE, "1.0");
		values.put(MeasurementCondition.HOST_TOOLS, "git 2.51.0");
		values.put(MeasurementCondition.EXTERNAL_SOURCES, "none");
		MeasurementIdentity identity = new MeasurementIdentity(values, Map.of("read-file/luna-low", Map.of(
			MeasurementCondition.MODEL, "gpt-6-luna", MeasurementCondition.REASONING, "low")));

		CertifiedEnvironment environment = new CertifiedEnvironment(identity, List.of("read-file/luna-low"),
			List.of(Path.of("AGENTS.md"), Path.of(".cat/rules/common/support.md")), List.of());

		requireThat(String.join("\n", environment.lines()), "lines").isEqualTo("""
			Certified for the following environment:

			Codex 0.160.0+41
			Plugins: cat 1.0 (built from the project), formatter@acme-tools 2.3.1
			Model: gpt-6-luna, reasoning low
			Prompt files loaded: AGENTS.md, .cat/rules/common/support.md
			Harness settings: the project's .codex/config.toml, with your connection settings and tool restrictions \
			carried over
			Trial setup: as introduced in CAT 1.0
			Tools run directly: git 2.51.0
			Outside sources: none""");
	}

	/**
	 * A Claude Code certification without trial plugins or project settings files gives a model line for each cell,
	 * reads {@code defaults} for the settings, says which conditions the record lacks, and marks each line whose
	 * condition the reporting session could not check.
	 */
	@Test
	public void readsDefaultsWithoutSettingsFiles()
	{
		// Requirement: R-M7
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(MeasurementIdentity.ofPlugins(List.of(), Map.of()).values());
		values.put(MeasurementCondition.PROVIDER, "claude");
		values.put(MeasurementCondition.HARNESS_VERSION, "2.1.286 (Claude Code)");
		values.put(MeasurementCondition.LOADER_FILES, "none");
		values.put(MeasurementCondition.SESSION_ENVIRONMENT_RELEASE, "1.0");
		values.put(MeasurementCondition.EXTERNAL_SOURCES, "none");
		MeasurementIdentity identity = new MeasurementIdentity(values, Map.of(
			"read-file/haiku-low", Map.of(MeasurementCondition.MODEL, "claude-haiku-4-5",
				MeasurementCondition.REASONING, "low"),
			"read-file/sonnet-high", Map.of(MeasurementCondition.MODEL, "claude-sonnet-5",
				MeasurementCondition.REASONING, "high")));

		CertifiedEnvironment environment = new CertifiedEnvironment(identity,
			List.of("read-file/haiku-low", "read-file/sonnet-high"), List.of(Path.of("CLAUDE.md")),
			List.of(MeasurementCondition.HARNESS_VERSION, MeasurementCondition.MODEL));

		requireThat(environment.lines(), "lines").isEqualTo(List.of(
			"Certified for the following environment:",
			"",
			"Claude Code 2.1.286 (not checked)",
			"Plugins: none",
			"Model: claude-haiku-4-5, reasoning low (not checked)",
			"Model: claude-sonnet-5, reasoning high (not checked)",
			"Prompt files loaded: CLAUDE.md",
			"Harness settings: defaults, with your connection settings and tool restrictions carried over",
			"Trial setup: as introduced in CAT 1.0",
			"Tools run directly: not recorded",
			"Outside sources: none"));
	}
}
