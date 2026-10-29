package com.example.shop;

import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;

/**
 * Prints receipts as plain text.
 */
public final class ReceiptPrinter
{
	private static final DateTimeFormatter SOLD = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final String AMOUNT_LINE = "%-30s%10.2f%n";

	/**
	 * Prints a receipt.
	 *
	 * @param receipt the receipt
	 * @param out     where to print it
	 */
	public void print(Receipt receipt, PrintWriter out)
	{
		out.println(receipt.store());
		out.println(SOLD.format(receipt.sold()));
		out.println("-".repeat(40));

		for (Receipt.Line line : receipt.lines())
			out.printf(AMOUNT_LINE, line.quantity() + " x " + line.name(), line.amount());
		out.println("-".repeat(40));

		out.printf(AMOUNT_LINE, "Subtotal", receipt.subtotal());
		out.printf(AMOUNT_LINE, "Tax", receipt.tax());
		out.printf(AMOUNT_LINE, "Total", receipt.total());
		out.flush();
	}
}
