package com.js.motos.clientApp.user.service;

import com.js.motos.clientApp.user.domain.PayoutAccount;
import com.js.motos.clientApp.user.domain.PayoutType;
import com.js.motos.clientApp.user.web.dto.UserDtos;

/** Entity to DTO conversions for the user module. */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserDtos.PayoutAccount toDto(PayoutAccount account) {
        return new UserDtos.PayoutAccount(
                account.getId(),
                account.getType(),
                account.getAccountHolderName(),
                account.getUpiId(),
                maskedAccount(account),
                account.getIfsc(),
                account.getBankName(),
                account.getStatus(),
                account.isPrimary(),
                account.getRejectionReason(),
                account.getVerifiedAt(),
                account.getCreatedAt());
    }

    /**
     * Bank account numbers are encrypted at rest and never leave the server in
     * full — clients only ever see the last four digits.
     */
    private static String maskedAccount(PayoutAccount account) {
        if (account.getType() != PayoutType.BANK || account.getBankAccountLast4() == null) {
            return null;
        }
        return "****" + account.getBankAccountLast4();
    }
}