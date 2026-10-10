package io.github.cowwoc.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexRunner;
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
	 * Verifies the app-server runs CAT's hooks when CAT is installed, and loads no plugin when it is not.
	 */
	@Test
	public void startsAppServerForInstallation()
	{
		requireThat(CodexRunner.appServerCommand("codex", true), "withCat").isEqualTo(
			List.of("codex", "app-server", "--stdio", "--enable", "hooks"));
		requireThat(CodexRunner.appServerCommand("codex", false), "withoutCat").isEqualTo(
			List.of("codex", "app-server", "--stdio", "--disable", "plugins"));
	}
}
