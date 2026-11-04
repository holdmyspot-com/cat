module com.holdmyspot.cat.codex.updater.test
{
	requires com.holdmyspot.cat.codex.updater;
	requires com.holdmyspot.cat.common.updater;
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires org.testng;
	requires tools.jackson.databind;

	uses com.holdmyspot.cat.common.updater.activation.PluginActivation;
	uses com.holdmyspot.cat.common.updater.activation.HarnessIntegration;

	exports com.holdmyspot.cat.codex.updater.test to org.testng;
}
