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
import kotlin.math.abs
import kotlin.math.max
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
        const val ADD_OBJECT_THRESHOLD = 2
        const val REMOVE_OBJECT_THRESHOLD = 5
        const val OVERALL_DISTANCE_THRESHOLD = 0.45f
        const val LEVENSHTEIN_DISTANCE_FACTOR = 0.35f //(When to consider string same or different in %age)
        const val BLOCK_ACTIVATOR_THRESHOLD = 0.5f //(%age in Change in number of blocks to fire broadcast)
        var visionTextObject: Text? = null
        var objectDict: HashMap<String, Int> = HashMap()
        var masterObjectSet = HashSet<String>()

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

        fun showFireToast(objectList: ArrayList<String>, blockWiseTextList: ArrayList<String>, context: Context) {
            if (!DEBUG) return
            val toastText:String = """
            Broadcast is Fired: 
            Object List Size: ${objectList.size}
            Text List Size: ${blockWiseTextList.size}
            """.trimIndent()

            Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
        }

        fun showFailToast(context: Context){
            Toast.makeText(context,
                "Companion app Smart Notes not found",
                Toast.LENGTH_SHORT).show()
        }

        // Constructor of static block
        init {
            //Log.d(TAG, "initialize objectDict class: ${Thread.currentThread().name}")
        }

        fun sendBroadcastIntent(bundle: Bundle, context: Context){
            Intent().also { intent ->
                intent.action = PARSE_BUNDLE
                intent.addCategory("android.intent.category.DEFAULT")
                intent.setPackage(BENEFICIARY)
                intent.putExtras(bundle)
                context.sendBroadcast(intent)
            }
        }
    }

    private fun displayObjectLabels(labels: List<ImageLabel>){
        var strAll = ""
        for (label in labels){
            val text = label.text
            val confidence = label.confidence
            val index = label.index
            strAll += "$text$confidence "
        }
        Log.d(TAG, "displayObjectLabels: $strAll")
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

    private fun getLevenshteinDistance(s: String, t: String): Int {
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

    private fun handleTextNoiseAndBroadcast (prevText: Text, currText: Text){
        //Log.d(TAG, "handleTextNoiseAndBroadcast")
        //top level / broad filter
        var fulltextPrev = prevText.getText()
        var fulltextCurr = currText.getText()
        if (fulltextPrev.isEmpty() && fulltextCurr.isEmpty()) return
        val topLevelDist = getLevenshteinDistance(fulltextPrev,fulltextCurr)
        val relativeTopLevelDist = (2*topLevelDist).toFloat()/
                (fulltextPrev.length + fulltextCurr.length).toFloat()
        if (relativeTopLevelDist <= OVERALL_DISTANCE_THRESHOLD) {
            return
        }
        // Compare num of Blocks in both frames
        val blocksDiffCoefficient = abs(prevText.textBlocks.size - currText.textBlocks.size).
        toFloat()/ max(prevText.textBlocks.size, currText.textBlocks.size).toFloat()
        if (blocksDiffCoefficient > BLOCK_ACTIVATOR_THRESHOLD){
            if(DEBUG){
                Log.d(TAG, "handleTextNoiseAndBroadcast: relativeTopLevelDist($OVERALL_DISTANCE_THRESHOLD) = " +
                        "$relativeTopLevelDist")
                Log.d(TAG,fulltextPrev)
                Log.d(TAG,"~~fulltext~~")
                Log.d(TAG,fulltextCurr)
                Log.d(TAG,"topLevel check overridden")
                Log.d(TAG,
                    "handleTextNoiseAndBroadcast: blocksDiff($BLOCK_ACTIVATOR_THRESHOLD) = $blocksDiffCoefficient"
                )
                displayBlockWiseText(prevText)
                Log.d(TAG,"^^BD^^")
                displayBlockWiseText(currText)
                Log.d(TAG, "------------------------BD------------------------------")
            }
            visionTextObject = currText
            processBroadcastText()
            return
        }


        var levenshteinDistance = 0
        for (blockOfPrevText in prevText.textBlocks){
            var preferredLevenshteinDistanceOfBlock = Int.MAX_VALUE
            for (blockOfCurrText in currText.textBlocks){
                //assign most similar (min edit distance to block)
                preferredLevenshteinDistanceOfBlock =
                    min(getLevenshteinDistance(blockOfCurrText.text, blockOfPrevText.text),
                        preferredLevenshteinDistanceOfBlock)
            }
            //accumulate all blocks to get of complete text
            levenshteinDistance += preferredLevenshteinDistanceOfBlock
        }
        val relativeLevenshteinCoefficient = (2*levenshteinDistance).toFloat()/
                (prevText.text.length + currText.text.length).toFloat()
        if (relativeLevenshteinCoefficient >= LEVENSHTEIN_DISTANCE_FACTOR){
            if(DEBUG){
                Log.d(TAG, "handleTextNoiseAndBroadcast: relativeTopLevelDist($OVERALL_DISTANCE_THRESHOLD) = " +
                        "$relativeTopLevelDist")
                Log.d(TAG,fulltextPrev)
                Log.d(TAG,"~~fulltext~~")
                Log.d(TAG,fulltextCurr)
                Log.d(TAG,"topLevel check overridden")
                Log.d(TAG,
                    "handleTextNoiseAndBroadcast: relativeLevenshteinCoefficient($LEVENSHTEIN_DISTANCE_FACTOR) = $relativeLevenshteinCoefficient"
                )
                displayBlockWiseText(prevText)
                Log.d(TAG,"^^LD^^")
                displayBlockWiseText(currText)
                Log.d(TAG, "-----------------------LD-------------------------------")
            }
            visionTextObject = currText
            processBroadcastText()
            return
        }
        //Log.d(TAG, "---------------------------------------------------")
        visionTextObject = currText
    }

    private fun getObjectList(currObjectSet: HashSet<String>): ArrayList<String> {
        return ArrayList(currObjectSet)
    }

    private fun getBlockWiseTextObject(visionText: Text?): ArrayList<String> {
        val returnableObject: ArrayList<String> = ArrayList()
        if (visionText == null) return returnableObject
        for (block in visionText.textBlocks){
            val blockText = block.text
            returnableObject.add(blockText)
        }
        return returnableObject
    }

    private fun setVisionTextObject(visionText: Text?) {
        if (visionTextObject == null && visionText == null){
            return
        }

        else if (visionTextObject == null && visionText != null){
            visionTextObject = visionText
            processBroadcastText()
            return
        }

        else{
            handleTextNoiseAndBroadcast(visionTextObject!!, visionText!!)
        }

    }

    private fun getBundle(objectList: ArrayList<String>, blockWiseTextList: ArrayList<String>): Bundle{
        val bundle = Bundle()
        bundle.putSerializable(KEY1, objectList)
        bundle.putSerializable(KEY2, blockWiseTextList)
        return bundle
    }

    private fun processBroadcastText(){
        if (!MainActivity.bcEnabled) return
        Log.d(TAG,">>> T E X T    BROADCAST SENT")
        // Get Context
        val context:Context = MainActivity.appContext

        if (isPackageInstalled(BENEFICIARY, context.packageManager)){
            val objectList = getObjectList(masterObjectSet)
            val blockWiseTextList = getBlockWiseTextObject(visionTextObject)
            val bundle = getBundle(objectList, blockWiseTextList)
            Companion.sendBroadcastIntent(bundle, context)
            showFireToast(objectList, blockWiseTextList, context)
        }

        else{
            showFailToast(context)
        }

    }

    private fun processBroadcastObject(objectSet: HashSet<String>){
        if (!MainActivity.bcEnabled) return
        Log.d(TAG,">>> ### OBJECT ##  BROADCAST SENT")
        // Get Context
        val context:Context = MainActivity.appContext

        if (isPackageInstalled(BENEFICIARY, context.packageManager)){
            val objectList = getObjectList(objectSet)
            val blockWiseTextList = getBlockWiseTextObject(visionTextObject)
            val bundle = getBundle(objectList, blockWiseTextList)
            Companion.sendBroadcastIntent(bundle, context)
            showFireToast(objectList, blockWiseTextList, context)
        }
        else{
            showFailToast(context)
        }
    }

    private fun displaySharableObject(currObjectSet: HashSet<String>){
        var allStr = ">>> "
        for (obj in currObjectSet){
            allStr += "$obj "
        }
        Log.d(TAG, "displaySharableObject: $allStr")
    }

    // Logic to maintain a dictionary in Static Variable
    private fun objectDictMaintainer(labels: List<ImageLabel>) {
        //Log.d(TAG, "objectDictMaintainer")
        if (DEBUG) displayObjectLabels(labels)
        for (label in labels){
            val key = label.text
            if (objectDict.containsKey(key) ){
                if (objectDict[key]!! >= ADD_OBJECT_THRESHOLD) {
                    objectDict[key] = REMOVE_OBJECT_THRESHOLD + 1
                } else {
                    objectDict[key] = objectDict[key]!! + 2
                }
            } else {
                objectDict[key] = 2
            }
        }

        var masterSetUpdated = false
        for (key in objectDict.keys){
            objectDict[key] = objectDict[key]!! - 1
            if (objectDict[key]!! >= ADD_OBJECT_THRESHOLD){
                if(masterObjectSet.add(key)) masterSetUpdated = true
            } else if (objectDict[key]!! <= 0){
                objectDict[key] = 0
                if(masterObjectSet.remove(key)) masterSetUpdated = true
            }
        }
        objectDict.entries.removeIf {it.value == 0}

        if(masterSetUpdated){
            processBroadcastObject(masterObjectSet)
            if (DEBUG) displaySharableObject(masterObjectSet)
        }
    }

    private fun startImageClassification(
        processableImage: InputImage,
        image: ImageProxy,
        textReadJob: Job,
    ): Unit {
        //Log.d(TAG, "startImageClassification")
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
        //Log.d(TAG, "analyze")
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