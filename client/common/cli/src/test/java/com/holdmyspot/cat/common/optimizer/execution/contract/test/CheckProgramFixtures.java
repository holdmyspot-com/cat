package com.holdmyspot.cat.common.optimizer.execution.contract.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.contract.RecordedTrial;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramEvaluation;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.runner.CheckProgramClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.SkipException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds a prompt test with one check program, and the record of a trial that the program checks, for tests of the
 * routes that run check programs.
 */
public final class CheckProgramFixtures
{
	/**
	 * The identifier of the check-program assertion.
	 */
	public static final String CHECK = "records_service";
	/**
	 * A program that passes only if it cannot read the file that the project's {@code secret.txt} names, and prints the
	 * project's {@code service.txt} and whether the file is readable.
	 */
	public static final String DENIED_READ_PROGRAM = """
		import java.io.IOException;
		import java.nio.file.Files;
		import java.nio.file.Path;

		final class Check
		{
			public static void main(String[] args) throws IOException
			{
				Path input = Path.of(System.getenv("CAT_CHECK_INPUT"));
				Path secret = Path.of(Files.readString(input.resolve("project/secret.txt")).strip());
				boolean readable = true;
				try
				{
					Files.readString(secret);
				}
				catch (IOException e)
				{
					readable = false;
				}
				System.out.println("service " + Files.readString(input.resolve("project/service.txt")).strip() +
					", secret readable: " + readable);
				System.exit(readable ? 1 : 0);
			}
		}
		""";
	private static final String TEST = "formatting";
	private static final String TOOL = "check-program checks/Check.java";

	/**
	 * Skips the running test unless the host can run a check program's own sandbox.
	 *
	 * @throws SkipException if the host is not Linux or has no system Bubblewrap
	 */
	public static void requireBubblewrap()
	{
		if (!Platform.current().isLinux() || Files.notExists(Path.of("/usr/bin/bwrap")))
			throw new SkipException("The check-program sandbox needs Linux and a system Bubblewrap");
	}

	/**
	 * Runs {@link #DENIED_READ_PROGRAM} on a trial of its own through a client, as the evaluation of a campaign owner
	 * does.
	 *
	 * @param client     sends the run
	 * @param json       the input mapper
	 * @param directory  a new empty directory that holds the trial's snapshots and the staged input
	 * @param secretFile the file that the program tries to read
	 * @param heldOut    the absolute paths that the program may not see
	 * @return the program's result
	 * @throws IOException          if the run cannot be staged, sent or answered
	 * @throws InterruptedException if the run is interrupted
	 */
	public static CheckProgram.Result runDeniedRead(CheckProgramClient client, JsonMapper json, Path directory,
		Path secretFile, List<Path> heldOut) throws IOException, InterruptedException
	{
		PromptTest test = promptTest(DENIED_READ_PROGRAM);
		TrialRecord record = deniedReadRecord(Files.createDirectory(directory.resolve("trial")), secretFile);
		Path staging = Files.createDirectory(directory.resolve("staging"));
		return CheckProgramEvaluation.requested(client, json, staging, heldOut).run(test, testCase(test), record, 1).
			get(CHECK).getFirst();
	}

	/**
	 * Returns a prompt test whose only assertion runs a program that has no arguments.
	 *
	 * @param source the program's Java source, a class named {@code Check}
	 * @return the test
	 */
	public static PromptTest promptTest(String source)
	{
		PromptTest formatting = ContractFixtures.contract().findTest(TEST).orElseThrow();
		Requirement requirement = new Requirement(CHECK, "The requested service is recorded.", Requirement.GOAL, null,
			List.of("service_missing"));
		Assertion check = new Assertion(CHECK, AssertionKind.EFFECT, TOOL, OnToolFailure.FAIL,
			requirement.description());
		Path directory = PromptTestLocations.scenarioDirectory(Path.of("prompt.md")).resolve(TEST);
		PromptTest.Draft draft = new PromptTest.Draft(directory, "Check the service saved by the second turn.", Map.of(),
			Map.of(), Set.of(), Map.of("Check.java", new FileContent(source.getBytes(UTF_8))));
		return new PromptTest(TEST, formatting.riskRank(), List.of(CHECK), List.of(),
			"The first turn requests an acknowledgment.", List.of("Reply pass in {{tone}} style.",
			"Record a service for a {{audience}} reader."), formatting.timeLimit(),
			"The assistant acknowledges the request and records the service.", List.of(), formatting.variables(),
			List.of(), formatting.testCases(), List.of(new MappedAssertion(check, CHECK)), List.of(), List.of(),
			Optional.of(draft));
	}

	/**
	 * Returns the first test case of a prompt test.
	 *
	 * @param test the test
	 * @return the case
	 */
	public static TestCase testCase(PromptTest test)
	{
		return test.testCases().getFirst();
	}

	/**
	 * Writes a trial's two project snapshots and returns the trial's record.
	 *
	 * @param directory the new empty directory that holds the snapshots
	 * @param initial   the files of the project before the trial, by path
	 * @param effects   the files of the project after the trial, by path
	 * @return the record
	 * @throws IOException if a snapshot cannot be written
	 */
	public static TrialRecord record(Path directory, Map<String, String> initial, Map<String, String> effects)
		throws IOException
	{
		Path before = write(directory.resolve("initial-snapshot"), initial);
		Path after = write(directory.resolve("effects-snapshot"), effects);
		return new RecordedTrial(List.of(List.of("pass")), new SessionEvidence(List.of()), before, after)
		{
		};
	}

	/**
	 * Returns the record of a trial that {@link #DENIED_READ_PROGRAM} checks.
	 *
	 * @param directory  the new empty directory that holds the snapshots
	 * @param secretFile the file that the program must not be able to read
	 * @return the record, whose final {@code service.txt} holds {@code after}
	 * @throws IOException if a snapshot cannot be written
	 */
	public static TrialRecord deniedReadRecord(Path directory, Path secretFile) throws IOException
	{
		return record(directory, Map.of("service.txt", "before\n", "secret.txt", secretFile + "\n"),
			Map.of("service.txt", "after\n", "secret.txt", secretFile + "\n"));
	}

	/**
	 * Creates a directory that holds a {@code bwrap} that refuses to start, as the one that a nested session's sandbox
	 * leaves a command does.
	 *
	 * @param parent the directory that receives the new directory
	 * @return the new directory, which a command's search path can name
	 * @throws IOException if the program cannot be written
	 */
	public static Path refusingBubblewrap(Path parent) throws IOException
	{
		Path directory = Files.createDirectory(parent.resolve("refusing-bin"));
		Path program = Files.writeString(directory.resolve("bwrap"), """
			#!/bin/sh
			echo "bwrap: No permissions to create new namespace, likely because the kernel refuses user namespaces" >&2
			exit 1
			""");
		Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwxr-xr-x"));
		return directory;
	}

	/**
	 * Writes files into a new directory.
	 *
	 * @param directory the directory to create
	 * @param files     the files by relative path
	 * @return the directory
	 * @throws IOException if a file cannot be written
	 */
	private static Path write(Path directory, Map<String, String> files) throws IOException
	{
		Files.createDirectory(directory);
		for (Map.Entry<String, String> file : files.entrySet())
		{
			Path path = directory.resolve(file.getKey());
			Files.createDirectories(path.getParent());
			Files.writeString(path, file.getValue());
		}
		return directory;
	}

	/**
	 * Prevents construction.
	 */
	private CheckProgramFixtures()
	{
	}
}
