package com.alanan.accounting.masterdata;

import com.alanan.accounting.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PhoneNormalizerTest {
    @Test void ignoresFormattingAndNormalizesArabicDigitsWithoutGuessingCountryCodes() {
        assertThat(PhoneNormalizer.normalize("(٠٥٩٩) ١٢٣-٤٥٦")).isEqualTo("0599123456");
        assertThat(PhoneNormalizer.normalize("+970 599 123456")).isEqualTo("970599123456");
        assertThat(PhoneNormalizer.normalize(" ")).isNull();
        assertThatThrownBy(() -> PhoneNormalizer.normalize("hello123456")).isInstanceOf(ApiException.class);
    }
}
