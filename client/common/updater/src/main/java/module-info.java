/**
 * Updates an installed CAT plugin to a signed release that the user's trust policy approves, without changing the
 * harness's configuration.
 */
module com.holdmyspot.cat.common.updater
{
	requires transitive com.holdmyspot.cat.common.cli;
	requires transitive java.net.http;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.core;
	requires tools.jackson.databind;

	exports com.holdmyspot.cat.common.updater.activation;
	exports com.holdmyspot.cat.common.updater.engine;
	exports com.holdmyspot.cat.common.updater.secrets to com.holdmyspot.cat.common.updater.test;
	exports com.holdmyspot.cat.common.updater.trust;
	exports com.holdmyspot.cat.common.updater.cli to com.holdmyspot.cat.claude.updater,
		com.holdmyspot.cat.codex.updater, com.holdmyspot.cat.common.updater.test,
		com.holdmyspot.cat.claude.updater.test, com.holdmyspot.cat.codex.updater.test;

	exports com.holdmyspot.cat.common.updater.cli.internal to com.holdmyspot.cat.common.updater.test;

	uses com.holdmyspot.cat.common.updater.activation.PluginActivation;
	uses com.holdmyspot.cat.common.updater.cli.SandboxExemption;
}
