package com.notekeep.local.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object BackupManager {

    /**
     * Embeds each note's background image directly in the backup (base64), instead of just its
     * uri string. A content:// uri (or even an old file:// path) is meaningless once restored on
     * another device or after the source file is gone, so the actual image bytes travel with the
     * backup and get written back out to a fresh local file on restore.
     */
    fun toJson(context: Context, notes: List<Note>, labelsByNoteId: Map<Long, List<String>> = emptyMap()): String {
        val array = JSONArray()
        for (note in notes) {
            val obj = JSONObject()
            obj.put("title", note.title)
            obj.put("content", note.content)
            obj.put("color", note.color)
            obj.put("createdAt", note.createdAt)
            obj.put("updatedAt", note.updatedAt)
            obj.put("pinned", note.pinned)
            obj.put("archived", note.archived)

            val bgUri = note.backgroundImageUri
            if (bgUri != null) {
                val base64 = ImageStore.readAsBase64(context, bgUri)
                if (base64 != null) {
                    obj.put("backgroundImageData", base64)
                    obj.put("backgroundImageExt", bgUri.substringAfterLast('.', "jpg").take(4))
                } else {
                    // couldn't read the source (permission gone, file missing) - nothing to embed
                    obj.put("backgroundImageData", JSONObject.NULL)
                }
            } else {
                obj.put("backgroundImageData", JSONObject.NULL)
            }

            val labelsArray = JSONArray()
            labelsByNoteId[note.id]?.forEach { labelsArray.put(it) }
            obj.put("labels", labelsArray)
            array.put(obj)
        }
        val root = JSONObject()
        root.put("app", "NotesLink")
        root.put("version", 3)
        root.put("notes", array)
        return root.toString()
    }

    /**
     * Parses a backup file. Ignores original ids so imported notes get fresh ones. Any embedded
     * background image is written out to a new private file automatically - the caller doesn't
     * need to do anything further for it to show up as the note's background.
     */
    fun fromJson(context: Context, json: String): List<Note> {
        val root = JSONObject(json)
        val array = root.optJSONArray("notes") ?: JSONArray(json)
        val notes = ArrayList<Note>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)

            val embeddedData = if (obj.isNull("backgroundImageData")) null else obj.optString("backgroundImageData")
            val restoredUri: String? = if (!embeddedData.isNullOrEmpty()) {
                val ext = obj.optString("backgroundImageExt", "jpg").ifBlank { "jpg" }
                ImageStore.writeFromBase64(context, embeddedData, ext)
            } else {
                // backward compatibility with older backups (version 2 and earlier) that only
                // stored a bare uri string, which may still be resolvable right after restore
                val legacyUri = obj.optString("backgroundImageUri", null)
                legacyUri.takeUnless { it.isNullOrEmpty() || it == "null" }
            }

            notes.add(
                Note(
                    title = obj.optString("title", ""),
                    content = obj.optString("content", ""),
                    color = obj.optInt("color", 0),
                    createdAt = obj.optLong("createdAt", obj.optLong("updatedAt", System.currentTimeMillis())),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    pinned = obj.optBoolean("pinned", false),
                    archived = obj.optBoolean("archived", false),
                    backgroundImageUri = restoredUri
                )
            )
        }
        return notes
    }

    /** Label names attached to each note in a backup file, keyed by the note's position/order in the file. */
    fun labelsPerNote(json: String): List<List<String>> {
        val root = JSONObject(json)
        val array = root.optJSONArray("notes") ?: JSONArray(json)
        val result = ArrayList<List<String>>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val labelsArray = obj.optJSONArray("labels")
            val labels = ArrayList<String>()
            if (labelsArray != null) {
                for (j in 0 until labelsArray.length()) labels.add(labelsArray.getString(j))
            }
            result.add(labels)
        }
        return result
    }
}
