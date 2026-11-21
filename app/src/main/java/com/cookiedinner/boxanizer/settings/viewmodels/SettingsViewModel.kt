package com.cookiedinner.boxanizer.settings.viewmodels

import android.app.Application
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.cookiedinner.boxanizer.R
import com.cookiedinner.boxanizer.core.data.DataProvider
import com.cookiedinner.boxanizer.core.database.DatabaseImporter
import com.cookiedinner.boxanizer.core.models.ImportCandidate
import com.cookiedinner.boxanizer.core.models.ImportException
import com.cookiedinner.boxanizer.core.utilities.getPackageInfo
import com.cookiedinner.boxanizer.core.utilities.safelyShowSnackbar
import com.cookiedinner.boxanizer.core.viewmodels.ViewModelWithSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(
    private val context: Application,
    private val dataProvider: DataProvider
) : ViewModelWithSnack() {
    /** Guards export and import, so that they never run at the same time. */
    private var dataOperationInProgress = false

    /** File waiting for the user's confirmation in the import dialog (null = dialog hidden). */
    var importCandidate by mutableStateOf<ImportCandidate?>(null)
        private set
    var importing by mutableStateOf(false)
        private set

    fun prepareImport(sourceUri: Uri) {
        if (dataOperationInProgress) return
        dataOperationInProgress = true
        viewModelScope.launch(Dispatchers.IO) {
            val workFile = File(context.cacheDir, IMPORT_WORK_FILE)
            try {
                deleteWithSidecars(workFile)
                val inputStream = context.contentResolver.openInputStream(sourceUri)
                    ?: throw ImportException(ImportException.Reason.NOT_A_DATABASE, "Could not open the chosen file")
                inputStream.use { input ->
                    workFile.outputStream().use { output -> input.copyTo(output) }
                }
                val fileCounts = DatabaseImporter.inspect(context, workFile)
                val currentCounts = dataProvider.getDataCounts()
                withContext(Dispatchers.Main) {
                    importCandidate = ImportCandidate(workFile, fileCounts, currentCounts)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                deleteWithSidecars(workFile)
                snackbarHostState.safelyShowSnackbar(importErrorMessage(ex))
            } finally {
                dataOperationInProgress = false
            }
        }
    }

    fun cancelImport() {
        if (importing) return
        importCandidate?.let { deleteWithSidecars(it.file) }
        importCandidate = null
    }

    fun confirmImport() {
        val candidate = importCandidate ?: return
        if (dataOperationInProgress || importing) return
        dataOperationInProgress = true
        importing = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureEnoughSpaceFor(candidate.file)
                backUpCurrentData()
                val counts = dataProvider.importDatabase(candidate.file)
                snackbarHostState.safelyShowSnackbar(
                    message = context.getString(R.string.import_database_success, counts.boxes, counts.items),
                    duration = SnackbarDuration.Long
                )
            } catch (ex: Exception) {
                ex.printStackTrace()
                snackbarHostState.safelyShowSnackbar(importErrorMessage(ex))
            } finally {
                deleteWithSidecars(candidate.file)
                withContext(Dispatchers.Main) {
                    importCandidate = null
                    importing = false
                }
                dataOperationInProgress = false
            }
        }
    }

    private fun ensureEnoughSpaceFor(importFile: File) {
        val currentDatabaseSize = context.getDatabasePath(DATABASE_NAME).length()
        // backup of the current data + imported data in the main database + its journal, with a margin
        val needed = currentDatabaseSize + importFile.length() * 2 + 20L * 1024 * 1024
        if (context.filesDir.usableSpace < needed)
            throw ImportException(ImportException.Reason.NOT_ENOUGH_SPACE)
    }

    /**
     * Saves the current data before it gets replaced. Keeps the [BACKUPS_TO_KEEP] newest copies.
     * If the backup fails, the exception stops the import.
     */
    private fun backUpCurrentData() {
        val backupDir = File(context.filesDir, BACKUP_DIR).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        dataProvider.exportDatabase(File(backupDir, "before-import-$timestamp.db"))
        backupDir.listFiles { file -> file.name.startsWith("before-import-") }
            ?.sortedByDescending { it.name }
            ?.drop(BACKUPS_TO_KEEP)
            ?.forEach { it.delete() }
    }

    private fun importErrorMessage(ex: Exception): String = context.getString(
        when ((ex as? ImportException)?.reason) {
            ImportException.Reason.NOT_A_DATABASE -> R.string.import_error_not_a_database
            ImportException.Reason.CORRUPTED -> R.string.import_error_corrupted
            ImportException.Reason.NEWER_VERSION -> R.string.import_error_newer_version
            ImportException.Reason.NOT_ENOUGH_SPACE -> R.string.import_error_not_enough_space
            else -> R.string.import_database_failed
        }
    )

    private fun deleteWithSidecars(file: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private companion object {
        const val DATABASE_NAME = "boxanizer.db"
        const val IMPORT_WORK_FILE = "boxanizer-import.db"
        const val BACKUP_DIR = "backups"
        const val BACKUPS_TO_KEEP = 3
    }

    fun suggestedExportFileName(): String {
        val version = getPackageInfo(context, 0).versionName
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        return "boxanizer-$version-$date.db"
    }

    fun exportDatabase(targetUri: Uri) {
        if (dataOperationInProgress) return
        dataOperationInProgress = true
        viewModelScope.launch(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "boxanizer-export.db")
            try {
                val summary = dataProvider.exportDatabase(tempFile)
                val outputStream = context.contentResolver.openOutputStream(targetUri, "w")
                    ?: throw Exception("Could not open the chosen file for writing")
                outputStream.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                }
                snackbarHostState.safelyShowSnackbar(
                    message = context.getString(R.string.export_database_success, summary.boxes, summary.items),
                    duration = SnackbarDuration.Long
                )
            } catch (ex: Exception) {
                ex.printStackTrace()
                snackbarHostState.safelyShowSnackbar(context.getString(R.string.export_database_failed))
            } finally {
                tempFile.delete()
                dataOperationInProgress = false
            }
        }
    }

    fun checkForUpdates(openBrowserForUrl: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val latestRelease = dataProvider.checkForUpdates()
                if (latestRelease == null) {
                    snackbarHostState.safelyShowSnackbar(context.getString(R.string.no_other_version_available))
                    return@launch
                }
                val currentVersion = getPackageInfo(context, 0).versionName
                if (latestRelease.name > currentVersion) {
                    val result = snackbarHostState.safelyShowSnackbar(
                        message = context.getString(R.string.newer_version_available),
                        actionLabel = context.getString(R.string.open),
                        duration = SnackbarDuration.Indefinite
                    )
                    when (result) {
                        SnackbarResult.Dismissed -> {}
                        SnackbarResult.ActionPerformed -> {
                            withContext(Dispatchers.Main) {
                                openBrowserForUrl(latestRelease.html_url)
                            }
                        }

                        null -> {}
                    }
                } else {
                    snackbarHostState.safelyShowSnackbar(context.getString(R.string.you_have_the_newest_version))
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                snackbarHostState.safelyShowSnackbar(context.getString(R.string.failed_to_check_for_updates))
            }
        }
    }
}