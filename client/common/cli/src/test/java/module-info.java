module com.holdmyspot.cat.common.cli.test
{
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.pouch10.core;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
	requires org.testng;

	exports com.holdmyspot.cat.common.fakes.test to org.testng, com.holdmyspot.cat.claude.cli.test,
		com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.git.replay.test to org.testng;
	exports com.holdmyspot.cat.common.git.test to org.testng;
	exports com.holdmyspot.cat.common.history.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.adapter.fakes.test to com.holdmyspot.cat.claude.cli.test,
		com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.optimizer.adapter.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.campaign.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.cli.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.contract.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.delivery.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.evidence.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.execution.prompt.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.execution.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.inventory.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.port.contract.test to org.testng, com.holdmyspot.cat.claude.cli.test,
		com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.optimizer.port.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.proposal.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.report.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.statistics.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.store.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.test to org.testng;
	exports com.holdmyspot.cat.common.optimizer.workspace.test to org.testng;
	exports com.holdmyspot.cat.common.runner.fakes.test to com.holdmyspot.cat.claude.cli.test,
		com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.common.runner.test to org.testng;
	exports com.holdmyspot.cat.common.test to org.testng;

	opens com.holdmyspot.cat.common.git.replay.test to tools.jackson.databind;
}
