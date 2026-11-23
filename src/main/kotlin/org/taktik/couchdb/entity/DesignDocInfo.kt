package org.taktik.couchdb.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.io.Serializable

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class DesignDocInfo(
	val name: String,
	@param:JsonProperty("view_index") val viewIndex: ViewIndex
) : Serializable

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ViewIndex(
	@param:JsonProperty("compact_running") val compactRunning: Boolean,
	val language: String,
	@param:JsonProperty("purge_seq") val purgeSeq: String?,
	@param:JsonProperty("update_seq") val updateSeq: String?,
	val signature: String,
	val sizes: Sizes,
	@param:JsonProperty("updater_running") val updaterRunning: Boolean,
	@param:JsonProperty("waiting_clients") val waitingClients: Int,
	@param:JsonProperty("waiting_commit") val waitingCommit: Boolean,
) : Serializable