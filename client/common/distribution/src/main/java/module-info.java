module io.github.cowwoc.cat.distribution
{
	requires io.github.cowwoc.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;
	requires org.apache.commons.compress;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports io.github.cowwoc.cat.distribution;
	exports io.github.cowwoc.cat.distribution.bundle to io.github.cowwoc.cat.distribution.test;
	exports io.github.cowwoc.cat.distribution.bundle.secrets to io.github.cowwoc.cat.distribution.test;
	exports io.github.cowwoc.cat.distribution.license to io.github.cowwoc.cat.distribution.test;
	exports io.github.cowwoc.cat.distribution.secrets to io.github.cowwoc.cat.distribution.test;
}
