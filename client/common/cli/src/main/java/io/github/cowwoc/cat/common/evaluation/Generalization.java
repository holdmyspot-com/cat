package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.array;
import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.read;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.requiredFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates the suite declarations that keep fixture-only details out of general reader guidance.
 */
public final class Generalization
{
	/**
	 * Prevents instantiation of this static utility.
	 */
	private Generalization()
	{
	}

	/**
	 * Rejects reader guidance that exposes a detail reserved to a concrete scenario fixture.
	 *
	 * @param suitePath the scenario suite that owns the fixture details
	 * @param promptPath the reader guidance being checked
	 * @throws IOException if either input is invalid or the guidance repeats an undeclared fixture detail
	 */
	public static void audit(Path suitePath, Path promptPath) throws IOException
	{
		suitePath = suitePath.toAbsolutePath().normalize();
		promptPath = promptPath.toAbsolutePath().normalize();
		SuiteContract.validate(suitePath);
		if (!Files.isRegularFile(promptPath))
			throw new IOException("Expected prompt artifact: " + promptPath);
		ObjectNode suite = read(suitePath);
		ObjectNode generalization = object(suite.path("generalization"), "generalization");
		ArrayNode distinctives = array(generalization.path("fixture_distinctives"), "fixture_distinctives", suitePath);
		if (distinctives.isEmpty())
			throw new IOException("Generalization audit requires one or more fixture_distinctives: " + suitePath);
		String promptText = Files.readString(promptPath).toLowerCase(Locale.ROOT);
		Set<String> caseIds = testCaseIds(suite, suitePath);
		for (JsonNode node : distinctives)
		{
			ObjectNode distinctive = object(node, "fixture distinctive");
			String caseId = text(distinctive, "case_id", suitePath);
			if (!caseIds.contains(caseId))
				throw new IOException("Fixture distinctive names no testcase: " + caseId);
			String phrase = text(distinctive, "text", suitePath);
			if (distinctive.has("external_authority"))
			{
				ObjectNode authority = object(distinctive.path("external_authority"), "external authority");
				Path authorityPath = requiredFile(suitePath, text(authority, "path", suitePath),
					"External authority", caseId);
				if (!Files.readString(authorityPath).toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT)))
				{
					throw new IOException("External authority does not establish fixture-specific phrase '" + phrase +
						"': " + authorityPath);
				}
				continue;
			}
			if (promptText.contains(phrase.toLowerCase(Locale.ROOT)))
			{
				throw new IOException("Prompt artifact repeats fixture-specific phrase '" + phrase + "' from testcase " +
					caseId + ". Use a dissimilar example or declare the external authority that requires the overlap: " +
					promptPath);
			}
		}
	}

	/**
	 * Returns the stable identifiers declared by one validated suite.
	 *
	 * @param suite the suite declaration
	 * @param suitePath the suite path
	 * @return its testcase identifiers
	 * @throws IOException if a testcase identifier is absent
	 */
	private static Set<String> testCaseIds(ObjectNode suite, Path suitePath) throws IOException
	{
		Set<String> result = new HashSet<>();
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
			result.add(text(object(node, "test case"), "id", suitePath));
		return result;
	}
}
