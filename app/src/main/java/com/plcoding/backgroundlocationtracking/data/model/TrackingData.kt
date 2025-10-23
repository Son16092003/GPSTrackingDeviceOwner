@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.plcoding.backgroundlocationtracking.data.model

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.serialization.Serializable

@Serializable
data class TrackingData(
    val Oid: String = UUID.randomUUID().toString(),
    val DeviceID: String,
    val Title: String? = null,
    val Latitude: Double,
    val Longitude: Double,
    val RecordDate: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(Date()),
    val OptimisticLockField: Int? = null,
    val GCRecord: Int? = null,
    val UserName: String? = null
) {
    fun toJsonString(): String {
        val jo = JSONObject()
        jo.put("Oid", Oid)
        jo.put("DeviceID", DeviceID)
        jo.put("Title", Title)
        jo.put("Latitude", Latitude)
        jo.put("Longitude", Longitude)
        jo.put("RecordDate", RecordDate)
        jo.put("OptimisticLockField", OptimisticLockField ?: JSONObject.NULL)
        jo.put("GCRecord", GCRecord ?: JSONObject.NULL)
        jo.put("UserName", UserName)
        return jo.toString()
    }
}
