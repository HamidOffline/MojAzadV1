package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivitySubEditBinding
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubEditActivity : BaseActivity() {

    private val binding by lazy {
        ActivitySubEditBinding.inflate(layoutInflater)
    }

    private var del_config: MenuItem? = null
    private var save_config: MenuItem? = null

    private val editSubId by lazy {
        intent.getStringExtra("subId").orEmpty()
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.title_sub_setting)
        )

        setupProfileRemarkInputs()

        SettingsChangeManager.makeSetupGroupTab()

        val subItem =
            MmkvManager.decodeSubscription(
                editSubId
            )

        if (subItem != null) {
            bindingServer(subItem)
        } else {
            clearServer()
        }
    }

    /**
     * Bind existing subscription.
     */
    private fun bindingServer(
        subItem: SubscriptionItem
    ): Boolean {

        binding.etRemarks.text =
            Utils.getEditable(
                subItem.remarks
            )

        binding.etUrl.text =
            Utils.getEditable(
                subItem.url
            )

        binding.etUserAgent.text =
            Utils.getEditable(
                subItem.userAgent
            )

        binding.etFilter.text =
            Utils.getEditable(
                subItem.filter
            )

        binding.chkEnable.isChecked =
            subItem.enabled

        binding.autoUpdateCheck.isChecked =
            subItem.autoUpdate

        binding.etUpdateInterval.text =
            Utils.getEditable(
                subItem.updateInterval.toString()
            )

        binding.allowInsecureUrl.isChecked =
            subItem.allowInsecureUrl

        binding.etPreProfile.text =
            Utils.getEditable(
                subItem.prevProfile
            )

        binding.etNextProfile.text =
            Utils.getEditable(
                subItem.nextProfile
            )

        return true
    }

    /**
     * Defaults for a new MojAzad subscription.
     */
    private fun clearServer(): Boolean {

        binding.etRemarks.text = null
        binding.etUrl.text = null
        binding.etUserAgent.text = null
        binding.etFilter.text = null

        binding.chkEnable.isChecked = true

        /*
         * MojAzad:
         * new subscriptions update automatically.
         */
        binding.autoUpdateCheck.isChecked = true

        binding.etUpdateInterval.text =
            Utils.getEditable("60")

        binding.allowInsecureUrl.isChecked = false

        binding.etPreProfile.text = null
        binding.etNextProfile.text = null

        return true
    }

    private fun setupProfileRemarkInputs() {

        val suggestions =
            SettingsManager.getProfileRemarks(
                excludeConfigTypes =
                    setOf(
                        EConfigType.CUSTOM,
                        EConfigType.POLICYGROUP,
                        EConfigType.PROXYCHAIN
                    )
            )

        setupProfileRemarkInput(
            binding.etPreProfile,
            binding.btnPreProfileDropdown,
            suggestions
        )

        setupProfileRemarkInput(
            binding.etNextProfile,
            binding.btnNextProfileDropdown,
            suggestions
        )
    }

    private fun setupProfileRemarkInput(
        input: AutoCompleteTextView,
        dropdownButton: ImageButton,
        suggestions: List<String>
    ) {

        val adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                suggestions
            )

        input.setAdapter(adapter)
        input.threshold = 0

        dropdownButton.setOnClickListener {

            input.requestFocus()
            input.showDropDown()
        }

        input.setOnClickListener {

            input.showDropDown()
        }
    }

    /**
     * Save subscription.
     *
     * New subscriptions get their own UUID.
     * That UUID is returned to SubSettingActivity.
     */
    private fun saveServer(): Boolean {

        val isNewSubscription =
            editSubId.isBlank()

        /*
         * Existing subscription keeps its ID.
         * New subscription gets a new local UUID.
         */
        val targetSubId =
            if (isNewSubscription) {
                Utils.getUuid()
            } else {
                editSubId
            }

        val subItem =
            if (isNewSubscription) {

                SubscriptionItem()

            } else {

                MmkvManager
                    .decodeSubscription(
                        editSubId
                    )
                    ?: SubscriptionItem()
            }

        subItem.remarks =
            binding.etRemarks
                .text
                .toString()
                .trim()

        subItem.url =
            binding.etUrl
                .text
                .toString()
                .trim()

        subItem.userAgent =
            binding.etUserAgent
                .text
                .toString()
                .trim()

        subItem.filter =
            binding.etFilter
                .text
                .toString()
                .trim()

        subItem.enabled =
            binding.chkEnable
                .isChecked

        subItem.autoUpdate =
            binding.autoUpdateCheck
                .isChecked

        val intervalInput =
            binding.etUpdateInterval
                .text
                .toString()
                .trim()

        val intervalMinutes =
            intervalInput
                .toLongOrNull()

        if (subItem.autoUpdate) {

            if (intervalMinutes == null) {

                subItem.updateInterval =
                    SubscriptionItem()
                        .updateInterval

            } else if (
                intervalMinutes <
                AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES
            ) {

                toast(
                    R.string.toast_invalid_update_interval
                )

                return false

            } else {

                subItem.updateInterval =
                    intervalMinutes
            }

        } else {

            if (
                intervalMinutes != null &&
                intervalMinutes >=
                AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES
            ) {

                subItem.updateInterval =
                    intervalMinutes
            }
        }

        subItem.prevProfile =
            binding.etPreProfile
                .text
                .toString()
                .trim()

        subItem.nextProfile =
            binding.etNextProfile
                .text
                .toString()
                .trim()

        subItem.allowInsecureUrl =
            binding.allowInsecureUrl
                .isChecked

        if (
            TextUtils.isEmpty(
                subItem.remarks
            )
        ) {

            toast(
                R.string.sub_setting_remarks
            )

            return false
        }

        if (
            subItem.url.isNotEmpty()
        ) {

            if (
                !Utils.isValidUrl(
                    subItem.url
                )
            ) {

                toast(
                    R.string.toast_invalid_url
                )

                return false
            }

            if (
                !Utils.isValidSubUrl(
                    subItem.url
                )
            ) {

                toast(
                    R.string.toast_insecure_url_protocol
                )

                if (
                    !subItem.allowInsecureUrl
                ) {

                    return false
                }
            }
        }

        /*
         * Save subscription using its real ID.
         */
        MmkvManager.encodeSubscription(
            targetSubId,
            subItem
        )

        /*
         * Keep periodic update scheduling enabled.
         */
        SubscriptionUpdater.syncOne(
            subId = targetSubId
        )

        SettingsChangeManager
            .makeSetupGroupTab()

        /*
         * Return the subscription ID.
         */
        setResult(
            RESULT_OK,
            Intent().apply {

                putExtra(
                    SubSettingActivity.EXTRA_SUB_ID,
                    targetSubId
                )

                putExtra(
                    SubSettingActivity.EXTRA_IS_NEW_SUB,
                    isNewSubscription
                )
            }
        )

        toastSuccess(
            R.string.toast_success
        )

        finish()

        return true
    }

    /**
     * Delete existing subscription.
     */
    private fun deleteServer(): Boolean {

        if (
            editSubId.isNotEmpty()
        ) {

            if (
                MmkvManager.decodeSettingsBool(
                    AppConfig.PREF_CONFIRM_REMOVE
                )
            ) {

                AlertDialog.Builder(this)
                    .setMessage(
                        R.string.del_config_comfirm
                    )
                    .setPositiveButton(
                        android.R.string.ok
                    ) { _, _ ->

                        lifecycleScope.launch(
                            Dispatchers.IO
                        ) {

                            SettingsManager
                                .removeSubscriptionWithDefault(
                                    editSubId
                                )

                            launch(
                                Dispatchers.Main
                            ) {

                                SettingsChangeManager
                                    .makeSetupGroupTab()

                                finish()
                            }
                        }
                    }
                    .setNegativeButton(
                        android.R.string.cancel,
                        null
                    )
                    .show()

            } else {

                lifecycleScope.launch(
                    Dispatchers.IO
                ) {

                    SettingsManager
                        .removeSubscriptionWithDefault(
                            editSubId
                        )

                    launch(
                        Dispatchers.Main
                    ) {

                        SettingsChangeManager
                            .makeSetupGroupTab()

                        finish()
                    }
                }
            }
        }

        return true
    }

    override fun onCreateOptionsMenu(
        menu: Menu
    ): Boolean {

        menuInflater.inflate(
            R.menu.action_server,
            menu
        )

        del_config =
            menu.findItem(
                R.id.del_config
            )

        save_config =
            menu.findItem(
                R.id.save_config
            )

        /*
         * A brand-new subscription
         * cannot be deleted yet.
         */
        del_config?.isVisible =
            editSubId.isNotBlank()

        return super
            .onCreateOptionsMenu(
                menu
            )
    }

    override fun onOptionsItemSelected(
        item: MenuItem
    ) =
        when (
            item.itemId
        ) {

            R.id.del_config -> {

                deleteServer()
                true
            }

            R.id.save_config -> {

                saveServer()
                true
            }

            else ->

                super.onOptionsItemSelected(
                    item
                )
        }
}
