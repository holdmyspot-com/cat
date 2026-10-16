package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.distribution.bundle.ArtifactLayer;
import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;
import io.github.cowwoc.cat.distribution.bundle.ModuleDescriptorPatcher;
import io.github.cowwoc.cat.distribution.bundle.PluginArtifactBuilder;
import io.github.cowwoc.cat.distribution.bundle.ReleasePackager;
import io.github.cowwoc.cat.distribution.bundle.RuleArtifactRouter;
import io.github.cowwoc.cat.distribution.license.LegalMaterialsBuilder;
import io.github.cowwoc.cat.distribution.license.ThirdPartyComponent;
import io.github.cowwoc.cat.distribution.secrets.DistributionMainAccess;
import io.github.cowwoc.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Builds the platform-specific Claude and Codex CAT release archives from Maven module outputs.
 */
public final class DistributionMain
{
	static
	{
		DistributionMainSecrets.setAccess(new DistributionMainAccess()
		{
			@Override
			public List<JlinkImageBuilder.Launcher> claudeLaunchers()
			{
				return DistributionMain.claudeLaunchers();
			}

			@Override
			public List<JlinkImageBuilder.Launcher> codexLaunchers()
			{
				return DistributionMain.codexLaunchers();
			}

			@Override
			public ArtifactLayer codexPluginLayer(Path jar)
			{
				return DistributionMain.codexPluginLayer(jar);
			}

			@Override
			public Path codexImage(JlinkImageBuilder builder, List<Path> modules, Path output, Platform platform)
				throws IOException
			{
				return DistributionMain.codexImage(builder, modules, output, platform);
			}

			@Override
			public String dependencyCoordinates(String groupId, String artifactId, Path path)
			{
				return new DependencyJar(groupId, artifactId, path).coordinates();
			}
		});
	}

	/**
	 * Builds distributions for the current platform.
	 *
	 * @param args the project root, module directory, and release directory
	 * @throws NullPointerException if {@code args} is null
	 * @throws IllegalArgumentException if {@code args} does not contain exactly three values
	 * @throws IOException if distribution assembly fails
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length != 3)
		{
			throw new IllegalArgumentException(
				"Usage: DistributionMain <project-root> <module-directory> <release-directory>");
		}
		build(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Platform.current());
	}

	/**
	 * Builds the harness distributions for a platform.
	 *
	 * @param projectRoot the repository root
	 * @param moduleDirectory the directory containing dependency JARs
	 * @param releaseDirectory the output directory for release archives
	 * @param platform the target platform
	 * @throws NullPointerException if {@code projectRoot}, {@code moduleDirectory}, {@code releaseDirectory},
	 *         or {@code platform} are null
	 * @throws IllegalArgumentException if {@code releaseDirectory} is the filesystem root
	 * @throws IOException if distribution assembly fails
	 */
	public static void build(Path projectRoot, Path moduleDirectory, Path releaseDirectory, Platform platform)
		throws IOException
	{
		requireThat(projectRoot, "projectRoot").isNotNull();
		requireThat(moduleDirectory, "moduleDirectory").isNotNull();
		requireThat(releaseDirectory, "releaseDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		projectRoot = projectRoot.toAbsolutePath().normalize();
		moduleDirectory = moduleDirectory.toAbsolutePath().normalize();
		releaseDirectory = releaseDirectory.toAbsolutePath().normalize();
		requireThat(moduleDirectory, "moduleDirectory").isDirectory();
		requireThat(releaseDirectory, "releaseDirectory").isNotEqualTo(releaseDirectory.getRoot(), "filesystemRoot");

		Path target = releaseDirectory.getParent();
		Path runtimeDirectory = target.resolve("runtime");
		Path jlinkDirectory = target.resolve("jlink");
		Path jlinkModules = target.resolve("jlink-modules");
		deleteAndRecreateDirectory(releaseDirectory);
		deleteAndRecreateDirectory(runtimeDirectory);
		deleteAndRecreateDirectory(jlinkDirectory);
		deleteAndRecreateDirectory(jlinkModules);

		Path commonCli = findDependencyJar(moduleDirectory, "client-common-cli");
		Path claudeCli = findDependencyJar(moduleDirectory, "client-claude-cli");
		Path codexCli = findDependencyJar(moduleDirectory, "client-codex-cli");
		DependencyJar requirements = findThirdParty(moduleDirectory, "io.github.cowwoc.requirements",
			"requirements-java");
		DependencyJar requirementsAnnotation = findThirdParty(moduleDirectory, "io.github.cowwoc.requirements",
			"requirements-annotation");
		DependencyJar pouch = findThirdParty(moduleDirectory, "io.github.cowwoc.pouch", "pouch-core");
		DependencyJar slf4j = findThirdParty(moduleDirectory, "org.slf4j", "slf4j-api");
		DependencyJar slf4jNop = findThirdParty(moduleDirectory, "org.slf4j", "slf4j-nop");
		DependencyJar javaDiff = findThirdParty(moduleDirectory, "io.github.java-diff-utils", "java-diff-utils");
		Path commonAgents = findDependencyJar(moduleDirectory, "client-common-agents");
		Path commonPlugin = findDependencyJar(moduleDirectory, "client-common-plugin");
		Path commonSkills = findDependencyJar(moduleDirectory, "client-common-skills");
		Path commonRules = findDependencyJar(moduleDirectory, "client-common-rules");
		Path claudePlugin = findDependencyJar(moduleDirectory, "client-claude-plugin");
		Path claudeRules = findDependencyJar(moduleDirectory, "client-claude-rules");
		Path claudeSkills = findDependencyJar(moduleDirectory, "client-claude-skills");
		Path codexSkills = findDependencyJar(moduleDirectory, "client-codex-skills");
		Path codexPlugin = findDependencyJar(moduleDirectory, "client-codex-plugin");
		Path codexRules = findDependencyJar(moduleDirectory, "client-codex-rules");
		DependencyJar jacksonCore = findThirdParty(moduleDirectory, "tools.jackson.core", "jackson-core");
		DependencyJar jacksonAnnotations = findThirdParty(moduleDirectory, "com.fasterxml.jackson.core",
			"jackson-annotations");
		DependencyJar jacksonDatabind = findThirdParty(moduleDirectory, "tools.jackson.core", "jackson-databind");
		DependencyJar jacksonYaml = findThirdParty(moduleDirectory, "tools.jackson.dataformat",
			"jackson-dataformat-yaml");
		DependencyJar snakeyaml = findThirdParty(moduleDirectory, "org.snakeyaml", "snakeyaml-engine");

		List<Path> sharedRuntimeModules = List.of(copyModule(commonCli, jlinkModules),
			copyModule(requirements.path(), jlinkModules), copyModule(requirementsAnnotation.path(), jlinkModules),
			copyModule(pouch.path(), jlinkModules), copyModule(slf4j.path(), jlinkModules),
			copyModule(slf4jNop.path(), jlinkModules),
			modularizeJavaDiff(javaDiff.path(), jlinkModules));
		Path packagedClaudeCli = copyModule(claudeCli, jlinkModules);
		Path packagedCodexCli = copyModule(codexCli, jlinkModules);
		List<Path> jsonRuntimeModules = List.of(copyModule(jacksonCore.path(), jlinkModules),
			copyModule(jacksonAnnotations.path(), jlinkModules), copyModule(jacksonDatabind.path(), jlinkModules),
			copyModule(jacksonYaml.path(), jlinkModules), copyModule(snakeyaml.path(), jlinkModules));

		JlinkImageBuilder imageBuilder = new JlinkImageBuilder();
		Path claudeImage = imageBuilder.build(append(append(sharedRuntimeModules, packagedClaudeCli), jsonRuntimeModules),
			Set.of("io.github.cowwoc.cat.claude.cli", "org.slf4j.nop"),
			claudeLaunchers(), jlinkDirectory.resolve("claude"));
		List<Path> codexModules = append(append(sharedRuntimeModules, packagedCodexCli), jsonRuntimeModules);
		Path codexImage = codexImage(imageBuilder, codexModules, jlinkDirectory.resolve("codex"), platform);

		PluginArtifactBuilder artifactBuilder = new PluginArtifactBuilder(PluginInfo.CAT_VERSION);
		LegalMaterialsBuilder legalMaterialsBuilder = new LegalMaterialsBuilder(PluginInfo.CAT_VERSION);
		Path catLicense = projectRoot.resolve("LICENSE.md");
		Path canonicalLicenses = projectRoot.resolve("client/legal/licenses");
		Path commonInstallers = projectRoot.resolve("client/common/lifecycle/src/main/resources/install");
		Path claudeInstallers = projectRoot.resolve("client/claude/lifecycle/src/main/resources/install");
		Path codexInstallers = projectRoot.resolve("client/codex/lifecycle/src/main/resources/install");
		String jacksonCoreCoordinates = jacksonCore.coordinates();
		List<ThirdPartyComponent> legalComponents = List.of(
			new ThirdPartyComponent("java-diff-utils", javaDiff.coordinates(), "Apache-2.0.txt", javaDiff.path(),
				List.of()),
			new ThirdPartyComponent("Pouch", pouch.coordinates(), "Apache-2.0.txt", pouch.path(), List.of()),
			new ThirdPartyComponent("Requirements Annotation", requirementsAnnotation.coordinates(), "Apache-2.0.txt",
				requirementsAnnotation.path(), List.of()),
			new ThirdPartyComponent("Requirements Java", requirements.coordinates(), "Apache-2.0.txt",
				requirements.path(), List.of()),
			new ThirdPartyComponent("SLF4J API", slf4j.coordinates(), "MIT.txt", slf4j.path(),
				List.of("META-INF/LICENSE.txt")),
			new ThirdPartyComponent("SLF4J NOP Provider", slf4jNop.coordinates(), "MIT.txt", slf4jNop.path(),
				List.of("META-INF/LICENSE.txt")),
			new ThirdPartyComponent("Jackson Annotations", jacksonAnnotations.coordinates(), "Apache-2.0.txt",
				jacksonAnnotations.path(), List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("Jackson Core", jacksonCoreCoordinates, "Apache-2.0.txt",
				jacksonCore.path(), List.of("META-INF/NOTICE", "META-INF/FastDoubleParser-LICENSE",
					"META-INF/FastDoubleParser-ThirdParty-LICENSE", "META-INF/Schubfach-LICENSE")),
			new ThirdPartyComponent("FastDoubleParser", "embedded in " + jacksonCoreCoordinates, "MIT.txt",
				jacksonCore.path(), List.of()),
			new ThirdPartyComponent("fast_double_parser", "embedded in " + jacksonCoreCoordinates,
				"Boost-1.0.txt", jacksonCore.path(), List.of()),
			new ThirdPartyComponent("fast_float", "embedded in " + jacksonCoreCoordinates, "MIT.txt",
				jacksonCore.path(), List.of()),
			new ThirdPartyComponent("bigint", "embedded in " + jacksonCoreCoordinates, "BSD-2-Clause.txt",
				jacksonCore.path(), List.of()),
			new ThirdPartyComponent("Schubfach", "embedded in " + jacksonCoreCoordinates, "MIT.txt",
				jacksonCore.path(), List.of()),
			new ThirdPartyComponent("Jackson Databind", jacksonDatabind.coordinates(), "Apache-2.0.txt",
				jacksonDatabind.path(), List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("Jackson Dataformat YAML", jacksonYaml.coordinates(), "Apache-2.0.txt",
				jacksonYaml.path(), List.of("META-INF/NOTICE")),
			new ThirdPartyComponent("SnakeYAML Engine", snakeyaml.coordinates(), "Apache-2.0.txt",
				snakeyaml.path(), List.of()));
		Path legalMaterials = legalMaterialsBuilder.build(catLicense, legalComponents, canonicalLicenses,
			runtimeDirectory.resolve("legal"));
		Path claudeRoot = artifactBuilder.build(List.of(new ArtifactLayer("common-agents", commonAgents),
			new ArtifactLayer("common-plugin", commonPlugin, Set.of(), Set.of("hooks/session-start.sh",
				"hooks/subagent-start.sh")),
			new ArtifactLayer("common-skills", commonSkills), new ArtifactLayer("claude-skills", claudeSkills),
			new ArtifactLayer("common-rules", commonRules),
			new ArtifactLayer("claude-plugin", claudePlugin, Set.of(), Set.of("hooks/session-end.sh")),
			new ArtifactLayer("claude-rules", claudeRules)),
			claudeImage, legalMaterials, List.of(commonInstallers, claudeInstallers),
			runtimeDirectory.resolve("claude"));
		Path codexRoot = artifactBuilder.build(List.of(new ArtifactLayer("common-agents", commonAgents),
			new ArtifactLayer("common-plugin", commonPlugin, Set.of(), Set.of("hooks/session-start.sh",
				"hooks/subagent-start.sh")),
			new ArtifactLayer("common-skills", commonSkills),
			new ArtifactLayer("common-rules", commonRules), new ArtifactLayer("codex-skills", codexSkills, Set.of(
				"skills/get-history/SKILL.md",
				"skills/git-amend/SKILL.md", "skills/git-commit/SKILL.md", "skills/git-merge-linear/SKILL.md",
				"skills/git-rebase/SKILL.md", "skills/git-rewrite-history/SKILL.md", "skills/git-squash/SKILL.md",
				"skills/git-commit-per-topic/SKILL.md", "skills/help/SKILL.md")),
			codexPluginLayer(codexPlugin), new ArtifactLayer("codex-rules", codexRules)), codexImage,
			legalMaterials, List.of(commonInstallers, codexInstallers), runtimeDirectory.resolve("codex"));
		try (ToolScope scope = new ToolScope())
		{
			validateRuleDependencies(claudeRoot, codexRoot, projectRoot, scope.yaml());
			RuleArtifactRouter.route(claudeRoot, scope.yaml());
			RuleArtifactRouter.route(codexRoot, scope.yaml());
		}

		ReleasePackager packager = new ReleasePackager(PluginInfo.CAT_VERSION);
		Path claudeArchive = packager.packageArtifact("claude", platform, claudeRoot, releaseDirectory);
		Path codexArchive = packager.packageArtifact("codex", platform, codexRoot, releaseDirectory);
		packager.writeChecksumManifest(List.of(claudeArchive, codexArchive), releaseDirectory.resolve("SHA256SUMS.txt"));
	}

	/**
	 * Prevents instantiation.
	 */
	private DistributionMain()
	{
	}

	/**
	 * Validates the rule graphs that each packaged harness will inject before modifying the assembled artifacts. Each
	 * artifact holds the Windows variants of its harness rules, so the graphs are validated with and without them.
	 *
	 * @param claudeRoot the assembled Claude artifact
	 * @param codexRoot the assembled Codex artifact
	 * @param projectRoot the CAT project root
	 * @param yamlMapper the YAML parser
	 * @throws IOException if a direct rule dependency is missing or cyclic
	 */
	private static void validateRuleDependencies(Path claudeRoot, Path codexRoot, Path projectRoot, YAMLMapper yamlMapper)
		throws IOException
	{
		for (Platform platform : List.of(Platform.LINUX_X64, Platform.WINDOWS_X64))
		{
			ProjectRuleLoader loader = new ProjectRuleLoader(yamlMapper, platform);
			loader.loadForMainAgent(claudeRoot, projectRoot, "claude");
			loader.loadForMainAgent(codexRoot, projectRoot, "codex");
		}
	}

	/**
	 * Finds one dependency JAR by artifact identifier.
	 *
	 * @param directory the dependency directory
	 * @param artifactId the artifact identifier
	 * @return the single JAR whose filename starts with the artifact identifier
	 * @throws IOException if no matching JAR or more than one matching JAR is found
	 */
	private static Path findDependencyJar(Path directory, String artifactId) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			List<Path> matches = entries.filter(path -> path.getFileName().toString().startsWith(artifactId + "-") &&
				path.getFileName().toString().endsWith(".jar")).sorted().toList();
			if (matches.size() != 1)
				throw new IOException("Expected one " + artifactId + " JAR in " + directory + " but found " + matches);
			return matches.getFirst();
		}
	}

	/**
	 * Finds one third-party dependency JAR.
	 *
	 * @param directory the dependency directory
	 * @param groupId the Maven group identifier
	 * @param artifactId the Maven artifact identifier
	 * @return the single JAR whose filename starts with the artifact identifier
	 * @throws IOException if no matching JAR or more than one matching JAR is found
	 */
	private static DependencyJar findThirdParty(Path directory, String groupId, String artifactId) throws IOException
	{
		return new DependencyJar(groupId, artifactId, findDependencyJar(directory, artifactId));
	}

	/**
	 * Copies a module into the empty jlink module directory.
	 *
	 * @param module the module to copy
	 * @param directory the destination directory
	 * @return the copied module
	 * @throws IOException if the module cannot be copied
	 */
	private static Path copyModule(Path module, Path directory) throws IOException
	{
		return Files.copy(module, directory.resolve(module.getFileName()));
	}

	/**
	 * Appends one value to a list.
	 *
	 * @param <T> the value type
	 * @param values the existing values
	 * @param value the value to append
	 * @return the combined list
	 */
	private static <T> List<T> append(List<T> values, T value)
	{
		return Stream.concat(values.stream(), Stream.of(value)).toList();
	}

	/**
	 * Appends values to a list.
	 *
	 * @param <T> the value type
	 * @param values the existing values
	 * @param additionalValues the values to append
	 * @return the combined list
	 */
	private static <T> List<T> append(List<T> values, List<T> additionalValues)
	{
		return Stream.concat(values.stream(), additionalValues.stream()).toList();
	}

	/**
	 * Adds an explicit module descriptor to java-diff-utils.
	 *
	 * @param source the original JAR
	 * @param moduleDirectory the patched-module directory
	 * @return the patched JAR
	 * @throws IOException if the descriptor cannot be added
	 */
	private static Path modularizeJavaDiff(Path source, Path moduleDirectory) throws IOException
	{
		return new ModuleDescriptorPatcher().patch(source, moduleDirectory.resolve(source.getFileName()),
			"io.github.javadiffutils", Set.of("com.github.difflib", "com.github.difflib.algorithm",
				"com.github.difflib.algorithm.myers", "com.github.difflib.patch", "com.github.difflib.text",
				"com.github.difflib.text.deltamerge", "com.github.difflib.unifieddiff"));
	}

	/**
	 * Deletes the directory and all of its contents when it exists, then creates an empty directory at the same path.
	 *
	 * @param directory the directory to delete and recreate
	 * @throws IOException if the directory cannot be recreated
	 */
	private static void deleteAndRecreateDirectory(Path directory) throws IOException
	{
		if (Files.exists(directory, NOFOLLOW_LINKS))
		{
			try (Stream<Path> paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
		Files.createDirectories(directory);
	}

	/**
	 * Returns the layer of the Codex plugin's own resources, including the launchers that the Codex read-file rule tells
	 * agents to run, the programs that commands run by {@code run-part} find first, and the launcher of the PostToolUse
	 * hook.
	 *
	 * @param jar the Codex plugin resource JAR
	 * @return the layer
	 */
	static ArtifactLayer codexPluginLayer(Path jar)
	{
		return new ArtifactLayer("codex-plugin", jar, Set.of(), Set.of("bin/read-part", "bin/run-part",
			"bin/run-part-overrides/rg", "hooks/post-tool-use.sh"));
	}

	/**
	 * Builds the Codex image.
	 *
	 * @param builder  the image builder
	 * @param modules  the module-path entries of the image
	 * @param output   the image's directory
	 * @param platform the platform that the image runs on
	 * @return the image's directory
	 * @throws IOException if the image cannot be built
	 */
	static Path codexImage(JlinkImageBuilder builder, List<Path> modules, Path output, Platform platform)
		throws IOException
	{
		return builder.build(modules, Set.of("io.github.cowwoc.cat.codex.cli", "org.slf4j.nop"), codexLaunchers(),
			output);
	}

	/**
	 * Returns the launchers of the Claude image.
	 *
	 * @return the launchers
	 */
	static List<JlinkImageBuilder.Launcher> claudeLaunchers()
	{
		return append(sharedLaunchers(), List.of(
			new JlinkImageBuilder.Launcher("plugin-info", "io.github.cowwoc.cat.claude.cli",
				"io.github.cowwoc.cat.claude.ClaudePluginInfo"),
			new JlinkImageBuilder.Launcher("claude-runner", "io.github.cowwoc.cat.claude.cli",
				"io.github.cowwoc.cat.claude.runner.ClaudeRunnerCli"),
			new JlinkImageBuilder.Launcher("get-history", "io.github.cowwoc.cat.claude.cli",
				"io.github.cowwoc.cat.claude.ClaudeGetHistory"),
			new JlinkImageBuilder.Launcher("session-start", "io.github.cowwoc.cat.claude.cli",
				"io.github.cowwoc.cat.claude.ClaudeSessionStartHook"),
			new JlinkImageBuilder.Launcher("session-end", "io.github.cowwoc.cat.claude.cli",
				"io.github.cowwoc.cat.claude.ClaudeSessionEndHook"),
			new JlinkImageBuilder.Launcher("subagent-start", "io.github.cowwoc.cat.claude.cli",
				"io.github.cowwoc.cat.claude.ClaudeSubagentStartHook"),
			new JlinkImageBuilder.Launcher("prompt-optimizer", "io.github.cowwoc.cat.claude.cli",
				"io.github.cowwoc.cat.claude.optimizer.ClaudePromptOptimizer")));
	}

	/**
	 * Returns the launchers of the Codex image.
	 *
	 * @return the launchers
	 */
	static List<JlinkImageBuilder.Launcher> codexLaunchers()
	{
		return append(sharedLaunchers(), List.of(
			new JlinkImageBuilder.Launcher("plugin-info", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexPluginInfo"),
			new JlinkImageBuilder.Launcher("codex-runner", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.runner.CodexRunnerCli"),
			new JlinkImageBuilder.Launcher("get-history", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexGetHistory"),
			new JlinkImageBuilder.Launcher("session-start", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexSessionStartHook"),
			new JlinkImageBuilder.Launcher("subagent-start", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexSubagentStartHook"),
			new JlinkImageBuilder.Launcher("post-tool-use", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexPostToolUseHook"),
			new JlinkImageBuilder.Launcher("run-part", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexRunPart"),
			new JlinkImageBuilder.Launcher("read-part", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexReadPart"),
			new JlinkImageBuilder.Launcher("prompt-optimizer", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.optimizer.CodexPromptOptimizer")));
	}

	/**
	 * Returns the launchers that both images contain.
	 *
	 * @return the launchers
	 */
	private static List<JlinkImageBuilder.Launcher> sharedLaunchers()
	{
		return List.of(
			new JlinkImageBuilder.Launcher("git-topic-replay", "io.github.cowwoc.cat.common.cli",
				"io.github.cowwoc.cat.common.git.replay.GitTopicReplayEditor"),
			new JlinkImageBuilder.Launcher("session-analyzer", "io.github.cowwoc.cat.common.cli",
				"io.github.cowwoc.cat.common.history.SessionHistory"));
	}
}
