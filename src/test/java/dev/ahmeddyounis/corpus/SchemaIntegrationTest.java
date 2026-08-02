package dev.ahmeddyounis.corpus;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayCreatesAllTables() {
        List<String> tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public'
                        """)
                .query(String.class)
                .list();

        assertThat(tables).contains(
                "vector_store", "users", "documents", "conversations",
                "spring_ai_chat_memory", "flyway_schema_history");
    }

    @Test
    void embeddingColumnMatchesConfiguredDimension() {
        String type = jdbc.sql("""
                        SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                        WHERE attrelid = 'vector_store'::regclass AND attname = 'embedding'
                        """)
                .query(String.class)
                .single();

        assertThat(type).isEqualTo("vector(384)");
    }

    @Test
    void fullTextColumnAndIndexesExist() {
        String tsvType = jdbc.sql("""
                        SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                        WHERE attrelid = 'vector_store'::regclass AND attname = 'content_tsv'
                        """)
                .query(String.class)
                .single();
        assertThat(tsvType).isEqualTo("tsvector");

        List<String> indexes = jdbc.sql(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'vector_store'")
                .query(String.class)
                .list();
        assertThat(indexes).contains(
                "idx_vector_store_embedding", "idx_vector_store_tsv", "idx_vector_store_metadata");
    }

    @Test
    void documentOwnershipColumnsAndUniqueFilenameIndexExist() {
        List<String> columns = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name = 'documents'
                        """)
                .query(String.class)
                .list();
        assertThat(columns).contains("owner_instance", "claimed_at");

        List<String> indexes = jdbc.sql(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'documents'")
                .query(String.class)
                .list();
        assertThat(indexes).contains("uq_documents_user_filename", "idx_documents_inflight");
    }
}
