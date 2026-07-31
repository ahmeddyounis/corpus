package dev.ahmeddyounis.corpus.ingestion;

import java.io.InputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

/** Extracts plain text from PDF/DOCX/Markdown/TXT streams via Tika auto-detection. */
@Component
public class TikaTextExtractor {

    public String extract(InputStream in, String filename) {
        try {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
            String text = handler.toString().strip();
            if (text.isEmpty()) {
                throw new IngestionException("No extractable text in " + filename);
            }
            return text;
        } catch (IngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new IngestionException("Failed to parse " + filename + ": " + e.getMessage(), e);
        }
    }
}
