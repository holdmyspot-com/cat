package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Observes one installed evaluator's metadata against an existing staged source.
 */
final class EvaluatorInfo
{
	static final String COMMAND = "evaluator-info";
	static final String INSTALLED = "installed-plugins";
	static final String EARLY_HOOKS = "early-hooks";
	private static final String USAGE = COMMAND +
		" <absolute-project> <absolute-staged-source> <relative-target> [<absolute-plugin-bases-file>] or " +
		COMMAND + " " + INSTALLED + " <absolute-project> <plugin-name> or " + COMMAND + " " + EARLY_HOOKS +
		" <absolute-project> <absolute-staged-source> <absolute-plugin-bases-file> [<absolute-trial-home>]";
	private static final int MAXIMUM_PATH_LENGTH = 16_384;
	private static final int MAXIMUM_BINDING_BYTES = 1_048_576;
	private static final int MAXIMUM_BINDINGS = 4_096;

	/**
	 * Runs metadata inspection with the supplied harness, before campaign and launch validation.
	 *
	 * @param scope the evaluator's scope
	 * @param args  the command and its inspection arguments
	 * @param out   receives the report or the command error
	 * @return 0 for a report, 1 for an observation failure, or 2 for invalid arguments
	 */
	static int run(AbstractOptimizerCommandScope scope, List<String> args, PrintStream out)
	{
		if (args.size() < 4 || args.size() > 6 || args.subList(1, args.size()).stream().
			anyMatch(value -> value.isBlank() || value.length() > MAXIMUM_PATH_LENGTH))
		{
			return invalid(scope, out);
		}
		if (args.get(1).equals(EARLY_HOOKS))
			return earlyHooks(scope, args, out);
		if (args.size() == 4 && args.get(1).equals(INSTALLED))
			return installed(scope, args.get(2), args.get(3), out);
		if (args.size() > 5)
			return invalid(scope, out);
		Path project;
		Path staged;
		Path target;
		try
		{
			project = Path.of(args.get(1)).normalize();
			staged = Path.of(args.get(2)).normalize();
			target = Path.of(args.get(3)).normalize();
		}
		catch (InvalidPathException _)
		{
			return invalid(scope, out);
		}
		if (!project.isAbsolute() || !staged.isAbsolute() || PortablePaths.isRooted(target) ||
			target.toString().isEmpty() || target.startsWith(".."))
		{
			return invalid(scope, out);
		}
		Harness harness = scope.harness();
		if (!Files.isDirectory(project) || !Files.isDirectory(staged))
		{
			return PromptOptimizerCli.fail(scope.json(), out, harness.productName() +
				" requires an existing project and staged source.");
		}
		try
		{
			List<PluginBase> bases = List.of();
			if (args.size() == 5)
				bases = EvaluatorPluginBases.read(scope.json(), Path.of(args.get(4)));
			inspect(scope, project, staged, target, bases).write(scope.json(), out);
			return 0;
		}
		catch (IOException | UncheckedIOException | JacksonException | IllegalArgumentException _)
		{
			// A parser can quote the user's configuration or command output in its exception.
			return PromptOptimizerCli.fail(scope.json(), out, harness.productName() +
				" could not read the evaluator's profiles or prompt sources.");
		}
	}

	/**
	 * Describes startup hooks for one staged project using the native adapter.
	 *
	 * @param scope the installed evaluator
	 * @param args identifies the staged project, plugin input and optional trial home
	 * @param out receives the descriptor report
	 * @return the command exit status
	 */
	private static int earlyHooks(AbstractOptimizerCommandScope scope, List<String> args, PrintStream out)
	{
		if (args.size() < 5)
			return invalid(scope, out);
		try
		{
			Path project = Path.of(args.get(2)).normalize();
			Path staged = Path.of(args.get(3)).normalize();
			Optional<Path> trialHome = Optional.empty();
			if (args.size() == 6)
				trialHome = Optional.of(Path.of(args.get(5)).normalize());
			if (!existingDirectory(project) || !existingDirectory(staged) ||
				trialHome.filter(path -> !existingDirectory(path)).isPresent())
			{
				return invalid(scope, out);
			}
			List<PluginBase> bases = EvaluatorPluginBases.read(scope.json(), Path.of(args.get(4)));
			EvaluatorPluginBases.verify(bases);
			SessionPort inspection = scope.evaluatorInspection(project, project.resolve("results"));
			List<HookInvocation> hooks = inspection.earlyHooks(staged, bases, trialHome, HookCheck.PROMPT);
			if (trialHome.isEmpty() && scope instanceof MainOptimizerCommandScope nativeScope)
				hooks = withNativeHome(nativeScope, hooks);
			EvaluatorHookInvocations.write(scope.json(), out, scope.harness(), hooks);
			return 0;
		}
		catch (InvalidPathException _)
		{
			return invalid(scope, out);
		}
		catch (IOException | UncheckedIOException | JacksonException | IllegalArgumentException _)
		{
			return PromptOptimizerCli.fail(scope.json(), out, scope.harness().productName() +
				" could not inspect its startup hooks.");
		}
	}

	/**
	 * Checks an absolute directory that the caller prepared for inspection.
	 *
	 * @param path the directory
	 * @return whether the directory exists
	 */
	private static boolean existingDirectory(Path path)
	{
		return path.isAbsolute() && Files.isDirectory(path);
	}

	/**
	 * Supplies the peer's native resolved home when describing the production shape.
	 *
	 * @param scope the installed evaluator
	 * @param hooks its adapter-defined descriptors
	 * @return descriptors with the native process and harness home overlaid
	 */
	private static List<HookInvocation> withNativeHome(MainOptimizerCommandScope scope, List<HookInvocation> hooks)
	{
		Map<String, String> nativeHome = new TreeMap<>();
		HarnessEnvironment.setHomeDirectory(nativeHome, scope.home());
		String key = switch (scope.harness())
		{
			case CODEX -> "CODEX_HOME";
			case CLAUDE -> "CLAUDE_CONFIG_DIR";
		};
		nativeHome.put(key, scope.evaluatorConfigurationDirectory().toString());
		return hooks.stream().map(hook ->
		{
			Map<String, String> overlay = new TreeMap<>(nativeHome);
			overlay.putAll(hook.environment());
			return new HookInvocation(hook.name(), hook.command(), overlay, hook.input(), hook.timeout());
		}).toList();
	}

	/**
	 * Looks up a configured name in the native evaluator's installation.
	 *
	 * @param scope the evaluator's scope
	 * @param projectArgument the path of the original project
	 * @param name the configured plugin name
	 * @param out receives the installation report
	 * @return the command exit status
	 */
	private static int installed(AbstractOptimizerCommandScope scope, String projectArgument, String name,
		PrintStream out)
	{
		Path project;
		try
		{
			project = Path.of(projectArgument).normalize();
		}
		catch (InvalidPathException _)
		{
			return invalid(scope, out);
		}
		if (!project.isAbsolute() || !Files.isDirectory(project))
			return invalid(scope, out);
		Harness harness = scope.harness();
		try
		{
			List<Path> installed = scope.evaluatorInspection(project, project.resolve("results")).installedPlugins(name);
			ObjectNode report = scope.json().createObjectNode();
			report.put("harness", harness.name());
			ArrayNode directories = report.putArray("installed");
			for (Path path : installed)
				directories.add(path.toString());
			out.println(scope.json().writeValueAsString(report));
			return 0;
		}
		catch (IOException | UncheckedIOException | JacksonException | IllegalArgumentException _)
		{
			return PromptOptimizerCli.fail(scope.json(), out, harness.productName() +
				" could not inspect its installed plugins.");
		}
	}

	/**
	 * Observes the profile and loader inputs of a staged project without requesting a session.
	 *
	 * @param scope the evaluator's scope
	 * @param project the original project
	 * @param staged the staged project used for source discovery
	 * @param target the target relative to the project and staged source
	 * @return the evaluator's report, including any target loading refusal
	 * @throws IOException if the evaluator's configuration cannot be read
	 */
	static EvaluatorMetadata inspect(AbstractOptimizerCommandScope scope, Path project, Path staged, Path target)
		throws IOException
	{
		return inspect(scope, project, staged, target, List.of());
	}

	/**
	 * Observes the native loader with the selected ordered trial plugin bases.
	 *
	 * @param scope the evaluator's scope
	 * @param project the original project
	 * @param staged the staged project used for source discovery
	 * @param target the target relative to the project and staged source
	 * @param bases the selected plugin bases in trial order
	 * @return the evaluator's report, including any target loading refusal
	 * @throws IOException if a base has changed or the evaluator's configuration cannot be read
	 */
	static EvaluatorMetadata inspect(AbstractOptimizerCommandScope scope, Path project, Path staged, Path target,
		List<PluginBase> bases) throws IOException
	{
		EvaluatorPluginBases.verify(bases);
		Harness harness = scope.harness();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "evaluator-"));
			SessionPort inspection = scope.evaluatorInspection(project, temporary.resolve("results"));
			LoadedSources loaded = inspection.loadedSources(staged, bases);
			LoaderIdentity loader = LoaderIdentity.read(scope.loaderConfiguration(),
				path -> InventoryScanner.entry(staged, path), scope.userLoaderSettings());
			ProfileInventory inventory = scope.profiles();
			ProfileEvidence evidence = profiles(inventory, staged);
			Optional<String> refusal = loadRefusal(harness, target, project, staged, loaded.trials());
			EvaluatorRuntime runtime = EvaluatorRuntime.read(scope.harnessVersion(), inspection);
			EvaluatorObservations observations = EvaluatorObservations.inspect(inspection, staged, bases, loaded);
			return new EvaluatorMetadata(harness, evidence, inventory.roleProfile(), loader.files(), loader.digest(),
				loaded.trials(), runtime, refusal, Optional.of(observations));
		}
	}

	/**
	 * Refuses malformed arguments without printing their values.
	 *
	 * @param scope the evaluator's scope
	 * @param out   the command output
	 * @return the invalid-input exit code
	 */
	private static int invalid(AbstractOptimizerCommandScope scope, PrintStream out)
	{
		out.println(Commands.statusMessage(scope.json(), "invalid_input", "Expected " + USAGE));
		return 2;
	}

	/**
	 * Reads a bounded set of model and effort bindings using the evaluator's own prompt file recognition.
	 *
	 * @param inventory the evaluator's profile inventory
	 * @param staged    the staged source containing the prompt binding files
	 * @return the model inventory, ranking and bindings in that source
	 * @throws IOException if the binding files or inventory cannot be read within the limits of the report
	 */
	private static ProfileEvidence profiles(ProfileInventory inventory, Path staged) throws IOException
	{
		Map<String, TrialProfile> bindings = new TreeMap<>();
		try (Stream<Path> files = Files.walk(staged))
		{
			Iterator<Path> paths = files.filter(Files::isRegularFile).map(staged::relativize).
				filter(inventory::canBind).iterator();
			int examined = 0;
			while (paths.hasNext())
			{
				if (examined == MAXIMUM_BINDINGS)
					throw new IOException("The staged source has too many prompt binding files");
				++examined;
				Path file = paths.next();
				String content = BoundedInput.readUtf8(staged.resolve(file), MAXIMUM_BINDING_BYTES, "Prompt binding file");
				TrialProfile binding = inventory.binding(content);
				if (binding.isBound())
					bindings.put(PortablePaths.text(file), binding);
			}
		}
		return new ProfileEvidence(inventory.inventory(), inventory.ranking(), bindings);
	}

	/**
	 * Identifies a target the evaluator's inspected trial sources do not load.
	 *
	 * @param harness the evaluator's installed harness
	 * @param target  the target path relative to the project and the staged source
	 * @param project the original project
	 * @param staged  the staged project on which trials run
	 * @param sources the paths that the evaluator loads in trials
	 * @return a named refusal, or empty if the target is in both projects and loads in trials
	 */
	private static Optional<String> loadRefusal(Harness harness, Path target, Path project, Path staged,
		List<DerivedSource> sources)
	{
		String prefix = harness.productName() + " cannot load target " + PortablePaths.text(target);
		if (!Files.isRegularFile(project.resolve(target)))
			return Optional.of(prefix + " because it is missing from the project.");
		if (!Files.isRegularFile(staged.resolve(target)))
			return Optional.of(prefix + " because it is missing from the staged source.");
		if (sources.stream().noneMatch(source -> Path.of(source.path()).normalize().equals(target)))
			return Optional.of(prefix + " because its trial loaders do not load that file.");
		return Optional.empty();
	}

	/**
	 * Prevents construction.
	 */
	private EvaluatorInfo()
	{
	}
}
