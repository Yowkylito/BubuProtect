package com.personal.bubuprotect.core.recovery

import com.personal.bubuprotect.core.crypto.RecoveryCode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The page a user prints, writes on, or files away.
 *
 * ### Why plain text
 *
 * Not a PDF. This has to survive being printed, emailed to a printer, opened on a desktop years from
 * now, read aloud over the phone to a family member, or pasted into a text field - and plain text
 * does all of that with no reader, no fonts and no format that can rot. A PDF would look nicer once
 * and be worse at every one of those.
 *
 * ### What is deliberately not on it
 *
 * The master passphrase. Not because writing a passphrase down is wrong - for a page kept somewhere
 * safe it is far better than forgetting it - but because the recovery code already opens the vault
 * completely. A second secret on the same sheet would add no access the first does not already grant,
 * while doubling what is lost if the sheet is photographed.
 *
 * Nor is there any hint of *what* is in the vault: no entry count, no site names, no device name. A
 * page that says "recovery kit, 84 accounts" tells whoever finds it that it is worth pursuing.
 */
object RecoveryKit {

    const val MIME_TYPE = "text/plain"
    const val FILE_EXTENSION = "txt"

    /**
     * The kit body.
     *
     * @param code the formatted recovery code, exactly as [RecoveryCode.formatted] produced it.
     * @param createdAt when the kit was minted, so two kits in a drawer can be told apart.
     */
    fun render(code: String, createdAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val created = HUMAN_DATE.format(Instant.ofEpochMilli(createdAt).atZone(zone))
        return """
            BUBU PROTECT - RECOVERY KIT
            ===========================

            Created: $created

            YOUR RECOVERY CODE

                $code

            WHAT THIS IS

            This code is a second key to your Bubu Protect vault. If you forget your
            master passphrase, this is the only way back in - and it is the only one.
            Nobody can reset it for you. There is no support line, no email link, and
            no copy of it anywhere else. That is what makes the vault private, and it
            is also why this page matters.

            HOW TO USE IT

            1. Open Bubu Protect. On the lock screen, choose "Forgot your passphrase?".
            2. Confirm with the fingerprint or face unlock on that phone.
            3. Type the code above. Upper or lower case, dashes or no dashes - all fine.
               If you read a 0 as an O, or a 1 as an I or l, it will still work.
            4. You will be asked to set a new master passphrase straight away.

            WHY STEP 2 EXISTS

            This code alone opens the vault, so a phone that only asked for the code would be opened
            by anyone who found this page - or who found a copy of it saved on the phone itself. The
            fingerprint check is bound to the exact fingerprints that were set up when this kit was
            made, so changing or removing them does not get around it; it seals recovery instead.

            If that happens - you replaced a fingerprint, or reset the phone - recovery will say it is
            sealed. Unlock once with your master passphrase and it switches back on. That is why this
            page is a second way in, not a replacement for remembering your passphrase.

            WHERE TO KEEP IT

            Anyone holding this code can open your vault, so treat the page like a
            spare house key: somewhere private, and somewhere you will still be able to
            find it in five years. A locked drawer, a home safe, or with a document you
            would never throw away. If you keep a copy with someone you trust, they can
            reach your accounts if something happens to you - which for many people is
            the point of having this at all.

            Do not store it as a photo or a note on the phone that holds the vault.
            The two together are the whole lock and the whole key in one place.

            IF YOU LOSE THIS PAGE

            Open Bubu Protect, go to Settings, and create a new recovery kit. Making a
            new one immediately stops this code from working, so a lost or copied page
            becomes harmless.
        """.trimIndent()
    }

    /**
     * @param dateStamp an ISO date, from [dateStamp].
     *
     * The name says "recovery-kit" and carries no account or device detail, for the same reason the
     * body does not: a filename is visible in a folder listing, a sync notification and a share
     * sheet, long before anyone opens the file.
     */
    fun fileName(dateStamp: String): String = "bubu-recovery-kit-$dateStamp.$FILE_EXTENSION"

    fun dateStamp(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(at).atZone(zone))

    /**
     * Locale-aware, unlike [dateStamp].
     *
     * The filename wants a sortable machine date; the page is read by a person and should say the
     * date the way they write dates. `Locale.getDefault()` is captured per call rather than held, so
     * a kit rendered after the user changes language comes out in the new one.
     */
    private val HUMAN_DATE: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
}
