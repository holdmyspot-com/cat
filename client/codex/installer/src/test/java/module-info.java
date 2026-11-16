module com.holdmyspot.cat.codex.installer.test
{
	requires com.holdmyspot.cat.codex.installer;
	requires com.holdmyspot.cat.codex.layout;
	requires com.holdmyspot.cat.common.installer;
	requires com.holdmyspot.cat.common.installer.test;
	requires com.holdmyspot.cat.common.cli;
	requires com.holdmyspot.cat.common.cli.test;
	requires io.github.cowwoc.requirements13.java;
	requires org.testng;
	requires tools.jackson.databind;

	uses com.holdmyspot.cat.common.installer.activation.PluginActivation;
	uses com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
	provides com.holdmyspot.cat.common.installer.fakes.test.HarnessProgram.Factory with
		com.holdmyspot.cat.codex.installer.test.CodexProgramFactory;

	exports com.holdmyspot.cat.codex.installer.test to org.testng;
}
