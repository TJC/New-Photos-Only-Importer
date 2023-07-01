package net.dryft.newphotosonlyimporter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import net.dryft.newphotosonlyimporter.ui.theme.NewPhotosOnlyImporterTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.w("Photo Syncer","App starting up...")

        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (isGranted) {
                    // Permission is granted.
                    Log.w("Photo Syncer", "permission granted")
                } else {
                    Log.w("Photo Syncer", "Permission denied!")
                }
            }

        getPermissions(applicationContext , requestPermissionLauncher)

        if (! Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))

        // The result of this is the base DCIM directory where we should copy things to:
        Log.i("Storage Int", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString())

        val sm = application.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        sm.storageVolumes.forEach {
            Log.i("Storage Ext", "${it.directory} Removable? ${it.isRemovable}")
        }

        // This is where the SDcard or hard drive is mounted
        val mountPoint = sm.storageVolumes.find {
            it.isRemovable
        }

        setContent {
            NewPhotosOnlyImporterTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    IntroPage(mountPoint?.directory)
//                    SimplePage()
                }
            }
        }
    }
}

@Composable
fun SimplePage(modifier: Modifier = Modifier) {
    Text(
        text = "This app checks for new photos on an external SD card and then copies them into main storage.",
        modifier = modifier
    )
}

@Composable
fun IntroPage(mountPoint: File?, modifier: Modifier = Modifier) {
    val photosDir = findPhotosBaseDir(mountPoint)
    val files = remember { mutableStateOf<List<String>>(emptyList()) }
    Column() {
        Text(
            text = "This app checks for new photos on an external SD card and then copies them into main storage.",
            modifier = modifier
        )
        Text("Base dir = $photosDir")
        Button(modifier = modifier, onClick = { startProcess(photosDir, files) }) {
            Text("Go")
        }
        files.value.forEach {
            Text(it)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun IntroPagePreview() {
    NewPhotosOnlyImporterTheme {
        IntroPage(File("/tmp"))
    }
}

// Try to find a DCIM directory that's at most two levels deeper than the start point.
fun findPhotosBaseDir(mountpoint: File?) : File? {
    return mountpoint?.walk()?.maxDepth(2)?.find {
        it.endsWith("DCIM")
    }
}

fun startProcess(baseDir: File?, files: MutableState<List<String>>) {
    if (baseDir != null) {
        baseDir.walkTopDown().forEach {
            files.value += "$it"
        }
    }
    else {
        files.value += "No DCIM dir found"
    }
}


fun getPermissions(context: Context, requestPermissionLauncher: ActivityResultLauncher<String>) {
    val permissions = arrayOf (
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_IMAGES
    )

    permissions.forEach { perm ->
        if (
            ContextCompat.checkSelfPermission(
                context,
                perm
            ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(perm)
            }

    }
}