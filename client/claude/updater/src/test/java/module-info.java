module com.holdmyspot.cat.claude.updater.test
{
	requires com.holdmyspot.cat.claude.updater;
	requires com.holdmyspot.cat.common.updater;
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires org.testng;

	uses com.holdmyspot.cat.common.updater.activation.PluginActivation;
	uses com.holdmyspot.cat.common.updater.activation.HarnessIntegration;

	exports com.holdmyspot.cat.claude.updater.test to org.testng;
}
