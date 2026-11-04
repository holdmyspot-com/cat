module com.holdmyspot.cat.codex.installer.test
{
	requires com.holdmyspot.cat.codex.installer;
	requires com.holdmyspot.cat.common.installer;
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires org.testng;
	requires tools.jackson.databind;

	uses com.holdmyspot.cat.common.installer.activation.PluginActivation;
	uses com.holdmyspot.cat.common.installer.activation.HarnessIntegration;

	exports com.holdmyspot.cat.codex.installer.test to org.testng;
}
