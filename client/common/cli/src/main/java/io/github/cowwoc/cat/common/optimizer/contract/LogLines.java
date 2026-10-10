package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code log_lines} value generator: lines that look like real application logs, such as
 * {@code 2025-02-11T08:14:03.512Z INFO  billing.InvoiceService - processed batch 4821 in 37 ms}.
 * <p>
 * Each line looks real by itself, in format, words and token density; the lines as a whole mean nothing. Their
 * timestamps increase and lie within 2025.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class LogLines implements ValueGenerator
{
	/**
	 * The generator.
	 */
	public static final LogLines INSTANCE = new LogLines();
	/**
	 * The most lines that one value may request.
	 */
	public static final long MAXIMUM_COUNT = 1_000_000;
	/**
	 * The name of the parameter that holds the number of lines.
	 */
	private static final String COUNT = "count";
	/**
	 * The first instant that a value may start at.
	 */
	private static final long FIRST_START = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
	/**
	 * The number of milliseconds within which a value starts. Ending a month before 2026 leaves room for
	 * {@link #MAXIMUM_COUNT} lines at the largest step.
	 */
	private static final long START_SPAN = Instant.parse("2025-12-01T00:00:00Z").toEpochMilli() - FIRST_START;
	/**
	 * The largest step, in milliseconds, between two consecutive timestamps.
	 */
	private static final int MAXIMUM_STEP = 2000;
	/**
	 * Formats a timestamp in UTC with milliseconds.
	 */
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
		Locale.ROOT).withZone(ZoneOffset.UTC);
	/**
	 * The smallest identifier that a line contains.
	 */
	private static final int FIRST_ID = 1000;
	/**
	 * The number of distinct identifiers that a line may contain.
	 */
	private static final int ID_COUNT = 9_000_000;
	/**
	 * Matches a slot of a message template, capturing its name.
	 */
	private static final Pattern SLOT = Pattern.compile("<([a-z]+)>");
	/**
	 * Matches the separators between the words of a line.
	 */
	private static final Pattern NON_LETTERS = Pattern.compile("[^A-Za-z]+");
	/**
	 * The loggers that a line names.
	 */
	private static final List<String> LOGGERS = List.of("billing.InvoiceService", "billing.PaymentGateway",
		"auth.SessionManager", "auth.TokenValidator", "orders.OrderController", "orders.CheckoutService",
		"inventory.StockRepository", "inventory.WarehouseClient", "http.RequestLogger", "db.ConnectionPool",
		"cache.RedisCache", "jobs.ReportScheduler", "mail.NotificationSender", "search.IndexUpdater");
	/**
	 * The message templates of {@code INFO} lines. A slot such as {@code <id>} is filled when the line is written.
	 */
	private static final List<String> INFO = List.of(
		"processed batch <id> in <ms> ms",
		"GET <path> completed with status 200 in <ms> ms",
		"POST <path> completed with status 201 in <ms> ms",
		"user <user> signed in from <ip>",
		"created <entity> <id> for account <id>",
		"cache hit ratio <percent> percent over last <count> requests",
		"scheduled job <job> finished in <ms> ms",
		"sent <count> notifications to queue <queue>",
		"connection pool size <count>, active <count>, idle <count>");
	/**
	 * The message templates of {@code DEBUG} lines.
	 */
	private static final List<String> DEBUG = List.of(
		"loading <entity> <id> from database",
		"cache miss for key <entity>:<id>",
		"acquired connection <id> after <ms> ms",
		"request headers parsed for <path>",
		"retry policy allows <count> more attempts");
	/**
	 * The message templates of {@code WARN} lines.
	 */
	private static final List<String> WARN = List.of(
		"slow query on table <table> took <ms> ms",
		"retrying request to <host> after timeout, attempt <count>",
		"<entity> <id> not found, returning empty result",
		"queue <queue> depth <count> exceeds soft limit");
	/**
	 * The message templates of {@code ERROR} lines.
	 */
	private static final List<String> ERROR = List.of(
		"failed to update <entity> <id>: connection reset by peer",
		"request to <host> failed with status 503",
		"payment <id> declined by gateway",
		"unhandled exception in job <job>");
	/**
	 * The words that fill each named slot.
	 */
	private static final Map<String, List<String>> WORDS = Map.of(
		"entity", List.of("order", "invoice", "customer", "shipment", "product", "session", "payment", "refund"),
		"path", List.of("/api/orders", "/api/customers", "/api/invoices", "/api/products", "/api/sessions", "/health"),
		"user", List.of("alice", "bruno", "carmen", "dmitri", "erin", "farah", "gustavo", "hana"),
		"host", List.of("inventory-service", "billing-api", "search-cluster", "mail-relay"),
		"queue", List.of("email-outbound", "invoice-render", "order-events", "audit-log"),
		"table", List.of("orders", "invoice_lines", "customers", "stock_levels"),
		"job", List.of("nightly-report", "cache-warmup", "ledger-reconcile", "token-cleanup"));
	/**
	 * Every word of two or more letters that a line may contain after its timestamp.
	 */
	private static final SortedSet<String> VOCABULARY = vocabularyOf();

	/**
	 * Creates the generator.
	 */
	private LogLines()
	{
	}

	/**
	 * Collects the words of the levels, loggers, templates and slot words.
	 *
	 * @return the words, sorted
	 */
	private static SortedSet<String> vocabularyOf()
	{
		SortedSet<String> words = new TreeSet<>();
		List<String> texts = new ArrayList<>(List.of("INFO", "DEBUG", "WARN", "ERROR"));
		texts.addAll(LOGGERS);
		for (List<String> templates : List.of(INFO, DEBUG, WARN, ERROR))
		{
			for (String template : templates)
				texts.add(SLOT.matcher(template).replaceAll(" "));
		}
		for (List<String> slotWords : WORDS.values())
			texts.addAll(slotWords);
		for (String text : texts)
		{
			for (String word : NON_LETTERS.split(text))
			{
				if (word.length() > 1)
					words.add(word);
			}
		}
		return Collections.unmodifiableSortedSet(words);
	}

	@Override
	public String name()
	{
		return "log_lines";
	}

	@Override
	public int currentVersion()
	{
		return 1;
	}

	@Override
	public String describe()
	{
		return "`" + name() + "`: lines that look like an application's log, such as\n" +
			"`2025-02-11T08:14:03.512Z INFO  billing.InvoiceService - processed batch 4821 in 37 ms`. Call it as\n" +
			"`{\"generator\": \"" + name() + "\", \"count\": <lines>}`, with between 1 and " + MAXIMUM_COUNT +
			" lines.\n" +
			"\n" +
			"- Each line starts with a UTC timestamp within 2025, written as in the example. The timestamps increase " +
			"from line to line.\n" +
			"- Every number after the timestamp is a whole number between 0 and " + (FIRST_ID + ID_COUNT - 1) +
			". An IP address, which starts with `10.`, joins four such numbers with dots.\n" +
			"- Words: " + String.join(", ", VOCABULARY) + ".\n" +
			"\n" +
			"A line holds no other word of two or more letters.";
	}

	@Override
	public void validate(int version, Map<String, Long> parameters)
	{
		requireThat(parameters, "parameters").isNotNull();
		if (version != 1)
			throw new IllegalArgumentException(name() + " has no version " + version + ". Versions: 1");
		for (String parameter : new TreeSet<>(parameters.keySet()))
		{
			if (!parameter.equals(COUNT))
			{
				throw new IllegalArgumentException(name() + " version 1 has no parameter '" + parameter +
					"'. Parameters: " + COUNT);
			}
		}
		Long count = parameters.get(COUNT);
		if (count == null)
			throw new IllegalArgumentException(name() + " requires the parameter " + COUNT);
		if (count < 1 || count > MAXIMUM_COUNT)
		{
			throw new IllegalArgumentException(name() + "'s " + COUNT + " must be between 1 and " + MAXIMUM_COUNT +
				", but it is " + count);
		}
	}

	/**
	 * Returns every word of two or more letters that a line may contain after its timestamp, so that a contract author
	 * can write facts that the generated lines never hold.
	 *
	 * @return the words, sorted
	 */
	public SortedSet<String> vocabulary()
	{
		return VOCABULARY;
	}

	@Override
	public void write(int version, long seed, Map<String, Long> parameters, Writer out) throws IOException
	{
		requireThat(out, "out").isNotNull();
		validate(version, parameters);
		long count = parameters.get(COUNT);
		// java.util.Random's algorithm is part of its specification, so the same seed yields the same lines on every JDK
		Random random = new Random(seed);
		long time = FIRST_START + Math.floorMod(random.nextLong(), START_SPAN);
		StringBuilder line = new StringBuilder();
		for (long i = 0; i < count; ++i)
		{
			time += 1 + random.nextInt(MAXIMUM_STEP);
			line.setLength(0);
			TIMESTAMP.formatTo(Instant.ofEpochMilli(time), line);
			int level = random.nextInt(100);
			List<String> templates;
			if (level < 70)
			{
				line.append(" INFO  ");
				templates = INFO;
			}
			else if (level < 85)
			{
				line.append(" DEBUG ");
				templates = DEBUG;
			}
			else if (level < 95)
			{
				line.append(" WARN  ");
				templates = WARN;
			}
			else
			{
				line.append(" ERROR ");
				templates = ERROR;
			}
			line.append(pick(random, LOGGERS)).append(" - ");
			appendMessage(random, pick(random, templates), line);
			line.append('\n');
			out.append(line);
		}
	}

	/**
	 * Returns a random element of a list.
	 *
	 * @param <E>      the type of the elements
	 * @param random   the source of randomness
	 * @param elements the list
	 * @return the element
	 */
	private static <E> E pick(Random random, List<E> elements)
	{
		return elements.get(random.nextInt(elements.size()));
	}

	/**
	 * Appends a template with every slot filled.
	 *
	 * @param random   the source of randomness
	 * @param template the template
	 * @param line     the line that receives the message
	 */
	private static void appendMessage(Random random, String template, StringBuilder line)
	{
		Matcher slot = SLOT.matcher(template);
		int end = 0;
		while (slot.find())
		{
			line.append(template, end, slot.start());
			String name = slot.group(1);
			switch (name)
			{
				case "id" -> line.append(FIRST_ID + random.nextInt(ID_COUNT));
				case "ms" -> line.append(1 + random.nextInt(2000));
				case "count" -> line.append(1 + random.nextInt(500));
				case "percent" -> line.append(random.nextInt(100));
				case "ip" -> line.append("10.").append(random.nextInt(256)).append('.').append(random.nextInt(256)).
					append('.').append(random.nextInt(256));
				default -> line.append(pick(random, WORDS.get(name)));
			}
			end = slot.end();
		}
		line.append(template, end, template.length());
	}
}
