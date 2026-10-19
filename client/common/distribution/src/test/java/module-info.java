module com.holdmyspot.cat.distribution.test
{
	requires com.holdmyspot.cat.distribution;
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires org.apache.commons.compress;
	requires org.testng;
	requires tools.jackson.databind;

	exports com.holdmyspot.cat.distribution.bundle.test to org.testng;
	exports com.holdmyspot.cat.distribution.license.test to org.testng;
	exports com.holdmyspot.cat.distribution.test to org.testng;
}
