package com.holdmyspot.cat.common.optimizer.test;

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
 * Audits the Java sources for isolation and testability properties that a source scan can establish.
 * <p>
 * The test-suite checks and the sleep checks cover every production and test source of the Java modules
 * ({@code common/cli}, {@code claude/cli}, {@code codex/cli} and {@code common/distribution}). The other checks cover
 * the production and test sources of {@code com.holdmyspot.cat.common.optimizer},
 * {@code com.holdmyspot.cat.claude.optimizer} and {@code com.holdmyspot.cat.codex.optimizer}, including their
 * subpackages, and the {@code HarnessCapacity*.java} files of {@code com.holdmyspot.cat.common.runner}. A scope that
 * does not exist yet contributes no files. The build passes the {@code client} directory in the
 * {@code cat.client.directory} system property.
 * <p>
 * The audit checks:
 * <ul>
 *   <li>no static field is non-final;</li>
 *   <li>a test class has only final fields;</li>
 *   <li>no field that tests share holds a mutable array, collection or builder;</li>
 *   <li>no test source names the current directory, resolves a relative path against it, or reads the user's home
 *   directory, the user's name or the whole process environment;</li>
 *   <li>no code other than {@code Pause.sleeping} sleeps, only a {@code main} method creates a sleeping pause or
 *   waiting, and no test calls the {@code main} method of a production class;</li>
 *   <li>only a class that declares {@code main}, or a harness adapter's production scope, reads {@code HOME},
 *   {@code user.home} or the process environment;</li>
 *   <li>the harness-neutral core references no Claude or Codex package;</li>
 *   <li>production sources reference no type from a test source tree;</li>
 *   <li>no production source other than the fork adapter constructs {@code Git}.</li>
 * </ul>
 * This class does not check statistical thresholds, caps or probabilities.
 */
public final class TestabilityAuditTest
{
	/**
	 * The packages, relative to a source root, whose files the audit scans.
	 */
	private static final List<String> OPTIMIZER_PACKAGES = List.of("com/holdmyspot/cat/common/optimizer",
		"com/holdmyspot/cat/claude/optimizer", "com/holdmyspot/cat/codex/optimizer");
	/**
	 * The package, relative to a source root, of the harness capacity files.
	 */
	private static final String RUNNER_PACKAGE = "com/holdmyspot/cat/common/runner";
	/**
	 * The modules, relative to the {@code client} directory, whose sources the audit scans.
	 */
	private static final List<String> MODULES = List.of("common/cli", "claude/cli", "codex/cli",
		"common/distribution");
	/**
	 * The module of the harness-neutral core.
	 */
	private static final String CORE_MODULE = "common/cli";
	/**
	 * Matches a call or reference to a {@code sleep} method, or its static import.
	 */
	private static final Pattern SLEEP = Pattern.compile("\\.\\s*sleep\\s*[(;]|::\\s*sleep(?![\\w$])");
	/**
	 * Matches a read of the process environment. The word boundary is checked after the literal, because a pattern
	 * that starts with a literal lets the matcher skip to its occurrences instead of trying every offset.
	 */
	private static final Pattern GETENV = Pattern.compile("getenv(?<![\\w$]getenv)(?![\\w$])");
	/**
	 * Matches the declaration of a {@code main} method. Like {@link #GETENV}, it starts with a literal.
	 */
	private static final Pattern MAIN = Pattern.compile("static(?<![\\w$]static)\\s+void\\s+main\\s*\\(");
	/**
	 * The literals that name the user's home directory.
	 */
	private static final Set<String> HOME_LITERALS = Set.of("HOME", "user.home");
	/**
	 * Matches a reference to a Claude or Codex package. Like {@link #GETENV}, it starts with a literal.
	 */
	private static final Pattern HARNESS_PACKAGE = Pattern.compile(
		"com(?<![\\w$.]com)\\s*\\.\\s*holdmyspot\\s*\\.\\s*cat\\s*\\.\\s*(claude|codex)(?![\\w$])");
	/**
	 * Matches a qualified type name: lowercase package segments followed by a capitalized type name.
	 */
	private static final Pattern QUALIFIED_TYPE = Pattern.compile("(?<![\\w$.])((?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*)");
	/**
	 * Matches a call to a constructor of {@code Git}, whose name may be qualified. Like {@link #GETENV}, it starts
	 * with a literal.
	 */
	private static final Pattern NEW_GIT = Pattern.compile(
		"new(?<![\\w$]new)\\s+(?:[a-z_][\\w$]*\\s*\\.\\s*)*Git\\s*\\(");
	/**
	 * Matches a reference to a constructor of {@code Git}.
	 */
	private static final Pattern GIT_CONSTRUCTOR_REFERENCE = Pattern.compile(
		"Git(?<![\\w$]Git)\\s*::\\s*new(?![\\w$])");
	/**
	 * The production source, relative to the {@code client} directory, that may construct {@code Git}: the adapter
	 * behind {@code Forks}, through which the rest of the optimizer reaches Git so that its tests can replace it.
	 */
	private static final String FORK_ADAPTER = "common/cli/src/main/java/" +
		"com/holdmyspot/cat/common/optimizer/workspace/Fork.java";
	/**
	 * The production source, relative to the {@code client} directory, that may sleep: it declares
	 * {@code Pause.sleeping}, which only an application entry point creates.
	 */
	private static final String SLEEPING_PAUSE = "common/cli/src/main/java/com/holdmyspot/cat/common/Pause.java";
	/**
	 * Matches a reference to the sleeping pause. Like {@link #GETENV}, it starts with a literal.
	 */
	private static final Pattern SLEEPING_REFERENCE = Pattern.compile(
		"Pause(?<![\\w$]Pause)\\s*(?:\\.|::)\\s*sleeping(?![\\w$])");
	/**
	 * Matches a call to a {@code main} method through its class's simple name, capturing the name.
	 */
	private static final Pattern MAIN_CALL = Pattern.compile("(?<![\\w$.])([A-Z][\\w$]*)\\s*\\.\\s*main\\s*\\(");
	/**
	 * Matches the code before a literal that a call takes as a path: the literal is the call's first argument.
	 */
	private static final Pattern PATH_CALL = Pattern.compile(
		"(?:Path\\s*\\.\\s*of|Paths\\s*\\.\\s*get|new\\s+File)\\s*\\(\\s*$");
	/**
	 * Matches the code after a path call that makes the path absolute against the current directory.
	 */
	private static final Pattern MADE_ABSOLUTE = Pattern.compile(
		"^\\s*\\.\\s*(?:toAbsolutePath|toRealPath|getAbsoluteFile|getAbsolutePath|getCanonicalFile|getCanonicalPath)" +
		"\\s*\\(");
	/**
	 * Matches the code before a path call whose result is the directory of a new temporary file or directory.
	 */
	private static final Pattern TEMPORARY_PARENT = Pattern.compile("createTemp(?:Directory|File)\\s*\\(\\s*$");
	/**
	 * Matches the code before a literal that names a system property or environment variable that is read.
	 */
	private static final Pattern READ_CALL = Pattern.compile("(?:getProperty|getenv)\\s*\\(\\s*$");
	/**
	 * Matches a read of the whole process environment.
	 */
	private static final Pattern WHOLE_ENVIRONMENT = Pattern.compile("getenv(?<![\\w$]getenv)\\s*\\(\\s*\\)");
	/**
	 * The system properties and environment variables that name the user, the home directory or the current directory.
	 */
	private static final Set<String> USER_NAMES = Set.of("HOME", "USERPROFILE", "user.home", "user.dir", "user.name");
	/**
	 * Matches an initializer that creates a mutable array, collection, map or builder.
	 */
	private static final Pattern MUTABLE_VALUE = Pattern.compile("^(?:\\{|new\\s+[\\w$.]+\\s*(?:<[^>]*>\\s*)?\\[|" +
		"new\\s+(?:java\\s*\\.\\s*util\\s*\\.\\s*)?(?:ArrayList|LinkedList|HashMap|LinkedHashMap|TreeMap|HashSet|" +
		"LinkedHashSet|TreeSet|EnumMap|ArrayDeque|StringBuilder|ConcurrentHashMap|CopyOnWriteArrayList)\\s*[<(]|" +
		"(?:PosixFilePermissions\\s*\\.\\s*fromString|Arrays\\s*\\.\\s*asList|" +
		"EnumSet\\s*\\.\\s*(?:of|noneOf|allOf|copyOf|range)|" +
		"Collectors\\s*\\.\\s*to(?:List|Set|Map))\\s*\\()");
	/**
	 * The test sources, relative to the {@code client} directory, and the literal path that each may use although it
	 * names the current directory. Each literal is a value under test, a symbolic link's target, or the working
	 * directory of a fixture process that the code under test starts in a directory it chooses.
	 */
	private static final Set<String> PATH_VALUES = Set.of(
		// The empty path is the input whose text the test checks
		"common/cli/src/test/java/com/holdmyspot/cat/common/test/PortablePathsTest.java|",
		// A link to the directory that holds it
		"common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/workspace/test/LinkReportTest.java|.",
		"common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/workspace/test/StagedSourceTest.java|.",
		// The plugin directory that a fake reports before a test sets one; only its text reaches the hooks
		"common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/cli/test/OptimizerCoreTest.java|",
		// A gate command run in its own process, whose working directory the gate runner sets to the worktree
		"common/cli/src/test/java/com/holdmyspot/cat/common/git/replay/test/GateCommandFixture.java|.");

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
			"com/holdmyspot/cat/common/optimizer/statistics/BettingProcess.java"));
		requireThat(scanned, "scanned").contains(auditSource(client));
	}

	/**
	 * Verifies that the test-suite checks scan the test sources of every Java module, so that a test outside the
	 * optimizer cannot escape them.
	 */
	@Test
	public void scanCoversEveryModule()
	{
		// Requirement: R-T1
		Path client = clientDirectory();
		List<Path> scanned = Scan.MODULE_FILES.stream().map(SourceFile::path).toList();
		for (String source : List.of(
			"codex/cli/src/test/java/com/holdmyspot/cat/codex/runner/test/CodexProcessTest.java",
			"claude/cli/src/test/java/com/holdmyspot/cat/claude/runner/test/MaintenanceTest.java",
			"common/cli/src/test/java/com/holdmyspot/cat/common/test/HookScopeTest.java",
			"common/distribution/src/test/java/com/holdmyspot/cat/distribution/bundle/test/JlinkImageBuilderTest.java"))
		{
			requireThat(scanned, "scanned").contains(client.resolve(source));
		}
	}

	/**
	 * Verifies that no static field of a test source, or of a production source in the optimizer scope, is mutable.
	 */
	@Test
	public void staticFieldsAreFinal()
	{
		// Requirement: R-T1
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.MODULE_FILES)
		{
			if (!file.isProduction() || Scan.FILES.contains(file))
				violations.addAll(findMutableStaticFields(file));
		}
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that every test class has only final fields.
	 */
	@Test
	public void testClassFieldsAreFinal()
	{
		// Requirement: R-T1
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.MODULE_FILES)
			violations.addAll(findMutableTestClassFields(file));
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that no field that tests share holds a mutable value.
	 */
	@Test
	public void sharedFixturesAreImmutable()
	{
		// Requirement: R-T1
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.MODULE_FILES)
			violations.addAll(findMutableSharedFixtures(file));
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that no test source depends on the current directory, the user's home directory, the user's name or
	 * the whole process environment.
	 */
	@Test
	public void testsReadNoHostLocation()
	{
		// Requirement: R-T1
		Path client = clientDirectory();
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.MODULE_FILES)
			violations.addAll(findHostReads(file, allowedPathValues(client, file)));
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that only a {@code main} method creates a sleeping pause or waiting, and that no test calls the
	 * {@code main} method of a production class, so that no unit test reaches the sleeping pause.
	 */
	@Test
	public void onlyMainCreatesSleepingPause()
	{
		// Requirement: R-T1
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.MODULE_FILES)
		{
			violations.addAll(findSleepingOutsideMain(file));
			violations.addAll(findProductionMainCalls(file, Scan.TEST_TYPES));
		}
		requireThat(violations, "violations").isEmpty();
	}

	/**
	 * Verifies that no source of the Java modules sleeps, except the one that declares {@code Pause.sleeping}.
	 */
	@Test
	public void sourcesDoNotSleep()
	{
		// Requirement: R-T1
		Path sleepingPause = clientDirectory().resolve(SLEEPING_PAUSE);
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.MODULE_FILES)
		{
			if (!file.path().equals(sleepingPause))
				violations.addAll(findSleeps(file));
		}
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
	 * Verifies that no production source other than the fork adapter constructs {@code Git}.
	 */
	@Test
	public void onlyForkAdapterConstructsGit()
	{
		// Requirement: R-T1
		Path adapter = clientDirectory().resolve(FORK_ADAPTER);
		List<String> violations = new ArrayList<>();
		for (SourceFile file : Scan.FILES)
		{
			if (!file.path().equals(adapter))
				violations.addAll(findGitConstructions(file));
		}
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
	 * Verifies that the shared-fixture check reports the mutable values of a test class's fields and of any test
	 * source's static fields, and accepts immutable copies, local variables and production sources.
	 */
	@Test
	public void reportsMutableSharedFixtures()
	{
		// Requirement: R-T1
		String text = """
			public final class SampleTest
			{
				private static final byte[] BYTES = {1, 2};
				private static final Set<PosixFilePermission> MODE = PosixFilePermissions.fromString("rw-r--r--");
				private static final Set<PosixFilePermission> COPY = Set.copyOf(PosixFilePermissions.fromString("rwx"));
				private final List<String> calls = new ArrayList<>();
				private final int[] counts = new int[2];
				private static final List<String> NAMES = List.of("a");
				void run()
				{
					List<String> local = new ArrayList<>();
				}
			}""";
		requireThat(findMutableSharedFixtures(new SourceFile(Path.of("SampleTest.java"), false, false,
			JavaSource.parse(text))), "violations").isEqualTo(List.of(
			"SampleTest.java:3: shared fixture is mutable: private static final byte[] BYTES",
			"SampleTest.java:4: shared fixture is mutable: private static final Set<PosixFilePermission> MODE",
			"SampleTest.java:6: shared fixture is mutable: private final List<String> calls",
			"SampleTest.java:7: shared fixture is mutable: private final int[] counts"));
		String helper = text.replace("class SampleTest", "class Sample");
		requireThat(findMutableSharedFixtures(sample(false, false, helper)), "helperViolations").isEqualTo(List.of(
			"Sample.java:3: shared fixture is mutable: private static final byte[] BYTES",
			"Sample.java:4: shared fixture is mutable: private static final Set<PosixFilePermission> MODE"));
		requireThat(findMutableSharedFixtures(sample(true, false, helper)), "productionViolations").isEmpty();
	}

	/**
	 * Verifies that the host check reports test code that names the current directory, makes a relative path absolute,
	 * creates a temporary directory below a relative one, or reads the user's home, directory or name or the whole
	 * environment, and ignores comments, other literals, allowed values and production sources.
	 */
	@Test
	public void reportsHostReads()
	{
		// Requirement: R-T1
		String text = """
			class Sample
			{
				void run() throws Exception
				{
					Path a = Path.of("");
					Path b = Paths.get(".");
					Path c = Path.of("artifact/bin").toAbsolutePath();
					Path d = Files.createTempDirectory(Path.of("target"), "x-");
					String e = System.getProperty("user.home");
					String f = System.getenv("HOME");
					String g = System.getProperty("user.name");
					Map<String, String> h = System.getenv();
					// Path.of("")
					Path i = Path.of("/absolute").toAbsolutePath();
					Path j = Path.of("relative");
					Map<String, String> k = Map.of("HOME", "/fake");
					String l = System.getenv("PATH");
				}
			}""";
		requireThat(findHostReads(sample(false, false, text), Set.of()), "violations").isEqualTo(List.of(
			"Sample.java:5: names the current directory: \"\"",
			"Sample.java:6: names the current directory: \".\"",
			"Sample.java:7: resolves a relative path against the current directory: artifact/bin",
			"Sample.java:8: resolves a relative path against the current directory: target",
			"Sample.java:9: reads the host's user.home",
			"Sample.java:10: reads the host's HOME",
			"Sample.java:11: reads the host's user.name",
			"Sample.java:12: reads the whole process environment"));
		requireThat(findHostReads(sample(false, false, text), Set.of("", ".")), "allowedViolations").isEqualTo(List.of(
			"Sample.java:7: resolves a relative path against the current directory: artifact/bin",
			"Sample.java:8: resolves a relative path against the current directory: target",
			"Sample.java:9: reads the host's user.home",
			"Sample.java:10: reads the host's HOME",
			"Sample.java:11: reads the host's user.name",
			"Sample.java:12: reads the whole process environment"));
		requireThat(findHostReads(sample(true, false, text), Set.of()), "productionViolations").isEmpty();
	}

	/**
	 * Verifies that the sleeping-pause check reports references outside a {@code main} method's body and the calls of
	 * a test to a production class's {@code main}, but not a test fixture's {@code main}.
	 */
	@Test
	public void reportsSleepingOutsideMain()
	{
		// Requirement: R-T1
		String text = """
			class Sample
			{
				public static void main(String[] args)
				{
					run(Pause::sleeping);
					if (args.length > 0)
					{
						run(Pause.sleeping(Duration.ofMillis(1)));
					}
				}
				static Waiting waiting()
				{
					return Pause::sleeping;
				}
				// Pause::sleeping
				static Pause fixed = Pause.sleeping(Duration.ZERO);
			}""";
		requireThat(findSleepingOutsideMain(sample(true, false, text)), "violations").isEqualTo(List.of(
			"Sample.java:13: creates a sleeping pause outside main: Pause::sleeping",
			"Sample.java:16: creates a sleeping pause outside main: Pause.sleeping"));
		String calls = """
			class SampleTest
			{
				void run() throws Exception
				{
					RunnerCli.main(new String[0]);
					FixtureProcess.main(new String[0]);
					runner.main(new String[0]);
				}
			}""";
		Set<String> testTypes = Set.of("com.holdmyspot.cat.test.FixtureProcess");
		requireThat(findProductionMainCalls(sample(false, false, calls), testTypes), "mainCalls").isEqualTo(List.of(
			"Sample.java:5: calls the main method of a production class: RunnerCli"));
		requireThat(findProductionMainCalls(sample(true, false, calls), testTypes), "productionCalls").isEmpty();
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
			import com.holdmyspot.cat.claude.Launcher;
			// com.holdmyspot.cat.codex.Launcher
			class Sample
			{
				Object create()
				{
					return new com.holdmyspot.cat.codex.Launcher();
				}
			}""";
		requireThat(findHarnessReferences(sample(true, true, text)), "coreViolations").isEqualTo(List.of(
			"Sample.java:1: references a harness package: com.holdmyspot.cat.claude",
			"Sample.java:7: references a harness package: com.holdmyspot.cat.codex"));
		requireThat(findHarnessReferences(sample(true, false, text)), "adapterViolations").isEmpty();
	}

	/**
	 * Verifies that the test-support check reports references from production sources only.
	 */
	@Test
	public void reportsTestSupportUseInProduction()
	{
		// Requirement: R-T2
		Set<String> testTypes = Set.of("com.holdmyspot.cat.common.optimizer.FakeStore");
		String text = """
			import com.holdmyspot.cat.common.optimizer.FakeStore;
			import static com.holdmyspot.cat.common.optimizer.FakeStore.create;
			class Sample
			{
				Object store = com.holdmyspot.cat.common.optimizer.Store.open();
			}""";
		requireThat(findTestSupportReferences(sample(true, true, text), testTypes), "productionViolations").
			isEqualTo(List.of(
				"Sample.java:1: references test support: com.holdmyspot.cat.common.optimizer.FakeStore",
				"Sample.java:2: references test support: com.holdmyspot.cat.common.optimizer.FakeStore"));
		requireThat(findTestSupportReferences(sample(false, true, text), testTypes), "testViolations").isEmpty();
	}

	/**
	 * Verifies that the Git check reports constructor calls and references in production sources only, and not
	 * comments, strings or other types whose names end with {@code Git}.
	 */
	@Test
	public void reportsGitConstructions()
	{
		// Requirement: R-T1
		String text = """
			class Sample
			{
				Object first = new Git(project);
				Object second = new com.holdmyspot.cat.common.git.Git(project);
				Function<Path, Object> third = Git::new;
				Object other = new TopicGit(project);
				// new Git(project)
				String text = "new Git(project)";
			}""";
		requireThat(findGitConstructions(sample(true, true, text)), "productionViolations").isEqualTo(List.of(
			"Sample.java:3: constructs Git",
			"Sample.java:4: constructs Git",
			"Sample.java:5: constructs Git"));
		requireThat(findGitConstructions(sample(false, true, text)), "testViolations").isEmpty();
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
		return client.resolve("common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/test/" +
			"TestabilityAuditTest.java");
	}

	/**
	 * Parses every source file of the Java modules.
	 *
	 * @param client the {@code client} directory
	 * @return the files
	 * @throws IOException if a source cannot be read
	 */
	private static List<SourceFile> scanModules(Path client) throws IOException
	{
		List<SourceFile> files = new ArrayList<>();
		for (String module : MODULES)
		{
			for (String sourceSet : List.of("main", "test"))
			{
				Path root = client.resolve(module).resolve("src").resolve(sourceSet).resolve("java");
				boolean isProduction = sourceSet.equals("main");
				boolean isCore = module.equals(CORE_MODULE);
				for (Path path : listJavaFiles(root))
					files.add(new SourceFile(path, isProduction, isCore, JavaSource.parse(Files.readString(path))));
			}
		}
		return List.copyOf(files);
	}

	/**
	 * Returns the source files in the audit's scope: those of the optimizer packages and the harness capacity files.
	 *
	 * @param client the {@code client} directory
	 * @param files  every source file of the Java modules
	 * @return the files in the audit's scope
	 */
	private static List<SourceFile> selectAudited(Path client, List<SourceFile> files)
	{
		List<Path> roots = new ArrayList<>();
		for (String module : MODULES)
		{
			for (String sourceSet : List.of("main", "test"))
				roots.add(client.resolve(module).resolve("src").resolve(sourceSet).resolve("java"));
		}
		List<SourceFile> audited = new ArrayList<>();
		for (SourceFile file : files)
		{
			for (Path root : roots)
			{
				if (isAudited(root, file.path()))
					audited.add(file);
			}
		}
		return List.copyOf(audited);
	}

	/**
	 * Indicates whether a source file is in the audit's scope.
	 *
	 * @param root the source root
	 * @param path the source file
	 * @return {@code true} if {@code path} is below an optimizer package of {@code root}, or is a harness capacity file
	 *   directly in its runner package
	 */
	private static boolean isAudited(Path root, Path path)
	{
		for (String optimizerPackage : OPTIMIZER_PACKAGES)
		{
			if (path.startsWith(root.resolve(optimizerPackage)))
				return true;
		}
		return path.getParent().equals(root.resolve(RUNNER_PACKAGE)) &&
			path.getFileName().toString().startsWith("HarnessCapacity");
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
	 * Returns the shared fields of a test source that hold a mutable value: every field of a test class, which TestNG
	 * shares between its test methods, and every static field of any test source.
	 *
	 * @param file the file
	 * @return a description of each violation, or an empty list if the file is a production source
	 */
	private static List<String> findMutableSharedFixtures(SourceFile file)
	{
		if (file.isProduction())
			return List.of();
		boolean isTestClass = file.path().getFileName().toString().endsWith("Test.java");
		List<String> violations = new ArrayList<>();
		for (JavaSource.Field field : file.source().fields())
		{
			boolean isShared = field.isStatic() || (isTestClass && field.isTopLevel());
			boolean isMutable = field.declaration().contains("[]") ||
				MUTABLE_VALUE.matcher(field.initializer()).find();
			if (isShared && isMutable)
				violations.add(file.describe(field.offset(), "shared fixture is mutable: " + field.declaration()));
		}
		return violations;
	}

	/**
	 * Returns the literal paths that a test source may use although they name the current directory.
	 *
	 * @param client the {@code client} directory
	 * @param file   the file
	 * @return the allowed literal values
	 */
	private static Set<String> allowedPathValues(Path client, SourceFile file)
	{
		String prefix = client.relativize(file.path()).toString().replace(file.path().getFileSystem().getSeparator(),
			"/") + "|";
		Set<String> values = new HashSet<>();
		for (String entry : PATH_VALUES)
		{
			if (entry.startsWith(prefix))
				values.add(entry.substring(prefix.length()));
		}
		return values;
	}

	/**
	 * Returns the places where test code depends on the host: it names the current directory, resolves a relative
	 * path against it, or reads the user's home directory, the user's name or the whole process environment.
	 *
	 * @param file          the file
	 * @param allowedValues the literal paths that the file may use although they name the current directory
	 * @return a description of each violation in source order, or an empty list if the file is a production source
	 */
	private static List<String> findHostReads(SourceFile file, Set<String> allowedValues)
	{
		if (file.isProduction())
			return List.of();
		String code = file.source().code();
		List<Violation> violations = new ArrayList<>();
		for (JavaSource.Literal literal : file.source().literals())
		{
			String before = code.substring(Math.max(0, literal.offset() - 80), literal.offset());
			String value = literal.value();
			if (PATH_CALL.matcher(before).find())
			{
				if ((value.isEmpty() || value.equals(".")) && !allowedValues.contains(value))
				{
					violations.add(new Violation(literal.offset(), "names the current directory: \"" + value + "\""));
					continue;
				}
				if (isRelative(value) && !value.isEmpty() && !value.equals("."))
				{
					int open = code.lastIndexOf('(', literal.offset());
					int close = closingParenthesis(code, open);
					String head = code.substring(Math.max(0, open - 80), open);
					String after = code.substring(close + 1, Math.min(code.length(), close + 80));
					boolean isTemporaryParent = TEMPORARY_PARENT.matcher(head.substring(0,
						head.length() - pathCallLength(head))).find();
					if (MADE_ABSOLUTE.matcher(after).find() || isTemporaryParent)
					{
						violations.add(new Violation(literal.offset(),
							"resolves a relative path against the current directory: " + value));
					}
				}
			}
			else if (USER_NAMES.contains(value) && READ_CALL.matcher(before).find())
				violations.add(new Violation(literal.offset(), "reads the host's " + value));
		}
		Matcher whole = WHOLE_ENVIRONMENT.matcher(code);
		while (whole.find())
			violations.add(new Violation(whole.start(), "reads the whole process environment"));
		violations.sort(Comparator.comparingInt(Violation::offset));
		return violations.stream().map(violation -> file.describe(violation.offset(), violation.message())).toList();
	}

	/**
	 * Returns the length of the path call at the end of the code before its argument.
	 *
	 * @param head the code before the path call's opening parenthesis
	 * @return the length of the call's name, which ends {@code head}
	 */
	private static int pathCallLength(String head)
	{
		Matcher matcher = Pattern.compile("(?:Path\\s*\\.\\s*of|Paths\\s*\\.\\s*get|new\\s+File)\\s*$").matcher(head);
		if (matcher.find())
			return head.length() - matcher.start();
		return 0;
	}

	/**
	 * Indicates whether a literal path is relative on every platform.
	 *
	 * @param value the literal
	 * @return {@code false} if it starts with a separator or a drive letter
	 */
	private static boolean isRelative(String value)
	{
		return !value.startsWith("/") && !value.startsWith("\\") && !value.matches("^[A-Za-z]:.*");
	}

	/**
	 * Returns the offset of the parenthesis that closes an opening one in blanked code.
	 *
	 * @param code the blanked code
	 * @param open the offset of the opening parenthesis
	 * @return the offset of the closing parenthesis, or the last offset if there is none
	 */
	private static int closingParenthesis(String code, int open)
	{
		int depth = 0;
		for (int i = open; i < code.length(); ++i)
		{
			char next = code.charAt(i);
			if (next == '(')
				++depth;
			else if (next == ')')
			{
				--depth;
				if (depth == 0)
					return i;
			}
		}
		return code.length() - 1;
	}

	/**
	 * Returns a file's references to the sleeping pause outside the body of a {@code main} method.
	 *
	 * @param file the file
	 * @return a description of each violation
	 */
	private static List<String> findSleepingOutsideMain(SourceFile file)
	{
		String code = file.source().code();
		if (!code.contains("sleeping"))
			return List.of();
		List<int[]> bodies = new ArrayList<>();
		Matcher main = MAIN.matcher(code);
		while (main.find())
		{
			int brace = code.indexOf('{', closingParenthesis(code, main.end() - 1));
			if (brace >= 0)
				bodies.add(new int[]{brace, closingBrace(code, brace)});
		}
		List<String> violations = new ArrayList<>();
		Matcher matcher = SLEEPING_REFERENCE.matcher(code);
		while (matcher.find())
		{
			int offset = matcher.start();
			boolean inMain = bodies.stream().anyMatch(body -> offset > body[0] && offset < body[1]);
			if (!inMain)
			{
				violations.add(file.describe(offset, "creates a sleeping pause outside main: " +
					matcher.group().replaceAll("\\s+", "")));
			}
		}
		return violations;
	}

	/**
	 * Returns the offset of the brace that closes an opening one in blanked code.
	 *
	 * @param code the blanked code
	 * @param open the offset of the opening brace
	 * @return the offset of the closing brace, or the last offset if there is none
	 */
	private static int closingBrace(String code, int open)
	{
		int depth = 0;
		for (int i = open; i < code.length(); ++i)
		{
			char next = code.charAt(i);
			if (next == '{')
				++depth;
			else if (next == '}')
			{
				--depth;
				if (depth == 0)
					return i;
			}
		}
		return code.length() - 1;
	}

	/**
	 * Returns a test file's calls to the {@code main} method of a production class, which may create the sleeping
	 * pause.
	 *
	 * @param file      the file
	 * @param testTypes the qualified names of the types in every test source tree
	 * @return a description of each violation, or an empty list if the file is a production source
	 */
	private static List<String> findProductionMainCalls(SourceFile file, Set<String> testTypes)
	{
		if (file.isProduction())
			return List.of();
		Set<String> testNames = new HashSet<>();
		for (String type : testTypes)
			testNames.add(type.substring(type.lastIndexOf('.') + 1));
		List<String> violations = new ArrayList<>();
		Matcher matcher = MAIN_CALL.matcher(file.source().code());
		while (matcher.find())
		{
			if (!testNames.contains(matcher.group(1)))
			{
				violations.add(file.describe(matcher.start(), "calls the main method of a production class: " +
					matcher.group(1)));
			}
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
		String code = file.source().code();
		// Every match contains the literal, which a plain search finds faster than the expression
		if (!code.contains("sleep"))
			return List.of();
		List<String> violations = new ArrayList<>();
		Matcher matcher = SLEEP.matcher(code);
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
			violations.add(file.describe(matcher.start(), "references a harness package: com.holdmyspot.cat." +
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
	 * Returns a production file's constructions of {@code Git}.
	 *
	 * @param file the file
	 * @return a description of each violation in source order, or an empty list if the file is not a production
	 *   source
	 */
	private static List<String> findGitConstructions(SourceFile file)
	{
		if (!file.isProduction())
			return List.of();
		List<Integer> offsets = new ArrayList<>();
		for (Pattern pattern : List.of(NEW_GIT, GIT_CONSTRUCTOR_REFERENCE))
		{
			Matcher matcher = pattern.matcher(file.source().code());
			while (matcher.find())
				offsets.add(matcher.start());
		}
		offsets.sort(Comparator.naturalOrder());
		List<String> violations = new ArrayList<>();
		for (int offset : offsets)
			violations.add(file.describe(offset, "constructs Git"));
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
		 * Every source file of the Java modules.
		 */
		static final List<SourceFile> MODULE_FILES;
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
				MODULE_FILES = scanModules(client);
				FILES = selectAudited(client, MODULE_FILES);
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
