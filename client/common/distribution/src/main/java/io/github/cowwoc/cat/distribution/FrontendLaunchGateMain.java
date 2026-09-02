package io.github.cowwoc.cat.distribution;

import io.github.cowwoc.cat.common.Fingerprints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Aggregates the fixed frontend launch-gate receipts into a release report.
 */
public final class FrontendLaunchGateMain
{
	/**
	 * Prevents construction of this command owner.
	 */
	private FrontendLaunchGateMain()
	{
	}

	/**
	 * Runs the aggregator and fails the invoking process when a required gate did not pass or receive authorization.
	 *
	 * @param args the release version, report path, ten receipt paths, and optional handoff inputs
	 * @throws IOException if a receipt or report cannot be processed
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length == 3 && args[0].equals("create-attempt"))
		{
			System.out.println(FrontendLaunchGate.createAttemptDirectory(Path.of(args[1]), args[2]));
			return;
		}
		if (run(args) != 0)
			throw new IOException("Frontend launch gate contains failed or unrun required gates");
	}

	/**
	 * Aggregates the supplied receipts.
	 *
	 * @param args the release version, report path, ten receipt paths, and optional handoff inputs
	 * @return zero when every gate passed; otherwise one
	 * @throws IOException if a receipt or report cannot be processed
	 */
	static int run(String[] args) throws IOException
	{
		if (args != null && args.length == 3 && args[0].equals("create-attempt"))
		{
			FrontendLaunchGate.createAttemptDirectory(Path.of(args[1]), args[2]);
			return 0;
		}
		if (args != null && args.length > 0 && args[0].equals("record"))
		{
			recordReceipt(args);
			return 0;
		}
		int receiptArgumentCount = FrontendLaunchGate.requiredIdentifiers().size() + 2;
		int handoffArgumentCount = receiptArgumentCount + 6;
		if (args == null || (args.length != receiptArgumentCount && args.length != handoffArgumentCount))
			throw new IllegalArgumentException("Usage: FrontendLaunchGateMain <version> <report-path> <receipt-path>... " +
				"[--handoff <personal-tree> <commercial-tree> <commit> <license-version> <offer-catalog>]");
		String version = args[0];
		Path reportPath = Path.of(args[1]);
		Map<FrontendLaunchGate.Identifier, Path> receiptPaths = new EnumMap<>(FrontendLaunchGate.Identifier.class);
		for (int index = 0; index < FrontendLaunchGate.requiredIdentifiers().size(); ++index)
			receiptPaths.put(FrontendLaunchGate.requiredIdentifiers().get(index), Path.of(args[index + 2]));
		if (args.length == handoffArgumentCount && args[receiptArgumentCount].equals("--exclude"))
			writeExcludedReceipt(args, receiptArgumentCount, receiptPaths);
		FrontendLaunchGate.Report report = FrontendLaunchGate.writeReport(new JsonMapper(), version, reportPath,
			receiptPaths);
		if (!report.entries().stream().allMatch(entry -> entry.status() == FrontendLaunchGate.Status.PASSED))
			return 1;
		if (args.length == handoffArgumentCount && args[receiptArgumentCount].equals("--handoff"))
			writeBackendHandoff(args, receiptArgumentCount, report);
		return 0;
	}

	/**
	 * Writes the backend handoff after all required frontend evidence passed.
	 *
	 * @param args all command-line arguments
	 * @param firstOption the first optional-argument index
	 * @param report the passed aggregate report
	 * @throws IOException if the handoff cannot be written
	 */
	private static void writeBackendHandoff(String[] args, int firstOption, FrontendLaunchGate.Report report)
		throws IOException
	{
		if (!args[firstOption].equals("--handoff"))
			throw new IllegalArgumentException("The only launch-gate option is --handoff");
		Path destination = Path.of(args[1]).toAbsolutePath().normalize().getParent().resolve("backend-handoff.json");
		BackendHandoff.write(new JsonMapper(), report, destination, Path.of(args[1]), Path.of(args[firstOption + 1]),
			Path.of(args[firstOption + 2]), args[firstOption + 3], args[firstOption + 4], Path.of(args[firstOption + 5]));
	}

	/**
	 * Records one shell-command outcome as an immutable receipt.
	 *
	 * @param args the record subcommand arguments
	 * @throws IOException if passed evidence cannot be fingerprinted or the receipt cannot be written
	 */
	private static void recordReceipt(String[] args) throws IOException
	{
		if (args.length < 8)
			throw new IllegalArgumentException("Usage: FrontendLaunchGateMain record <gate> <receipt-path> <status> " +
				"<command-or-dash> <started-at-or-dash> <completed-at-or-dash> <diagnostic-or-dash> [evidence-path...]");
		FrontendLaunchGate.Identifier identifier = FrontendLaunchGate.Identifier.fromExternalValue(args[1]);
		Path destination = Path.of(args[2]);
		FrontendLaunchGate.Status status = FrontendLaunchGate.Status.fromExternalValue(args[3]);
		String command = nullableText(args[4]);
		Instant startedAt = nullableInstant(args[5]);
		Instant completedAt = nullableInstant(args[6]);
		String diagnostic = nullableText(args[7]);
		List<Path> evidence = new ArrayList<>();
		List<String> fingerprints = new ArrayList<>();
		for (int index = 8; index < args.length; ++index)
		{
			Path path = Path.of(args[index]).toAbsolutePath().normalize();
			evidence.add(path);
			fingerprints.add(Fingerprints.ofFile(path));
		}
		FrontendLaunchGate.writeReceipt(new JsonMapper(), destination, new FrontendLaunchGate.Receipt(identifier, status,
			command, startedAt, completedAt, evidence, fingerprints, diagnostic));
	}

	/**
	 * Converts the command-line null sentinel to an empty value.
	 *
	 * @param value the command-line value
	 * @return an empty value for the sentinel, otherwise the supplied value
	 */
	private static String nullableText(String value)
	{
		if (value.equals("-"))
			return "";
		return value;
	}

	/**
	 * Converts the command-line null sentinel to a timestamp.
	 *
	 * @param value the command-line value
	 * @return {@code null} for the sentinel, otherwise the parsed instant
	 */
	private static Instant nullableInstant(String value)
	{
		if (value.equals("-"))
			return null;
		return Instant.parse(value);
	}

	/**
	 * Writes the sole product-owner-authorized exclusion before report aggregation.
	 *
	 * @param args all command arguments
	 * @param firstOption the index of the first option
	 * @param receiptPaths the registered receipt destinations
	 * @throws IOException if the exclusion receipt cannot be written
	 */
	private static void writeExcludedReceipt(String[] args, int firstOption,
		Map<FrontendLaunchGate.Identifier, Path> receiptPaths) throws IOException
	{
		if (!args[firstOption].equals("--exclude") || !args[firstOption + 2].equals("--authorized-by") ||
			!args[firstOption + 4].equals("--reason"))
			throw new IllegalArgumentException("Exclusion options must be --exclude, --authorized-by, and --reason");
		FrontendLaunchGate.Identifier identifier = FrontendLaunchGate.Identifier.fromExternalValue(args[firstOption + 1]);
		String authorizedBy = args[firstOption + 3];
		String reason = args[firstOption + 5];
		Path destination = receiptPaths.get(identifier).toAbsolutePath().normalize();
		if (Files.exists(destination))
			throw new IOException("Cannot exclude a gate with an existing receipt: " + destination);
		FrontendLaunchGate.writeReceipt(new JsonMapper(), destination, new FrontendLaunchGate.Receipt(identifier,
			FrontendLaunchGate.Status.EXCLUDED, "", null, null, List.of(), List.of(), "Excluded: " + reason,
			authorizedBy, reason));
	}
}
