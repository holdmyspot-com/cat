module io.github.cowwoc.cat.common.cli
{
	requires io.github.cowwoc.requirements13.java;
	requires io.github.cowwoc.pouch10.core;
	requires org.apache.commons.statistics.interval;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports io.github.cowwoc.cat.common;
	exports io.github.cowwoc.cat.common.history;
	exports io.github.cowwoc.cat.common.optimizer.api;
	exports io.github.cowwoc.cat.common.optimizer.runner;
	exports io.github.cowwoc.cat.common.optimizer.state;
	exports io.github.cowwoc.cat.common.runner;

	opens io.github.cowwoc.cat.common.git.replay to tools.jackson.databind;
	opens io.github.cowwoc.cat.common.optimizer.candidate to tools.jackson.databind;
	opens io.github.cowwoc.cat.common.optimizer.contract to tools.jackson.databind;
	opens io.github.cowwoc.cat.common.optimizer.loading to tools.jackson.databind;
	opens io.github.cowwoc.cat.common.optimizer.state to tools.jackson.databind;
	opens io.github.cowwoc.cat.common.optimizer.trial to tools.jackson.databind;
}
