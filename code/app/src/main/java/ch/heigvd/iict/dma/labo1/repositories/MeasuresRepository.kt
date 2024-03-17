package ch.heigvd.iict.dma.labo1.repositories

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ch.heigvd.iict.dma.labo1.models.*
import ch.heigvd.iict.dma.protobuf.MeasuresOuterClass.MeasuresAck
import ch.heigvd.iict.dma.protobuf.MeasuresOuterClass.Status
import ch.heigvd.iict.dma.protobuf.measure
import ch.heigvd.iict.dma.protobuf.measures
import com.google.gson.GsonBuilder
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

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

    fun toSimpleString(date: Date) = with(date) {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(this)
    }

    fun sendMeasureToServer(encryption : Encryption, compression : Compression, networkType : NetworkType, serialisation : Serialisation) {
        scope.launch(Dispatchers.Default) {

            val url = when (encryption) {
                Encryption.DISABLED -> httpUrl
                Encryption.SSL -> httpsUrl
            }

            val measureList = _measures.value!!

            val gson = GsonBuilder()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                .create()

            val elapsed = measureTimeMillis {
                with(URL(url).openConnection() as HttpURLConnection) {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", when (serialisation) {
                        Serialisation.JSON -> "application/json; charset=utf-8"
                        Serialisation.XML -> "application/xml; charset=utf-8"
                        Serialisation.PROTOBUF -> "application/protobuf"
                    })
                    setRequestProperty("User-Agent", "Dorian")
                    setRequestProperty("X-Network", networkType.toString())
                    setRequestProperty("X-Content-Encoding", when(compression) {
                        Compression.DISABLED -> "NONE"
                        Compression.DEFLATE -> "DEFLATE"
                    })

                    try {
                        doOutput = true

                        var newOutPutStream = outputStream

                        if (compression == Compression.DEFLATE) {
                            newOutPutStream = DeflaterOutputStream(newOutPutStream, Deflater(Deflater.DEFAULT_COMPRESSION, true))
                        }

                        when (serialisation) {
                            Serialisation.JSON -> {
                                OutputStreamWriter(newOutPutStream, "UTF-8").use { writer ->
                                    writer.write(gson.toJson(measureList)) //it
                                    writer.flush()
                                }
                            }
                            Serialisation.XML -> {
                                val rootElement = Element("measures")
                                val document = Document(rootElement)

                                for (measure in measureList) {
                                    val measureElement = Element("measure")
                                    measureElement.setAttribute("id", measure.id.toString())
                                    measureElement.setAttribute("status", measure.status.toString())

                                    measureElement.addContent(Element("type").addContent(measure.type.toString()))
                                    measureElement.addContent(Element("value").addContent(measure.value.toString()))
                                    measureElement.addContent(Element("date").addContent(toSimpleString(measure.date.time)))

                                    rootElement.addContent(measureElement)
                                }

                                val dtdURL = "https://mobile.iict.ch/measures.dtd"
                                document.docType = DocType("measures", dtdURL)

                                val format = org.jdom2.output.Format.getPrettyFormat()
                                format.textMode = org.jdom2.output.Format.TextMode.PRESERVE
                                val xmlOutputter = XMLOutputter(format)

                                val xmlString = xmlOutputter.outputString(document)

                                OutputStreamWriter(newOutPutStream, "UTF-8").use { writer ->
                                    writer.write(xmlString) //it
                                    writer.flush()
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

                                DataOutputStream(newOutPutStream).use {
                                    it.write(msrs.toByteArray())
                                    it.flush()
                                }
                            }
                        }
                        newOutPutStream.close()

                        if (responseCode != 200) {
                            var newInputStream = inputStream

                            if (compression == Compression.DEFLATE) {
                                newInputStream = InflaterInputStream(newInputStream, Inflater(true))
                            }

                            when (serialisation) {
                                Serialisation.JSON -> {
                                    BufferedReader(InputStreamReader(newInputStream)).use { reader ->
                                        val response = StringBuilder()
                                        var line: String?
                                        while (reader.readLine().also { line = it } != null) {
                                            response.append(line)
                                        }

                                        val res = gson.fromJson<List<MeasureACK>>(response.toString(), object : TypeToken<List<MeasureACK>>() {}.type)

                                        for (r in res) {
                                            measureList[r.id].status = r.status
                                        }
                                        _measures.postValue(measureList)
                                    }
                                }
                                Serialisation.XML -> {
                                    BufferedReader(InputStreamReader(newInputStream)).use { reader ->
                                        val response = StringBuilder()
                                        var line: String?

                                        while (reader.readLine().also { line = it } != null) {
                                            response.append(line)
                                        }

                                        val builder = SAXBuilder()
                                        builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
                                        val res = builder.build(StringReader(response.toString()))

                                        for (r in res.rootElement.children) {
                                            val status = Measure.Status.valueOf(r.getAttribute("status").value)
                                            measureList[r.getAttribute("id").intValue].status = status
                                        }

                                        _measures.postValue(measureList)
                                    }
                                }
                                Serialisation.PROTOBUF -> {
                                    DataInputStream(newInputStream).use { reader ->
                                        val bytes = reader.readBytes()

                                        val measures = _measures.value!!
                                        val result = MeasuresAck.parseFrom(bytes)

                                        result.measuresList.forEach{ measureAck ->
                                            measures[measureAck.id].status = Measure.Status.entries[measureAck.status.ordinal]
                                        }

                                        _measures.postValue(measures)
                                    }
                                }
                            }
                        } else {
                            Log.e("SendFragment", "Error : ${getHeaderField("X-Error")}")
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } finally {
                        disconnect()
                    }
                }
            }
            _requestDuration.postValue(elapsed)
        }
    }

}