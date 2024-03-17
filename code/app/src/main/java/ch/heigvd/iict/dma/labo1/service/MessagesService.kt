package ch.heigvd.iict.dma.labo1.service

import android.util.Log
import ch.heigvd.iict.dma.labo1.database.MessagesDatabase
import ch.heigvd.iict.dma.labo1.models.Message
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Calendar

public class MessagesService : FirebaseMessagingService() {

    private val TAG = "MessagesService"

    override fun onMessageReceived(remoteMessage: RemoteMessage){
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Message received from : ${remoteMessage.from}")

        remoteMessage.data.isNotEmpty().let {
            Log.d(TAG, "Message data: " + remoteMessage.data)

            val message = Message(
                    sentDate = Calendar.getInstance(),
                    receptionDate = Calendar.getInstance(),
                    message = remoteMessage.data["message"],
                    command = remoteMessage.data["command"]
            )
            MessagesDatabase.getDatabase(this).messagesDao().insert(message)
        }

        if (remoteMessage.data["command"] == "clear"){
            MessagesDatabase.getDatabase(this).messagesDao().deleteAllMessage()
        }

    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Created/Refreshed token: $token")
    }
}