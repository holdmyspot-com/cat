module io.github.cowwoc.cat.codex.cli
{
	requires io.github.cowwoc.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports io.github.cowwoc.cat.codex;
	exports io.github.cowwoc.cat.codex.optimizer;
	exports io.github.cowwoc.cat.codex.runner to io.github.cowwoc.cat.codex.cli.test;
	exports io.github.cowwoc.cat.codex.secrets to io.github.cowwoc.cat.codex.cli.test;
}
