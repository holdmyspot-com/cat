module com.holdmyspot.cat.common.updater.test
{
	requires com.holdmyspot.cat.common.cli;
	requires com.holdmyspot.cat.common.updater;
	requires io.github.cowwoc.requirements13.java;
	requires org.testng;
	requires java.net.http;
	requires org.apache.commons.compress;
	requires tools.jackson.databind;

	exports com.holdmyspot.cat.common.updater.activation.test to org.testng;
	exports com.holdmyspot.cat.common.updater.engine.test to org.testng;
	exports com.holdmyspot.cat.common.updater.cli.internal.test to org.testng;
	exports com.holdmyspot.cat.common.updater.trust.test to org.testng;
}
