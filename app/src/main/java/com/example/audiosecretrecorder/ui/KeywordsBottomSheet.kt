package com.example.audiosecretrecorder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.example.audiosecretrecorder.R
import com.example.audiosecretrecorder.databinding.BottomSheetKeywordsBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Hoja inferior para editar las palabras clave (iniciar / detener).
 * No guarda nada por sí misma: valida y le devuelve el resultado a MainActivity
 * mediante el Fragment Result API, y MainActivity las guarda con KeywordManager.
 */
class KeywordsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetKeywordsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetKeywordsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Si el sistema recrea la hoja (p. ej. al girar), los campos restauran su texto solos
        if (savedInstanceState == null) {
            binding.etKeywordStart.setText(arguments?.getString(ARG_START).orEmpty())
            binding.etKeywordStop.setText(arguments?.getString(ARG_STOP).orEmpty())
        }

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener { saveKeywords() }
    }

    override fun onStart() {
        super.onStart()
        // Abrir completa y redimensionar con el teclado para que no tape los campos
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun saveKeywords() {
        val startWord = binding.etKeywordStart.text.toString().trim()
        val stopWord = binding.etKeywordStop.text.toString().trim()

        binding.tilKeywordStart.error = null
        binding.tilKeywordStop.error = null

        if (startWord.isEmpty()) {
            binding.tilKeywordStart.error = getString(R.string.error_keyword_empty)
            return
        }
        if (stopWord.isEmpty()) {
            binding.tilKeywordStop.error = getString(R.string.error_keyword_empty)
            return
        }
        if (startWord.equals(stopWord, ignoreCase = true)) {
            binding.tilKeywordStop.error = getString(R.string.error_keyword_same)
            return
        }

        val result = Bundle().apply {
            putString(KEY_START, startWord)
            putString(KEY_STOP, stopWord)
        }
        parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
        dismiss()
    }

    companion object {
        const val TAG = "KeywordsBottomSheet"
        const val REQUEST_KEY = "keywords_request"
        const val KEY_START = "keyword_start"
        const val KEY_STOP = "keyword_stop"

        private const val ARG_START = "arg_start"
        private const val ARG_STOP = "arg_stop"

        fun newInstance(startWord: String, stopWord: String): KeywordsBottomSheet {
            return KeywordsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_START, startWord)
                    putString(ARG_STOP, stopWord)
                }
            }
        }
    }
}
