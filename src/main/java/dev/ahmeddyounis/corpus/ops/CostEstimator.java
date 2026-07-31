package dev.ahmeddyounis.corpus.ops;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Turns token usage into an estimated USD cost using the configured price table. */
@Component
public class CostEstimator {

    private final PricingProperties pricing;

    public CostEstimator(PricingProperties pricing) {
        this.pricing = pricing;
    }

    public Optional<Double> estimate(String model, Integer promptTokens, Integer completionTokens) {
        PricingProperties.ModelPrice price = pricing.models().get(normalize(model));
        if (price == null) {
            return Optional.empty();
        }
        double input = promptTokens == null ? 0 : promptTokens;
        double output = completionTokens == null ? 0 : completionTokens;
        return Optional.of(input / 1_000_000 * price.inputPerMtok() + output / 1_000_000 * price.outputPerMtok());
    }

    public static String normalize(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }
}
