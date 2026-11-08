package com.hikari.app.deeplink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.hikari.app.MainActivity

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
