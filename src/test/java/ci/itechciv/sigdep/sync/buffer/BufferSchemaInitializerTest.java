package ci.itechciv.sigdep.sync.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Couvre le découpage DDL de {@link BufferSchemaInitializer#splitStatements}.
 * Le splitter doit être robuste aux ';' présents dans les commentaires et les
 * littéraux — un tel ';' cassait auparavant un CREATE TABLE en deux fragments
 * invalides et empêchait le démarrage de l'application.
 */
class BufferSchemaInitializerTest {

    @Test
    @DisplayName("Cas nominal : deux instructions séparées par ';'")
    void nominal_twoStatements() {
        String ddl = """
                CREATE TABLE a (id INTEGER);
                CREATE TABLE b (id INTEGER);
                """;
        List<String> stmts = BufferSchemaInitializer.splitStatements(ddl);
        assertEquals(2, stmts.size());
        assertEquals("CREATE TABLE a (id INTEGER)", stmts.get(0));
        assertEquals("CREATE TABLE b (id INTEGER)", stmts.get(1));
    }

    @Test
    @DisplayName("';' dans un commentaire ligne (--) ne coupe pas l'instruction")
    void semicolonInLineComment_doesNotSplit() {
        String ddl = """
                CREATE TABLE outbox (
                  id INTEGER PRIMARY KEY,
                  -- tie-breaker de keyset ; NULL si non applicable
                  source_id INTEGER
                );
                """;
        List<String> stmts = BufferSchemaInitializer.splitStatements(ddl);
        assertEquals(1, stmts.size());
        // Le CREATE TABLE reste entier et le commentaire est retiré.
        assertTrue(stmts.get(0).startsWith("CREATE TABLE outbox"));
        assertTrue(stmts.get(0).contains("source_id INTEGER"));
        assertTrue(stmts.get(0).trim().endsWith(")"));
        assertTrue(!stmts.get(0).contains("tie-breaker"), "le commentaire doit être retiré");
    }

    @Test
    @DisplayName("';' dans un commentaire bloc (/* */) ne coupe pas l'instruction")
    void semicolonInBlockComment_doesNotSplit() {
        String ddl =
                "CREATE TABLE a (\n"
                + "  id INTEGER /* col ; principale */,\n"
                + "  name TEXT\n"
                + ");\n"
                + "CREATE TABLE b (id INTEGER);";
        List<String> stmts = BufferSchemaInitializer.splitStatements(ddl);
        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).startsWith("CREATE TABLE a"));
        assertTrue(stmts.get(0).contains("name TEXT"));
        assertTrue(!stmts.get(0).contains("principale"), "le commentaire bloc doit être retiré");
        assertEquals("CREATE TABLE b (id INTEGER)", stmts.get(1));
    }

    @Test
    @DisplayName("';' dans un littéral entre quotes simples ne coupe pas ; '--' littéral n'est pas un commentaire")
    void semicolonAndDashesInStringLiteral_areNotSpecial() {
        String ddl =
                "INSERT INTO t (v) VALUES ('a;b -- c');\n"
                + "CREATE TABLE b (id INTEGER);";
        List<String> stmts = BufferSchemaInitializer.splitStatements(ddl);
        assertEquals(2, stmts.size());
        // Le littéral est préservé intégralement : ';' et '--' dedans sont
        // du texte, pas des séparateurs/commentaires.
        assertEquals("INSERT INTO t (v) VALUES ('a;b -- c')", stmts.get(0));
        assertEquals("CREATE TABLE b (id INTEGER)", stmts.get(1));
    }

    @Test
    @DisplayName("Instruction finale sans ';' terminal est bien retournée")
    void lastStatementWithoutTrailingSemicolon() {
        String ddl = """
                CREATE TABLE a (id INTEGER);
                CREATE INDEX idx ON a(id)""";
        List<String> stmts = BufferSchemaInitializer.splitStatements(ddl);
        assertEquals(2, stmts.size());
        assertEquals("CREATE TABLE a (id INTEGER)", stmts.get(0));
        assertEquals("CREATE INDEX idx ON a(id)", stmts.get(1));
    }

    @Test
    @DisplayName("Fragments vides (';' consécutifs, espaces, commentaire seul) sont ignorés")
    void emptyFragments_areSkipped() {
        String ddl = """
                CREATE TABLE a (id INTEGER);;

                -- commentaire seul, aucune instruction
                ;
                CREATE TABLE b (id INTEGER);
                """;
        List<String> stmts = BufferSchemaInitializer.splitStatements(ddl);
        assertEquals(2, stmts.size());
        assertEquals("CREATE TABLE a (id INTEGER)", stmts.get(0));
        assertEquals("CREATE TABLE b (id INTEGER)", stmts.get(1));
    }

    @Test
    @DisplayName("Quote doublée ('') à l'intérieur d'un littéral : le ';' suivant coupe bien")
    void doubledQuoteInsideLiteral_closesCorrectly() {
        String ddl =
                "INSERT INTO t (v) VALUES ('O''Brien');\n"
                + "CREATE TABLE b (id INTEGER);";
        List<String> stmts = BufferSchemaInitializer.splitStatements(ddl);
        assertEquals(2, stmts.size());
        assertEquals("INSERT INTO t (v) VALUES ('O''Brien')", stmts.get(0));
        assertEquals("CREATE TABLE b (id INTEGER)", stmts.get(1));
    }
}
