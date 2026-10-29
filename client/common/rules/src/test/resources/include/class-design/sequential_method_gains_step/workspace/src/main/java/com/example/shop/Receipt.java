package com.example.shop;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A completed sale.
 *
 * @param store    the store's name
 * @param sold     when the sale completed
 * @param lines    the items sold
 * @param discount the amount taken off the subtotal, or zero
 * @param tax      the tax charged after the discount
 */
public record Receipt(String store, LocalDateTime sold, List<Line> lines, BigDecimal discount, BigDecimal tax)
{
	/**
	 * One item of a sale.
	 *
	 * @param name      the item's name
	 * @param quantity  how many were sold
	 * @param unitPrice the price of one
	 */
	public record Line(String name, int quantity, BigDecimal unitPrice)
	{
		/**
		 * @return the price of all of them
		 */
		public BigDecimal amount()
		{
			return unitPrice.multiply(BigDecimal.valueOf(quantity));
		}
	}

	/**
	 * @return the sum of the lines' amounts
	 */
	public BigDecimal subtotal()
	{
		BigDecimal sum = BigDecimal.ZERO;
		for (Line line : lines)
			sum = sum.add(line.amount());
		return sum;
	}

	/**
	 * @return the amount the customer pays
	 */
	public BigDecimal total()
	{
		return subtotal().subtract(discount).add(tax);
	}
}
