package com.synapse.orderrouter.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServiceAreaTest {

    @Test
    void singleZipMatchesOnlyThatZip() {
        ServiceArea area = ServiceArea.parse("10015");

        assertThat(area.serves("10015")).isTrue();
        assertThat(area.serves("10016")).isFalse();
    }

    @Test
    void rangeIncludesBothEndpointsAndExcludesJustOutside() {
        ServiceArea area = ServiceArea.parse("10001-10100");

        assertThat(area.serves("10001")).isTrue();
        assertThat(area.serves("10050")).isTrue();
        assertThat(area.serves("10100")).isTrue();
        assertThat(area.serves("10000")).isFalse();
        assertThat(area.serves("10101")).isFalse();
    }

    @Test
    void mixedListOfRangesAndSingleZipsMatchesAnyToken() {
        ServiceArea area = ServiceArea.parse("10451-10478, 10479-10502, 10001");

        assertThat(area.serves("10001")).isTrue();
        assertThat(area.serves("10460")).isTrue();
        assertThat(area.serves("10480")).isTrue();
        assertThat(area.serves("10503")).isFalse();
    }

    @Test
    void fourDigitTokensAreLeftPaddedWithZero() {
        ServiceArea area = ServiceArea.parse("2164-2213, 2110");

        assertThat(area.serves("02170")).isTrue();
        assertThat(area.serves("02110")).isTrue();
        assertThat(area.serves("02163")).isFalse();
        assertThat(area.serves("02214")).isFalse();
    }

    @Test
    void fullRangeActsAsAWildcard() {
        ServiceArea area = ServiceArea.parse("00100-99999");

        assertThat(area.serves("00100")).isTrue();
        assertThat(area.serves("02130")).isTrue();
        assertThat(area.serves("99999")).isTrue();
    }

    @Test
    void unparseableTokensAreIgnoredAndValidTokensStillWork() {
        ServiceArea area = ServiceArea.parse("N/A, 10015, abc-def");

        assertThat(area.serves("10015")).isTrue();
        assertThat(area.serves("10016")).isFalse();
    }

    @Test
    void negativeZipTokenIsIgnored() {
        ServiceArea area = ServiceArea.parse("-12345, 10015");

        assertThat(area.serves("12345")).isFalse();
        assertThat(area.serves("10015")).isTrue();
    }

    @Test
    void blankOrNullServesNothing() {
        assertThat(ServiceArea.parse(null).serves("10015")).isFalse();
        assertThat(ServiceArea.parse("").serves("10015")).isFalse();
        assertThat(ServiceArea.parse("   ").serves("10015")).isFalse();
    }
}
