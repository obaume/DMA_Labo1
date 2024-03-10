package ch.heigvd.iict.dma.labo1.repositories

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ch.heigvd.iict.dma.labo1.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.measureTimeMillis

class MeasuresRepository(private val scope : CoroutineScope,
                         private val dtd : String = "https://mobile.iict.ch/measures.dtd",
                         private val httpUrl : String = "http://mobile.iict.ch/api",
                         private val httpsUrl : String = "https://mobile.iict.ch/api") {

    private val _measures = MutableLiveData(mutableListOf<Measure>())
    val measures = _measures.map { mList -> mList.toList() }

    private val _requestDuration = MutableLiveData(-1L)
    val requestDuration : LiveData<Long> get() = _requestDuration

    fun generateRandomMeasures(nbr: Int = 3) {
        addMeasures(Measure.getRandomMeasures(nbr))
    }

    fun resetRequestDuration() {
        _requestDuration.postValue(-1L)
    }

    fun addMeasure(measure: Measure) {
        addMeasures(listOf(measure))
    }

    fun addMeasures(measures: List<Measure>) {
        val l = _measures.value!!
        l.addAll(measures)
        _measures.postValue(l)
    }

    fun clearAllMeasures() {
        _measures.postValue(mutableListOf())
    }

    fun sendMeasureToServer(encryption : Encryption, compression : Compression, networkType : NetworkType, serialisation : Serialisation) {
        scope.launch(Dispatchers.Default) {

            val url = when (encryption) {
                Encryption.DISABLED -> httpUrl
                Encryption.SSL -> httpsUrl
            }

            val elapsed = measureTimeMillis {
                Log.e("SendViewModel", "Implement me !!! Send measures to $url") //TODO
                when (serialisation) {
                    Serialisation.JSON -> {
                        val gson = Gson()

                        val measures = _measures.value!!

                        with(URL(url).openConnection() as HttpURLConnection) {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("User-Agent", "Dorian")

                            try {
                                // Enable output (sending data to server)
                                doOutput = true

                                // Write JSON data to the connection's output stream
                                OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                                    writer.write(gson.toJson(measures)) //it
                                    writer.flush()
                                }

                                // Read the response from the server
                                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                    val response = StringBuilder()
                                    var line: String?
                                    while (reader.readLine().also { line = it } != null) {
                                        response.append(line)
                                    }
                                    // Handle response
                                    val res = gson.fromJson<List<MeasureACK>>(response.toString(), object : TypeToken<List<MeasureACK>>() {}.type)

                                    for (r in res) {
                                        measures[r.id].status = r.status
                                    }
                                    _measures.postValue(measures)
                                }
                            } catch (e: IOException) {
                                e.printStackTrace()
                            } finally {
                                disconnect()
                            }
                        }

                    }
                    Serialisation.XML -> {

                    }
                    Serialisation.PROTOBUF -> {

                    }
                }
            }
            _requestDuration.postValue(elapsed)
        }
    }

}