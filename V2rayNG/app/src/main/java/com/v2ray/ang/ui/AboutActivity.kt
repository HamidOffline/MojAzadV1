package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityAboutBinding
import com.v2ray.ang.util.Utils

class AboutActivity : BaseActivity() {

    private val binding by lazy {
        ActivityAboutBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.title_about)
        )

        /*
         * MojAzad GitHub source code
         */
        binding.layoutSoureCcode.setOnClickListener {
            Utils.openUri(
                this,
                "https://github.com/HamidOffline/MojAzadV1"
            )
        }

        /*
         * Keep current feedback link for now.
         */
        binding.layoutFeedback.setOnClickListener {
            Utils.openUri(
                this,
                AppConfig.APP_ISSUES_URL
            )
        }

        /*
         * Open-source licenses
         */
        binding.layoutOssLicenses.setOnClickListener {

            val webView =
                android.webkit.WebView(this)

            webView.loadUrl(
                "file:///android_asset/open_source_licenses.html"
            )

            android.app.AlertDialog.Builder(this)
                .setTitle("Open source licenses")
                .setView(webView)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        /*
         * MojAzad Telegram channel
         */
        binding.layoutTgChannel.setOnClickListener {
            Utils.openUri(
                this,
                "https://t.me/MojAzadNet"
            )
        }

        /*
         * Privacy policy
         */
        binding.layoutPrivacyPolicy.setOnClickListener {
            Utils.openUri(
                this,
                AppConfig.APP_PRIVACY_POLICY
            )
        }

        /*
         * Version
         */
        "v${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        /*
         * Application ID
         */
        BuildConfig.APPLICATION_ID.also {
            binding.tvAppId.text = it
        }
    }
}
