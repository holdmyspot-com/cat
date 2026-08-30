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
	 * Verifies CAT is installed from this run's marketplace into the fresh isolated home.
	 */
	@Test
	public void reinstallsCatFromTheRunMarketplace()
	{
		requireThat(CodexRunner.pluginSetupCommands("codex", Path.of("marketplace")), "commands").isEqualTo(List.of(
			List.of("codex", "plugin", "marketplace", "add", "marketplace"),
			List.of("codex", "plugin", "add", "cat@cat")));
	}

	/**
	 * Verifies isolated sessions configure only the native features required by their CAT role.
	 */
	@Test
	public void configuresNativeFeatures()
	{
		requireThat(CodexRunner.appServerCommand("codex", true), "catCommand").isEqualTo(List.of("codex",
			"app-server", "--stdio", "--enable", "hooks"));
		requireThat(CodexRunner.appServerCommand("codex", false), "validatorCommand").isEqualTo(List.of("codex",
			"app-server", "--stdio", "--disable", "plugins"));
	}

	/**
	 * Verifies an unowned Codex route resolves the fixed CAT evaluation profile instead of a mutable host default.
	 *
	 * @throws java.io.IOException if profile resolution fails
	 */
	@Test
	public void resolvesFixedDefaultProfile() throws java.io.IOException
	{
		requireThat(CodexRunnerCli.resolveProfile(new String[] {"--resolve-profile"}), "profile").isEqualTo("""
			harness=codex
			owner=default
			model=gpt-5.6-luna
			effort=medium
			""");
	}
}
