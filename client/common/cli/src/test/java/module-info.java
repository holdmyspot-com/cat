module io.github.cowwoc.cat.common.cli.test
{
	requires io.github.cowwoc.cat.common.cli;
	requires io.github.cowwoc.pouch10.core;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
	requires org.testng;

	exports io.github.cowwoc.cat.common.fakes.test to org.testng, io.github.cowwoc.cat.claude.cli.test,
		io.github.cowwoc.cat.codex.cli.test;
	exports io.github.cowwoc.cat.common.git.replay.test to org.testng;
	exports io.github.cowwoc.cat.common.git.test to org.testng;
	exports io.github.cowwoc.cat.common.history.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.adapter.fakes.test to io.github.cowwoc.cat.claude.cli.test,
		io.github.cowwoc.cat.codex.cli.test;
	exports io.github.cowwoc.cat.common.optimizer.adapter.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.campaign.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.cli.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.contract.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.delivery.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.evidence.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.execution.prompt.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.execution.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.inventory.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.port.contract.test to org.testng, io.github.cowwoc.cat.claude.cli.test,
		io.github.cowwoc.cat.codex.cli.test;
	exports io.github.cowwoc.cat.common.optimizer.port.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.proposal.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.report.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.statistics.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.store.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.test to org.testng;
	exports io.github.cowwoc.cat.common.optimizer.workspace.test to org.testng;
	exports io.github.cowwoc.cat.common.runner.fakes.test to io.github.cowwoc.cat.claude.cli.test,
		io.github.cowwoc.cat.codex.cli.test;
	exports io.github.cowwoc.cat.common.runner.test to org.testng;
	exports io.github.cowwoc.cat.common.test to org.testng;

	opens io.github.cowwoc.cat.common.git.replay.test to tools.jackson.databind;
}
