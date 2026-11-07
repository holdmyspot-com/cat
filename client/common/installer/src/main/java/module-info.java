/**
 * Installs, reports and uninstalls the CAT plugin of one harness. Install adds CAT to a harness that has none or moves
 * it to another release, accepting only signed releases that the user's trust policy approves, and migrates CAT's data
 * to that release.
 */
module com.holdmyspot.cat.common.installer
{
	requires transitive com.holdmyspot.cat.common.cli;
	requires transitive java.net.http;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.core;
	requires tools.jackson.databind;

	exports com.holdmyspot.cat.common.installer.activation;
	exports com.holdmyspot.cat.common.installer.engine;
	exports com.holdmyspot.cat.common.installer.engine.internal to com.holdmyspot.cat.common.installer.test;
	exports com.holdmyspot.cat.common.installer.secrets to com.holdmyspot.cat.common.installer.test,
		com.holdmyspot.cat.distribution.test;
	exports com.holdmyspot.cat.common.installer.trust;
	exports com.holdmyspot.cat.common.installer.cli.internal to com.holdmyspot.cat.common.installer.test,
		com.holdmyspot.cat.distribution.test;

	uses com.holdmyspot.cat.common.installer.activation.PluginActivation;
	uses com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
}
