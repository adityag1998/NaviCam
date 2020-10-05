package com.samsung.navicam

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ObjectAnalyzer : ImageAnalysis.Analyzer {

    private suspend fun startImageClassification(
        processableImage: InputImage,
        image: ImageProxy,
        textReaderJob: Job,
    ): Unit {
        //Set Options and get an Image Labeler
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.7f)
            .build()
        val labeler = ImageLabeling.getClient(options)

        // Pass processableImage to Labeler

         labeler.process(processableImage)
            .addOnSuccessListener { labels ->
                for (label in labels) {
                    val text = label.text
                    val confidence = label.confidence
                    val index = label.index
                    Log.d(TAG, "Object:$index $text Confidence:$confidence")
                }
            }
            .addOnFailureListener { exc ->
                Log.e(TAG, "ObjectLabeler: $exc")
            }
            .addOnCompleteListener { _ ->
                textReaderJob.invokeOnCompletion {
                    image.close()
                }
            }
    }

    private suspend fun startTextClassification(
        processableImage: InputImage,
        image: ImageProxy
    ): Unit {
        val recognizer = TextRecognition.getClient()

        recognizer.process(processableImage)
            .addOnSuccessListener { visionText ->
                Log.d(TAG, "Text: ${visionText.text}")
            }
            .addOnFailureListener { exc ->
                Log.e(TAG, "TextIdentifier: $exc")
            }
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(image: ImageProxy) = runBlocking {
        val intermediate = image.image

        if (intermediate != null) {
            val processableImage =
                InputImage.fromMediaImage(intermediate, image.imageInfo.rotationDegrees)

            Log.d(TAG, "beforeCoroutineCreation: ${Thread.currentThread().name}")
            val startTime = System.currentTimeMillis()
            val textReaderJob = launch {
                Log.d(TAG, "Job2: TextClassification ${Thread.currentThread().name}")
                startTextClassification(processableImage, image)
            }

            val imageClassifierJob = launch {
                Log.d(TAG, "Job1: ImageClassification ${Thread.currentThread().name}")
                startImageClassification(processableImage, image, textReaderJob)
            }

        }
    }

    companion object {
        const val TAG = " ObjectAnalyzer"
    }
}