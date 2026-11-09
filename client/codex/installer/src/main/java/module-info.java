/**
 * Activates authenticated CAT releases in Codex and integrates the installer with Codex's command-line program and
 * execution policy.
 */
module com.holdmyspot.cat.codex.installer
{
	requires com.holdmyspot.cat.codex.layout;
	requires com.holdmyspot.cat.common.installer;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.toml;

	exports com.holdmyspot.cat.codex.installer to com.holdmyspot.cat.codex.installer.test,
		com.holdmyspot.cat.codex.cli, com.holdmyspot.cat.codex.cli.test;

	provides com.holdmyspot.cat.common.installer.activation.PluginActivation with
		com.holdmyspot.cat.codex.installer.CodexActivation;
	provides com.holdmyspot.cat.common.installer.activation.HarnessIntegration with
		com.holdmyspot.cat.codex.installer.CodexIntegration;
}
