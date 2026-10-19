module com.holdmyspot.cat.common.cli
{
	requires io.github.cowwoc.requirements13.java;
	requires io.github.cowwoc.pouch10.core;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports com.holdmyspot.cat.common;
	exports com.holdmyspot.cat.common.git to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.git.replay to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.history;
	exports com.holdmyspot.cat.common.optimizer.adapter;
	exports com.holdmyspot.cat.common.optimizer.campaign to com.holdmyspot.cat.claude.cli,
		com.holdmyspot.cat.codex.cli, com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.cli;
	exports com.holdmyspot.cat.common.optimizer.contract;
	exports com.holdmyspot.cat.common.optimizer.delivery to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.evidence to com.holdmyspot.cat.claude.cli,
		com.holdmyspot.cat.codex.cli, com.holdmyspot.cat.common.cli.test, com.holdmyspot.cat.claude.cli.test,
		com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.optimizer.execution to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.execution.prompt;
	exports com.holdmyspot.cat.common.optimizer.inventory to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.port;
	exports com.holdmyspot.cat.common.optimizer.proposal;
	exports com.holdmyspot.cat.common.optimizer.report;
	exports com.holdmyspot.cat.common.optimizer.statistics to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.store to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.workspace;
	exports com.holdmyspot.cat.common.runner;
	exports com.holdmyspot.cat.common.secrets to com.holdmyspot.cat.common.cli.test;

	opens com.holdmyspot.cat.common.git.replay to tools.jackson.databind;

	uses com.holdmyspot.cat.common.SessionHarness;
}
