package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

/**
 * Constructs CAT route-preparation turns and records the exact installed rules they contain.
 */
public final class RoutePreparationInput
{
	private static final String ROUTE_PREPARATION = "route_preparation";
	private static final String ROUTE_SELECTION = "route-selection";
	private static final String ROUTING_ONLY = "routing_only";
	private static final String PREPARATION_TEMPLATE = "/prompts/route-preparation/preparation.md";
	private static final String DECLARED_ROUTE_TEMPLATE = "/prompts/route-preparation/declared-route.md";
	private static final String TASK_PROMPT_MARKER = "{{TASK_PROMPT}}";
	private static final String RULE_CONTENTS_MARKER = "{{RULE_CONTENTS}}";

	/**
	 * Identifies one installed rule delivered to a native pre-task turn.
	 *
	 * @param relativePath the selected rule path below the installed rules root
	 * @param sha256 the SHA-256 fingerprint of the exact delivered content
	 */
	public record DeliveredRule(String relativePath, String sha256)
	{
		/**
		 * Validates one delivered-rule receipt entry.
		 *
		 * @param relativePath the selected rule path
		 * @param sha256 the rule-content fingerprint
		 */
		public DeliveredRule
		{
			requireThat(relativePath, "relativePath").isNotBlank();
			requireThat(sha256, "sha256").isNotBlank();
		}
	}

	/**
	 * Holds resolved rule text and the identity receipt for that text.
	 *
	 * @param contents the exact rule text to insert into a request
	 * @param deliveredRules the identity of every inserted rule
	 */
	public record ResolvedRules(String contents, List<DeliveredRule> deliveredRules)
	{
		/**
		 * Validates and snapshots resolved rule text and its receipt.
		 *
		 * @param contents the exact resolved rule text
		 * @param deliveredRules the rule identity receipt
		 */
		public ResolvedRules
		{
			requireThat(contents, "contents").isNotBlank();
			requireThat(deliveredRules, "deliveredRules").isNotEmpty();
			for (DeliveredRule deliveredRule : deliveredRules)
				requireThat(deliveredRule, "deliveredRulesEntry").isNotNull();
			deliveredRules = List.copyOf(deliveredRules);
		}
	}

	/**
	 * Holds JSONL ready for transport and the route turn that received declared rules, if any.
	 *
	 * @param jsonl the complete prepared bridge input
	 * @param deliveredRules the installed rules placed in the preparatory turn
	 * @param deliveryTurn the one-based route turn number, or zero when no rules were directly delivered
	 */
	public record PreparedInput(String jsonl, List<DeliveredRule> deliveredRules, int deliveryTurn)
	{
		/**
		 * Validates and snapshots prepared bridge input and its optional direct-delivery receipt.
		 *
		 * @param jsonl the complete bridge input
		 * @param deliveredRules the optional rule identity receipt
		 * @param deliveryTurn the direct-delivery turn, or zero
		 */
		public PreparedInput
		{
			requireThat(jsonl, "jsonl").isNotNull();
			requireThat(deliveredRules, "deliveredRules").isNotNull();
			for (DeliveredRule deliveredRule : deliveredRules)
				requireThat(deliveredRule, "deliveredRulesEntry").isNotNull();
			deliveredRules = List.copyOf(deliveredRules);
			requireThat(deliveryTurn, "deliveryTurn").isNotNegative();
			if ((deliveryTurn == 0) != deliveredRules.isEmpty())
				throw new IllegalArgumentException("deliveryTurn must be zero exactly when no rules were directly delivered");
		}
	}

	/**
	 * Holds the prepared first request and whether later client requests are intentionally excluded.
	 *
	 * @param input the first request and any preparatory requests
	 * @param suppressRemainder whether the bridge must stop after those requests
	 */
	record PreparedPrefix(PreparedInput input, boolean suppressRemainder)
	{
	}

	/**
	 * Constructs any needed CAT pre-task turn before the input reaches a harness transport.
	 *
	 * @param input the caller's complete JSONL bridge input
	 * @param ruleRoot the caller-supplied installed rules root, or {@code null} when CAT preparation is unavailable
	 * @param scope services shared for the runner process lifetime
	 * @return complete JSONL and the receipt for any directly delivered rules
	 * @throws IOException if bridge input or a declared route is malformed or unavailable
	 */
	public static PreparedInput prepare(Reader input, Path ruleRoot, HarnessRunnerScope scope) throws IOException
	{
		requireThat(input, "input").isNotNull();
		requireThat(scope, "scope").isNotNull();
		String source = read(input);
		int lineEnd = source.indexOf('\n');
		String firstLine = source;
		if (lineEnd != -1)
			firstLine = source.substring(0, lineEnd);
		PreparedPrefix prefix = prepareFirstLine(firstLine, ruleRoot, scope);
		if (prefix.suppressRemainder() || lineEnd == -1)
			return prefix.input();
		PreparedInput prepared = prefix.input();
		return new PreparedInput(prepared.jsonl() + source.substring(lineEnd), prepared.deliveredRules(),
			prepared.deliveryTurn());
	}

	/**
	 * Prepares one request without waiting for a live client's later requests.
	 *
	 * @param firstLine the first JSONL request, or null at end of input
	 * @param ruleRoot the installed rules root, or null when preparation is unavailable
	 * @param scope services shared for the runner process lifetime
	 * @return prepared requests and whether later input should be suppressed
	 * @throws IOException if the request or a declared route is invalid
	 */
	static PreparedPrefix prepareFirstLine(String firstLine, Path ruleRoot, HarnessRunnerScope scope)
		throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		if (firstLine == null)
			return new PreparedPrefix(new PreparedInput("", List.of(), 0), false);
		if (firstLine.isBlank())
			return new PreparedPrefix(new PreparedInput(firstLine, List.of(), 0), false);
		ObjectNode first = object(firstLine, scope);
		if (!first.path("type").asString().equals("turn"))
			return new PreparedPrefix(new PreparedInput(firstLine, List.of(), 0), false);
		JsonNode prompt = first.path("prompt");
		if (!prompt.isString() || prompt.asString().isBlank())
			return new PreparedPrefix(new PreparedInput(firstLine, List.of(), 0), false);
		boolean routingOnly = routingOnly(first);
		if (routingOnly && ruleRoot == null)
			throw new IOException("routing_only requires installed CAT route preparation");
		if (ruleRoot == null)
			return new PreparedPrefix(new PreparedInput(firstLine, List.of(), 0), false);
		if (routingOnly && first.has(ROUTE_PREPARATION))
			throw new IOException("routing_only cannot declare a direct route");
		if (first.has(ROUTE_PREPARATION))
			return new PreparedPrefix(prepareDeclaredRoute(first, ruleRoot, scope), false);
		ObjectNode preparation = scope.json().createObjectNode();
		preparation.put("type", ROUTE_SELECTION);
		preparation.put("prompt", renderTemplate(PREPARATION_TEMPLATE, TASK_PROMPT_MARKER, prompt.asString()));
		if (routingOnly)
			return new PreparedPrefix(routingOnlyInput(preparation, scope), true);
		String prepared = jsonl(scope, preparation) + System.lineSeparator() + firstLine;
		return new PreparedPrefix(new PreparedInput(prepared, List.of(), 0), false);
	}

	/**
	 * Extracts the evaluator-owned routing-only control from an initial bridge request.
	 *
	 * @param first the parsed initial bridge request
	 * @return whether the request stops after the ordinary route-selection turn
	 * @throws IOException if the optional control is not the literal enabled value
	 */
	private static boolean routingOnly(ObjectNode first) throws IOException
	{
		JsonNode value = first.remove(ROUTING_ONLY);
		if (value == null)
			return false;
		if (!value.isBoolean() || !value.booleanValue())
			throw new IOException("routing_only must be true when present");
		return true;
	}

	/**
	 * Completes a routing diagnostic after its ordinary route-selection turn, without exposing its later task as a
	 * second native turn.
	 *
	 * @param preparation the normal pre-task route-selection request
	 * @param scope services shared for the runner process lifetime
	 * @return the bounded routing-only bridge input
	 * @throws IOException if the bridge input cannot be serialized
	 */
	private static PreparedInput routingOnlyInput(ObjectNode preparation, HarnessRunnerScope scope) throws IOException
	{
		ObjectNode complete = scope.json().createObjectNode();
		complete.put("type", "complete");
		String jsonl = jsonl(scope, preparation) + System.lineSeparator() +
			jsonl(scope, complete);
		return new PreparedInput(jsonl, List.of(), 0);
	}

	/**
	 * Validates a model-selected route before the bridge resolves any installed rule.
	 *
	 * @param response the complete final response from the route-selection turn
	 * @param scope services shared for the runner process lifetime
	 * @return canonical relative paths in the model-selected order
	 * @throws IOException if the response is not the exact selection protocol
	 */
	public static List<String> selectedRules(String response, HarnessRunnerScope scope) throws IOException
	{
		requireThat(response, "response").isNotBlank();
		requireThat(scope, "scope").isNotNull();
		try
		{
			JsonNode selection = scope.json().readTree(response);
			if (!selection.isObject() || selection.size() != 1 || !selection.path("selected_rules").isArray())
				throw new IOException("CAT route selection must be exactly a JSON object with selected_rules");
			List<String> result = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (JsonNode entry : selection.path("selected_rules"))
			{
				if (!entry.isString() || entry.stringValue().isBlank() || entry.stringValue().startsWith("rules/") ||
					!seen.add(entry.stringValue()))
					throw new IOException("CAT route selection must contain distinct nonblank rule paths");
				result.add(entry.stringValue());
			}
			return List.copyOf(result);
		}
		catch (JacksonException e)
		{
			throw new IOException("CAT route selection must be JSON", e);
		}
	}

	/**
	 * Reads declared installed rules, validates their paths, and returns their exact text and receipt.
	 *
	 * @param ruleRoot the installed root that contains the declared relative rules
	 * @param relativePaths the ordered paths below {@code ruleRoot}
	 * @return exact text to insert into a request and its rule identity receipt
	 * @throws IOException if a path is invalid or does not identify a regular file below {@code ruleRoot}
	 */
	public static ResolvedRules resolveRules(Path ruleRoot, List<String> relativePaths) throws IOException
	{
		requireThat(ruleRoot, "ruleRoot").isNotNull();
		requireThat(relativePaths, "relativePaths").isNotEmpty();
		StringBuilder contents = new StringBuilder();
		List<DeliveredRule> deliveredRules = new ArrayList<>();
		for (String relativePath : relativePaths)
		{
			if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/") ||
				relativePath.contains(".."))
				throw new IOException("CAT direct rule delivery contains an invalid relative rule path");
			Path rule = ruleRoot.resolve(relativePath).normalize();
			if (!rule.startsWith(ruleRoot) || !Files.isRegularFile(rule))
				throw new IOException("CAT direct rule delivery cannot find declared rule: " + relativePath);
			byte[] content = Files.readAllBytes(rule);
			String ruleText = new String(content, StandardCharsets.UTF_8);
			String block = "\n--- " + relativePath + " ---\n" + ruleText;
			contents.append(block);
			deliveredRules.add(new DeliveredRule(relativePath, sha256(content)));
		}
		return new ResolvedRules(contents.toString(), List.copyOf(deliveredRules));
	}

	/**
	 * Renders the dedicated native pre-task turn that activates already validated installed rules.
	 *
	 * @param rules the exact installed rules selected for the later task
	 * @return the complete delivery prompt
	 * @throws IOException if the packaged delivery instruction is malformed
	 */
	public static String deliveryPrompt(ResolvedRules rules) throws IOException
	{
		requireThat(rules, "rules").isNotNull();
		return renderTemplate(DECLARED_ROUTE_TEMPLATE, RULE_CONTENTS_MARKER, rules.contents());
	}

	/**
	 * Constructs a direct route turn from declared paths before the original task.
	 *
	 * @param first parsed initial task request
	 * @param ruleRoot installed root that owns the declared paths
	 * @param scope services shared for the runner process lifetime
	 * @return prepared input with its direct-delivery receipt
	 * @throws IOException if the route is malformed or unavailable
	 */
	private static PreparedInput prepareDeclaredRoute(ObjectNode first, Path ruleRoot, HarnessRunnerScope scope)
		throws IOException
	{
		JsonNode declared = first.remove(ROUTE_PREPARATION);
		if (!declared.isArray() || declared.isEmpty())
			throw new IOException("route_preparation must be a nonempty array of relative rule paths");
		List<String> paths = new ArrayList<>(declared.size());
		for (JsonNode entry : declared)
		{
			if (!entry.isString())
				throw new IOException("route_preparation entries must be nonblank relative rule paths");
			paths.add(entry.stringValue());
		}
		ResolvedRules rules = resolveRules(ruleRoot, paths);
		ObjectNode route = scope.json().createObjectNode();
		route.put("type", "turn");
		route.put("prompt", deliveryPrompt(rules));
		String result = jsonl(scope, route) + System.lineSeparator() + jsonl(scope, first);
		return new PreparedInput(result, rules.deliveredRules(), 1);
	}

	/**
	 * Returns the fingerprint of one delivered rule.
	 *
	 * @param content the exact delivered bytes
	 * @return the lowercase SHA-256 hexadecimal fingerprint
	 */
	private static String sha256(byte[] content)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Reads complete bridge input before a synthetic first request is added.
	 *
	 * @param input the bridge input
	 * @return complete bridge input text
	 * @throws IOException if input cannot be read
	 */
	private static String read(Reader input) throws IOException
	{
		StringWriter contents = new StringWriter();
		input.transferTo(contents);
		return contents.toString();
	}

	/**
	 * Inserts one value into the required placeholder of a packaged instruction.
	 *
	 * @param resource the template resource
	 * @param marker the required placeholder
	 * @param value the replacement text
	 * @return the rendered instruction
	 * @throws IOException if the resource is absent or malformed
	 */
	private static String renderTemplate(String resource, String marker, String value) throws IOException
	{
		try (InputStream input = RoutePreparationInput.class.getResourceAsStream(resource))
		{
			if (input == null)
				throw new IOException("Packaged route-preparation prompt is absent: " + resource);
			String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			int first = template.indexOf(marker);
			if (first < 0 || first != template.lastIndexOf(marker))
				throw new IOException("Packaged route-preparation prompt must contain exactly one " + marker + ": " + resource);
			return template.substring(0, first) + value + template.substring(first + marker.length());
		}
	}

	/**
	 * Serializes one bridge record without indentation so its JSON remains one JSONL record.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param record the bridge record
	 * @return one compact JSONL record
	 * @throws IOException if the record cannot be serialized
	 */
	private static String jsonl(HarnessRunnerScope scope, JsonNode record) throws IOException
	{
		return scope.json().writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(record);
	}

	/**
	 * Parses one bridge JSON object.
	 *
	 * @param line one JSONL request
	 * @param scope services shared for the runner process lifetime
	 * @return the parsed object
	 * @throws IOException if the line is not a JSON object
	 */
	private static ObjectNode object(String line, HarnessRunnerScope scope) throws IOException
	{
		try
		{
			JsonNode value = scope.json().readTree(line);
			if (value == null || !value.isObject())
				throw new IOException("Bridge message must be a JSON object");
			return (ObjectNode) value;
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid bridge JSON", e);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private RoutePreparationInput()
	{
	}
}
