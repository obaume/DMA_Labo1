package ch.heigvd.iict.dma.labo1.repositories

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ch.heigvd.iict.dma.labo1.models.*
import ch.heigvd.iict.dma.protobuf.MeasuresAckKt
import ch.heigvd.iict.dma.protobuf.MeasuresKt
import ch.heigvd.iict.dma.protobuf.MeasuresOuterClass.MeasuresAck
import ch.heigvd.iict.dma.protobuf.MeasuresOuterClass.Status
import ch.heigvd.iict.dma.protobuf.measure
import ch.heigvd.iict.dma.protobuf.measures
import com.google.firebase.encoders.proto.Protobuf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jdom2.DocType
import org.jdom2.Document
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.measureTimeMillis
import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import org.jdom2.output.XMLOutputter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.StringReader
import java.text.Format

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
            val user_agent = "Dorian"
            val elapsed = measureTimeMillis {
                Log.e("SendViewModel", "Implement me !!! Send measures to $url") //TODO
                when (serialisation) {
                    Serialisation.JSON -> {
                        val gson = Gson()

                        val measures = _measures.value!!

                        with(URL(url).openConnection() as HttpURLConnection) {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("User-Agent", user_agent)

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
                        val rootElement = Element("measures")
                        val document = Document(rootElement)

                        val measures = _measures.value!!

                        // Measure parsing to XML
                        for (measure in measures) {
                            val measureElement = Element("measure")
                            measureElement.setAttribute("id", measure.id.toString())
                            measureElement.setAttribute("status", measure.status.toString())

                            measureElement.addContent(Element("type").addContent(measure.type.toString()))
                            measureElement.addContent(Element("value").addContent(measure.value.toString()))
                            // TODO date format
                            measureElement.addContent(Element("date").addContent(measure.date.toString()))

                            rootElement.addContent(measureElement)
                        }

                        val dtdURL = "https://mobile.iict.ch/measures.dtd"
                        document.docType = DocType("measures", dtdURL)

                        val format = org.jdom2.output.Format.getPrettyFormat()
                        format.textMode = org.jdom2.output.Format.TextMode.PRESERVE
                        val xmlOutputter = XMLOutputter(format)

                        val xmlString = xmlOutputter.outputString(document)

                        with(URL(url).openConnection() as HttpURLConnection) {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                            setRequestProperty("User-Agent", user_agent)

                            try {
                                // Enable output (sending data to server)
                                doOutput = true

                                // Write XML data to the connection's output stream
                                OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                                    writer.write(xmlString) //it
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
                                    val builder = SAXBuilder()
                                    builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
                                    val res = builder.build(StringReader(response.toString()))

                                    // Status update
                                    for (r in res.rootElement.children) {
                                        val status = Measure.Status.valueOf(r.getAttribute("status").value)
                                        measures[r.getAttribute("id").intValue].status = status
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
                    Serialisation.PROTOBUF -> {
                        val msrs = measures {
                            _measures.value!!.forEach {
                                measures.add(measure {
                                    id = it.id
                                    status = Status.forNumber(it.status.ordinal)
                                    type = it.type.toString()
                                    value = it.value
                                    date = it.date.timeInMillis
                                })
                            }
                        }

                        with(URL(url).openConnection() as HttpURLConnection) {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/protobuf")
                            setRequestProperty("User-Agent", user_agent)

                            try {
                                // Enable output (sending data to server)
                                doOutput = true

                                // Write Protobuf data to the connection's output stream
                                DataOutputStream(outputStream).use {
                                    it.write(msrs.toByteArray())
                                    it.flush()
                                }

                                // Read the response from the server
                                DataInputStream(inputStream).use { reader ->
                                    val bytes = reader.readBytes()

                                    // Handle response
                                    val measures = _measures.value!!
                                    val result = MeasuresAck.parseFrom(bytes)
                                    
                                    result.measuresList.forEach{ measureAck ->
                                        measures[measureAck.id].status = Measure.Status.entries[measureAck.status.ordinal]
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
                }
            }
            _requestDuration.postValue(elapsed)
        }
    }

}