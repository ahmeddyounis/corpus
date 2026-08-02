package dev.ahmeddyounis.corpus.quota;

import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuotaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private QuotaService quotas;
    @Autowired
    private QuotaDao dao;
    @Autowired
    private CorpusQuotaProperties properties;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID spender;

    @BeforeAll
    void createUser() {
        spender = users.findByUsername("quota-spender")
                .orElseGet(() -> users.save(UserAccount.create("quota-spender", passwordEncoder.encode("x"))))
                .id();
    }

    @Test
    void aFreshUserHasTheConfiguredBudget() {
        UUID fresh = users.save(UserAccount.create("quota-fresh-" + UUID.randomUUID(),
                passwordEncoder.encode("x"))).id();

        QuotaService.Usage usage = quotas.usage(fresh);

        assertThat(usage.totalTokens()).isZero();
        assertThat(usage.tokenLimit()).isEqualTo(properties.dailyTokens());
        assertThat(usage.exhausted()).isFalse();
        assertThatCode(() -> quotas.checkAllowed(fresh)).doesNotThrowAnyException();
    }

    @Test
    void spendAccrues() {
        UUID user = newUser();

        quotas.record(user, 100, 50, 0.002);
        quotas.record(user, 10, 5, 0.0001);

        QuotaService.Usage usage = quotas.usage(user);
        assertThat(usage.promptTokens()).isEqualTo(110);
        assertThat(usage.completionTokens()).isEqualTo(55);
        assertThat(usage.totalTokens()).isEqualTo(165);
        assertThat(usage.costUsd()).isEqualTo(0.0021);
        assertThat(usage.requests()).isEqualTo(2);
    }

    @Test
    void exhaustingTheTokenBudgetRefusesWithADistinctCode() {
        UUID user = newUser();
        dao.setOverride(user, 100L, null);

        quotas.record(user, 80, 40, 0.0);

        assertThatThrownBy(() -> quotas.checkAllowed(user))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(QuotaService.QUOTA_ERROR_CODE)
                // 429, not 402: RFC 9110 reserves 402 and this is not a payment failure.
                .hasMessageContaining("429");
    }

    @Test
    void theCostCeilingBindsIndependentlyOfTokens() {
        UUID user = newUser();
        dao.setOverride(user, 10_000_000L, 0.01);

        quotas.record(user, 1, 1, 0.02);

        assertThat(quotas.usage(user).exhausted()).isTrue();
    }

    @Test
    void aPerUserOverrideRaisesOnlyThatUsersCeiling() {
        UUID raised = newUser();
        UUID normal = newUser();
        dao.setOverride(raised, 99_000_000L, 99.0);

        assertThat(quotas.usage(raised).tokenLimit()).isEqualTo(99_000_000L);
        assertThat(quotas.usage(normal).tokenLimit()).isEqualTo(properties.dailyTokens());
    }

    /**
     * The accrual is one upsert precisely so concurrent turns on different
     * replicas cannot lose spend to an interleaved read-modify-write.
     */
    @Test
    void concurrentAccrualsDoNotLoseSpend() throws Exception {
        UUID user = newUser();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    quotas.record(user, 10, 10, 0.001);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        QuotaService.Usage usage = quotas.usage(user);
        assertThat(usage.totalTokens()).isEqualTo(threads * 20L);
        assertThat(usage.requests()).isEqualTo(threads);
    }

    @Test
    void usageIsScopedToTheCallerAndToday() {
        UUID user = newUser();
        quotas.record(user, 7, 3, 0.0);

        QuotaService.Usage usage = quotas.usage(user);

        assertThat(usage.date()).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(quotas.usage(newUser()).totalTokens()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theUsageEndpointReportsTheCallersOwnBudget() {
        String token = demoToken();

        Map<String, Object> body = restClient().get().uri("/api/usage")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Map.class);

        assertThat(body).containsKeys("date", "totalTokens", "tokenLimit", "remainingTokens",
                "costUsd", "exhausted", "enforced");
        assertThat((Boolean) body.get("enforced")).isEqualTo(properties.enabled());
    }

    /** Usage is a per-caller figure; there is no caller to report for without a token. */
    @Test
    void theUsageEndpointRequiresAuthentication() {
        var response = restClient().get().uri("/api/usage").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    private UUID newUser() {
        return users.save(UserAccount.create("quota-" + UUID.randomUUID(), passwordEncoder.encode("x"))).id();
    }
}
