module io.github.cowwoc.cat.distribution.test
{
	requires io.github.cowwoc.cat.distribution;
	requires io.github.cowwoc.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires org.apache.commons.compress;
	requires org.testng;

	exports io.github.cowwoc.cat.distribution.bundle.test to org.testng;
	exports io.github.cowwoc.cat.distribution.license.test to org.testng;
	exports io.github.cowwoc.cat.distribution.test to org.testng;
}
