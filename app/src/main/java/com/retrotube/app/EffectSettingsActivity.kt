package com.retrotube.app

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.retrotube.app.databinding.ActivityMainBinding
import com.retrotube.app.settings.SettingsRepository
import com.retrotube.app.settings.VideoEffectSettings
import com.retrotube.app.shader.DownscaleTarget
import com.retrotube.app.shader.PresetPreviewRenderer
import com.retrotube.app.shader.ShaderPreset

/**
 * Edits either the app-wide default effect settings, or a per-file override
 * for one specific video (see [MODE_GLOBAL] / [MODE_OVERRIDE]). Reuses the
 * same preset/curvature/downscale/aspect controls either way.
 */
@UnstableApi
class EffectSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val MODE_GLOBAL = "global"
        const val MODE_OVERRIDE = "override"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private var mode: String = MODE_GLOBAL
    private var videoUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = SettingsRepository(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_GLOBAL
        videoUri = intent.getStringExtra(EXTRA_VIDEO_URI)

        applyRowLabels()
        applyPresetThumbnails()

        val initial = if (mode == MODE_OVERRIDE && videoUri != null) {
            settingsRepository.effectiveSettings(videoUri!!)
        } else {
            settingsRepository.getGlobalDefault()
        }
        applySettingsToUi(initial)

        if (mode == MODE_OVERRIDE) {
            binding.saveButton.setText(R.string.save_override)
            binding.clearOverrideButton.visibility = android.view.View.VISIBLE
            binding.clearOverrideButton.setOnClickListener {
                videoUri?.let { settingsRepository.clearOverride(it) }
                finish()
            }
        }

        binding.saveButton.setOnClickListener {
            val settings = readSettingsFromUi()
            if (mode == MODE_OVERRIDE && videoUri != null) {
                settingsRepository.setOverride(videoUri!!, settings)
            } else {
                settingsRepository.setGlobalDefault(settings)
            }
            finish()
        }
    }

    /** Sets a two-line "title \n description" label on every row -- keeps the RadioGroup/
     *  RadioButton structure (and this activity's ID-based read/write logic) completely
     *  unchanged, just replaces each row's single-line label with a richer one. */
    private fun applyRowLabels() {
        binding.presetZfastCrt.setTitleAndDescription(R.string.title_zfast_crt, R.string.desc_zfast_crt)
        binding.presetPhosphorMono.setTitleAndDescription(R.string.title_phosphor_mono, R.string.desc_phosphor_mono)
        binding.presetDeconverge.setTitleAndDescription(R.string.title_deconverge, R.string.desc_deconverge)
        binding.presetInterlaceRoll.setTitleAndDescription(R.string.title_interlace_roll, R.string.desc_interlace_roll)
        binding.presetCrtEasymode.setTitleAndDescription(R.string.title_crt_easymode, R.string.desc_crt_easymode)
        binding.presetVhs.setTitleAndDescription(R.string.title_vhs, R.string.desc_vhs)
        binding.presetVhsRec.setTitleAndDescription(R.string.title_vhs_rec, R.string.desc_vhs_rec)
        binding.presetCrtGuestAdvanced.setTitleAndDescription(R.string.title_crt_guest_advanced, R.string.desc_crt_guest_advanced)
        binding.presetNtsc.setTitleAndDescription(R.string.title_ntsc, R.string.desc_ntsc)
        binding.presetNone.setTitleAndDescription(R.string.title_none, R.string.desc_none)

        binding.curvatureSwitch.setTitleAndDescription(R.string.curvature_label, R.string.desc_curvature)

        binding.downscaleNative.setTitleAndDescription(R.string.title_downscale_native, R.string.desc_downscale_native)
        binding.downscale240.setTitleAndDescription(R.string.title_downscale_240, R.string.desc_downscale_240)
        binding.downscale480.setTitleAndDescription(R.string.title_downscale_480, R.string.desc_downscale_480)
        binding.downscale720.setTitleAndDescription(R.string.title_downscale_720, R.string.desc_downscale_720)

        binding.aspectFit.setTitleAndDescription(R.string.title_aspect_fit, R.string.desc_aspect_fit)
        binding.aspectStretch.setTitleAndDescription(R.string.title_aspect_stretch, R.string.desc_aspect_stretch)
        binding.aspectCrop.setTitleAndDescription(R.string.title_aspect_crop, R.string.desc_aspect_crop)
    }

    /** Renders each preset's real shader once against a synthetic test pattern (see
     *  PresetPreviewRenderer) and sets it as the RadioButton's own leading compound
     *  drawable -- so people can see what "vhs" vs "ntsc" actually look like instead of
     *  having to guess from jargon names, without restructuring the RadioGroup (which
     *  requires RadioButtons as direct children to keep its exclusive-selection working). */
    private fun applyPresetThumbnails() {
        val density = resources.displayMetrics.density
        val widthPx = (48 * density).toInt()
        val heightPx = (36 * density).toInt()
        val paddingPx = (10 * density).toInt()

        val rows = listOf(
            binding.presetZfastCrt to ShaderPreset.ZFAST_CRT,
            binding.presetPhosphorMono to ShaderPreset.PHOSPHOR_MONO,
            binding.presetDeconverge to ShaderPreset.DECONVERGE,
            binding.presetInterlaceRoll to ShaderPreset.INTERLACE_ROLL,
            binding.presetCrtEasymode to ShaderPreset.CRT_EASYMODE,
            binding.presetVhs to ShaderPreset.VHS,
            binding.presetVhsRec to ShaderPreset.VHS_REC,
            binding.presetCrtGuestAdvanced to ShaderPreset.CRT_GUEST_ADVANCED,
            binding.presetNtsc to ShaderPreset.NTSC,
            // NONE intentionally skipped -- a passthrough preview shows nothing useful.
        )
        for ((radioButton, preset) in rows) {
            val bitmap = PresetPreviewRenderer.getOrRender(preset) ?: continue
            val drawable = BitmapDrawable(resources, bitmap).apply {
                setBounds(0, 0, widthPx, heightPx)
            }
            radioButton.setCompoundDrawables(drawable, null, null, null)
            radioButton.compoundDrawablePadding = paddingPx
        }
    }

    private fun CompoundButton.setTitleAndDescription(titleRes: Int, descriptionRes: Int) {
        val title = getString(titleRes)
        val description = getString(descriptionRes)
        val full = "$title\n$description"
        val spannable = SpannableString(full)
        val descStart = title.length + 1
        spannable.setSpan(RelativeSizeSpan(0.8f), descStart, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.retro_text_muted)),
            descStart,
            full.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        text = spannable
    }

    private fun applySettingsToUi(settings: VideoEffectSettings) {
        val presetId = when (settings.preset) {
            ShaderPreset.ZFAST_CRT -> binding.presetZfastCrt.id
            ShaderPreset.PHOSPHOR_MONO -> binding.presetPhosphorMono.id
            ShaderPreset.DECONVERGE -> binding.presetDeconverge.id
            ShaderPreset.INTERLACE_ROLL -> binding.presetInterlaceRoll.id
            ShaderPreset.CRT_EASYMODE -> binding.presetCrtEasymode.id
            ShaderPreset.VHS -> binding.presetVhs.id
            ShaderPreset.VHS_REC -> binding.presetVhsRec.id
            ShaderPreset.CRT_GUEST_ADVANCED -> binding.presetCrtGuestAdvanced.id
            ShaderPreset.NTSC -> binding.presetNtsc.id
            ShaderPreset.NONE -> binding.presetNone.id
        }
        binding.presetGroup.check(presetId)

        binding.curvatureSwitch.isChecked = settings.curvatureEnabled

        val downscaleId = when (settings.downscale) {
            DownscaleTarget.NATIVE -> binding.downscaleNative.id
            DownscaleTarget.P240 -> binding.downscale240.id
            DownscaleTarget.P480 -> binding.downscale480.id
            DownscaleTarget.P720 -> binding.downscale720.id
        }
        binding.downscaleGroup.check(downscaleId)

        val aspectId = when (settings.aspectMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> binding.aspectStretch.id
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> binding.aspectCrop.id
            else -> binding.aspectFit.id
        }
        binding.aspectGroup.check(aspectId)
    }

    private fun readSettingsFromUi(): VideoEffectSettings {
        val preset = when (binding.presetGroup.checkedRadioButtonId) {
            binding.presetZfastCrt.id -> ShaderPreset.ZFAST_CRT
            binding.presetPhosphorMono.id -> ShaderPreset.PHOSPHOR_MONO
            binding.presetDeconverge.id -> ShaderPreset.DECONVERGE
            binding.presetInterlaceRoll.id -> ShaderPreset.INTERLACE_ROLL
            binding.presetCrtEasymode.id -> ShaderPreset.CRT_EASYMODE
            binding.presetVhs.id -> ShaderPreset.VHS
            binding.presetVhsRec.id -> ShaderPreset.VHS_REC
            binding.presetCrtGuestAdvanced.id -> ShaderPreset.CRT_GUEST_ADVANCED
            binding.presetNtsc.id -> ShaderPreset.NTSC
            else -> ShaderPreset.NONE
        }
        val downscale = when (binding.downscaleGroup.checkedRadioButtonId) {
            binding.downscale240.id -> DownscaleTarget.P240
            binding.downscale480.id -> DownscaleTarget.P480
            binding.downscale720.id -> DownscaleTarget.P720
            else -> DownscaleTarget.NATIVE
        }
        val aspectMode = when (binding.aspectGroup.checkedRadioButtonId) {
            binding.aspectStretch.id -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            binding.aspectCrop.id -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        return VideoEffectSettings(
            preset = preset,
            curvatureEnabled = binding.curvatureSwitch.isChecked,
            downscale = downscale,
            aspectMode = aspectMode,
        )
    }
}
