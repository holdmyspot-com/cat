module io.github.cowwoc.cat.common.evaluation
{
	requires io.github.cowwoc.requirements13.java;
	requires org.testng;

	exports io.github.cowwoc.cat.evaluation;
	opens io.github.cowwoc.cat.evaluation.scenarios to org.testng;
}
