module io.github.cowwoc.cat.common.cli
{
	requires io.github.cowwoc.requirements13.java;
	requires io.github.cowwoc.pouch10.core;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports io.github.cowwoc.cat.common;
	exports io.github.cowwoc.cat.common.history;
	exports io.github.cowwoc.cat.common.optimizer.adapter;
	exports io.github.cowwoc.cat.common.optimizer.campaign to io.github.cowwoc.cat.claude.cli,
		io.github.cowwoc.cat.codex.cli;
	exports io.github.cowwoc.cat.common.optimizer.cli;
	exports io.github.cowwoc.cat.common.optimizer.contract;
	exports io.github.cowwoc.cat.common.optimizer.evidence to io.github.cowwoc.cat.claude.cli,
		io.github.cowwoc.cat.codex.cli;
	exports io.github.cowwoc.cat.common.optimizer.execution.prompt;
	exports io.github.cowwoc.cat.common.optimizer.port;
	exports io.github.cowwoc.cat.common.optimizer.proposal;
	exports io.github.cowwoc.cat.common.optimizer.report;
	exports io.github.cowwoc.cat.common.optimizer.workspace;
	exports io.github.cowwoc.cat.common.runner;

	opens io.github.cowwoc.cat.common.git.replay to tools.jackson.databind;
}
