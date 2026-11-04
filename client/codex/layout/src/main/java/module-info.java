/**
 * Declares where Codex keeps CAT's installation, for the codex plugin and for the other harness's plugin.
 */
module com.holdmyspot.cat.codex.layout
{
	requires transitive com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;

	exports com.holdmyspot.cat.codex.layout;
}
