package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.array;
import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.read;
import static io.github.cowwoc.cat.common.PromptEvaluation.sha256;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.diagnosticKind;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.routingOwner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Audits router ownership and publishes receipts for completed routing-matrix promotions.
 */
public final class RouterPromotion
{
	/**
	 * Supplies coordinator-owned durable routing-matrix operations.
	 */
	public interface Access
	{
		/**
		 * Indicates whether a retained run contains a completed routing matrix.
		 *
		 * @param run the retained routing-matrix run
		 * @return {@code true} if the run completed its routing matrix
		 */
		boolean hasCompletedMatrix(ObjectNode run);

		/**
		 * Atomically publishes one router-promotion receipt.
		 *
		 * @param destination the initially absent durable receipt
		 * @param receipt the completed receipt to publish
		 * @throws IOException if the receipt cannot be published
		 */
		void writeAtomically(Path destination, ObjectNode receipt) throws IOException;
	}

	private static volatile Access access;
	private static final Pattern MARKDOWN_RULE_PATH = Pattern.compile("(?:\\.\\./|\\./)?[A-Za-z0-9_./-]+\\.md");

	/**
	 * Prevents instantiation.
	 */
	private RouterPromotion()
	{
	}

	/**
	 * Verifies that an organic suite's expected routes are owned by its declared router.
	 *
	 * @param rulesRoot the root that owns relative rule paths
	 * @param routerRule the rule that routes readers to lazy rules
	 * @param certificationSuite the organic certification suite
	 * @throws IOException if a route is not owned, a path escapes the rules root, or the suite is not organic
	 */
	public static void audit(Path rulesRoot, Path routerRule, Path certificationSuite) throws IOException
	{
		rulesRoot = rulesRoot.toAbsolutePath().normalize();
		routerRule = routerRule.toAbsolutePath().normalize();
		certificationSuite = certificationSuite.toAbsolutePath().normalize();
		if (!Files.isDirectory(rulesRoot))
			throw new IOException("Rules root is not a directory: " + rulesRoot);
		if (!routerRule.startsWith(rulesRoot) || !Files.isRegularFile(routerRule))
			throw new IOException("Router rule is absent or escapes rules root: " + routerRule);
		SuiteContract.validate(certificationSuite);
		ObjectNode suite = SuiteContract.read(certificationSuite);
		if (SuitePurpose.parse(text(suite, "purpose", certificationSuite)) != SuitePurpose.CERTIFICATION)
		{
			throw new IOException("Routing audit requires a certification suite: " + certificationSuite);
		}
		String declaredOwner = routingOwner(suite, certificationSuite);
		String actualOwner = rulesRoot.relativize(routerRule).toString().replace(routerRule.getFileSystem().getSeparator(),
			"/");
		if (!declaredOwner.equals(actualOwner))
			throw new IOException("Certification suite names a different routing_owner: " + declaredOwner);
		verifyRouteOwnership(suite, rulesRoot, routerRule, Map.of(), certificationSuite);
	}

	/**
	 * Requires a router to own every route that one evaluation can require from it.
	 *
	 * @param suite the certification suite that describes the task population
	 * @param rulesRoot the installed rules root under evaluation
	 * @param routerRule the router selected by the suite
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific canonical rule paths
	 * @param suitePath the suite location used in diagnostics
	 * @throws IOException if a required route is absent from the router's owned targets
	 */
	public static void verifyRouteOwnership(ObjectNode suite, Path rulesRoot, Path routerRule,
		Map<String, String> routeReplacements, Path suitePath) throws IOException
	{
		Set<Path> ownedTargets = referencedRulePaths(rulesRoot, routerRule);
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = object(node, "test case");
			ObjectNode expectation = object(testCase.path("route_expectation"), "route expectation");
			if (!text(expectation, "mode", suitePath).equals("organic"))
				continue;
			String caseId = text(testCase, "id", suitePath);
			verifyOwnedRoutes(rulesRoot, routerRule, ownedTargets, routes(expectation, "selected", caseId,
				routeReplacements));
			verifyOwnedRoutes(rulesRoot, routerRule, ownedTargets, routes(expectation, "optional_selected", caseId,
				routeReplacements));
			verifyOwnedRoutes(rulesRoot, routerRule, ownedTargets, routes(expectation, "not_selected", caseId,
				routeReplacements));
		}
	}

	/**
	 * Publishes evidence that the current router passed the complete matrix recreated from one failed organic run.
	 *
	 * @param json the mapper that creates the promotion receipt
	 * @param sourceRunPath the failed organic certification record that justified the matrix
	 * @param matrixRunPath the completed routing-matrix record
	 * @param rulesRoot the root that owns the current router
	 * @param promotionPath the initially absent durable router-promotion receipt
	 * @return the published receipt
	 * @throws IOException if the matrix is incomplete, failed, mismatched, or the current router does not own its routes
	 */
	public static ObjectNode promote(JsonMapper json, Path sourceRunPath, Path matrixRunPath, Path rulesRoot,
		Path promotionPath)
		throws IOException
	{
		Path normalizedSourceRunPath = sourceRunPath.toAbsolutePath().normalize();
		Path normalizedMatrixRunPath = matrixRunPath.toAbsolutePath().normalize();
		Path normalizedRulesRoot = rulesRoot.toAbsolutePath().normalize();
		Path normalizedPromotionPath = promotionPath.toAbsolutePath().normalize();
		if (Files.exists(normalizedPromotionPath))
			throw new IOException("Router-promotion receipt already exists: " + normalizedPromotionPath);
		ObjectNode sourceRun = object(read(normalizedSourceRunPath), "source organic run");
		if (SuitePurpose.parse(text(sourceRun, "purpose", normalizedSourceRunPath)) != SuitePurpose.CERTIFICATION)
		{
			throw new IOException("Router promotion requires an organic certification source run: " +
				normalizedSourceRunPath);
		}
		Path certificationSuite = Path.of(text(sourceRun, "suite", normalizedSourceRunPath));
		SuiteContract.validate(certificationSuite);
		ObjectNode certification = SuiteContract.read(certificationSuite);
		ObjectNode matrixRun = object(read(normalizedMatrixRunPath), "routing-matrix run");
		if (!access().hasCompletedMatrix(matrixRun))
			throw new IOException("Router promotion requires a completed routing matrix: " + normalizedMatrixRunPath);
		ObjectNode matrix = object(matrixRun.path("routing_matrix"), "routing matrix");
		if (!text(matrix, "status", normalizedMatrixRunPath).equals("passed"))
		{
			throw new IOException("Router promotion requires every routing-matrix case to pass; the matrix is " +
				text(matrix, "status", normalizedMatrixRunPath) + ": " + normalizedMatrixRunPath);
		}
		Path matrixSuitePath = Path.of(text(matrixRun, "suite", normalizedMatrixRunPath));
		ObjectNode matrixSuite = SuiteContract.read(matrixSuitePath);
		if (SuitePurpose.parse(text(matrixRun, "purpose", normalizedMatrixRunPath)) != SuitePurpose.DIAGNOSTIC ||
			diagnosticKind(matrixSuite, matrixSuitePath, SuitePurpose.DIAGNOSTIC) != DiagnosticKind.ROUTING_ONLY)
		{
			throw new IOException("Router promotion requires a generated routing-only matrix: " + normalizedMatrixRunPath);
		}
		if (!text(matrixSuite, "source_run", matrixSuitePath).equals(normalizedSourceRunPath.toString()))
			throw new IOException("Routing matrix belongs to a different failed organic run: " + normalizedMatrixRunPath);
		verifyRoutingMatrixCases(certification, certificationSuite, matrixSuite, matrixSuitePath);
		String owner = routingOwner(certification, certificationSuite);
		Path routerRule = normalizedRulesRoot.resolve(owner).normalize();
		audit(normalizedRulesRoot, routerRule, certificationSuite);
		ObjectNode receipt = json.createObjectNode();
		receipt.put("schema_version", Records.schemaVersion());
		receipt.put("source_run", normalizedSourceRunPath.toString());
		receipt.put("routing_matrix_run", normalizedMatrixRunPath.toString());
		receipt.put("router_rule", owner);
		receipt.put("router_sha256", sha256(routerRule));
		receipt.put("suite_sha256", text(sourceRun, "suite_sha256", normalizedSourceRunPath));
		receipt.put("promoted_at_epoch_millis", System.currentTimeMillis());
		access().writeAtomically(normalizedPromotionPath, receipt);
		return receipt;
	}

	/**
	 * Resolves one expected route set after applying a candidate's declared replacements.
	 *
	 * @param expectation the case route expectation
	 * @param field the route-expectation field to resolve
	 * @param caseId the owning testcase identifier
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific paths
	 * @return the resolved route paths
	 * @throws IOException if the route field is invalid
	 */
	private static Set<String> routes(ObjectNode expectation, String field, String caseId,
		Map<String, String> routeReplacements) throws IOException
	{
		JsonNode declared = expectation.path(field);
		if (declared.isMissingNode())
			return Set.of();
		SuiteContract.nonemptyRoutePaths(declared, caseId, field);
		Set<String> result = new HashSet<>();
		for (JsonNode entry : declared)
			result.add(routeReplacements.getOrDefault(entry.asString(), entry.asString()));
		return result;
	}

	/**
	 * Requires every expected path to be an owned target below the rules root.
	 *
	 * @param rulesRoot the root that owns the paths
	 * @param routerRule the router that must name every path
	 * @param ownedTargets the paths named by the router
	 * @param expectedRoutes the suite's expected paths
	 * @throws IOException if an expected path is unsafe or unowned
	 */
	private static void verifyOwnedRoutes(Path rulesRoot, Path routerRule, Set<Path> ownedTargets,
		Set<String> expectedRoutes) throws IOException
	{
		for (String route : expectedRoutes)
		{
			Path target = rulesRoot.resolve(route).normalize();
			if (!target.startsWith(rulesRoot) || !ownedTargets.contains(target))
				throw new IOException("Organic route expectation is not owned by router " + routerRule + ": " + route);
		}
	}

	/**
	 * Requires a routing diagnostic to preserve every organic certification expectation.
	 *
	 * @param certification the immutable certification suite
	 * @param certificationSuitePath the certification-suite path
	 * @param matrix the generated routing diagnostic suite
	 * @param matrixSuitePath the routing-diagnostic-suite path
	 * @throws IOException if the matrix omits, adds, or changes an organic expectation
	 */
	private static void verifyRoutingMatrixCases(ObjectNode certification, Path certificationSuitePath, ObjectNode matrix,
		Path matrixSuitePath) throws IOException
	{
		Map<String, ObjectNode> expected = organicRoutingCases(certification, certificationSuitePath);
		Map<String, ObjectNode> actual = organicRoutingCases(matrix, matrixSuitePath);
		if (!expected.keySet().equals(actual.keySet()))
			throw new IOException("Routing matrix must contain exactly the organic certification cases");
		for (Map.Entry<String, ObjectNode> entry : expected.entrySet())
		{
			ObjectNode matrixCase = actual.get(entry.getKey());
			if (!object(entry.getValue().path("route_expectation"), "certification route expectation").equals(
				object(matrixCase.path("route_expectation"), "routing-matrix route expectation")))
			{
				throw new IOException("Routing matrix changed the route expectation for " + entry.getKey());
			}
			if (!matrixCase.path("routing_diagnostic").asBoolean(false) || matrixCase.has("deterministic_check"))
				throw new IOException("Routing matrix does not isolate route selection for " + entry.getKey());
		}
	}

	/**
	 * Returns the organic routing cases keyed by their stable identifiers.
	 *
	 * @param suite the suite to inspect
	 * @param suitePath the suite path
	 * @return the organic cases
	 * @throws IOException if an organic case identifier is duplicated
	 */
	private static Map<String, ObjectNode> organicRoutingCases(ObjectNode suite, Path suitePath) throws IOException
	{
		Map<String, ObjectNode> result = new HashMap<>();
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = object(node, "test case");
			if (!testCase.path("route_expectation").path("mode").asString().equals("organic"))
				continue;
			String caseId = text(testCase, "id", suitePath);
			if (result.put(caseId, testCase) != null)
				throw new IOException("Suite has duplicate organic routing case: " + caseId);
		}
		return result;
	}

	/**
	 * Resolves the regular rule files referenced by one router.
	 *
	 * @param rulesRoot the root that owns rule files
	 * @param routerRule the router to inspect
	 * @return the regular files named by the router
	 * @throws IOException if the router cannot be read
	 */
	private static Set<Path> referencedRulePaths(Path rulesRoot, Path routerRule) throws IOException
	{
		Set<Path> result = new HashSet<>();
		Matcher matcher = MARKDOWN_RULE_PATH.matcher(Files.readString(routerRule, StandardCharsets.UTF_8));
		while (matcher.find())
		{
			String reference = matcher.group();
			int rulesDirectory = reference.indexOf("/rules/");
			Path candidate;
			if (reference.startsWith("rules/"))
				candidate = rulesRoot.resolve(reference.substring("rules/".length())).normalize();
			else if (rulesDirectory >= 0)
				candidate = rulesRoot.resolve(reference.substring(rulesDirectory + "/rules/".length())).normalize();
			else
				candidate = routerRule.getParent().resolve(reference).normalize();
			if (candidate.startsWith(rulesRoot) && Files.isRegularFile(candidate))
				result.add(candidate);
		}
		return result;
	}

	/**
	 * Registers coordinator-owned routing-matrix operations.
	 *
	 * @param access the durable routing-matrix operations
	 */
	public static void configure(Access access)
	{
		RouterPromotion.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Returns the configured durable routing-matrix operations.
	 *
	 * @return the durable routing-matrix operations
	 * @throws AssertionError if evaluator composition did not configure router promotion
	 */
	private static Access access()
	{
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure router-promotion access");
		return result;
	}
}
