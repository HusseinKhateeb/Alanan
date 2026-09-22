package com.alanan.accounting.auth;

import org.junit.jupiter.api.Test;
import com.alanan.accounting.common.exception.ApiException;
import static org.assertj.core.api.Assertions.*;

class LoginThrottleTest {
    @Test void limitsBurstAttemptsWithoutBlockingOtherAddresses() {
        var throttle = new LoginThrottle();
        for (int i = 0; i < 20; i++) throttle.check("one");
        assertThatThrownBy(() -> throttle.check("one")).isInstanceOf(ApiException.class)
            .hasMessage("LOGIN_RATE_LIMITED");
        assertThatCode(() -> throttle.check("two")).doesNotThrowAnyException();
    }
}
