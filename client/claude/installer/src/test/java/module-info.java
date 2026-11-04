module com.holdmyspot.cat.claude.installer.test
{
	requires com.holdmyspot.cat.claude.installer;
	requires com.holdmyspot.cat.common.installer;
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires org.testng;

	uses com.holdmyspot.cat.common.installer.activation.PluginActivation;
	uses com.holdmyspot.cat.common.installer.activation.HarnessIntegration;

	exports com.holdmyspot.cat.claude.installer.test to org.testng;
}
