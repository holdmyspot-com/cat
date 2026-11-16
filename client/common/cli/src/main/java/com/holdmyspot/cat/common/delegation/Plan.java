package com.holdmyspot.cat.common.delegation;

import com.holdmyspot.cat.common.SessionDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads an immutable, bounded delegation request.
 */
public final class Plan
{
	private static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
	private static final Set<String> ROOT_FIELDS = Set.of("format_version", "project_path", "source_paths",
		"excluded_roots", "work", "cleanup_owner");
	private static final Set<String> WORK_FIELDS = Set.of("work_id", "assignment_id", "write_paths", "verification");
	private static final Set<String> VERIFICATION_FIELDS = Set.of("gate_kind", "modules", "tests", "scope_authority");
	private static final Set<String> AUTHORITY_FIELDS = Set.of("assignment_id", "review_id", "reviewer",
		"definition_paths", "project_output_roots", "permitted_owned_roots");
	private static final Set<String> OWNED_ROOTS = Set.of("home", "repository", "temporary", "diagnostics");
	private static final Set<String> REQUIRED_EXCLUSIONS = Set.of(".git", SessionDirectory.ROOT, ".cat-tmp");

	private final List<String> sourcePaths;
	private final List<String> excludedRoots;
	private final List<Work> work;
	private final String cleanupOwner;
	private final byte[] serialized;
	private final String identity;

	/**
	 * Creates an immutable request value.
	 *
	 * @param sourcePaths selected source files
	 * @param excludedRoots generated output roots
	 * @param work independently assigned work units
	 * @param cleanupOwner actor responsible for eventual cleanup
	 * @param serialized original validated request bytes
	 */
	private Plan(List<String> sourcePaths, List<String> excludedRoots, List<Work> work, String cleanupOwner,
		byte[] serialized)
	{
		this.sourcePaths = List.copyOf(sourcePaths);
		this.excludedRoots = List.copyOf(excludedRoots);
		this.work = List.copyOf(work);
		this.cleanupOwner = cleanupOwner;
		this.serialized = serialized.clone();
		this.identity = digest(serialized);
	}

	/**
	 * Reads and validates a delegation request.
	 *
	 * @param mapper the configured JSON mapper
	 * @param path the request file
	 * @return the immutable request
	 * @throws IOException if the file is too large, malformed, or invalid
	 */
	public static Plan read(JsonMapper mapper, Path path) throws IOException
	{
		byte[] content;
		try (InputStream input = Files.newInputStream(path))
		{
			content = input.readNBytes(MAXIMUM_BYTES + 1);
		}
		if (content.length > MAXIMUM_BYTES)
			throw new IOException(path + ": request exceeds " + MAXIMUM_BYTES + " bytes");

		JsonNode root;
		try
		{
			ObjectReader reader = mapper.readerFor(JsonNode.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
				with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
			root = reader.readValue(content);
		}
		catch (JacksonException e)
		{
			TokenStreamLocation location = e.getLocation();
			String coordinates = "";
			if (location != null)
				coordinates = " at line " + location.getLineNr() + ", column " + location.getColumnNr();
			throw new IOException(path + coordinates + ": malformed request: " + e.getOriginalMessage(), e);
		}
		if (root == null || !root.isObject())
			throw invalid(path, "root", "must be an object");
		checkFields(path, root, ROOT_FIELDS, "root");
		if (!root.has("format_version") || !root.get("format_version").isInt() ||
			root.get("format_version").intValue() != 1)
			throw invalid(path, "format_version", "must be integer 1");
		if (!".".equals(text(path, root, "project_path")))
			throw invalid(path, "project_path", "must be .");
		List<String> sources = strings(path, root, "source_paths", true);
		List<String> exclusions = strings(path, root, "excluded_roots", true);
		validatePaths(path, "source_paths", sources);
		validatePaths(path, "excluded_roots", exclusions);
		if (!new HashSet<>(exclusions).containsAll(REQUIRED_EXCLUSIONS))
			throw invalid(path, "excluded_roots", "must include " + REQUIRED_EXCLUSIONS);
		for (String source : sources)
			if (exclusions.stream().anyMatch(exclusion -> isWithin(source, exclusion)))
				throw invalid(path, "source_paths", "selected source is excluded: " + source);
		String cleanupOwner = text(path, root, "cleanup_owner");
		JsonNode workNode = required(path, root, "work");
		if (!workNode.isArray() || workNode.isEmpty())
			throw invalid(path, "work", "must be a nonempty array");
		List<Work> work = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		Set<String> assignedWrites = new HashSet<>();
		for (JsonNode item : workNode)
		{
			if (!item.isObject())
				throw invalid(path, "work", "each entry must be an object");
			checkFields(path, item, WORK_FIELDS, "work");
			String id = text(path, item, "work_id");
			if (!ids.add(id))
				throw invalid(path, "work_id", "must be unique");
			String assignment = text(path, item, "assignment_id");
			List<String> writes = strings(path, item, "write_paths", true);
			validatePaths(path, "write_paths", writes);
			for (String write : writes)
			{
				if (!assignedWrites.add(write.toLowerCase(java.util.Locale.ROOT)))
					throw invalid(path, "write_paths", "overlaps another work unit: " + write);
				if (exclusions.stream().anyMatch(exclusion -> isWithin(write, exclusion)))
					throw invalid(path, "write_paths", "write path is excluded: " + write);
			}
			JsonNode verificationNode = required(path, item, "verification");
			if (!verificationNode.isObject())
				throw invalid(path, "verification", "must be an object");
			checkFields(path, verificationNode, VERIFICATION_FIELDS, "verification");
			if (!"project_maven".equals(text(path, verificationNode, "gate_kind")))
				throw invalid(path, "gate_kind", "must be project_maven");
			List<String> modules = strings(path, verificationNode, "modules", true);
			List<String> tests = strings(path, verificationNode, "tests", true);
			validatePaths(path, "modules", modules);
			for (String test : tests)
				if (!test.matches("[A-Za-z_$][A-Za-z0-9_$.]*"))
					throw invalid(path, "tests", "must contain class names without expressions or options");
			JsonNode authority = required(path, verificationNode, "scope_authority");
			if (!authority.isObject())
				throw invalid(path, "scope_authority", "must be an object");
			checkFields(path, authority, AUTHORITY_FIELDS, "scope_authority");
			String authorityAssignment = text(path, authority, "assignment_id");
			if (!assignment.equals(authorityAssignment))
				throw invalid(path, "scope_authority.assignment_id", "must match the work assignment");
			String reviewId = text(path, authority, "review_id");
			String reviewer = text(path, authority, "reviewer");
			List<String> definitions = strings(path, authority, "definition_paths", true);
			List<String> outputs = strings(path, authority, "project_output_roots", true);
			List<String> owned = strings(path, authority, "permitted_owned_roots", true);
			validatePaths(path, "definition_paths", definitions);
			validatePaths(path, "project_output_roots", outputs);
			Set<String> reviewedDefinitions = new HashSet<>(sources);
			reviewedDefinitions.addAll(writes);
			if (!reviewedDefinitions.containsAll(definitions))
				throw invalid(path, "definition_paths", "each path must be a selected source or work write path");
			if (!new HashSet<>(exclusions).containsAll(outputs))
				throw invalid(path, "project_output_roots", "each output root must be an excluded root");
			for (String output : outputs)
				if (sources.stream().anyMatch(source -> isWithin(source, output)) ||
					writes.stream().anyMatch(write -> isWithin(write, output)))
					throw invalid(path, "project_output_roots", "output root overlaps source or write paths: " + output);
			if (!Set.copyOf(owned).equals(OWNED_ROOTS))
				throw invalid(path, "permitted_owned_roots", "must contain exactly " + OWNED_ROOTS);
			work.add(new Work(id, assignment, writes,
				new Verification(modules, tests, reviewId, reviewer, definitions, outputs)));
		}
		return new Plan(sources, exclusions, work, cleanupOwner, content);
	}

	/**
	 * Returns selected source paths.
	 *
	 * @return selected source paths
	 */
	public List<String> sourcePaths()
	{
		return sourcePaths;
	}

	/**
	 * Returns excluded output roots.
	 *
	 * @return excluded output roots
	 */
	public List<String> excludedRoots()
	{
		return excludedRoots;
	}

	/**
	 * Returns requested work units.
	 *
	 * @return requested work units
	 */
	public List<Work> work()
	{
		return work;
	}

	/**
	 * Returns the cleanup owner.
	 *
	 * @return the cleanup owner
	 */
	public String cleanupOwner()
	{
		return cleanupOwner;
	}

	/**
	 * Returns the immutable request identity.
	 *
	 * @return SHA-256 identity of the validated request bytes
	 */
	public String identity()
	{
		return identity;
	}

	/**
	 * Persists the validated request bytes as the cohort-owned plan.
	 *
	 * @param path cohort plan path
	 * @throws IOException if the plan cannot be created
	 */
	void writeTo(Path path) throws IOException
	{
		Files.write(path, serialized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	/**
	 * Locates a work unit by its stable identifier.
	 *
	 * @param workId stable identifier
	 * @return matching work unit
	 * @throws IOException if no such work unit exists
	 */
	Work work(String workId) throws IOException
	{
		for (Work item : work)
			if (item.workId().equals(workId))
				return item;
		throw new IOException("Unknown work identifier: " + workId);
	}

	/**
	 * Reads one nonblank string field.
	 *
	 * @param path request file
	 * @param object containing JSON object
	 * @param field field name
	 * @return field value
	 * @throws IOException if the field is absent or has the wrong type
	 */
	private static String text(Path path, JsonNode object, String field) throws IOException
	{
		JsonNode value = required(path, object, field);
		if (!value.isString() || value.stringValue().isBlank())
			throw invalid(path, field, "must be a nonblank string");
		return value.stringValue();
	}

	/**
	 * Returns a required non-null field.
	 *
	 * @param path request file
	 * @param object containing JSON object
	 * @param field field name
	 * @return field value
	 * @throws IOException if the field is absent or null
	 */
	private static JsonNode required(Path path, JsonNode object, String field) throws IOException
	{
		if (!object.has(field) || object.get(field).isNull())
			throw invalid(path, field, "is required");
		return object.get(field);
	}

	/**
	 * Reads a nonempty array of strings.
	 *
	 * @param path request file
	 * @param object containing JSON object
	 * @param field field name
	 * @param unique whether duplicate entries are rejected
	 * @return immutable array values
	 * @throws IOException if the field is absent or has invalid entries
	 */
	private static List<String> strings(Path path, JsonNode object, String field, boolean unique) throws IOException
	{
		JsonNode value = required(path, object, field);
		if (!value.isArray())
			throw invalid(path, field, "must be an array");
		List<String> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonNode item : value)
		{
			if (!item.isString() || item.stringValue().isBlank())
				throw invalid(path, field, "entries must be nonblank strings");
			String entry = item.stringValue();
			if (unique && !seen.add(entry))
				throw invalid(path, field, "entries must be unique");
			result.add(entry);
		}
		if (result.isEmpty())
			throw invalid(path, field, "must be nonempty");
		return List.copyOf(result);
	}

	/**
	 * Rejects unknown object fields.
	 *
	 * @param path request file
	 * @param object object to check
	 * @param allowed allowed field names
	 * @param label schema location for diagnostics
	 * @throws IOException if an unknown field is present
	 */
	private static void checkFields(Path path, JsonNode object, Set<String> allowed, String label) throws IOException
	{
		for (String field : object.propertyNames())
		{
			if (!allowed.contains(field))
				throw invalid(path, label, "unknown field " + field);
		}
	}

	/**
	 * Creates a path- and field-specific schema error.
	 *
	 * @param path request file
	 * @param field invalid field
	 * @param detail validation failure
	 * @return schema error
	 */
	private static IOException invalid(Path path, String field, String detail)
	{
		return new IOException(path + ": invalid " + field + ": " + detail);
	}

	/**
	 * Computes the SHA-256 identity of request bytes.
	 *
	 * @param bytes request bytes
	 * @return lowercase hexadecimal digest
	 */
	private static String digest(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is required by the Java platform", e);
		}
	}

	/**
	 * Validates exact slash-separated project-relative paths and case aliases.
	 *
	 * @param request request file
	 * @param field schema field
	 * @param paths path values
	 * @throws IOException if a path is unsafe or aliases another path
	 */
	private static void validatePaths(Path request, String field, List<String> paths) throws IOException
	{
		Set<String> normalized = new HashSet<>();
		for (String value : paths)
		{
			boolean hasUnsafeSegment = value.startsWith("/") || value.contains("\\") || value.indexOf('\0') >= 0;
			boolean isGitExclusion = field.equals("excluded_roots") && value.equals(".git");
			String[] segments = value.split("/", -1);
			for (String segment : segments)
				hasUnsafeSegment |= segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
					segment.equalsIgnoreCase(".git") && !isGitExclusion;
			if (hasUnsafeSegment)
				throw invalid(request, field, "unsafe project-relative path: " + value);
			if (!normalized.add(value.toLowerCase(java.util.Locale.ROOT)))
				throw invalid(request, field, "path alias or case collision: " + value);
		}
	}

	/**
	 * Determines whether a project-relative path is equal to or below a root.
	 *
	 * @param path project-relative path
	 * @param root project-relative root
	 * @return whether the path is within the root
	 */
	private static boolean isWithin(String path, String root)
	{
		return path.equals(root) || path.startsWith(root + "/");
	}

	/**
	 * One independently assigned work unit.
	 *
	 * @param workId stable work identifier
	 * @param assignmentId parent assignment identity
	 * @param writePaths exact writable project paths
	 * @param verification reviewed Maven gate
	 */
	public record Work(String workId, String assignmentId, List<String> writePaths, Verification verification)
	{
		/**
		 * Copies all collection values.
		 *
		 * @param workId stable work identifier
		 * @param assignmentId parent assignment identity
		 * @param writePaths exact writable project paths
		 * @param verification reviewed Maven gate
		 */
		public Work
		{
			writePaths = List.copyOf(writePaths);
		}
	}

	/**
	 * Reviewed Maven verification definition.
	 *
	 * @param modules Maven modules relative to client
	 * @param tests focused test classes
	 * @param reviewId reviewer-issued scope decision identity
	 * @param reviewer reviewer identity
	 * @param definitionPaths reviewed gate definition paths
	 * @param projectOutputRoots exact project output roots
	 */
	public record Verification(List<String> modules, List<String> tests, String reviewId, String reviewer,
		List<String> definitionPaths, List<String> projectOutputRoots)
	{
		/**
		 * Copies all collection values.
		 *
		 * @param modules Maven modules relative to client
		 * @param tests focused test classes
		 * @param reviewId reviewer-issued scope decision identity
		 * @param reviewer reviewer identity
		 * @param definitionPaths reviewed gate definition paths
		 * @param projectOutputRoots exact project output roots
		 */
		public Verification
		{
			modules = List.copyOf(modules);
			tests = List.copyOf(tests);
			definitionPaths = List.copyOf(definitionPaths);
			projectOutputRoots = List.copyOf(projectOutputRoots);
		}
	}
}
