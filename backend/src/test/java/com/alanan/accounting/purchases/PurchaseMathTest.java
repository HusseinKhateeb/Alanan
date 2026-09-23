package com.alanan.accounting.purchases;
import com.alanan.accounting.purchases.domain.PurchaseMath;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PurchaseMathTest {
    @Test void roundsMoneyHalfUpAndKeepsSixDecimalWac() {
        assertThat(PurchaseMath.lineTotal(new BigDecimal("0.125"),new BigDecimal("1"))).isEqualByComparingTo("0.13");
        assertThat(PurchaseMath.wac(BigDecimal.ZERO,null,new BigDecimal("3"),new BigDecimal("1"))).isEqualByComparingTo("0.333333");
    }
}
