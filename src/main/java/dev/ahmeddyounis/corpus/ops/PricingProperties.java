package dev.ahmeddyounis.corpus.ops;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Price table (USD per million tokens) keyed by model id; drives cost estimation. */
@ConfigurationProperties(prefix = "corpus.pricing")
public record PricingProperties(Map<String, ModelPrice> models) {

    public PricingProperties {
        if (models == null) {
            models = Map.of();
        }
    }

    public record ModelPrice(double inputPerMtok, double outputPerMtok) {
    }
}
