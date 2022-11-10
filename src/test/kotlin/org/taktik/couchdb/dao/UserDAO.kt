package org.taktik.couchdb.dao

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.taktik.couchdb.User
import org.taktik.couchdb.annotation.View

@View(name = "all", map = "function(doc) { if (doc.java_type == 'org.taktik.icure.entities.User' && !doc.deleted) emit( null, doc._rev )}")
class UserDAO() {
    @View(name = "by_exp_date", map = "function(doc) {  if (doc.java_type == 'org.taktik.icure.entities.User' && !doc.deleted && doc.expirationDate) {emit(doc.expirationDate.epochSecond, doc._id)  }}")
    fun getExpiredUsers(): Flow<User> = flow {
        User(id="test")
    }

    @View(name = "by_username", map = "function(doc) {  if (doc.java_type == 'org.taktik.icure.entities.User' && !doc.deleted) {emit(doc.login, null)}}")
    fun listUsersByUsername(): Flow<User> = flow {
        User(id="test")
    }

    @View(name = "by_email", map = "function(doc) {  if (doc.java_type == 'org.taktik.icure.entities.User' && !doc.deleted && doc.email) {emit(doc.email, null)}}")
    fun listUsersByEmail(): Flow<User> = flow {
        User(id="test")
    }

    @View(name = "by_phone", map = "classpath:js/user/By_phone.js")
    fun listUsersByPhone(): Flow<User> = flow {
        User(id="test")
    }

    /**
     * startKey in pagination is the email of the patient.
     */
    @View(name = "allForPagination", map = "map = function (doc) { if (doc.java_type == 'org.taktik.icure.entities.User' && !doc.deleted) { emit(doc.login, null); }};")
    fun findUsers(): Flow<User> = flow {
        User(id="test")
    }

    @View(name = "by_hcp_id", map = "classpath:js/user/by_hcp_id.js")
    fun listUsersByHcpId(): Flow<User> = flow {
        User(id="test")
    }

    @View(name = "by_patient_id", map = "classpath:js/user/by_patient_id.js")
    fun listUsersByPatientId(patientId: String): Flow<User> = flow {
        User(id="test")
    }

    @View(name = "by_name_email_phone", map = "classpath:js/user/By_name_email_phone.js")
    fun listUserIdsByNameEmailPhone(): Flow<User> = flow {
        User(id="test")
    }

}