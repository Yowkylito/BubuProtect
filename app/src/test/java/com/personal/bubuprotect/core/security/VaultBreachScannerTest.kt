package com.personal.bubuprotect.core.security

import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.BreachVerdict
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultDraft
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.domain.repository.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VaultBreachScannerTest {

    @Test
    fun `checks only password-shaped kinds and records each verdict`() = runBlocking {
        val repository = FakeRepository(
            entries = listOf(
                entry("login", ItemKind.LOGIN, "hunter2"),
                entry("wifi", ItemKind.WIFI, "network-key"),
                entry("card", ItemKind.CARD, "4242424242424242"),
                entry("note", ItemKind.NOTE, "a secret")
            )
        )
        val scanner = VaultBreachScanner(
            repository = repository,
            checker = checkerReturning(mapOf("hunter2" to 12L)),
            clock = { NOW }
        )

        val states = scanner.scan(repository.items(), force = true).toList()

        val finished = states.filterIsInstance<BreachScanState.Finished>().single()
        assertEquals(2, finished.checked)
        assertEquals(1, finished.breached)
        assertEquals(0, finished.skipped)

        // A card number and a note are never sent anywhere.
        assertEquals(setOf("login", "wifi"), repository.recorded.keys)
        assertEquals(12L, repository.recorded.getValue("login"))
        assertEquals(0L, repository.recorded.getValue("wifi"))
    }

    @Test
    fun `skips entries with a current verdict unless forced`() = runBlocking {
        val repository = FakeRepository(
            entries = listOf(
                entry("fresh", ItemKind.LOGIN, "a"),
                entry("never", ItemKind.LOGIN, "b")
            )
        )
        val items = listOf(
            item("fresh", BreachStatus(BreachVerdict.SAFE, checkedAt = NOW - 1_000L)),
            item("never", BreachStatus.Unchecked)
        )
        val scanner = VaultBreachScanner(repository, checkerReturning(emptyMap()), clock = { NOW })

        val finished = scanner.scan(items, force = false)
            .toList()
            .filterIsInstance<BreachScanState.Finished>()
            .single()

        assertEquals(1, finished.checked)
        assertEquals(setOf("never"), repository.recorded.keys)
    }

    @Test
    fun `records against the secret's own timestamp, not the row's`() = runBlocking {
        val repository = FakeRepository(
            entries = listOf(
                entry("login", ItemKind.LOGIN, "hunter2", updatedAt = 900L, secretUpdatedAt = 100L)
            )
        )
        val scanner = VaultBreachScanner(repository, checkerReturning(emptyMap()), clock = { NOW })

        scanner.scan(repository.items(), force = true).toList()

        // 100, not 900: a rename bumped updated_at, but the password is the one checked at 100, and
        // guarding on the wrong column would make every verdict fail to stick after any edit.
        assertEquals(100L, repository.recordedAgainst.getValue("login"))
    }

    @Test
    fun `gives up after repeated network failures rather than grinding through the vault`() =
        runBlocking {
            val repository = FakeRepository(
                entries = (1..10).map { entry("e$it", ItemKind.LOGIN, "p$it") }
            )
            val scanner = VaultBreachScanner(
                repository = repository,
                checker = PwnedPasswordChecker(PwnedPasswordsClient { _, _ -> throw IOException("offline") }),
                clock = { NOW }
            )

            val states = scanner.scan(repository.items(), force = true).toList()

            val failed = states.filterIsInstance<BreachScanState.Failed>().single()
            assertEquals(BreachScanFailure.UNREACHABLE, failed.reason)
            assertTrue(repository.recorded.isEmpty())
        }

    @Test
    fun `one bad lookup skips one entry and the scan carries on`() = runBlocking {
        val repository = FakeRepository(
            entries = listOf(
                entry("a", ItemKind.LOGIN, "good-1"),
                entry("b", ItemKind.LOGIN, "bad"),
                entry("c", ItemKind.LOGIN, "good-2")
            )
        )
        val scanner = VaultBreachScanner(
            repository = repository,
            checker = PwnedPasswordChecker(
                PwnedPasswordsClient { prefix, _ ->
                    if (prefix == prefixOf("bad")) throw IOException("flaky") else 0L
                }
            ),
            clock = { NOW }
        )

        val finished = scanner.scan(repository.items(), force = true)
            .toList()
            .filterIsInstance<BreachScanState.Finished>()
            .single()

        assertEquals(2, finished.checked)
        assertEquals(1, finished.skipped)
        assertEquals(setOf("a", "c"), repository.recorded.keys)
    }

    @Test
    fun `an empty target list finishes immediately without a request`() = runBlocking {
        val repository = FakeRepository(entries = emptyList())
        var calls = 0
        val scanner = VaultBreachScanner(
            repository = repository,
            checker = PwnedPasswordChecker(PwnedPasswordsClient { _, _ -> calls++; 0L }),
            clock = { NOW }
        )

        val states = scanner.scan(emptyList(), force = true).toList()

        assertEquals(
            listOf(BreachScanState.Finished(checked = 0, breached = 0, skipped = 0)),
            states
        )
        assertEquals(0, calls)
    }

    // --- Fixtures --------------------------------------------------------------------------------

    private companion object {
        const val NOW = 1_700_000_000_000L
    }

    /** Maps plaintext -> exposure count, keyed by the prefix the checker actually sends. */
    private fun checkerReturning(counts: Map<String, Long>): PwnedPasswordChecker {
        val byPrefix = counts.mapKeys { (password, _) -> prefixOf(password) }
        return PwnedPasswordChecker(PwnedPasswordsClient { prefix, _ -> byPrefix[prefix] ?: 0L })
    }

    private fun prefixOf(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(password.toByteArray(Charsets.UTF_8))
        return digest.take(3)
            .joinToString("") { "%02X".format(it) }
            .substring(0, 5)
    }

    private fun entry(
        id: String,
        kind: ItemKind,
        secret: String,
        updatedAt: Long = 100L,
        secretUpdatedAt: Long = updatedAt
    ) = VaultEntry(
        id = id,
        kind = kind,
        label = id,
        identity = "",
        secret = secret,
        updatedAt = updatedAt,
        secretUpdatedAt = secretUpdatedAt
    )

    private fun item(id: String, breach: BreachStatus) = VaultItem(
        id = id,
        kind = ItemKind.LOGIN,
        label = id,
        subtitle = "",
        breach = breach
    )

    private class FakeRepository(private val entries: List<VaultEntry>) : VaultRepository {
        val recorded = linkedMapOf<String, Long>()
        val recordedAgainst = linkedMapOf<String, Long>()

        fun items(): List<VaultItem> = entries.map {
            VaultItem(
                id = it.id,
                kind = it.kind,
                label = it.label,
                subtitle = it.identity,
                breach = it.breach,
                updatedAt = it.updatedAt
            )
        }

        override fun observeItems(): Flow<List<VaultItem>> = flowOf(items())

        override suspend fun getEntry(id: String): VaultEntry? = entries.firstOrNull { it.id == id }

        override suspend fun recordBreachCheck(
            id: String,
            exposureCount: Long,
            secretUpdatedAt: Long
        ): Boolean {
            recorded[id] = exposureCount
            recordedAgainst[id] = secretUpdatedAt
            return true
        }

        override suspend fun save(draft: VaultDraft): String = error("not used")
        override suspend fun delete(id: String) = error("not used")
        override suspend fun count(): Int = entries.size
        override suspend fun acknowledgeBreach(id: String) = error("not used")
        override suspend fun acknowledgeAllBreaches() = error("not used")
        override suspend fun exportEntries(): List<VaultEntry> = entries
        override suspend fun restoreEntries(entries: List<VaultEntry>): Int = error("not used")
        override suspend fun linkedEntryIds(targetKey: String, signature: String?): Set<String> =
            emptySet()

        override suspend fun rememberAutofillLink(
            targetKey: String,
            entryId: String,
            signature: String?
        ) = error("not used")
    }
}
