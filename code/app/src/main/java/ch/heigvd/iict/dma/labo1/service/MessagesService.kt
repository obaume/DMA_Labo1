package ch.heigvd.iict.dma.labo1.repositories

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

public class MessagesService : FirebaseMessagingService() {

    private val TAG = "MessagesService"


    override fun onMessageReceived(remoteMessage: RemoteMessage){
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Message received: ${remoteMessage.data}")


        val message = remoteMessage.data["message"]
        //if (message == "clear")

    }
}