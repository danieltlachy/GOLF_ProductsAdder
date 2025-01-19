package com.example.productsadder

import android.app.Activity
import android.content.Intent
import android.content.Intent.ACTION_GET_CONTENT
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.productsadder.databinding.ActivityMainBinding
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val selectedColors = mutableListOf<Int>()
    private val selectedImages = mutableListOf<Uri>()
    private lateinit var storage: FirebaseStorage
    private lateinit var storageReference: StorageReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        FirebaseApp.initializeApp(this)
        storage = FirebaseStorage.getInstance()
        storageReference = storage.reference

        binding.buttonColorPicker.setOnClickListener {
            ColorPickerDialog
                .Builder(this)
                .setTitle("Product color")
                .setPositiveButton("Select", object : ColorEnvelopeListener {

                    override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                        envelope?.let {
                            selectedColors.add(it.color)
                            updateColors()
                        }
                    }

                }).setNegativeButton("Cancel") { colorPicker, _ ->
                    colorPicker.dismiss()
                }.show()
        }

        val selectImagesActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val intent = result.data
                    if (intent?.clipData != null) {
                        val count = intent.clipData?.itemCount ?: 0
                        (0 until count).forEach {
                            val imageUri = intent.clipData?.getItemAt(it)?.uri
                            imageUri?.let { selectedImages.add(it) }
                        }
                    } else {
                        val imageUri = intent?.data
                        imageUri?.let { selectedImages.add(it) }
                    }
                    updateImages()
                }
            }

        binding.buttonImagesPicker.setOnClickListener {
            val intent = Intent(ACTION_GET_CONTENT)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.type = "image/*"
            selectImagesActivityResult.launch(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.saveProduct) {
            val isValid = validateInformation()
            if (!isValid) {
                Toast.makeText(this, "Check your inputs", Toast.LENGTH_SHORT).show()
                return false
            }
            saveProducts { isSuccess ->
                if (isSuccess) {
                    Toast.makeText(this, "Product saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to save product", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun validateInformation(): Boolean {
        if (selectedImages.isEmpty())
            return false
        if (binding.edName.text.toString().trim().isEmpty())
            return false
        if (binding.edCategory.text.toString().trim().isEmpty())
            return false
        if (binding.edPrice.text.toString().trim().isEmpty())
            return false
        return true
    }

    private fun updateImages() {
        binding.imagePreviewContainer.removeAllViews() // Limpiar vistas anteriores

        if (selectedImages.isNotEmpty()) {
            binding.scrollImagePreviewContainer.visibility = View.VISIBLE
            selectedImages.forEach { uri ->
                val imageView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(200, 200).apply {
                        setMargins(16, 16, 16, 16) // Márgenes uniformes
                    }
                    setImageURI(uri)
                    scaleType = ImageView.ScaleType.CENTER_CROP // Recortar imagen para ajustar
                }
                binding.imagePreviewContainer.addView(imageView)
            }
            binding.tvSelectedImages.text = "Selected images: ${selectedImages.size}"
        } else {
            binding.scrollImagePreviewContainer.visibility = View.GONE
            binding.tvSelectedImages.text = "No images selected"
        }
    }


    private fun saveProducts(state: (Boolean) -> Unit) {
        val sizes = getSizesList(binding.edSizes.text.toString().trim())
        val imagesByteArrays = getImagesByteArrays()
        val name = binding.edName.text.toString().trim()
        val images = mutableListOf<String>()
        val category = binding.edCategory.text.toString().trim()
        val description = binding.edDescription.text.toString().trim()
        val price = binding.edPrice.text.toString().trim()
        val offerPercentage = binding.edOfferPercentage.text.toString().trim()

        lifecycleScope.launch {
            showLoading()
            try {
                Log.d("SaveProducts", "Uploading images...")
                async {
                    imagesByteArrays.forEach { byteArray ->
                        // Usar UUID para generar un nombre único
                        val uniqueImageName = "products/images/${UUID.randomUUID()}.jpg"
                        val imageRef = storageReference.child(uniqueImageName)
                        val result = imageRef.putBytes(byteArray).await()
                        val downloadUrl = result.storage.downloadUrl.await().toString()
                        images.add(downloadUrl)
                        Log.d("SaveProducts", "Image uploaded: $downloadUrl")
                    }
                }.await()

                Log.d("SaveProducts", "Creating product object...")
                val product = Product(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    category = category,
                    price = price.toFloat(),
                    offerPercentage = if (offerPercentage.isEmpty()) null else offerPercentage.toFloat(),
                    description = if (description.isEmpty()) null else description,
                    colors = selectedColors,
                    sizes = sizes,
                    images = images
                )

                Log.d("SaveProducts", "Saving product to Firestore...")
                Firebase.firestore.collection("Products").add(product)
                    .addOnSuccessListener {
                        Log.d("SaveProducts", "Product saved successfully.")
                        hideLoading()
                        clearForm()
                        state(true)
                    }
                    .addOnFailureListener { e ->
                        Log.e("Firestore", "Error adding product: ${e.message}")
                        hideLoading()
                        state(false)
                    }
            } catch (e: Exception) {
                Log.e("SaveProducts", "Error during product saving: ${e.message}", e)
                hideLoading()
                state(false)
            }
        }
    }

    private fun showLoading() {
        binding.progressbar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE // Asegúrate de que el TextView para mostrar el progreso esté visible
    }


    private fun hideLoading() {
        binding.progressbar.visibility = View.INVISIBLE
        binding.tvProgress.visibility = View.INVISIBLE
    }

    private fun getImagesByteArrays(): List<ByteArray> {
        return selectedImages.mapNotNull { uri ->
            try {
                val stream = ByteArrayOutputStream()
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)) {
                    Log.d("ImageConversion", "Image converted to ByteArray: $uri")
                    stream.toByteArray()
                } else {
                    Log.e("ImageConversion", "Failed to compress image: $uri")
                    null
                }
            } catch (e: Exception) {
                Log.e("ImageConversion", "Error converting image: ${e.message}", e)
                null
            }
        }
    }

    private fun getSizesList(sizes: String): List<String>? {
        if (sizes.isEmpty())
            return null
        val sizesList = sizes.split(",").map { it.trim() }
        return sizesList
    }

    private fun updateColors() {
        var colors = ""
        selectedColors.forEach {
            colors = "$colors ${Integer.toHexString(it)}, "
        }
        binding.tvSelectedColors.text = colors
    }

    private fun clearForm() {
        binding.edName.text.clear()
        binding.edCategory.text.clear()
        binding.edDescription.text.clear()
        binding.edPrice.text.clear()
        binding.edOfferPercentage.text.clear()
        binding.edSizes.text.clear()
        binding.tvSelectedColors.text = ""
        binding.tvSelectedImages.text = ""
        selectedImages.clear()
        selectedColors.clear()
        binding.imagePreviewContainer.removeAllViews()
        binding.imagePreviewContainer.visibility = View.GONE
    }
}
