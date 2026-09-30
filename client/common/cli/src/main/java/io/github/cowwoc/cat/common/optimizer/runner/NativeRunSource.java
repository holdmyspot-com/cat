package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Selects a native execution source and owns only temporary sterile evaluator inputs. */
public final class NativeRunSource implements AutoCloseable
{
	private final Path project;
	private final boolean catInstallationRequired;
	private final TemporaryDirectories cleanup;

	/**
	 * Selects caller-owned trial or generation input, or materializes a temporary sterile evaluator source.
	 *
	 * @param request original native call
	 * @param store immutable input repository
	 * @param tempRoot absolute owned root for temporary evaluator sources
	 * @return source retained until the caller finishes synchronous native copying
	 * @throws NullPointerException if a required argument is null
	 * @throws IllegalArgumentException if the temporary root is not absolute
	 * @throws IOException if the context is unsupported or retained inputs cannot be materialized
	 */
	public static NativeRunSource prepare(NestedRunRequest request, CampaignStore store, Path tempRoot)
		throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(tempRoot, "tempRoot");
		if (!tempRoot.isAbsolute())
			throw new IllegalArgumentException("tempRoot must be absolute");

		if (request.context() instanceof PrivateGenerationContext generation)
			return new NativeRunSource(generation.privateProject(), true, new TemporaryDirectories());
		if (request.context() instanceof ProjectRunContext trial)
		{
			if (trial.mode() != ProjectWorkspaceMode.ISOLATED_COPY)
				throw new IOException("Native execution source requires an isolated project copy");
			return new NativeRunSource(trial.snapshot().projectRoot(), true, new TemporaryDirectories());
		}
		if (!(request.context() instanceof SterileWorkspaceContext sterile) ||
			sterile.kind() != SterileWorkspaceKind.EVALUATOR)
			throw new IOException("Native execution source requires an isolated trial or sterile evaluator");

		Files.createDirectories(tempRoot);
		if (!Files.isDirectory(tempRoot, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Sterile temporary root is not a real directory: " + tempRoot);
		TemporaryDirectories cleanup = new TemporaryDirectories();
		try
		{
			Path container = cleanup.add(Files.createTempDirectory(tempRoot, "evaluator-source-"));
			Path source = container.resolve("project");
			SterileWorkspaceMaterializer.materialize(sterile, store::readImmutableEvidence, source);
			return new NativeRunSource(source, false, cleanup);
		}
		catch (IOException | RuntimeException failure)
		{
			try
			{
				cleanup.close();
			}
			catch (IOException cleanupFailure)
			{
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * Attaches derived source facts to their temporary resource owner.
	 *
	 * @param project selected source directory
	 * @param catInstallationRequired derived installation requirement
	 * @param cleanup owner of temporary materialized sources only
	 */
	private NativeRunSource(Path project, boolean catInstallationRequired, TemporaryDirectories cleanup)
	{
		this.project = Objects.requireNonNull(project, "project");
		this.catInstallationRequired = catInstallationRequired;
		this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
	}

	/**
	 * Returns the source selected for native execution.
	 *
	 * @return caller-owned source or temporary sterile input directory
	 */
	public Path project()
	{
		return project;
	}

	/**
	 * Reports whether this execution context requires CAT plugin installation.
	 *
	 * @return true for project trials and private generation, false for sterile evaluation
	 */
	public boolean requiresCatInstallation()
	{
		return catInstallationRequired;
	}

	/**
	 * Deletes owned temporary evaluator inputs after native copying, retaining caller-owned sources.
	 *
	 * @throws IOException if temporary cleanup cannot finish
	 */
	@Override
	public void close() throws IOException
	{
		cleanup.close();
	}
}
