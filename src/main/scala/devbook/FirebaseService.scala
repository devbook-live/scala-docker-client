package DevBook

import java.io.{ File, FileInputStream, PrintWriter, IOException }
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.cloud.FirestoreClient
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseService {
  private val serviceAccountPath = System.getProperty("user.dir") + "/serviceAccountKey.json"
  private val serviceAccountFile = new File(serviceAccountPath)

  if (!serviceAccountFile.exists()) {
    val credentialsContents = sys.env("GOOGLE_APPLICATION_CREDENTIALS")
    val printWriter = new PrintWriter(serviceAccountPath)
    printWriter.write(credentialsContents)
    if (printWriter.checkError()) throw new IOException()
    printWriter.close
  }

  private val serviceAccount = new FileInputStream(serviceAccountPath)
  private val credentials = GoogleCredentials.fromStream(serviceAccount)
  private val options = new FirebaseOptions.Builder()
    .setCredentials(credentials)
    .build()

  // Only available to all members of the current package DevBook
  private[DevBook] val firebaseApp = FirebaseApp.initializeApp(options)

  private[DevBook] val db = FirestoreClient.getFirestore(firebaseApp)
}

