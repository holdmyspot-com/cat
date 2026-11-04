/**
 * Plans activation of authenticated CAT releases in Claude Code's directory marketplace, and registers CAT and the
 * updater's agent permissions with Claude Code.
 */
module com.holdmyspot.cat.claude.updater
{
	requires com.holdmyspot.cat.common.updater;
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.core;
	requires tools.jackson.databind;

	exports com.holdmyspot.cat.claude.updater to com.holdmyspot.cat.claude.updater.test;

	provides com.holdmyspot.cat.common.updater.activation.PluginActivation
		with com.holdmyspot.cat.claude.updater.Activation;
	provides com.holdmyspot.cat.common.updater.activation.HarnessIntegration
		with com.holdmyspot.cat.claude.updater.Integration;
}
