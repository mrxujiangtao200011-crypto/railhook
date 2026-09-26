package com.webhook.platform.api.service.signin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// A Workspace account names its company; a personal one does not.
class NewAccountOrganizationNameTest {

    private static VerifiedIdentity identity(String email, String givenName, String hostedDomain) {
        return new VerifiedIdentity("google", "sub-1", email, "Full Name", givenName, hostedDomain);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "ada@acme.com, Ada, acme.com, Acme",
            "ada@northwind-traders.co.uk, Ada, northwind-traders.co.uk, Northwind-traders",
            "ada@gmail.com, Ada, NULL, Ada's workspace",
            "ada.lovelace@gmail.com, NULL, '', ada.lovelace's workspace"})
    void namesTheCompanyAfterTheDomainAndAPersonalAccountAfterThePerson(
            String email, String givenName, String hostedDomain, String expected) {
        assertThat(NewAccountOrganizationName.of(identity(email, givenName, hostedDomain))).isEqualTo(expected);
    }

    @Test
    void neverProducesANameLongerThanTheColumnAllows() {
        String longName = "x".repeat(300);
        assertThat(NewAccountOrganizationName.of(identity("a@gmail.com", longName, null)))
                .hasSizeLessThanOrEqualTo(NewAccountOrganizationName.MAX_LENGTH);
    }
}
