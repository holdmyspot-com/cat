module com.holdmyspot.cat.codex.cli.test
{
	requires com.holdmyspot.cat.codex.cli;
	requires com.holdmyspot.cat.common.cli;
	requires com.holdmyspot.cat.common.cli.test;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires org.testng;

	exports com.holdmyspot.cat.codex.optimizer.test to org.testng;
	exports com.holdmyspot.cat.codex.runner.test to org.testng;
	exports com.holdmyspot.cat.codex.test to org.testng;
}
