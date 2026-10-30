/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.CompoundButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import java.io.File
import java.util.Locale

class KeystoreGeneratorDialogFragment : AppCompatDialogFragment() {

    private var destDirStr: String = ""
    var onKeyGeneratedListener: ((KeystoreGeneratorHelper.Result) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destDirStr = arguments?.getString(ARG_DEST_DIR) ?: "/storage/emulated/0/Download"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = AppCompatDialog(requireContext())
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_keystore_generator, null)
        dialog.setContentView(view)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val displayMetrics = context.resources.displayMetrics
            val targetWidth = (displayMetrics.widthPixels * 0.94).toInt().coerceAtMost(
                (500 * displayMetrics.density).toInt()
            )
            setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        setupViews(view, dialog)
        return dialog
    }

    private fun setupViews(view: View, dialog: Dialog) {
        val editDir = view.findViewById<EditText>(R.id.editKeystoreDir)
        val editFileName = view.findViewById<EditText>(R.id.editKeystoreFileName)
        val cbFormatJks = view.findViewById<CheckBox>(R.id.cbFormatJks)
        val cbFormatBks = view.findViewById<CheckBox>(R.id.cbFormatBks)
        val cbFormatAosp = view.findViewById<CheckBox>(R.id.cbFormatAosp)
        val editAlias = view.findViewById<EditText>(R.id.editAlias)
        val editStorePass = view.findViewById<EditText>(R.id.editStorePass)
        val cbSameKeyPass = view.findViewById<CheckBox>(R.id.cbSameKeyPass)
        val layoutKeyPass = view.findViewById<LinearLayout>(R.id.layoutKeyPass)
        val editKeyPass = view.findViewById<EditText>(R.id.editKeyPass)
        val rbKeySize4096 = view.findViewById<RadioButton>(R.id.rbKeySize4096)
        val editValidityYears = view.findViewById<EditText>(R.id.editValidityYears)
        val editCommonName = view.findViewById<EditText>(R.id.editCommonName)

        val switchAdvanced = view.findViewById<android.widget.CompoundButton>(R.id.switchAdvancedDetails)
        val layoutAdvanced = view.findViewById<LinearLayout>(R.id.layoutAdvancedDetails)
        val editOrgUnit = view.findViewById<EditText>(R.id.editOrgUnit)
        val editOrgName = view.findViewById<EditText>(R.id.editOrgName)
        val editLocality = view.findViewById<EditText>(R.id.editLocality)
        val editState = view.findViewById<EditText>(R.id.editState)
        val editCountry = view.findViewById<EditText>(R.id.editCountry)

        val btnCancel = view.findViewById<Button>(R.id.btnCancelKeystore)
        val btnGenerate = view.findViewById<Button>(R.id.btnGenerateKeystore)

        // Defaults
        editDir.setText(destDirStr)
        try {
            val defaultCountry = Locale.getDefault().country
            if (!defaultCountry.isNullOrEmpty()) {
                editCountry.setText(defaultCountry)
            }
        } catch (_: Throwable) {}

        cbSameKeyPass.setOnCheckedChangeListener { _, isChecked ->
            layoutKeyPass.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        switchAdvanced.setOnCheckedChangeListener { _, isChecked ->
            layoutAdvanced.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnGenerate.setOnClickListener {
            val dirPath = editDir.text.toString().trim()
            val fileName = editFileName.text.toString().trim()
            val alias = editAlias.text.toString().trim()
            val storePass = editStorePass.text.toString()
            val samePass = cbSameKeyPass.isChecked
            val keyPass = if (samePass) storePass else editKeyPass.text.toString()

            if (dirPath.isEmpty()) {
                Toast.makeText(context, "Ingresa un directorio de destino válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (fileName.isEmpty()) {
                Toast.makeText(context, "Ingresa el nombre del archivo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!cbFormatJks.isChecked && !cbFormatBks.isChecked && !cbFormatAosp.isChecked) {
                Toast.makeText(context, "Selecciona al menos un formato de clave", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (alias.isEmpty()) {
                Toast.makeText(context, "Ingresa el alias de la clave", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (storePass.length < 6) {
                Toast.makeText(context, "La contraseña del KeyStore debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!samePass && keyPass.length < 6) {
                Toast.makeText(context, "La contraseña de la clave debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val keySize = if (rbKeySize4096.isChecked) 4096 else 2048
            val validity = editValidityYears.text.toString().toIntOrNull() ?: 25
            val commonName = editCommonName.text.toString().trim().ifEmpty { "Android" }

            val config = KeystoreGeneratorHelper.Config(
                directory = File(dirPath),
                fileName = fileName,
                alias = alias,
                storePassword = storePass,
                keyPassword = keyPass,
                keySize = keySize,
                validityYears = validity,
                generateJks = cbFormatJks.isChecked,
                generateBks = cbFormatBks.isChecked,
                generateAospKeys = cbFormatAosp.isChecked,
                commonName = commonName,
                organizationUnit = editOrgUnit.text.toString().trim(),
                organizationName = editOrgName.text.toString().trim(),
                localityName = editLocality.text.toString().trim(),
                stateName = editState.text.toString().trim(),
                country = editCountry.text.toString().trim()
            )

            dialog.dismiss()
            performGeneration(config)
        }
    }

    private fun performGeneration(config: KeystoreGeneratorHelper.Config) {
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.keystore_generator_title)
            .setMessage(R.string.keystore_generating)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    KeystoreGeneratorHelper.generateKeystore(config)
                } catch (e: Exception) {
                    null to e
                }
            }

            progressDialog.dismiss()

            if (result is Pair<*, *>) {
                val exception = result.second as? Exception
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.keystore_error_title)
                    .setMessage("No se pudo generar la clave:\n${exception?.localizedMessage}")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val genResult = result as KeystoreGeneratorHelper.Result
            onKeyGeneratedListener?.invoke(genResult)

            val createdPaths = mutableListOf<String>()
            genResult.jksFile?.let { createdPaths.add("JKS / PKCS12: ${it.absolutePath}") }
            genResult.bksFile?.let { createdPaths.add("BKS: ${it.absolutePath}") }
            genResult.pk8File?.let { createdPaths.add("PK8: ${it.absolutePath}") }
            genResult.pemFile?.let { createdPaths.add("PEM: ${it.absolutePath}") }

            val message = """
                Clave generada con éxito.
                
                [Archivos Creados]
                ${createdPaths.joinToString("\n")}
                
                Alias: ${genResult.alias}
                
                [Huella Digital SHA-256]
                ${genResult.sha256Fingerprint}
                
                [Huella Digital SHA-1]
                ${genResult.sha1Fingerprint}
            """.trimIndent()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keystore_success_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Copiar SHA-256") { _, _ ->
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SHA-256", genResult.sha256Fingerprint))
                    Toast.makeText(requireContext(), "Huella SHA-256 copiada al portapapeles", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    companion object {
        private const val TAG = "KeystoreGeneratorDialogFragment"
        private const val ARG_DEST_DIR = "arg_dest_dir"

        fun show(
            fragment: Fragment,
            destDir: String,
            onKeyGenerated: ((KeystoreGeneratorHelper.Result) -> Unit)? = null
        ): KeystoreGeneratorDialogFragment {
            val dialog = KeystoreGeneratorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEST_DIR, destDir)
                }
                this.onKeyGeneratedListener = onKeyGenerated
            }
            dialog.show(fragment.parentFragmentManager, TAG)
            return dialog
        }

        fun show(
            activity: FragmentActivity,
            destDir: String,
            onKeyGenerated: ((KeystoreGeneratorHelper.Result) -> Unit)? = null
        ): KeystoreGeneratorDialogFragment {
            val dialog = KeystoreGeneratorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEST_DIR, destDir)
                }
                this.onKeyGeneratedListener = onKeyGenerated
            }
            dialog.show(activity.supportFragmentManager, TAG)
            return dialog
        }
    }
}
