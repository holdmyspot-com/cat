module io.github.cowwoc.cat.codex.cli.test
{
	requires io.github.cowwoc.cat.codex.cli;
	requires io.github.cowwoc.cat.common.cli;
	requires io.github.cowwoc.cat.common.cli.test;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires org.testng;

	exports io.github.cowwoc.cat.codex.optimizer.test to org.testng;
	exports io.github.cowwoc.cat.codex.runner.test to org.testng;
	exports io.github.cowwoc.cat.codex.test to org.testng;
}
