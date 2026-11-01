# Member names omit owner words

## Variables

- **imported_files**: A kind of file, other than the archive's own files, that the archive reads while it stores an
	invoice and whose layout has its own version number, such as exchange-rate tables

## Scenario

I'm extending `InvoiceArchive`, a class that stores a company's invoices as files in one directory. Each archive file
starts with a version number for the layout of that file, and while it stores an invoice the archive also reads
{{imported_files}}, which carry a version number for their own layout. Suggest a name for each of these new members and
explain each choice briefly.

```java
public final class InvoiceArchive
{
	/** The layout version that this archive writes at the start of each of its files. */
	private static final int ??? = 3;
	/** The layout version of the {{imported_files}} that this archive can read. */
	private static final int ??? = 2;

	/**
	 * Deletes every stored invoice issued before a date.
	 *
	 * @param cutoff the earliest issue date to keep
	 * @return the number of invoices deleted
	 * @throws IOException if a file cannot be deleted
	 */
	public int ???(LocalDate cutoff) throws IOException { ... }
}
```

Callers outside the class write, for example, `archive.???(LocalDate.of(2020, 1, 1))`.

## Assertions

### owner_words_omitted_from_constant

- **kind**: effect
- **description**: The proposed name of the constant for the layout version of the archive's own files contains no
	word that `InvoiceArchive` already supplies, such as `INVOICE` or `ARCHIVE`; for example, `FORMAT_VERSION`.

### owner_words_omitted_from_method

- **kind**: effect
- **description**: The proposed name of the method that deletes the invoices issued before a date contains no word
	that `InvoiceArchive` already supplies, such as `Invoice`, `Invoices`, or `Archive`; for example, `deleteBefore` or
	`deleteIssuedBefore`.

### distinct_file_kind_named

- **kind**: guard
- **description**: The proposed name of the constant for the layout version of the {{imported_files}} keeps a word that
	names those files, so that it cannot be read as the version of the archive's own files.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/identifier-naming.md`
	before its first governed action.
