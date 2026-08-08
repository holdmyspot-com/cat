package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexRunner}.
 */
public final class CodexRunnerTest
{
	/**
	 * Verifies an isolated home uses a credential file only when no usable environment authentication is available.
	 */
	@Test
	public void copiesCredentialFileWhenNeeded()
	{
		requireThat(CodexRunner.requiresCredentialFiles(Map.of()), "withoutAuthentication").isTrue();
		requireThat(CodexRunner.requiresCredentialFiles(Map.of("CODEX_API_KEY", "token")),
			"withAuthentication").isFalse();
	}

	/**
	 * Verifies CAT is removed before it is reinstalled from this run's marketplace.
	 */
	@Test
	public void reinstallsCatFromTheRunMarketplace()
	{
		requireThat(CodexRunner.pluginSetupCommands("codex", Path.of("marketplace")), "commands").isEqualTo(List.of(
			List.of("codex", "plugin", "marketplace", "add", "marketplace"),
			List.of("codex", "plugin", "remove", "cat@cat"),
			List.of("codex", "plugin", "add", "cat@cat")));
	}
}
