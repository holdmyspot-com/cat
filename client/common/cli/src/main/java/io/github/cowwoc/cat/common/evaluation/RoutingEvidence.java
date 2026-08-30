package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Defines values used while interpreting retained routing evidence.
 */
public final class RoutingEvidence
{
	/**
	 * Prevents construction of this static namespace.
	 */
	private RoutingEvidence()
	{
	}

	/**
	 * Describes candidate-specific canonical rule paths retained in an evaluation record.
	 *
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific rule paths
	 */
	public record CandidateRoute(Map<String, String> routeReplacements)
	{
	}

	/**
	 * Reads the optional candidate route identity from one retained evaluation record.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the record location for diagnostics
	 * @return the candidate route, or {@code null} when the packaged prompt set is under evaluation
	 * @throws IOException if the retained candidate identity is malformed
	 */
	public static CandidateRoute candidateRoute(ObjectNode run, Path runPath) throws IOException
	{
		if (!run.has("candidate_bundle"))
			return null;
		ObjectNode bundle = object(run.path("candidate_bundle"), "candidate bundle");
		ObjectNode replacements = object(bundle.path("route_replacements"), "candidate route replacements");
		Map<String, String> result = new TreeMap<>();
		for (String ordinary : replacements.propertyNames())
		{
			JsonNode value = replacements.path(ordinary);
			if (!value.isString())
				throw new IOException("Candidate route replacement is not text: " + ordinary + " in " + runPath);
			String candidate = value.asString();
			if (Path.of(ordinary).isAbsolute() || Path.of(candidate).isAbsolute() || ordinary.contains("..") ||
				candidate.contains(".."))
			{
				throw new IOException("Candidate route replacement is not a safe relative path: " + runPath);
			}
			result.put(ordinary, candidate);
		}
		return new CandidateRoute(Map.copyOf(result));
	}

	/**
	 * Determines whether retained native commands meet one routing-only expectation.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param testCase the generated routing testcase
	 * @param rawResult the executor's retained native event stream
	 * @param assertionId the deterministic assertion to record
	 * @return one JSON result for the declared assertion
	 * @throws IOException if the testcase or retained stream is invalid
	 */
	public static ObjectNode routeDeliveryCheck(JsonMapper json, ObjectNode testCase, Path rawResult, String assertionId)
		throws IOException
	{
		return routeDeliveryCheck(json, testCase, rawResult, assertionId, null);
	}

	/**
	 * Determines whether retained native commands meet one routing-only expectation for one candidate.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param testCase the generated routing testcase
	 * @param rawResult the executor's retained native event stream
	 * @param assertionId the deterministic assertion to record
	 * @param candidate the measured candidate, or {@code null} for a package-default route
	 * @return one JSON result for the declared assertion
	 * @throws IOException if the testcase or retained stream is invalid
	 */
	public static ObjectNode routeDeliveryCheck(JsonMapper json, ObjectNode testCase, Path rawResult, String assertionId,
		CandidateRoute candidate) throws IOException
	{
		String caseId = text(testCase, "id", rawResult);
		ObjectNode expectation = object(testCase.path("route_expectation"), "route expectation");
		Set<String> selected = selectedRoutes(expectation, caseId, candidate);
		Set<String> optionalSelected = candidateRoutes(expectation, "optional_selected", caseId, candidate);
		Set<String> notSelected = candidateRoutes(expectation, "not_selected", caseId, candidate);
		Set<String> read = new HashSet<>();
		Set<String> failedRequired = new HashSet<>();
		Set<String> attempted = new HashSet<>();
		Map<String, String> startedCommands = new HashMap<>();
		Set<String> runnerSelected = new HashSet<>();
		Set<String> runnerDelivered = new HashSet<>();
		boolean hasRunnerSelection = false;
		boolean hasRunnerDelivery = false;
		int selectionTurn = 0;
		int deliveryTurn = 0;
		int taskTurn = 0;
		boolean taskActionBeforeRoute = false;
		for (String line : Files.readAllLines(rawResult, StandardCharsets.UTF_8))
		{
			try
			{
				JsonNode event = json.readTree(line);
				if (event.path("type").asString().equals("route-selected"))
				{
					hasRunnerSelection = true;
					selectionTurn = event.path("selection_turn").asInt();
					for (JsonNode rule : event.path("rules"))
						if (rule.isString())
							runnerSelected.add(rule.stringValue());
				}
				else if (event.path("type").asString().equals("route-delivered"))
				{
					hasRunnerDelivery = true;
					deliveryTurn = event.path("delivery_turn").asInt();
					taskTurn = event.path("precedes_task_turn").asInt();
					for (JsonNode rule : event.path("rules"))
						if (rule.path("relative_path").isString())
							runnerDelivered.add(rule.path("relative_path").stringValue());
				}
			}
			catch (JacksonException _)
			{
				// Native diagnostic lines remain available to the command-based check below.
			}
			NativeCommand command = nativeCommand(json, line);
			if (command == null)
				continue;
			if (command.started())
			{
				startedCommands.put(command.id(), command.text());
				for (String rule : selected)
					if (command.text().contains("/rules/" + rule))
						attempted.add(rule);
				for (String rule : notSelected)
					if (command.text().contains("/rules/" + rule))
						attempted.add(rule);
				if (!read.containsAll(selected) && !command.text().contains("/rules/"))
					taskActionBeforeRoute = true;
				continue;
			}
			String started = startedCommands.getOrDefault(command.id(), command.text());
			for (String rule : selected)
				if (started.contains("/rules/" + rule))
				{
					if (command.succeeded())
						read.add(rule);
					else
						failedRequired.add(rule);
				}
			for (String rule : notSelected)
				if (started.contains("/rules/" + rule))
					attempted.add(rule);
		}
		if (hasRunnerSelection)
			return modelSelectedRouteCheck(json, assertionId, selected, optionalSelected, notSelected, runnerSelected,
				runnerDelivered, hasRunnerDelivery, selectionTurn, deliveryTurn, taskTurn);
		String missing = selected.stream().filter(rule -> !read.contains(rule)).sorted().findFirst().orElse(null);
		String failed = selected.stream().filter(failedRequired::contains).sorted().findFirst().orElse(null);
		String forbidden = notSelected.stream().filter(attempted::contains).sorted().findFirst().orElse(null);
		String evidence;
		String status;
		if (forbidden != null)
		{
			status = "failed";
			evidence = "A native command attempted excluded route /rules/" + forbidden + ".";
		}
		else if (failed != null)
		{
			status = "failed";
			evidence = "The required route command for /rules/" + failed + " did not complete successfully.";
		}
		else if (taskActionBeforeRoute)
		{
			status = "failed";
			evidence = "A native command began task work before every selected route was read.";
		}
		else if (missing != null)
		{
			status = "failed";
			evidence = "No retained native command reads /rules/" + missing + ".";
		}
		else
		{
			status = "passed";
			evidence = "Each required route command completed successfully before task work, and no excluded route was " +
				"attempted.";
		}
		ObjectNode result = json.createObjectNode();
		result.putObject("assertions").putObject(assertionId).put("status", status).put("evidence", evidence);
		return result;
	}

	/**
	 * Resolves the required selected routes for one execution using its candidate-specific canonical paths.
	 *
	 * @param expectation the testcase route expectation
	 * @param caseId the owning testcase identifier
	 * @param candidate the measured candidate, or {@code null} for a package-default execution
	 * @return the concrete canonical paths that the execution must select
	 * @throws IOException if retained candidate route evidence is invalid
	 */
	public static Set<String> selectedRoutes(ObjectNode expectation, String caseId, CandidateRoute candidate)
		throws IOException
	{
		return candidateRoutes(expectation, "selected", caseId, candidate);
	}

	/**
	 * Resolves one route-expectation field for the candidate under evaluation.
	 *
	 * @param expectation the testcase route expectation
	 * @param field the selected, optional-selected, or excluded field
	 * @param caseId the owning testcase identifier
	 * @param candidate the measured candidate, or {@code null} for a package-default execution
	 * @return the concrete canonical paths that the execution must select, may select, or exclude
	 * @throws IOException if retained candidate route evidence is invalid
	 */
	public static Set<String> candidateRoutes(ObjectNode expectation, String field, String caseId,
		CandidateRoute candidate) throws IOException
	{
		Set<String> selected = new HashSet<>(routePaths(expectation.path(field), caseId, field));
		if (candidate == null)
			return selected;
		for (Map.Entry<String, String> replacement : candidate.routeReplacements().entrySet())
		{
			if (selected.remove(replacement.getKey()))
				selected.add(replacement.getValue());
		}
		return selected;
	}

	/**
	 * Verifies the runner's model-selected route receipt.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param assertionId the owned deterministic assertion
	 * @param expectedSelected the route paths required to be selected
	 * @param optionalSelected the route paths permitted but not required to be selected
	 * @param expectedAbsent the route paths expected to remain absent
	 * @param actualSelected the model-selected canonical paths
	 * @param actualDelivered the installed paths delivered by the runner
	 * @param hasDelivery whether the runner emitted a delivery receipt
	 * @param selectionTurn the model selection turn
	 * @param deliveryTurn the installed-rule delivery turn
	 * @param taskTurn the later task turn that the receipt precedes
	 * @return one deterministic route-delivery result
	 */
	private static ObjectNode modelSelectedRouteCheck(JsonMapper json, String assertionId, Set<String> expectedSelected,
		Set<String> optionalSelected, Set<String> expectedAbsent, Set<String> actualSelected, Set<String> actualDelivered,
		boolean hasDelivery, int selectionTurn, int deliveryTurn, int taskTurn)
	{
		String status = "passed";
		String evidence = "The runner recorded the expected model-selected route and delivered its exact installed " +
			"contents before the later task turn.";
		if (!actualSelected.containsAll(expectedSelected))
		{
			status = "failed";
			evidence = "The runner did not select every required route " + expectedSelected + "; it selected " +
				actualSelected + ".";
		}
		else if (!java.util.Collections.disjoint(actualSelected, expectedAbsent))
		{
			status = "failed";
			evidence = "The runner recorded an excluded route in its model selection.";
		}
		else if (!permittedRoutes(expectedSelected, optionalSelected).containsAll(actualSelected))
		{
			status = "failed";
			evidence = "The runner selected a route that is neither required nor permitted for this testcase.";
		}
		else if (!expectedSelected.isEmpty() && (!hasDelivery || !actualDelivered.equals(actualSelected)))
		{
			status = "failed";
			evidence = "The runner did not deliver exactly the model-selected installed rules.";
		}
		else if (hasDelivery && (deliveryTurn <= selectionTurn || taskTurn <= deliveryTurn))
		{
			status = "failed";
			evidence = "The runner route-delivery receipt does not precede the later task turn.";
		}
		else if (expectedSelected.isEmpty() && hasDelivery)
		{
			status = "failed";
			evidence = "The runner delivered rules although the model selected none.";
		}
		ObjectNode result = json.createObjectNode();
		result.putObject("assertions").putObject(assertionId).put("status", status).put("evidence", evidence);
		return result;
	}

	/**
	 * Returns every route that an organic testcase may select.
	 *
	 * @param required routes whose trigger is unconditionally true
	 * @param optional routes permitted when their conditional trigger becomes true
	 * @return the union of required and optional routes
	 */
	private static Set<String> permittedRoutes(Set<String> required, Set<String> optional)
	{
		Set<String> result = new HashSet<>(required);
		result.addAll(optional);
		return result;
	}

	/**
	 * Returns route paths from an optional routing-expectation field.
	 *
	 * @param declared the selected or excluded route declaration
	 * @param caseId the owning testcase identifier
	 * @param field the declared route field
	 * @return declared paths, or an empty set when the field is absent
	 * @throws IOException if a supplied path is invalid
	 */
	private static Set<String> routePaths(JsonNode declared, String caseId, String field) throws IOException
	{
		if (declared.isMissingNode())
			return Set.of();
		SuiteContract.nonemptyRoutePaths(declared, caseId, field);
		Set<String> result = new HashSet<>();
		for (JsonNode entry : declared)
			result.add(entry.asString());
		return result;
	}

	/**
	 * Returns one native command lifecycle event from a retained event-stream line.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param line one retained event-stream line
	 * @return the command event, or {@code null} when the line is unrelated or malformed
	 */
	private static NativeCommand nativeCommand(JsonMapper json, String line)
	{
		try
		{
			JsonNode envelope = json.readTree(line);
			if (!envelope.path("type").asString().equals("native-event") || !envelope.path("event").isString())
				return null;
			JsonNode event = json.readTree(envelope.path("event").asString());
			JsonNode item = event.path("params").path("item");
			String method = event.path("method").asString();
			if ((!method.equals("item/started") && !method.equals("item/completed")) ||
				!item.path("type").asString().equals("commandExecution") || !item.path("id").isString() ||
				!item.path("command").isString())
			{
				return null;
			}
			boolean succeeded = method.equals("item/completed") && item.path("status").asString().equals("completed") &&
				item.path("exitCode").asInt(-1) == 0;
			return new NativeCommand(item.path("id").asString(), item.path("command").asString(),
				method.equals("item/started"), succeeded);
		}
		catch (JacksonException _)
		{
			return null;
		}
	}

	private record NativeCommand(String id, String text, boolean started, boolean succeeded)
	{
	}
}
