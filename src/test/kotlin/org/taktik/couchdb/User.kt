/*
 * Copyright (c) 2020. Taktik SA, All rights reserved.
 */
package org.taktik.couchdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.taktik.couchdb.entity.Attachment
import java.io.Serializable

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class User(
	@param:JsonProperty("_id") override val id: String,
	@param:JsonProperty("_rev") override val rev: String? = null,
	@param:JsonProperty("deleted") val deletionDate: Long? = null,
	val created: Long? = null,

	val name: String? = null,
	val roles: Set<String> = emptySet(),
	val login: String? = null,
	val passwordHash: String? = null,
	val secret: String? = null,
	@param:JsonProperty("isUse2fa") val use2fa: Boolean? = null,
	val groupId: String? = null,
	val healthcarePartyId: String? = null,
	val patientId: String? = null,
	val deviceId: String? = null,
	val createdDate: Long? = null,
	val lastLoginDate: Long? = null,
	val expirationDate: Long? = null,
	val termsOfUseDate: Long? = null,

	val email: String? = null,
	val mobilePhone: String? = null,

	@Deprecated("Application tokens stocked in clear and eternal. Replaced by authenticationTokens")
	val applicationTokens: Map<String, String>? = null,

	@param:JsonProperty("_attachments") val attachments: Map<String, Attachment>? = emptyMap(),
	@param:JsonProperty("_conflicts") val conflicts: List<String>? = emptyList(),
	@param:JsonProperty("rev_history") override val revHistory : Map<String, String>? = emptyMap(),
) : CouchDbDocument, Cloneable, Serializable {
	override fun withIdRev(id: String?, rev: String) = if (id != null) this.copy(id = id, rev = rev) else this.copy(rev = rev)

	@JsonProperty("java_type")
	fun getJavaType(): String = "User"

	@JsonProperty("java_type")
	fun setJavaType(value: String) {
		if ("User" != value) throw DeserializationTypeException(this.id)
	}
}
