package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport.HookDifference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The observations returned by an authenticated native hook comparison.
 *
 * @param report the number and output differences of the compared hooks
 * @param failures the hooks that succeeded in production and failed in the trial
 * @param unapproved staged plugin hooks that lacked verified approval
 */
public record HookComparisonResult(HookReport report, List<String> failures, List<String> unapproved)
{
	/**
	 * The most bytes in one comparison response, including captured hook output.
	 */
	public static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
	private static final Set<String> RESULT_FIELDS = Set.of("checked", "differences", "failures", "unapproved");
	private static final Set<String> DIFFERENCE_FIELDS = Set.of("hook", "trial", "session");

	/**
	 * Creates an immutable comparison result.
	 *
	 * @param report the completed comparison report
	 * @param failures the hooks that fail only in the trial
	 * @param unapproved plugin hooks that lack verified approval
	 * @throws NullPointerException if an argument or list element is null
	 */
	public HookComparisonResult
	{
		Objects.requireNonNull(report);
		failures = List.copyOf(failures);
		unapproved = List.copyOf(unapproved);
	}

	/**
	 * Writes only the observations, without native commands, paths or policy fields for the receiver to act on.
	 *
	 * @param json the document mapper
	 * @return the observations document
	 */
	public ObjectNode document(JsonMapper json)
	{
		ObjectNode root = json.createObjectNode().put("checked", report.checked());
		ArrayNode differences = root.putArray("differences");
		for (HookDifference difference : report.differences())
		{
			ObjectNode item = differences.addObject().put("hook", difference.hook());
			difference.trial().forEach(item.putArray("trial")::add);
			difference.session().forEach(item.putArray("session")::add);
		}
		failures.forEach(root.putArray("failures")::add);
		unapproved.forEach(root.putArray("unapproved")::add);
		return root;
	}

	/**
	 * Validates a complete result from the selected native runner.
	 *
	 * @param root the result document
	 * @return the observations
	 * @throws IOException if any field is missing, unsupported or invalid
	 */
	public static HookComparisonResult parse(JsonNode root) throws IOException
	{
		requireFields(root, RESULT_FIELDS);
		JsonNode checked = root.path("checked");
		JsonNode differences = root.path("differences");
		if (!checked.isInt() || !differences.isArray() || checked.intValue() < differences.size())
			throw new IOException("Invalid checked count or differences in the hook comparison result");
		List<HookDifference> result = new ArrayList<>();
		for (JsonNode item : differences)
		{
			requireFields(item, DIFFERENCE_FIELDS);
			JsonNode hook = item.path("hook");
			if (!hook.isString() || hook.asString().isBlank())
				throw new IOException("A hook comparison difference requires a hook name");
			result.add(new HookDifference(hook.asString(), strings(item.path("trial")), strings(item.path("session"))));
		}
		return new HookComparisonResult(new HookReport(checked.intValue(), result), strings(root.path("failures")),
			strings(root.path("unapproved")));
	}

	/**
	 * Reads the answer for an exact request.
	 *
	 * @param json the response mapper
	 * @param directory the response directory named by the original request
	 * @return the result, or empty while the broker has not answered
	 * @throws IOException if the request failed or an answer cannot be validated
	 */
	public static Optional<HookComparisonResult> read(JsonMapper json, Path directory) throws IOException
	{
		Path file = directory.resolve(WorktreeResponse.FILE_NAME);
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The hook comparison response is not a regular file");
		JsonNode root;
		try
		{
			root = json.readTree(BoundedInput.readUtf8(file, MAXIMUM_BYTES, "Hook comparison response"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The hook comparison response is not valid JSON", e);
		}
		if (!root.path("format_version").isInt() || root.path("format_version").intValue() != 1 ||
			!directory.getFileName().toString().equals(root.path("id").asString("")))
		{
			throw new IOException("The hook comparison response does not identify the request");
		}
		String status = root.path("status").asString("");
		if (status.equals(WorktreeRequest.REFUSED) || status.equals(WorktreeRequest.FAILED))
		{
			requireFields(root, Set.of("format_version", "id", "status", "reason"));
			if (!root.path("reason").isString())
				throw new IOException("Missing reason for a failed hook comparison");
			throw new IOException(root.path("reason").asString());
		}
		requireFields(root, Set.of("format_version", "id", "status", "result"));
		if (!status.equals(WorktreeRequest.DONE))
			throw new IOException("Invalid hook comparison response status");
		return Optional.of(parse(root.path("result")));
	}

	/**
	 * Publishes the completed comparison after its inputs are released.
	 *
	 * @param json the response mapper
	 * @param id the original request identity
	 * @param directory the response directory
	 * @throws IOException if the response exceeds the bound or cannot be published
	 */
	void write(JsonMapper json, String id, Path directory) throws IOException
	{
		ObjectNode response = json.createObjectNode().put("format_version", 1).put("id", id).
			put("status", WorktreeRequest.DONE);
		response.set("result", document(json));
		if (json.writeValueAsBytes(response).length > MAXIMUM_BYTES)
			throw new IOException("The hook comparison output exceeds the response size limit");
		SessionRequestCli.write(json, directory.resolve(WorktreeResponse.FILE_NAME), response);
	}

	/**
	 * Requires exactly the declared contract fields.
	 *
	 * @param object the object to validate
	 * @param fields the required fields
	 * @throws IOException if the object has different fields
	 */
	private static void requireFields(JsonNode object, Set<String> fields) throws IOException
	{
		if (!object.isObject() || !Set.copyOf(object.propertyNames()).equals(fields))
			throw new IOException("Unexpected fields in a hook comparison response");
	}

	/**
	 * Reads a list of strings without coercion.
	 *
	 * @param array the value to read
	 * @return its text elements
	 * @throws IOException if the value is not an array or contains other types
	 */
	private static List<String> strings(JsonNode array) throws IOException
	{
		if (!array.isArray() || array.valueStream().anyMatch(value -> !value.isString()))
			throw new IOException("Expected a list of text values in the hook comparison result");
		return array.valueStream().map(JsonNode::stringValue).toList();
	}
}
