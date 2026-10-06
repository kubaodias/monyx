package com.monyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The money arithmetic that lives in SQL, run through real SQLite.
 *
 * Every other test in this module tests Kotlin, and twice now a conversion bug
 * has shipped because the rule was in a `@DatabaseView` or a `@Query` where no
 * JVM test could reach it: the ledger row that silently never carried its unit,
 * and the account balance that added 100 zł to a euro balance as though it were
 * 100 €. Those are the two most expensive places in the app to be wrong and the
 * two least covered.
 *
 * So this test takes the SQL from where it actually lives — the exported Room
 * schema for the view, the `@Query` annotation's own text for the balance — and
 * runs it with the `sqlite3` binary. Retyping either one here would make this
 * another test that asserts a copy and passes while the app is broken.
 *
 * It needs `sqlite3` on PATH and FAILS rather than skips without it. There is no
 * CI: the suite runs on one machine, and a test that quietly stops running on
 * that machine is worse than no test at all.
 */
class LedgerViewTest {

    // --- where the SQL comes from ------------------------------------------

    /** The module directory, wherever Gradle happens to run the tests from. */
    private val appDir: File = File("").absoluteFile.let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "schemas/com.monyx.data.MonyxDatabase").isDirectory }
            ?: error("cannot find app/schemas from $cwd")
    }

    /**
     * The newest exported schema, not a pinned version number: a view change
     * bumps the database version, and this test has to follow the view forward
     * rather than keep checking the one it was written against.
     */
    private fun ledgerViewSql(): String {
        val dir = File(appDir, "schemas/com.monyx.data.MonyxDatabase")
        val newest = dir.listFiles { f: File -> f.name.endsWith(".json") }
            ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: -1 }
            ?: error("no exported schemas in $dir")
        val json = newest.readText()
        // Deliberately not a JSON library: the module has no test dependency on
        // one, and the field is a single string value whose end is unambiguous.
        val key = "\"createSql\": \""
        val start = json.indexOf(key, json.indexOf("\"viewName\": \"ledger_pln\"").let {
            require(it >= 0) { "ledger_pln is not in ${newest.name}" }
            it
        })
        require(start >= 0) { "no createSql for ledger_pln in ${newest.name}" }
        val body = StringBuilder()
        var i = start + key.length
        while (json[i] != '"') {
            if (json[i] == '\\') {
                i++
                body.append(
                    when (json[i]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> json[i]
                    },
                )
            } else {
                body.append(json[i])
            }
            i++
        }
        return body.toString().replace("\${VIEW_NAME}", "ledger_pln")
    }

    /** The text of one `@Query` in Daos.kt, by the function it annotates. */
    private fun daoQuery(functionName: String): String {
        val source = File(appDir, "src/main/java/com/monyx/data/Daos.kt").readText()
        val fn = source.indexOf("fun $functionName(")
        require(fn >= 0) { "no $functionName in Daos.kt" }
        val close = source.lastIndexOf("\"\"\"", fn)
        val open = source.lastIndexOf("\"\"\"", close - 1)
        require(open >= 0 && close > open) { "no triple-quoted query above $functionName" }
        val sql = source.substring(open + 3, close)
        // Room binds :named parameters; sqlite3 would prompt for them. Checked
        // against the SQL with its -- comments stripped, since a comment is
        // allowed to contain a colon and the first version of this check tripped
        // over one of mine.
        val bare = sql.lines().joinToString("\n") { it.substringBefore("--") }
        val parameter = Regex(":[A-Za-z]\\w*").find(bare)
        require(parameter == null) {
            "$functionName takes ${parameter?.value}; bind it before running this query"
        }
        return sql
    }

    // --- the harness --------------------------------------------------------

    private val tables = """
        CREATE TABLE transactions (
            id TEXT PRIMARY KEY, kind TEXT, amountMinor INTEGER, accountId TEXT,
            transferAccountId TEXT, categoryId TEXT, note TEXT, occurredAt INTEGER,
            occurredOn TEXT, createdBy TEXT, source TEXT, recurringRuleId TEXT,
            createdAt INTEGER, currency TEXT, seq INTEGER DEFAULT 0,
            deleted INTEGER DEFAULT 0, pending INTEGER DEFAULT 0, rejected INTEGER DEFAULT 0);
        CREATE TABLE accounts (
            id TEXT PRIMARY KEY, name TEXT, icon TEXT, color TEXT,
            initialBalanceMinor INTEGER DEFAULT 0, sortOrder INTEGER DEFAULT 0,
            archived INTEGER DEFAULT 0, excludedFromSummary INTEGER DEFAULT 0,
            currency TEXT, deleted INTEGER DEFAULT 0);
        CREATE TABLE fx_rates (
            currency TEXT, effectiveOn TEXT, publishedOn TEXT, rateMicro INTEGER,
            PRIMARY KEY (currency, effectiveOn));
    """.trimIndent()

    /** The household's own rates, for 5 October 2026. EUR 4,3855 is the real one. */
    private val rates = """
        INSERT INTO fx_rates VALUES
            ('EUR','2026-10-05','2026-10-05',4385500),
            ('USD','2026-10-05','2026-10-05',3700000);
    """.trimIndent()

    private fun query(setup: String, select: String): List<List<String?>> {
        val script = File.createTempFile("ledger", ".sql").apply { deleteOnExit() }
        script.writeText(
            listOf(tables, ledgerViewSql() + ";", rates, setup, ".mode list", ".nullvalue NULL", select)
                .joinToString("\n"),
        )
        val process = ProcessBuilder("sqlite3", ":memory:")
            .redirectInput(script)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        assertEquals("sqlite3 failed:\n$out", 0, code)
        return out.trim().lines().filter { it.isNotBlank() }
            .map { line -> line.split("|").map { if (it == "NULL") null else it } }
    }

    private fun accounts(vararg rows: Triple<String, String, Long>) =
        rows.joinToString("\n") { (id, currency, opening) ->
            "INSERT INTO accounts (id,name,initialBalanceMinor,currency) " +
                "VALUES ('$id','$id',$opening,'$currency');"
        }

    private fun expense(id: String, minor: Long, account: String, currency: String) =
        "INSERT INTO transactions (id,kind,amountMinor,accountId,occurredOn,currency," +
            "occurredAt,createdBy,source,createdAt) VALUES " +
            "('$id','expense',$minor,'$account','2026-10-05','$currency',0,'m','manual',0);"

    private fun transfer(id: String, minor: Long, from: String, to: String, currency: String) =
        "INSERT INTO transactions (id,kind,amountMinor,accountId,transferAccountId,occurredOn," +
            "currency,occurredAt,createdBy,source,createdAt) VALUES " +
            "('$id','transfer',$minor,'$from','$to','2026-10-05','$currency',0,'m','manual',0);"

    // --- the view's own columns --------------------------------------------

    @Test
    fun `a zloty expense on a euro account converts both ways`() {
        // The reported bug, as arithmetic: 100 zł paid from a euro card is 100 zł
        // in złoty and 22,80 € out of the account. Neither figure is 100 of the
        // other unit.
        val rows = query(
            accounts(Triple("eur", "EUR", 0)) + expense("a", 100_00, "eur", "PLN"),
            "SELECT plnMinor, accountCurrency, accountMinor FROM ledger_pln WHERE id='a';",
        )
        assertEquals(listOf(listOf("10000", "EUR", "2280")), rows)
    }

    @Test
    fun `a euro expense on a euro account is not converted into itself`() {
        // accountMinor must be the amount itself, to the grosz, with no rate
        // involved: an account holding exactly 15 € of euro purchases reads
        // -15,00 € however the rate moves afterwards.
        val rows = query(
            accounts(Triple("eur", "EUR", 0)) + expense("b", 15_00, "eur", "EUR"),
            "SELECT plnMinor, accountMinor FROM ledger_pln WHERE id='b';",
        )
        assertEquals(listOf(listOf("6578", "1500")), rows)
    }

    @Test
    fun `a euro expense on a dollar account crosses through zloty`() {
        // 15 € is 65,78 zł is 17,77 $. Two roundings, which is the documented
        // cost of fx_rates being keyed on złoty alone.
        val rows = query(
            accounts(Triple("usd", "USD", 0)) + expense("c", 15_00, "usd", "EUR"),
            "SELECT plnMinor, accountMinor FROM ledger_pln WHERE id='c';",
        )
        assertEquals(listOf(listOf("6578", "1777")), rows)
    }

    @Test
    fun `a currency with no rate yields null in every converted column`() {
        // Never zero. A row worth nothing would be silently dropped from a total
        // that looked complete.
        val rows = query(
            accounts(Triple("eur", "EUR", 0)) + expense("d", 15_00, "eur", "SEK"),
            "SELECT plnMinor, accountMinor FROM ledger_pln WHERE id='d';",
        )
        assertEquals(listOf(listOf(null, null)), rows)
    }

    @Test
    fun `the two legs of a cross-currency transfer carry different figures`() {
        // 100 zł leaves the złoty account and arrives on the dollar card as
        // 27,02 $. transferPlnMinor was removed in #62 on the grounds that one
        // amount means one converted figure; that is true in złoty only.
        val rows = query(
            accounts(Triple("pln", "PLN", 0), Triple("usd", "USD", 0)) +
                transfer("t", 100_00, "pln", "usd", "PLN"),
            "SELECT accountMinor, transferAccountMinor FROM ledger_pln WHERE id='t';",
        )
        assertEquals(listOf(listOf("10000", "2702")), rows)
    }

    @Test
    fun `a row that is not a transfer has no far leg`() {
        val rows = query(
            accounts(Triple("pln", "PLN", 0)) + expense("e", 100_00, "pln", "PLN"),
            "SELECT transferAccountMinor FROM ledger_pln WHERE id='e';",
        )
        assertEquals(listOf(listOf(null)), rows)
    }

    // --- the balance, which is the one sum not in złoty ---------------------

    @Test
    fun `an account balance adds up its own currency, not whatever was typed`() {
        // THE reported bug. Before 0.23.2 this query summed raw amountMinor, so
        // a 100 zł purchase on a euro card made the tile read "-100,00 €" and,
        // converted, "-438,55 zł" — four times the money that was spent.
        val rows = query(
            accounts(Triple("eur", "EUR", 0)) +
                expense("a", 100_00, "eur", "PLN") +
                expense("b", 15_00, "eur", "EUR"),
            daoQuery("accountBalances").replace("\"\"\"", "") + ";",
        )
        // -22,80 € for the złoty purchase, -15,00 € for the euro one.
        val balance = rows.single()
        assertEquals("-3780", balance[4])
        // Valued at one rate, today's: 37,80 € × 4,3855. One grosz under the
        // 165,78 the two rows converted individually come to, because a balance
        // is a position now and not a sum of historical conversions. See
        // ADR 0022.
        assertEquals("-16577", balance.last())
    }

    @Test
    fun `an opening balance is already in the account's own currency`() {
        // It is not converted on the way in, which is what makes it addable to
        // the converted rows.
        val rows = query(
            accounts(Triple("eur", "EUR", 1_000_00)) + expense("a", 100_00, "eur", "PLN"),
            daoQuery("accountBalances").replace("\"\"\"", "") + ";",
        )
        assertEquals("97720", rows.single()[4])
    }

    @Test
    fun `a zloty account is untouched by any of this`() {
        // The overwhelming majority of rows, and the regression that would be
        // least forgivable: no rate is consulted and the arithmetic is exact.
        val rows = query(
            accounts(Triple("pln", "PLN", 500_00)) +
                expense("a", 89_99, "pln", "PLN") +
                expense("b", 10_01, "pln", "PLN"),
            daoQuery("accountBalances").replace("\"\"\"", "") + ";",
        )
        val balance = rows.single()
        assertEquals("40000", balance[4])
        assertEquals("40000", balance.last())
    }

    @Test
    fun `a row whose rate is unknown is left out rather than counted at face value`() {
        // The honest degradation: the balance understates by that row instead of
        // adding kronor to euro. Nothing on the strip claims otherwise — the
        // tile shows what IS known.
        val rows = query(
            accounts(Triple("eur", "EUR", 50_00)) +
                expense("a", 15_00, "eur", "SEK"),
            daoQuery("accountBalances").replace("\"\"\"", "") + ";",
        )
        assertEquals("5000", rows.single()[4])
    }

    @Test
    fun `the harness is reading the real SQL`() {
        // If either extractor silently returned something empty, every test
        // above would be asserting on an empty result set rather than on the
        // app's own SQL.
        assertTrue("view SQL looks empty", ledgerViewSql().contains("accountMinor"))
        assertTrue(
            "balance query looks empty",
            daoQuery("accountBalances").contains("transferAccountMinor"),
        )
        assertNull(
            "sqlite3 must be on PATH; this suite has no other runner",
            runCatching { ProcessBuilder("sqlite3", "-version").start().waitFor() }.exceptionOrNull(),
        )
    }
}
