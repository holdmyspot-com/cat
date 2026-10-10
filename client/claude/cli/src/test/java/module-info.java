module io.github.cowwoc.cat.claude.cli.test
{
	requires io.github.cowwoc.cat.claude.cli;
	requires io.github.cowwoc.cat.common.cli;
	requires io.github.cowwoc.cat.common.cli.test;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires org.testng;

	exports io.github.cowwoc.cat.claude.optimizer.test to org.testng;
	exports io.github.cowwoc.cat.claude.runner.test to org.testng;
	exports io.github.cowwoc.cat.claude.test to org.testng;
}
