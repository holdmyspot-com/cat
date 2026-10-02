package io.github.cowwoc.cat.common.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Audits the optimizer and harness capacity sources for isolation and testability properties that a source scan
 * can establish.
 * <p>
 * The scan covers the production and test sources of {@code io.github.cowwoc.cat.common.optimizer},
 * {@code io.github.cowwoc.cat.claude.optimizer} and {@code io.github.cowwoc.cat.codex.optimizer}, including their
 * subpackages, and the {@code HarnessCapacity*.java} files of {@code io.github.cowwoc.cat.common.runner}. A scope that
 * does not exist yet contributes no files. The build passes the {@code client} directory in the
 * {@code cat.client.directory} system property.
 * <p>
 * The audit checks:
 * <ul>
 *   <li>no static field is non-final;</li>
 *   <li>a test class has only final fields;</li>
 *   <li>no code sleeps;</li>
 *   <li>only a class that declares {@code main}, or a harness adapter's production scope, reads {@code HOME},
 *   {@code user.home} or the process environment;</li>
 *   <li>the harness-neutral core references no Claude or Codex package;</li>
 *   <li>production sources reference no type from a test source tree.</li>
 * </ul>
 * This class does not check statistical thresholds, caps or probabilities.
 */
public final class TestabilityAuditTest
{
	/**
	 * The packages, relative to a source root, whose files the audit scans.
	 */
	private static final List<String> OPTIMIZER_PACKAGES = List.of("io/github/cowwoc/cat/common/optimizer",
		"io/github/cowwoc/cat/claude/optimizer", "io/github/cowwoc/cat/codex/optimizer");
	/**
	 * The package, relative to a source root, of the harness capacity files.
	 */
	private static final String RUNNER_PACKAGE = "io/github/cowwoc/cat/common/runner";
	/**
	 * The modules, relative to the {@code client} directory, whose sources the audit scans.
	 */
	private static final List<String> MODULES = List.of("common/cli", "claude/cli", "codex/cli");
	/**
	 * The module of the harness-neutral core.
	 */
	private static final String CORE_MODULE = "common/cli";
	/**
	 * Matches a call or reference to a {@code sleep} method, or its static import.
	 */
	private static final Pattern SLEEP = Pattern.compile("\\.\\s*sleep\\s*[(;]|::\\s*sleep(?![\\w$])");
	/**
	 * Matches a read of the process environment.
	 */
	private static final Pattern GETENV = Pattern.compile("(?<![\\w$])getenv(?![\\w$])");
	/**
	 * Matches the declaration of a {@code main} method.
	 */
	private static final Pattern MAIN = Pattern.compile("(?<![\\w$])static\\s+void\\s+main\\s*\\(");
	/**
	 * The literals that name the user's home directory.
	 */
	private static final Set<String> HOME_LITERALS = Set.of("HOME", "user.home");
	/**
	 * Matches a reference to a Claude or Codex package.
	 */
	private static final Pattern HARNESS_PACKAGE = Pattern.compile(
		"(?<![\\w$.])io\\s*\\.\\s*github\\s*\\.\\s*cowwoc\\s*\\.\\s*cat\\s*\\.\\s*(claude|codex)(?![\\w$])");
	/**
	 * Matches a qualified type name: lowercase package segments followed by a capitalized type name.
	 */
	private static final Pattern QUALIFIED_TYPE = Pattern.compile("(?<![\\w$.])((?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*)");

	/**
	 * Creates a test instance.
	 */
	public TestabilityAuditTest()
	{
	}

	/**
	 * Verifies that the scan finds the statistics sources and this test, so that a wrong source root cannot pass the
	 * audit vacuously.
	 */
	@Test
	public void scanCoversOptimizerSources()
	{
		// Requirement: R-T1, R-T2
		Path client = clientDirectory();
		List<Path> scanned = Scan.FILES.stream().map(SourceFile::path).toList();
		requireThat(scanned, "scanned").contains(client.resolve("common/cli/src/main/java/" +
			"io/github/cowwoc/cat/common/optimizer/statistics/BettingProcess.java"));
		requireThat(scanned, "scanned").contains(auditSource(client));
	}

	/**
	 * Verifies that no scanned static field is mutable.
	 */
	@Test
	public void staticFieldsAreFinal()
	{
		// Requirement: R-T1
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.FILES)
			violations.addAll(findMutableStaticFields(file));
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that every scanned test class has only final fields.
	 */
	@Test
	public void testClassFieldsAreFinal()
	{
		// Requirement: R-T1
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.FILES)
			violations.addAll(findMutableTestClassFields(file));
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that no scanned source sleeps.
	 */
	@Test
	public void sourcesDoNotSleep()
	{
		// Requirement: R-T1
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.FILES)
			violations.addAll(findSleeps(file));
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that only {@code main} classes and harness production scopes read the home directory or the process
	 * environment.
	 */
	@Test
	public void onlyMainReadsEnvironment()
	{
		// Requirement: R-T1
		Path audit = auditSource(clientDirectory());
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.FILES)
		{
			// This class names the forbidden literals in order to find them
			violations.addAll(findEnvironmentReads(file, !file.path().equals(audit)));
		}
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that the harness-neutral core references no harness package.
	 */
	@Test
	public void coreReferencesNoHarness()
	{
		// Requirement: R-T2
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.FILES)
			violations.addAll(findHarnessReferences(file));
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that production sources reference no type from a test source tree.
	 */
	@Test
	public void productionReferencesNoTestSupport()
	{
		// Requirement: R-T2
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.FILES)
			violations.addAll(findTestSupportReferences(file, Scan.TEST_TYPES));
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that the static-field check reports exactly the mutable static fields of classes, enums and records.
	 */
	@Test
	public void reportsMutableStaticFields()
	{
		// Requirement: R-T1
		SourceFile file = sample(true, false, """
			class Sample
			{
				// static int commented;
				private static int count = 0;
				@SuppressWarnings({"unused"})
				static java.util.List<String> names;
				private static final int LIMIT = 1;
				private static final Runnable TASK = () ->
				{
					int local = 0;
				};
				private int instance;
				static
				{
					LIMIT2 = "static int notAField;";
				}
				abstract static int compute(int value);
				interface Constants
				{
					int VALUE = 1;
				}
				enum Color
				{
					RED
					{
						int body;
					},
					GREEN;
					static int counter;
				}
				record Point(int x)
				{
					static int created;
				}
			}""");
		requireThat(findMutableStaticFields(file), "violations").isEqualTo(List.of(
			"Sample.java:4: static field is not final: private static int count",
			"Sample.java:6: static field is not final: static java.util.List<String> names",
			"Sample.java:29: static field is not final: static int counter",
			"Sample.java:33: static field is not final: static int created"));
	}

	/**
	 * Verifies that the test-class check reports a test class's mutable fields and ignores nested helpers.
	 */
	@Test
	public void reportsMutableTestClassFields()
	{
		// Requirement: R-T1
		SourceFile file = new SourceFile(Path.of("SampleTest.java"), false, false, JavaSource.parse("""
			public final class SampleTest
			{
				private int calls;
				private final int limit = 1;
				private static final class Helper
				{
					private int state;
				}
			}"""));
		requireThat(findMutableTestClassFields(file), "violations").isEqualTo(List.of(
			"SampleTest.java:3: test class field is not final: private int calls"));
		SourceFile helper = new SourceFile(Path.of("Helper.java"), false, false, JavaSource.parse("""
			final class Helper
			{
				private int state;
			}"""));
		requireThat(findMutableTestClassFields(helper), "helperViolations").isEmpty();
	}

	/**
	 * Verifies that the sleep check reports calls, method references and static imports, but not comments or
	 * literals.
	 */
	@Test
	public void reportsSleeps()
	{
		// Requirement: R-T1
		SourceFile file = sample(true, false, """
			import static java.lang.Thread.sleep;
			class Sample
			{
				void run() throws InterruptedException
				{
					Thread.sleep(5);
					java.util.concurrent.TimeUnit.SECONDS.sleep(1);
					Pause pause = Thread::sleep;
					// Thread.sleep(1);
					String text = "Thread.sleep(1)";
				}
			}""");
		requireThat(findSleeps(file), "violations").isEqualTo(List.of(
			"Sample.java:1: sleeps: .sleep;",
			"Sample.java:6: sleeps: .sleep(",
			"Sample.java:7: sleeps: .sleep(",
			"Sample.java:8: sleeps: ::sleep"));
	}

	/**
	 * Verifies that the environment check reports reads outside {@code main} classes and harness production scopes
	 * only.
	 */
	@Test
	public void reportsEnvironmentReadsOutsideMain()
	{
		// Requirement: R-T1
		String library = """
			class Sample
			{
				String read()
				{
					String path = System.getenv("PATH");
					String home = System.getProperty("user.home");
					return java.util.Map.of("HOME", path).get(home);
				}
			}""";
		requireThat(findEnvironmentReads(sample(true, false, library), true), "libraryViolations").isEqualTo(List.of(
			"Sample.java:5: reads the process environment",
			"Sample.java:6: names the home directory: user.home",
			"Sample.java:7: names the home directory: HOME"));
		requireThat(findEnvironmentReads(sample(true, false, library), false), "withoutLiterals").isEqualTo(List.of(
			"Sample.java:5: reads the process environment"));
		String main = library.replace("String read()", "public static void main(String[] args)");
		requireThat(findEnvironmentReads(sample(true, false, main), true), "mainViolations").isEmpty();

		String scope = library.replace("class Sample", "class SampleScope");
		Path scopePath = Path.of("SampleScope.java");
		requireThat(findEnvironmentReads(new SourceFile(scopePath, true, false, JavaSource.parse(scope)), true),
			"harnessScopeViolations").isEmpty();
		requireThat(findEnvironmentReads(new SourceFile(scopePath, true, true, JavaSource.parse(scope)), true),
			"coreScopeViolations").isEqualTo(List.of(
				"SampleScope.java:5: reads the process environment",
				"SampleScope.java:6: names the home directory: user.home",
				"SampleScope.java:7: names the home directory: HOME"));
		requireThat(findEnvironmentReads(new SourceFile(scopePath, false, false, JavaSource.parse(scope)), true),
			"testScopeViolations").isEqualTo(List.of(
				"SampleScope.java:5: reads the process environment",
				"SampleScope.java:6: names the home directory: user.home",
				"SampleScope.java:7: names the home directory: HOME"));
	}

	/**
	 * Verifies that the harness check reports references from core sources only.
	 */
	@Test
	public void reportsHarnessReferencesFromCore()
	{
		// Requirement: R-T2
		String text = """
			import io.github.cowwoc.cat.claude.Launcher;
			// io.github.cowwoc.cat.codex.Launcher
			class Sample
			{
				Object create()
				{
					return new io.github.cowwoc.cat.codex.Launcher();
				}
			}""";
		requireThat(findHarnessReferences(sample(true, true, text)), "coreViolations").isEqualTo(List.of(
			"Sample.java:1: references a harness package: io.github.cowwoc.cat.claude",
			"Sample.java:7: references a harness package: io.github.cowwoc.cat.codex"));
		requireThat(findHarnessReferences(sample(true, false, text)), "adapterViolations").isEmpty();
	}

	/**
	 * Verifies that the test-support check reports references from production sources only.
	 */
	@Test
	public void reportsTestSupportUseInProduction()
	{
		// Requirement: R-T2
		Set<String> testTypes = Set.of("io.github.cowwoc.cat.common.optimizer.FakeStore");
		String text = """
			import io.github.cowwoc.cat.common.optimizer.FakeStore;
			import static io.github.cowwoc.cat.common.optimizer.FakeStore.create;
			class Sample
			{
				Object store = io.github.cowwoc.cat.common.optimizer.Store.open();
			}""";
		requireThat(findTestSupportReferences(sample(true, true, text), testTypes), "productionViolations").
			isEqualTo(List.of(
				"Sample.java:1: references test support: io.github.cowwoc.cat.common.optimizer.FakeStore",
				"Sample.java:2: references test support: io.github.cowwoc.cat.common.optimizer.FakeStore"));
		requireThat(findTestSupportReferences(sample(false, true, text), testTypes), "testViolations").isEmpty();
	}

	/**
	 * Returns a sample source file named {@code Sample.java}.
	 *
	 * @param isProduction {@code true} if the sample is a production source
	 * @param isCore       {@code true} if the sample belongs to the harness-neutral core
	 * @param text         the sample's contents
	 * @return the sample
	 */
	private static SourceFile sample(boolean isProduction, boolean isCore, String text)
	{
		return new SourceFile(Path.of("Sample.java"), isProduction, isCore, JavaSource.parse(text));
	}

	/**
	 * Returns the {@code client} directory that the build passes in.
	 *
	 * @return the directory
	 */
	private static Path clientDirectory()
	{
		String value = System.getProperty("cat.client.directory");
		requireThat(value, "cat.client.directory").isNotNull();
		return Path.of(value).toAbsolutePath().normalize();
	}

	/**
	 * Returns the path of this test's source file.
	 *
	 * @param client the {@code client} directory
	 * @return the path
	 */
	private static Path auditSource(Path client)
	{
		return client.resolve("common/cli/src/test/java/io/github/cowwoc/cat/common/optimizer/" +
			"TestabilityAuditTest.java");
	}

	/**
	 * Parses every source file in the audit's scope.
	 *
	 * @param client the {@code client} directory
	 * @return the files
	 * @throws IOException if a source cannot be read
	 */
	private static List<SourceFile> scan(Path client) throws IOException
	{
		List<SourceFile> files = new ArrayList<>();
		for (String module : MODULES)
		{
			for (String sourceSet : List.of("main", "test"))
			{
				Path root = client.resolve(module).resolve("src").resolve(sourceSet).resolve("java");
				boolean isProduction = sourceSet.equals("main");
				boolean isCore = module.equals(CORE_MODULE);
				List<Path> paths = new ArrayList<>();
				for (String optimizerPackage : OPTIMIZER_PACKAGES)
					paths.addAll(listJavaFiles(root.resolve(optimizerPackage)));
				for (Path path : listJavaFiles(root.resolve(RUNNER_PACKAGE)))
				{
					if (path.getParent().equals(root.resolve(RUNNER_PACKAGE)) &&
						path.getFileName().toString().startsWith("HarnessCapacity"))
					{
						paths.add(path);
					}
				}
				for (Path path : paths)
					files.add(new SourceFile(path, isProduction, isCore, JavaSource.parse(Files.readString(path))));
			}
		}
		return List.copyOf(files);
	}

	/**
	 * Returns the Java source files below a directory.
	 *
	 * @param directory the directory
	 * @return the files, or an empty list if the directory does not exist
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> listJavaFiles(Path directory) throws IOException
	{
		if (Files.notExists(directory))
			return List.of();
		try (Stream<Path> paths = Files.walk(directory))
		{
			return paths.filter(Files::isRegularFile).
				filter(path -> path.getFileName().toString().endsWith(".java")).
				sorted().
				toList();
		}
	}

	/**
	 * Returns the qualified names of the types in every test source tree.
	 *
	 * @param client the {@code client} directory
	 * @return the names
	 * @throws IOException if a test source tree cannot be listed
	 */
	private static Set<String> findTestTypes(Path client) throws IOException
	{
		Set<String> types = new HashSet<>();
		for (String module : MODULES)
		{
			Path root = client.resolve(module).resolve("src/test/java");
			for (Path path : listJavaFiles(root))
			{
				String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), ".");
				String type = relative.substring(0, relative.length() - ".java".length());
				if (!type.endsWith("module-info") && !type.endsWith("package-info"))
					types.add(type);
			}
		}
		return types;
	}

	/**
	 * Returns a file's non-final static fields.
	 *
	 * @param file the file
	 * @return a description of each violation
	 */
	private static List<String> findMutableStaticFields(SourceFile file)
	{
		List<String> violations = new ArrayList<>();
		for (JavaSource.Field field : file.source().fields())
		{
			if (field.isStatic() && !field.isFinal())
				violations.add(file.describe(field.offset(), "static field is not final: " + field.declaration()));
		}
		return violations;
	}

	/**
	 * Returns the non-final fields of a test class.
	 *
	 * @param file the file
	 * @return a description of each violation, or an empty list if the file is not a test class
	 */
	private static List<String> findMutableTestClassFields(SourceFile file)
	{
		if (file.isProduction() || !file.path().getFileName().toString().endsWith("Test.java"))
			return List.of();
		List<String> violations = new ArrayList<>();
		for (JavaSource.Field field : file.source().fields())
		{
			if (field.isTopLevel() && !field.isFinal())
				violations.add(file.describe(field.offset(), "test class field is not final: " + field.declaration()));
		}
		return violations;
	}

	/**
	 * Returns a file's sleeps.
	 *
	 * @param file the file
	 * @return a description of each violation
	 */
	private static List<String> findSleeps(SourceFile file)
	{
		List<String> violations = new ArrayList<>();
		Matcher matcher = SLEEP.matcher(file.source().code());
		while (matcher.find())
			violations.add(file.describe(matcher.start(), "sleeps: " + matcher.group().replaceAll("\\s+", "")));
		return violations;
	}

	/**
	 * Returns a file's reads of the home directory or the process environment.
	 *
	 * @param file          the file
	 * @param checkLiterals {@code true} to report literals that name the home directory
	 * @return a description of each violation, or an empty list if the file declares {@code main} or is a harness
	 *   adapter's production scope
	 */
	private static List<String> findEnvironmentReads(SourceFile file, boolean checkLiterals)
	{
		String code = file.source().code();
		if (MAIN.matcher(code).find() || isHarnessProductionScope(file))
			return List.of();
		List<Violation> violations = new ArrayList<>();
		Matcher matcher = GETENV.matcher(code);
		while (matcher.find())
			violations.add(new Violation(matcher.start(), "reads the process environment"));
		if (checkLiterals)
		{
			for (JavaSource.Literal literal : file.source().literals())
			{
				if (HOME_LITERALS.contains(literal.value()))
					violations.add(new Violation(literal.offset(), "names the home directory: " + literal.value()));
			}
		}
		violations.sort(Comparator.comparingInt(Violation::offset));
		return violations.stream().map(violation -> file.describe(violation.offset(), violation.message())).toList();
	}

	/**
	 * Indicates whether a file is the production scope of a harness adapter: the composition boundary that resolves
	 * the process environment and hands the resolved values to the core.
	 *
	 * @param file the file
	 * @return {@code true} if the file is a production source outside the core whose name ends with {@code Scope}
	 */
	private static boolean isHarnessProductionScope(SourceFile file)
	{
		return file.isProduction() && !file.isCore() && file.path().getFileName().toString().endsWith("Scope.java");
	}

	/**
	 * Returns a core file's references to harness packages.
	 *
	 * @param file the file
	 * @return a description of each violation, or an empty list if the file is not in the core
	 */
	private static List<String> findHarnessReferences(SourceFile file)
	{
		if (!file.isCore())
			return List.of();
		List<String> violations = new ArrayList<>();
		Matcher matcher = HARNESS_PACKAGE.matcher(file.source().code());
		while (matcher.find())
		{
			violations.add(file.describe(matcher.start(), "references a harness package: io.github.cowwoc.cat." +
				matcher.group(1)));
		}
		return violations;
	}

	/**
	 * Returns a production file's references to test-support types.
	 *
	 * @param file      the file
	 * @param testTypes the qualified names of the types in every test source tree
	 * @return a description of each violation, or an empty list if the file is not a production source
	 */
	private static List<String> findTestSupportReferences(SourceFile file, Set<String> testTypes)
	{
		if (!file.isProduction())
			return List.of();
		List<String> violations = new ArrayList<>();
		Matcher matcher = QUALIFIED_TYPE.matcher(file.source().code());
		while (matcher.find())
		{
			if (testTypes.contains(matcher.group(1)))
				violations.add(file.describe(matcher.start(), "references test support: " + matcher.group(1)));
		}
		return violations;
	}

	/**
	 * The sources in the audit's scope, parsed once for every test of the class because parsing them dominates each
	 * check's runtime. The holder is initialized on first use, so a failure to read the sources fails only the tests
	 * that need them.
	 */
	private static final class Scan
	{
		/**
		 * Every source file in the audit's scope.
		 */
		static final List<SourceFile> FILES;
		/**
		 * The qualified names of the types in every test source tree.
		 */
		static final Set<String> TEST_TYPES;

		static
		{
			Path client = clientDirectory();
			try
			{
				FILES = scan(client);
				TEST_TYPES = Set.copyOf(findTestTypes(client));
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}

		/**
		 * Prevents construction.
		 */
		private Scan()
		{
		}
	}

	/**
	 * A scanned source file.
	 *
	 * @param path         the file's path
	 * @param isProduction {@code true} if the file is in a production source tree
	 * @param isCore       {@code true} if the file belongs to the harness-neutral core
	 * @param source       the parsed contents
	 */
	private record SourceFile(Path path, boolean isProduction, boolean isCore, JavaSource source)
	{
		/**
		 * Describes a violation in this file.
		 *
		 * @param offset  the offset of the violation
		 * @param message the violation
		 * @return the description, prefixed by the file and line
		 */
		String describe(int offset, String message)
		{
			return path + ":" + source.lineOf(offset) + ": " + message;
		}
	}

	/**
	 * A violation before it is described.
	 *
	 * @param offset  the offset of the violation
	 * @param message the violation
	 */
	private record Violation(int offset, String message)
	{
	}
}
