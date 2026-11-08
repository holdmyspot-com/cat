module com.holdmyspot.cat.common.installer.test
{
	requires com.holdmyspot.cat.common.cli;
	requires com.holdmyspot.cat.common.cli.test;
	requires com.holdmyspot.cat.common.installer;
	requires io.github.cowwoc.requirements13.java;
	requires org.testng;
	requires java.net.http;
	requires org.apache.commons.compress;
	requires tools.jackson.databind;

	exports com.holdmyspot.cat.common.installer.engine.test to org.testng;
	exports com.holdmyspot.cat.common.installer.contract.test to com.holdmyspot.cat.claude.installer.test,
		com.holdmyspot.cat.codex.installer.test;
	exports com.holdmyspot.cat.common.installer.fakes.test to com.holdmyspot.cat.claude.installer.test,
		com.holdmyspot.cat.codex.installer.test, com.holdmyspot.cat.distribution.test;
	exports com.holdmyspot.cat.common.installer.engine.internal.test to org.testng;
	exports com.holdmyspot.cat.common.installer.cli.internal.test to org.testng;
	exports com.holdmyspot.cat.common.installer.trust.test to org.testng;
}
