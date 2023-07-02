package net.dryft.newphotosonlyimporter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dryft.newphotosonlyimporter.ui.theme.NewPhotosOnlyImporterTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.notExists

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.w("Photo Syncer", "App starting up...")

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

        getPermissions(applicationContext, requestPermissionLauncher)

        if (!Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))

        // The result of this is the base DCIM directory where we should copy things to:
        val targetDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)

        val sm = application.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        setContent {
            NewPhotosOnlyImporterTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IntroPage(sm, targetDir)
                }
            }
        }
    }
}

@Composable
fun StorageList(
    sm: StorageManager,
    selectedDir: MutableState<File?>,
    photosBaseDir: MutableState<File?>
) {
    // TODO: Provide mechanism to select preferred drive, if there are more than one.

    val refresh = {
        sm.storageVolumes.forEach {
            Log.i("Storage Ext", "${it.directory} Removable? ${it.isRemovable}")
        }

        // This is where the SDcard or hard drive is mounted
        val mountPoints = sm.storageVolumes.filter {
            it.isRemovable && it.directory != null
        }.map { it.directory!! }

        if (mountPoints.isEmpty())
            selectedDir.value = null
        else {
            selectedDir.value = mountPoints.first()
            photosBaseDir.value = findPhotosBaseDir(selectedDir.value)
        }
    }

    refresh()

    Column() {
        DisplayVolumeInfo(selectedDir, photosBaseDir)
        Button(onClick = refresh) { Text("Refresh") }
    }
}

@Composable
fun DisplayVolumeInfo(selectedDir: MutableState<File?>, photosBaseDir: MutableState<File?>) {
    Column() {
        if (selectedDir.value == null)
            Text("Volume: None found")
        else {
            Text("Volume: ${selectedDir.value}")
            if (photosBaseDir.value == null)
                Text("No DCIM folder found")
            else
                Text("Photos: ${photosBaseDir.value}")
        }
    }
}

@Composable
fun IntroPage(sm: StorageManager, targetDir: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val selectedBaseDir = remember { mutableStateOf<File?>(null) }
    val photosBaseDir = remember { mutableStateOf<File?>(null) }

    // This is where the output/progress ends up, at the moment
    val files = remember { mutableStateOf<List<String>>(emptyList()) }

    val startOnClick: () -> Unit = {
        coroutineScope.launch {
            startProcess(
                context,
                photosBaseDir.value,
                targetDir,
                files
            )
        }
    }

    Column() {
        Text(
            text = "This app checks for new photos on an external SD card and then copies them into main storage, split into directories by date."
        )
        StorageList(sm, selectedBaseDir, photosBaseDir)

        Button(onClick = startOnClick) {
            Text("Go")
        }
        files.value.forEach {
            Text(it)
        }
    }
}

//@Preview(showBackground = true)
//@Composable
//fun IntroPagePreview() {
//    NewPhotosOnlyImporterTheme {
//        IntroPage(StorageManager())
//    }
//}

// Try to find a DCIM directory that's at most two levels deeper than the start point.
fun findPhotosBaseDir(mountpoint: File?): File? {
    return mountpoint?.walk()?.maxDepth(2)?.find {
        it.endsWith("DCIM")
    }
}

/*
  This is where the action actually happens.

  Image files are located on the mounted external storage.
  A target directory is created on the internal storage, based on the modification time of the file,
  converted to an YYYY-MM-DD date string.
  If the image file does not already exist, then it's copied over.
  At the end, all copied files are sent to the MediaScanner service, in order for them to be
  visible to other services.

  To be honest, it's possible I should be using a different interface to import the files.
  Something to look into later.

 */
suspend fun startProcess(
    context: Context,
    baseDir: File?,
    targetDir: File,
    files: MutableState<List<String>>
) {
    if (baseDir == null) return;

    withContext(Dispatchers.IO) {

        files.value = emptyList()

        var copiedFiles = emptyArray<String>()

        baseDir.walkTopDown().filter {
            it.isFile && it.name.endsWith("jpg", ignoreCase = true)
        }
            .forEach {
                val t =
                    Instant.ofEpochMilli(it.lastModified()).atZone(ZoneId.systemDefault())
                        .toLocalDate()
                val dtstring = t.toString()
                val source = Path(it.path)
                val target = Path("$targetDir/$dtstring/${it.name}")
                File("$targetDir/$dtstring").mkdirs()
                if (target.notExists() || source.fileSize() != target.fileSize()) {
                    files.value += "${it.name} --> $dtstring/${it.name}"
                    source.copyTo(target, overwrite = true)
                    copiedFiles += target.toString()
                }
            }

        MediaScannerConnection.scanFile(context, copiedFiles, null, null)
    }
}


/*
    I don't think this is actually working
 */
fun getPermissions(context: Context, requestPermissionLauncher: ActivityResultLauncher<String>) {
    val permissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_IMAGES
    )

    permissions.forEach { perm ->
        if (
            ContextCompat.checkSelfPermission(
                context,
                perm
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(perm)
        }
    }
}