package com.samsung.navicam

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class ObjectAnalyzer : ImageAnalysis.Analyzer {

    companion object {
        //Static Members
        const val CONFIDENCE_THRESHOLD = 0.7f
        const val TAG = " ObjectAnalyzer"
        const val BENEFICIARY = "com.samsung.smartnotes"
        const val PARSE_BUNDLE = "com.samsung.navicam.parse_bundle"
        const val KEY1 = "com.samsung.navicam.objectList"
        const val KEY2 = "com.samsung.navicam.text"
        const val NOISE_CANCELLATION_BUFFER = 20
        const val REMOVE_OBJECT_NOISE_THRESHOLD = 10
        const val RESET_NOISE_THRESHOLD = 5
        var text: Text? = null
        var objectDict: HashMap<String, Int> = HashMap()
        var prevObjectSet = HashSet<String>()

        // Static helper function
        fun isPackageInstalled(packageName: String, pm: PackageManager): Boolean {
            return try {
                pm.getApplicationInfo(packageName, 0).enabled
                true
            }
            catch (e: PackageManager.NameNotFoundException){
                false
            }
        }

        // Constructor of static block
        init {
            Log.d(TAG, "initialize objectDict class: ${Thread.currentThread().name}")
        }
    }

    private fun displayObjectLabels(labels: List<ImageLabel>){
        for (label in labels){
            val text = label.text
            val confidence = label.confidence
            val index = label.index
            Log.d(TAG, "displayObjectLabels: Object${index}, Confidence: $confidence, $text")
        }
    }

    private fun showFireToast(objectList: ArrayList<String>, text: String, context: Context){
        val toastText:String = """
            Broadcast is Fired: 
            Object List Size: ${objectList.size}
            Text String Length: ${text.length}
            """.trimIndent()

        Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
    }

    private fun showFailToast(context: Context){
        Toast.makeText(context,
            "Companion App: com.samsung.smartnotes Not Found",
            Toast.LENGTH_SHORT).show()
    }

    private fun sendBroadcastIntent(bundle: Bundle, context: Context){
        Intent().also { intent ->
            intent.action = PARSE_BUNDLE
            intent.addCategory("android.intent.category.DEFAULT")
            intent.setPackage(BENEFICIARY)
            intent.putExtras(bundle)
            context.sendBroadcast(intent)
        }
    }

    private fun getObjectList(currObjectSet: HashSet<String>): ArrayList<String> {
        return ArrayList(currObjectSet)
    }

    private fun getText(visionText: Text?): String{
        return visionText?.text ?: ""
    }

    private fun getBundle(objectList: ArrayList<String>, text: String): Bundle{
        val bundle = Bundle()
        bundle.putSerializable(KEY1, objectList)
        bundle.putSerializable(KEY2, text)
        return bundle
    }

    private fun processBroadcast(currObjectSet: HashSet<String>){
        // Get Context
        val context:Context = MainActivity.appContext

        if (isPackageInstalled(BENEFICIARY, context.packageManager)){
            val objectList = getObjectList(currObjectSet)
            val text = getText(text)
            val bundle = getBundle(objectList, text)
            sendBroadcastIntent(bundle, context)
            showFireToast(objectList, text, context)
        }

        else{
            showFailToast(context)
        }

    }

    private fun displayObjectDict(objectDict: HashMap<String, Int>){
        Log.d(TAG, "displayObjectDict: --Object Dict Start--")
        for (key in objectDict.keys){
            Log.d(TAG, "Object: $key Frequency: ${objectDict[key]}")
        }
        Log.d(TAG, "displayObjectDict: --Object Dict Finish--")
    }

    private fun displaySharableObject(currObjectSet: HashSet<String>){
        Log.d(TAG, "displaySharableObject: ---Sharable Object Start---")
        for (obj in currObjectSet){
            Log.d(TAG, "displaySharableObject: $obj")
        }
        Log.d(TAG, "displaySharableObject: ---Sharable Object Finish---")
    }

    private fun prepareSharableObjectSet(objectDict: HashMap<String, Int>) {
        var currObjectSet = HashSet<String>()
        for (key in objectDict.keys){
            if (objectDict[key]!! >= REMOVE_OBJECT_NOISE_THRESHOLD){
                currObjectSet.add(key)
            }
        }

        if (!currObjectSet.equals(prevObjectSet)){
            processBroadcast(currObjectSet)
            displaySharableObject(currObjectSet)
            prevObjectSet = currObjectSet
        }
    }

    // Logic to maintain a dictionary in Static Variable
    private fun objectDictMaintainer(labels: List<ImageLabel>) {
        //Log.d(TAG, "objectDictMaintainer: Before Reducing Freq")
        //displayObjectDict(objectDict)

        for (key in objectDict.keys){
            // Decrement all Objects by 1
            objectDict[key] = objectDict[key]!! - 1
        }

        //Log.d(TAG, "objectDictMaintainer: After Reducing Freq")
        //displayObjectDict(objectDict)

        //IMPLEMENTING SAFE DELETE TO AVOID CRASH USING ITERATOR
        // ITERATOR SEEN IN JAVA 7
        // THEN SEEN IN JAVA 8
        // THEN IMPLEMENTED IN KOTLIN
        objectDict.entries.removeIf {it.value == 0}
        //Log.d(TAG, "objectDictMaintainer: After Removing 0 Freq")
        //displayObjectDict(objectDict)


        // Increment found objects by 2
        for (label in labels){
            val key = label.text
            if (objectDict.containsKey(key) && objectDict[key]!! <= NOISE_CANCELLATION_BUFFER-2){
                objectDict[key] = objectDict[key]!! + 2
            }
            else if (objectDict.containsKey(key) && objectDict[key]!! > NOISE_CANCELLATION_BUFFER-2){
                objectDict[key] = NOISE_CANCELLATION_BUFFER
            }
            else{
                objectDict[key] = 2
            }
        }

        // Net Result Old objects removed, new objects introduced and Updated Dict
        // Call getSharableObjectSet
        prepareSharableObjectSet(objectDict)
    }

    private fun startImageClassification(
        processableImage: InputImage,
        image: ImageProxy,
        textReadJob: Job,
    ): Unit {
        //Set Options and get an Image Labeler
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
            .build()
        val labeler = ImageLabeling.getClient(options)

        // Pass processableImage to Labeler
         labeler.process(processableImage)
            .addOnSuccessListener { labels ->
                //displayObjectLabels(labels)
                objectDictMaintainer(labels)
            }
            .addOnFailureListener { exc ->
                Log.e(TAG, "ObjectLabeler: $exc")
            }
            .addOnCompleteListener { _ ->
                textReadJob.invokeOnCompletion {
                    image.close()
                }
            }
    }

    private fun startTextClassification(
        processableImage: InputImage,
    ): Unit {

        val recognizer = TextRecognition.getClient()

        recognizer.process(processableImage)
            .addOnSuccessListener { visionText ->
                //Log.d(TAG, "Text: ${visionText.text}")
                text = visionText
            }
            .addOnFailureListener { exc ->
                Log.e(TAG, "TextIdentifier: $exc")
            }
    }


    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(image: ImageProxy) = runBlocking{
        val intermediate = image.image
        //Log.d(TAG, "analyze override fn: ${Thread.currentThread().name}")
        if (intermediate != null) {
            val processableImage =
                InputImage.fromMediaImage(intermediate, image.imageInfo.rotationDegrees)
            val textReadJob = launch { startTextClassification(processableImage) }
            launch {startImageClassification(processableImage, image, textReadJob)}
        }
    }
}