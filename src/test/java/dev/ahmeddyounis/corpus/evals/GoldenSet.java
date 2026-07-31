package dev.ahmeddyounis.corpus.evals;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Loads {@code evals/golden-set.yaml} (project root) for the eval tests. */
public final class GoldenSet {

    public record GoldenCase(String id, String question, List<String> expectedSources, String referenceAnswer) {
    }

    private GoldenSet() {
    }

    @SuppressWarnings("unchecked")
    public static List<GoldenCase> load() {
        Path path = Path.of(System.getProperty("user.dir")).resolve("evals/golden-set.yaml");
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> root = new Yaml().load(in);
            List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");
            return cases.stream()
                    .map(c -> new GoldenCase(
                            (String) c.get("id"),
                            (String) c.get("question"),
                            (List<String>) c.get("expected_sources"),
                            (String) c.get("reference_answer")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load golden set from " + path, e);
        }
    }
}
