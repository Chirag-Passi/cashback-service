package com.js.motos.clientApp.user.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.js.motos.clientApp.security.AuthPrincipal;
import com.js.motos.clientApp.user.service.PayoutAccountService;
import com.js.motos.clientApp.user.service.UserService;
import com.js.motos.clientApp.user.web.dto.UserDtos;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Profile", description = "About-me info, and the UPI / bank details cashback is paid into")
public class ProfileController {

    private final UserService userService;
    private final PayoutAccountService payoutAccountService;

    public ProfileController(UserService userService, PayoutAccountService payoutAccountService) {
        this.userService = userService;
        this.payoutAccountService = payoutAccountService;
    }

    @GetMapping
    @Operation(summary = "Get my profile and earnings summary")
    public UserDtos.Profile me(AuthPrincipal principal) {
        return userService.getProfile(principal.userId());
    }

    @PatchMapping
    @Operation(summary = "Update my profile",
            description = "Omit a field to leave it unchanged; send an empty string to clear it.")
    public UserDtos.Profile updateMe(AuthPrincipal principal,
                                     @Valid @RequestBody UserDtos.UpdateProfile request) {
        return userService.updateProfile(principal.userId(), request);
    }

    // --- payout accounts --------------------------------------------------

    @GetMapping("/payout-accounts")
    @Operation(summary = "List my UPI and bank accounts")
    public List<UserDtos.PayoutAccount> payoutAccounts(AuthPrincipal principal) {
        return payoutAccountService.listForUser(principal.userId());
    }

    @PostMapping("/payout-accounts/upi")
    @Operation(summary = "Add a UPI ID",
            description = "Submitted for admin verification; cashback can only be paid "
                    + "to a verified account.")
    public UserDtos.PayoutAccount addUpi(AuthPrincipal principal,
                                         @Valid @RequestBody UserDtos.CreateUpiAccount request) {
        return payoutAccountService.addUpi(principal.userId(), request);
    }

    @PostMapping("/payout-accounts/bank")
    @Operation(summary = "Add bank account details",
            description = "The account number is encrypted at rest and never returned in full.")
    public UserDtos.PayoutAccount addBank(AuthPrincipal principal,
                                          @Valid @RequestBody UserDtos.CreateBankAccount request) {
        return payoutAccountService.addBank(principal.userId(), request);
    }

    @PutMapping("/payout-accounts/{accountId}/primary")
    @Operation(summary = "Make a verified account the default payout target")
    public UserDtos.PayoutAccount makePrimary(AuthPrincipal principal,
                                              @PathVariable UUID accountId) {
        return payoutAccountService.makePrimary(principal.userId(), accountId);
    }

    @DeleteMapping("/payout-accounts/{accountId}")
    @Operation(summary = "Remove a payout account")
    public ResponseEntity<Void> deleteAccount(AuthPrincipal principal,
                                              @PathVariable UUID accountId) {
        payoutAccountService.delete(principal.userId(), accountId);
        return ResponseEntity.noContent().build();
    }
}