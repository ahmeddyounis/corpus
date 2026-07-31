package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.ingestion.DocumentEntity;
import dev.ahmeddyounis.corpus.ingestion.DocumentRepository;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentMetadataToolsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DocumentMetadataTools tools;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void toolAnswersOnlyForTheActingUser() {
        UserAccount owner = users.findByUsername("tool-owner")
                .orElseGet(() -> users.save(UserAccount.create("tool-owner", passwordEncoder.encode("x"))));
        UserAccount stranger = users.findByUsername("tool-stranger")
                .orElseGet(() -> users.save(UserAccount.create("tool-stranger", passwordEncoder.encode("x"))));

        if (documents.findByUserIdAndFilename(owner.id(), "tool-doc.md").isEmpty()) {
            documents.save(DocumentEntity.create(owner.id(), "tool-doc.md", "text/markdown", 123).ready(4));
        }

        String forOwner = tools.documentInfo("tool-doc.md",
                new ToolContext(Map.of(DocumentMetadataTools.USER_ID_CONTEXT_KEY, owner.id().toString())));
        assertThat(forOwner).contains("tool-doc.md").contains("status=READY").contains("chunks=4");

        String forStranger = tools.documentInfo("tool-doc.md",
                new ToolContext(Map.of(DocumentMetadataTools.USER_ID_CONTEXT_KEY, stranger.id().toString())));
        assertThat(forStranger).contains("No document named");
    }
}
