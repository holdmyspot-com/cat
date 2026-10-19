module com.holdmyspot.cat.claude.cli.test
{
	requires com.holdmyspot.cat.claude.cli;
	requires com.holdmyspot.cat.common.cli;
	requires com.holdmyspot.cat.common.cli.test;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires org.testng;

	exports com.holdmyspot.cat.claude.optimizer.test to org.testng;
	exports com.holdmyspot.cat.claude.runner.test to org.testng;
	exports com.holdmyspot.cat.claude.test to org.testng;
}
