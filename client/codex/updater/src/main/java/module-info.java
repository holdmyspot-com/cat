/**
 * Activates authenticated CAT releases in Codex and integrates the updater with Codex's command-line program and
 * execution policy.
 */
module com.holdmyspot.cat.codex.updater
{
	requires com.holdmyspot.cat.common.updater;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;

	provides com.holdmyspot.cat.common.updater.activation.PluginActivation with
		com.holdmyspot.cat.codex.updater.CodexActivation;
	provides com.holdmyspot.cat.common.updater.activation.HarnessIntegration with
		com.holdmyspot.cat.codex.updater.CodexIntegration;
}
