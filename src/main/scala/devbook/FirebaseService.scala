package DevBook

import java.io.FileInputStream
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.cloud.FirestoreClient
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseService {
  private val serviceAccount = new FileInputStream(System.getProperty("user.dir") + "/serviceAccount.json")
  private val credentials = GoogleCredentials.fromStream(serviceAccount)
  private val options = new FirebaseOptions.Builder()
    .setCredentials(credentials)
    .build()

  // Only available to all members of the current package DevBook
  private[DevBook] val firebaseApp = FirebaseApp.initializeApp(options)

  private[DevBook] val db = FirestoreClient.getFirestore(firebaseApp)
}

