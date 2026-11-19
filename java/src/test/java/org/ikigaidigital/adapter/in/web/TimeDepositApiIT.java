package org.ikigaidigital.adapter.in.web;

import com.jayway.jsonpath.JsonPath;
import org.ikigaidigital.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over HTTP against the running server and PostgreSQL (PLAN.md §6 item 6). Not
 * transactional: the server handles requests in its own threads, so rows are inserted with
 * explicit ids and deleted after each test. Interest boundaries are covered by the unit tests;
 * here only one eligible and one ineligible deposit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TimeDepositApiIT {

    // RFC 8288: <URL>; rel="next"
    private static final Pattern NEXT_LINK = Pattern.compile("^<([^>]+)>; rel=\"next\"$");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private RestTestClient client;

    @BeforeEach
    void createClient() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void deleteAllRows() {
        jdbc.update("DELETE FROM withdrawals");
        jdbc.update("DELETE FROM time_deposits");
    }

    // --- GET without parameters ---

    @Test
    void getWithoutParameters_returnsAllDepositsInTheReadmeSchema_withWithdrawalsOrderedById() {
        insertDeposit(1, "basic", 31, "1200.00");
        insertDeposit(2, "premium", 10, "5000.50");
        // Inserted out of id order: the response must order withdrawals by id, not insertion.
        insertWithdrawal(11, 1, "50.25", LocalDate.of(2026, 2, 1));
        insertWithdrawal(10, 1, "100.00", LocalDate.of(2026, 1, 15));

        client.get().uri("/time-deposits").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().doesNotExist(HttpHeaders.LINK)
                // STRICT: exact field names, no extra fields (e.g. the interest marker), array order;
                // balance/amount must be JSON numbers and the date a "YYYY-MM-DD" string.
                .expectBody().json("""
                        [
                          {"id": 1, "planType": "basic", "balance": 1200.00, "days": 31,
                           "withdrawals": [
                             {"id": 10, "amount": 100.00, "date": "2026-01-15"},
                             {"id": 11, "amount": 50.25, "date": "2026-02-01"}
                           ]},
                          {"id": 2, "planType": "premium", "balance": 5000.50, "days": 10,
                           "withdrawals": []}
                        ]""", JsonCompareMode.STRICT);
    }

    @Test
    void getWithoutParameters_onEmptyDatabase_returnsEmptyArray() {
        client.get().uri("/time-deposits").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.LINK)
                .expectBody().json("[]", JsonCompareMode.STRICT);
    }

    // --- POST balance updates ---

    @Test
    void postBalanceUpdates_returns204_andAppliesInterestOnlyToEligibleDeposits() {
        insertDeposit(1, "basic", 31, "1200.00"); // eligible: 1200 * 0.01 / 12 = 1.00
        insertDeposit(2, "basic", 30, "1200.00"); // grace period: unchanged

        client.post().uri("/time-deposits/balance-updates").exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        assertBalances("""
                [{"id": 1, "balance": 1201.00}, {"id": 2, "balance": 1200.00}]""");
    }

    @Test
    void secondPostBalanceUpdates_returns204_andLeavesBalancesUnchanged() {
        insertDeposit(1, "basic", 31, "1200.00");
        client.post().uri("/time-deposits/balance-updates").exchange().expectStatus().isNoContent();
        assertBalances("""
                [{"id": 1, "balance": 1201.00}]""");

        client.post().uri("/time-deposits/balance-updates").exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        assertBalances("""
                [{"id": 1, "balance": 1201.00}]""");
    }

    // --- GET pagination ---

    @Test
    void getWithLimit_returnsFirstPage_andLinkToNextPageAfterItsLastId() {
        insertDeposits(5);

        EntityExchangeResult<byte[]> page = get(URI.create("/time-deposits?limit=2"));

        assertThat(ids(page)).containsExactly(1, 2);
        // Path-absolute reference (RFC 8288): no host from request headers, so it stays correct
        // behind a reverse proxy.
        assertThat(nextLinkTarget(page)).isEqualTo("/time-deposits?afterId=2&limit=2");
        assertThat(nextLink(page).getPath()).isEqualTo("/time-deposits");
    }

    @Test
    void nextLink_carriesOnlyTheCursor_notOtherRequestParameters() {
        insertDeposits(5);

        EntityExchangeResult<byte[]> page = get(URI.create("/time-deposits?limit=2&x=a%20b"));

        assertThat(nextLinkTarget(page)).isEqualTo("/time-deposits?afterId=2&limit=2");
    }

    @Test
    void getWithAfterIdAtHighestId_returnsEmptyPage_withoutLink() {
        insertDeposits(5);

        EntityExchangeResult<byte[]> page = get(URI.create("/time-deposits?afterId=5&limit=2"));

        assertThat(ids(page)).isEmpty();
        assertThat(page.getResponseHeaders().containsHeader(HttpHeaders.LINK)).isFalse();
    }

    // Only limit is validated (CLAUDE.md: invalid input is out of scope); a negative afterId simply
    // starts at the first deposit.
    @Test
    void getWithNegativeAfterId_startsAtTheFirstDeposit() {
        insertDeposits(3);

        assertThat(ids(get(URI.create("/time-deposits?afterId=-1&limit=2")))).containsExactly(1, 2);
    }

    @Test
    void followingNextLinks_visitsEveryDepositExactlyOnce_andLastPageHasNoLink() {
        insertDeposits(5);

        List<List<Integer>> pages = new ArrayList<>();
        EntityExchangeResult<byte[]> page = get(URI.create("/time-deposits?limit=2"));
        pages.add(ids(page));
        while (page.getResponseHeaders().containsHeader(HttpHeaders.LINK)) {
            assertThat(pages).as("more pages than deposits: link loop").hasSizeLessThan(5);
            page = get(nextLink(page));
            pages.add(ids(page));
        }

        assertThat(pages).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
    }

    @Test
    void getWithAfterIdOnly_returnsDefaultPageOf100_andLinkToNextPage() {
        insertDeposits(101);

        EntityExchangeResult<byte[]> page = get(URI.create("/time-deposits?afterId=0"));

        assertThat(ids(page)).hasSize(100).startsWith(1).endsWith(100);
        MultiValueMap<String, String> query =
                UriComponentsBuilder.fromUri(nextLink(page)).build().getQueryParams();
        assertThat(query.get("afterId")).containsExactly("100");
        assertThat(query.get("limit")).containsExactly("100");
    }

    // limit == remaining guards a "page is full, so there is more" shortcut; limit > remaining the plain case.
    @ParameterizedTest(name = "limit={0}")
    @ValueSource(ints = {2, 10})
    void getWithLimitCoveringAllRemainingDeposits_returnsThem_withoutLink(int limit) {
        insertDeposits(5);

        EntityExchangeResult<byte[]> page = get(URI.create("/time-deposits?afterId=3&limit=" + limit));

        assertThat(ids(page)).containsExactly(4, 5);
        assertThat(page.getResponseHeaders().containsHeader(HttpHeaders.LINK)).isFalse();
    }

    // --- limit validation (generated bean validation, range 1–1000) ---

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(ints = {0, 1001})
    void getWithLimitOutsideRange_returns400(int limit) {
        client.get().uri("/time-deposits?limit=" + limit).exchange()
                .expectStatus().isBadRequest();
    }

    @ParameterizedTest(name = "limit={0}")
    @CsvSource({"1, 1, true", "1000, 2, false"})
    void getWithLimitAtRangeBounds_isAccepted(int limit, int expectedItems, boolean hasNextPage) {
        insertDeposits(2);

        EntityExchangeResult<byte[]> page = get(URI.create("/time-deposits?limit=" + limit));

        assertThat(ids(page)).hasSize(expectedItems);
        assertThat(page.getResponseHeaders().containsHeader(HttpHeaders.LINK)).isEqualTo(hasNextPage);
    }

    // --- API documentation tooling ---

    @Test
    void swaggerUi_isServed_andShowsTheContractFile() {
        client.get().uri("/swagger-ui.html").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class).value(html -> assertThat(html).contains("openapi/time-deposits.yaml"));

        // Version-less webjar paths resolved by webjars-locator-lite.
        client.get().uri("/webjars/swagger-ui/swagger-ui-bundle.js").exchange().expectStatus().isOk();
        client.get().uri("/webjars/swagger-ui/swagger-ui.css").exchange().expectStatus().isOk();
    }

    @Test
    void contractFile_isServedAsIs() {
        client.get().uri("/openapi/time-deposits.yaml").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(yaml -> assertThat(yaml)
                        .contains("openapi: 3.0.3")
                        .contains("/time-deposits/balance-updates:"));
    }

    @Test
    void generatedApiDocs_doNotExist() {
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isNotFound();
    }

    // --- helpers ---

    private EntityExchangeResult<byte[]> get(URI uri) {
        return client.get().uri(uri).exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();
    }

    private static List<Integer> ids(EntityExchangeResult<byte[]> result) {
        return JsonPath.read(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8), "$[*].id");
    }

    /** The raw target of the `Link: <target>; rel="next"` header. */
    private static String nextLinkTarget(EntityExchangeResult<byte[]> result) {
        String link = result.getResponseHeaders().getFirst(HttpHeaders.LINK);
        assertThat(link).as("Link header").isNotNull();
        Matcher matcher = NEXT_LINK.matcher(link);
        assertThat(matcher.matches()).as("Link header format: %s", link).isTrue();
        return matcher.group(1);
    }

    /** The next-page URL, resolved against the request URL as a client does. */
    private static URI nextLink(EntityExchangeResult<byte[]> result) {
        return result.getUrl().resolve(nextLinkTarget(result));
    }

    // LENIENT: compares only id and balance of each deposit (same number of deposits).
    private void assertBalances(String expectedJson) {
        client.get().uri("/time-deposits").exchange()
                .expectStatus().isOk()
                .expectBody().json(expectedJson, JsonCompareMode.LENIENT);
    }

    private void insertDeposits(int count) {
        for (int id = 1; id <= count; id++) {
            insertDeposit(id, "basic", 10, "100.00");
        }
    }

    private void insertDeposit(int id, String planType, int days, String balance) {
        jdbc.update("INSERT INTO time_deposits (id, plan_type, days, balance) VALUES (?, ?, ?, ?)",
                id, planType, days, new BigDecimal(balance));
    }

    private void insertWithdrawal(int id, int depositId, String amount, LocalDate date) {
        jdbc.update("INSERT INTO withdrawals (id, time_deposit_id, amount, date) VALUES (?, ?, ?, ?)",
                id, depositId, new BigDecimal(amount), date);
    }
}
