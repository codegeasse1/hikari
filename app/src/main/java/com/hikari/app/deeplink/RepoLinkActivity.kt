package com.hikari.app.deeplink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.hikari.app.MainActivity

/**
 * The "Open with → Hikari" entry for extension-repo links tapped in a browser.
 *
 * CloudStream shows itself for repo links the same way (and Android offers a
 * chooser when several apps on the device understand the link): this activity
 * exists only to own those intent-filters (see the manifest — `*.json` over
 * http/https plus the `stremio:` scheme) and to hand the link to the real app.
 * It draws nothing and keeps no history — it forwards the URL to
 * [MainActivity] (singleTask, so a running app gets it through onNewIntent)
 * and finishes. [MainActivity] publishes it as
 * [com.hikari.app.HikariApp.repoLinkRequest], AppNav switches to the
 * Extensions tab, and the Extensions screen installs it into the section it
 * belongs to (sniffed from the file's own content) and opens that repo's
 * folder.
 */
class RepoLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.data?.toString().orEmpty().trim()
        val next = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (url.isNotBlank()) {
            next.putExtra(EXTRA_REPO_LINK, url)
        }
        startActivity(next)
        finish()
    }

    companion object {
        const val EXTRA_REPO_LINK = "hikari_repo_link"
    }
}
