module com.holdmyspot.cat.common.cli
{
	requires io.github.cowwoc.requirements13.java;
	requires com.github.oshi.common;
	requires io.github.cowwoc.pouch10.core;
	requires org.apache.commons.compress;
	requires tools.jackson.core;
	requires transitive tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
	requires java.xml;
	requires java.net.http;
	requires jdk.httpserver;

	exports com.holdmyspot.cat.common;
	exports com.holdmyspot.cat.common.build to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.delegation to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.git to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.history;
	exports com.holdmyspot.cat.common.installation;
	exports com.holdmyspot.cat.common.optimizer.adapter;
	exports com.holdmyspot.cat.common.optimizer.campaign to com.holdmyspot.cat.claude.cli,
		com.holdmyspot.cat.codex.cli, com.holdmyspot.cat.common.cli.test, com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.optimizer.cli;
	exports com.holdmyspot.cat.common.optimizer.contract;
	exports com.holdmyspot.cat.common.optimizer.delivery to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.evidence to com.holdmyspot.cat.claude.cli,
		com.holdmyspot.cat.codex.cli, com.holdmyspot.cat.common.cli.test, com.holdmyspot.cat.claude.cli.test,
		com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.optimizer.execution to com.holdmyspot.cat.common.cli.test,
		com.holdmyspot.cat.claude.cli.test, com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.optimizer.execution.prompt;
	exports com.holdmyspot.cat.common.optimizer.hooks to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.inventory to com.holdmyspot.cat.common.cli.test,
		com.holdmyspot.cat.claude.cli.test, com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.optimizer.port;
	exports com.holdmyspot.cat.common.optimizer.proposal;
	exports com.holdmyspot.cat.common.optimizer.report;
	exports com.holdmyspot.cat.common.optimizer.statistics to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.store to com.holdmyspot.cat.common.cli.test;
	exports com.holdmyspot.cat.common.optimizer.workspace;
	exports com.holdmyspot.cat.common.release to com.holdmyspot.cat.claude.cli, com.holdmyspot.cat.codex.cli,
		com.holdmyspot.cat.common.cli.test, com.holdmyspot.cat.common.installer,
		com.holdmyspot.cat.common.installer.test;
	exports com.holdmyspot.cat.common.runner;
	exports com.holdmyspot.cat.common.runner.launcher to com.holdmyspot.cat.claude.cli, com.holdmyspot.cat.codex.cli,
		com.holdmyspot.cat.common.cli.test, com.holdmyspot.cat.claude.cli.test, com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.runner.login to com.holdmyspot.cat.claude.cli, com.holdmyspot.cat.codex.cli,
		com.holdmyspot.cat.common.cli.test, com.holdmyspot.cat.claude.cli.test, com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.runner.policy to com.holdmyspot.cat.claude.cli, com.holdmyspot.cat.codex.cli,
		com.holdmyspot.cat.common.cli.test, com.holdmyspot.cat.claude.cli.test, com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.secrets to com.holdmyspot.cat.common.cli.test, com.holdmyspot.cat.claude.cli.test,
		com.holdmyspot.cat.codex.cli.test;

	uses com.holdmyspot.cat.common.SessionHarness;
}
