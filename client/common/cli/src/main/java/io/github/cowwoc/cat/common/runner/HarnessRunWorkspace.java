package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.ExecutionContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectExecutionSnapshot;
import io.github.cowwoc.cat.common.optimizer.runner.PrivateGenerationContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceContext;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceMaterializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Binds a retained harness run to the project it actually executes in.
 *
 * @param runId the owned native run identity
 * @param context the selected project or private generation authority
 * @param executionProject the native process working directory
 */
public record HarnessRunWorkspace(String runId, ExecutionContext context, Path executionProject)
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<String> FIELDS = Set.of("schemaVersion", "runId", "mode", "sourceProject",
		"executionProject", "snapshotManifestRef", "confirmedEditableRootsRef", "liveCheckpointRef");
	private static final Set<String> PRIVATE_FIELDS = Set.of("schemaVersion", "runId", "mode",
		"selectedProject", "privateProject", "executionProject", "generationRequestRef", "readinessRef",
		"confirmedRootsRef");

	/**
	 * Validates one run's selected execution project.
	 *
	 * @param runId the owned native run identity
	 * @param context the selected project or private generation authority
	 * @param executionProject the absolute normalized native working directory
	 * @throws NullPointerException if context or executionProject is null
	 * @throws IllegalArgumentException if the execution project differs from its context or the run ID is invalid
	 */
	public HarnessRunWorkspace
	{
		HarnessRun.requireValidId(runId);
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(executionProject, "executionProject");
		if (!executionProject.isAbsolute() || !executionProject.equals(executionProject.normalize()))
			throw new IllegalArgumentException("executionProject must be absolute and normalized");
		if (context instanceof ProjectRunContext project)
		{
			if (project.mode() == ProjectWorkspaceMode.ACTIVE_WORKTREE &&
				!executionProject.equals(project.snapshot().projectRoot()))
				throw new IllegalArgumentException("Active harness execution project differs from selected worktree");
		}
		else if (context instanceof PrivateGenerationContext generation)
		{
			if (!executionProject.equals(generation.privateProject()))
				throw new IllegalArgumentException("Private harness execution project differs from generation worktree");
		}
		else
			throw new IllegalArgumentException("harness workspace binding requires a project context");
	}

	/**
	 * Returns the request-bound execution path, refusing an unbound active run even before its first turn.
	 *
	 * @param run the retained owned run
	 * @param request the original native call
	 * @param evidenceReader the reader for immutable request inputs
	 * @return the project actually selected for execution
	 * @throws IOException if the retained binding is missing, malformed, or differs from the request
	 */
	public static Path executionProject(HarnessRun run, NestedRunRequest request, EvidenceReader evidenceReader)
		throws IOException
	{
		Objects.requireNonNull(run, "run");
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(evidenceReader, "evidenceReader");
		Path copiedProject = run.directory().resolve("project").toAbsolutePath().normalize();
		if (request.context() instanceof SterileWorkspaceContext sterile)
		{
			if (!Files.isDirectory(copiedProject, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Copied harness sterile workspace is missing: " + copiedProject);
			SterileWorkspaceMaterializer.verify(sterile, evidenceReader, copiedProject);
			return copiedProject;
		}
		if (request.context() instanceof PrivateGenerationContext generation)
		{
			Path source = artifact(run);
			if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Private harness workspace binding is missing: " + source);
			HarnessRunWorkspace binding = read(source);
			if (!binding.runId().equals(run.id()) || !binding.context().equals(generation))
				throw new IOException("Private harness workspace binding differs from requested run: " + source);
			if (!Files.isDirectory(generation.privateProject(), LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Private harness worktree is missing: " + generation.privateProject());
			return binding.executionProject();
		}
		if (!(request.context() instanceof ProjectRunContext context))
			throw new IOException("harness result requires a project execution context");
		Path source = artifact(run);
		if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
		{
			if (context.mode() == ProjectWorkspaceMode.ACTIVE_WORKTREE)
				throw new IOException("Active harness workspace binding is missing: " + source);
			return copiedProject;
		}
		HarnessRunWorkspace binding = read(source);
		if (!binding.runId().equals(run.id()) || !binding.context().equals(context))
			throw new IOException("harness workspace binding differs from requested run: " + source);
		if (context.mode() == ProjectWorkspaceMode.ISOLATED_COPY && !binding.executionProject().equals(copiedProject))
			throw new IOException("Copied harness workspace binding differs from owned project: " + source);
		return binding.executionProject();
	}

	/**
	 * Seals the selected workspace before native startup, refusing a conflicting retained binding.
	 *
	 * @param run the owned run
	 * @throws IOException if the private worktree is missing or redirected, or the binding cannot be published
	 */
	public void seal(HarnessRun run) throws IOException
	{
		Objects.requireNonNull(run, "run");
		if (!run.id().equals(runId))
			throw new IOException("harness workspace binding run ID differs from owned run");
		if (context instanceof PrivateGenerationContext &&
			(!Files.isDirectory(executionProject, LinkOption.NOFOLLOW_LINKS) ||
				!executionProject.toRealPath().equals(executionProject)))
			throw new IOException("Private harness worktree is missing or redirected: " + executionProject);
		Path destination = artifact(run);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
		{
			if (!equals(read(destination)))
				throw new IOException("harness workspace binding conflicts with retained artifact: " + destination);
			return;
		}
		Path parent = destination.getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".workspace-binding-", ".json");
		try
		{
			JSON.writeValue(temporary.toFile(), toJson());
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Returns the sealed binding path under one run's artifacts.
	 *
	 * @param run the owned run
	 * @return the binding artifact path
	 */
	private static Path artifact(HarnessRun run)
	{
		return run.directory().resolve("artifacts/workspace-binding.json");
	}

	/**
	 * Reads a bounded, strictly shaped workspace binding.
	 *
	 * @param source the binding artifact path
	 * @return the parsed binding
	 * @throws IOException if the artifact cannot be trusted
	 */
	private static HarnessRunWorkspace read(Path source) throws IOException
	{
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > 64 * 1024)
			throw new IOException("harness workspace binding is not a bounded regular file: " + source);
		try
		{
			JsonNode raw = JSON.readTree(source.toFile());
			if (!(raw instanceof ObjectNode object) || !raw.path("schemaVersion").isIntegralNumber() ||
				raw.path("schemaVersion").intValue() != 1)
				throw new IllegalArgumentException("Unsupported harness workspace binding schema");
			String mode = text(raw.path("mode"));
			return switch (mode)
			{
				case "PRIVATE_GENERATION" ->
				{
					if (!new HashSet<>(object.propertyNames()).equals(PRIVATE_FIELDS))
						throw new IllegalArgumentException("Invalid private harness workspace binding fields");
					PrivateGenerationContext context = new PrivateGenerationContext(
						Path.of(text(raw.path("selectedProject"))), Path.of(text(raw.path("privateProject"))),
						ref(raw.path("generationRequestRef")), ref(raw.path("readinessRef")),
						ref(raw.path("confirmedRootsRef")));
					yield new HarnessRunWorkspace(text(raw.path("runId")), context,
						Path.of(text(raw.path("executionProject"))));
				}
				case "ACTIVE_WORKTREE", "ISOLATED_COPY" ->
				{
					if (!new HashSet<>(object.propertyNames()).equals(FIELDS))
						throw new IllegalArgumentException("Invalid project harness workspace binding fields");
					ProjectRunContext context = new ProjectRunContext(
						new ProjectExecutionSnapshot(Path.of(text(raw.path("sourceProject"))),
							ref(raw.path("snapshotManifestRef"))),
						ProjectWorkspaceMode.valueOf(mode),
						optionalRef(raw.path("confirmedEditableRootsRef")), optionalRef(raw.path("liveCheckpointRef")));
					yield new HarnessRunWorkspace(text(raw.path("runId")), context,
						Path.of(text(raw.path("executionProject"))));
				}
				default -> throw new IllegalArgumentException("Unsupported harness workspace mode: " + mode);
			};
		}
		catch (RuntimeException e)
		{
			throw new IOException("Malformed harness workspace binding: " + source, e);
		}
	}

	/**
	 * Converts a binding to its exact versioned JSON shape.
	 *
	 * @return the binding JSON
	 */
	private ObjectNode toJson()
	{
		ObjectNode value = JSON.createObjectNode();
		value.put("schemaVersion", 1);
		value.put("runId", runId);
		value.put("executionProject", executionProject.toString());
		if (context instanceof PrivateGenerationContext generation)
		{
			value.put("mode", "PRIVATE_GENERATION");
			value.put("selectedProject", generation.selectedProject().toString());
			value.put("privateProject", generation.privateProject().toString());
			value.set("generationRequestRef", jsonRef(generation.generationRequestRef()));
			value.set("readinessRef", jsonRef(generation.readinessRef()));
			value.set("confirmedRootsRef", jsonRef(generation.confirmedRootsRef()));
			return value;
		}
		ProjectRunContext project = (ProjectRunContext) context;
		value.put("mode", project.mode().name());
		value.put("sourceProject", project.snapshot().projectRoot().toString());
		value.set("snapshotManifestRef", jsonRef(project.snapshot().snapshotManifestRef()));
		if (project.confirmedEditableRootsRef().isPresent())
			value.set("confirmedEditableRootsRef", jsonRef(project.confirmedEditableRootsRef().orElseThrow()));
		else
			value.putNull("confirmedEditableRootsRef");
		if (project.liveCheckpointRef().isPresent())
			value.set("liveCheckpointRef", jsonRef(project.liveCheckpointRef().orElseThrow()));
		else
			value.putNull("liveCheckpointRef");
		return value;
	}

	/**
	 * Converts one immutable evidence reference to JSON.
	 *
	 * @param ref the evidence reference
	 * @return the reference object
	 */
	private static ObjectNode jsonRef(EvidenceRef ref)
	{
		ObjectNode value = JSON.createObjectNode();
		value.put("collection", ref.collection());
		value.put("evidenceId", ref.evidenceId());
		return value;
	}

	/**
	 * Parses one required reference with no extension fields.
	 *
	 * @param raw the reference object
	 * @return the reference
	 */
	private static EvidenceRef ref(JsonNode raw)
	{
		if (!(raw instanceof ObjectNode object) ||
			!new HashSet<>(object.propertyNames()).equals(Set.of("collection", "evidenceId")))
			throw new IllegalArgumentException("Invalid harness workspace evidence reference");
		return new EvidenceRef(text(raw.path("collection")), text(raw.path("evidenceId")));
	}

	/**
	 * Parses an absent or required evidence reference.
	 *
	 * @param raw the optional reference object
	 * @return the optional reference
	 */
	private static Optional<EvidenceRef> optionalRef(JsonNode raw)
	{
		if (raw.isNull())
			return Optional.empty();
		return Optional.of(ref(raw));
	}

	/**
	 * Requires JSON text.
	 *
	 * @param raw the JSON value
	 * @return its text
	 */
	private static String text(JsonNode raw)
	{
		if (!raw.isString())
			throw new IllegalArgumentException("Expected harness workspace binding text");
		return raw.asString();
	}
}
