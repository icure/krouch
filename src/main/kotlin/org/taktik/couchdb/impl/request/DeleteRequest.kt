/*
 *    Copyright 2020 Taktik SA
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package org.taktik.couchdb.impl.request

import com.fasterxml.jackson.annotation.JsonProperty
import io.icure.asyncjacksonhttpclient.net.param

internal data class DeleteRequest(
	@param:JsonProperty("_id") val id: String,
	@param:JsonProperty("_rev") val rev: String?,
	@param:JsonProperty("_deleted") val deleted: Boolean = true
)
