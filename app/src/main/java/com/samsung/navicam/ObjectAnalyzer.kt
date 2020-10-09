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
import kotlin.math.min

class ObjectAnalyzer : ImageAnalysis.Analyzer {

    companion object {
        //Static Members
        const val DEBUG = false
        const val CONFIDENCE_THRESHOLD = 0.7f
        const val TAG = " ObjectAnalyzer"
        const val BENEFICIARY = "com.samsung.smartnotes"
        const val PARSE_BUNDLE = "com.samsung.navicam.parse_bundle"
        const val KEY1 = "com.samsung.navicam.objectList"
        const val KEY2 = "com.samsung.navicam.blockWiseTextList"
        const val NOISE_CANCELLATION_BUFFER = 20
        const val REMOVE_OBJECT_NOISE_THRESHOLD = 10
        const val LEVENSHTEIN_DISTANCE_THRESHOLD = 250
        var visionTextObject: Text? = null
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

        fun showFireToast(objectList: ArrayList<String>, blockWiseTextList: ArrayList<String>, context: Context){
            val toastText:String = """
            Broadcast is Fired: 
            Object List Size: ${objectList.size}
            Text List Size: ${blockWiseTextList.size}
            """.trimIndent()

            Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
        }

        fun showFailToast(context: Context){
            Toast.makeText(context,
                "Error 404: Companion app Smart Notes not found",
                Toast.LENGTH_SHORT).show()
        }

        // Constructor of static block
        init {
            //Log.d(TAG, "initialize objectDict class: ${Thread.currentThread().name}")
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

    private fun displayObjectDict(objectDict: HashMap<String, Int>){
        Log.d(TAG, "displayObjectDict: --Object Dict Start--")
        for (key in objectDict.keys){
            Log.d(TAG, "Object: $key Frequency: ${objectDict[key]}")
        }
        Log.d(TAG, "displayObjectDict: --Object Dict Finish--")
    }

    private fun displayBlockWiseText(visionText: Text?){
        Log.d(TAG, "displayBlockWiseText: --Text Blocks Start--")
        if (visionText == null) {
            Log.d(TAG, "displayBlockWiseText: --Text Blocks End--")
            return
        }
        var count:Int = 1
        for (block in visionText.textBlocks){
            Log.d(TAG, "displayBlockWiseText: Block:$count Text:${block.text}")
            count += 1
        }
        Log.d(TAG, "displayBlockWiseText: --Text Blocks End--")
    }

    fun getLevenshteinDistance(s: String, t: String): Int {
        // degenerate cases
        if (s == t)  return 0
        if (s == "") return t.length
        if (t == "") return s.length

        // create two integer arrays of distances and initialize the first one
        val v0 = IntArray(t.length + 1) { it }  // previous
        val v1 = IntArray(t.length + 1)         // current

        var cost: Int
        for (i in s.indices) {
            // calculate v1 from v0
            v1[0] = i + 1
            for (j in t.indices) {
                cost = if (s[i] == t[j]) 0 else 1
                v1[j + 1] = min(v1[j] + 1, min(v0[j + 1] + 1, v0[j] + cost))
            }
            // copy v1 to v0 for next iteration
            for (j in 0 .. t.length) v0[j] = v1[j]
        }
        return v1[t.length]
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

    private fun getBlockWiseTextObject(visionText: Text?): ArrayList<String> {
        var returnableObject: ArrayList<String> = ArrayList()
        if (visionText == null) return returnableObject
        for (block in visionText.textBlocks){
            val blockText = block.text
            returnableObject.add(blockText)
        }
        return returnableObject
    }

    private fun getCombinedString(inputText: Text?): String{
        if (inputText == null){
            return ""
        }
        val sb = StringBuilder()
        for (block in inputText.textBlocks){
            for (line in block.lines){
                for (element in line.elements){
                    //Log.d(TAG, "getCombinedStringdirect: ${element.text}")
                    sb.append(element.text)
                    //Log.d(TAG, "getCombinedStringaftercat: $sb")
                }
            }
        }

        return sb.toString()
    }

    private fun setVisionTextObject(visionText: Text?){
        val prevText = getCombinedString(visionTextObject)
        val currText = getCombinedString(visionText)
        //Log.d(TAG, "setVisionTextObjectPrev: $prevText")
        //Log.d(TAG, "setVisionTextObjectPrev: $currText")
        val levenshteinDistance = getLevenshteinDistance(prevText, currText)
        Log.d(TAG, "setVisionTextObjectleveishtein distance: $levenshteinDistance")
        if (levenshteinDistance >= LEVENSHTEIN_DISTANCE_THRESHOLD){
            visionTextObject = visionText
            processBroadcastText()
        }
    }

    private fun getBundle(objectList: ArrayList<String>, blockWiseTextList: ArrayList<String>): Bundle{
        val bundle = Bundle()
        bundle.putSerializable(KEY1, objectList)
        bundle.putSerializable(KEY2, blockWiseTextList)
        return bundle
    }

    private fun processBroadcastText(){
        // Get Context
        val context:Context = MainActivity.appContext

        if (isPackageInstalled(BENEFICIARY, context.packageManager)){
            val objectList = getObjectList(prevObjectSet)
            val blockWiseTextList = getBlockWiseTextObject(visionTextObject)
            val bundle = getBundle(objectList, blockWiseTextList)
            sendBroadcastIntent(bundle, context)
            showFireToast(objectList, blockWiseTextList, context)
        }

        else{
            showFailToast(context)
        }

    }

    private fun processBroadcastObject(currObjectSet: HashSet<String>){
        // Get Context
        val context:Context = MainActivity.appContext

        if (isPackageInstalled(BENEFICIARY, context.packageManager)){
            val objectList = getObjectList(currObjectSet)
            val blockWiseTextList = getBlockWiseTextObject(visionTextObject)
            val bundle = getBundle(objectList, blockWiseTextList)
            sendBroadcastIntent(bundle, context)
            showFireToast(objectList, blockWiseTextList, context)
        }

        else{
            showFailToast(context)
        }

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
            processBroadcastObject(currObjectSet)
            if (DEBUG) displaySharableObject(currObjectSet)
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

        // IMPLEMENTING SAFE DELETE TO AVOID CRASH USING ITERATOR INSTEAD OF SIMPLE REMOVE
        // ITERATOR SEEN IN JAVA 7
        // THEN SEEN IN JAVA 8
        // THEN IMPLEMENTED IN KOTLIN BY ASSUMPTIONS FROM JAVA 8

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
        // Call prepareSharableObjectSet from this dictionary
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
                //displayBlockWiseText(visionText)
                setVisionTextObject(visionText)
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