package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivitySubSettingBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SubSettingActivity : BaseActivity() {

    companion object {

        const val EXTRA_SUB_ID =
            "mojazad_sub_id"

        const val EXTRA_IS_NEW_SUB =
            "mojazad_is_new_sub"
    }

    private val binding by lazy {
        ActivitySubSettingBinding.inflate(layoutInflater)
    }

    private val ownerActivity: SubSettingActivity
        get() = this

    private val viewModel: SubscriptionsViewModel by viewModels()

    private lateinit var adapter: SubSettingRecyclerAdapter

    private var mItemTouchHelper: ItemTouchHelper? = null

    private val share_method: Array<out String> by lazy {
        resources.getStringArray(
            R.array.share_sub_method
        )
    }

    /*
     * MojAzad:
     *
     * Open SubEditActivity for a result.
     *
     * When a NEW subscription is saved,
     * SubEditActivity will return its exact ID.
     *
     * We then forward that ID to MainActivity
     * and close Subscription Settings.
     *
     * Editing an existing subscription simply
     * refreshes this screen and stays here.
     */
    private val subEditLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            refreshData()

            if (
                result.resultCode != RESULT_OK
            ) {
                return@registerForActivityResult
            }

            val data =
                result.data
                    ?: return@registerForActivityResult

            val subId =
                data.getStringExtra(
                    EXTRA_SUB_ID
                ).orEmpty()

            val isNewSubscription =
                data.getBooleanExtra(
                    EXTRA_IS_NEW_SUB,
                    false
                )

            if (
                isNewSubscription &&
                subId.isNotBlank()
            ) {

                /*
                 * Return the newly-created subscription
                 * to MainActivity.
                 */
                setResult(
                    RESULT_OK,
                    Intent().apply {

                        putExtra(
                            EXTRA_SUB_ID,
                            subId
                        )

                        putExtra(
                            EXTRA_IS_NEW_SUB,
                            true
                        )
                    }
                )

                /*
                 * Go back to MojAzad main screen.
                 *
                 * MainActivity will later:
                 *
                 * Update this subscription
                 * -> Open its tab
                 * -> Ping
                 * -> Sort
                 * -> Select fastest
                 * -> Auto-connect.
                 */
                finish()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title =
                getString(
                    R.string.title_sub_setting
                )
        )

        adapter =
            SubSettingRecyclerAdapter(
                viewModel,
                ActivityAdapterListener()
            )

        binding.recyclerView
            .setHasFixedSize(
                true
            )

        binding.recyclerView.layoutManager =
            LinearLayoutManager(
                this
            )

        addCustomDividerToRecyclerView(
            binding.recyclerView,
            this,
            R.drawable.custom_divider
        )

        binding.recyclerView.adapter =
            adapter

        mItemTouchHelper =
            ItemTouchHelper(
                SimpleItemTouchHelperCallback(
                    adapter
                )
            )

        mItemTouchHelper
            ?.attachToRecyclerView(
                binding.recyclerView
            )
    }

    override fun onResume() {

        super.onResume()

        refreshData()
    }

    override fun onCreateOptionsMenu(
        menu: Menu
    ): Boolean {

        menuInflater.inflate(
            R.menu.action_sub_setting,
            menu
        )

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

            R.id.add_config -> {

                /*
                 * MojAzad:
                 * Launch new-subscription editor
                 * and wait for the generated ID.
                 */
                subEditLauncher.launch(
                    Intent(
                        this,
                        SubEditActivity::class.java
                    )
                )

                true
            }

            R.id.sub_update -> {

                showLoading()

                lifecycleScope.launch(
                    Dispatchers.IO
                ) {

                    val result =
                        AngConfigManager
                            .updateConfigViaSubAll()

                    delay(
                        500L
                    )

                    launch(
                        Dispatchers.Main
                    ) {

                        if (
                            result.successCount +
                            result.failureCount +
                            result.skipCount == 0
                        ) {

                            toast(
                                R.string
                                    .title_update_subscription_no_subscription
                            )

                        } else if (
                            result.successCount > 0 &&
                            result.failureCount +
                            result.skipCount == 0
                        ) {

                            toast(
                                getString(
                                    R.string.title_update_config_count,
                                    result.configCount
                                )
                            )

                        } else {

                            toast(
                                getString(
                                    R.string.title_update_subscription_result,
                                    result.configCount,
                                    result.successCount,
                                    result.failureCount,
                                    result.skipCount
                                )
                            )
                        }

                        hideLoading()

                        refreshData()
                    }
                }

                true
            }

            else ->

                super.onOptionsItemSelected(
                    item
                )
        }

    @SuppressLint(
        "NotifyDataSetChanged"
    )
    fun refreshData() {

        viewModel.reload()

        adapter.notifyDataSetChanged()
    }

    private inner class ActivityAdapterListener :
        BaseAdapterListener {

        override fun onEdit(
            guid: String,
            position: Int
        ) {

            /*
             * Editing an existing subscription
             * also uses the result launcher.
             *
             * Existing subscriptions will not
             * trigger MojAzad's new-sub auto flow.
             */
            subEditLauncher.launch(
                Intent(
                    ownerActivity,
                    SubEditActivity::class.java
                )
                    .putExtra(
                        "subId",
                        guid
                    )
            )
        }

        override fun onRemove(
            guid: String,
            position: Int
        ) {

            if (
                MmkvManager.decodeSettingsBool(
                    AppConfig.PREF_CONFIRM_REMOVE
                )
            ) {

                AlertDialog.Builder(
                    ownerActivity
                )
                    .setMessage(
                        R.string.del_config_comfirm
                    )
                    .setPositiveButton(
                        android.R.string.ok
                    ) { _, _ ->

                        viewModel.remove(
                            guid
                        )

                        refreshData()
                    }
                    .setNegativeButton(
                        android.R.string.cancel,
                        null
                    )
                    .show()

            } else {

                viewModel.remove(
                    guid
                )

                refreshData()
            }
        }

        override fun onShare(
            url: String
        ) {

            AlertDialog.Builder(
                ownerActivity
            )
                .setItems(
                    share_method
                        .asList()
                        .toTypedArray()
                ) { _, i ->

                    try {

                        when (
                            i
                        ) {

                            0 -> {

                                val ivBinding =
                                    ItemQrcodeBinding.inflate(
                                        LayoutInflater.from(
                                            ownerActivity
                                        )
                                    )

                                ivBinding
                                    .ivQcode
                                    .setImageBitmap(
                                        QRCodeDecoder
                                            .createQRCode(
                                                url
                                            )
                                    )

                                AlertDialog.Builder(
                                    ownerActivity
                                )
                                    .setView(
                                        ivBinding.root
                                    )
                                    .show()
                            }

                            1 -> {

                                Utils.setClipboard(
                                    ownerActivity,
                                    url
                                )
                            }

                            else -> {

                                ownerActivity.toast(
                                    "else"
                                )
                            }
                        }

                    } catch (
                        e: Exception
                    ) {

                        LogUtil.e(
                            AppConfig.TAG,
                            "Share subscription failed",
                            e
                        )
                    }
                }
                .show()
        }

        override fun onRefreshData() {

            refreshData()
        }
    }
}
