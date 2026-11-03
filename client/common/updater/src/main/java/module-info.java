/**
 * Updates an installed CAT plugin to a signed release that the user's trust policy approves, without changing the
 * harness's configuration.
 */
module com.holdmyspot.cat.common.updater
{
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.core;
	requires tools.jackson.databind;

	exports com.holdmyspot.cat.common.updater.activation;
	exports com.holdmyspot.cat.common.updater.trust;

	uses com.holdmyspot.cat.common.updater.activation.PluginActivation;
}
