package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.Fingerprints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the runner-enforced description of a mechanically executable implementation plan.
 *
 * <p>The parent supplies this compact document beside the detailed plan. It binds the plan bytes to the permitted
 * writes and their required final bytes, so the runner can reject contradictory work before starting a harness.</p>
 */
public final class WorkExecutePlan
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
	private static final String SCHEMA_VERSION = "cat-work-execute-plan-v1";
	private static final Set<String> FIELDS = Set.of("schema_version", "plan_path", "plan_sha256",
		"workspace_mode", "writes");
	private static final Set<String> WRITE_FIELDS = Set.of("step", "path", "sha256");

	/**
	 * Reads and validates one plan manifest relative to its project.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param source the manifest path
	 * @param project the project root that contains the detailed plan and permitted writes
	 * @return the validated plan manifest
	 * @throws IOException if the manifest is malformed, escapes the project, or binds a different detailed plan
	 */
	public static Manifest read(JsonMapper json, Path source, Path project) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		Path projectRoot = projectRoot(project);
		ObjectNode document = document(json, source);
		verifyFields(document, FIELDS, source, "Work-execute plan manifest");
		if (!text(document, "schema_version", source).equals(SCHEMA_VERSION))
			throw new IOException("Unsupported work-execute plan manifest schema: " + source);
		Path planPath = projectPath(projectRoot, text(document, "plan_path", source), source, "plan_path");
		String planFingerprint = fingerprint(text(document, "plan_sha256", source), source, "plan_sha256");
		if (!Files.isRegularFile(planPath))
			throw new IOException("Work-execute plan is not a regular file: " + planPath);
		if (!Fingerprints.ofFile(planPath).equals(planFingerprint))
			throw new IOException("Work-execute plan does not match its manifest fingerprint: " + planPath);
		WorkspaceMode workspaceMode = WorkspaceMode.fromExternalValue(text(document, "workspace_mode", source));
		List<Write> writes = writes(document, projectRoot, source);
		return new Manifest(projectRoot, planPath, workspaceMode, writes);
	}

	/**
	 * Prevents construction of this manifest reader.
	 */
	private WorkExecutePlan()
	{
	}

	/**
	 * Reads the strict JSON document with bounded input.
	 *
	 * @param json the JSON mapper
	 * @param source the manifest path
	 * @return the parsed object
	 * @throws IOException if the document is malformed or not an object
	 */
	private static ObjectNode document(JsonMapper json, Path source) throws IOException
	{
		try
		{
			JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strict.readTree(BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES,
				"Work-execute plan manifest"));
			if (parsed instanceof ObjectNode object)
				return object;
			throw new IOException("Work-execute plan manifest is not a JSON object: " + source);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid work-execute plan manifest: " + source, e);
		}
	}

	/**
	 * Reads the exact write list and identifies any conflicting final content for one path.
	 *
	 * @param document the parsed manifest
	 * @param projectRoot the canonical project root
	 * @param source the manifest path
	 * @return immutable declared writes
	 * @throws IOException if a write is malformed or two steps require different content for one path
	 */
	private static List<Write> writes(ObjectNode document, Path projectRoot, Path source) throws IOException
	{
		JsonNode value = document.get("writes");
		if (!(value instanceof ArrayNode entries))
			throw new IOException("Work-execute plan manifest has invalid writes: " + source);
		List<Write> result = new ArrayList<>();
		java.util.Map<Path, Write> byPath = new java.util.LinkedHashMap<>();
		for (int index = 0; index < entries.size(); ++index)
		{
			if (!(entries.get(index) instanceof ObjectNode entry))
				throw new IOException("Work-execute plan manifest write is not an object: " + source);
			verifyFields(entry, WRITE_FIELDS, source, "Work-execute plan manifest write");
			Write write = new Write(text(entry, "step", source), projectPath(projectRoot, text(entry, "path", source),
				source, "writes.path"), fingerprint(text(entry, "sha256", source), source, "writes.sha256"));
			Write previous = byPath.putIfAbsent(write.path(), write);
			if (previous != null && !previous.sha256().equals(write.sha256()))
				throw new IOException("Work-execute plan has contradictory writes for " + write.path() + " in " +
					previous.step() + " and " + write.step());
			result.add(write);
		}
		return List.copyOf(result);
	}

	/**
	 * Resolves one non-escaping project-relative path.
	 *
	 * @param projectRoot the canonical project root
	 * @param relativePath the manifest path text
	 * @param source the manifest path
	 * @param field the logical field name
	 * @return the canonical path inside the project
	 * @throws IOException if the path is blank, absolute, or escapes the project
	 */
	private static Path projectPath(Path projectRoot, String relativePath, Path source, String field) throws IOException
	{
		Path path = Path.of(relativePath);
		if (path.isAbsolute())
			throw new IOException("Work-execute plan manifest " + field + " must be project-relative: " + source);
		Path result = projectRoot.resolve(path).normalize();
		if (!result.startsWith(projectRoot))
			throw new IOException("Work-execute plan manifest " + field + " escapes the project: " + source);
		return result;
	}

	/**
	 * Canonicalizes the project root.
	 *
	 * @param project the supplied project root
	 * @return the canonical project root
	 * @throws IOException if the project is absent
	 */
	private static Path projectRoot(Path project) throws IOException
	{
		Objects.requireNonNull(project, "project must not be null");
		Path result = project.toAbsolutePath().normalize();
		if (!Files.isDirectory(result))
			throw new IOException("Work-execute project is not a directory: " + result);
		return result;
	}

	/**
	 * Reads required nonblank text.
	 *
	 * @param document the object containing the field
	 * @param field the field name
	 * @param source the manifest path
	 * @return the required text
	 * @throws IOException if the field is absent or invalid
	 */
	private static String text(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Work-execute plan manifest has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Validates a lowercase SHA-256 fingerprint.
	 *
	 * @param value the fingerprint text
	 * @param source the manifest path
	 * @param field the field name
	 * @return the valid fingerprint
	 * @throws IOException if the fingerprint is invalid
	 */
	private static String fingerprint(String value, Path source, String field) throws IOException
	{
		if (!value.matches("[0-9a-f]{64}"))
			throw new IOException("Work-execute plan manifest has invalid " + field + ": " + source);
		return value;
	}

	/**
	 * Validates an object's exact field set.
	 *
	 * @param document the object to validate
	 * @param expected the exact expected field names
	 * @param source the manifest path
	 * @param description the logical object description
	 * @throws IOException if a field is missing or unsupported
	 */
	private static void verifyFields(ObjectNode document, Set<String> expected, Path source, String description)
		throws IOException
	{
		Set<String> actual = new HashSet<>(document.propertyNames());
		if (!actual.equals(expected))
			throw new IOException(description + " has unsupported or missing fields: " + source);
	}

	/**
	 * Names the workspace ownership arrangement the parent established before execution.
	 */
	public enum WorkspaceMode
	{
		EXCLUSIVE_SHARED("exclusive_shared"),
		ISOLATED("isolated");

		private final String externalValue;

		/**
		 * Creates one serialized workspace mode.
		 *
		 * @param externalValue the exact manifest value
		 */
		WorkspaceMode(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Parses one exact workspace mode.
		 *
		 * @param value the serialized mode
		 * @return the matching mode
		 */
		public static WorkspaceMode fromExternalValue(String value)
		{
			for (WorkspaceMode candidate : values())
				if (candidate.externalValue.equals(value))
					return candidate;
			throw new IllegalArgumentException("Unsupported work-execute workspace mode: " + value);
		}
	}

	/**
	 * Holds one runner-validated detailed plan and its allowed writes.
	 *
	 * @param projectRoot the project root used to resolve plan and write paths
	 * @param planPath the bound detailed-plan path
	 * @param workspaceMode the parent-declared workspace mode
	 * @param writes the ordered required file outcomes
	 */
	public record Manifest(Path projectRoot, Path planPath, WorkspaceMode workspaceMode, List<Write> writes)
	{
		/**
		 * Validates the final file bytes after the harness has completed the plan.
		 *
		 * @param completedProject the isolated project directory after harness execution
		 * @throws IOException if a declared file was not produced with its required bytes
		 */
		public void verifyWrites(Path completedProject) throws IOException
		{
			for (Write write : writes)
			{
				Path output = completedProject.toAbsolutePath().normalize().resolve(projectRoot.relativize(write.path()));
				if (!Files.isRegularFile(output) || !Fingerprints.ofFile(output).equals(write.sha256()))
					throw new IOException("Work-execute did not produce the required bytes for " + output +
						" from " + write.step());
			}
		}

		/**
		 * Validates the manifest's immutable values.
		 *
		 * @param projectRoot the project root used to resolve plan and write paths
		 * @param planPath the bound detailed-plan path
		 * @param workspaceMode the parent-declared workspace mode
		 * @param writes the ordered required file outcomes
		 */
		public Manifest
		{
			projectRoot = Objects.requireNonNull(projectRoot, "projectRoot must not be null");
			planPath = Objects.requireNonNull(planPath, "planPath must not be null");
			workspaceMode = Objects.requireNonNull(workspaceMode, "workspaceMode must not be null");
			writes = List.copyOf(writes);
		}
	}

	/**
	 * Holds one exact file result required by a detailed-plan step.
	 *
	 * @param step the detailed-plan step identifier
	 * @param path the file path inside the project
	 * @param sha256 the required final file fingerprint
	 */
	public record Write(String step, Path path, String sha256)
	{
		/**
		 * Validates one declared file outcome.
		 *
		 * @param step the detailed-plan step identifier
		 * @param path the file path inside the project
		 * @param sha256 the required final file fingerprint
		 */
		public Write
		{
			if (Objects.requireNonNull(step, "step must not be null").isBlank())
				throw new IllegalArgumentException("step must not be blank");
			path = Objects.requireNonNull(path, "path must not be null");
			sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
		}
	}
}
