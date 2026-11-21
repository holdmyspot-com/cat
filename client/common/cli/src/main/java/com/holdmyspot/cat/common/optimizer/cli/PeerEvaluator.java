package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads evaluator metadata from a selected installed artifact in the caller's process sandbox.
 */
final class PeerEvaluator
{
	private static final long TIMEOUT_SECONDS = 60;
	private static final int MAXIMUM_INSTALLATION_BYTES = 1_048_576;

	/**
	 * Runs the selected artifact's fixed metadata command under the current process's sandbox.
	 *
	 * @param json       the report mapper
	 * @param executable the caller's executable process starter
	 * @param artifact   the installed peer artifact selected by the calling harness
	 * @param evaluator  the requested peer
	 * @param project    the absolute project directory, which becomes the child's working directory
	 * @param staged     the absolute existing staged source
	 * @param target     the target path relative to the project and staged source
	 * @return the peer's report
	 * @throws IOException          if the process cannot start, does not report by its timeout, or fails
	 * @throws InterruptedException if the caller is interrupted while waiting
	 */
	static EvaluatorMetadata read(JsonMapper json, ExecutableFiles executable, Path artifact, Harness evaluator,
		Path project, Path staged, Path target) throws IOException, InterruptedException
	{
		return inspect(json, executable, artifact, evaluator, project,
			List.of(EvaluatorInfo.COMMAND, project.toString(), staged.toString(), target.toString()));
	}

	/**
	 * Inspects only the selected plugin bases in the peer's own loader.
	 *
	 * @param json the protocol mapper
	 * @param executable the caller's executable process starter
	 * @param artifact the installed peer artifact
	 * @param evaluator the requested peer
	 * @param project the original project
	 * @param staged the staged source
	 * @param target the relative target
	 * @param plugins the selected plugin bases in trial order
	 * @param temporary the caller's temporary directory
	 * @return the peer's metadata report
	 * @throws IOException if the input cannot be written or the peer does not produce a report
	 * @throws InterruptedException if the caller is interrupted
	 */
	static EvaluatorMetadata read(JsonMapper json, ExecutableFiles executable, Path artifact, Harness evaluator,
		Path project, Path staged, Path target, List<PluginBase> plugins, Path temporary)
		throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path exchange = cleanup.add(Files.createTempDirectory(temporary, "evaluator-input-"));
			Path input = exchange.resolve("plugins.json");
			EvaluatorPluginBases.write(json, input, plugins);
			return inspect(json, executable, artifact, evaluator, project,
				List.of(EvaluatorInfo.COMMAND, project.toString(), staged.toString(), target.toString(), input.toString()));
		}
	}

	/**
	 * Prepares read-only startup-hook descriptors in the installed evaluator's metadata process.
	 *
	 * @param scope the campaign owner's scope
	 * @param evaluator the installed peer
	 * @param project the original project directory
	 * @param staged the already staged project shape used for the comparison
	 * @param plugins the digest-checked plugin bases in trial order
	 * @param trialHome the prepared fresh process home, or empty for the evaluator's native home
	 * @return the descriptors in declaration order
	 * @throws IOException if peer inspection fails or returns an invalid report
	 * @throws InterruptedException if the caller is interrupted
	 */
	static List<HookInvocation> earlyHooks(AbstractOptimizerCommandScope scope, Harness evaluator, Path project,
		Path staged, List<PluginBase> plugins, Optional<Path> trialHome) throws IOException, InterruptedException
	{
		JsonMapper json = scope.json();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path exchange = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "evaluator-input-"));
			Path input = exchange.resolve("plugins.json");
			EvaluatorPluginBases.write(json, input, plugins);
			List<String> arguments = new ArrayList<>(List.of(EvaluatorInfo.COMMAND, EvaluatorInfo.EARLY_HOOKS,
				project.toString(), staged.toString(), input.toString()));
			trialHome.ifPresent(home -> arguments.add(home.toString()));
			return launch(scope.executableFiles(), scope.peerEvaluatorArtifact(evaluator), evaluator, project,
				arguments, response -> EvaluatorHookInvocations.read(json, response, evaluator));
		}
	}

	/**
	 * Queries the peer evaluator's installation using a configured name.
	 *
	 * @param json the protocol mapper
	 * @param executable the caller's executable process starter
	 * @param artifact the installed peer artifact
	 * @param evaluator the requested peer
	 * @param project the original project
	 * @param name the configured plugin name
	 * @return the installed directories
	 * @throws IOException if the peer does not produce its installation report
	 * @throws InterruptedException if the caller is interrupted
	 */
	static List<Path> installed(JsonMapper json, ExecutableFiles executable, Path artifact, Harness evaluator,
		Path project, String name) throws IOException, InterruptedException
	{
		return launch(executable, artifact, evaluator, project,
			List.of(EvaluatorInfo.COMMAND, EvaluatorInfo.INSTALLED, project.toString(), name),
			input -> readInstallation(json, evaluator, input));
	}

	/**
	 * Runs the fixed metadata command and validates its evaluator identity.
	 *
	 * @param json the report mapper
	 * @param executable the caller's executable process starter
	 * @param artifact the installed peer artifact
	 * @param evaluator the requested peer
	 * @param project the working directory
	 * @param arguments the fixed metadata command arguments
	 * @return the peer's metadata report
	 * @throws IOException if the peer does not supply a valid report
	 * @throws InterruptedException if the caller is interrupted
	 */
	private static EvaluatorMetadata inspect(JsonMapper json, ExecutableFiles executable, Path artifact,
		Harness evaluator, Path project, List<String> arguments) throws IOException, InterruptedException
	{
		EvaluatorMetadata metadata = launch(executable, artifact, evaluator, project, arguments,
			input -> EvaluatorMetadata.read(json, input));
		requireEvaluator(evaluator, metadata.harness());
		return metadata;
	}

	/**
	 * Reads the native paths and verifies the response's evaluator.
	 *
	 * @param json the report mapper
	 * @param evaluator the requested peer
	 * @param input the process report
	 * @return the installed directories
	 * @throws IOException if the report is invalid
	 */
	private static List<Path> readInstallation(JsonMapper json, Harness evaluator, InputStream input)
		throws IOException
	{
		String report = BoundedInput.readUtf8(input, evaluator.productName(), MAXIMUM_INSTALLATION_BYTES,
			"Evaluator installation report");
		JsonNode result = json.readTree(report);
		requireEvaluator(evaluator, Harness.valueOf(EvaluatorPluginBases.text(result, "harness")));
		JsonNode installed = result.path("installed");
		if (!installed.isArray())
			throw new IOException("Expected evaluator installation paths");
		List<Path> paths = new ArrayList<>();
		for (JsonNode node : installed)
		{
			if (!node.isString())
				throw new IOException("Expected evaluator installation path text");
			Path path = Path.of(node.stringValue());
			if (!path.isAbsolute())
				throw new IOException("Expected an absolute evaluator installation path");
			paths.add(path.normalize());
		}
		return List.copyOf(paths);
	}

	/**
	 * Requires the evaluator identity requested by the owner.
	 *
	 * @param evaluator the requested peer
	 * @param reported the peer identified in its report
	 * @throws IOException if the peer identifies a different evaluator
	 */
	private static void requireEvaluator(Harness evaluator, Harness reported) throws IOException
	{
		if (reported != evaluator)
		{
			throw new IOException("Requested " + evaluator.productName() + " metadata, but the process reported " +
				"evaluator " + reported.productName() + ".");
		}
	}

	/**
	 * Runs the fixed installed launcher under the current caller's sandbox.
	 *
	 * @param <T> the report type
	 * @param executable the caller's executable process starter
	 * @param artifact the installed peer artifact
	 * @param evaluator the requested peer
	 * @param project the working directory
	 * @param arguments the metadata command arguments
	 * @param reader reads the report
	 * @return the peer report
	 * @throws IOException if the peer does not supply a valid report
	 * @throws InterruptedException if the caller is interrupted
	 */
	private static <T> T launch(ExecutableFiles executable, Path artifact, Harness evaluator, Path project,
		List<String> arguments, Reader<T> reader) throws IOException, InterruptedException
	{
		String name = "prompt-optimizer";
		if (Platform.current().isWindows())
			name += ".cmd";
		Path launcher = artifact.resolve(PluginDirectory.BIN).resolve(name);
		List<String> command = new ArrayList<>();
		command.add(launcher.toString());
		command.addAll(arguments);
		ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()).
			redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.environment().remove(SessionRequestAccess.REQUESTS_VARIABLE);
		builder.environment().remove(SessionRequestAccess.LAUNCHER_VARIABLE);
		builder.environment().remove(SessionRequestAccess.SHELL_VARIABLE);

		try (Process process = executable.run(builder))
		{
			return await(evaluator, process, reader);
		}
	}

	/**
	 * Reads the process report and its exit status within one deadline.
	 *
	 * @param <T> the report type
	 * @param evaluator the requested peer
	 * @param process the process writing the report
	 * @param reader reads the report
	 * @return the peer's report
	 * @throws IOException          if the process fails to supply a report before the deadline
	 * @throws InterruptedException if the caller is interrupted while waiting
	 */
	private static <T> T await(Harness evaluator, Process process, Reader<T> reader)
		throws IOException, InterruptedException
	{
		FutureTask<T> response = new FutureTask<>(() ->
		{
			T metadata = reader.read(process.getInputStream());
			if (process.waitFor() != 0)
				throw new IOException("The metadata process returned a failure exit status");
			return metadata;
		});
		try
		{
			process.getOutputStream().close();
			Thread.startVirtualThread(response);
			return response.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (ExecutionException e)
		{
			throw new IOException(evaluator.productName() + " did not return evaluator metadata.", e.getCause());
		}
		catch (TimeoutException e)
		{
			throw new IOException(evaluator.productName() + " did not finish the evaluator metadata command in " +
				TIMEOUT_SECONDS + " seconds.", e);
		}
		finally
		{
			if (process.isAlive())
				HarnessProcessTermination.terminateImmediately(process);
			response.cancel(true);
		}
	}

	/**
	 * Decodes a bounded response from the installed evaluator.
	 *
	 * @param <T> the report type
	 */
	@FunctionalInterface
	private interface Reader<T>
	{
		/**
		 * Reads the report.
		 *
		 * @param input the process output
		 * @return the report
		 * @throws IOException if the report cannot be read
		 */
		T read(InputStream input) throws IOException;
	}

	/**
	 * Prevents construction.
	 */
	private PeerEvaluator()
	{
	}
}
