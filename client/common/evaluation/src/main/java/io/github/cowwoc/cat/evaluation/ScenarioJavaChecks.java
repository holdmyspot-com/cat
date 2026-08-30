package io.github.cowwoc.cat.evaluation;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.TestListenerAdapter;
import org.testng.TestNG;
import org.testng.TestNGException;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

/**
 * Runs the TestNG checks conventionally assigned to one scenario.
 */
public final class ScenarioJavaChecks
{
	/**
 * Prevents construction of Java-check execution.
	 */
	private ScenarioJavaChecks()
	{
	}

	/**
	 * Runs one scenario's conventionally discovered TestNG class against evaluator-owned evidence paths.
	 *
	 * @param className the configured package plus the conventionally derived scenario class name
	 * @param scenarioId the stable scenario identifier used in diagnostics
	 * @param retainedProject the Runner project retained after execution
	 * @param executorResult the Runner output retained after execution
	 * @return one passed or failed outcome per discovered TestNG method
	 * @throws IOException if TestNG cannot produce one unambiguous outcome for each discovered method
	 */
	public static Map<String, Result> run(String className, String scenarioId, String retainedProject,
		String executorResult) throws IOException
	{
		TestListenerAdapter listener = new TestListenerAdapter();
		TestNG testng = new TestNG(false);
		testng.setUseDefaultListeners(false);
		try
		{
			XmlSuite suite = new XmlSuite();
			suite.setName("scenario-java-checks");
			XmlTest test = new XmlTest(suite);
			test.setName(scenarioId);
			test.setXmlClasses(List.of(new XmlClass(className)));
			testng.setXmlSuites(List.of(suite));
		}
		catch (TestNGException e)
		{
			throw new IOException("Scenario has no discovered Java checks: " + scenarioId, e);
		}
		testng.addListener(new EvidenceContext(retainedProject, executorResult));
		testng.addListener(listener);
		testng.run();

		Map<String, ITestResult> results = new HashMap<>();
		addResults(results, listener.getPassedTests(), scenarioId);
		addResults(results, listener.getFailedTests(), scenarioId);
		addResults(results, listener.getSkippedTests(), scenarioId);
		if (results.isEmpty())
			throw new IOException("Scenario has no discovered Java checks: " + scenarioId);

		Map<String, Result> outcome = new HashMap<>();
		for (Map.Entry<String, ITestResult> entry : results.entrySet())
		{
			Throwable failure = entry.getValue().getThrowable();
			outcome.put(entry.getKey(), new Result(entry.getValue().isSuccess(), failure == null ? "TestNG check passed" :
				failure.toString()));
		}
		return Map.copyOf(outcome);
	}

	/**
	 * Adds TestNG outcomes and rejects two checks that would produce the same assertion identifier.
	 *
	 * @param results the accumulated outcomes by assertion identifier
	 * @param additions outcomes reported for one TestNG status
	 * @param scenarioId the stable scenario identifier used in diagnostics
	 * @throws IOException if two Java checks have the same method name
	 */
	private static void addResults(Map<String, ITestResult> results, List<ITestResult> additions, String scenarioId)
		throws IOException
	{
		for (ITestResult result : additions)
			if (results.putIfAbsent(result.getName(), result) != null)
				throw new IOException("Scenario has ambiguous Java checks: " + scenarioId);
	}

	/**
	 * One Java-check outcome retained by the evaluator.
	 *
	 * @param passed whether the TestNG check completed successfully
	 * @param evidence the TestNG outcome evidence retained for the assertion
	 */
	public record Result(boolean passed, String evidence) {}

	private record EvidenceContext(String retainedProject, String executorResult) implements IInvokedMethodListener
	{
		@Override
		public void beforeInvocation(IInvokedMethod method, ITestResult result)
		{
			if (method.isTestMethod())
			{
				ITestContext context = result.getTestContext();
				context.setAttribute("retainedProject", retainedProject);
				context.setAttribute("executorResult", executorResult);
			}
		}
	}
}
