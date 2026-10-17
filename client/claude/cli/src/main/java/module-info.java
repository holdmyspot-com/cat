module io.github.cowwoc.cat.claude.cli
{
	requires io.github.cowwoc.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports io.github.cowwoc.cat.claude to io.github.cowwoc.cat.claude.cli.test;
	exports io.github.cowwoc.cat.claude.optimizer;
	exports io.github.cowwoc.cat.claude.runner to io.github.cowwoc.cat.claude.cli.test;
	exports io.github.cowwoc.cat.claude.secrets to io.github.cowwoc.cat.claude.cli.test;

	provides io.github.cowwoc.cat.common.SessionHarness with io.github.cowwoc.cat.claude.ClaudeSessionHarness;
}
