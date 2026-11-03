/**
 * Plans activation of authenticated CAT releases in Claude Code's directory marketplace.
 */
module com.holdmyspot.cat.claude.updater
{
	requires com.holdmyspot.cat.common.updater;
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	provides com.holdmyspot.cat.common.updater.activation.PluginActivation
		with com.holdmyspot.cat.claude.updater.Activation;
	provides com.holdmyspot.cat.common.updater.cli.SandboxExemption
		with com.holdmyspot.cat.claude.updater.Exemption;
}
