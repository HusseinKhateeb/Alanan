package com.alanan.accounting.purchases.domain;

import com.alanan.accounting.common.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;

public final class PurchaseMath {
    private PurchaseMath() {}
    public static BigDecimal money(BigDecimal amount) { return amount.setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal lineTotal(BigDecimal quantity, BigDecimal cost) {
        BigDecimal total = money(quantity.multiply(cost));
        if (total.signum() <= 0 || total.precision() > 18) fail("PURCHASE_AMOUNT_INVALID");
        return total;
    }
    public static BigDecimal wac(BigDecimal stock, BigDecimal previousCost, BigDecimal incoming, BigDecimal incomingValue) {
        if (stock.signum() < 0 || (stock.signum() > 0 && previousCost == null)) fail("WAC_REVIEW_REQUIRED");
        BigDecimal existingValue = stock.signum() == 0 ? BigDecimal.ZERO : stock.multiply(previousCost);
        return existingValue.add(incomingValue).divide(stock.add(incoming), 6, RoundingMode.HALF_UP);
    }
    public static void fail(String code) { throw new ApiException(HttpStatus.BAD_REQUEST, code); }
}
