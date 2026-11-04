/**
 * Plans activation of authenticated CAT releases in Claude Code's directory marketplace, and registers CAT and the
 * installer's agent permissions with Claude Code.
 */
module com.holdmyspot.cat.claude.installer
{
	requires com.holdmyspot.cat.claude.layout;
	requires com.holdmyspot.cat.common.installer;
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.core;
	requires tools.jackson.databind;

	exports com.holdmyspot.cat.claude.installer to com.holdmyspot.cat.claude.installer.test;

	provides com.holdmyspot.cat.common.installer.activation.PluginActivation
		with com.holdmyspot.cat.claude.installer.Activation;
	provides com.holdmyspot.cat.common.installer.activation.HarnessIntegration
		with com.holdmyspot.cat.claude.installer.Integration;
}
