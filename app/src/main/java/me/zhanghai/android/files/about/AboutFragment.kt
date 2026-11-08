/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.about

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.databinding.AboutFragmentBinding
import me.zhanghai.android.files.ui.LicensesDialogFragment
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.startActivitySafe

class AboutFragment : Fragment() {
    private lateinit var binding: AboutFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        AboutFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // Card 1: OpenManager repository & licenses
        binding.gitHubLayout.setOnClickListener { startActivitySafe(GITHUB_URI.createViewIntent()) }
        binding.licensesLayout.setOnClickListener { LicensesDialogFragment.show(this) }
        binding.privacyPolicyLayout.setOnClickListener {
            startActivitySafe(PRIVACY_POLICY_URI.createViewIntent())
        }

        // Card 2: Author of OpenManager (Uamo11)
        binding.authorNameLayout.setOnClickListener {
            startActivitySafe(AUTHOR_WEB_URI.createViewIntent())
        }
        binding.authorWebsiteLayout.setOnClickListener {
            startActivitySafe(AUTHOR_WEB_URI.createViewIntent())
        }
        binding.authorGitHubLayout.setOnClickListener {
            startActivitySafe(AUTHOR_GITHUB_URI.createViewIntent())
        }

        // Card 3: Base Project & Acknowledgements (Hai Zhang / Material Files)
        binding.baseProjectLayout.setOnClickListener {
            startActivitySafe(BASE_PROJECT_GITHUB_URI.createViewIntent())
        }
        binding.baseAuthorLayout.setOnClickListener {
            startActivitySafe(BASE_AUTHOR_GITHUB_URI.createViewIntent())
        }
        binding.baseGitHubLayout.setOnClickListener {
            startActivitySafe(BASE_PROJECT_GITHUB_URI.createViewIntent())
        }

        val scrollToAcknowledgements = activity.intent.getBooleanExtra(
            AboutActivity.EXTRA_SCROLL_TO_ACKNOWLEDGEMENTS,
            false
        )
        if (scrollToAcknowledgements) {
            binding.scrollView.post {
                binding.scrollView.smoothScrollTo(0, binding.cardAcknowledgements.top)
            }
        }
    }

    companion object {
        private val GITHUB_URI = Uri.parse("https://github.com/uamo11/OpenManager")
        private val PRIVACY_POLICY_URI =
            Uri.parse("https://github.com/uamo11/OpenManager/blob/main/PRIVACY.md")
        private val AUTHOR_WEB_URI = Uri.parse("https://uam.lol")
        private val AUTHOR_GITHUB_URI = Uri.parse("https://github.com/uamo11")
        private val BASE_PROJECT_GITHUB_URI = Uri.parse("https://github.com/zhanghai/MaterialFiles")
        private val BASE_AUTHOR_GITHUB_URI = Uri.parse("https://github.com/zhanghai")
    }
}
