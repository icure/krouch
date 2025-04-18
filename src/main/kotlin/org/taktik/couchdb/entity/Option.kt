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

package org.taktik.couchdb.entity

sealed interface Option {
    val queryParamName: String
    val queryParamValue: String

    companion object {
        @Deprecated(
            message = "Replaced by data object",
            replaceWith = ReplaceWith("Option.Conflicts", "org.taktik.couchdb.entity.Option")
        )
        val CONFLICTS = Conflicts
        @Deprecated(
            message = "Replaced by data object",
            replaceWith = ReplaceWith("Option.RevisionsInfo", "org.taktik.couchdb.entity.Option")
        )
        val REVISIONS_INFO = RevisionsInfo
        @Deprecated(
            message = "Replaced by data object",
            replaceWith = ReplaceWith("Option.Attachments", "org.taktik.couchdb.entity.Option")
        )
        val ATTACHMENTS = Attachments
    }
    data object Conflicts : Option {
        override val queryParamName: String
            get() = "conflicts"
        override val queryParamValue: String
            get() = "true"
    }

    data object RevisionsInfo : Option {
        override val queryParamName: String
            get() = "revs_info"
        override val queryParamValue: String
            get() = "true"
    }

    data object Attachments : Option {
        override val queryParamName: String
            get() = "attachments"
        override val queryParamValue: String
            get() = "true"
    }
}
