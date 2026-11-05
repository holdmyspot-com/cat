package com.holdmyspot.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import com.holdmyspot.cat.common.runner.RuntimeRequirement;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.distribution.bundle.ApplicationModules;
import com.holdmyspot.cat.distribution.bundle.ArtifactLayer;
import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;
import com.holdmyspot.cat.distribution.bundle.Launcher;
import com.holdmyspot.cat.distribution.bundle.ModuleDescriptorPatcher;
import com.holdmyspot.cat.distribution.bundle.PluginArtifactBuilder;
import com.holdmyspot.cat.distribution.bundle.PluginInstaller;
import com.holdmyspot.cat.distribution.bundle.PluginLaunchers;
import com.holdmyspot.cat.distribution.bundle.ReleaseAsset;
import com.holdmyspot.cat.distribution.bundle.ReleaseAssetsFragment;
import com.holdmyspot.cat.distribution.bundle.ReleaseBuild;
import com.holdmyspot.cat.distribution.bundle.ReleasePackager;
import com.holdmyspot.cat.distribution.bundle.RuleArtifactRouter;
import com.holdmyspot.cat.distribution.bundle.RuleReferences;
import com.holdmyspot.cat.distribution.license.LegalMaterialsBuilder;
import com.holdmyspot.cat.distribution.license.ThirdPartyComponent;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Builds the platform-specific CAT plugin archives, each of which carries its own installer, from Maven module outputs.
 */
public final class DistributionMain
{
	/**
	 * The file of a plugin's {@code bin} folder that names the Java runtime that the plugin needs.
	 */
	private static final Path RUNTIME_REQUIREMENT = PluginDirectory.BIN.relativize(RuntimeRequirement.PLUGIN_FILE);
	/**
	 * The input that a harness gives its session-start hook when a new session starts, which an installation gives the
	 * plugin's session-start program to train the plugin's startup cache.
	 */
	private static final String SESSION_START_INPUT = "{\"session_id\":\"00000000-0000-4000-8000-000000000000\"," +
		"\"hook_event_name\":\"SessionStart\",\"source\":\"startup\"}";

	static
	{
		DistributionMainSecrets.setDistributionMainAccess(new DistributionMainAccess()
		{
			@Override
			public List<Launcher> claudeLaunchers()
			{
				return DistributionMain.claudeLaunchers();
			}

			@Override
			public List<Launcher> codexLaunchers()
			{
				return DistributionMain.codexLaunchers();
			}

			@Override
			public ArtifactLayer codexPluginLayer(Path jar)
			{
				return DistributionMain.codexPluginLayer(jar);
			}

			@Override
			public void writeCodexLaunchers(Path bin, Platform platform) throws IOException
			{
				DistributionMain.writeCodexLaunchers(bin, platform);
			}

			@Override
			public Path stageBin(ApplicationModules application, Path runtime, Path bin, JsonMapper json)
				throws IOException
			{
				return DistributionMain.stageBin(application, runtime, bin, json);
			}

			@Override
			public void writeRuntimeBinding(Path plugin, Path runtime) throws IOException
			{
				DistributionMain.writeRuntimeBinding(plugin, runtime);
			}

			@Override
			public void writeStartupCacheTraining(Path bin, String harness, JsonMapper json) throws IOException
			{
				DistributionMain.writeStartupCacheTraining(bin, harness, json);
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
	 * @param args the project root, module directory, release directory, base64 release public key (empty for a
	 *             development build) and the release commit's first twelve hexadecimal digits (empty to use the
	 *             project's {@code HEAD}); Maven represents an empty argument as null
	 * @throws NullPointerException if {@code args} is null
	 * @throws IllegalArgumentException if {@code args} does not contain exactly five values
	 * @throws IOException if distribution assembly fails
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length != 5)
		{
			throw new IllegalArgumentException("Usage: DistributionMain <project-root> <module-directory> " +
				"<release-directory> <release-public-key> <release-commit>");
		}
		Path projectRoot = Path.of(args[0]);
		PluginVersion version = new PluginVersion(PluginInfo.CAT_VERSION, ReleaseBuild.commit(args[4], projectRoot));
		try (JvmScope scope = new MainJvmScope(Platform.current().environment(System.getenv()), Pause::sleeping))
		{
			build(scope, projectRoot, Path.of(args[1]), Path.of(args[2]), Platform.current(),
				Objects.requireNonNullElse(args[3], ""), version);
		}
	}

	/**
	 * Builds the plugin distributions with the release key that install writes into the user's policy.
	 *
	 * @param scope            the build's process scope
	 * @param projectRoot      the repository root
	 * @param moduleDirectory  the directory containing dependency JARs
	 * @param releaseDirectory the output directory for archives, checksums and the release assets fragment
	 * @param platform         the target platform
	 * @param publicKey        the base64 X.509 Ed25519 release key, or empty for a build that trusts no key
	 * @param version          the plugin version of the release that the build assembles
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code releaseDirectory} is the filesystem root
	 * @throws IOException              if distribution assembly fails or the public key is invalid
	 */
	public static void build(JvmScope scope, Path projectRoot, Path moduleDirectory, Path releaseDirectory,
		Platform platform, String publicKey, PluginVersion version) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(projectRoot, "projectRoot").isNotNull();
		requireThat(moduleDirectory, "moduleDirectory").isNotNull();
		requireThat(releaseDirectory, "releaseDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(publicKey, "publicKey").isNotNull();
		requireThat(version.release(), "version.release()").isEqualTo(PluginInfo.CAT_VERSION);
		PluginInstaller pluginInstaller = new PluginInstaller(scope, PluginInfo.CAT_VERSION, publicKey);
		projectRoot = projectRoot.toAbsolutePath().normalize();
		moduleDirectory = moduleDirectory.toAbsolutePath().normalize();
		releaseDirectory = releaseDirectory.toAbsolutePath().normalize();
		requireThat(moduleDirectory, "moduleDirectory").isDirectory();
		requireThat(releaseDirectory, "releaseDirectory").isNotEqualTo(releaseDirectory.getRoot(), "filesystemRoot");

		Path target = releaseDirectory.getParent();
		Path runtimeDirectory = target.resolve("runtime");
		// The inputs that the artifacts are assembled from: the modules that the applications resolve from, the plugins'
		// bin folders, and the legal materials
		Path staging = target.resolve("distribution-staging");
		deleteAndRecreateDirectory(releaseDirectory);
		deleteAndRecreateDirectory(runtimeDirectory);
		deleteAndRecreateDirectory(staging);
		Path staged = Files.createDirectory(staging.resolve("modules"));
		Path binDirectory = Files.createDirectory(staging.resolve("bins"));
		Path legalDirectory = staging.resolve("legal");

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
		DependencyJar compress = findThirdParty(moduleDirectory, "org.apache.commons", "commons-compress");
		DependencyJar commonsIo = findThirdParty(moduleDirectory, "commons-io", "commons-io");
		DependencyJar commonsCodec = findThirdParty(moduleDirectory, "commons-codec", "commons-codec");
		DependencyJar commonsLang = findThirdParty(moduleDirectory, "org.apache.commons", "commons-lang3");
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
		DependencyJar jacksonToml = findThirdParty(moduleDirectory, "tools.jackson.dataformat",
			"jackson-dataformat-toml");

		List<Path> sharedRuntimeModules = List.of(copyModule(commonCli, staged),
			copyModule(requirements.path(), staged), copyModule(requirementsAnnotation.path(), staged),
			copyModule(pouch.path(), staged), copyModule(slf4j.path(), staged),
			copyModule(slf4jNop.path(), staged), copyModule(compress.path(), staged),
			copyModule(commonsIo.path(), staged), copyModule(commonsCodec.path(), staged),
			copyModule(commonsLang.path(), staged),
			modularizeJavaDiff(javaDiff.path(), staged));
		Path packagedClaudeCli = copyModule(claudeCli, staged);
		Path packagedCodexCli = copyModule(codexCli, staged);
		// Each harness's runner launcher finds the other harness's installed plugin through that harness's layout, and
		// each harness's installer finds its own installation through it
		List<Path> layoutModules = List.of(copyModule(findDependencyJar(moduleDirectory, "client-claude-layout"),
			staged), copyModule(findDependencyJar(moduleDirectory, "client-codex-layout"), staged));
		Path commonInstaller = findDependencyJar(moduleDirectory, "client-common-installer");
		List<Path> installerModules = List.of(copyModule(commonInstaller, staged),
			copyModule(findDependencyJar(moduleDirectory, "client-claude-installer"), staged),
			copyModule(findDependencyJar(moduleDirectory, "client-codex-installer"), staged));
		List<Path> jsonRuntimeModules = List.of(copyModule(jacksonCore.path(), staged),
			copyModule(jacksonAnnotations.path(), staged), copyModule(jacksonDatabind.path(), staged),
			copyModule(jacksonYaml.path(), staged), copyModule(snakeyaml.path(), staged),
			copyModule(jacksonToml.path(), staged));

		List<Path> applicationModules = append(append(append(append(append(sharedRuntimeModules, packagedClaudeCli),
			packagedCodexCli), layoutModules), installerModules), jsonRuntimeModules);
		// Each plugin also runs the installer from its own modules
		Set<String> installerRoots = Set.of("com.holdmyspot.cat.common.installer",
			"com.holdmyspot.cat.claude.installer", "com.holdmyspot.cat.codex.installer", "com.holdmyspot.cat.common.cli",
			"java.net.http", "org.slf4j.nop");
		ApplicationModules claudeApplication = ApplicationModules.resolve(applicationModules,
			roots(withRoot(installerRoots, "com.holdmyspot.cat.claude.cli"), claudeLaunchers()));
		ApplicationModules codexApplication = ApplicationModules.resolve(applicationModules,
			roots(withRoot(installerRoots, "com.holdmyspot.cat.codex.cli"), codexLaunchers()));
		Set<String> runtimeModules = new TreeSet<>(claudeApplication.systemModules());
		runtimeModules.addAll(codexApplication.systemModules());
		Path releaseRuntime = new JlinkImageBuilder().build(runtimeModules, runtimeDirectory.resolve("java"),
			scope.json());
		Path claudeBin = stageBin(claudeApplication, releaseRuntime, binDirectory.resolve("claude"), scope.json());
		PluginLaunchers.write(claudeLaunchers(), platform, true, claudeBin);
		pluginInstaller.write(claudeBin, platform, true);
		writeStartupCacheTraining(claudeBin, "claude", scope.json());
		Path codexBin = stageBin(codexApplication, releaseRuntime, binDirectory.resolve("codex"), scope.json());
		writeCodexLaunchers(codexBin, platform);
		pluginInstaller.write(codexBin, platform, !platform.isWindows());
		writeStartupCacheTraining(codexBin, "codex", scope.json());

		PluginArtifactBuilder artifactBuilder = new PluginArtifactBuilder(version);
		LegalMaterialsBuilder legalMaterialsBuilder = new LegalMaterialsBuilder(PluginInfo.CAT_VERSION);
		Path catLicense = projectRoot.resolve("LICENSE.md");
		Path canonicalLicenses = projectRoot.resolve("client/legal/licenses");
		Path commonInstallers = projectRoot.resolve("client/common/lifecycle/src/main/resources/install");
		Path claudeInstallers = projectRoot.resolve("client/claude/lifecycle/src/main/resources/install");
		Path codexInstallers = projectRoot.resolve("client/codex/lifecycle/src/main/resources/install");
		String jacksonCoreCoordinates = jacksonCore.coordinates();
		List<ThirdPartyComponent> legalComponents = List.of(
			new ThirdPartyComponent("Apache Commons Compress", compress.coordinates(), "Apache-2.0.txt",
				compress.path(), List.of("META-INF/NOTICE.txt")),
			new ThirdPartyComponent("Apache Commons IO", commonsIo.coordinates(), "Apache-2.0.txt",
				commonsIo.path(), List.of("META-INF/NOTICE.txt")),
			new ThirdPartyComponent("Apache Commons Codec", commonsCodec.coordinates(), "Apache-2.0.txt",
				commonsCodec.path(), List.of("META-INF/NOTICE.txt")),
			new ThirdPartyComponent("Apache Commons Lang", commonsLang.coordinates(), "Apache-2.0.txt",
				commonsLang.path(), List.of("META-INF/NOTICE.txt")),
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
				snakeyaml.path(), List.of()),
			new ThirdPartyComponent("Jackson Dataformat TOML", jacksonToml.coordinates(), "Apache-2.0.txt",
				jacksonToml.path(), List.of("META-INF/NOTICE")));
		Path legalMaterials = legalMaterialsBuilder.build(catLicense, legalComponents, canonicalLicenses,
			legalDirectory);
		Path claudeRoot = artifactBuilder.build(List.of(new ArtifactLayer("common-agents", commonAgents),
			new ArtifactLayer("common-plugin", commonPlugin, Set.of(), Set.of("hooks/session-start.sh",
				"hooks/subagent-start.sh")),
			new ArtifactLayer("common-skills", commonSkills), new ArtifactLayer("claude-skills", claudeSkills),
			new ArtifactLayer("common-rules", commonRules),
			new ArtifactLayer("claude-plugin", claudePlugin, Set.of(), Set.of("hooks/session-end.sh")),
			new ArtifactLayer("claude-rules", claudeRules)),
			claudeBin, legalMaterials, List.of(commonInstallers, claudeInstallers),
			runtimeDirectory.resolve("claude"));
		Path codexRoot = artifactBuilder.build(List.of(new ArtifactLayer("common-agents", commonAgents),
			new ArtifactLayer("common-plugin", commonPlugin, Set.of(), Set.of("hooks/session-start.sh",
				"hooks/subagent-start.sh")),
			new ArtifactLayer("common-skills", commonSkills),
			new ArtifactLayer("common-rules", commonRules), new ArtifactLayer("codex-skills", codexSkills, Set.of(
				"skills/get-history/SKILL.md",
				"skills/git-amend/SKILL.md", "skills/git-commit/SKILL.md", "skills/git-merge-linear/SKILL.md",
				"skills/git-rebase/SKILL.md", "skills/git-rewrite-history/SKILL.md", "skills/git-squash/SKILL.md",
				"skills/help/SKILL.md")),
			codexPluginLayer(codexPlugin), new ArtifactLayer("codex-rules", codexRules)), codexBin, legalMaterials,
			List.of(commonInstallers, codexInstallers),
			runtimeDirectory.resolve("codex"));
		validateRuleDependencies(claudeRoot, codexRoot, projectRoot, scope.yaml());
		RuleArtifactRouter.route(claudeRoot, scope.yaml());
		RuleArtifactRouter.route(codexRoot, scope.yaml());
		RuleReferences.requireResolvable(claudeRoot);
		RuleReferences.requireResolvable(codexRoot);

		writeRuntimeBinding(claudeRoot, releaseRuntime);
		writeRuntimeBinding(codexRoot, releaseRuntime);

		ReleasePackager packager = new ReleasePackager(PluginInfo.CAT_VERSION);
		Path claudeArchive = packager.packageArtifact("claude", platform, claudeRoot, releaseDirectory);
		Path codexArchive = packager.packageArtifact("codex", platform, codexRoot, releaseDirectory);
		packager.writeChecksumManifest(List.of(claudeArchive, codexArchive),
			releaseDirectory.resolve("SHA256SUMS.txt"));
		new ReleaseAssetsFragment(scope).write(version, platform,
			List.of(ReleaseAsset.plugin("claude", platform, claudeArchive),
			ReleaseAsset.plugin("codex", platform, codexArchive)), releaseDirectory);
	}

	/**
	 * Prevents instantiation.
	 */
	private DistributionMain()
	{
	}

	/**
	 * Validates the rule graphs that each packaged harness will inject before modifying the assembled artifacts. Each
	 * artifact holds the POSIX and Windows fragments that its harness rules include, so the graphs are validated with
	 * each.
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
	 * Copies a module into the empty directory of the modules that the applications resolve from.
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
	 * @param added the value to append
	 * @return the combined list
	 */
	private static <T> List<T> append(List<T> values, T added)
	{
		return Stream.concat(values.stream(), Stream.of(added)).toList();
	}

	/**
	 * Appends values to a list.
	 *
	 * @param <T> the value type
	 * @param values the existing values
	 * @param added the values to append
	 * @return the combined list
	 */
	private static <T> List<T> append(List<T> values, List<T> added)
	{
		return Stream.concat(values.stream(), added.stream()).toList();
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
	 * Returns the layer of the Codex plugin's own resources, including the programs that commands run by
	 * {@code run-part} find first and the launcher of the PostToolUse hook.
	 *
	 * @param jar the Codex plugin resource JAR
	 * @return the layer
	 */
	static ArtifactLayer codexPluginLayer(Path jar)
	{
		return new ArtifactLayer("codex-plugin", jar, Set.of(), Set.of("bin/run-part-overrides/rg",
			"hooks/post-tool-use.sh"));
	}

	/**
	 * Writes the launchers of the Codex plugin's {@code bin} folder. On Windows, the plugin holds only the batch
	 * launchers: Codex runs commands there in PowerShell, and CAT's skills name each launcher by its path without an
	 * extension, which PowerShell resolves to the POSIX launcher whenever it exists. The Claude plugin keeps both,
	 * because Claude Code runs commands in Git Bash.
	 *
	 * @param bin      the folder that becomes the plugin's {@code bin} folder
	 * @param platform the platform that the plugin runs on
	 * @throws IOException if the launchers cannot be written
	 */
	static void writeCodexLaunchers(Path bin, Platform platform) throws IOException
	{
		PluginLaunchers.write(codexLaunchers(), platform, !platform.isWindows(), bin);
	}

	/**
	 * Stages the folder that becomes a plugin's {@code bin} folder with the release's Java runtime, the plugin's modules
	 * beside the runtime's own folders, and the plugin's runtime requirement.
	 *
	 * @param application the plugin's modules
	 * @param runtime     the release's Java runtime
	 * @param bin         the folder, which must not exist
	 * @param json        the JSON writer
	 * @return {@code bin}
	 * @throws IOException if the folder cannot be written, or the runtime already holds the folder of the modules
	 */
	static Path stageBin(ApplicationModules application, Path runtime, Path bin, JsonMapper json) throws IOException
	{
		Path runtimeCopy = bin.resolve(PluginDirectory.BIN.relativize(PluginDirectory.RUNTIME));
		copyTree(runtime, runtimeCopy);
		Path modules = bin.resolve(PluginDirectory.BIN.relativize(PluginDirectory.MODULES));
		if (Files.exists(modules, NOFOLLOW_LINKS))
			throw new IOException("The Java runtime already holds the plugin modules' folder: " + modules);
		Files.createDirectories(modules);
		for (Path jar : application.jars())
			Files.copy(jar, modules.resolve(jar.getFileName()));
		application.writeRequirement(bin.resolve(RUNTIME_REQUIREMENT), json);
		return bin;
	}

	/**
	 * Copies a directory tree, keeping its symbolic links and its files' permissions.
	 *
	 * @param source      the directory
	 * @param destination the copy, which must not exist
	 * @throws IOException if the tree cannot be copied
	 */
	private static void copyTree(Path source, Path destination) throws IOException
	{
		if (!Files.isDirectory(source, NOFOLLOW_LINKS))
			throw new IOException("Copy source is not a directory: " + source);
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : paths.sorted().toList())
			{
				Path target = destination.resolve(source.relativize(path).toString());
				if (Files.isSymbolicLink(path))
					Files.createSymbolicLink(target, Files.readSymbolicLink(path));
				else if (Files.isDirectory(path, NOFOLLOW_LINKS))
					Files.createDirectories(target);
				else
					Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
			}
		}
	}

	/**
	 * Writes the description of how an installation trains a plugin's startup cache into the plugin's {@code bin}
	 * folder.
	 *
	 * @param bin     the folder that becomes the plugin's {@code bin} folder
	 * @param harness the plugin's harness, {@code claude} or {@code codex}
	 * @param json    the JSON writer
	 * @throws IOException if the description cannot be written
	 */
	static void writeStartupCacheTraining(Path bin, String harness, JsonMapper json) throws IOException
	{
		List<Launcher> launchers;
		String rootVariable;
		switch (harness)
		{
			// The variable that names the plugin directory to the harness's hooks
			case "claude" ->
			{
				launchers = claudeLaunchers();
				rootVariable = "CLAUDE_PLUGIN_ROOT";
			}
			case "codex" ->
			{
				launchers = codexLaunchers();
				rootVariable = "PLUGIN_ROOT";
			}
			default -> throw new IllegalArgumentException("Unsupported harness: " + harness);
		}
		Launcher sessionStart = launchers.stream().filter(launcher -> launcher.name().equals("session-start")).
			findFirst().orElseThrow();
		ObjectNode training = json.createObjectNode();
		training.put("format", 1);
		ArrayNode arguments = training.putArray("arguments");
		PluginLaunchers.programArguments(sessionStart).forEach(arguments::add);
		training.put("plugin_root_variable", rootVariable);
		training.put("input", SESSION_START_INPUT);
		Files.writeString(bin.resolve(PluginDirectory.BIN.relativize(StartupCache.TRAINING)),
			json.writeValueAsString(training) + "\n", UTF_8);
	}

	/**
	 * Binds a plugin assembled in the source checkout to the release runtime that the build wrote, so that the plugin
	 * runs from the checkout without being installed. The binding is written after the plugin's runtime manifest,
	 * because it names a path on the building machine, and release archives leave it out.
	 *
	 * @param plugin  the assembled plugin
	 * @param runtime the release runtime
	 * @throws IOException if the binding cannot be written
	 */
	static void writeRuntimeBinding(Path plugin, Path runtime) throws IOException
	{
		Files.writeString(plugin.resolve(RuntimeBinding.PATH),
			runtime.toAbsolutePath().normalize() + "\n", UTF_8);
	}

	/**
	 * Adds one module to a set of roots.
	 *
	 * @param roots the roots
	 * @param root  the added module
	 * @return the combined roots
	 */
	private static Set<String> withRoot(Set<String> roots, String root)
	{
		Set<String> combined = new TreeSet<>(roots);
		combined.add(root);
		return combined;
	}

	/**
	 * Returns the modules that an application starts from: its own roots and the modules of its launchers.
	 *
	 * @param roots     the application's roots
	 * @param launchers the application's launchers
	 * @return the modules
	 */
	private static Set<String> roots(Set<String> roots, List<Launcher> launchers)
	{
		Set<String> modules = new TreeSet<>(roots);
		for (Launcher launcher : launchers)
			modules.add(launcher.module());
		return modules;
	}

	/**
	 * Returns the launchers of the Claude plugin.
	 *
	 * @return the launchers
	 */
	static List<Launcher> claudeLaunchers()
	{
		return append(sharedLaunchers(), List.of(
			new Launcher("plugin-info", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudePluginInfo"),
			new Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"),
			new Launcher("claude-runner", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.runner.ClaudeRunnerCli"),
			new Launcher("claude-artifact", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.runner.ClaudeArtifact"),
			new Launcher("codex-artifact", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.runner.CodexArtifact"),
			new Launcher("codex-runner", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.runner.CodexRunnerRelay"),
			new Launcher("get-history", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeGetHistory"),
			new Launcher("session-start", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeSessionStartHook"),
			new Launcher("session-end", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeSessionEndHook"),
			new Launcher("subagent-start", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeSubagentStartHook"),
			new Launcher("prompt-optimizer", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.optimizer.ClaudePromptOptimizer"),
			new Launcher("wait-until", "com.holdmyspot.cat.claude.cli",
				"com.holdmyspot.cat.claude.ClaudeWaitUntil")));
	}

	/**
	 * Returns the launchers of the Codex plugin.
	 *
	 * @return the launchers
	 */
	static List<Launcher> codexLaunchers()
	{
		return append(sharedLaunchers(), List.of(
			new Launcher("plugin-info", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexPluginInfo"),
			new Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"),
			new Launcher("codex-runner", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.runner.CodexRunnerCli"),
			new Launcher("codex-artifact", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.runner.CodexArtifact"),
			new Launcher("claude-artifact", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.runner.ClaudeArtifact"),
			new Launcher("claude-runner", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.runner.ClaudeRunnerRelay"),
			new Launcher("get-history", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexGetHistory"),
			new Launcher("session-start", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexSessionStartHook"),
			new Launcher("subagent-start", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexSubagentStartHook"),
			new Launcher("post-tool-use", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexPostToolUseHook"),
			new Launcher("run-part", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexRunPart"),
			new Launcher("read-part", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexReadPart"),
			new Launcher("prompt-optimizer", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.optimizer.CodexPromptOptimizer"),
			new Launcher("wait-until", "com.holdmyspot.cat.codex.cli",
				"com.holdmyspot.cat.codex.CodexWaitUntil")));
	}

	/**
	 * Returns the launchers that both plugins contain.
	 *
	 * @return the launchers
	 */
	private static List<Launcher> sharedLaunchers()
	{
		return List.of(
			new Launcher("session-analyzer", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.history.SessionHistory"));
	}
}
