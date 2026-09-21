package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager

class ScScannerActivity : HelperBaseActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_none
        )

        importQRcode()
    }

    private fun importQRcode() {

        launchQRCodeScanner { scanResult ->

            if (
                scanResult != null
            ) {

                val (
                    count,
                    countSub
                ) =
                    AngConfigManager
                        .importBatchConfig(
                            scanResult,
                            "",
                            false
                        )

                if (
                    count + countSub >
                    0
                ) {

                    toastSuccess(
                        R.string.toast_success
                    )

                } else {

                    toastError(
                        R.string.toast_failure
                    )
                }

                val mainIntent =
                    Intent().apply {

                        setClassName(
                            applicationContext,
                            "com.v2ray.ang.ui.MainActivity"
                        )

                        addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }

                startActivity(
                    mainIntent
                )
            }

            finish()
        }
    }
}
