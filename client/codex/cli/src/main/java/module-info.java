module com.holdmyspot.cat.codex.cli
{
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports com.holdmyspot.cat.codex;
	exports com.holdmyspot.cat.codex.optimizer;
	exports com.holdmyspot.cat.codex.runner to com.holdmyspot.cat.codex.cli.test;
	exports com.holdmyspot.cat.codex.secrets to com.holdmyspot.cat.codex.cli.test;

	provides com.holdmyspot.cat.common.SessionHarness with com.holdmyspot.cat.codex.CodexSessionHarness;
}
