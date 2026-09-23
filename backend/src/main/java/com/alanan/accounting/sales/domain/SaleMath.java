package com.alanan.accounting.sales.domain;

import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.sales.api.SaleRequest;
import java.math.*;
import org.springframework.http.HttpStatus;

public final class SaleMath {
    private SaleMath() {}
    public record Line(BigDecimal quantity,BigDecimal total) {}
    public static Line calculate(SaleRequest.Item item,boolean weight) {
        if(weight ? item.mode()==SaleRequest.Mode.BY_PIECE : item.mode()!=SaleRequest.Mode.BY_PIECE) fail("SALE_MODE_INVALID");
        BigDecimal quantity,total;
        if(item.mode()==SaleRequest.Mode.BY_AMOUNT) {
            if(item.requestedAmount()==null || item.quantity()!=null) fail("SALE_INPUT_INVALID");
            total=money(item.requestedAmount());
            quantity=total.divide(item.unitPrice(),3,RoundingMode.HALF_UP);
        } else {
            if(item.quantity()==null || item.requestedAmount()!=null) fail("SALE_INPUT_INVALID");
            quantity=item.quantity().setScale(3,RoundingMode.UNNECESSARY);
            if(!weight && quantity.stripTrailingZeros().scale()>0) fail("WHOLE_PIECES_REQUIRED");
            total=money(quantity.multiply(item.unitPrice()));
        }
        if(quantity.signum()<=0 || quantity.precision()>18 || total.signum()<=0) fail("SALE_AMOUNT_INVALID");
        return new Line(quantity,total);
    }
    public static BigDecimal money(BigDecimal value) {
        BigDecimal result=value.setScale(2,RoundingMode.HALF_UP);
        if(result.precision()>18) fail("SALE_AMOUNT_INVALID");
        return result;
    }
    public static void fail(String code) { throw new ApiException(HttpStatus.BAD_REQUEST,code); }
}
