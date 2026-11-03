/**
 * Plans activation of authenticated CAT releases in Codex without changing its configuration.
 */
module com.holdmyspot.cat.codex.updater
{
	requires com.holdmyspot.cat.common.updater;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;

	provides com.holdmyspot.cat.common.updater.activation.PluginActivation with
		com.holdmyspot.cat.codex.updater.CodexActivation;
	provides com.holdmyspot.cat.common.updater.cli.SandboxExemption with
		com.holdmyspot.cat.codex.updater.CodexExemption;
}
