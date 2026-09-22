package com.alanan.accounting.masterdata;

import com.alanan.accounting.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public final class PhoneNormalizer {
    private PhoneNormalizer() {}
    public static String normalize(String phone) {
        if (phone == null || phone.isBlank()) return null;
        StringBuilder digits = new StringBuilder();
        for (int i=0; i<phone.length(); i++) {
            char c = phone.charAt(i);
            int digit = Character.digit(c, 10);
            if (digit >= 0) digits.append(digit);
            else if (!Character.isWhitespace(c) && "-()+.".indexOf(c) < 0)
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }
        if (digits.length() < 6 || digits.length() > 20)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        // Do not guess country codes or collapse different local/international numbers.
        return digits.toString();
    }
}
