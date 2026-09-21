package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {

    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2

        /*
         * MojAzad V3 Server Health
         *
         * 1 - 200 ms   = Excellent
         * 201 - 400 ms = Good
         * 401+ ms      = Weak
         * Negative     = Offline
         * 0            = Not tested
         */
        private const val HEALTH_EXCELLENT_MAX = 200L
        private const val HEALTH_GOOD_MAX = 400L

        private const val HEALTH_COLOR_EXCELLENT = "#00A86B"
        private const val HEALTH_COLOR_GOOD = "#0878E8"
        private const val HEALTH_COLOR_WEAK = "#F59E0B"
        private const val HEALTH_COLOR_OFFLINE = "#E53935"
    }

    private val doubleColumnDisplay =
        MmkvManager.decodeSettingsBool(
            AppConfig.PREF_DOUBLE_COLUMN_DISPLAY,
            false
        )

    private var data: MutableList<ServersCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(
        newData: MutableList<ServersCache>?,
        position: Int = -1
    ) {
        data = newData?.toMutableList() ?: mutableListOf()

        if (position >= 0 && position in data.indices) {
            notifyItemChanged(position)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int {
        return data.size + 1
    }

    override fun onBindViewHolder(
        holder: BaseViewHolder,
        position: Int
    ) {
        if (holder is MainViewHolder) {

            val context =
                holder.itemMainBinding.root.context

            val guid =
                data[position].guid

            val profile =
                data[position].profile

            holder.itemView.setBackgroundColor(
                Color.TRANSPARENT
            )

            /*
             * Server information
             */
            holder.itemMainBinding.tvName.text =
                profile.remarks

            holder.itemMainBinding.tvStatistics.text =
                getAddress(profile)

            holder.itemMainBinding.tvType.text =
                getProtocolDescription(profile)

            /*
             * Ping
             */
            val aff =
                MmkvManager.decodeServerAffiliationInfo(
                    guid
                )

            val delay =
                aff?.testDelayMillis ?: 0L

            holder.itemMainBinding.tvTestResult.text =
                aff?.getTestDelayString().orEmpty()

            if (delay < 0L) {

                holder.itemMainBinding.tvTestResult
                    .setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.colorPingRed
                        )
                    )

            } else {

                holder.itemMainBinding.tvTestResult
                    .setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.colorPing
                        )
                    )
            }

            /*
             * MojAzad V3 Server Health
             */
            bindServerHealth(
                holder = holder,
                delay = delay
            )

            /*
             * Selected server
             */
            if (guid == MmkvManager.getSelectServer()) {

                holder.itemMainBinding.layoutIndicator
                    .setBackgroundResource(
                        R.color.colorIndicator
                    )

            } else {

                holder.itemMainBinding.layoutIndicator
                    .setBackgroundResource(
                        0
                    )
            }

            /*
             * Subscription
             */
            val subRemarks =
                getSubscriptionRemarks(
                    profile
                )

            holder.itemMainBinding.tvSubscription.text =
                subRemarks

            holder.itemMainBinding.layoutSubscription.visibility =
                if (subRemarks.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

            /*
             * Actions
             */
            if (doubleColumnDisplay) {

                holder.itemMainBinding.layoutShare.visibility =
                    View.GONE

                holder.itemMainBinding.layoutEdit.visibility =
                    View.GONE

                holder.itemMainBinding.layoutRemove.visibility =
                    View.GONE

                holder.itemMainBinding.layoutMore.visibility =
                    View.VISIBLE

                holder.itemMainBinding.layoutMore
                    .setOnClickListener {

                        adapterListener?.onShare(
                            guid,
                            profile,
                            position,
                            true
                        )
                    }

            } else {

                holder.itemMainBinding.layoutShare.visibility =
                    View.VISIBLE

                holder.itemMainBinding.layoutEdit.visibility =
                    View.VISIBLE

                holder.itemMainBinding.layoutRemove.visibility =
                    View.VISIBLE

                holder.itemMainBinding.layoutMore.visibility =
                    View.GONE

                holder.itemMainBinding.layoutShare
                    .setOnClickListener {

                        adapterListener?.onShare(
                            guid,
                            profile,
                            position,
                            false
                        )
                    }

                holder.itemMainBinding.layoutEdit
                    .setOnClickListener {

                        adapterListener?.onEdit(
                            guid,
                            position,
                            profile
                        )
                    }

                holder.itemMainBinding.layoutRemove
                    .setOnClickListener {

                        adapterListener?.onRemove(
                            guid,
                            position
                        )
                    }
            }

            holder.itemMainBinding.infoContainer
                .setOnClickListener {

                    adapterListener?.onSelectServer(
                        guid
                    )
                }
        }
    }

    /*
     * MojAzad V3
     *
     * Health icon:
     *
     * Excellent = Green check
     * Good      = Blue info
     * Weak      = Orange warning
     * Offline   = Red cross
     */
    private fun bindServerHealth(
        holder: MainViewHolder,
        delay: Long
    ) {

        val healthIcon =
            holder.itemMainBinding.ivServerHealth

        /*
         * Server has not been tested yet.
         */
        if (delay == 0L) {

            healthIcon.visibility =
                View.GONE

            healthIcon.contentDescription =
                null

            return
        }

        healthIcon.visibility =
            View.VISIBLE

        when {

            /*
             * Offline
             */
            delay < 0L -> {

                healthIcon.setImageResource(
                    R.drawable.ic_health_offline
                )

                healthIcon.imageTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(
                            HEALTH_COLOR_OFFLINE
                        )
                    )

                healthIcon.contentDescription =
                    "Server health: Offline"
            }

            /*
             * Excellent
             */
            delay <= HEALTH_EXCELLENT_MAX -> {

                healthIcon.setImageResource(
                    R.drawable.ic_health_excellent
                )

                healthIcon.imageTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(
                            HEALTH_COLOR_EXCELLENT
                        )
                    )

                healthIcon.contentDescription =
                    "Server health: Excellent"
            }

            /*
             * Good
             */
            delay <= HEALTH_GOOD_MAX -> {

                healthIcon.setImageResource(
                    R.drawable.ic_health_good
                )

                healthIcon.imageTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(
                            HEALTH_COLOR_GOOD
                        )
                    )

                healthIcon.contentDescription =
                    "Server health: Good"
            }

            /*
             * Weak
             */
            else -> {

                healthIcon.setImageResource(
                    R.drawable.ic_health_weak
                )

                healthIcon.imageTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(
                            HEALTH_COLOR_WEAK
                        )
                    )

                healthIcon.contentDescription =
                    "Server health: Weak"
            }
        }
    }

    /**
     * Gets server address.
     */
    private fun getAddress(
        profile: ProfileItem
    ): String {

        return profile.description
            .nullIfBlank()
            ?: AngConfigManager.generateDescription(
                profile
            )
    }

    /**
     * Gets subscription remarks.
     */
    private fun getSubscriptionRemarks(
        profile: ProfileItem
    ): String {

        val subRemarks =
            if (mainViewModel.subscriptionId.isEmpty()) {

                MmkvManager
                    .decodeSubscription(
                        profile.subscriptionId
                    )
                    ?.remarks
                    ?.firstOrNull()

            } else {

                null
            }

        return subRemarks?.toString()
            ?: ""
    }

    private fun getProtocolDescription(
        profile: ProfileItem
    ): String {

        if (profile.configType.isComplexType()) {
            return profile.configType.name
        }

        val parts =
            mutableListOf<String>()

        parts.add(
            profile.configType.name
        )

        /*
         * Transport
         */
        profile.network?.let { net ->

            if (
                net.isNotBlank() &&
                !net.equals(
                    "tcp",
                    ignoreCase = true
                )
            ) {

                parts.add(
                    net
                )
            }
        }

        /*
         * Security
         */
        profile.security?.let { sec ->

            if (sec.isNotBlank()) {

                if (
                    profile.insecure == true &&
                    sec.equals(
                        "tls",
                        ignoreCase = true
                    )
                ) {

                    parts.add(
                        "$sec insecure"
                    )

                } else {

                    parts.add(
                        sec
                    )
                }
            }
        }

        return parts.joinToString(
            " / "
        )
    }

    fun removeServerSub(
        guid: String,
        position: Int
    ) {

        val idx =
            data.indexOfFirst {
                it.guid == guid
            }

        if (idx >= 0) {

            data.removeAt(
                idx
            )

            notifyItemRemoved(
                idx
            )

            notifyItemRangeChanged(
                idx,
                data.size - idx
            )
        }
    }

    fun setSelectServer(
        fromPosition: Int,
        toPosition: Int
    ) {

        notifyItemChanged(
            fromPosition
        )

        notifyItemChanged(
            toPosition
        )
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): BaseViewHolder {

        return when (viewType) {

            VIEW_TYPE_ITEM -> {

                MainViewHolder(
                    ItemRecyclerMainBinding.inflate(
                        LayoutInflater.from(
                            parent.context
                        ),
                        parent,
                        false
                    )
                )
            }

            else -> {

                FooterViewHolder(
                    ItemRecyclerFooterBinding.inflate(
                        LayoutInflater.from(
                            parent.context
                        ),
                        parent,
                        false
                    )
                )
            }
        }
    }

    override fun getItemViewType(
        position: Int
    ): Int {

        return if (position == data.size) {

            VIEW_TYPE_FOOTER

        } else {

            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(
        itemView
    ) {

        fun onItemSelected() {

            itemView.setBackgroundColor(
                Color.LTGRAY
            )
        }

        fun onItemClear() {

            itemView.setBackgroundColor(
                0
            )
        }
    }

    class MainViewHolder(
        val itemMainBinding:
            ItemRecyclerMainBinding
    ) : BaseViewHolder(
        itemMainBinding.root
    ),
        ItemTouchHelperViewHolder

    class FooterViewHolder(
        val itemFooterBinding:
            ItemRecyclerFooterBinding
    ) : BaseViewHolder(
        itemFooterBinding.root
    )

    override fun onItemMove(
        fromPosition: Int,
        toPosition: Int
    ): Boolean {

        mainViewModel.swapServer(
            fromPosition,
            toPosition
        )

        if (
            fromPosition < data.size &&
            toPosition < data.size
        ) {

            Collections.swap(
                data,
                fromPosition,
                toPosition
            )
        }

        notifyItemMoved(
            fromPosition,
            toPosition
        )

        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(
        position: Int
    ) {
        // do nothing
    }
}
