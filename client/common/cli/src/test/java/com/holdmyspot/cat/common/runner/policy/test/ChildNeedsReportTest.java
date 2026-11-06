package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Tests the report of what a child session needs, as the runner of the child's harness writes it with
 * {@link ChildNeedsReport#OPTION}: that the runner that starts the child reads back what was written, refuses a report
 * that is not valid, and refuses a child whose isolated home would copy a login that the parent may not read.
 */
public final class ChildNeedsReportTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ChildNeedsReportTest()
	{
	}

	/**
	 * Verifies that a child needs its run folder, the launch's denied reads, and the writes and hosts that the runner
	 * of the child's harness reports, as the runner that starts the child reads the report back.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the child is refused
	 */
	@Test
	public void needsWhatRunnerReports() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-needs-")).toRealPath();
			Path run = root.resolve("run");
			Path cache = root.resolve("cache");
			Path denied = root.resolve("denied");
			Path login = root.resolve("home/auth.json");
			Path file = root.resolve(ChildNeedsReport.FILE_NAME);
			new ChildNeedsReport(List.of(cache), Set.of("model.example"), List.of(login)).write(json, file);

			ChildNeedsReport report = ChildNeedsReport.read(json, file);
			ChildNeeds needs = report.needs("codex", run, List.of(denied), ParentPolicy.UNSANDBOXED);

			requireThat(report, "report").isEqualTo(new ChildNeedsReport(List.of(cache), Set.of("model.example"),
				List.of(login)));
			requireThat(needs, "needs").isEqualTo(new ChildNeeds(run, List.of(cache), List.of(denied),
				Set.of("model.example")));
		}
	}

	/**
	 * Verifies that a child whose isolated home would copy a login file that the parent's sandbox denies it reading is
	 * refused with the file's name, since the parent could read the copy in the child's run folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesLoginThatParentMayNotRead() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-needs-")).toRealPath();
			Path home = Files.createDirectory(root.resolve("home"));
			Path login = Files.writeString(home.resolve("auth.json"), "{}");
			ParentPolicy parent = new ParentPolicy(true, List.of(root), List.of(), List.of(home), List.of(),
				HostAccess.listed(Set.of("model.example")), false);
			ChildNeedsReport report = new ChildNeedsReport(List.of(), Set.of("model.example"), List.of(login));

			PolicyRefusal refusal = expectThrows(PolicyRefusal.class,
				() -> report.needs("codex", root.resolve("run"), List.of(), parent));

			requireThat(refusal.reason(), "reason").contains(login.toString());
		}
	}

	/**
	 * Returns reports that are not valid, and what the refusal names.
	 *
	 * @return the report's text and the problem
	 */
	@DataProvider
	public Object[][] invalidReports()
	{
		return new Object[][]
			{
				{"not json", "not valid JSON"},
				{"[]", "not a JSON object"},
				{"{\"format_version\":2,\"writes\":[],\"hosts\":[],\"logins\":[]}", "unsupported format_version"},
				{"{\"format_version\":1,\"writes\":[],\"hosts\":[],\"logins\":[],\"extra\":1}", "unknown field"},
				{"{\"format_version\":1,\"hosts\":[],\"logins\":[]}", "lacks the array writes"},
				{"{\"format_version\":1,\"writes\":[1],\"hosts\":[],\"logins\":[]}", "non-text element in writes"},
				{"{\"format_version\":1,\"writes\":[\"relative\"],\"hosts\":[],\"logins\":[]}", "absolute paths"}
			};
	}

	/**
	 * Verifies that a report that is not valid is refused with its problem rather than read as fewer needs.
	 *
	 * @param text    the report's text
	 * @param problem what the refusal names
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "invalidReports")
	public void refusesInvalidReport(String text, String problem) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-needs-")).toRealPath();
			Path file = Files.writeString(root.resolve(ChildNeedsReport.FILE_NAME), text);

			IOException refusal = expectThrows(IOException.class, () -> ChildNeedsReport.read(json, file));

			requireThat(refusal.getMessage(), "message").contains(problem);
		}
	}
}
