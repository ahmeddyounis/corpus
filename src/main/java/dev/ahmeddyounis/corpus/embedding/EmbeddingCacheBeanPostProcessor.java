package dev.ahmeddyounis.corpus.embedding;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Wraps whichever {@link EmbeddingModel} the active profile auto-configured in a
 * {@link CachingEmbeddingModel}.
 *
 * <p>A {@code @Primary} bean would not work here: the wrapper needs the real
 * model injected, and the only {@code EmbeddingModel} bean available to inject
 * would be the wrapper itself. Post-processing is the seam that lets the cache
 * sit in front of a bean it does not own — which matters because nothing in this
 * codebase calls the embedding model directly. {@code PgVectorStore} embeds
 * internally on both {@code add} and {@code similaritySearch}, so wrapping the
 * bean is the only way to cache either path.
 */
@Component
@ConditionalOnProperty(prefix = "corpus.embedding.cache", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EmbeddingCacheBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCacheBeanPostProcessor.class);

    private final BeanFactory beans;
    private final Environment environment;

    /**
     * Collaborators are resolved lazily through the bean factory rather than
     * injected. A post-processor that injects beans forces them to initialise
     * before post-processing is ready, which takes them out of the reach of every
     * other post-processor in the context.
     */
    public EmbeddingCacheBeanPostProcessor(BeanFactory beans, Environment environment) {
        this.beans = beans;
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof EmbeddingModel model) || bean instanceof CachingEmbeddingModel) {
            return bean;
        }
        EmbeddingCacheProperties properties = beans.getBean(EmbeddingCacheProperties.class);
        String namespace = namespace(model);
        log.info("Embedding cache enabled: namespace={} l1={} l2={}",
                namespace, properties.l1MaxEntries(), properties.l2MaxEntries());
        return new CachingEmbeddingModel(model, beans.getBean(EmbeddingCacheDao.class),
                beans.getBean(MeterRegistry.class), namespace,
                properties.l1MaxEntries(), properties.l2MaxEntries());
    }

    /**
     * {@code provider:model:dimension}. Every input that changes the vectors is in
     * the key, so switching model or dimension misses the cache instead of
     * silently serving vectors from a model that is no longer in use.
     */
    private String namespace(EmbeddingModel model) {
        String provider = environment.getProperty("spring.ai.model.embedding", "unknown");
        String name = switch (provider) {
            case "openai" -> environment.getProperty("spring.ai.openai.embedding.options.model", "default");
            case "ollama" -> environment.getProperty("spring.ai.ollama.embedding.options.model", "default");
            case "transformers" -> environment.getProperty("spring.ai.embedding.transformer.onnx.model-uri",
                    "all-MiniLM-L6-v2");
            default -> model.getClass().getSimpleName();
        };
        // The configured dimension, not delegate.dimensions(): asking the model
        // would force it to load during post-processing. DimensionGuard already
        // asserts this value matches the column the vectors are stored in.
        String dimension = environment.getProperty("corpus.embedding.dimension", "unknown");
        return provider + ":" + name + ":" + dimension;
    }
}
