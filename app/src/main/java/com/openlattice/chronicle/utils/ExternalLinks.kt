package com.openlattice.chronicle.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.openlattice.chronicle.R

/** Opens an https link in the user's browser; tells the user when no browser can take it. */
public object ExternalLinks {
    private const val TAG = "ExternalLinks"

    public fun openHttps(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return false
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No browser available for an https link")
            Toast.makeText(context, R.string.no_browser_for_link, Toast.LENGTH_LONG).show()
            false
        }
    }
}
