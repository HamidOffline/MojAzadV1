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

    companion object {
        private const val MOJAZAD_GITHUB =
            "https://github.com/HamidOffline/MojAzadV1"

        private const val MOJAZAD_TELEGRAM =
            "https://t.me/MojAzadNet"

        private const val MOJAZAD_ISSUES =
            "https://github.com/HamidOffline/MojAzadV1/issues"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.title_about)
        )

        /*
         * Open MojAzad GitHub source.
         */
        binding.layoutSoureCcode.setOnClickListener {
            Utils.openUri(
                this,
                MOJAZAD_GITHUB
            )
        }

        /*
         * Open MojAzad GitHub issues.
         */
        binding.layoutFeedback.setOnClickListener {
            Utils.openUri(
                this,
                MOJAZAD_ISSUES
            )
        }

        /*
         * Open source licenses.
         */
        binding.layoutOssLicenses.setOnClickListener {

            val webView =
                android.webkit.WebView(this)

            webView.loadUrl(
                "file:///android_asset/open_source_licenses.html"
            )

            android.app.AlertDialog.Builder(this)
                .setTitle(
                    "Open source licenses"
                )
                .setView(
                    webView
                )
                .setPositiveButton(
                    android.R.string.ok
                ) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        /*
         * MojAzad Telegram channel.
         */
        binding.layoutTgChannel.setOnClickListener {

            Utils.openUri(
                this,
                MOJAZAD_TELEGRAM
            )
        }

        /*
         * Privacy policy.
         */
        binding.layoutPrivacyPolicy.setOnClickListener {

            Utils.openUri(
                this,
                AppConfig.APP_PRIVACY_POLICY
            )
        }

        /*
         * App version.
         */
        binding.tvVersion.text =
            "v${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})"


        /*
         * Package name.
         */
        binding.tvAppId.text =
            BuildConfig.APPLICATION_ID
    }
}
