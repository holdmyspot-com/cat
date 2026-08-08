module io.github.cowwoc.cat.common.cli
{
	requires io.github.cowwoc.requirements13.java;
	requires io.github.cowwoc.pouch10.core;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;

	exports io.github.cowwoc.cat.common;
	exports io.github.cowwoc.cat.common.history;

	opens io.github.cowwoc.cat.common.git.replay to tools.jackson.databind;
}
