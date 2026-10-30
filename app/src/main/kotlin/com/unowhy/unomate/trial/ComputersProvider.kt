package com.unowhy.unomate.trial

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.core.net.toUri
import com.unowhy.unomate.Computers

/**
 * Ordinateurs appairés (jetons compris, [Computers.export]) lus par l'appli d'essai dans la stable ([Trial.importComputers]).
 * Exporté sous la permission `com.unowhy.unomate.permission.COMPUTERS` de niveau signature (AndroidManifest.xml) :
 * seule une appli signée par la même clé le lit, Android refuse les autres. Lecture seule : une ligne, une colonne JSON.
 */
class ComputersProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sortOrder: String?): Cursor {
        val context = context ?: throw IllegalStateException("Fournisseur non attaché")
        return MatrixCursor(arrayOf(COLUMN)).apply { addRow(arrayOf(Computers.export(context))) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private const val COLUMN = "computers"

        /** Adresse du fournisseur d'une des deux applis (autorité `<paquet>.computers`, AndroidManifest.xml). */
        fun uri(pkg: String): Uri = "content://$pkg.computers".toUri()
    }
}
