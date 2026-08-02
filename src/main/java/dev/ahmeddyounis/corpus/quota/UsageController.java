package dev.ahmeddyounis.corpus.quota;

import dev.ahmeddyounis.corpus.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets a caller see their own budget before they hit it. A quota a user cannot
 * observe is indistinguishable from the service being broken.
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    public record UsageResponse(LocalDate date, long promptTokens, long completionTokens, long totalTokens,
                                double costUsd, long requests, long tokenLimit, double costLimitUsd,
                                long remainingTokens, double remainingCostUsd, boolean exhausted,
                                boolean enforced) {
    }

    private final QuotaService quotas;
    private final CorpusQuotaProperties properties;
    private final CurrentUser currentUser;

    public UsageController(QuotaService quotas, CorpusQuotaProperties properties, CurrentUser currentUser) {
        this.quotas = quotas;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Today's token and cost usage for the caller, with the limits in force")
    @GetMapping
    public UsageResponse usage() {
        QuotaService.Usage usage = quotas.usage(currentUser.id());
        return new UsageResponse(usage.date(), usage.promptTokens(), usage.completionTokens(),
                usage.totalTokens(), usage.costUsd(), usage.requests(), usage.tokenLimit(),
                usage.costLimit(), usage.remainingTokens(), usage.remainingCostUsd(),
                properties.enabled() && usage.exhausted(), properties.enabled());
    }
}
