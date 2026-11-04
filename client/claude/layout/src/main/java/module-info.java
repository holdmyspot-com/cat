/**
 * Declares where Claude Code keeps CAT's installation, for the claude plugin and for the other harness's plugin.
 */
module com.holdmyspot.cat.claude.layout
{
	requires transitive com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;

	exports com.holdmyspot.cat.claude.layout;
}
