package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns creation of one fresh executor workspace from a durable scenario suite.
 */
public final class TrialPreparation
{
	/**
	 * Prevents construction of this static trial-preparation owner.
	 */
	private TrialPreparation()
	{
	}

	/**
	 * Validates a scenario suite, selects one scenario, and creates its isolated executor workspace.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param suitePath identifies the durable scenario suite
	 * @param caseId identifies the scenario to execute
	 * @param workspace receives the copied fixture or an empty project
	 * @return the selected scenario, prompt, and workspace description
	 * @throws IOException if the suite, scenario, fixture, or workspace is invalid
	 */
	public static ObjectNode prepare(tools.jackson.databind.json.JsonMapper json, Path suitePath, String caseId,
		Path workspace) throws IOException
	{
		suitePath = suitePath.toAbsolutePath().normalize();
		workspace = workspace.toAbsolutePath().normalize();
		SuiteContract.validate(suitePath);
		if (Files.exists(workspace))
			throw new IOException("Trial workspace already exists: " + workspace);

		ObjectNode suite = SuiteContract.read(suitePath);
		ObjectNode testCase = SuiteContract.testCase(suite, caseId, suitePath);
		Path prompt = SuiteContract.requiredFile(suitePath, PromptEvaluation.text(testCase, "prompt", suitePath),
			"Test prompt", caseId);
		if (testCase.has("fixture"))
		{
			Path fixture = SuiteContract.requiredDirectory(suitePath,
				PromptEvaluation.text(testCase, "fixture", suitePath), "Test fixture", caseId);
			EvaluationFiles.copyFixture(fixture, workspace);
		}
		else
			Files.createDirectory(workspace);

		ObjectNode result = json.createObjectNode();
		result.put("case_id", caseId);
		result.put("prompt", prompt.toString());
		result.put("workspace", workspace.toString());
		return result;
	}
}
