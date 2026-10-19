module com.holdmyspot.cat.distribution
{
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires org.apache.commons.compress;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports com.holdmyspot.cat.distribution;
	exports com.holdmyspot.cat.distribution.bundle to com.holdmyspot.cat.distribution.test;
	exports com.holdmyspot.cat.distribution.bundle.secrets to com.holdmyspot.cat.distribution.test;
	exports com.holdmyspot.cat.distribution.license to com.holdmyspot.cat.distribution.test;
	exports com.holdmyspot.cat.distribution.secrets to com.holdmyspot.cat.distribution.test;
}
