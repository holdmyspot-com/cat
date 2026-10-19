module com.holdmyspot.cat.claude.cli
{
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports com.holdmyspot.cat.claude to com.holdmyspot.cat.claude.cli.test;
	exports com.holdmyspot.cat.claude.optimizer;
	exports com.holdmyspot.cat.claude.runner to com.holdmyspot.cat.claude.cli.test;
	exports com.holdmyspot.cat.claude.secrets to com.holdmyspot.cat.claude.cli.test;

	provides com.holdmyspot.cat.common.SessionHarness with com.holdmyspot.cat.claude.ClaudeSessionHarness;
}
